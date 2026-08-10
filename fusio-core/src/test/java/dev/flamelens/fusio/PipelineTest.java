package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Lines;
import dev.flamelens.fusio.pipes.Utf8;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineTest {

    @Test
    void fileSourceStreamsWholeFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("data.txt");
        String text = "alpha\nbeta\ngamma\n".repeat(10_000); // spans several 64K chunks
        Files.writeString(f, text, StandardCharsets.UTF_8);

        long lines = ByteSource.file(f).via(Utf8.decode()).via(Lines.split()).count();
        assertEquals(30_000, lines);

        long bytes = ByteSource.file(f).foldLong(0L, (n, b) -> n + b.length);
        assertEquals(Files.size(f), bytes);
    }

    @Test
    void foldAccumulates() {
        byte[] data = "1\n2\n3\n".getBytes(StandardCharsets.UTF_8);
        int sum = ByteSource.of(data)
                .via(Utf8.decode())
                .via(Lines.split())
                .fold(0, (acc, line) -> acc + Integer.parseInt(line));
        assertEquals(6, sum);
    }

    @Test
    void toListCollectsInOrder() {
        byte[] data = "x\ny\n".getBytes(StandardCharsets.UTF_8);
        List<String> lines = ByteSource.of(data).via(Utf8.decode()).via(Lines.split()).toList();
        assertEquals(List.of("x", "y"), lines);
    }

    @Test
    void pipelineIsReusable() {
        Pipeline<String> p = ByteSource.of("a\nb\n".getBytes(StandardCharsets.UTF_8))
                .via(Utf8.decode())
                .via(Lines.split());
        assertEquals(2, p.count());
        assertEquals(2, p.count()); // a pipeline is a value; running it twice is fine
    }

    @Test
    void ioErrorsSurfaceAsUncheckedIOException(@TempDir Path dir) {
        Path missing = dir.resolve("nope.txt");
        assertThrows(UncheckedIOException.class,
                () -> ByteSource.file(missing).via(Utf8.decode()).count());
    }
}
