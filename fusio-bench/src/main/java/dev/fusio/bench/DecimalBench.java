package dev.fusio.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.openjdk.jmh.annotations.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The money case: values that arrive as text and must become {@link BigDecimal}.
 *
 * <p>Financial and ledger APIs habitually quote their numbers
 * ({@code "amount":"1234.56"}) so no intermediate binary float can round them.
 * That erases the structural advantage a JSON parser has over CSV — a quoted
 * number is a string token in both formats, and {@code BigDecimal} has to be
 * built from characters either way.
 *
 * <p>Note on parser behavior, established before benchmarking: Jackson coerces
 * a quoted number into a {@code BigDecimal} field out of the box. Fory JSON
 * 1.6.1 <b>rejects</b> it ({@code ForyJsonException: Expected digit}) — quoted
 * decimals require registering a codec, which {@link QuotedBigDecimalCodec}
 * below does. The {@code plain_*} benchmarks are the control: the same values
 * as unquoted JSON numbers, which is Fory's native path.
 *
 * <p>Same 200K rows in every variant, identical values, verified equal.
 * Four decimal columns: three money-scale (2dp, fits BigDecimal's compact
 * long representation) and one 20-significant-digit column that forces the
 * BigInteger path.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DecimalBench {

    public static final class Row {
        public long id;
        public BigDecimal price;
        public BigDecimal tax;
        public BigDecimal total;
        public BigDecimal precise;
        public String currency;

        public Row() {
        }
    }

    /** Reads/writes BigDecimal as a JSON *string*; Fory has no built-in coercion for that. */
    public static final class QuotedBigDecimalCodec extends AbstractJsonValueCodec<BigDecimal> {
        @Override
        public void write(JsonWriter w, BigDecimal v) {
            w.writeString(v.toPlainString());
        }

        @Override
        public BigDecimal read(JsonReader r) {
            return new BigDecimal(r.readString());
        }
    }

    private static final int ROWS = 200_000;

    byte[] csv;
    byte[] jsonQuoted;   // {"price":"1234.56", ...}
    byte[] jsonPlain;    // {"price":1234.56, ...}

    ForyJson foryQuoted;  // with the codec above
    ForyJson foryPlain;   // stock
    ObjectMapper jackson;
    TypeRef<List<Row>> foryType;
    TypeReference<List<Row>> jacksonType;

    @Setup
    public void setup() {
        Random r = new Random(42);
        String[] ccy = {"USD", "EUR", "GBP", "JPY", "CHF"};
        StringBuilder c = new StringBuilder(20 << 20);
        StringBuilder q = new StringBuilder(48 << 20);
        StringBuilder p = new StringBuilder(48 << 20);
        q.append('[');
        p.append('[');
        for (long id = 0; id < ROWS; id++) {
            String price = (r.nextInt(1_000_000) / 100.0 + "").replaceAll("(\\.\\d)$", "$10");
            String tax = (r.nextInt(10_000) / 100.0 + "").replaceAll("(\\.\\d)$", "$10");
            String total = (r.nextInt(1_010_000) / 100.0 + "").replaceAll("(\\.\\d)$", "$10");
            // 20 significant digits: past long, forces BigDecimal's BigInteger path
            String precise = (1_000_000_000L + r.nextInt(1_000_000_000)) + "." + (1_000_000_000L + r.nextInt(1_000_000_000));
            String cur = ccy[r.nextInt(ccy.length)];

            c.append(id).append(',').append(price).append(',').append(tax).append(',')
             .append(total).append(',').append(precise).append(',').append(cur).append('\n');

            if (id > 0) {
                q.append(',');
                p.append(',');
            }
            q.append("{\"id\":").append(id).append(",\"price\":\"").append(price)
             .append("\",\"tax\":\"").append(tax).append("\",\"total\":\"").append(total)
             .append("\",\"precise\":\"").append(precise).append("\",\"currency\":\"").append(cur).append("\"}");
            p.append("{\"id\":").append(id).append(",\"price\":").append(price)
             .append(",\"tax\":").append(tax).append(",\"total\":").append(total)
             .append(",\"precise\":").append(precise).append(",\"currency\":\"").append(cur).append("\"}");
        }
        q.append(']');
        p.append(']');
        csv = c.toString().getBytes(StandardCharsets.UTF_8);
        jsonQuoted = q.toString().getBytes(StandardCharsets.UTF_8);
        jsonPlain = p.toString().getBytes(StandardCharsets.UTF_8);

        foryQuoted = ForyJson.builder()
                .registerCodec(BigDecimal.class, new QuotedBigDecimalCodec())
                .build();
        foryPlain = ForyJson.builder().build();
        jackson = new ObjectMapper();
        foryType = new TypeRef<List<Row>>() {
        };
        jacksonType = new TypeReference<List<Row>>() {
        };
        System.out.printf("%n# DecimalBench: %d rows — csv %.1f MB, json quoted %.1f MB, json plain %.1f MB%n",
                ROWS, csv.length / 1048576.0, jsonQuoted.length / 1048576.0, jsonPlain.length / 1048576.0);
    }

    // ---- the money case: text in, BigDecimal out ------------------------

    @Benchmark
    public List<Row> quoted_fusioCsv() {
        List<Row> out = new ArrayList<>(ROWS);
        ByteSource.of(csv).via(Utf8.decode()).via(Csv.parse()).forEach(cells -> {
            Row row = new Row();
            row.id = Long.parseLong(cells[0]);
            row.price = new BigDecimal(cells[1]);
            row.tax = new BigDecimal(cells[2]);
            row.total = new BigDecimal(cells[3]);
            row.precise = new BigDecimal(cells[4]);
            row.currency = cells[5];
            out.add(row);
        });
        return out;
    }

    @Benchmark
    public List<Row> quoted_foryJson() {
        return foryQuoted.fromJson(jsonQuoted, foryType);
    }

    @Benchmark
    public List<Row> quoted_jacksonJson() throws Exception {
        return jackson.readValue(jsonQuoted, jacksonType);
    }

    // ---- control: unquoted JSON numbers (Fory's native path) ------------

    @Benchmark
    public List<Row> plain_foryJson() {
        return foryPlain.fromJson(jsonPlain, foryType);
    }

    @Benchmark
    public List<Row> plain_jacksonJson() throws Exception {
        return jackson.readValue(jsonPlain, jacksonType);
    }
}
