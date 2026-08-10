package dev.flamelens.fusio.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;
import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Bom;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Gzip;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InteropTest {

    // ---- FusioCsvReader: the opencsv migration path ----

    /** The opencsv usage shape, unchanged, against fusio's parser. */
    @Test
    void opencsvStyleReadNextLoop() throws IOException {
        String csv = "a,b\n\"1,5\",two\nlast,\"multi\nline\"\n";
        List<String[]> rows = new ArrayList<>();
        try (FusioCsvReader r = new FusioCsvReader(new StringReader(csv))) {
            String[] row;
            while ((row = r.readNext()) != null) {
                rows.add(row);
            }
            assertNull(r.readNext(), "stays null after EOF");
        }
        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{"a", "b"}, rows.get(0));
        assertArrayEquals(new String[]{"1,5", "two"}, rows.get(1));
        assertArrayEquals(new String[]{"last", "multi\nline"}, rows.get(2));
    }

    @Test
    void readAllMatchesReadNextLoop() throws IOException {
        String csv = "a,b\n\"1,5\",two\n";
        try (FusioCsvReader r = new FusioCsvReader(new StringReader(csv))) {
            List<String[]> all = r.readAll();
            assertEquals(2, all.size());
            assertArrayEquals(new String[]{"1,5", "two"}, all.get(1));
            assertNull(r.readNext());
        }
    }

    @Test
    void forEachIterationWorks() {
        String csv = "x\ny\nz\n";
        List<String> got = new ArrayList<>();
        for (String[] row : new FusioCsvReader(new StringReader(csv))) {
            got.add(row[0]);
        }
        assertEquals(List.of("x", "y", "z"), got);
    }

    /** Differential: FusioCsvReader and opencsv's RFC4180 CSVReader agree on generated data. */
    @Test
    void agreesWithOpencsvReader() throws Exception {
        Random r = new Random(77);
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            int fields = 1 + r.nextInt(5);
            for (int f = 0; f < fields; f++) {
                StringBuilder cell = new StringBuilder();
                int len = r.nextInt(10);
                for (int k = 0; k < len; k++) {
                    char[] alphabet = {'a', ',', '"', '\n', ' ', 'z'};
                    cell.append(alphabet[r.nextInt(alphabet.length)]);
                }
                csv.append('"').append(cell.toString().replace("\"", "\"\"")).append('"');
                if (f < fields - 1) {
                    csv.append(',');
                }
            }
            csv.append('\n');
        }
        String text = csv.toString();

        List<String[]> viaOpencsv = new ArrayList<>();
        try (var reader = new CSVReaderBuilder(new StringReader(text))
                .withCSVParser(new RFC4180ParserBuilder().build())
                .build()) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                viaOpencsv.add(row);
            }
        }

        List<String[]> viaFusio = new ArrayList<>();
        try (FusioCsvReader reader = new FusioCsvReader(new StringReader(text))) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                viaFusio.add(row);
            }
        }
        assertEquals(viaOpencsv.size(), viaFusio.size());
        for (int i = 0; i < viaOpencsv.size(); i++) {
            assertArrayEquals(viaOpencsv.get(i), viaFusio.get(i), "row " + i);
        }
    }

    /** The byte-level factory skips the Reader layer and tolerates a BOM. */
    @Test
    void byteLevelFactoryStripsBom() throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] csv = "name,city\nAda,London\n".getBytes(StandardCharsets.UTF_8);
        byte[] joined = new byte[bom.length + csv.length];
        System.arraycopy(bom, 0, joined, 0, bom.length);
        System.arraycopy(csv, 0, joined, bom.length, csv.length);

        try (FusioCsvReader r = FusioCsvReader.of(new ByteArrayInputStream(joined))) {
            assertArrayEquals(new String[]{"name", "city"}, r.readNext());
            assertArrayEquals(new String[]{"Ada", "London"}, r.readNext());
            assertNull(r.readNext());
        }
    }

    // ---- PipeInputStream: the universal InputStream socket ----

    @Test
    void gunzipPipelineAsInputStream() throws IOException {
        byte[] plain = "stream me through a fused pipeline\n".repeat(10_000)
                .getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new PipeInputStream(
                new ByteArrayInputStream(gzip(plain)), Gzip.gunzip())) {
            assertArrayEquals(plain, in.readAllBytes());
        }
    }

    /** The Jackson wiring example: gzipped JSON body -> fusio gunzip -> ObjectMapper. */
    @Test
    void feedsJacksonObjectMapper() throws IOException {
        byte[] gzJson = gzip("{\"name\":\"fusio\",\"fused\":true,\"stages\":3}"
                .getBytes(StandardCharsets.UTF_8));
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new PipeInputStream(new ByteArrayInputStream(gzJson), Gzip.gunzip())) {
            JsonNode node = mapper.readTree(in);
            assertEquals("fusio", node.get("name").asText());
            assertEquals(3, node.get("stages").asInt());
        }
    }

    @Test
    void singleByteReadsWork() throws IOException {
        byte[] plain = "abc".getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new PipeInputStream(
                new ByteArrayInputStream(gzip(plain)), Gzip.gunzip())) {
            assertEquals('a', in.read());
            assertEquals('b', in.read());
            assertEquals('c', in.read());
            assertEquals(-1, in.read());
            assertEquals(-1, in.read());
        }
    }

    // ---- CsvInputStream: rows -> lazy CSV bytes ----

    @Test
    void csvInputStreamMatchesCsvText() throws IOException {
        List<String[]> rows = List.<String[]>of(
                new String[]{"sku", "name"},
                new String[]{"A,1", "say \"hi\""},
                new String[]{"B", "multi\nline"});
        byte[] streamed;
        try (CsvInputStream in = new CsvInputStream(rows.iterator())) {
            streamed = in.readAllBytes();
        }
        assertArrayEquals(
                dev.flamelens.fusio.pipes.Csv.text(rows).getBytes(StandardCharsets.UTF_8),
                streamed);
    }

    @Test
    void csvInputStreamIsLazy() throws IOException {
        var pulled = new java.util.concurrent.atomic.AtomicInteger();
        Iterator<String[]> counting = new Iterator<>() {
            int i = 0;

            @Override
            public boolean hasNext() {
                return i < 1_000_000;
            }

            @Override
            public String[] next() {
                pulled.incrementAndGet();
                return new String[]{"row-" + i++, "value"};
            }
        };
        try (CsvInputStream in = new CsvInputStream(counting)) {
            in.readNBytes(64); // read a tiny prefix of a million-row source
        }
        // laziness is bounded by the formatter's 16K char buffer (~2-3K short
        // rows), NOT by the source size: memory is O(buffer), never O(rows)
        assertEquals(true, pulled.get() < 10_000,
                "expected buffer-bounded pull, got " + pulled.get());
    }

    // ---- Bom.strip() as a standalone pipe ----

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 64})
    void bomStrippedAtAnyChunkSize(int chunkSize) {
        byte[] withBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        String result = ByteSource.of(withBom, chunkSize)
                .via(Bom.strip())
                .via(Utf8.decode())
                .fold(new StringBuilder(), (sb, c) -> sb.append(c.array, c.offset, c.length))
                .toString();
        assertEquals("hi", result);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 64})
    void bomLookalikePrefixIsPreserved(int chunkSize) {
        // EF BB without BF is real data (a truncated lookalike), must re-emit
        byte[] lookalike = {(byte) 0xEF, (byte) 0xBB, 'x'};
        long count = ByteSource.of(lookalike, chunkSize)
                .via(Bom.strip())
                .foldLong(0L, (n, b) -> n + b.length);
        assertEquals(3, count);
    }

    @Test
    void bomOnlyInputBecomesEmpty() {
        byte[] onlyBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        assertEquals(0, ByteSource.of(onlyBom, 1).via(Bom.strip()).foldLong(0L, (n, b) -> n + b.length));
    }

    /** With Bom.strip() composed in, the Jackson BOM divergence closes. */
    @Test
    void bomStripClosesJacksonDivergence() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] csv = "Bob,Robertson\n".getBytes(StandardCharsets.UTF_8);
        byte[] joined = new byte[bom.length + csv.length];
        System.arraycopy(bom, 0, joined, 0, bom.length);
        System.arraycopy(csv, 0, joined, bom.length, csv.length);
        List<String[]> rows = ByteSource.of(joined, 64)
                .via(Bom.strip())
                .via(Utf8.decode())
                .via(Csv.parse())
                .toList();
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"Bob", "Robertson"}, rows.get(0));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }
}
