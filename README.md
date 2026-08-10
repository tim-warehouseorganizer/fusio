# fusio

Fused functional I/O pipelines for the JVM — `java.io` without the decorator onion.

```java
long errorCount = ByteSource.file(log)
        .via(Utf8.decode())
        .via(Lines.split())
        .foldLong(0, (n, line) -> line.contains("ERROR") ? n + 1 : n);

List<String[]> rows = ByteSource.file(csv)
        .via(Utf8.decode())
        .via(Csv.parse())
        .toList();
```

A pipeline is a value: nothing opens until a terminal operation runs, resources are
bracketed inside the run, and the composed stages fuse into a single loop over
chunks — one buffer, no per-byte calls, no locks, no `IOException` in your lambdas.

## Why

The `java.io` decorator model has four structural costs, each measured in
`fusio-bench` (JMH, JDK 25):

| Pathology | java.io | fused | ratio |
|---|---|---|---|
| Per-byte reads through `BufferedInputStream` (locked) | 86.0 ms | 3.2 ms | **26x** |
| `DataInputStream.readInt` vs chunked decode | 14.8 ms | 1.5 ms | **10x** |
| ...and wrapping it in `BufferedInputStream` ("the fix") | 43.3 ms | — | **3x worse** |
| Count lines, decode needed, no Strings needed (16 MB) | 41.6 ms | 30.9 ms | **1.35x** |
| Count lines, no decode needed either | 41.6 ms | 6.9 ms | **6x** |

And the honest row: producing the exact same `String` per line as
`BufferedReader.readLine`, fusio is only ~4% faster (40.0 vs 41.6 ms) — once you
materialize the same objects, allocation dominates. The fusion win is that the
API lets you *not* do work: `readLine` must always decode and always allocate;
a fold over chunks decodes only if you ask and allocates only what you keep.

## CSV

`Csv.parse()` is a single fused pass (RFC 4180: quotes, `""` escapes, embedded
delimiters/newlines, `\r\n`, fields across chunk boundaries), verified by
differential tests against opencsv's RFC4180 parser.

16 MB / 8 columns, same work in all three (JMH, JDK 25):

| parser | avg | heap per op |
|---|---|---|
| opencsv (decorator-based) | 88.7 ms | 154.2 MB |
| jackson-dataformat-csv (internally fused) | 62.1 ms | 85.8 MB |
| **fusio** | **40.6 ms** | **85.9 MB** |

~2.2x faster than opencsv and ~1.5x faster than Jackson's hand-tuned CSV
parser — as a generic, composable pipeline. (Profiling found 7% of parse time
in `ArrayList.clear`; replacing the row list with a reused `String[]` buffer
took fusio from ~53 ms to 40.6 ms.) fusio and Jackson allocate within 0.1% of
each other — both sit at the floor of one String per cell.

## Gzip

`Gzip.gunzip()` is a push-based RFC 1952 stage: full header state machine
(FEXTRA/FNAME/FCOMMENT/FHCRC, all spanning-chunk-safe), CRC32 + ISIZE trailer
verification, concatenated multi-member streams. Same `Inflater` intrinsics as
`GZIPInputStream`, so this isolates the pipeline model on a decompress-heavy
workload (16 MB text, identical work):

| stack | avg |
|---|---|
| `GZIPInputStream` → `InputStreamReader` → `BufferedReader` | 73.1 ms |
| **fusio** `gunzip → decode → lines` | **68.6 ms** |

~7% — inflate dominates, as expected. The win compounds with what the API lets
you skip downstream.

## mmap: an honest negative result

`Mmap.byteSource(path)` (copy into reused heap chunks) and `Mmap.foldLong`
(zero-copy `MemorySegment` slices) are implemented and tested. On Windows
ARM64, JDK 25, scanning a page-cache-hot 64 MB file once:

| tier | avg |
|---|---|
| buffered stream reads (`ByteSource.file`) | **36.2 ms** |
| mmap, copy to heap chunk | 55.1 ms |
| mmap, zero-copy SWAR long reads | 65.3 ms |
| mmap, zero-copy per-byte `MemorySegment.get` | 128.0 ms |

For one-shot sequential scans on Windows, mmap loses: each run pays mapping
setup plus thousands of soft page faults, while `ReadFile` streams straight
out of the page cache — and the JIT auto-vectorizes the plain heap-array scan
better than scalar SWAR over a segment. Folklore says "mmap is faster"; the
benchmark says "it depends, and here's on what."

