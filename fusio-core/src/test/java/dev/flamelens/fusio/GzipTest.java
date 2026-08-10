package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Gzip;
import dev.flamelens.fusio.pipes.Lines;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GzipTest {

    private static final byte[] PLAIN =
            "the quick brown fox, ünïcödé too\n".repeat(5_000).getBytes(StandardCharsets.UTF_8);

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzipVia(byte[] gz, int chunkSize) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ByteSource.of(gz, chunkSize)
                .via(Gzip.gunzip())
                .forEach(b -> bos.write(b.array, b.offset, b.length));
        return bos.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 64, 8192, 1 << 20})
    void roundTripsAtEveryChunkSize(int chunkSize) throws IOException {
        assertArrayEquals(PLAIN, gunzipVia(gzip(PLAIN), chunkSize));
    }

    @Test
    void emptyPayloadRoundTrips() throws IOException {
        assertArrayEquals(new byte[0], gunzipVia(gzip(new byte[0]), 7));
    }

    @Test
    void concatenatedMembersDecompressToConcatenation() throws IOException {
        byte[] a = "first member\n".getBytes(StandardCharsets.UTF_8);
        byte[] b = "second member\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(gzip(a));
        joined.write(gzip(b));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(a);
        expected.write(b);
        assertArrayEquals(expected.toByteArray(), gunzipVia(joined.toByteArray(), 3));
    }

    @Test
    void optionalFilenameHeaderIsSkipped() throws IOException {
        byte[] gz = gzip(PLAIN);
        byte[] name = "data.txt\0".getBytes(StandardCharsets.US_ASCII);
        byte[] withName = new byte[gz.length + name.length];
        System.arraycopy(gz, 0, withName, 0, 10);
        withName[3] |= 8; // FNAME flag
        System.arraycopy(name, 0, withName, 10, name.length);
        System.arraycopy(gz, 10, withName, 10 + name.length, gz.length - 10);
        assertArrayEquals(PLAIN, gunzipVia(withName, 5));
    }

    @Test
    void truncatedStreamThrows() throws IOException {
        byte[] gz = gzip(PLAIN);
        byte[] cut = Arrays.copyOf(gz, gz.length - 5);
        assertThrows(UncheckedIOException.class, () -> gunzipVia(cut, 64));
    }

    @Test
    void corruptedCrcThrows() throws IOException {
        byte[] gz = gzip(PLAIN);
        gz[gz.length - 6] ^= 0x55; // inside the 4-byte trailer CRC
        assertThrows(UncheckedIOException.class, () -> gunzipVia(gz, 64));
    }

    @Test
    void nonGzipInputThrows() {
        assertThrows(UncheckedIOException.class,
                () -> gunzipVia("definitely not gzip data".getBytes(StandardCharsets.UTF_8), 64));
    }

    @Test
    void composesWithDecodeAndLines() throws IOException {
        byte[] gz = gzip("a\nb\nc\n".getBytes(StandardCharsets.UTF_8));
        long lines = ByteSource.of(gz, 3)
                .via(Gzip.gunzip())
                .via(Utf8.decode())
                .via(Lines.split())
                .count();
        assertEquals(3, lines);
    }
}
