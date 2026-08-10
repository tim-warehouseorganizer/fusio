package dev.flamelens.fusio;

import dev.flamelens.fusio.pipes.Gzip;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SegmentsTest {

    @Test
    void collectRoundTripsAcrossBlockBoundaries() {
        byte[] data = new byte[3 * 256 * 1024 + 12_345]; // spans several growth blocks
        new Random(5).nextBytes(data);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Segments.collect(ByteSource.of(data, 7_001).chunks(), arena);
            assertEquals(data.length, seg.byteSize());
            assertArrayEquals(data, seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void collectEmptyPipelineGivesEmptySegment() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Segments.collect(ByteSource.of(new byte[0]).chunks(), arena);
            assertEquals(0, seg.byteSize());
        }
    }

    @Test
    void gunzipStraightToOffHeap() throws IOException {
        byte[] plain = "off-heap ingestion target\n".repeat(20_000).getBytes();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(plain);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Segments.collect(
                    ByteSource.of(bos.toByteArray()).via(Gzip.gunzip()), arena);
            assertArrayEquals(plain, seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void collectIntoWritesAtOffsetZeroAndReportsLength() {
        byte[] data = new byte[100_000];
        new Random(6).nextBytes(data);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = arena.allocate(data.length + 50);
            long written = Segments.collectInto(ByteSource.of(data, 999).chunks(), target);
            assertEquals(data.length, written);
            assertArrayEquals(data, target.asSlice(0, written).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void collectIntoOverflowThrows() {
        byte[] data = new byte[10_000];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment small = arena.allocate(100);
            assertThrows(UncheckedIOException.class,
                    () -> Segments.collectInto(ByteSource.of(data).chunks(), small));
        }
    }
}
