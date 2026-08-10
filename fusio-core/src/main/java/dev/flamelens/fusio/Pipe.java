package dev.flamelens.fusio;

import java.util.Objects;

/**
 * A transformation stage described as a sink transformer (transducer style):
 * given where the output goes, produce the sink the input should be pushed
 * into. Composition happens once, when the pipeline is built — not per chunk —
 * so N composed pipes cost the same dispatch as one.
 */
@FunctionalInterface
public interface Pipe<A, B> {

    /** Build this stage's input sink, wired to push results into {@code downstream}. */
    Sink<A> connect(Sink<B> downstream);

    /** Compose: {@code this} then {@code next}. */
    default <C> Pipe<A, C> then(Pipe<B, C> next) {
        Objects.requireNonNull(next);
        return down -> connect(next.connect(down));
    }

    static <T> Pipe<T, T> identity() {
        return down -> down;
    }
}
