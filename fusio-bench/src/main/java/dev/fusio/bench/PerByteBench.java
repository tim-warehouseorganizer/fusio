package dev.fusio.bench;

import org.openjdk.jmh.annotations.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Assumption under test: per-byte APIs are the biggest pathology in java.io.
 * DataInputStream.readInt() makes 4 separate virtual (and, on ByteArrayInputStream,
 * synchronized) read() calls per int. A chunk-oriented decode does one bounds check
 * and one wide load.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PerByteBench {

    static final int INTS = 2 * 1024 * 1024; // 8 MB
    byte[] data;

    @Setup
    public void setup() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(INTS * 4);
        DataOutputStream dos = new DataOutputStream(bos);
        Random r = new Random(42);
        for (int i = 0; i < INTS; i++) dos.writeInt(r.nextInt());
        data = bos.toByteArray();
    }

    /** Classic binary record reading: 4 virtual read() calls per int. */
    @Benchmark
    public long dataInputStreamReadInt() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        long sum = 0;
        for (int i = 0; i < INTS; i++) sum += in.readInt();
        return sum;
    }

    /** Same, with the "fix" everyone applies: buffer it. Still 4 calls per int. */
    @Benchmark
    public long dataInputStreamBufferedReadInt() throws IOException {
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(new ByteArrayInputStream(data)));
        long sum = 0;
        for (int i = 0; i < INTS; i++) sum += in.readInt();
        return sum;
    }

    /** What a chunked pipeline stage compiles to. */
    @Benchmark
    public long byteBufferGetInt() {
        ByteBuffer bb = ByteBuffer.wrap(data);
        long sum = 0;
        for (int i = 0; i < INTS; i++) sum += bb.getInt();
        return sum;
    }
}
