package dev.fusio.bench;

import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.Mmap;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Newline count over a real 64MB file (page-cache hot after warmup), three
 * tiers: buffered stream reads, mmap with a copy into a reused heap chunk,
 * and zero-copy folding over mapped MemorySegment slices — the 1BRC path.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MmapBench {

    Path file;

    @Setup
    public void setup() throws IOException {
        file = Files.createTempFile("fusio-mmap-bench", ".txt");
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder(4 * 1024 * 1024);
        String[] words = {"request", "handled", "in", "ms", "user", "session", "token", "cache"};
        while (sb.length() < 4 * 1024 * 1024) {
            int wordsInLine = 4 + r.nextInt(12);
            for (int i = 0; i < wordsInLine; i++) {
                sb.append(words[r.nextInt(words.length)]);
                sb.append(i == wordsInLine - 1 ? '\n' : ' ');
            }
        }
        byte[] block = sb.toString().getBytes(StandardCharsets.UTF_8);
        try (var out = Files.newOutputStream(file)) {
            for (int i = 0; i < 16; i++) { // ~64MB
                out.write(block);
            }
        }
    }

    @TearDown
    public void tearDown() throws IOException {
        Files.deleteIfExists(file);
    }

    @Benchmark
    public long streamScan() {
        return ByteSource.file(file).foldLong(0L, (acc, b) -> {
            long n = acc;
            for (int i = b.offset; i < b.offset + b.length; i++) {
                if (b.array[i] == '\n') n++;
            }
            return n;
        });
    }

    @Benchmark
    public long mmapCopyScan() {
        return Mmap.byteSource(file).foldLong(0L, (acc, b) -> {
            long n = acc;
            for (int i = b.offset; i < b.offset + b.length; i++) {
                if (b.array[i] == '\n') n++;
            }
            return n;
        });
    }

    @Benchmark
    public long segmentScan() {
        return Mmap.foldLong(file, 1 << 20, 0L, (acc, seg) -> {
            long n = acc;
            long size = seg.byteSize();
            for (long i = 0; i < size; i++) {
                if (seg.get(ValueLayout.JAVA_BYTE, i) == '\n') n++;
            }
            return n;
        });
    }

    /** The actual 1BRC idiom: 8 bytes per read, SWAR zero-byte detection. */
    @Benchmark
    public long segmentScanSwar() {
        return Mmap.foldLong(file, 1 << 20, 0L, (acc, seg) -> {
            long n = acc;
            long size = seg.byteSize();
            long i = 0;
            for (; i + 8 <= size; i += 8) {
                long v = seg.get(ValueLayout.JAVA_LONG_UNALIGNED, i) ^ 0x0A0A0A0A0A0A0A0AL;
                long hit = (v - 0x0101010101010101L) & ~v & 0x8080808080808080L;
                n += Long.bitCount(hit); // one high bit per '\n' byte
            }
            for (; i < size; i++) {
                if (seg.get(ValueLayout.JAVA_BYTE, i) == '\n') n++;
            }
            return n;
        });
    }
}
