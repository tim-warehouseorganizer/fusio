package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Lines;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinesTest {

    private static List<String> viaFusio(String text, int chunkSize) {
        return ByteSource.of(text.getBytes(StandardCharsets.UTF_8), chunkSize)
                .via(Utf8.decode())
                .via(Lines.split())
                .toList();
    }

    private static List<String> viaBufferedReader(String text) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line);
            }
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64, 8192})
    void matchesReadLineOnRandomTerminatorSoup(int chunkSize) throws IOException {
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder();
        String[] terminators = {"\n", "\r", "\r\n"};
        for (int i = 0; i < 500; i++) {
            int len = r.nextInt(12);
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            sb.append(terminators[r.nextInt(terminators.length)]);
        }
        sb.append("no trailing newline");
        String text = sb.toString();
        assertEquals(viaBufferedReader(text), viaFusio(text, chunkSize));
    }

    @Test
    void emptyInputHasNoLines() {
        assertEquals(List.of(), viaFusio("", 64));
    }

    @Test
    void trailingNewlineDoesNotEmitEmptyLastLine() {
        assertEquals(List.of("a", "b"), viaFusio("a\nb\n", 64));
    }

    @Test
    void missingTrailingNewlineStillEmitsLastLine() {
        assertEquals(List.of("a", "b"), viaFusio("a\nb", 64));
    }

    @Test
    void blankLinesArePreserved() {
        assertEquals(List.of("a", "", "b"), viaFusio("a\n\nb\n", 64));
    }

    @Test
    void crlfSplitAcrossChunkBoundaryIsOneTerminator() {
        // chunk size 3 puts the boundary between \r and \n
        assertEquals(List.of("ab", "cd"), viaFusio("ab\r\ncd", 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64, 8192})
    void byteLinesMatchSplitOnMultibyteText(int chunkSize) {
        // multibyte chars ensure byte-level splitting never cuts a sequence
        String text = "état: größe 汉字 😀\nsecond ligne\r\nthird\rlast no newline";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        List<String> viaBytes = new ArrayList<>();
        ByteSource.of(bytes, chunkSize)
                .via(Lines.bytes())
                .forEach(b -> viaBytes.add(new String(b.array, b.offset, b.length, StandardCharsets.UTF_8)));
        assertEquals(viaFusio(text, chunkSize), viaBytes);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64, 8192})
    void viewsMatchSplitOnRandomTerminatorSoup(int chunkSize) {
        Random r = new Random(43);
        StringBuilder sb = new StringBuilder();
        String[] terminators = {"\n", "\r", "\r\n"};
        for (int i = 0; i < 500; i++) {
            int len = r.nextInt(12);
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            sb.append(terminators[r.nextInt(terminators.length)]);
        }
        sb.append("tail without newline");
        String text = sb.toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        // views are only valid during accept, so materialize inside the fold
        List<String> viaViews = new ArrayList<>();
        ByteSource.of(bytes, chunkSize)
                .via(Utf8.decode())
                .via(Lines.views())
                .forEach(c -> viaViews.add(new String(c.array, c.offset, c.length)));

        assertEquals(viaFusio(text, chunkSize), viaViews);
    }
}
