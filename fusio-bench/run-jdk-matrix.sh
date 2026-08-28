#!/usr/bin/env bash
# Run the portable benchmark suite on one or more JDKs and write one result
# file per JDK into fusio-bench/results/.
#
#   ./fusio-bench/run-jdk-matrix.sh <label>=<path-to-java> [<label>=<path-to-java> ...]
#
#   e.g. ./fusio-bench/run-jdk-matrix.sh jdk8=/opt/jdk8/bin/java jdk17=/opt/jdk17/bin/java
#
# With no arguments it runs on whatever `java` is on PATH, labelled by its
# feature version — this is what the Docker images call.
#
# Environment:
#   BENCH_JAR    path to benchmarks-portable.jar (default: fusio-bench/target/benchmarks-portable.jar)
#   BENCH_OUT    results directory (default: fusio-bench/results)
#   BENCH_ARGS   extra JMH args (default: -e JdbcBench -e MmapBench -e SegmentBench) —
#                JdbcBench needs a live MySQL; the FFM benches are not in the portable jar.
#   BENCH_JVM_ARGS  JVM flags for the forked benchmark JVM (default: -Xms1g -Xmx1g) — the
#                same bounded heap on every JDK, so GC sizing is not a variable
#   BENCH_QUICK  set to 1 for a fast smoke run (-wi 1 -i 2 -f 1)
#
# JDKs are run strictly one after another: concurrent JVMs would skew each
# other's numbers.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="${BENCH_JAR:-$here/target/benchmarks-portable.jar}"
out="${BENCH_OUT:-$here/results}"
args="${BENCH_ARGS:--e JdbcBench -e MmapBench -e SegmentBench}"
jvm_args="${BENCH_JVM_ARGS:--Xms1g -Xmx1g}"
if [ "${BENCH_QUICK:-0}" = "1" ]; then
  args="$args -wi 1 -i 2 -f 1"
fi

if [ ! -f "$jar" ]; then
  echo "benchmark jar not found: $jar" >&2
  echo "build it with: mvn -Pportable -pl fusio-bench -am -DskipTests package" >&2
  exit 1
fi
mkdir -p "$out"

if [ $# -eq 0 ]; then
  if [ -n "${JDK:-}" ]; then
    v="$JDK"                       # set by the Docker image
  else
    v="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p' | head -1)"
    v="${v#1.}"                    # Java 8 reports 1.8
    v="${v%%.*}"
  fi
  set -- "jdk$v=java"
fi

for spec in "$@"; do
  label="${spec%%=*}"
  java_bin="${spec#*=}"
  txt="$out/$label.txt"
  json="$out/$label.json"
  {
    echo "# fusio portable benchmarks"
    echo "# label:   $label"
    echo "# date:    $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# java:    $("$java_bin" -version 2>&1 | tr '\n' ' ')"
    echo "# os:      $(uname -srm 2>/dev/null || echo unknown)"
    echo "# cpu:     ${BENCH_CPU:-$( (grep -m1 'model name' /proc/cpuinfo 2>/dev/null | sed 's/.*: //') || echo unknown)}"
    echo "# cpus:    $(nproc 2>/dev/null || echo unknown)"
    echo "# jmh:     $args"
    echo "# jvmArgs: $jvm_args"
    echo
  } > "$txt"
  echo ">>> $label: $java_bin"
  # shellcheck disable=SC2086
  "$java_bin" -jar "$jar" $args -jvmArgs "$jvm_args" -rf json -rff "$json" 2>&1 | tee -a "$txt"
done
echo "results in $out"
