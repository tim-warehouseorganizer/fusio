package dev.flamelens.fusio.compat;

import static dev.flamelens.fusio.TestUtil.readAllBytes;
import static dev.flamelens.fusio.TestUtil.repeat;

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

/**
 * Test cases ported from opencsv's own suite (Apache License 2.0,
 * Copyright 2005 Bytecode Pty Ltd and opencsv contributors) —
 * primarily RFC4180ParserSpec.groovy, CSVReaderAndParserIntegrationSpec.groovy,
 * CSVParserTest.java (the RFC-compatible subset), CSVReaderTest.java, and
 * Bug259's DoubleNewLineTest. fusio's Csv.parse() targets RFC 4180 semantics,
 * which is opencsv's RFC4180Parser dialect: no escape character (backslash is
 * data), no whitespace trimming, lenient about unterminated quotes.
 *
 * Deliberately NOT ported: cases exercising opencsv's legacy CSVParser dialect
 * (backslash escapes, strictQuotes, ignoreLeadingWhiteSpace,
 * nullFieldIndicator variants other than the default) — those are
 * configuration features, not CSV semantics.
 */
class OpencsvCompatTest {

    private static List<String[]> parse(String text, int chunkSize) {
        return ByteSource.of(text.getBytes(StandardCharsets.UTF_8), chunkSize)
                .via(Utf8.decode())
                .via(Csv.parse())
                .toList();
    }

    private static String[] parseSingle(String text, int chunkSize) {
        List<String[]> rows = parse(text, chunkSize);
        assertEquals(1, rows.size(), "expected a single row for: " + text);
        return rows.get(0);
    }

    // RFC4180ParserSpec: "parse a simple line"
    @Test
    void simpleLine() {
        assertArrayEquals(new String[]{"This", "is", "a", "test"},
                parseSingle("This,is,a,test", 64));
    }

    // RFC4180ParserSpec: parseLineMulti with embedded newline
    @Test
    void quotedFieldWithEmbeddedNewline() {
        assertArrayEquals(new String[]{"This", "is", "a multiple \n line", "test"},
                parseSingle("\"This\",\"is\",\"a multiple \n line\",\"test\"", 64));
    }

