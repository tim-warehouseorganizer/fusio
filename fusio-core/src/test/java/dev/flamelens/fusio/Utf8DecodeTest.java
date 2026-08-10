package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8DecodeTest {

    // ascii, 2-byte (é, ß), 3-byte (汉, €), 4-byte (😀 -> surrogate pair)
    private static final String MIXED =
            "plain ascii, then é ß 汉字 € and emoji 😀🚀 mixed into text\n".repeat(40);

    private static String decodeVia(byte[] bytes, int chunkSize, int outChars) {
        StringBuilder sb = new StringBuilder();
        ByteSource.of(bytes, chunkSize)
                .via(Utf8.decode(outChars))
                .forEach(c -> sb.append(c.array, c.offset, c.length));
        return sb.toString();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 13, 64, 8192})
    void roundTripsAtEveryChunkSize(int chunkSize) {
        byte[] bytes = MIXED.getBytes(StandardCharsets.UTF_8);
        assertEquals(MIXED, decodeVia(bytes, chunkSize, 8192));
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 9, 16, 17})
    void roundTripsWithTinyOutputBuffers(int outChars) {
        byte[] bytes = MIXED.getBytes(StandardCharsets.UTF_8);
        assertEquals(MIXED, decodeVia(bytes, 3, outChars));
    }

    @Test
    void emptyInputProducesNothing() {
        assertEquals("", decodeVia(new byte[0], 64, 64));
    }

    @Test
    void truncatedMultibyteTailBecomesReplacementChar() {
        byte[] full = "a汉".getBytes(StandardCharsets.UTF_8); // 1 + 3 bytes
        byte[] truncated = new byte[3];                        // 'a' + 2 of the 3 bytes
        System.arraycopy(full, 0, truncated, 0, 3);
        assertEquals("a�", decodeVia(truncated, 1, 64));
    }

    @Test
    void malformedByteMidStreamBecomesReplacementChar() {
        byte[] bad = {'a', (byte) 0xFF, 'b'};
        assertEquals("a�b", decodeVia(bad, 1, 64));
    }

    @Test
    void matchesJdkDecodingOnRandomBytes() {
        java.util.Random r = new java.util.Random(7);
        byte[] noise = new byte[8 * 1024];
        r.nextBytes(noise); // mostly malformed — both decoders must agree on U+FFFD placement
        String expected = new String(noise, StandardCharsets.UTF_8);
        assertEquals(expected, decodeVia(noise, 17, 100));
    }
}
