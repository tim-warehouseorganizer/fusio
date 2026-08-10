package dev.flamelens.fusio.pipes;

/**
 * Parsing dialect for {@link Csv#parse(CsvDialect)}. The default is strict
 * RFC 4180; named presets reproduce the behaviors of the libraries people
 * migrate from, so adoption can be behavior-identical first and modernized
 * second — one line to switch, one line to revert.
 *
 * <ul>
 *   <li>{@link #rfc4180()} — the fusio default: no escape character
 *       (backslash is data), quotes escaped by doubling, whitespace is data,
 *       lenient about unterminated quotes, CR/LF preserved inside quotes.</li>
 *   <li>{@link #opencsvLegacy()} — opencsv's default CSVParser dialect:
 *       backslash escapes ({@code \"}, {@code \\}, {@code \,}), carriage
 *       returns stripped inside quoted fields (opencsv's
 *       {@code keepCarriageReturn=false}), and an unterminated quote at end
 *       of input is an error. (opencsv's separator-adjacency quote heuristic
 *       is deliberately NOT reproduced — it silently drops characters.)</li>
 *   <li>{@link #jackson()} — jackson-dataformat-csv defaults: spaces around
 *       a quoted field are skipped rather than treated as data, and an
 *       unterminated quote at end of input is an error.</li>
 * </ul>
 */
public final class CsvDialect {

    public static final char NO_ESCAPE = '\0';

    final char delimiter;
    final char escapeChar;
    final boolean errorOnUnterminatedQuote;
    final boolean trimAroundQuotes;
    final boolean stripCarriageReturnsInQuotes;

    private CsvDialect(char delimiter, char escapeChar, boolean errorOnUnterminatedQuote,
                       boolean trimAroundQuotes, boolean stripCarriageReturnsInQuotes) {
        if (delimiter == '"' || delimiter == '\n' || delimiter == '\r') {
            throw new IllegalArgumentException("illegal delimiter: " + delimiter);
        }
        if (escapeChar != NO_ESCAPE && (escapeChar == delimiter || escapeChar == '"'
                || escapeChar == '\n' || escapeChar == '\r')) {
            throw new IllegalArgumentException("illegal escape char: " + escapeChar);
        }
        this.delimiter = delimiter;
        this.escapeChar = escapeChar;
        this.errorOnUnterminatedQuote = errorOnUnterminatedQuote;
        this.trimAroundQuotes = trimAroundQuotes;
        this.stripCarriageReturnsInQuotes = stripCarriageReturnsInQuotes;
    }

    public static CsvDialect rfc4180() {
        return new CsvDialect(',', NO_ESCAPE, false, false, false);
    }

    public static CsvDialect opencsvLegacy() {
        return new CsvDialect(',', '\\', true, false, true);
    }

    public static CsvDialect jackson() {
        return new CsvDialect(',', NO_ESCAPE, true, true, false);
    }

    public CsvDialect withDelimiter(char delimiter) {
        return new CsvDialect(delimiter, escapeChar, errorOnUnterminatedQuote,
                trimAroundQuotes, stripCarriageReturnsInQuotes);
    }

    /** {@link #NO_ESCAPE} disables escaping (RFC 4180). */
    public CsvDialect withEscapeChar(char escapeChar) {
        return new CsvDialect(delimiter, escapeChar, errorOnUnterminatedQuote,
                trimAroundQuotes, stripCarriageReturnsInQuotes);
    }

    public CsvDialect withErrorOnUnterminatedQuote(boolean error) {
        return new CsvDialect(delimiter, escapeChar, error,
                trimAroundQuotes, stripCarriageReturnsInQuotes);
    }

    public CsvDialect withTrimAroundQuotes(boolean trim) {
        return new CsvDialect(delimiter, escapeChar, errorOnUnterminatedQuote,
                trim, stripCarriageReturnsInQuotes);
    }

    public CsvDialect withStripCarriageReturnsInQuotes(boolean strip) {
        return new CsvDialect(delimiter, escapeChar, errorOnUnterminatedQuote,
                trimAroundQuotes, strip);
    }
}
