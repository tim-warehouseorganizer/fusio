package dev.fusio.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Format-vs-format, not parser-vs-parser: the SAME ~1M-cell tabular dataset
 * ingested as CSV through fusio and as JSON through Apache Fory JSON (which
 * claims "10x faster than Jackson/Gson") and Jackson databind. fusio has no
 * JSON path, so this is the only honest comparison there is — it answers
 * "if I control the wire format for bulk tabular data, what does each cost?"
 *
 * <p>Two shapes, each producing the exact same Java objects in every variant:
 * <ul>
 *   <li><b>rows</b> — one String per cell ({@code List<String[]>}-equivalent
 *       work; CSV rows vs JSON array-of-arrays of strings).</li>
 *   <li><b>records</b> — typed POJOs ({@code Rec} with long/int/double/boolean
 *       fields); CSV needs explicit per-field parsing (done inline), JSON
 *       array-of-objects binds by name.</li>
 * </ul>
 * The JSON inputs are generated from the same RNG stream as the CSV, so the
 * values are identical; JSON is ~1.4x (rows) / ~2.6x (records) more bytes for
 * the same data, and that is part of what is being measured.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TabularFormatBench {

    /** Same shape as CsvBench rows: id, qty, name, ratio, flag, date, hash, segment. */
    public static final class Rec {
        public long id;
        public int qty;
        public String name;
        public double ratio;
        public boolean flag;
        public String date;
        public long hash;
        public String segment;

        public Rec() {
        }
    }

    private static final int ROWS = 200_000;   // ~1.6M cells

    byte[] csv;
    byte[] jsonRows;      // [["0","123","Ada Lovelace",...],...]
    byte[] jsonRecords;   // [{"id":0,"qty":123,"name":"Ada Lovelace",...},...]

    ForyJson fory;
    ObjectMapper jackson;
    TypeRef<List<String[]>> foryRowsType;
    TypeRef<List<Rec>> foryRecType;
    TypeReference<List<String[]>> jacksonRowsType;
    TypeReference<List<Rec>> jacksonRecType;

    @Setup
    public void setup() {
        Random r = new Random(42);
        String[] first = {"Ada", "Grace", "Alan", "Edsger", "Barbara", "Donald", "John", "Margaret"};
        String[] last = {"Lovelace", "Hopper", "Turing", "Dijkstra", "Liskov", "Knuth", "Backus", "Hamilton"};
        StringBuilder c = new StringBuilder(16 << 20);
        StringBuilder jr = new StringBuilder(24 << 20);
        StringBuilder jo = new StringBuilder(40 << 20);
        jr.append('[');
        jo.append('[');
        for (long id = 0; id < ROWS; id++) {
            String name = first[r.nextInt(first.length)] + " " + last[r.nextInt(last.length)];
            if (r.nextInt(5) == 0) {
                name = last[r.nextInt(last.length)] + ", " + first[r.nextInt(first.length)]
                        + " (\"" + first[r.nextInt(first.length)] + "\")";
            }
            int qty = r.nextInt(100_000);
            double ratio = r.nextDouble();
            boolean flag = r.nextBoolean();
            String date = "2026-08-" + (1 + r.nextInt(28));
            long hash = r.nextLong();
            String segment = "segment-" + r.nextInt(50);

            c.append(id).append(',').append(qty).append(',').append(csvField(name)).append(',')
             .append(ratio).append(',').append(flag).append(',').append(date).append(',')
             .append(hash).append(',').append(segment).append('\n');

            if (id > 0) {
                jr.append(',');
                jo.append(',');
            }
            jr.append("[\"").append(id).append("\",\"").append(qty).append("\",").append(jsonStr(name))
              .append(",\"").append(ratio).append("\",\"").append(flag).append("\",\"").append(date)
              .append("\",\"").append(hash).append("\",\"").append(segment).append("\"]");
            jo.append("{\"id\":").append(id).append(",\"qty\":").append(qty)
              .append(",\"name\":").append(jsonStr(name)).append(",\"ratio\":").append(ratio)
              .append(",\"flag\":").append(flag).append(",\"date\":\"").append(date)
              .append("\",\"hash\":").append(hash).append(",\"segment\":\"").append(segment).append("\"}");
        }
        jr.append(']');
        jo.append(']');
        csv = c.toString().getBytes(StandardCharsets.UTF_8);
        jsonRows = jr.toString().getBytes(StandardCharsets.UTF_8);
        jsonRecords = jo.toString().getBytes(StandardCharsets.UTF_8);

        fory = ForyJson.builder().build();
        jackson = new ObjectMapper();
        foryRowsType = new TypeRef<List<String[]>>() {
        };
        foryRecType = new TypeRef<List<Rec>>() {
        };
        jacksonRowsType = new TypeReference<List<String[]>>() {
        };
        jacksonRecType = new TypeReference<List<Rec>>() {
        };
        System.out.printf("%n# TabularFormatBench: %d rows — csv %.1f MB, json rows %.1f MB, json records %.1f MB%n",
                ROWS, csv.length / 1048576.0, jsonRows.length / 1048576.0, jsonRecords.length / 1048576.0);
    }

    private static String csvField(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- rows: one String per cell --------------------------------------

    @Benchmark
    public List<String[]> rows_fusioCsv() {
        return ByteSource.of(csv).via(Utf8.decode()).via(Csv.parse()).toList();
    }

    @Benchmark
    public List<String[]> rows_foryJson() {
        return fory.fromJson(jsonRows, foryRowsType);
    }

    @Benchmark
    public List<String[]> rows_jacksonJson() throws Exception {
        return jackson.readValue(jsonRows, jacksonRowsType);
    }

    // ---- records: typed POJOs -------------------------------------------

    @Benchmark
    public List<Rec> records_fusioCsv() {
        List<Rec> out = new ArrayList<>(ROWS);
        ByteSource.of(csv).via(Utf8.decode()).via(Csv.parse()).forEach(row -> {
            Rec rec = new Rec();
            rec.id = Long.parseLong(row[0]);
            rec.qty = Integer.parseInt(row[1]);
            rec.name = row[2];
            rec.ratio = Double.parseDouble(row[3]);
            rec.flag = Boolean.parseBoolean(row[4]);
            rec.date = row[5];
            rec.hash = Long.parseLong(row[6]);
            rec.segment = row[7];
            out.add(rec);
        });
        return out;
    }

    @Benchmark
    public List<Rec> records_foryJson() {
        return fory.fromJson(jsonRecords, foryRecType);
    }

    @Benchmark
    public List<Rec> records_jacksonJson() throws Exception {
        return jackson.readValue(jsonRecords, jacksonRecType);
    }
}
