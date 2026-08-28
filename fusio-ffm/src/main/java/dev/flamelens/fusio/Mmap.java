package dev.flamelens.fusio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped file sources (FFM API). Two tiers:
 *
 * <ul>
 *   <li>{@link #byteSource} — interop tier: chunks are copied from the mapping
 *       into a reused heap buffer so every existing pipe works unchanged.</li>
 *   <li>{@link #foldLong} — zero-copy tier: fold directly over
 *       {@link MemorySegment} slices of the mapping. No heap copies at all;
 *       this is the 1BRC-style path for scanning huge files.</li>
 * </ul>
 *
 * The mapping lives in a confined Arena bracketed by the run, so it is
 * unmapped deterministically instead of waiting for GC.
 */
public final class Mmap {

    private Mmap() {
    }

    public static ByteSource byteSource(Path path) {
        return byteSource(path, ByteSource.DEFAULT_CHUNK);
    }

    public static ByteSource byteSource(Path path, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        return new ByteSource() {
            @Override
            public void drainTo(Sink<Bytes> sink) throws IOException {
                try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
                     Arena arena = Arena.ofConfined()) {
                    long size = ch.size();
                    if (size == 0) {
                        return;
                    }
                    MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
                    byte[] buf = new byte[chunkSize];
                    MemorySegment heap = MemorySegment.ofArray(buf);
                    for (long off = 0; off < size; off += chunkSize) {
                        int len = (int) Math.min(chunkSize, size - off);
                        MemorySegment.copy(seg, off, heap, 0, len);
                        if (!sink.accept(new Bytes(buf, 0, len))) {
                            return;
                        }
                    }
                }
            }
        };
    }

    /** Zero-copy fold over mapped slices. Slices are valid only during the fold step. */
    public static long foldLong(Path path, long chunkSize, long seed, LongFolder<MemorySegment> f) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {
            long size = ch.size();
            long acc = seed;
            if (size > 0) {
                MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
                for (long off = 0; off < size; off += chunkSize) {
                    long len = Math.min(chunkSize, size - off);
                    acc = f.apply(acc, seg.asSlice(off, len));
                }
            }
            return acc;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
