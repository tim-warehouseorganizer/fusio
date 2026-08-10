package dev.flamelens.fusio.pipes;

import dev.flamelens.fusio.Chars;
import dev.flamelens.fusio.Pipe;
import dev.flamelens.fusio.Sink;

import java.io.IOException;
import java.util.Arrays;

/**
 * RFC 4180 CSV parsing as a single fused pass: quoted fields, {@code ""}
 * escapes, delimiters/newlines inside quotes, {@code \r\n} row endings, and
 * fields split across chunk boundaries. Unquoted stretches are scanned and
 * bulk-copied rather than handled char-by-char. Emits one {@code String[]}
 * per row; a blank line is a row with one empty field.
 *
 * <p>Parsing behavior is controlled by {@link CsvDialect}: the default is
 * strict RFC 4180, and named presets reproduce opencsv's and Jackson's
 * dialects for behavior-identical migration. {@link CsvCascade} tries
 * dialects in order, falling through on detected mismatches.
 */
public final class Csv {

    private Csv() {
    }

    public static Pipe<Chars, String[]> parse() {
        return parse(CsvDialect.rfc4180());
    }

    public static Pipe<Chars, String[]> parse(char delimiter) {
        return parse(CsvDialect.rfc4180().withDelimiter(delimiter));
    }

    public static Pipe<Chars, String[]> parse(CsvDialect dialect) {
        return down -> new CsvSink(down, dialect, false);
    }

    /** Cascade tiers parse with anomaly detection on: dialect-ambiguous input throws. */
    static Pipe<Chars, String[]> parseDetecting(CsvDialect dialect) {
        return down -> new CsvSink(down, dialect, true);
    }

    /**
     * The write-side mirror of {@link #parse()}: rows to RFC 4180 text. Fields
     * are quoted only when needed (delimiter, quote, CR or LF present); quotes
     * are doubled; rows end with {@code \n}; null fields become empty. Feed
     * the output through {@link Utf8#encode()} for bytes.
     */
    public static Pipe<String[], Chars> format() {
        return format(',', false);
    }

    public static Pipe<String[], Chars> format(char delimiter) {
        return format(delimiter, false);
    }

    /** @param crlf terminate rows with {@code \r\n} (RFC 4180's preference) instead of {@code \n} */
    public static Pipe<String[], Chars> format(char delimiter, boolean crlf) {
        if (delimiter == '"' || delimiter == '\n' || delimiter == '\r') {
            throw new IllegalArgumentException("illegal delimiter: " + delimiter);
        }
        return down -> new FormatSink(down, delimiter, crlf);
    }

    /** One-shot convenience: format rows to a CSV string in memory. */
    public static String text(java.util.List<String[]> rows) {
        return text(rows, ',', false);
    }