**And on Linux it flips.** Same hardware, same jar, Linux ARM64 (Docker,
Temurin 25):

| tier | Windows | Linux |
|---|---|---|
| buffered stream reads | 36.2 ms | 37.9 ms |
| mmap zero-copy + SWAR | 65.3 ms | **27.4 ms** |

Linux's cheap soft faults make the zero-copy SWAR scan the fastest way to
read a file — 28% ahead of stream reads, and 2.4x faster than the identical
code on Windows. The 1BRC folklore is true *on 1BRC's platform*. Every other
benchmark (CSV, text, gzip — all CPU-bound) is within noise across the two
platforms; only the mmap path is platform-sensitive.

## Using fusio in an existing application

fusio is an engine. Most applications shouldn't rewrite their I/O around it —
they should swap it in underneath what they already have. Three adapters make
that a small change (all zero-dependency, in `dev.flamelens.fusio.interop`):

**Migrating from opencsv** — `FusioCsvReader` has the same usage shape as
`CSVReader`; migration is the construction line:

```java
// before                                      // after
CSVReader r = new CSVReader(reader);           FusioCsvReader r = new FusioCsvReader(reader);
String[] row;                                  String[] row;
while ((row = r.readNext()) != null) { ... }   while ((row = r.readNext()) != null) { ... }
```

When you control the source, skip the Reader layer entirely and get the fully
fused path (bytes → BOM strip → UTF-8 → CSV):

```java
try (FusioCsvReader r = FusioCsvReader.of(path)) {
    for (String[] row : r) { ... }
}
```

**Feeding anything that eats an InputStream** — `PipeInputStream` turns any
byte pipeline into a plain `InputStream`, the universal socket. Jackson's
`ObjectMapper`, image decoders, legacy APIs:

```java
try (InputStream in = new PipeInputStream(httpBody, Gzip.gunzip())) {
    MyDto dto = objectMapper.readValue(in, MyDto.class);
}
```

**Consuming what a framework hands you** — `ByteSource.from(inputStream)`
adapts a servlet request body, SDK response, or any stream into a pipeline:

```java
@PostMapping("/import")
public ImportResult importCsv(HttpServletRequest request) throws IOException {
    long rows = ByteSource.from(request.getInputStream(), 64 * 1024)
            .via(Utf8.decode())
            .via(Csv.parse())
            .foldLong(0, (n, row) -> { ingest(row); return n + 1; });
    return new ImportResult(rows);
}
```

Rows in the adapters are produced lazily with bounded memory; `PipeInputStream`
pays one defensive copy at the boundary (pipeline buffers are reused; stream
consumers read at their own pace) — the cost of compatibility, paid only there.

**The Spring Boot starter** (`fusio-spring-boot-starter`, targets Boot 4.x):
add the jar, get behavior — no configuration required.

- `text/csv` HttpMessageConverter, server side (`@RequestBody List<String[]>`
  works in controllers) and client side (registered on every Boot-built
  `RestClient.Builder`).
- Gzip request-body decompression filter: `Content-Encoding: gzip` uploads
  are transparently decompressed for every downstream consumer (Jackson, the
  CSV converter, application code). Disable: `fusio.request-decompression=false`.
- Client-side transparent gzip: `Accept-Encoding: gzip` on outgoing RestClient
  calls with streaming decompression of gzip responses — notably useful because
  the JDK HttpClient does not decompress on its own. Disable: `fusio.rest-client=false`.
- `GzipWrappingRedisSerializer` — decorate any RedisSerializer to gzip cached
  values above a size threshold (Redis memory, not CPU, is the win; safe to
  enable on a warm cache — legacy uncompressed values still read).

Server-side pieces activate only in servlet web apps; client and Redis pieces
only when those stacks are on the classpath. Everything is additive — no
existing behavior changes.

## CSV → database: the bulk-loading story (`fusio-jdbc`)

CSV is the lingua franca of data *boundaries* — what ERPs export, SaaS
tools emit, customers upload, Excel produces. (Inside modern ML pipelines
the internal format war went to Parquet/Arrow; fusio doesn't pretend
otherwise — columnar stages are roadmap.) What has NOT changed is the
canonical fast path from that boundary into a database: Postgres's
`COPY ... FROM STDIN (FORMAT csv)` and MySQL's `LOAD DATA LOCAL INFILE`
are stream-shaped bulk doors, and fusio walks straight through them —
rows are formatted lazily as CSV (`CsvInputStream`, memory bounded at
~16KB regardless of row count) and streamed in:

