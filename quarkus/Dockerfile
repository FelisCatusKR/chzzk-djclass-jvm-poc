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
