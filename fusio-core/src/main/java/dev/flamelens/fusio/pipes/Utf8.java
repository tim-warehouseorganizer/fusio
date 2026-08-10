package dev.flamelens.fusio.pipes;

import dev.flamelens.fusio.Bytes;
import dev.flamelens.fusio.Chars;
import dev.flamelens.fusio.Pipe;
import dev.flamelens.fusio.Sink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * UTF-8 decode stage. Decodes byte chunks in place (no staging copy on the
 * fast path); a multi-byte sequence split across a chunk boundary is carried
 * as at most 3 bytes and completed against the next chunk. Malformed input
 * decodes to U+FFFD, matching InputStreamReader's default.
 */
public final class Utf8 {

    private Utf8() {
    }

    public static Pipe<Bytes, Chars> decode() {
        return decode(16 * 1024);
    }

    public static Pipe<Bytes, Chars> decode(int outputChars) {
        if (outputChars < 8) {
            throw new IllegalArgumentException("outputChars must be >= 8: " + outputChars);
        }
        return down -> new DecodeSink(down, outputChars);
    }

    /** UTF-8 encode stage — the write-side mirror of {@link #decode()}. */
    public static Pipe<Chars, Bytes> encode() {
        return encode(16 * 1024);
    }

    public static Pipe<Chars, Bytes> encode(int outputBytes) {
        if (outputBytes < 8) {
            throw new IllegalArgumentException("outputBytes must be >= 8: " + outputBytes);
        }
        return down -> new EncodeSink(down, outputBytes);
    }

    private static final class EncodeSink implements Sink<Chars> {
        private final Sink<Bytes> down;
        private final java.nio.charset.CharsetEncoder enc = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private final byte[] out;
        private final ByteBuffer outBuf;
        private char carry;
        private boolean hasCarry = false;
        private boolean stopped = false;

        EncodeSink(Sink<Bytes> down, int outputBytes) {
            this.down = down;
            this.out = new byte[outputBytes];
            this.outBuf = ByteBuffer.wrap(out);
        }

        @Override
        public boolean accept(Chars c) throws IOException {
            if (stopped) {
                return false;
            }
            int off = c.offset;
            int len = c.length;
            if (hasCarry && len > 0) {
                // complete the dangling high surrogate against the next char
                CharBuffer tb = CharBuffer.wrap(new char[]{carry, c.array[off]});
                if (!drain(tb, false)) {
                    return false;
                }
                int consumed = tb.position();
                hasCarry = false;
                // pair consumed -> 1 char of this chunk used; malformed lone
                // surrogate -> replacement emitted, 0 chars of this chunk used
                off += consumed - 1;
                len -= consumed - 1;
            }
            if (len > 0) {
                CharBuffer in = CharBuffer.wrap(c.array, off, len);
                if (!drain(in, false)) {
                    return false;
                }
                if (in.hasRemaining()) { // at most 1 dangling high surrogate
                    carry = in.get();
                    hasCarry = true;
                }
            }
            return true;
        }

        @Override
        public void end() throws IOException {
            if (!stopped) {
                CharBuffer tail = hasCarry
                        ? CharBuffer.wrap(new char[]{carry})
                        : CharBuffer.allocate(0);
                hasCarry = false;
                drain(tail, true);
                while (!stopped && enc.flush(outBuf).isOverflow()) {
                    emit();
                }
                emit();
            }
            down.end();
        }

        private boolean drain(CharBuffer in, boolean endOfInput) throws IOException {
            while (true) {
                CoderResult r = enc.encode(in, outBuf, endOfInput);
                if (r.isOverflow()) {
                    if (!emit()) {
                        return false;
                    }
                } else {
                    return true;
                }
            }
        }

        private boolean emit() throws IOException {
            int n = outBuf.position();
            if (n == 0) {
                return !stopped;
            }
            outBuf.clear();
            if (!down.accept(new Bytes(out, 0, n))) {
                stopped = true;
                return false;
            }
            return true;
        }
    }

    private static final class DecodeSink implements Sink<Bytes> {
        private final Sink<Chars> down;
        private final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private final char[] out;
        private final CharBuffer outBuf;
        private final byte[] carry = new byte[8];
        private int carryLen = 0;
        private boolean stopped = false;

        DecodeSink(Sink<Chars> down, int outputChars) {
            this.down = down;
            this.out = new char[outputChars];
            this.outBuf = CharBuffer.wrap(out);
        }

        @Override
        public boolean accept(Bytes b) throws IOException {
            if (stopped) {
                return false;
            }
            int off = b.offset;
            int len = b.length;

            // Complete any sequence left dangling by the previous chunk. The
            // decoder only ever leaves a valid prefix (<= 3 bytes), so joining
            // it with up to 4 fresh bytes is guaranteed to make progress.
            while (carryLen > 0 && len > 0) {
                int take = Math.min(4, len);
                byte[] tmp = new byte[carryLen + take];
                System.arraycopy(carry, 0, tmp, 0, carryLen);
                System.arraycopy(b.array, off, tmp, carryLen, take);
                ByteBuffer tb = ByteBuffer.wrap(tmp);
                if (!drain(tb, false)) {
                    return false;
                }
                int consumed = tb.position();
                if (consumed == 0) {
                    // whole chunk was < 4 bytes and still doesn't finish the sequence
                    System.arraycopy(b.array, off, carry, carryLen, take);
                    carryLen += take;
                    off += take;
                    len -= take;
                } else if (consumed >= carryLen) {
                    off += consumed - carryLen;
                    len -= consumed - carryLen;
                    carryLen = 0;
                } else {
                    // malformed carry partially consumed as U+FFFD; keep the rest
                    int remain = carryLen - consumed;
                    System.arraycopy(carry, consumed, carry, 0, remain);
                    carryLen = remain;
                }
            }

            if (len > 0) {
                ByteBuffer in = ByteBuffer.wrap(b.array, off, len);
                if (!drain(in, false)) {
                    return false;
                }
                if (in.hasRemaining()) {
                    carryLen = in.remaining();
                    in.get(carry, 0, carryLen);
                }
            }
            return true;
        }

        @Override
        public void end() throws IOException {
            if (!stopped) {
                drain(ByteBuffer.wrap(carry, 0, carryLen), true);
                while (!stopped && dec.flush(outBuf).isOverflow()) {
                    emit();
                }
                emit();
            }
            down.end();
        }

        private boolean drain(ByteBuffer in, boolean endOfInput) throws IOException {
            while (true) {
                CoderResult r = dec.decode(in, outBuf, endOfInput);
                if (r.isOverflow()) {
                    if (!emit()) {
                        return false;
                    }
                } else {
                    return true;
                }
            }
        }

        private boolean emit() throws IOException {
            int n = outBuf.position();
            if (n == 0) {
                return !stopped;
            }
            outBuf.clear();
            if (!down.accept(new Chars(out, 0, n))) {
                stopped = true;
                return false;
            }
            return true;
        }
    }
}