```java
// uploaded file -> parsed -> validated rows -> straight into the table
List<String[]> rows = validate(FusioCsvReader.of(upload.getInputStream()).readAll());
JdbcBulkLoad.postgresCopy(connection, "feature_store", COLUMNS, rows.iterator());
```

The ML-relevant case in 2026 is concrete: **Postgres as a vector/feature
database (pgvector)**. Embedding and feature tables are bulk-loaded via
COPY — this bridge is that path, with bounded memory and no ORM overhead.
Database compatibility: the MySQL path also works against **MariaDB
servers** when connecting through MySQL Connector/J (MariaDB's own driver
exposes a different API — native support is roadmap). The portable
`batchInsert` tier covers every other JDBC database.

100K rows into MySQL 8.4 (Docker, same host):

| route | time | heap allocated |
|---|---|---|
| `batchInsert` (default driver settings) | 287 s | 490.9 MB |
| `batchInsert` + `rewriteBatchedStatements=true` (MySQL best practice) | ~1.5 s | 219.8 MB |
| **`mysqlLoadData`** (streamed CSV → LOAD DATA) | **420 ms** | **5.2 MB** |

~3.7x faster than the documented best practice with 42x less allocation —
and 684x faster than what default-configured code does. The bridge sets
`ESCAPED BY ''` so MySQL reads RFC 4180 doubled-quote escaping (its default
backslash mode would corrupt the data); integration tests round-trip 10K
adversarial rows (commas, quotes, backslashes, newlines, emoji) byte-exact
against real MySQL and Postgres. The portable `batchInsert` tier runs
anywhere and is tested on in-memory H2. Caveat: bulk paths bypass
ORM-layer machinery (auditing, encryption, listeners) — an opt-in fast path
for the right tables, not a transparent swap.

## Swapping fusio in at the stream seams — the generic recipe

Every library boundary is one of four shapes, and fusio has an adapter for
each. Identify the shape, pick the adapter:

| The other library... | Adapter | Example |
|---|---|---|
| **hands you an InputStream** (servlet body, SDK response, S3 download) | `ByteSource.from(in, chunkSize)` — the stream becomes a pipeline source | `ByteSource.from(request.getInputStream(), 64 * 1024).via(Utf8.decode())...` |
| **wants an InputStream from you** (ObjectMapper, image decoders, S3 upload, JDBC bulk doors) | `PipeInputStream` (transform an existing stream) or `CsvInputStream` (rows from your code) | `objectMapper.readValue(new PipeInputStream(body, Gzip.gunzip()), Dto.class)` |
| **wants a Reader / has a Reader-shaped API** (opencsv-style code) | `FusioCsvReader` — same readNext/readAll/Iterable shape | swap the construction line, keep the loop |
| **pushes items at you** (event handlers, batch callbacks) | `Pipe.connect(sink)` — build the fused chain once, feed it per item | `Sink<String[]> sink = Csv.format().then(Utf8.encode()).connect(...)` |

Rules of thumb: adapters pay at most one defensive copy at the boundary
(documented on each); pipeline buffers are reused, so anything you retain
past an `accept` call must be copied; and the fused interior stays fused —
only the outermost seam speaks the legacy shape.

## Charsets: UTF-8 by design, legacy at the boundary

fusio's native decode/encode is UTF-8 only, deliberately: it is the
interchange standard (the overwhelming default of the web, and Java's own
default charset since JDK 18), and a single hot-path encoding keeps the
fused decoder simple and fast. `Bom.strip()` handles the UTF-8 BOM Excel
prepends.

Legacy encodings still arrive in real CSV work, and the pattern is
*transcode at the boundary, stay UTF-16/UTF-8 inside*:

- **Windows-1252 / ISO-8859-1** — historical Excel "CSV" (ANSI) exports and
  old system dumps;
- **UTF-16LE** — Excel's "Unicode Text" export;
- **GB18030** (mandated in China), **Shift_JIS**, **EUC-KR** — regional
  defaults that outlived the transition.

All of these work with fusio *today* via the Reader seam — the JDK
transcodes, fusio does everything after:

```java
try (FusioCsvReader r = new FusioCsvReader(
        new InputStreamReader(in, Charset.forName("windows-1252")))) { ... }
```

