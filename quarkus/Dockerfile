# Same variants as spring/Dockerfile so the numbers are comparable.

# ---- build (fast-jar: target/quarkus-app) ----
# Official Maven image instead of ./mvnw: the Temurin image has no curl/unzip, and
# the wrapper's Java fallback downloader failed its own SHA-256 check there.
FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && cp -r target/quarkus-app /app

# ---- A: full JRE on Ubuntu ----
FROM docker.io/library/eclipse-temurin:25-jre AS jvm
COPY --from=build /app /app
WORKDIR /app
ENV SQLITE_PATH=/data/djclass.sqlite3
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]

# ---- B: jlink'ed runtime on distroless ----
FROM build AS jre
# quarkus-run.jar is only a launcher: analyse every jar (incl. the build-time generated
# bytecode), with lib/ on the module path so modular jars' `requires` resolve.
# jdk.zipfs: Quarkus opens jars via the jar: FileSystemProvider (ServiceLoader, invisible to jdeps).
RUN MODS=$(jdeps --ignore-missing-deps -q --multi-release 25 --print-module-deps \
        --module-path /app/lib/main:/app/lib/boot $(find /app -name '*.jar')),jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.zipfs \
 && echo "modules: $MODS" \
 && jlink --add-modules "$MODS" --strip-debug --no-man-pages --no-header-files \
        --compress zip-6 --output /jre

FROM gcr.io/distroless/cc-debian13:nonroot AS jlink
COPY --from=jre /jre /jre
COPY --from=build /app /app
WORKDIR /app
ENV SQLITE_PATH=/data/djclass.sqlite3
ENTRYPOINT ["/jre/bin/java", "-jar", "quarkus-run.jar"]

# ---- C: B + JDK 25 AOT cache. Training run: start, wait for /healthz, SIGTERM ----
FROM build AS aot-train
COPY --from=jre /jre /jre
WORKDIR /app
RUN SQLITE_PATH=/tmp/train.sqlite3 /jre/bin/java -XX:AOTCacheOutput=/app/app.aot -jar quarkus-run.jar & pid=$!; \
    for i in $(seq 1 120); do \
      (exec 3<>/dev/tcp/127.0.0.1/8000 && printf 'GET /healthz HTTP/1.0\r\n\r\n' >&3 && grep -q '"UP"' <&3) 2>/dev/null && break; sleep 0.5; \
    done; \
    kill -TERM $pid; wait $pid; ls -la /app/app.aot

FROM gcr.io/distroless/cc-debian13:nonroot AS jlink-aot
COPY --from=jre /jre /jre
COPY --from=aot-train /app /app
WORKDIR /app
ENV SQLITE_PATH=/data/djclass.sqlite3
ENTRYPOINT ["/jre/bin/java", "-XX:AOTCache=app.aot", "-jar", "quarkus-run.jar"]

# ---- D: native image (Mandrel, Quarkus' GraalVM distribution) ----
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25.0.4.1 AS native-build
USER root
COPY --from=docker.io/library/maven:3.9-eclipse-temurin-25 /usr/share/maven /opt/maven
WORKDIR /src
COPY pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 /opt/maven/bin/mvn -B -q package -Dnative -DskipTests \
 && cp target/*-runner /djclass

FROM gcr.io/distroless/cc-debian13:nonroot AS native
COPY --from=native-build /djclass /djclass
ENV SQLITE_PATH=/data/djclass.sqlite3
ENTRYPOINT ["/djclass"]
