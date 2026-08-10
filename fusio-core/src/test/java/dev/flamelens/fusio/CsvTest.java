package dev.flamelens.fusio;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvTest {

    private static List<String[]> viaFusio(String text, int chunkSize) {
        return ByteSource.of(text.getBytes(StandardCharsets.UTF_8), chunkSize)
                .via(Utf8.decode())
                .via(Csv.parse())
                .toList();
    }

    private static void assertRows(List<String[]> expected, List<String[]> actual) {
        assertEquals(expected.size(), actual.size(), "row count");
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), "row " + i);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64, 8192})
    void plainRows(int chunkSize) {
        assertRows(List.<String[]>of(new String[]{"a", "b", "c"}, new String[]{"1", "2", "3"}),
                viaFusio("a,b,c\n1,2,3\n", chunkSize));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64})
    void quotedFieldWithDelimiter(int chunkSize) {
        assertRows(List.<String[]>of(new String[]{"a,b", "c"}), viaFusio("\"a,b\",c\n", chunkSize));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64})
    void escapedQuotes(int chunkSize) {
        assertRows(List.<String[]>of(new String[]{"He said \"hi\"", "x"}),
                viaFusio("\"He said \"\"hi\"\"\",x\n", chunkSize));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64})
    void newlineInsideQuotedField(int chunkSize) {
        assertRows(List.<String[]>of(new String[]{"line1\nline2", "y"}),
                viaFusio("\"line1\nline2\",y\n", chunkSize));
    }

    @Test
    void crlfRowEndings() {
        assertRows(List.<String[]>of(new String[]{"a", "b"}, new String[]{"c", "d"}),
                viaFusio("a,b\r\nc,d\r\n", 64));
    }

    @Test
    void emptyFields() {
        assertRows(List.<String[]>of(new String[]{"a", "", "c"}, new String[]{"", "", ""}),
                viaFusio("a,,c\n,,\n", 64));
    }

    @Test
    void missingTrailingNewlineStillEmitsLastRow() {
        assertRows(List.<String[]>of(new String[]{"a", "b"}, new String[]{"c", "d"}),
                viaFusio("a,b\nc,d", 64));
    }

    @Test
    void trailingDelimiterMeansTrailingEmptyField() {
        assertRows(List.<String[]>of(new String[]{"a", ""}), viaFusio("a,\n", 64));
    }

    @Test
    void blankLineIsSingleEmptyField() {
        assertRows(List.<String[]>of(new String[]{"a"}, new String[]{""}, new String[]{"b"}),
                viaFusio("a\n\nb\n", 64));
    }

    /**
     * Differential test: 500 rows of adversarial fields (embedded delimiters,
     * quotes, newlines), serialized with RFC 4180 quoting, must parse
     * identically to opencsv's RFC4180Parser at an awkward chunk size.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void agreesWithOpencsvOnGeneratedData(int chunkSize) throws Exception {
        Random r = new Random(1234);
        StringBuilder csv = new StringBuilder();
        List<String[]> written = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String[] row = new String[1 + r.nextInt(6)];
            for (int f = 0; f < row.length; f++) {
                StringBuilder cell = new StringBuilder();
                int len = r.nextInt(12);
                for (int k = 0; k < len; k++) {
                    char[] alphabet = {'a', 'b', ',', '"', '\n', ' ', 'z'};
                    cell.append(alphabet[r.nextInt(alphabet.length)]);
                }
                row[f] = cell.toString();
                csv.append('"').append(cell.toString().replace("\"", "\"\"")).append('"');
                if (f < row.length - 1) {
                    csv.append(',');
                }
            }
            written.add(row);
            csv.append('\n');
        }
        String text = csv.toString();

        List<String[]> viaOpencsv = new ArrayList<>();
        try (CSVReader reader = new CSVReaderBuilder(new StringReader(text))
                .withCSVParser(new RFC4180ParserBuilder().build())
                .build()) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                viaOpencsv.add(row);
            }
        }

        List<String[]> viaUs = viaFusio(text, chunkSize);
        assertRows(written, viaUs);      // we match what was written
        assertRows(viaOpencsv, viaUs);   // and we match opencsv's reading of it
    }
}
