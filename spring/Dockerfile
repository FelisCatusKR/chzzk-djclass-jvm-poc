# ---- build ----
FROM docker.io/library/eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/app.jar extract --destination /app

# ---- A: the "default" image: full JRE on Ubuntu ----
FROM docker.io/library/eclipse-temurin:25-jre AS jvm
COPY --from=build /app /app
WORKDIR /app
ENV SQLITE_PATH=/data/djclass.sqlite3
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- B: jlink'ed runtime with only the needed modules, on distroless ----
FROM build AS jre
RUN MODS=$(jdeps --ignore-missing-deps -q --multi-release 25 --print-module-deps \
        --class-path '/app/lib/*' /app/app.jar),jdk.crypto.ec,jdk.unsupported,jdk.management \
 && echo "modules: $MODS" \
 && jlink --add-modules "$MODS" --strip-debug --no-man-pages --no-header-files \
        --compress zip-6 --output /jre

FROM gcr.io/distroless/cc-debian13:nonroot AS jlink
COPY --from=jre /jre /jre
COPY --from=build /app /app
WORKDIR /app
ENV SQLITE_PATH=/data/djclass.sqlite3
ENTRYPOINT ["/jre/bin/java", "-jar", "app.jar"]

