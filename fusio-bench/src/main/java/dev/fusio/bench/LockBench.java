package dev.fusio.bench;

import org.openjdk.jmh.annotations.*;

import java.io.*;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Assumption under test: java.io locks on every operation even for single-threaded
 * use, and since biased locking was removed (JDK 15) each uncontended synchronized
 * call costs a real atomic operation.
 *
 * Both variants do identical buffered per-byte reads over the same 4 MB; the only
 * difference is the lock. (BufferedInputStream on modern JDKs uses an internal
 * lock object on every public method for virtual-thread friendliness — same cost,
 * same reason.)
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LockBench {

    byte[] data;

    @Setup
    public void setup() {
        data = new byte[4 * 1024 * 1024];
        new Random(42).nextBytes(data);
    }

    /** JDK BufferedInputStream: every read() takes the lock. */
    @Benchmark
    public long jdkBufferedPerByte() throws IOException {
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(data), 8192);
        long sum = 0;
        int b;
        while ((b = in.read()) != -1) sum += b;
        return sum;
    }

    /** Identical buffering logic, no lock — single-owner by construction. */
    @Benchmark
    public long unsyncBufferedPerByte() throws IOException {
        UnsyncBuffered in = new UnsyncBuffered(new ByteArrayInputStream(data), 8192);
        long sum = 0;
        int b;
        while ((b = in.read()) != -1) sum += b;
        return sum;
    }

    static final class UnsyncBuffered {
        private final InputStream src;
        private final byte[] buf;
        private int pos, count;

        UnsyncBuffered(InputStream src, int size) {
            this.src = src;
            this.buf = new byte[size];
        }

        int read() throws IOException {
            if (pos >= count) {
                count = src.read(buf, 0, buf.length);
                pos = 0;
                if (count <= 0) return -1;
            }
            return buf[pos++] & 0xff;
        }
    }
}
