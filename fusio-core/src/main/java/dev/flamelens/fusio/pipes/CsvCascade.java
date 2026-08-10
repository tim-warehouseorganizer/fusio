package dev.flamelens.fusio.pipes;

import dev.flamelens.fusio.ByteSource;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * Waterfall parsing: try dialects in order, most-preferred first. Every tier
 * except the last runs in detection mode, where constructs whose meaning
 * differs between dialects (a space before a quoted field, a bare quote
 * inside an unquoted field, data after a closing quote, an unterminated
 * quote) raise {@link CsvDialectMismatchException} instead of being silently
 * interpreted — and the cascade re-parses with the next tier. The last tier
 * parses permissively under its own rules and its result stands.
 *
 * <p>Fallback restarts the whole document, not the failing row — record
 * boundaries themselves differ between dialects (an escaped quote in one is
 * an unterminated quote in another), so per-row fallback would be unsound.
 * The source must therefore be replayable, which fusio sources are: byte
 * arrays and files re-run from the start.
 *
 * <p>Honest limitation: detection triggers on quote-structure anomalies
 * only. Backslash-escape intent is undetectable — {@code a\,b} is valid
 * RFC 4180 data (a literal backslash) — so data written in a
 * backslash-escaping dialect parses "successfully" under RFC with different
 * values. If your producers backslash-escape, pin
 * {@link CsvDialect#opencsvLegacy()} instead of cascading.
 */
public final class CsvCascade {

    /** Which tier won, and its rows. */
    public record Result(CsvDialect dialect, int tierIndex, List<String[]> rows) {
    }

    private CsvCascade() {
    }

    /** Default chain: RFC 4180, then Jackson, then opencsv-legacy. */
    public static Result parse(ByteSource source) {
        return parse(source, CsvDialect.rfc4180(), CsvDialect.jackson(), CsvDialect.opencsvLegacy());
    }

    public static Result parse(ByteSource source, CsvDialect... tiers) {
        if (tiers == null || tiers.length == 0) {
            throw new IllegalArgumentException("at least one dialect tier is required");
        }
        for (int i = 0; i < tiers.length; i++) {
            boolean last = i == tiers.length - 1;
            try {
                List<String[]> rows = source
                        .via(Utf8.decode())
                        .via(last ? Csv.parse(tiers[i]) : Csv.parseDetecting(tiers[i]))
                        .toList();
                return new Result(tiers[i], i, rows);
            } catch (UncheckedIOException e) {
                if (last || !(e.getCause() instanceof CsvDialectMismatchException)) {
                    throw e;
                }
            }
        }
        throw new AssertionError("unreachable");
    }
}
