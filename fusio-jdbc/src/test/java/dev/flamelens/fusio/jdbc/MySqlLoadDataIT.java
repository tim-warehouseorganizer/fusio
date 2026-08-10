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
 * Integration test against a real MySQL (H2 cannot emulate LOAD DATA LOCAL
 * INFILE). Not part of the default build (surefire runs *Test, not *IT).
 *
 * <pre>
 * docker run -d --name fusio-mysql -e MYSQL_ROOT_PASSWORD=fusio -e MYSQL_DATABASE=bench \
 *   -p 3316:3306 mysql:8.4 --local-infile=1
 * mvn test -pl fusio-jdbc -Dtest=MySqlLoadDataIT
 * </pre>
 */
class MySqlLoadDataIT {

    private static final String URL = System.getProperty("fusio.mysql.url",
            "jdbc:mysql://localhost:3316/bench?allowLoadLocalInfile=true");

    private Connection connect() {
        try {
            return DriverManager.getConnection(URL, "root", "fusio");
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void loadDataRoundTripsAdversarialRows() throws Exception {
        Connection cn = connect();
        assumeTrue(cn != null, "MySQL not reachable at " + URL + " — skipping");
        try (cn; Statement st = cn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS items");
            st.execute("CREATE TABLE items (id INT, sku VARCHAR(64), name VARCHAR(255))"
                    + " CHARACTER SET utf8mb4");

            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"1", "A,1", "comma, in name"});
            rows.add(new String[]{"2", "B\"2", "say \"hi\" twice \"\""});
            rows.add(new String[]{"3", "C\\3", "backslash \\ literal"});
            rows.add(new String[]{"4", "D4", "multi\nline value"});
            rows.add(new String[]{"5", "É5", "unicode 汉字 😀"});
            for (int i = 6; i <= 10_000; i++) {
                rows.add(new String[]{String.valueOf(i), "SKU-" + i, "item " + i});
            }

            long loaded = JdbcBulkLoad.mysqlLoadData(cn, "items",
                    List.of("id", "sku", "name"), rows.iterator());
            assertEquals(rows.size(), loaded);

            try (ResultSet rs = st.executeQuery("SELECT sku, name FROM items ORDER BY id LIMIT 5")) {
                for (int i = 0; i < 5; i++) {
                    rs.next();
                    assertEquals(rows.get(i)[1], rs.getString(1), "sku row " + i);
                    assertEquals(rows.get(i)[2], rs.getString(2), "name row " + i);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM items")) {
                rs.next();
                assertEquals(rows.size(), rs.getLong(1));
            }
        }
    }
}
