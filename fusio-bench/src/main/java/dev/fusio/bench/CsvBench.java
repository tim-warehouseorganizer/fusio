package dev.fusio.bench;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.opencsv.CSVReader;
import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The shootout: fusio's fused CSV stage vs opencsv (decorator-based, the
 * popular choice) vs jackson-dataformat-csv (already internally fused — the
 * incumbent to beat or honorably lose to). ~16MB, 8 columns, ~20% of name
 * fields quoted with embedded commas/quotes. All variants compute the same
 * result: total cell count + total chars of column 2.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CsvBench {

    byte[] data;

    @Setup
    public void setup() {
        Random r = new Random(42);
        String[] first = {"Ada", "Grace", "Alan", "Edsger", "Barbara", "Donald", "John", "Margaret"};
        String[] last = {"Lovelace", "Hopper", "Turing", "Dijkstra", "Liskov", "Knuth", "Backus", "Hamilton"};
        StringBuilder sb = new StringBuilder(17 * 1024 * 1024);
        long id = 0;
        while (sb.length() < 16 * 1024 * 1024) {
            String name = first[r.nextInt(first.length)] + " " + last[r.nextInt(last.length)];
            if (r.nextInt(5) == 0) {
                name = "\"" + last[r.nextInt(last.length)] + ", " + first[r.nextInt(first.length)]
                        + " (\"\"" + first[r.nextInt(first.length)] + "\"\")\"";
            }
            sb.append(id++).append(',')
              .append(r.nextInt(100_000)).append(',')
              .append(name).append(',')
              .append(r.nextDouble()).append(',')
              .append(r.nextBoolean()).append(',')
              .append("2026-08-").append(1 + r.nextInt(28)).append(',')
              .append(r.nextLong()).append(',')
              .append("segment-").append(r.nextInt(50)).append('\n');
        }
        data = sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public long opencsv() throws Exception {
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            long cells = 0, chars = 0;
            String[] row;
            while ((row = reader.readNext()) != null) {
                cells += row.length;
                chars += row[2].length();
            }
            return cells * 31 + chars;
        }
    }

    @Benchmark
    public long jacksonCsv() throws Exception {
        CsvMapper mapper = CsvMapper.builder()
                .enable(CsvParser.Feature.WRAP_AS_ARRAY)
                .build();
        long cells = 0, chars = 0;
        try (MappingIterator<String[]> it = mapper.readerFor(String[].class)
                .readValues(new ByteArrayInputStream(data))) {
            while (it.hasNext()) {
                String[] row = it.next();
                cells += row.length;
                chars += row[2].length();
            }
        }
        return cells * 31 + chars;
    }

    @Benchmark
    public long fusio() {
        return ByteSource.of(data)
                .via(Utf8.decode())
                .via(Csv.parse())
                .foldLong(0L, (acc, row) -> acc + row.length * 31L + row[2].length());
    }
}
