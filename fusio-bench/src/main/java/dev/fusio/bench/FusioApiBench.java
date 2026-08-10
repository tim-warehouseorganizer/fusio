package dev.fusio.bench;

import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Lines;
import dev.flamelens.fusio.pipes.Utf8;
import org.openjdk.jmh.annotations.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The test that matters: does the real fusio API keep the wins the hand-fused
 * loops showed, or does the abstraction eat them? Same 16MB text corpus as
 * TextPipelineBench, decorator baseline included for a same-run comparison.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FusioApiBench {

    byte[] data;

    @Setup
    public void setup() {
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
        data = sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Baseline: the java.io way, identical work (decode + line Strings). */
    @Benchmark
    public long decoratorReadLine() throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            long lines = 0, chars = 0;
            String line;
            while ((line = br.readLine()) != null) {
                lines++;
                chars += line.length();
            }
            return lines * 31 + chars;
        }
    }

    /** fusio, identical work: decode + line Strings + fold. */
    @Benchmark
    public long fusioLines() {
        return ByteSource.of(data)
                .via(Utf8.decode())
                .via(Lines.split())
                .foldLong(0L, (acc, line) -> acc + 31 + line.length());
    }

    /** Profile-driven: byte-level line splitting, decode skipped entirely (sound for UTF-8). */
    @Benchmark
    public long fusioByteLineViews() {
        return ByteSource.of(data)
                .via(Lines.bytes())
                .foldLong(0L, (acc, b) -> acc + 31 + b.length);
    }

    /** fusio, per-line granularity but zero-allocation views instead of Strings. */
    @Benchmark
    public long fusioLineViews() {
        return ByteSource.of(data)
                .via(Utf8.decode())
                .via(Lines.views())
                .foldLong(0L, (acc, c) -> acc + 31 + c.length);
    }

    /** fusio, same decode work but no String materialization — the API lets you skip it. */
    @Benchmark
    public long fusioCharScan() {
        return ByteSource.of(data)
                .via(Utf8.decode())
                .foldLong(0L, (acc, c) -> {
                    long lines = 0, chars = 0;
                    for (int i = c.offset; i < c.offset + c.length; i++) {
                        if (c.array[i] == '\n') lines++; else chars++;
                    }
                    return acc + lines * 31 + chars;
                });
    }

    /** fusio ceiling: fold over raw bytes, decode stage dropped entirely. */
    @Benchmark
    public long fusioByteScan() {
        return ByteSource.of(data)
                .foldLong(0L, (acc, b) -> {
                    long lines = 0, bytes = 0;
                    for (int i = b.offset; i < b.offset + b.length; i++) {
                        if (b.array[i] == '\n') lines++; else bytes++;
                    }
                    return acc + lines * 31 + bytes;
                });
    }
}