A native `Text.decode(charset)` pipe (generalizing `Utf8.decode`'s fused
path to arbitrary charsets) is additive roadmap — it earns its place when a
hot path actually bottlenecks on a legacy charset, not before.

## Dialects and the cascade

The parser's default is strict RFC 4180 — but every departure from the
libraries people migrate from has a revert lever. Named dialects reproduce
incumbent behaviors so adoption can be behavior-identical first,
modernized second:

```java
Csv.parse()                              // RFC 4180 (default)
Csv.parse(CsvDialect.opencsvLegacy())    // backslash escapes, CR-stripping in quotes,
                                         //   error on unterminated quote
Csv.parse(CsvDialect.jackson())          // spaces around quoted fields skipped,
                                         //   error on unterminated quote
Csv.parse(CsvDialect.rfc4180()           // or cherry-pick individual knobs
        .withEscapeChar('\\')
        .withErrorOnUnterminatedQuote(true))
```

`FusioCsvReader` accepts a dialect everywhere, so a migration can pin
`opencsvLegacy()` in one place and flip to RFC when ready — one line each
way. Dialect conformance is tested with cases ported from each incumbent's
own suite. (One behavior is deliberately not reproduced in any dialect:
opencsv's separator-adjacency quote heuristic, which silently drops
characters.)

**The cascade** waterfalls dialects, most-preferred first. Non-final tiers
run in detection mode — constructs whose *meaning differs between dialects*
(space before a quoted field, bare quote mid-field, data after a closing
quote, unterminated quote) raise a mismatch instead of being silently
interpreted, and the document re-parses under the next tier:

```java
CsvCascade.Result r = CsvCascade.parse(source);            // RFC → Jackson → opencsv-legacy
CsvCascade.Result r = CsvCascade.parse(source, myTiers);   // or your own chain
r.dialect(); r.tierIndex(); r.rows();                      // which tier won, and the data
```

Fallback restarts the document, not the row — record boundaries themselves
differ between dialects, so per-row fallback would be unsound. Honest
limitation, pinned as a test: backslash-escape *intent* is undetectable
(`a\,b` is valid RFC data), so backslash-dialect producers should pin
`opencsvLegacy()` rather than cascade.

## License

Apache License 2.0 (see `LICENSE`, `NOTICE`). Chosen deliberately: it
matches the whole neighborhood (Spring, Jackson, opencsv — and the test
material we port from the latter two), and its explicit patent grant is
what corporate adopters' lawyers look for. Vendored csv-spectrum test data
is BSD-2-Clause; attributions live in `NOTICE`. Shipped jars contain no
third-party source.

## Compatibility test suite

fusio's CSV parser is validated against the documented test bases of the
libraries it competes with, ported with attribution into
`fusio-core/src/test/java/dev/flamelens/fusio/compat/`:

- **opencsv** (Apache 2.0): the RFC4180ParserSpec corpus, the cross-parser
  integration documents, and the RFC-compatible subset of CSVParserTest —
  including the "Excel generated string" and every backslash-is-data negative
  test. The Bug143.csv real-world fixture must parse identically to opencsv's
  RFC4180Parser, verified live.
- **jackson-dataformat-csv** (Apache 2.0): CsvDecoderTextTest, the
  empty-line/empty-row suites, mixed line terminators, multibyte chars at
  buffer boundaries, and both CSV fuzzer regression fixtures.