    /**
     * RFC4180ParserSpec main @Unroll table (default separator and quote).
     * The backslash rows are deliberate negative tests: RFC 4180 has no escape
     * character, so backslash must survive as literal data.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 64})
    void rfc4180SpecTable(int chunkSize) {
        assertArrayEquals(new String[]{"7", "seven", "7.89", "12/11/16"},
                parseSingle("7,seven,7.89,12/11/16", chunkSize));
        assertArrayEquals(new String[]{"1", "\\\"", "this is a quote \" character", "test"},
                parseSingle("1,\"\\\"\"\",\"this is a quote \"\" character\",test", chunkSize));
        assertArrayEquals(new String[]{"2", "\\ ", "this is a comma , character", "two"},
                parseSingle("2,\\ ,\"this is a comma , character\",two", chunkSize));
        assertArrayEquals(new String[]{"3", "\\\\ ", "this is a backslash \\ character", "three"},
                parseSingle("3,\\\\ ,this is a backslash \\ character,three", chunkSize));
        assertArrayEquals(new String[]{"5", "21,34", "test comma", "five"},
                parseSingle("5,\"21,34\",test comma,five", chunkSize));
        assertArrayEquals(
                new String[]{"8", "\\'", "a big line with \nmultiple carriage returns\nin it.", "eight"},
                parseSingle("8,\\',\"a big line with \nmultiple carriage returns\nin it.\",eight", chunkSize));
    }

    // RFC4180ParserSpec bug 157: quote in the middle of an unquoted field is literal data
    @Test
    void quoteInMiddleOfUnquotedField() {
        assertArrayEquals(new String[]{"21", "2\"2", "23", "24"},
                parseSingle("21,2\"2,23,24", 64));
    }

    // RFC4180ParserSpec: a trailing lone quote is kept, no error
    @Test
    void trailingLoneQuoteIsKept() {
        assertArrayEquals(new String[]{"line 1\""}, parseSingle("line 1\"", 64));
    }

    /**
     * RFC4180ParserSpec "Excel generated string": the densest quote/backslash
     * mix in their suite. CSV text: "\""",\,\,"""",""","
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 64})
    void excelGeneratedString(int chunkSize) {
        String testLine = "\"\\\"\"\",\\,\\,\"\"\"\",\"\"\",\"";
        assertArrayEquals(new String[]{"\\\"", "\\", "\\", "\"", "\","},
                parseSingle(testLine, chunkSize));
    }

    // RFC4180ParserSpec Stack Overflow pair: unterminated quotes are lenient, like RFC4180Parser
    @Test
    void unterminatedQuoteIsLenient() {
        assertArrayEquals(new String[]{"ABC\\"}, parseSingle("\"ABC\\", 64));
        assertArrayEquals(new String[]{"ABC\\\""}, parseSingle("\"ABC\\\"\"", 64));
    }

    // RFC4180ParserSpec nullFieldIndicator=NEITHER row (the RFC-correct default):
    // no trimming, quoted-empty and unquoted-empty are both ""
    @Test
    void emptyAndWhitespaceFields() {
        assertArrayEquals(new String[]{"", " ", "", "", ""}, parseSingle(", ,,\"\",", 64));
    }

    // RFC4180ParserTest.testEmptyString
    @Test
    void emptyStringIsSingleEmptyField() {
        assertArrayEquals(new String[]{""}, parseSingle("\n", 64));
    }

    /** CSVReaderAndParserIntegrationSpec: the four-record document both opencsv parsers agree on. */
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void integrationSpecDocument(int chunkSize) {
        String doc = "a,b,c\n"
                + "a,\"b,b,b\",c\n"
                + ",,\n"
                + "a,\"PO Box 123,\nKippax,ACT. 2615.\nAustralia\",d.\n";
        List<String[]> rows = parse(doc, chunkSize);
        assertEquals(4, rows.size());
        assertArrayEquals(new String[]{"a", "b", "c"}, rows.get(0));
        assertArrayEquals(new String[]{"a", "b,b,b", "c"}, rows.get(1));
        assertArrayEquals(new String[]{"", "", ""}, rows.get(2));
        assertArrayEquals(new String[]{"a", "PO Box 123,\nKippax,ACT. 2615.\nAustralia", "d."}, rows.get(3));
    }

    /**
     * CSVReaderAndParserIntegrationSpec "Bug 143": \r\n inside a quoted field.
     * opencsv needs withKeepCarriageReturn(true) for this; fusio (like Jackson
     * and RFC 4180) preserves it by default.
     */
    @Test
    void crlfInsideQuotedFieldIsPreserved() {
        String value2 = "\"that \"\"if the reptile is killed the baby will pine away and die"
                + " a few weeks later.\"\"\r\n\r\nThese mystical snakes will protect their owner"
                + " and be a topic of conversation for sure.\"";
        String[] row = parseSingle("100," + value2 + ",300", 64);
        assertEquals(3, row.length);
        assertEquals("100", row[0]);
        assertEquals("that \"if the reptile is killed the baby will pine away and die"
                + " a few weeks later.\"\r\n\r\nThese mystical snakes will protect their owner"
                + " and be a topic of conversation for sure.", row[1]);
        assertEquals("300", row[2]);
    }

    // CSVParserTest RFC-compatible subset
    @Test
    void csvParserTestRfcSubset() {
        assertArrayEquals(new String[]{"This", " is", " a", " test."},
                parseSingle("This, is, a, test.", 64));
        assertArrayEquals(new String[]{"a", "b", "c"}, parseSingle("\"a\",\"b\",\"c\"", 64));
        assertArrayEquals(new String[]{"", "", ""}, parseSingle(",,", 64));
        assertArrayEquals(new String[]{"a", "123\"4\"567", "c"}, parseSingle("a,123\"4\"567,c", 64));
        assertArrayEquals(new String[]{"a", "\"", "c"}, parseSingle("a,\"\"\"\",c", 64));
        assertArrayEquals(new String[]{"Glen \"The Man\" Smith", "Athlete", "Developer"},
                parseSingle("\"Glen \"\"The Man\"\" Smith\",Athlete,Developer", 64));
        assertArrayEquals(new String[]{"a\nb", "b", "\nd", "e"},
                parseSingle("\"a\nb\",b,\"\nd\",e", 64));
        assertArrayEquals(new String[]{"Small test", "This is a test across \ntwo lines."},
                parseSingle("Small test,\"This is a test across \ntwo lines.\"", 64));
        assertArrayEquals(new String[]{"a", "PO Box 123,\r\nKippax,ACT. 2615.\r\nAustralia", "d."},
                parseSingle("a,\"PO Box 123,\r\nKippax,ACT. 2615.\r\nAustralia\",d.", 64));
        assertArrayEquals(new String[]{"", "2"}, parseSingle("\"\",2", 64));
        assertArrayEquals(new String[]{"", "", "", "", ""}, parseSingle(",,,\"\",", 64));
    }

