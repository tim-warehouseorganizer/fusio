package dev.flamelens.fusio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Off-heap terminals: run a byte pipeline and land the output in native
 * memory without a heap detour. The idiomatic java.io route
 * ({@code in.readAllBytes()} then copy to a segment) allocates the entire
 * payload on the heap first; this collects into off-heap blocks as chunks
 * arrive, then gathers into one exactly-sized segment in the caller's Arena.
 * Peak transient memory is off-heap and bounded; steady-state heap allocation
 * is near zero. This is the substrate for feeding native runtimes (ONNX,
 * DJL, ...) without GC pressure.
 */
public final class Segments {

    private static final int FIRST_BLOCK = 256 * 1024;
    private static final int MAX_BLOCK = 8 * 1024 * 1024;

    private Segments() {
    }

    /** Run the pipeline and collect its output bytes into a segment allocated in {@code arena}. */
    public static MemorySegment collect(Pipeline<Bytes> pipeline, Arena arena) {
        try (Arena scratch = Arena.ofConfined()) {
            List<MemorySegment> blocks = new ArrayList<>();
            long[] used = {0};   // bytes used in the current (last) block
            long[] total = {0};

            pipeline.forEach(b -> {
                int off = b.offset;
                int remaining = b.length;
                while (remaining > 0) {
                    MemorySegment block = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
                    if (block == null || used[0] == block.byteSize()) {
                        long nextSize = block == null ? FIRST_BLOCK
                                : Math.min(block.byteSize() * 2, MAX_BLOCK);
                        block = scratch.allocate(nextSize);
                        blocks.add(block);
                        used[0] = 0;
                    }
                    int n = (int) Math.min(remaining, block.byteSize() - used[0]);
                    MemorySegment.copy(b.array, off, block, java.lang.foreign.ValueLayout.JAVA_BYTE, used[0], n);
                    used[0] += n;
                    total[0] += n;
                    off += n;
                    remaining -= n;
                }
            });

            MemorySegment result = arena.allocate(total[0]);
            long pos = 0;
            for (int i = 0; i < blocks.size(); i++) {
                MemorySegment block = blocks.get(i);
                long n = (i == blocks.size() - 1) ? used[0] : block.byteSize();
                MemorySegment.copy(block, 0, result, pos, n);
                pos += n;
            }
            return result;
        }
    }

    /**
     * Run the pipeline into a caller-provided segment (e.g. a mapped file or a
     * pre-sized tensor buffer). Returns bytes written; throws if the pipeline
     * produces more than {@code target} can hold.
     */
    public static long collectInto(Pipeline<Bytes> pipeline, MemorySegment target) {
        long[] pos = {0};
        pipeline.forEach(b -> {
            if (pos[0] + b.length > target.byteSize()) {
                throw new UncheckedIOException(
                        new IOException("pipeline output exceeds target segment size " + target.byteSize()));
            }
            MemorySegment.copy(b.array, b.offset, target, java.lang.foreign.ValueLayout.JAVA_BYTE, pos[0], b.length);
            pos[0] += b.length;
        });
        return pos[0];
    }
}
