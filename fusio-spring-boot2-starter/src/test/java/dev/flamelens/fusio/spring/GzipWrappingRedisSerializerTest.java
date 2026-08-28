package dev.flamelens.fusio.spring;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GzipWrappingRedisSerializerTest {

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private final GzipWrappingRedisSerializer<String> serializer =
            new GzipWrappingRedisSerializer<>(new StringRedisSerializer(), 256);

    @Test
    void smallValuesPassThroughUncompressed() {
        byte[] stored = serializer.serialize("small value");
        assertArrayEquals(new StringRedisSerializer().serialize("small value"), stored);
        assertEquals("small value", serializer.deserialize(stored));
    }

    @Test
    void largeValuesAreGzippedAndRoundTrip() {
        String big = repeat("repetitive cached report content ", 200); // ~6.6KB
        byte[] stored = serializer.serialize(big);
        assertTrue(stored.length < big.length() / 5, "expected strong compression, got " + stored.length);
        assertEquals((byte) 0x1f, stored[0]);
        assertEquals((byte) 0x8b, stored[1]);
        assertEquals(big, serializer.deserialize(stored));
    }

    @Test
    void preExistingUncompressedEntriesStillDeserialize() {
        // simulates enabling the wrapper on a warm cache
        byte[] legacy = new StringRedisSerializer().serialize(repeat("x", 10_000));
        assertEquals(repeat("x", 10_000), serializer.deserialize(legacy));
    }

    @Test
    void nullsBehaveExactlyLikeTheDelegate() {
        StringRedisSerializer delegate = new StringRedisSerializer();
        assertArrayEquals(delegate.serialize(null), serializer.serialize(null));
        assertEquals(delegate.deserialize(null), serializer.deserialize(null));
    }
}
