package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Lines;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MmapTest {

    @Test
    void byteSourceMatchesFileContents(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("blob.bin");
        byte[] data = new byte[300_001]; // deliberately not a multiple of the chunk size
        new Random(9).nextBytes(data);
        Files.write(f, data);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Mmap.byteSource(f, 7_777).chunks()
                .forEach(b -> bos.write(b.array, b.offset, b.length));
        assertArrayEquals(data, bos.toByteArray());
    }

    @Test
    void segmentFoldSeesEveryByte(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("blob.bin");
        byte[] data = new byte[100_003];
        new Random(11).nextBytes(data);
        Files.write(f, data);

        long expected = 0;
        for (byte b : data) {
            expected += b & 0xff;
        }
        long actual = Mmap.foldLong(f, 9_999, 0L, (acc, seg) -> {
            long sum = acc;
            long n = seg.byteSize();
            for (long i = 0; i < n; i++) {
                sum += seg.get(ValueLayout.JAVA_BYTE, i) & 0xff;
            }
            return sum;
        });
        assertEquals(expected, actual);
    }

    @Test
    void emptyFileYieldsNothing(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("empty.bin");
        Files.write(f, new byte[0]);
        assertEquals(0, Mmap.byteSource(f).chunks().count());
        assertEquals(42, Mmap.foldLong(f, 1024, 42L, (acc, seg) -> acc + 1));
    }

    @Test
    void byteSourceComposesWithPipes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("text.txt");
        Files.write(f, ("alpha\nbeta\ngamma\n".repeat(1_000)).getBytes(StandardCharsets.UTF_8));
        long lines = Mmap.byteSource(f).via(Utf8.decode()).via(Lines.split()).count();
        assertEquals(3_000, lines);
    }
}

