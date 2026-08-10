package dev.fusio.bench;

import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Gzip;
import dev.flamelens.fusio.pipes.Lines;
import dev.flamelens.fusio.pipes.Utf8;
import org.openjdk.jmh.annotations.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The full classic stack: gunzip -> decode -> lines. java.io needs three
 * nested decorators (GZIPInputStream, InputStreamReader, BufferedReader);
 * fusio fuses the same stages into one pass. Both use the same Inflater
 * intrinsics underneath, so this isolates what the pipeline model itself buys
 * on a decompress-heavy workload. ~16MB of text, gzipped in setup.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GzipBench {

    byte[] gz;

    @Setup
    public void setup() throws IOException {
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
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        gz = bos.toByteArray();
    }

    /** The three-decorator tutorial stack, identical work (line Strings). */
    @Benchmark
    public long decoratorGzipReadLine() throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gz), 64 * 1024), StandardCharsets.UTF_8))) {
            long lines = 0, chars = 0;
            String line;
            while ((line = br.readLine()) != null) {
                lines++;
                chars += line.length();
            }
            return lines * 31 + chars;
        }
    }

    /** fusio, identical work (line Strings). */
    @Benchmark
    public long fusioGzipLines() {
        return ByteSource.of(gz)
                .via(Gzip.gunzip())
                .via(Utf8.decode())
                .via(Lines.split())
                .foldLong(0L, (acc, line) -> acc + 31 + line.length());
    }

    /** fusio with zero-allocation line views — same lines, no Strings. */
    @Benchmark
    public long fusioGzipLineViews() {
        return ByteSource.of(gz)
                .via(Gzip.gunzip())
                .via(Utf8.decode())
                .via(Lines.views())
                .foldLong(0L, (acc, c) -> acc + 31 + c.length);
    }
}
