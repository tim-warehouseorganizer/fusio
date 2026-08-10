package dev.flamelens.fusio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A description of where bytes come from. Nothing is opened until a terminal
 * operation runs; resources are acquired and released inside {@link #drainTo}
 * (bracket semantics), so pipelines are safely reusable values.
 */
public abstract class ByteSource {

    public static final int DEFAULT_CHUNK = 64 * 1024;

    /** Push all chunks into {@code sink}, honoring early stop. Does not call {@link Sink#end}. */
    public abstract void drainTo(Sink<Bytes> sink) throws IOException;

    public static ByteSource file(Path path) {
        return file(path, DEFAULT_CHUNK);
    }

    public static ByteSource file(Path path, int chunkSize) {
        checkChunk(chunkSize);
        return new ByteSource() {
            @Override
            public void drainTo(Sink<Bytes> sink) throws IOException {
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] buf = new byte[chunkSize];
                    int n;
                    while ((n = in.read(buf, 0, chunkSize)) != -1) {
                        if (n > 0 && !sink.accept(new Bytes(buf, 0, n))) {
                            return;
                        }
                    }
                }
            }
        };
    }

    /** In-memory source; hands out slices of {@code data} without copying. */
    public static ByteSource of(byte[] data) {
        return of(data, DEFAULT_CHUNK);
    }

    public static ByteSource of(byte[] data, int chunkSize) {
        checkChunk(chunkSize);
        return new ByteSource() {
            @Override
            public void drainTo(Sink<Bytes> sink) throws IOException {
                for (int off = 0; off < data.length; off += chunkSize) {
                    int len = Math.min(chunkSize, data.length - off);
                    if (!sink.accept(new Bytes(data, off, len))) {
                        return;
                    }
                }
            }
        };
    }

    /**
     * Adapt an existing InputStream. The caller keeps ownership of the stream's
     * lifecycle; the pipeline reads it to exhaustion. For interop at library
     * boundaries (servlet request bodies, SDK responses).
     */
    public static ByteSource from(InputStream in, int chunkSize) {
        checkChunk(chunkSize);
        return new ByteSource() {
            @Override
            public void drainTo(Sink<Bytes> sink) throws IOException {
                byte[] buf = new byte[chunkSize];
                int n;
                while ((n = in.read(buf, 0, chunkSize)) != -1) {
                    if (n > 0 && !sink.accept(new Bytes(buf, 0, n))) {
                        return;
                    }
                }
            }
        };
    }

    public <B> Pipeline<B> via(Pipe<Bytes, B> pipe) {
        return new Pipeline<>(this, pipe);
    }

    /** The untransformed pipeline of raw chunks. */
    public Pipeline<Bytes> chunks() {
        return via(Pipe.identity());
    }

    /** Fold directly over raw chunks — the no-decode fast path. */
    public long foldLong(long seed, LongFolder<? super Bytes> f) {
        return chunks().foldLong(seed, f);
    }

    private static void checkChunk(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
    }
}
