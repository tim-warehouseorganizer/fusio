package dev.flamelens.fusio;

/**
 * A view over a slice of a char array. Same ownership contract as {@link Bytes}:
 * valid only during the {@link Sink#accept} call it is passed to.
 */
public final class Chars {
    public final char[] array;
    public final int offset;
    public final int length;

    public Chars(char[] array, int offset, int length) {
        this.array = array;
        this.offset = offset;
        this.length = length;
    }
}
