package dev.flamelens.fusio;

import java.io.IOException;

/**
 * The step function every pipeline compiles down to. A source pushes chunks
 * into a sink; composed pipes are just sinks wrapping sinks, fused at
 * pipeline-build time so the hot loop is one monomorphic call chain.
 */
public interface Sink<T> {

    /** Consume one item. Return {@code false} to stop the source early. */
    boolean accept(T item) throws IOException;

    /**
     * Signal end of input. Stages flush carried state (partial UTF-8 sequence,
     * unterminated line, pending CSV row) and must propagate to downstream.
     */
    default void end() throws IOException {
    }
}
