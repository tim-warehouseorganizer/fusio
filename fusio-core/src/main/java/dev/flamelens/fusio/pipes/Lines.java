package dev.flamelens.fusio.pipes;

import dev.flamelens.fusio.Bytes;
import dev.flamelens.fusio.Chars;
import dev.flamelens.fusio.Pipe;
import dev.flamelens.fusio.Sink;

import java.io.IOException;

/**
 * Line splitting with BufferedReader.readLine semantics: lines end at
 * {@code \n}, {@code \r}, or {@code \r\n}; terminators are not included; a
 * final line without a terminator is still emitted. Lines that fall entirely
 * inside one chunk take a single String allocation; only lines spanning
 * chunks touch the carry buffer.
 */
public final class Lines {

    private Lines() {
    }

    /**
     * Byte-level line splitting — no charset decode at all. Sound for UTF-8
     * (and any ASCII-compatible encoding) because {@code \n}/{@code \r} can
     * never occur inside a multibyte sequence: continuation bytes are always
     * {@code >= 0x80}. Profiling showed decode is ~half the cost of a text
     * pipeline; when the consumer only needs some lines (filtering, sampling,
     * counting), split first and decode per line on demand. Emits {@link Bytes}
     * views under the standard ownership contract.
     */
    public static Pipe<Bytes, Bytes> bytes() {
        return down -> new Sink<Bytes>() {
            private byte[] carry = new byte[256];
            private int carryLen = 0;
            private boolean skipLF = false;
            private boolean stopped = false;

            @Override
            public boolean accept(Bytes b) throws IOException {
                if (stopped) {
                    return false;
                }
                byte[] a = b.array;
                int i = b.offset;
                int end = b.offset + b.length;
                if (skipLF && i < end) {
                    if (a[i] == '\n') {
                        i++;
                    }
                    skipLF = false;
                }
                int start = i;
                while (i < end) {
                    byte ch = a[i];
                    if (ch == '\n' || ch == '\r') {
                        Bytes line;
                        if (carryLen == 0) {
                            line = new Bytes(a, start, i - start);
                        } else {
                            appendCarry(a, start, i - start);
                            line = new Bytes(carry, 0, carryLen);
                            carryLen = 0;
                        }
                        if (ch == '\r') {
                            if (i + 1 < end) {
                                if (a[i + 1] == '\n') {
                                    i++;
                                }
                            } else {
                                skipLF = true;
                            }
                        }
                        i++;
                        start = i;
                        if (!down.accept(line)) {
                            stopped = true;
                            return false;
                        }
                    } else {
                        i++;
                    }
                }
                if (start < end) {
                    appendCarry(a, start, end - start);
                }
                return true;
            }

            @Override
            public void end() throws IOException {
                if (!stopped && carryLen > 0) {
                    down.accept(new Bytes(carry, 0, carryLen));
                    carryLen = 0;
                }
                down.end();
            }

            private void appendCarry(byte[] a, int from, int len) {
                if (carryLen + len > carry.length) {
                    carry = java.util.Arrays.copyOf(carry, Math.max(carry.length * 2, carryLen + len));
                }
                System.arraycopy(a, from, carry, carryLen, len);
                carryLen += len;
            }
        };
    }

    /**
     * Zero-allocation variant of {@link #split()}: emits each line as a
     * {@link Chars} view instead of a String. Lines contained in one chunk are
     * slices of the decode buffer; only lines spanning chunks touch the carry
     * buffer. Views obey the standard ownership contract — valid only during
     * the {@link Sink#accept} call.
     */
    public static Pipe<Chars, Chars> views() {
        return down -> new Sink<Chars>() {
            private char[] carry = new char[128];
            private int carryLen = 0;
            private boolean skipLF = false;
            private boolean stopped = false;

            @Override
            public boolean accept(Chars c) throws IOException {
                if (stopped) {
                    return false;
                }
                char[] a = c.array;
                int i = c.offset;
                int end = c.offset + c.length;
                if (skipLF && i < end) {
                    if (a[i] == '\n') {
                        i++;
                    }
                    skipLF = false;
                }
                int start = i;
                while (i < end) {
                    char ch = a[i];
                    if (ch == '\n' || ch == '\r') {
                        Chars line;
                        if (carryLen == 0) {
                            line = new Chars(a, start, i - start);
                        } else {
                            appendCarry(a, start, i - start);
                            line = new Chars(carry, 0, carryLen);
                            carryLen = 0;
                        }
                        if (ch == '\r') {
                            if (i + 1 < end) {
                                if (a[i + 1] == '\n') {
                                    i++;
                                }
                            } else {
                                skipLF = true;
                            }
                        }
                        i++;
                        start = i;
                        if (!down.accept(line)) {
                            stopped = true;
                            return false;
                        }
                    } else {
                        i++;
                    }
                }
                if (start < end) {
                    appendCarry(a, start, end - start);
                }
                return true;
            }

            @Override
            public void end() throws IOException {
                if (!stopped && carryLen > 0) {
                    down.accept(new Chars(carry, 0, carryLen));
                    carryLen = 0;
                }
                down.end();
            }

            private void appendCarry(char[] a, int from, int len) {
                if (carryLen + len > carry.length) {
                    carry = java.util.Arrays.copyOf(carry, Math.max(carry.length * 2, carryLen + len));
                }
                System.arraycopy(a, from, carry, carryLen, len);
                carryLen += len;
            }
        };
    }

    public static Pipe<Chars, String> split() {
        return down -> new Sink<Chars>() {
            private final StringBuilder partial = new StringBuilder();
            private boolean skipLF = false;
            private boolean stopped = false;

            @Override
            public boolean accept(Chars c) throws IOException {
                if (stopped) {
                    return false;
                }
                char[] a = c.array;
                int i = c.offset;
                int end = c.offset + c.length;
                if (skipLF && i < end) {
                    if (a[i] == '\n') {
                        i++;
                    }
                    skipLF = false;
                }
                int start = i;
                while (i < end) {
                    char ch = a[i];
                    if (ch == '\n' || ch == '\r') {
                        String line;
                        if (partial.length() == 0) {
                            line = new String(a, start, i - start);
                        } else {
                            partial.append(a, start, i - start);
                            line = partial.toString();
                            partial.setLength(0);
                        }
                        if (ch == '\r') {
                            if (i + 1 < end) {
                                if (a[i + 1] == '\n') {
                                    i++;
                                }
                            } else {
                                skipLF = true;
                            }
                        }
                        i++;
                        start = i;
                        if (!down.accept(line)) {
                            stopped = true;
                            return false;
                        }
                    } else {
                        i++;
                    }
                }
                if (start < end) {
                    partial.append(a, start, end - start);
                }
                return true;
            }

            @Override
            public void end() throws IOException {
                if (!stopped && partial.length() > 0) {
                    down.accept(partial.toString());
                    partial.setLength(0);
                }
                down.end();
            }
        };
    }
}
