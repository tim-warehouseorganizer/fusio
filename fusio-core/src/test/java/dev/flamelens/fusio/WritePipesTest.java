package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Gzip;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The write-side mirrors: Utf8.encode, Csv.format, Gzip.gzip. */
class WritePipesTest {

    // ---- Utf8.encode ----

    private static byte[] encodeVia(String text, int chunkChars) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Sink<Chars> sink = Utf8.encode(64).connect(b -> {
            bos.write(b.array, b.offset, b.length);
            return true;
        });
        char[] chars = text.toCharArray();
        for (int off = 0; off < chars.length; off += chunkChars) {
            int n = Math.min(chunkChars, chars.length - off);
            sink.accept(new Chars(chars, off, n));
        }
        sink.end();
        return bos.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64})
    void encodeMatchesJdkAtEveryChunkSize(int chunkChars) throws IOException {
        String text = "ascii é ß 汉字 € emoji 😀🚀 done\n".repeat(50);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), encodeVia(text, chunkChars));
    }

    @Test
    void encodeHandlesSurrogatePairSplitAcrossChunks() throws IOException {
        // chunk size 1 forces every pair to split
        String emoji = "😀😀😀";
        assertArrayEquals(emoji.getBytes(StandardCharsets.UTF_8), encodeVia(emoji, 1));
    }

    @Test
    void encodeReplacesLoneSurrogate() throws IOException {
        String bad = "a\uD800b"; // unpaired high surrogate
        assertArrayEquals(bad.getBytes(StandardCharsets.UTF_8), encodeVia(bad, 1));
    }

    @Test
    void encodeDecodeRoundTrip() {
        String text = "état, größe, 汉字, 😀 — mixed\n".repeat(200);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        ByteSource.of(bytes, 13)
                .via(Utf8.decode())
                .via(Utf8.encode(64).then(Utf8.decode(64)))
                .forEach(c -> sb.append(c.array, c.offset, c.length));
        assertEquals(text, sb.toString());
    }

    // ---- Csv.format ----

    private static String formatVia(List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        Sink<String[]> sink = Csv.format().connect(c -> {
            sb.append(c.array, c.offset, c.length);
            return true;
        });
        for (String[] row : rows) {
            sink.accept(row);
        }
        sink.end();
        return sb.toString();
    }

    @Test
    void formatQuotesOnlyWhenNeeded() throws IOException {
        String csv = formatVia(List.<String[]>of(
                new String[]{"plain", "with,comma", "with\"quote", "multi\nline", null}));
        assertEquals("plain,\"with,comma\",\"with\"\"quote\",\"multi\nline\",\n", csv);
    }

    @Test
    void formatParseRoundTripOnAdversarialRows() throws IOException {
        Random r = new Random(99);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String[] row = new String[1 + r.nextInt(6)];
            for (int f = 0; f < row.length; f++) {
                StringBuilder cell = new StringBuilder();
                int len = r.nextInt(12);
                for (int k = 0; k < len; k++) {
                    char[] alphabet = {'a', ',', '"', '\n', '\r', ' ', 'é', 'z'};
                    cell.append(alphabet[r.nextInt(alphabet.length)]);
                }
                row[f] = cell.toString();
            }
            rows.add(row);
        }
        byte[] bytes = formatVia(rows).getBytes(StandardCharsets.UTF_8);
        List<String[]> back = ByteSource.of(bytes, 7)
                .via(Utf8.decode())
                .via(Csv.parse())
                .toList();
        assertEquals(rows.size(), back.size());
        for (int i = 0; i < rows.size(); i++) {
            assertArrayEquals(rows.get(i), back.get(i), "row " + i);
        }
    }

    // ---- Gzip.gzip ----

    private static byte[] gzipVia(byte[] data, int chunkSize) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ByteSource.of(data, chunkSize)
                .via(Gzip.gzip())
                .forEach(b -> bos.write(b.array, b.offset, b.length));
        return bos.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192, 1 << 20})
    void jdkGzipInputStreamReadsOurOutput(int chunkSize) throws IOException {
        byte[] plain = "compress me with a fused stage\n".repeat(3_000)
                .getBytes(StandardCharsets.UTF_8);
        try (GZIPInputStream in = new GZIPInputStream(
                new ByteArrayInputStream(gzipVia(plain, chunkSize)))) {
            assertArrayEquals(plain, in.readAllBytes());
        }
    }

    @Test
    void gzipGunzipRoundTripThroughPipes() {
        byte[] plain = new byte[300_000];
        new Random(4).nextBytes(plain); // incompressible data path
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ByteSource.of(gzipVia(plain, 8192), 977)
                .via(Gzip.gunzip())
                .forEach(b -> bos.write(b.array, b.offset, b.length));
        assertArrayEquals(plain, bos.toByteArray());
    }

    @Test
    void emptyPayloadGzipsToValidStream() throws IOException {
        byte[] gz = gzipVia(new byte[0], 64);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            assertArrayEquals(new byte[0], in.readAllBytes());
        }
    }
}