    public static String text(java.util.List<String[]> rows, char delimiter, boolean crlf) {
        StringBuilder sb = new StringBuilder();
        try {
            Sink<String[]> sink = format(delimiter, crlf).connect(c -> {
                sb.append(c.array, c.offset, c.length);
                return true;
            });
            for (String[] row : rows) {
                sink.accept(row);
            }
            sink.end();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e); // unreachable: in-memory sink
        }
        return sb.toString();
    }

    private static final class CsvSink implements Sink<Chars> {
        private static final int START = 0;     // at the beginning of a field
        private static final int UNQUOTED = 1;
        private static final int QUOTED = 2;
        private static final int QUOTE_Q = 3;   // saw a quote inside a quoted field

        private final Sink<String[]> down;
        private final char delim;
        private final char esc;
        private final boolean hasEsc;
        private final boolean errorUnterminated;
        private final boolean trim;
        private final boolean stripCr;
        private final boolean strict;           // cascade detection mode

        private char[] field = new char[64];
        private int fieldLen = 0;
        private String[] row = new String[8];
        private int rowLen = 0;
        private int state = START;
        private boolean skipLF = false;
        private boolean stopped = false;
        private int pendingSpaces = 0;          // spaces held at field start (trim/strict modes)
        private boolean pendingEscape = false;  // escape char was the last char of a chunk

        CsvSink(Sink<String[]> down, CsvDialect dialect, boolean strict) {
            this.down = down;
            this.delim = dialect.delimiter;
            this.esc = dialect.escapeChar;
            this.hasEsc = dialect.escapeChar != CsvDialect.NO_ESCAPE;
            this.errorUnterminated = dialect.errorOnUnterminatedQuote;
            this.trim = dialect.trimAroundQuotes;
            this.stripCr = dialect.stripCarriageReturnsInQuotes;
            this.strict = strict;
        }

        @Override
        public boolean accept(Chars c) throws IOException {
            if (stopped) {
                return false;
            }
            char[] a = c.array;
            int i = c.offset;
            int end = c.offset + c.length;
            while (i < end) {
                char ch = a[i];
                if (skipLF) {
                    skipLF = false;
                    if (ch == '\n') {
                        i++;
                        continue;
                    }
                }
                if (pendingEscape) {
                    pendingEscape = false;
                    if (escapable(ch)) {
                        appendChar(ch);
                        i++;
                        continue;
                    }
                    appendChar(esc); // lone escape char is literal; reprocess ch normally
                    ch = a[i];
                }
                switch (state) {
                    case START:
                        if (ch == '"') {
                            if (pendingSpaces > 0) {
                                if (strict && !trim) {
                                    throw mismatch("space before a quoted field");
                                }
                                pendingSpaces = 0; // trim mode: spaces around quotes are skipped
                            }
                            state = QUOTED;
                            i++;
                        } else if (ch == delim) {
                            flushPendingSpaces();
                            endField();
                            i++;
                        } else if (ch == '\n') {
                            flushPendingSpaces();
                            if (!endRow()) {
                                return false;
                            }
                            i++;
                        } else if (ch == '\r') {
                            flushPendingSpaces();
                            if (!endRow()) {
                                return false;
                            }
                            skipLF = true;
                            i++;
                        } else if (ch == ' ' && (trim || strict)) {
                            pendingSpaces++;
                            i++;
                        } else {
                            flushPendingSpaces();
                            state = UNQUOTED; // reprocessed by the scan below
                        }
                        break;
                    case UNQUOTED: {
                        if (hasEsc || strict) {
                            while (i < end) {
                                char d = a[i];
                                if (d == delim || d == '\n' || d == '\r') {
                                    break;
                                }
                                if (strict && d == '"') {
                                    throw mismatch("quote inside an unquoted field");
                                }
                                if (hasEsc && d == esc) {
                                    if (i + 1 < end) {
                                        char nx = a[i + 1];
                                        if (nx == '"' || nx == esc || nx == delim) {
                                            appendChar(nx);
                                            i += 2;
                                        } else {
                                            appendChar(esc);
                                            i++;
                                        }
                                    } else {
                                        pendingEscape = true;
                                        i++;
                                    }
                                } else {
                                    appendChar(d);
                                    i++;
                                }
                            }
                        } else {
                            int s = i;
                            while (i < end) {
                                char d = a[i];
                                if (d == delim || d == '\n' || d == '\r') {
                                    break;
                                }
                                i++;
                            }
                            appendRange(a, s, i - s);
                        }
                        if (i < end && !pendingEscape) {
                            char d = a[i];
                            if (d == delim) {
                                endField();
                            } else {
                                if (!endRow()) {
                                    return false;
                                }
                                if (d == '\r') {
                                    skipLF = true;
                                }
                            }
                            state = START;
                            i++;
                        }
                        break;
                    }
                    case QUOTED: {
                        if (hasEsc || stripCr) {
                            while (i < end) {
                                char d = a[i];
                                if (d == '"') {
                                    state = QUOTE_Q;
                                    i++;
                                    break;
                                }
                                if (hasEsc && d == esc) {
                                    if (i + 1 < end) {
                                        char nx = a[i + 1];
                                        if (nx == '"' || nx == esc) {
                                            appendChar(nx);
                                            i += 2;
                                        } else {
                                            appendChar(esc);
                                            i++;
                                        }
                                    } else {
                                        pendingEscape = true;
                                        i++;
                                        break;
                                    }
                                } else if (stripCr && d == '\r') {
                                    i++;
                                } else {
                                    appendChar(d);
                                    i++;
                                }
                            }
                        } else {
                            int s = i;
                            while (i < end && a[i] != '"') {
                                i++;
                            }
                            appendRange(a, s, i - s);
                            if (i < end) {
                                state = QUOTE_Q;
                                i++;
                            }
                        }
                        break;
                    }
                    case QUOTE_Q:
                        if (ch == '"') {
                            appendChar('"');
                            state = QUOTED;
                            i++;
                        } else if (ch == delim) {
                            endField();
                            state = START;
                            i++;
                        } else if (ch == '\n') {
                            if (!endRow()) {
                                return false;
                            }
                            state = START;
                            i++;
                        } else if (ch == '\r') {
                            if (!endRow()) {
                                return false;
                            }
                            state = START;
                            skipLF = true;
                            i++;
                        } else if (ch == ' ' && trim) {
                            i++; // Jackson: spaces after a closing quote are skipped
                        } else {
                            if (strict) {
                                throw mismatch("data after a closing quote");
                            }
                            // tolerate; keep the quote and the character as
                            // literal data (no data loss), with delimiters and
                            // newlines still terminating normally
                            appendChar('"');
                            appendChar(ch);
                            state = UNQUOTED;
                            i++;
                        }
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
            return true;
        }

        @Override
        public void end() throws IOException {
            if (!stopped) {
                if (pendingEscape) {
                    pendingEscape = false;
                    appendChar(esc);
                }
                if (state == QUOTED && (errorUnterminated || strict)) {
                    throw mismatch("unterminated quoted field at end of input");
                }
                flushPendingSpaces();
                if (fieldLen > 0 || rowLen > 0 || state == QUOTED || state == QUOTE_Q) {
                    endRow();
                }
            }
            down.end();
        }

        private boolean escapable(char ch) {
            if (ch == '"' || ch == esc) {
                return true;
            }
            return state != QUOTED && ch == delim;
        }

        private CsvDialectMismatchException mismatch(String what) {
            return new CsvDialectMismatchException(what);
        }

        private void flushPendingSpaces() {
            while (pendingSpaces > 0) {
                appendChar(' ');
                pendingSpaces--;
            }
        }

        private void endField() {
            if (rowLen == row.length) {
                row = Arrays.copyOf(row, row.length * 2);
            }
            row[rowLen++] = new String(field, 0, fieldLen);
            fieldLen = 0;
        }

        private boolean endRow() throws IOException {
            endField();
            String[] cells = Arrays.copyOf(row, rowLen);
            rowLen = 0;
            state = START;
            if (!down.accept(cells)) {
                stopped = true;
                return false;
            }
            return true;
        }

        private void appendRange(char[] a, int from, int len) {
            if (len == 0) {
                return;
            }
            ensure(len);
            System.arraycopy(a, from, field, fieldLen, len);
            fieldLen += len;
        }

        private void appendChar(char ch) {
            ensure(1);
            field[fieldLen++] = ch;
        }

        private void ensure(int extra) {
            if (fieldLen + extra > field.length) {
                field = Arrays.copyOf(field, Math.max(field.length * 2, fieldLen + extra));
            }
        }
    }

    private static final class FormatSink implements Sink<String[]> {
        private final Sink<Chars> down;
        private final char delim;
        private final boolean crlf;
        private final char[] buf = new char[16 * 1024];
        private int len = 0;
        private boolean stopped = false;

        FormatSink(Sink<Chars> down, char delim, boolean crlf) {
            this.down = down;
            this.delim = delim;
            this.crlf = crlf;
        }

        @Override
        public boolean accept(String[] row) throws IOException {
            if (stopped) {
                return false;
            }
            for (int i = 0; i < row.length; i++) {
                if (i > 0 && !append(delim)) {
                    return false;
                }
                String fieldValue = row[i] == null ? "" : row[i];
                if (needsQuoting(fieldValue)) {
                    if (!append('"')) {
                        return false;
                    }
                    for (int k = 0; k < fieldValue.length(); k++) {
                        char ch = fieldValue.charAt(k);
                        if (ch == '"' && !append('"')) {
                            return false;
                        }
                        if (!append(ch)) {
                            return false;
                        }
                    }
                    if (!append('"')) {
                        return false;
                    }
                } else {
                    for (int k = 0; k < fieldValue.length(); k++) {
                        if (!append(fieldValue.charAt(k))) {
                            return false;
                        }
                    }
                }
            }
            if (crlf && !append('\r')) {
                return false;
            }
            return append('\n');
        }

        @Override
        public void end() throws IOException {
            if (!stopped && len > 0) {
                down.accept(new Chars(buf, 0, len));
                len = 0;
            }
            down.end();
        }

        private boolean needsQuoting(String fieldValue) {
            for (int k = 0; k < fieldValue.length(); k++) {
                char ch = fieldValue.charAt(k);
                if (ch == delim || ch == '"' || ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private boolean append(char ch) throws IOException {
            if (len == buf.length) {
                if (!down.accept(new Chars(buf, 0, len))) {
                    stopped = true;
                    return false;
                }
                len = 0;
            }
            buf[len++] = ch;
            return true;
        }
    }
}
