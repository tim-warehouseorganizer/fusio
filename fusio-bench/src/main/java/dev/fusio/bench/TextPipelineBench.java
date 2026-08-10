package dev.fusio.bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Assumption under test: the tutorial decorator stack
 * (BufferedReader over InputStreamReader, optionally over BufferedInputStream)
 * pays for redundant buffers, per-stage dispatch, and String materialization,
 * while a fused single-loop pipeline over the same bytes does not.
 *
 * All variants consume identical UTF-8 text and produce (lineCount, charOrByteSum).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TextPipelineBench {

    byte[] data;

    @Setup
    public void setup() {
        // ~16 MB of realistic log-ish lines, ASCII with occasional multibyte chars
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

    /** The pattern from every Java tutorial: two buffers (BufferedReader + StreamDecoder). */
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

    /** The "extra careful" tutorial pattern: three buffers. */
    @Benchmark
    public long decoratorTripleBuffer() throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new BufferedInputStream(new ByteArrayInputStream(data)),
                        StandardCharsets.UTF_8))) {
            long lines = 0, chars = 0;
            String line;
            while ((line = br.readLine()) != null) {
                lines++;
                chars += line.length();
            }
            return lines * 31 + chars;
        }
    }

    /**
     * What a fused pipeline compiles to for source -> utf8 decode -> lines -> fold:
     * one pass, one reused CharBuffer, no intermediate stream objects, no Strings.
     * Does the same UTF-8 decode work as the decorator variants.
     */
    @Benchmark
    public long fusedDecodeLines() {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(8192);
        long lines = 0, chars = 0;
        boolean done = false;
        while (!done) {
            done = dec.decode(in, out, true).isUnderflow();
            out.flip();
            char[] arr = out.array();
            int lim = out.limit();
            for (int i = 0; i < lim; i++) {
                if (arr[i] == '\n') lines++; else chars++;
            }
            out.clear();
        }
        return lines * 31 + chars;
    }

    /**
     * The ceiling: when the fold doesn't need chars at all, fusion lets the decode
     * stage disappear entirely. The decorator API cannot express this — readLine
     * always decodes and always allocates a String.
     */
    @Benchmark
    public long fusedByteScan(Blackhole bh) {
        long lines = 0, bytes = 0;
        // chunked to mimic a source handing out 8K segments (ownership transfer, no copy)
        for (int off = 0; off < data.length; off += 8192) {
            int end = Math.min(off + 8192, data.length);
            for (int i = off; i < end; i++) {
                if (data[i] == '\n') lines++; else bytes++;
            }
        }
        return lines * 31 + bytes;
    }
}
