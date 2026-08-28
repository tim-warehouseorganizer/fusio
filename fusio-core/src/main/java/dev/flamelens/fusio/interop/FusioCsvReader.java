package dev.flamelens.fusio.interop;

import dev.flamelens.fusio.Bytes;
import dev.flamelens.fusio.Chars;
import dev.flamelens.fusio.Sink;
import dev.flamelens.fusio.pipes.Bom;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.CsvDialect;
import dev.flamelens.fusio.pipes.Utf8;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Drop-in replacement for opencsv's {@code CSVReader} usage shape, backed by
 * fusio's fused RFC 4180 parser. Migration is a construction-line change:
 *
 * <pre>{@code
 * // before                                   // after
 * CSVReader r = new CSVReader(reader);        FusioCsvReader r = new FusioCsvReader(reader);
 * String[] row;                               String[] row;
 * while ((row = r.readNext()) != null) ...    while ((row = r.readNext()) != null) ...
 * }</pre>
 *
 * Rows are produced lazily: input is pulled one chunk at a time and parsed
 * rows are buffered only until consumed, so memory stays bounded regardless
 * of file size. The {@link #of(Path)} and {@link #of(InputStream)} factories
 * skip the Reader layer entirely (bytes -> BOM strip -> UTF-8 decode -> CSV
 * in one fused pass) and are the faster choice when you control the source.
 */
public final class FusioCsvReader implements Closeable, Iterable<String[]> {

    private static final int CHUNK = 8192;

    private final ArrayDeque<String[]> rows = new ArrayDeque<>();
    private final Feeder feeder;
    private boolean eof = false;

    private interface Feeder extends Closeable {
        /** Pull one chunk into the pipeline; return false at end of input. */
        boolean feed() throws IOException;
    }

    /** opencsv-compatible constructor: parse chars from an existing Reader. */
    public FusioCsvReader(Reader reader) {
        this(reader, ',');
    }

    public FusioCsvReader(Reader reader, char delimiter) {
        this(reader, CsvDialect.rfc4180().withDelimiter(delimiter));
    }

    /** Parse with a specific {@link CsvDialect} (e.g. {@code CsvDialect.opencsvLegacy()} during migration). */
    public FusioCsvReader(Reader reader, CsvDialect dialect) {
        Sink<Chars> sink = Csv.parse(dialect).connect(this::collect);
        char[] buf = new char[CHUNK];
        this.feeder = new Feeder() {
            @Override
            public boolean feed() throws IOException {
                int n = reader.read(buf);
                if (n == -1) {
                    sink.end();
                    return false;
                }
                if (n > 0) {
                    sink.accept(new Chars(buf, 0, n));
                }
                return true;
            }

            @Override
            public void close() throws IOException {
                reader.close();
            }
        };
    }

    private FusioCsvReader(InputStream in, CsvDialect dialect, boolean closeUnderlying) {
        Sink<Bytes> sink = Bom.strip()
                .then(Utf8.decode())
                .then(Csv.parse(dialect))
                .connect(this::collect);
        byte[] buf = new byte[CHUNK];
        this.feeder = new Feeder() {
            @Override
            public boolean feed() throws IOException {
                int n = in.read(buf);
                if (n == -1) {
                    sink.end();
                    return false;
                }
                if (n > 0) {
                    sink.accept(new Bytes(buf, 0, n));
                }
                return true;
            }

            @Override
            public void close() throws IOException {
                if (closeUnderlying) {
                    in.close();
                }
            }
        };
    }

    /** Fused byte-level reader for a UTF-8 file (BOM tolerated). */
    public static FusioCsvReader of(Path path) throws IOException {
        return new FusioCsvReader(Files.newInputStream(path), CsvDialect.rfc4180(), true);
    }

    public static FusioCsvReader of(Path path, CsvDialect dialect) throws IOException {
        return new FusioCsvReader(Files.newInputStream(path), dialect, true);
    }

    /** Fused byte-level reader over a UTF-8 stream; the caller keeps ownership of the stream. */
    public static FusioCsvReader of(InputStream in) {
        return new FusioCsvReader(in, CsvDialect.rfc4180(), false);
    }

    public static FusioCsvReader of(InputStream in, CsvDialect dialect) {
        return new FusioCsvReader(in, dialect, false);
    }

    public static FusioCsvReader of(InputStream in, char delimiter) {
        return new FusioCsvReader(in, CsvDialect.rfc4180().withDelimiter(delimiter), false);
    }

    private boolean collect(String[] row) {
        rows.add(row);
        return true;
    }

    /** All remaining rows — opencsv's {@code readAll()} shape for drop-in migration. */
    public java.util.List<String[]> readAll() throws IOException {
        java.util.List<String[]> all = new java.util.ArrayList<>();
        String[] row;
        while ((row = readNext()) != null) {
            all.add(row);
        }
        return all;
    }

    /** Next row, or {@code null} at end of input — opencsv semantics. */
    public String[] readNext() throws IOException {
        while (rows.isEmpty() && !eof) {
            if (!feeder.feed()) {
                eof = true;
            }
        }
        return rows.poll();
    }

    @Override
    public Iterator<String[]> iterator() {
        return new Iterator<String[]>() {
            private String[] next;

            @Override
            public boolean hasNext() {
                if (next == null) {
                    try {
                        next = readNext();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return next != null;
            }

            @Override
            public String[] next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                String[] row = next;
                next = null;
                return row;
            }
        };
    }

    @Override
    public void close() throws IOException {
        feeder.close();
    }
}
