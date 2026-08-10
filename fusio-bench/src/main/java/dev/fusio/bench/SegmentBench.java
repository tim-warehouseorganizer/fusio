package dev.fusio.bench;

import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.Segments;
import dev.flamelens.fusio.pipes.Gzip;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The ML-ingestion shape: decompress a 16MB payload into OFF-HEAP memory
 * (where a native runtime would read it). The idiomatic java.io route detours
 * the entire payload through the heap (readAllBytes) before copying out. The
 * fusio route streams pipeline chunks into off-heap blocks directly. Watch
 * gc.alloc.rate.norm, not just time — GC pressure is the metric that matters
 * at ingestion scale.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SegmentBench {

    byte[] gz;
    Arena reusedArena;
    MemorySegment reusedTarget;

    @Setup
    public void setup() throws IOException {
        reusedArena = Arena.ofShared();
        reusedTarget = reusedArena.allocate(17 * 1024 * 1024);
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder(17 * 1024 * 1024);
        String[] words = {"request", "handled", "in", "ms", "user", "session", "état", "größe", "token", "cache"};
        while (sb.length() < 16 * 1024 * 1024) {
            int wordsInLine = 4 + r.nextInt(12);
            for (int i = 0; i < wordsInLine; i++) {
                sb.append(words[r.nextInt(words.length)]);
                sb.append(i == wordsInLine - 1 ? '\n' : ' ');
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 * 1024 * 1024);
        try (GZIPOutputStream out = new GZIPOutputStream(bos, 64 * 1024)) {
            out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        gz = bos.toByteArray();
    }

    /** Idiomatic java.io: inflate to a heap byte[], then copy off-heap. */
    @Benchmark
    public long jdkHeapDetour() throws IOException {
        byte[] onHeap;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz), 64 * 1024)) {
            onHeap = in.readAllBytes();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(onHeap.length);
            MemorySegment.copy(onHeap, 0, seg, ValueLayout.JAVA_BYTE, 0, onHeap.length);
            return seg.byteSize();
        }
    }

    /** fusio: pipeline chunks land in off-heap blocks as they are produced. */
    @Benchmark
    public long fusioOffHeap() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Segments.collect(ByteSource.of(gz).via(Gzip.gunzip()), arena);
            return seg.byteSize();
        }
    }

    /** fusio with a pre-sized target (the ML case: tensor dims are known) — no gather copy. */
    @Benchmark
    public long fusioOffHeapPresized() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = arena.allocate(17 * 1024 * 1024);
            return Segments.collectInto(ByteSource.of(gz).via(Gzip.gunzip()), target);
        }
    }

    /**
     * The steady-state batch-ingestion shape: the target buffer is allocated
     * once and reused per batch. Profiling found ~9-13% of per-op time was the
     * JDK zero-filling fresh native memory (SegmentFactories.initNativeMemory)
     * that the pipeline immediately overwrites; reuse makes it a one-time cost.
     */
    @Benchmark
    public long fusioOffHeapReusedTarget() {
        return Segments.collectInto(ByteSource.of(gz).via(Gzip.gunzip()), reusedTarget);
    }

    @TearDown
    public void tearDown() {
        reusedArena.close();
    }
}
