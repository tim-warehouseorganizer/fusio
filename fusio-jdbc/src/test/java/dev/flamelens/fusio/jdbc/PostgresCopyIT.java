package dev.flamelens.fusio.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real Postgres. Not part of the default build.
 *
 * <pre>
 * docker compose up -d --wait     (see compose.yaml at the repo root)
 * mvn test -pl fusio-jdbc -Dtest='MySqlLoadDataIT,PostgresCopyIT'
 * </pre>
 */
class PostgresCopyIT {

    private static final String URL = System.getProperty("fusio.pg.url",
            "jdbc:postgresql://localhost:5416/bench");

    private Connection connect() {
        try {
            return DriverManager.getConnection(URL, "postgres", "fusio");
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void copyRoundTripsAdversarialRows() throws Exception {
        Connection cn = connect();
        assumeTrue(cn != null, "Postgres not reachable at " + URL + " — skipping");
        try (Connection c = cn; Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS items");
            st.execute("CREATE TABLE items (id INT, sku VARCHAR(64), name VARCHAR(255))");

            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"1", "A,1", "comma, in name"});
            rows.add(new String[]{"2", "B\"2", "say \"hi\" twice \"\""});
            rows.add(new String[]{"3", "C\\3", "backslash \\ literal"});
            rows.add(new String[]{"4", "D4", "multi\nline value"});
            rows.add(new String[]{"5", "É5", "unicode 汉字 😀"});
            for (int i = 6; i <= 10_000; i++) {
                rows.add(new String[]{String.valueOf(i), "SKU-" + i, "item " + i});
            }

            long copied = JdbcBulkLoad.postgresCopy(cn, "items",
                    java.util.Arrays.asList("id", "sku", "name"), rows.iterator());
            assertEquals(rows.size(), copied);

            try (ResultSet rs = st.executeQuery("SELECT sku, name FROM items ORDER BY id LIMIT 5")) {
                for (int i = 0; i < 5; i++) {
                    rs.next();
                    assertEquals(rows.get(i)[1], rs.getString(1), "sku row " + i);
                    assertEquals(rows.get(i)[2], rs.getString(2), "name row " + i);
                }
            }
        }
    }
}
