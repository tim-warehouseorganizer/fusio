package dev.fusio.bench;

import dev.flamelens.fusio.jdbc.JdbcBulkLoad;
import org.openjdk.jmh.annotations.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The bulk-ingestion story: 100K rows into MySQL three ways. Requires a local
 * MySQL (not started by the benchmark):
 *
 * <pre>
 * docker run -d --name fusio-mysql -e MYSQL_ROOT_PASSWORD=fusio -e MYSQL_DATABASE=bench \
 *   -p 3316:3306 mysql:8.4 --local-infile=1
 * </pre>
 *
 * Variants:
 * - plainBatch: PreparedStatement batching, driver defaults — what most code does.
 * - rewriteBatch: batching + rewriteBatchedStatements=true — MySQL's documented
 *   best practice for INSERT throughput.
 * - fusioLoadData: rows streamed lazily as CSV into LOAD DATA LOCAL INFILE.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
public class JdbcBench {

    private static final String BASE_URL = System.getProperty("fusio.mysql.url",
            "jdbc:mysql://localhost:3316/bench");
    private static final int ROWS = 100_000;

    List<String[]> rows;
    Connection plain;
    Connection rewrite;
    Connection infile;

    @Setup
    public void setup() throws SQLException {
        Random r = new Random(42);
        rows = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            rows.add(new String[]{
                    "SKU-" + i,
                    "item " + i + (i % 20 == 0 ? ", with comma" : ""),
                    String.valueOf(r.nextInt(1000)),
                    r.nextInt(100000) / 100.0 + ""});
        }
        plain = DriverManager.getConnection(BASE_URL, "root", "fusio");
        rewrite = DriverManager.getConnection(BASE_URL + "?rewriteBatchedStatements=true",
                "root", "fusio");
        infile = DriverManager.getConnection(BASE_URL + "?allowLoadLocalInfile=true",
                "root", "fusio");
        try (Statement st = plain.createStatement()) {
            st.execute("DROP TABLE IF EXISTS bench_rows");
            st.execute("CREATE TABLE bench_rows (sku VARCHAR(64), name VARCHAR(255),"
                    + " qty INT, price DECIMAL(10,2)) CHARACTER SET utf8mb4");
        }
    }

    @Setup(Level.Invocation)
    public void truncate() throws SQLException {
        try (Statement st = plain.createStatement()) {
            st.execute("TRUNCATE TABLE bench_rows");
        }
    }

    @TearDown
    public void tearDown() throws SQLException {
        plain.close();
        rewrite.close();
        infile.close();
    }

    @Benchmark
    public long plainBatch() throws SQLException {
        return JdbcBulkLoad.batchInsert(plain, "bench_rows",
                List.of("sku", "name", "qty", "price"), rows.iterator(), 1000);
    }

    @Benchmark
    public long rewriteBatch() throws SQLException {
        return JdbcBulkLoad.batchInsert(rewrite, "bench_rows",
                List.of("sku", "name", "qty", "price"), rows.iterator(), 1000);
    }

    @Benchmark
    public long fusioLoadData() throws SQLException {
        return JdbcBulkLoad.mysqlLoadData(infile, "bench_rows",
                List.of("sku", "name", "qty", "price"), rows.iterator());
    }
}