- **csv-spectrum** (BSD-2-Clause): the full acceptance corpus at chunk sizes
  1, 7, and 8192, checked against its JSON ground truth. (Our vendored copy
  fixes an upstream bug: the phone number in location_coordinates.json was
  anonymized but the CSV wasn't.)

Divergences are documented as tests, not hidden: fusio is lenient on
unterminated quotes (like opencsv's RFC4180Parser; Jackson throws), treats
spaces before a quoted field as data (like RFC 4180; Jackson trims), does not
consume BOMs by default (compose `Bom.strip()` ahead of the decoder to get
Jackson's behavior), and preserves rather than drops characters in malformed
quote sequences (both incumbents apply lossy heuristics there).

## Profiling artifacts

`jfr/` contains JFR recordings of the flagship benchmark pairs (fusio vs the
java.io/opencsv/Jackson equivalent for CSV, text, gzip, and off-heap
ingestion), captured via `-prof jfr` on JDK 25. `profile-run*.txt` and
`linux-profile.txt` hold the JMH gc/stack profiler logs behind the
optimization story in this README.

## Modules

- `fusio-core` — the library. Zero dependencies. Unit tests (including
  differential tests vs the JDK's UTF-8 decoder and opencsv) run on every build.
- `fusio-bench` — JMH benchmarks: `java -jar fusio-bench/target/benchmarks.jar`

## Zero-allocation line views

`Lines.views()` emits each line as a `Chars` view instead of a String
(40.0 ms vs 41.5 ms for the decorator stack — only ~4%: per-line sink dispatch
eats most of what skipping the String saves on ~370K short lines). The real
lever remains dropping granularity you don't need: folding over decoded chunks
directly runs 30.8 ms, and over raw bytes 7.4 ms.

## Profile-driven: byte-level line splitting

Stack profiles showed UTF-8 decode is ~half the cost of every text pipeline.
But for *line splitting*, decode is unnecessary: in UTF-8 a `\n`/`\r` byte can
never occur inside a multibyte sequence (continuation bytes are always
`>= 0x80`), so `Lines.bytes()` splits on raw bytes and lets you decode per
line only if you actually read it:

| per-line iteration over 16 MB | avg | heap per op |
|---|---|---|
| `BufferedReader.readLine` | 42.2 ms | 30.2 MB |
| `Lines.bytes()` views | **11.7 ms** | **465 B** |

3.6x faster with per-line granularity intact. Filtering, sampling, and
counting workloads never pay for decoding lines they discard.

## Off-heap ingestion (`Segments`)

`Segments.collect` / `collectInto` run a byte pipeline straight into native
memory — the shape ML data loading wants (native runtimes read
`MemorySegment`s; the GC never sees the payload). Decompressing 16 MB of
gzip to off-heap:

| route | avg | heap per op | GCs |
|---|---|---|---|
| `GZIPInputStream.readAllBytes()` → copy to segment | 37.4 ms | 35.4 MB | 33 |
| `Segments.collect` (size unknown) | 41.4 ms | 68 KB | 0 |
| `Segments.collectInto` (pre-sized) | 32.4 ms | 67 KB | 0 |
| `Segments.collectInto` (reused target — steady-state batches) | **27.1 ms** | **67 KB** | **0** |

When the target size is known — tensor dims usually are — fusio is faster
*and* allocates 520x less heap. The reused-target row is another
profile-driven find: stack profiles showed ~9–13% of per-op time was the JDK
zero-filling fresh native memory (`SegmentFactories.initNativeMemory`) that
the pipeline immediately overwrites. Batch ingestion reuses its buffer, making
that a one-time cost: **1.43x faster than the JDK route at steady state**.

Platform nuance from the Linux profile: `initNativeMemory` costs ~1% there
(vs ~9% on Windows) — Linux hands out pre-zeroed pages lazily, so pre-sized
and reused targets tie (26.2 / 25.1 ms vs jdk 30.9 ms). At steady state on
either platform the profile shows ~99% of app time in `Inflater` itself: the
pipeline overhead is gone, and the next win would be a faster inflater
(libdeflate/zlib-ng via FFM) — on the roadmap.

## Stability: the 1.0 contract

The public API is locked. Everything documented in this README — the core
types (`ByteSource`, `Pipeline`, `Pipe`, `Sink`, `Bytes`, `Chars`,
`Segments`, `Mmap`), the pipes (`Utf8`, `Lines`, `Csv`, `CsvDialect`,
`CsvCascade`, `Gzip`, `Bom`), the interop adapters (`FusioCsvReader`,
`PipeInputStream`, `CsvInputStream`), `JdbcBulkLoad`, and the starter's
auto-configured behavior and properties — follows semantic versioning:

- **No breaking changes within 1.x.** Existing signatures, parsing/formatting
  semantics, and dialect behaviors stay fixed; the compat and dialect test
  suites are the executable contract.
- **Additive evolution only** — new pipes, new dialects, new terminals, new
  starter features arrive as additions.
- **Deprecation before removal**, and removal only at a major version.

Roadmap (all additive): zstd stage, decode-into-`MemorySegment` sinks,
Vector API scan stages, parallel segment scanning, Kafka/AMQP payload
serializers, `fusio-spring` message-converter metrics.

1.0.0 publishes to Maven Central after real-application interop validation
(see `docs/RELEASING.md` for the gate).
