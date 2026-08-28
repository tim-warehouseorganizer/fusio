package dev.flamelens.fusio.interop;

import dev.flamelens.fusio.Sink;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Streams rows as UTF-8 RFC 4180 CSV bytes, produced lazily one row at a
 * time — rows are only pulled from the iterator as the consumer reads, so a
 * million-row source never materializes as text. This is the bridge that
 * feeds any InputStream-shaped bulk API: MySQL {@code LOAD DATA LOCAL
 * INFILE}, Postgres {@code COPY FROM STDIN}, S3 uploads, servlet responses.
 */
public final class CsvInputStream extends InputStream {

    private final Iterator<String[]> rows;
    private final Sink<String[]> head;
    private final ArrayDeque<byte[]> out = new ArrayDeque<>();
    private byte[] current;
    private int pos;
    private boolean ended = false;

    public CsvInputStream(Iterator<String[]> rows) {
        this(rows, ',', false);
    }

    public CsvInputStream(Iterator<String[]> rows, char delimiter, boolean crlf) {
        this(rows, delimiter, crlf, Csv.Escaping.RFC4180);
    }

    /**
     * @param escaping how null fields are written. RFC 4180 cannot express null at all, so a bulk
     *                 loader that needs real nulls must pass the dialect its target expects -
     *                 {@link Csv.Escaping#MYSQL_LOAD_DATA} or {@link Csv.Escaping#POSTGRES_COPY}.
     */
    public CsvInputStream(Iterator<String[]> rows, char delimiter, boolean crlf, Csv.Escaping escaping) {
        this.rows = rows;
        this.head = Csv.format(delimiter, crlf, escaping).then(Utf8.encode()).connect(b -> {
            if (b.length > 0) {
                out.add(Arrays.copyOfRange(b.array, b.offset, b.offset + b.length));
            }
            return true;
        });
    }

    @Override
    public int read() throws IOException {
        if (!ensure()) {
            return -1;
        }
        int b = current[pos++] & 0xff;
        if (pos == current.length) {
            current = null;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!ensure()) {
            return -1;
        }
        int n = Math.min(len, current.length - pos);
        System.arraycopy(current, pos, b, off, n);
        pos += n;
        if (pos == current.length) {
            current = null;
        }
        return n;
    }

    private boolean ensure() throws IOException {
        while (current == null) {
            if (!out.isEmpty()) {
                current = out.poll();
                pos = 0;
                return true;
            }
            if (ended) {
                return false;
            }
            if (rows.hasNext()) {
                head.accept(rows.next());
            } else {
                ended = true;
                head.end();
            }
        }
        return true;
    }
}