    /**
     * opencsv issue 2726363 (legacy CSVParser): input field {@code ""London"shop"}
     * parses there as {@code "London"shop} via a neighbor-sniffing heuristic (a
     * quote is data only when not adjacent to a separator). That is not RFC 4180
     * semantics; fusio instead preserves every character of the malformed field
     * (empty quoted prefix, then all remaining characters as literal data).
     */
    @Test
    void divergenceLegacyQuoteSniffing() {
        assertArrayEquals(new String[]{"804503689", "London", "\"London\"shop\"", "address"},
                parseSingle("\"804503689\",\"London\",\"\"London\"shop\",\"address\"", 64));
    }

    // CSVParserTest.parseMultipleQuotes: """""","test" -> two literal quotes, then test
    @Test
    void sixQuotesIsTwoLiteralQuotes() {
        assertArrayEquals(new String[]{"\"\"", "test"}, parseSingle("\"\"\"\"\"\",\"test\"", 64));
    }

    // CSVParserTest.parserHandlesNullInString / CSVReaderTest.readerCanHandleNullInString
    @Test
    void nulBytesAreOrdinaryData() {
        assertArrayEquals(new String[]{"a", "\0b", "c"}, parseSingle("a,\0b,c", 64));
    }

    // CSVReaderTest.testIssue102: quoted-empty first fields across two records
    @Test
    void issue102QuotedEmptyFirstField() {
        List<String[]> rows = parse("\"\",a\n\"\",b\n", 64);
        assertEquals(2, rows.size());
        assertArrayEquals(new String[]{"", "a"}, rows.get(0));
        assertArrayEquals(new String[]{"", "b"}, rows.get(1));
    }

    // Bug259 DoubleNewLineTest: "a blank line is a record with a single empty element"
    @Test
    void doubleNewlineYieldsThreeRecords() {
        List<String[]> rows = parse("Hello\n\nWorld", 64);
        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{"Hello"}, rows.get(0));
        assertArrayEquals(new String[]{""}, rows.get(1));
        assertArrayEquals(new String[]{"World"}, rows.get(2));
    }

    // UniCodeTest: CJK content round-trips
    @Test
    void unicodeContent() {
        assertArrayEquals(new String[]{"日本語", "中文", "한국어"},
                parseSingle("日本語,中文,한국어", 3));
    }

    /** opencsv's Bug143.csv fixture (real Etsy export): fusio must agree with RFC4180Parser. */
    @Test
    void bug143FixtureMatchesRfc4180Parser() throws Exception {
        byte[] bytes;
        try (java.io.InputStream in = getClass().getResourceAsStream("/fixtures/opencsv-Bug143.csv")) {
            bytes = readAllBytes(in);
        }
        List<String[]> viaFusio = ByteSource.of(bytes, 1024)
                .via(Utf8.decode())
                .via(Csv.parse())
                .toList();

        List<String[]> viaOpencsv = new java.util.ArrayList<>();
        try (com.opencsv.CSVReader reader = new com.opencsv.CSVReaderBuilder(
                new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes),
                        StandardCharsets.UTF_8))
                .withCSVParser(new com.opencsv.RFC4180ParserBuilder().build())
                .withKeepCarriageReturn(true)
                .build()) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                viaOpencsv.add(row);
            }
        }
        assertEquals(viaOpencsv.size(), viaFusio.size(), "row count");
        for (int i = 0; i < viaOpencsv.size(); i++) {
            assertArrayEquals(viaOpencsv.get(i), viaFusio.get(i), "row " + i);
        }
    }
}
