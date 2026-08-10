package dev.flamelens.fusio.pipes;

import java.io.IOException;

/**
 * The input contains a construct the active {@link CsvDialect} rejects (or,
 * in {@link CsvCascade} detection mode, one whose meaning differs between
 * dialects). CsvCascade catches this to fall through to the next tier.
 */
public class CsvDialectMismatchException extends IOException {

    public CsvDialectMismatchException(String message) {
        super(message);
    }
}
