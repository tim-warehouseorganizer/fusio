package dev.flamelens.fusio.pipes;

import dev.flamelens.fusio.Bytes;
import dev.flamelens.fusio.Pipe;
import dev.flamelens.fusio.Sink;

import java.io.EOFException;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * Streaming gzip decompression as a push stage: full RFC 1952 header state
 * machine (FEXTRA/FNAME/FCOMMENT/FHCRC, all of which may span chunk
 * boundaries), CRC32 + ISIZE trailer verification, and concatenated
 * multi-member streams, matching GZIPInputStream semantics without the
 * pull-based decorator around it.
 */
public final class Gzip {

    private Gzip() {
    }

    public static Pipe<Bytes, Bytes> gunzip() {
        return gunzip(64 * 1024);
    }

    public static Pipe<Bytes, Bytes> gunzip(int outputBytes) {
        if (outputBytes < 1024) {
            throw new IllegalArgumentException("outputBytes must be >= 1024: " + outputBytes);
        }
        return down -> new GunzipSink(down, outputBytes);
    }

    /** The write-side mirror: gzip compression (RFC 1952 member with CRC32 + ISIZE trailer). */
    public static Pipe<Bytes, Bytes> gzip() {
        return gzip(64 * 1024);
    }

    public static Pipe<Bytes, Bytes> gzip(int outputBytes) {
        if (outputBytes < 1024) {
            throw new IllegalArgumentException("outputBytes must be >= 1024: " + outputBytes);
        }
        return down -> new GzipCompressSink(down, outputBytes);
    }

    private static final class GzipCompressSink implements Sink<Bytes> {
        private static final byte[] HEADER = {
                0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff
        };

        private final Sink<Bytes> down;
        private final java.util.zip.Deflater def =
                new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        private final CRC32 crc = new CRC32();
        private final byte[] out;
        private boolean headerSent = false;
        private boolean stopped = false;

        GzipCompressSink(Sink<Bytes> down, int outputBytes) {
            this.down = down;
            this.out = new byte[outputBytes];
        }

