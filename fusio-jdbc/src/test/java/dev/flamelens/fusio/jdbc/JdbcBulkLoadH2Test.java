package dev.flamelens.fusio.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The portable batch path, validated on an in-memory H2 database. */
class JdbcBulkLoadH2Test {

    private Connection cn;

    @BeforeEach
    void open() throws SQLException {
        cn = DriverManager.getConnection("jdbc:h2:mem:fusio;DB_CLOSE_DELAY=-1");
        try (Statement st = cn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS items");
            st.execute("CREATE TABLE items (sku VARCHAR(64), name VARCHAR(255), qty INT)");
        }
    }

    @AfterEach
    void close() throws SQLException {
        cn.close();
    }

    @Test
    void insertsAllRowsAcrossBatchBoundaries() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 2_503; i++) { // deliberately not a batch multiple
            rows.add(new String[]{"SKU-" + i, "item " + i, String.valueOf(i % 100)});
        }
        long inserted = JdbcBulkLoad.batchInsert(cn, "items", List.of("sku", "name", "qty"),
                rows.iterator(), 1000);
        assertEquals(2_503, inserted);
        try (Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(qty) FROM items")) {
            rs.next();
            assertEquals(2_503, rs.getLong(1));
        }
    }

    @Test
    void specialCharactersSurviveBinding() throws SQLException {
        List<String[]> rows = List.of(
                new String[]{"A\"1", "name, with comma", "1"},
                new String[]{"B\n2", "line\nbreak", "2"},
                new String[]{"Cé3", "unicode 汉字", "3"});
        JdbcBulkLoad.batchInsert(cn, "items", List.of("sku", "name", "qty"), rows.iterator(), 2);
        try (Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("SELECT sku, name FROM items ORDER BY qty")) {
            rs.next();
            assertEquals("A\"1", rs.getString(1));
            assertEquals("name, with comma", rs.getString(2));
            rs.next();
            assertEquals("B\n2", rs.getString(1));
            rs.next();
            assertEquals("unicode 汉字", rs.getString(2));
        }
    }

    @Test
    void rejectsMalformedIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> JdbcBulkLoad.batchInsert(
                cn, "items; DROP TABLE items", List.of("sku"), List.<String[]>of().iterator(), 10));
        assertThrows(IllegalArgumentException.class, () -> JdbcBulkLoad.batchInsert(
                cn, "items", List.of("sku, qty"), List.<String[]>of().iterator(), 10));
    }

    @Test
    void rejectsWidthMismatch() {
        assertThrows(SQLException.class, () -> JdbcBulkLoad.batchInsert(
                cn, "items", List.of("sku", "name", "qty"),
                List.<String[]>of(new String[]{"only-one"}).iterator(), 10));
    }
}
