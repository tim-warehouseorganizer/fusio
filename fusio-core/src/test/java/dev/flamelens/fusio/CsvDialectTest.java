package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.CsvCascade;
import dev.flamelens.fusio.pipes.CsvDialect;
import dev.flamelens.fusio.pipes.CsvDialectMismatchException;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Dialect conformance: the named presets must reproduce the incumbent
 * behaviors they are named after (cases ported from opencsv's CSVParserTest
 * and jackson-dataformat-csv's suites), and the default must stay strict
 * RFC 4180. CsvCascade waterfalls across them.
 */
class CsvDialectTest {

    private static List<String[]> parse(String text, CsvDialect dialect, int chunkSize) {
        return ByteSource.of(text.getBytes(StandardCharsets.UTF_8), chunkSize)
                .via(Utf8.decode())
                .via(Csv.parse(dialect))
                .toList();
    }

    // ---- opencsvLegacy: backslash escapes (from opencsv CSVParserTest) ----

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 64})
    void legacyEscapedSeparator(int chunkSize) {
        // CSVParserTest.parseEscapingTheSeparatorCharacterWithoutQuotes: a,b\,c,d -> 3 fields
        List<String[]> rows = parse("a,b\\,c,d", CsvDialect.opencsvLegacy(), chunkSize);
        assertArrayEquals(new String[]{"a", "b,c", "d"}, rows.get(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 64})
    void legacyEscapedEscape(int chunkSize) {
        // CSVParserTest.parseEscapingTheEscapeCharacter: "a","b\\c","d" -> b\c
        List<String[]> rows = parse("\"a\",\"b\\\\c\",\"d\"", CsvDialect.opencsvLegacy(), chunkSize);
        assertArrayEquals(new String[]{"a", "b\\c", "d"}, rows.get(0));
    }

    @Test
    void legacyEscapedQuoteInsideQuotes() {
        // CSVParserTest.testEscapedDoubleQuoteAsDataElement: "a\"b" -> a"b
        List<String[]> rows = parse("\"a\\\"b\",c", CsvDialect.opencsvLegacy(), 64);
        assertArrayEquals(new String[]{"a\"b", "c"}, rows.get(0));
    }

    @Test
    void legacyEscapeAtFieldStart() {
        // CSVParserTest.parseEscapingTheFirstCharacter: \,a first field is ",a"
        List<String[]> rows = parse("\\,a,b,c", CsvDialect.opencsvLegacy(), 64);
        assertArrayEquals(new String[]{",a", "b", "c"}, rows.get(0));
    }

    @Test
    void legacyEscapeBeforeNonEscapableStaysLiteral() {
        // RFC4180ParserSpec row 8: \' is not escapable -> both chars literal
        List<String[]> rows = parse("8,\\',x", CsvDialect.opencsvLegacy(), 64);
        assertArrayEquals(new String[]{"8", "\\'", "x"}, rows.get(0));
    }

    @Test
    void legacyStripsCarriageReturnsInsideQuotes() {
        // opencsv default keepCarriageReturn=false
        List<String[]> rows = parse("\"a\",\"1\r\n2\",\"c\"", CsvDialect.opencsvLegacy(), 64);
        assertArrayEquals(new String[]{"a", "1\n2", "c"}, rows.get(0));
    }

    @Test
    void legacyThrowsOnUnterminatedQuote() {
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> parse("\"abc", CsvDialect.opencsvLegacy(), 64));
        assertInstanceOf(CsvDialectMismatchException.class, e.getCause());
    }

    // ---- jackson: trim around quotes, strict unterminated ----

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64})
    void jacksonTrimsSpacesAroundQuotedFields(int chunkSize) {
        // ParserQuotes643Test: "abc"  ,  "def" -> [abc, def]
        List<String[]> rows = parse("\"abc\"  ,  \"def\"\n", CsvDialect.jackson(), chunkSize);
        assertArrayEquals(new String[]{"abc", "def"}, rows.get(0));
    }

    @Test
    void jacksonPreservesUnquotedLeadingSpaces() {
        // ParserQuotes643Test.testUnquotedLeadingSpacesPreserved
        List<String[]> rows = parse("abc,  def\n", CsvDialect.jackson(), 64);
        assertArrayEquals(new String[]{"abc", "  def"}, rows.get(0));
    }

    @Test
    void jacksonThrowsOnUnterminatedQuote() {
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> parse("\"unterminated,c\n", CsvDialect.jackson(), 64));
        assertInstanceOf(CsvDialectMismatchException.class, e.getCause());
    }

    // ---- default stays RFC 4180 ----

    @Test
    void defaultTreatsBackslashAsData() {
        List<String[]> rows = parse("a\\,b", CsvDialect.rfc4180(), 64);
        assertArrayEquals(new String[]{"a\\", "b"}, rows.get(0));
    }

    @Test
    void defaultTreatsSpacesBeforeQuoteAsData() {
        List<String[]> rows = parse("abc,  \"def\"\n", CsvDialect.rfc4180(), 64);
        assertArrayEquals(new String[]{"abc", "  \"def\""}, rows.get(0));
    }

    // ---- the cascade ----

    @Test
    void cleanRfcDocumentWinsAtTierZero() {
        byte[] doc = "a,b\n\"1,5\",x\n".getBytes(StandardCharsets.UTF_8);
        CsvCascade.Result result = CsvCascade.parse(ByteSource.of(doc));
        assertEquals(0, result.tierIndex());
        assertEquals(2, result.rows().size());
    }

    @Test
    void spaceBeforeQuoteFallsThroughToJackson() {
        byte[] doc = "abc,  \"def\"\nxyz,  \"ghi\"\n".getBytes(StandardCharsets.UTF_8);
        CsvCascade.Result result = CsvCascade.parse(ByteSource.of(doc));
        assertEquals(1, result.tierIndex()); // Jackson tier
        assertArrayEquals(new String[]{"abc", "def"}, result.rows().get(0));
    }

    @Test
    void quoteChaosFallsThroughToLegacy() {
        // escaped quote inside quotes: RFC-strict sees data-after-closing-quote,
        // Jackson-strict likewise; legacy's escape handling parses it
        byte[] doc = "\"a\\\"b\",c\n".getBytes(StandardCharsets.UTF_8);
        CsvCascade.Result result = CsvCascade.parse(ByteSource.of(doc));
        assertEquals(2, result.tierIndex()); // opencsvLegacy tier
        assertArrayEquals(new String[]{"a\"b", "c"}, result.rows().get(0));
    }

    @Test
    void trulyBrokenDocumentThrowsFromLastTier() {
        byte[] doc = "\"never closed".getBytes(StandardCharsets.UTF_8);
        assertThrows(UncheckedIOException.class, () -> CsvCascade.parse(ByteSource.of(doc)));
    }

    @Test
    void explicitLenientFinalTierAcceptsAnything() {
        byte[] doc = "\"never closed".getBytes(StandardCharsets.UTF_8);
        CsvCascade.Result result = CsvCascade.parse(ByteSource.of(doc),
                CsvDialect.rfc4180(), CsvDialect.jackson(), CsvDialect.opencsvLegacy(),
                CsvDialect.rfc4180()); // lenient RFC as the catch-all
        assertEquals(3, result.tierIndex());
        assertArrayEquals(new String[]{"never closed"}, result.rows().get(0));
    }

    /** The honest limitation, pinned as a test: backslash intent is undetectable. */
    @Test
    void cascadeCannotDetectBackslashEscapeIntent() {
        byte[] doc = "a\\,b\n".getBytes(StandardCharsets.UTF_8);
        CsvCascade.Result result = CsvCascade.parse(ByteSource.of(doc));
        assertEquals(0, result.tierIndex()); // RFC wins — backslash is valid data
        assertArrayEquals(new String[]{"a\\", "b"}, result.rows().get(0));
    }
}
