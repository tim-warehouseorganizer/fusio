package dev.flamelens.fusio.jdbc;

import dev.flamelens.fusio.interop.CsvInputStream;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bulk row loading in three tiers, fastest available first:
 *
 * <ul>
 *   <li>{@link #mysqlLoadData} — streams rows as CSV straight into MySQL's
 *       {@code LOAD DATA LOCAL INFILE}, the fastest ingestion path MySQL has.
 *       Rows are formatted lazily ({@link CsvInputStream}); memory stays
 *       bounded regardless of row count. Requires
 *       {@code allowLoadLocalInfile=true} on the JDBC URL and
 *       {@code local_infile=1} on the server.</li>
 *   <li>{@link #postgresCopy} — the same idea via Postgres's
 *       {@code COPY ... FROM STDIN (FORMAT csv)}.</li>
 *   <li>{@link #batchInsert} — plain JDBC prepared-statement batching; works
 *       on any driver (H2, anything). The portable fallback and the honest
 *       baseline the fast paths are benchmarked against.</li>
 * </ul>
 *
 * Values are bound/emitted as strings; the database performs its usual
 * implicit conversion into column types. Null row elements become SQL
 * empty-string via the CSV paths and {@code NULL} via {@code batchInsert} —
 * if that distinction matters for a table, normalize before loading.
 * Identifiers are validated against a conservative pattern; everything else
 * is parameterized or streamed, never concatenated.
 */
public final class JdbcBulkLoad {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private JdbcBulkLoad() {
    }

    /** Portable prepared-statement batching. Returns rows inserted. */
    public static long batchInsert(Connection connection, String table, List<String> columns,
                                   Iterator<String[]> rows, int batchSize) throws SQLException {
        validate(table, columns);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + placeholders + ")";
        long total = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int pending = 0;
            while (rows.hasNext()) {
                String[] row = rows.next();
                if (row.length != columns.size()) {
                    throw new SQLException("row has " + row.length + " values, expected "
                            + columns.size());
                }
                for (int i = 0; i < row.length; i++) {
                    ps.setString(i + 1, row[i]);
                }
                ps.addBatch();
                if (++pending == batchSize) {
                    total += sum(ps.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                total += sum(ps.executeBatch());
            }
        }
        return total;
    }

    /** MySQL {@code LOAD DATA LOCAL INFILE} fed from a lazy CSV stream. Returns rows loaded. */
    public static long mysqlLoadData(Connection connection, String table, List<String> columns,
                                     Iterator<String[]> rows) throws SQLException {
        validate(table, columns);
        try (Statement st = connection.createStatement()) {
            com.mysql.cj.jdbc.JdbcStatement mysql = st.unwrap(com.mysql.cj.jdbc.JdbcStatement.class);
            mysql.setLocalInfileInputStream(new CsvInputStream(rows));
            // ESCAPED BY '' because RFC 4180 escapes quotes by doubling, not backslashes
            String sql = "LOAD DATA LOCAL INFILE 'fusio-stream' INTO TABLE " + table
                    + " CHARACTER SET utf8mb4"
                    + " FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"' ESCAPED BY ''"
                    + " LINES TERMINATED BY '\n'"
                    + " (" + String.join(", ", columns) + ")";
            return st.executeUpdate(sql);
        }
    }

    /** Postgres {@code COPY ... FROM STDIN (FORMAT csv)} fed from a lazy CSV stream. Returns rows copied. */
    public static long postgresCopy(Connection connection, String table, List<String> columns,
                                    Iterator<String[]> rows) throws SQLException, IOException {
        validate(table, columns);
        org.postgresql.PGConnection pg = connection.unwrap(org.postgresql.PGConnection.class);
        String sql = "COPY " + table + " (" + String.join(", ", columns)
                + ") FROM STDIN (FORMAT csv)";
        return pg.getCopyAPI().copyIn(sql, new CsvInputStream(rows));
    }

    private static void validate(String table, List<String> columns) {
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("invalid table identifier: " + table);
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columns must be non-empty");
        }
        for (String column : columns) {
            if (!IDENTIFIER.matcher(column).matches()) {
                throw new IllegalArgumentException("invalid column identifier: " + column);
            }
        }
    }

    private static long sum(int[] counts) {
        long n = 0;
        for (int c : counts) {
            n += c == Statement.SUCCESS_NO_INFO ? 1 : Math.max(c, 0);
        }
        return n;
    }
}