        @Override
        public boolean accept(Bytes b) throws IOException {
            if (stopped) {
                return false;
            }
            if (!headerSent) {
                headerSent = true;
                if (!down.accept(new Bytes(HEADER, 0, HEADER.length))) {
                    stopped = true;
                    return false;
                }
            }
            crc.update(b.array, b.offset, b.length);
            def.setInput(b.array, b.offset, b.length);
            while (!def.needsInput()) {
                int n = def.deflate(out, 0, out.length);
                if (n > 0 && !down.accept(new Bytes(out, 0, n))) {
                    stopped = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public void end() throws IOException {
            try {
                if (!stopped) {
                    if (!headerSent) {
                        headerSent = true;
                        if (!down.accept(new Bytes(HEADER, 0, HEADER.length))) {
                            stopped = true;
                        }
                    }
                    def.finish();
                    while (!stopped && !def.finished()) {
                        int n = def.deflate(out, 0, out.length);
                        if (n > 0 && !down.accept(new Bytes(out, 0, n))) {
                            stopped = true;
                        }
                    }
                    if (!stopped) {
                        byte[] trailer = new byte[8];
                        writeUIntLE(trailer, 0, crc.getValue());
                        writeUIntLE(trailer, 4, def.getBytesRead() & 0xffffffffL);
                        down.accept(new Bytes(trailer, 0, 8));
                    }
                }
            } finally {
                def.end();
            }
            down.end();
        }

        private static void writeUIntLE(byte[] b, int off, long v) {
            b[off] = (byte) v;
            b[off + 1] = (byte) (v >>> 8);
            b[off + 2] = (byte) (v >>> 16);
            b[off + 3] = (byte) (v >>> 24);
        }
    }

    private static final class GunzipSink implements Sink<Bytes> {
        private static final int FHCRC_BIT = 2, FEXTRA_BIT = 4, FNAME_BIT = 8, FCOMMENT_BIT = 16;

        private static final int HEADER = 0;
        private static final int FEXTRA_LEN = 1;
        private static final int FEXTRA_SKIP = 2;
        private static final int FNAME = 3;
        private static final int FCOMMENT = 4;
        private static final int FHCRC = 5;
        private static final int DEFLATE = 6;
        private static final int TRAILER = 7;

        private final Sink<Bytes> down;
        private final Inflater inf = new Inflater(true);
        private final CRC32 crc = new CRC32();
        private final byte[] out;
        private final byte[] small = new byte[10]; // header/trailer accumulator
        private int smallLen = 0;
        private int flg;
        private int skip;
        private int state = HEADER;
        private boolean stopped = false;

        GunzipSink(Sink<Bytes> down, int outputBytes) {
            this.down = down;
            this.out = new byte[outputBytes];
        }

        @Override
        public boolean accept(Bytes b) throws IOException {
            if (stopped) {
                return false;
            }
            byte[] a = b.array;
            int i = b.offset;
            int end = b.offset + b.length;
            while (i < end) {
                switch (state) {
                    case HEADER:
                        small[smallLen++] = a[i++];
                        if (smallLen == 10) {
                            if ((small[0] & 0xff) != 0x1f || (small[1] & 0xff) != 0x8b) {
                                throw new ZipException("not a gzip stream");
                            }
                            if (small[2] != 8) {
                                throw new ZipException("unsupported gzip compression method: " + small[2]);
                            }
                            flg = small[3] & 0xff;
                            smallLen = 0;
                            state = (flg & FEXTRA_BIT) != 0 ? FEXTRA_LEN : afterExtra();
                        }
                        break;
                    case FEXTRA_LEN:
                        small[smallLen++] = a[i++];
                        if (smallLen == 2) {
                            skip = (small[0] & 0xff) | ((small[1] & 0xff) << 8);
                            smallLen = 0;
                            state = skip == 0 ? afterExtra() : FEXTRA_SKIP;
                        }
                        break;
                    case FEXTRA_SKIP: {
                        int n = Math.min(skip, end - i);
                        i += n;
                        skip -= n;
                        if (skip == 0) {
                            state = afterExtra();
                        }
                        break;
                    }
                    case FNAME:
                        while (i < end && a[i] != 0) {
                            i++;
                        }
                        if (i < end) {
                            i++;
                            state = afterName();
                        }
                        break;
                    case FCOMMENT:
                        while (i < end && a[i] != 0) {
                            i++;
                        }
                        if (i < end) {
                            i++;
                            state = afterComment();
                        }
                        break;
                    case FHCRC:
                        small[smallLen++] = a[i++];
                        if (smallLen == 2) {
                            smallLen = 0;
                            state = DEFLATE;
                        }
                        break;
                    case DEFLATE:
                        inf.setInput(a, i, end - i);
                        if (!inflateAll()) {
                            return false;
                        }
                        i = end - inf.getRemaining();
                        if (inf.finished()) {
                            state = TRAILER;
                            smallLen = 0;
                        }
                        break;
                    case TRAILER:
                        small[smallLen++] = a[i++];
                        if (smallLen == 8) {
                            long expectedCrc = readUIntLE(small, 0);
                            long expectedSize = readUIntLE(small, 4);
                            if (expectedCrc != crc.getValue()) {
                                throw new ZipException("corrupt gzip stream: CRC mismatch");
                            }
                            if (expectedSize != (inf.getBytesWritten() & 0xffffffffL)) {
                                throw new ZipException("corrupt gzip stream: size mismatch");
                            }
                            inf.reset();
                            crc.reset();
                            smallLen = 0;
                            state = HEADER; // a following member is legal (concatenated gzip)
                        }
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
            return true;
        }

        @Override
        public void end() throws IOException {
            inf.end();
            if (!stopped && !(state == HEADER && smallLen == 0)) {
                throw new EOFException("truncated gzip stream");
            }
            down.end();
        }

        private boolean inflateAll() throws IOException {
            while (true) {
                int n;
                try {
                    n = inf.inflate(out, 0, out.length);
                } catch (DataFormatException e) {
                    throw new ZipException("corrupt gzip stream: " + e.getMessage());
                }
                if (n > 0) {
                    crc.update(out, 0, n);
                    if (!down.accept(new Bytes(out, 0, n))) {
                        stopped = true;
                        return false;
                    }
                } else if (inf.needsDictionary()) {
                    throw new ZipException("gzip stream requires a preset dictionary");
                } else {
                    return true; // finished or needs more input
                }
            }
        }

        private int afterExtra() {
            return (flg & FNAME_BIT) != 0 ? FNAME : afterName();
        }

        private int afterName() {
            return (flg & FCOMMENT_BIT) != 0 ? FCOMMENT : afterComment();
        }

        private int afterComment() {
            return (flg & FHCRC_BIT) != 0 ? FHCRC : DEFLATE;
        }

        private static long readUIntLE(byte[] b, int off) {
            return (b[off] & 0xffL)
                    | ((b[off + 1] & 0xffL) << 8)
                    | ((b[off + 2] & 0xffL) << 16)
                    | ((b[off + 3] & 0xffL) << 24);
        }
    }
}
