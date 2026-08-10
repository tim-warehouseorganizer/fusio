package dev.flamelens.fusio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * A source plus a composed transformation — still just a description. Terminal
 * operations connect the fused sink chain, drain the source once, and flush
 * carried state via {@link Sink#end}. IOExceptions surface as
 * {@link UncheckedIOException} so fold lambdas stay clean.
 */
public final class Pipeline<T> {

    private final ByteSource source;
    private final Pipe<Bytes, T> pipe;

    Pipeline(ByteSource source, Pipe<Bytes, T> pipe) {
        this.source = source;
        this.pipe = pipe;
    }

    public <U> Pipeline<U> via(Pipe<T, U> next) {
        return new Pipeline<>(source, pipe.then(next));
    }

    public <R> R fold(R seed, BiFunction<R, ? super T, ? extends R> f) {
        class Folder implements Sink<T> {
            R acc = seed;

            @Override
            public boolean accept(T item) {
                acc = f.apply(acc, item);
                return true;
            }
        }
        Folder folder = new Folder();
        run(folder);
        return folder.acc;
    }

    public long foldLong(long seed, LongFolder<? super T> f) {
        class Folder implements Sink<T> {
            long acc = seed;

            @Override
            public boolean accept(T item) {
                acc = f.apply(acc, item);
                return true;
            }
        }
        Folder folder = new Folder();
        run(folder);
        return folder.acc;
    }

    public long count() {
        return foldLong(0L, (n, item) -> n + 1);
    }

    public void forEach(Consumer<? super T> action) {
        run(item -> {
            action.accept(item);
            return true;
        });
    }

    public List<T> toList() {
        List<T> out = new ArrayList<>();
        forEach(out::add);
        return out;
    }

    private void run(Sink<T> terminal) {
        try {
            Sink<Bytes> head = pipe.connect(terminal);
            source.drainTo(head);
            head.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
