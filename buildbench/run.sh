#!/bin/bash
# Build-cost benchmark: every build runs in its own container and measure.sh reads that
# container's cgroup (wall time, CPU time, peak memory).
#   GO_SRC=/path/to/chzzk-djclass-chat ./run.sh
# Scenarios: cold (empty dependency cache), clean (deps cached, no build output/cache),
# incremental (one source file changed), native (deps cached).
set -u
here=$(cd "$(dirname "$0")" && pwd); repo=$(dirname "$here")
work=${WORK:-/tmp/buildbench}; mkdir -p "$work"; cd "$work"
M="-v $here/measure.sh:/m.sh:ro --entrypoint /m.sh"   # builder images' entrypoints differ (native-image)
fresh() { podman unshare rm -rf work/$1; mkdir -p work; cp -r "$2" work/$1; }
vol() { podman volume rm -f "$@" >/dev/null 2>&1; for v in "$@"; do podman volume create $v >/dev/null; done; }
size() { podman run --rm -v $1:/x docker.io/library/golang:1.27.1-trixie du -sh /x | cut -f1; }
r() { printf '%-26s ' "$1"; shift; podman run --rm $M "$@"; }

podman build -q -t bb-graalvm -f - . >/dev/null <<'C'
FROM ghcr.io/graalvm/native-image-community:25
RUN microdnf install -y findutils > /dev/null && microdnf clean all > /dev/null
C
podman build -q -t bb-mandrel -f - . >/dev/null <<'C'
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25.0.4.1
USER root
COPY --from=docker.io/library/maven:3.9-eclipse-temurin-25 /usr/share/maven /opt/maven
ENV PATH=/opt/maven/bin:$PATH
C

GO_ENV="-e GOMODCACHE=/gomod -e GOCACHE=/gocache -e CGO_ENABLED=0 -e GOFLAGS=-trimpath"
GO_BUILD='go build -ldflags="-s -w" -o /tmp/djclass ./cmd/server'
GOIMG=docker.io/library/golang:1.27.1-trixie
GOV="-v bb-gomod:/gomod -v bb-gocache:/gocache -v ./work/go:/src -w /src"
vol bb-gomod bb-gocache; fresh go "$GO_SRC"
r go-cold        $GO_ENV $GOV $GOIMG "go mod download && $GO_BUILD"
echo "  (module cache $(size bb-gomod), build cache $(size bb-gocache))"
vol bb-gocache; fresh go "$GO_SRC"
r go-clean       $GO_ENV $GOV $GOIMG "$GO_BUILD"
echo '// touch' >> work/go/internal/realtime/hub.go
r go-incremental $GO_ENV $GOV $GOIMG "$GO_BUILD"
# How much of the clean build is modernc.org/sqlite (C transpiled to Go)?
vol bb-gocache; fresh go "$GO_SRC"
r go-clean-sqlite-only $GO_ENV $GOV $GOIMG 'go build modernc.org/sqlite'
r go-then-rest         $GO_ENV $GOV $GOIMG "$GO_BUILD"

JDK=docker.io/library/eclipse-temurin:25-jdk
SV="-v bb-gradle:/root/.gradle -v ./work/spring:/src -w /src"
vol bb-gradle; fresh spring "$repo/spring"
r spring-cold        $SV $JDK './gradlew --no-daemon -q bootJar'
echo "  (gradle home $(size bb-gradle))"
fresh spring "$repo/spring"
r spring-clean       $SV $JDK './gradlew --no-daemon -q bootJar'
echo '// touch' >> work/spring/src/main/java/kr/felis/djclass/realtime/Hub.java
r spring-incremental $SV $JDK './gradlew --no-daemon -q bootJar'

MVN=docker.io/library/maven:3.9-eclipse-temurin-25
QV="-v bb-m2:/root/.m2 -v ./work/quarkus:/src -w /src"
vol bb-m2; fresh quarkus "$repo/quarkus"
r quarkus-cold        $QV $MVN 'mvn -B -q package -DskipTests'
echo "  (maven repo $(size bb-m2))"
fresh quarkus "$repo/quarkus"
r quarkus-clean       $QV $MVN 'mvn -B -q package -DskipTests'
echo '// touch' >> work/quarkus/src/main/java/kr/felis/djclass/realtime/Hub.java
r quarkus-incremental $QV $MVN 'mvn -B -q package -DskipTests'

fresh spring "$repo/spring"
r spring-native  $SV localhost/bb-graalvm './gradlew --no-daemon -q nativeCompile'
fresh quarkus "$repo/quarkus"
r quarkus-native $QV localhost/bb-mandrel 'mvn -B -q package -Dnative -DskipTests'
