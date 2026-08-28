# Reproducible cross-JDK benchmark image.
#
#   docker build -f fusio-bench/docker/Dockerfile --build-arg JDK=8 -t fusio-bench:jdk8 .
#   docker run --rm -v "$PWD/fusio-bench/results:/results" fusio-bench:jdk8
#
# Stage 1 builds the portable (Java 8 bytecode, no FFM) benchmark jar ONCE on
# JDK 25 — `--release` pins the bytecode level, so the exact same jar runs on
# every runtime under test. Stage 2 is the runtime: an Eclipse Temurin JRE of
# the requested feature version, native for the host architecture (amd64 and
# arm64 images exist for 8/11/17/21/25), so nothing is emulated.
#
# Build context is the repository root (the jar needs fusio-core and fusio-jdbc).

ARG JDK=25

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY fusio-core/pom.xml fusio-core/
COPY fusio-jdbc/pom.xml fusio-jdbc/
COPY fusio-ffm/pom.xml fusio-ffm/
COPY fusio-bench/pom.xml fusio-bench/
COPY fusio-spring-boot-starter/pom.xml fusio-spring-boot-starter/
COPY fusio-spring-boot3-starter/pom.xml fusio-spring-boot3-starter/
COPY fusio-spring-boot2-starter/pom.xml fusio-spring-boot2-starter/
# warm the dependency cache before copying sources so source edits don't re-download
RUN mvn -B -ntp -q -Pportable -pl fusio-bench -am dependency:go-offline || true
COPY fusio-core fusio-core
COPY fusio-jdbc fusio-jdbc
COPY fusio-bench fusio-bench
RUN mvn -B -ntp -q -Pportable -pl fusio-bench -am -DskipTests package

FROM eclipse-temurin:${JDK}-jre
ARG JDK
ENV JDK=${JDK}
WORKDIR /bench
COPY --from=build /src/fusio-bench/target/benchmarks-portable.jar /bench/benchmarks-portable.jar
COPY fusio-bench/run-jdk-matrix.sh /bench/run-jdk-matrix.sh
ENV BENCH_JAR=/bench/benchmarks-portable.jar \
    BENCH_OUT=/results
VOLUME /results
# Label the result file by the JDK under test; BENCH_QUICK=1 for a smoke run.
ENTRYPOINT ["bash", "/bench/run-jdk-matrix.sh"]
CMD []
