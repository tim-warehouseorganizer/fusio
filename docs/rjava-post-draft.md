# r/java post draft — fusio

> Status: DRAFT skeleton. Publish after: Maven Central release under
> dev.flamelens, GitHub repo public, flamelens analysis of the jfr/ recordings
> embedded as images. Tone: show receipts, lead with the honest negatives —
> r/java rewards "here's where we lose" far more than "we're the fastest."

---

**Title options** (A/B against each other):
1. I rewrote java.io's decorator pattern as fused functional pipelines — here's what profiling says about 25 years of stream-wrapping
2. What BufferedReader actually costs: benchmarking java.io's decorator model against a fused-pipeline rewrite
3. fusio: RFC 4180 CSV parsing 2.2x faster than opencsv, validated against opencsv's own test suite

**Hook (first paragraph):**
Every Java tutorial teaches `new BufferedReader(new InputStreamReader(new
FileInputStream(f)))`. That stack has three buffers copying the same bytes,
takes a lock on every operation it doesn't need since biased locking was
removed, and — the part nobody talks about — *forces* work on you: readLine
must decode UTF-8 and allocate a String even when you're just counting lines.
I built a small library that replaces the decorator onion with transducer-style
fused pipelines and benchmarked every claim. Some results were embarrassing
for java.io. One was embarrassing for me.

**Section: the numbers** (final table from README; keep the ± errors)
- Per-line iteration: 42.2ms → 11.7ms (3.6x), 30.2MB → 465 B allocated per op
- CSV: 2.2x opencsv, ~1.2-1.5x jackson-csv — passes both libraries' own parsing tests
- Off-heap ingestion: 1.43x faster than readAllBytes+copy, zero GC cycles
- Gzip: +7% (Inflater dominates — say so plainly)

**Section: the one that was embarrassing for me (mmap)**
Windows: mmap LOSES to stream reads for one-shot scans (65 vs 36ms).
Linux: flips completely (27 vs 38ms). Same jar. Show both tables.
Moral: 1BRC folklore is true on 1BRC's platform. This section buys the
credibility for everything else.

**Section: profile-driven finds** (each: prediction → profile → fix → number)
1. ArrayList.clear was 6.8% of CSV parse → reused String[] → 53ms → 40.6ms
2. Decode is ~50% of text pipelines but unnecessary for line splitting
   (\n can't appear in UTF-8 multibyte sequences) → Lines.bytes() → 3.6x
3. Arena.allocate zero-fills memory we immediately overwrite
   (SegmentFactories.initNativeMemory, 9-13% on Windows) → reuse target → 1.43x
[flamelens: embed flame graphs from jfr/ recordings here — the before/after
 pair for each find. The jdkHeapDetour recording is 1.45MB of mostly GC
 events vs fusio's 0.26MB — the file size alone is a chart.]

**Section: compatibility receipts**
Ported the parsing test suites of opencsv (their RFC4180ParserSpec lives in
Groovy, TIL) and jackson-dataformat-csv, plus the csv-spectrum corpus.
168 tests. Divergences documented as tests, not hidden. Fun finds: git
autocrlf corrupts csv-spectrum's fixtures on Windows checkout, and the
corpus itself has a mismatched anonymized phone number (upstream PR filed).

**Section: what this is NOT**
- Not faster syscalls — page cache and TLS dominate real network I/O
- Not a Jackson replacement — Jackson is already internally fused; that's
  the existence proof. We feed it (PipeInputStream), not replace it.
- Not 1.0 — API will change; that's why the post asks for design feedback

**Closing ask:** which integration would you actually use — the opencsv-shaped
reader, the InputStream bridge, or native pipelines? Link repo + benchmarks.

**Comment-thread prep (answers to have ready):**
- "Why not just use Okio?" — Okio solved buffering, not fusion/skipping;
  also JVM-only story vs our FFM/MemorySegment tier. Genuinely great library;
  compare buffer models honestly.
- "JMH stack profiler is safepoint-biased" — yes, said so in the README;
  qualitative use only; async-profiler run is planned (flamelens post).
- "Virtual threads make this irrelevant" — orthogonal: Loom removes thread
  cost, not per-byte locks, triple buffers, or forced decode.
- "SWAR overcounts on \v after \n" — known, documented, exact variant planned.
