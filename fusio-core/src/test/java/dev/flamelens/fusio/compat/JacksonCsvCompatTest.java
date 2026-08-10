package dev.flamelens.fusio.compat;

import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test cases ported from jackson-dataformat-csv's suite (Apache License 2.0,
 * FasterXML) — primarily CsvDecoderTextTest, SkipEmptyLines15Test,
 * SkipEmptyRows368Test, TestParserQuotes, TestParserNoSchema,
 * BasicCSVParserTest, MissingColumnsTest, UnicodeCSVRead497Test, and the
 * CSVFuzz regression fixtures. Cases are ported at Jackson's DEFAULT feature
 * settings (no comments, no empty-line skipping, no trimming, no escape char).
 *
 * Documented divergences from Jackson (not ported as passing tests):
 * - Jackson throws on an unterminated quote at EOF; fusio is lenient like
 *   opencsv's RFC4180Parser (see {@link #divergenceUnterminatedQuote()}).
 * - Jackson strips spaces around a QUOTED field ("  \"def\"" parses as
 *   "def"); fusio, like RFC 4180 and opencsv's RFC4180Parser, treats those
 *   spaces as data (see {@link #divergenceSpacesAroundQuotedField()}).
 * - Jackson auto-detects and consumes BOMs; fusio currently passes a UTF-8
 *   BOM through as U+FEFF (see {@link #divergenceUtf8BomIsNotStripped()}).
 */
class JacksonCsvCompatTest {

    private static List<String[]> parse(String text, int chunkSize) {
        return parse(text.getBytes(StandardCharsets.UTF_8), chunkSize);
    }

    private static List<String[]> parse(byte[] bytes, int chunkSize) {
        return ByteSource.of(bytes, chunkSize)
                .via(Utf8.decode())
                .via(Csv.parse())
                .toList();
    }

    // CsvDecoderTextTest.testQuotedDoubledQuote
    @Test
    void quotedDoubledQuote() {
        List<String[]> rows = parse("\"a\"\"b\",c\n", 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"a\"b", "c"}, rows.get(0));
    }

    /**
     * CsvDecoderTextTest.testQuotedEmbeddedLinefeeds: LF, CR, and CRLF inside
     * a quoted field are all preserved verbatim — no normalization.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64})
    void quotedEmbeddedLinefeedsPreservedVerbatim(int chunkSize) {
        List<String[]> rows = parse("\"x\ny\rz\r\nw\",end\n", chunkSize);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"x\ny\rz\r\nw", "end"}, rows.get(0));
    }

    // CsvDecoderTextTest.testLongQuotedValueSpansSegments
    @Test
    void longQuotedValueSpansSegments() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 5000) {
            sb.append("lorem,ipsum ");
        }
        String value = sb.toString();
        List<String[]> rows = parse("\"" + value + "\",tail\n", 4000);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{value, "tail"}, rows.get(0));
    }

    // TestParserQuotes.testDefaultSimpleQuotes / MappingIteratorEnd9Test:
    // a lone quoted value is exactly one row — no phantom trailing row
    @Test
    void loneQuotedValueIsExactlyOneRow() {
        List<String[]> rows = parse("\"te,st\"", 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"te,st"}, rows.get(0));
    }

    // TestParserQuotes.testSimpleQuotes: unquoted spaces preserved, doubled quotes collapsed
    @Test
    void unquotedSpacesPreservedDoubledQuotesCollapsed() {
        List<String[]> rows = parse(" 13  ,\"Joe \"\"Sixpack\"\" Paxson\"", 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{" 13  ", "Joe \"Sixpack\" Paxson"}, rows.get(0));
    }

    // TestParserQuotes.testSimpleMultiLine row 1: fully-quoted quoted-word
    @Test
    void quotedWordField() {
        List<String[]> rows = parse("-3,\"\"\"Unknown\"\"\"\n", 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"-3", "\"Unknown\""}, rows.get(0));
    }

    /**
     * BasicCSVParserTest._testMapsWithLinefeeds: ragged rows plus \n and \r\n
     * row terminators mixed in one document, plus both multiline flavours.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void raggedRowsAndMixedTerminators(int chunkSize) {
        String doc = "A,B,C\n"
                + "data11,data12\n"
                + "data21,data22,data23\r\n"
                + "data31,\"data32 data32\ndata32 data32\",data33\n"
                + "data41,\"data42 data42\r\ndata42\",data43\n";
        List<String[]> rows = parse(doc, chunkSize);
        assertEquals(5, rows.size());
        assertArrayEquals(new String[]{"A", "B", "C"}, rows.get(0));
        assertArrayEquals(new String[]{"data11", "data12"}, rows.get(1));
        assertArrayEquals(new String[]{"data21", "data22", "data23"}, rows.get(2));
        assertArrayEquals(new String[]{"data31", "data32 data32\ndata32 data32", "data33"}, rows.get(3));
        assertArrayEquals(new String[]{"data41", "data42 data42\r\ndata42", "data43"}, rows.get(4));
    }

    // TestParserNoSchema.testUntypedAsStringArray / SkipEmptyLines15Test:
    // blank line is one empty field; trailing \n creates no extra row
    @Test
    void blankLineAndTrailingNewline() {
        List<String[]> rows = parse("1,\"xyz\"\n\ntrue,\n", 64);
        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{"1", "xyz"}, rows.get(0));
        assertArrayEquals(new String[]{""}, rows.get(1));
        assertArrayEquals(new String[]{"true", ""}, rows.get(2));
    }

    // SkipEmptyLines15Test: whitespace-only line preserved as ["   "]
    @Test
    void whitespaceOnlyLinePreserved() {
        List<String[]> rows = parse("1,\"xyz\"\n   \ntrue,\n", 64);
        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{"   "}, rows.get(1));
    }

    // SkipEmptyLines15Test: leading blank line
    @Test
    void leadingBlankLine() {
        List<String[]> rows = parse("\n1,\"xyz\"\ntrue,\n", 64);
        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{""}, rows.get(0));
    }

    // SkipEmptyLines15Test: trailing blank lines — only the final terminator is consumed
    @Test
    void trailingBlankLines() {
        List<String[]> rows = parse("1,\"xyz\"\ntrue,\n  \n\n", 64);
        assertEquals(4, rows.size());
        assertArrayEquals(new String[]{"  "}, rows.get(2));
        assertArrayEquals(new String[]{""}, rows.get(3));
    }

    // SkipEmptyRows368Test: a ,,-only row is three empty fields, never skipped
    @Test
    void separatorOnlyRowIsEmptyFields() {
        List<String[]> rows = parse("1,red\n,,\n2,blue\n", 64);
        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{"", "", ""}, rows.get(1));
    }

    // MissingColumnsTest.testDefaultMissingHandling: "\n" is one row of one empty string
    @Test
    void lineFeedOnlyDocument() {
        List<String[]> rows = parse("\n", 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{""}, rows.get(0));
    }

    /**
     * UnicodeCSVRead497Test: a 3-byte UTF-8 char straddling the read-buffer
     * boundary, for boundary offsets around 4000; input bytes must not be
     * mutated by parsing.
     */
    @ParameterizedTest
    @ValueSource(ints = {3998, 3999, 4000, 4001})
    void multibyteCharAtBufferBoundary(int prefixLen) {
        String value = "a".repeat(prefixLen) + '咖';
        byte[] bytes = (value + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] pristine = bytes.clone();
        List<String[]> rows = parse(bytes, 4000);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{value}, rows.get(0));
        assertArrayEquals(pristine, bytes, "input must not be mutated");
    }

    /** Jackson fuzz fixtures: must terminate without crashing or hanging. */
    @ParameterizedTest
    @ValueSource(strings = {"/fixtures/jackson-fuzz-499695465.csv", "/fixtures/jackson-fuzz-50402.csv"})
    void fuzzFixturesParseWithoutCrashing(String resource) throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream(resource)) {
            bytes = in.readAllBytes();
        }
        List<String[]> rows = parse(bytes, 1024);
        assertTrue(rows.size() > 0);
    }

    // ---- documented divergences ----

    /** Jackson throws "Missing closing quote"; fusio is lenient like opencsv's RFC4180Parser. */
    @Test
    void divergenceUnterminatedQuote() {
        List<String[]> rows = parse("\"unterminated,c\n", 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"unterminated,c\n"}, rows.get(0));
    }

    /** Jackson parses "  \"def\"" as def; fusio keeps the spaces+quotes as data (RFC 4180 reading). */
    @Test
    void divergenceSpacesAroundQuotedField() {
        List<String[]> rows = parse("abc,  \"def\"\n", 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"abc", "  \"def\""}, rows.get(0));
    }

    /** Jackson consumes a UTF-8 BOM; fusio currently surfaces it as U+FEFF (roadmap: opt-in strip). */
    @Test
    void divergenceUtf8BomIsNotStripped() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] csv = "Bob,Robertson\n".getBytes(StandardCharsets.UTF_8);
        byte[] joined = new byte[bom.length + csv.length];
        System.arraycopy(bom, 0, joined, 0, bom.length);
        System.arraycopy(csv, 0, joined, bom.length, csv.length);
        List<String[]> rows = parse(joined, 64);
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"﻿Bob", "Robertson"}, rows.get(0));
    }
}
