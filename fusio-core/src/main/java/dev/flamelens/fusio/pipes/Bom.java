package dev.flamelens.fusio.pipes;

import dev.flamelens.fusio.Bytes;
import dev.flamelens.fusio.Pipe;
import dev.flamelens.fusio.Sink;

import java.io.IOException;

/**
 * Strips a leading UTF-8 byte-order mark (EF BB BF) if present, even when it
 * is split across chunk boundaries. Bytes that merely look like the start of
 * a BOM are re-emitted once the mismatch is known. Compose ahead of
 * {@link Utf8#decode()} to match Jackson's BOM-consuming behavior;
 * java.io's InputStreamReader (and opencsv) do not strip BOMs.
 */
public final class Bom {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private Bom() {
    }

    public static Pipe<Bytes, Bytes> strip() {
        return down -> new Sink<Bytes>() {
            private int matched = 0;
            private boolean decided = false;
            private boolean stopped = false;

            @Override
            public boolean accept(Bytes b) throws IOException {
                if (stopped) {
                    return false;
                }
                int off = b.offset;
                int len = b.length;
                while (!decided && len > 0) {
                    if (b.array[off] == UTF8_BOM[matched]) {
                        matched++;
                        off++;
                        len--;
                        if (matched == UTF8_BOM.length) {
                            decided = true; // full BOM consumed; drop it
                        }
                    } else {
                        decided = true;
                        if (matched > 0 && !down.accept(new Bytes(UTF8_BOM, 0, matched))) {
                            stopped = true;
                            return false;
                        }
                    }
                }
                if (len > 0) {
                    if (!down.accept(new Bytes(b.array, off, len))) {
                        stopped = true;
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void end() throws IOException {
                if (!stopped && !decided && matched > 0) {
                    down.accept(new Bytes(UTF8_BOM, 0, matched));
                }
                down.end();
            }
        };
    }
}
