package dev.flamelens.fusio.compat;

import static dev.flamelens.fusio.TestUtil.readAllBytes;
import static dev.flamelens.fusio.TestUtil.repeat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The csv-spectrum acceptance corpus (github.com/maxogden/csv-spectrum,
 * BSD-2-Clause) — "an acid test for CSV parsing libraries", with JSON ground
 * truth. Files are vendored under src/test/resources/csv-spectrum. Every file
 * is parsed at chunk sizes 1, 7, and 8192 and compared against the JSON.
 *
 * Bonus coverage: location_coordinates.csv contains bytes that are invalid
 * UTF-8; the corpus ground truth encodes them as U+FFFD, which also validates
 * fusio's malformed-input REPLACE behavior end to end.
 *
 * Vendoring notes: files must be checked out WITHOUT CRLF conversion (git
 * autocrlf corrupts the LF-only fixtures against their own ground truth), and
 * our copy of location_coordinates.json fixes an upstream corpus bug — the
 * phone number was anonymized in the JSON (1234567890) but not in the CSV
 * (2095257564); we align the JSON with the CSV.
 */
class CsvSpectrumCompatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "simple", "simple_crlf", "empty", "empty_crlf", "escaped_quotes",
            "json", "newlines", "newlines_crlf", "quotes_and_newlines",
            "comma_in_quotes", "utf8", "location_coordinates"
    })
    void spectrumFile(String name) throws Exception {
        byte[] csv = resource("/csv-spectrum/csvs/" + name + ".csv");
        JsonNode root = JSON.readTree(resource("/csv-spectrum/json/" + name + ".json"));

        List<JsonNode> objects = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(objects::add);
        } else {
            objects.add(root);
        }
        String[] header = new ArrayList<>(objects.get(0).properties()).stream()
                .map(java.util.Map.Entry::getKey)
                .toArray(String[]::new);

        for (int chunkSize : new int[]{1, 7, 8192}) {
            List<String[]> rows = ByteSource.of(csv, chunkSize)
                    .via(Utf8.decode())
                    .via(Csv.parse())
                    .toList();
            assertEquals(objects.size() + 1, rows.size(), name + " row count @chunk " + chunkSize);
            assertArrayEquals(header, rows.get(0), name + " header @chunk " + chunkSize);
            for (int i = 0; i < objects.size(); i++) {
                JsonNode obj = objects.get(i);
                String[] expected = new ArrayList<>(obj.properties()).stream()
                        .map(e -> e.getValue().asText())
                        .toArray(String[]::new);
                assertArrayEquals(expected, rows.get(i + 1),
                        name + " row " + i + " @chunk " + chunkSize);
            }
        }
    }

    private byte[] resource(String path) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream(path)) {
            return readAllBytes(in);
        }
    }
}
