package dev.flamelens.fusio;

import dev.flamelens.fusio.interop.CsvInputStream;
import dev.flamelens.fusio.pipes.Csv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link Csv.Escaping}, added in 2.0.0 so the CSV writer can express a null at all.
 *
 * <p>RFC 4180 has no null: through 1.x a null element was collapsed to an empty string before
 * quoting, so bulk loaders wrote {@code ''} into nullable columns. Harmless on a VARCHAR, a
 * coercion or an error on a DECIMAL or DATE, and invisible until someone reads the data back.
 */
class CsvEscapingTest {

    // fusio targets maven.compiler.release=8, so no var, no InputStream.readAllBytes.
    private static String format(Csv.Escaping escaping, String[]... rows) throws Exception {
        try (CsvInputStream in = new CsvInputStream(
                Arrays.asList(rows).iterator(), ',', false, escaping)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("RFC4180 still cannot tell null from empty - unchanged, and the default")
    void rfc4180CollapsesNull() throws Exception {
        assertEquals("a,,,b\n",
                format(Csv.Escaping.RFC4180, new String[]{"a", null, "", "b"}));
    }

    @Test
    @DisplayName("RFC4180 remains byte-identical for ordinary rows")
    void rfc4180Unchanged() throws Exception {
        assertEquals("plain,\"has,comma\",\"say \"\"hi\"\"\"\n",
                format(Csv.Escaping.RFC4180, new String[]{"plain", "has,comma", "say \"hi\""}));
    }

    @Test
    @DisplayName("Postgres: unquoted empty is NULL, quoted empty is the empty string")
    void postgresDistinguishesNullFromEmpty() throws Exception {
        assertEquals("a,,\"\",b\n",
                format(Csv.Escaping.POSTGRES_COPY, new String[]{"a", null, "", "b"}));
    }

    @Test
    @DisplayName("Postgres leaves backslashes alone - CSV mode gives them no meaning")
    void postgresDoesNotEscapeBackslash() throws Exception {
        assertEquals("c:\\temp\n",
                format(Csv.Escaping.POSTGRES_COPY, new String[]{"c:\\temp"}));
    }

    @Test
    @DisplayName("MySQL: null is an unquoted \\N, empty string stays empty")
    void mysqlWritesBackslashN() throws Exception {
        assertEquals("a,\\N,,b\n",
                format(Csv.Escaping.MYSQL_LOAD_DATA, new String[]{"a", null, "", "b"}));
    }

    @Test
    @DisplayName("MySQL: a literal backslash is doubled so ESCAPED BY does not eat the next char")
    void mysqlDoublesBackslash() throws Exception {
        assertEquals("c:\\\\temp\n",
                format(Csv.Escaping.MYSQL_LOAD_DATA, new String[]{"c:\\temp"}));
    }

    @Test
    @DisplayName("MySQL: data that looks like the null marker is escaped, not read as NULL")
    void mysqlEscapesLiteralNullMarker() throws Exception {
        // Without doubling, this row would load as NULL rather than the two-character string.
        assertEquals("\\\\N\n",
                format(Csv.Escaping.MYSQL_LOAD_DATA, new String[]{"\\N"}));
    }

    @Test
    @DisplayName("MySQL: quotes are still doubled inside an enclosed field")
    void mysqlKeepsQuoteDoubling() throws Exception {
        // MySQL accepts doubled quotes within an enclosed field regardless of ESCAPED BY, which is
        // why turning escaping on costs nothing here.
        assertEquals("\"say \"\"hi\"\"\"\n",
                format(Csv.Escaping.MYSQL_LOAD_DATA, new String[]{"say \"hi\""}));
    }

    @Test
    @DisplayName("MySQL: a backslash inside a quoted field is doubled too")
    void mysqlEscapesBackslashInsideQuotes() throws Exception {
        assertEquals("\"a,b\\\\c\"\n",
                format(Csv.Escaping.MYSQL_LOAD_DATA, new String[]{"a,b\\c"}));
    }

    @Test
    @DisplayName("every dialect agrees on rows with no nulls, empties or backslashes")
    void dialectsAgreeOnOrdinaryRows() throws Exception {
        String[] row = new String[]{"sku-1", "Widget", "12.50"};

        String rfc = format(Csv.Escaping.RFC4180, row);
        assertEquals(rfc, format(Csv.Escaping.POSTGRES_COPY, row));
        assertEquals(rfc, format(Csv.Escaping.MYSQL_LOAD_DATA, row));
    }
}
