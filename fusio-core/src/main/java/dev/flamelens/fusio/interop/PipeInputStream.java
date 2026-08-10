package dev.flamelens.fusio.interop;

import dev.flamelens.fusio.Bytes;
import dev.flamelens.fusio.Pipe;
import dev.flamelens.fusio.Sink;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Exposes a fusio byte pipeline as a plain {@link InputStream} — the
 * universal socket for feeding existing libraries. Anything that consumes an
 * InputStream (Jackson's ObjectMapper, file APIs, image decoders, ...) can
 * sit downstream of fusio stages:
 *
 * <pre>{@code
 * try (InputStream in = new PipeInputStream(httpBody, Gzip.gunzip())) {
 *     MyDto dto = objectMapper.readValue(in, MyDto.class);
 * }
 * }</pre>
 *
 * Transformation happens lazily, one source chunk at a time; output is
 * buffered only until consumed. Emitted chunks are copied once at this
 * boundary (pipeline buffers are reused, InputStream consumers may read at
 * their own pace) — the price of compatibility, paid only here.
 */
public final class PipeInputStream extends InputStream {

    private static final int CHUNK = 64 * 1024;

    private final InputStream src;
    private final Sink<Bytes> head;
    private final byte[] inBuf = new byte[CHUNK];
    private final ArrayDeque<byte[]> out = new ArrayDeque<>();
    private byte[] current;
    private int pos;
    private boolean eof = false;

    public PipeInputStream(InputStream src, Pipe<Bytes, Bytes> pipe) {
        this.src = src;
        this.head = pipe.connect(b -> {
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

    @Override
    public int available() {
        int n = current == null ? 0 : current.length - pos;
        for (byte[] chunk : out) {
            n += chunk.length;
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        src.close();
    }

    /** Make {@link #current} non-empty if any output remains; false at true EOF. */
    private boolean ensure() throws IOException {
        while (current == null) {
            if (!out.isEmpty()) {
                current = out.poll();
                pos = 0;
                return true;
            }
            if (eof) {
                return false;
            }
            int n = src.read(inBuf);
            if (n == -1) {
                eof = true;
                head.end();
            } else if (n > 0) {
                head.accept(new Bytes(inBuf, 0, n));
            }
        }
        return true;
    }
}
