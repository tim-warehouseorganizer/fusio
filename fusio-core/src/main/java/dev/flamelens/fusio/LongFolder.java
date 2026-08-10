package dev.flamelens.fusio;

/** Primitive-accumulator fold step — avoids Long boxing in hot terminal loops. */
@FunctionalInterface
public interface LongFolder<T> {
    long apply(long acc, T item);
}
