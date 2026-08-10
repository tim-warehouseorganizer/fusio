package dev.flamelens.fusio;

/**
 * A view over a slice of a byte array — the chunk currency of every pipeline.
 *
 * <p>Ownership contract: a {@code Bytes} handed to {@link Sink#accept} is only
 * valid for the duration of that call. Sources reuse their buffer between
 * chunks; a stage that needs the data later must copy it. This is what makes
 * a pipeline zero-allocation in steady state.
 */
public final class Bytes {
    public final byte[] array;
    public final int offset;
    public final int length;

    public Bytes(byte[] array, int offset, int length) {
        this.array = array;
        this.offset = offset;
        this.length = length;
    }
}
