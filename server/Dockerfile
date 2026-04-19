# ---- Stage 1: build the server distribution ----------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Cache the wrapper + version catalog first so dependency resolution is reusable
# across code changes.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./

# Project sources the server actually needs. `shared` is a dependency of the
# server module, everything else is client-only and is skipped to keep the
# build context small.
COPY shared shared
COPY server server

RUN chmod +x gradlew \
 && ./gradlew --no-daemon :server:installDist

# ---- Stage 2: minimal runtime image ------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /src/server/build/install/server/ /app/

ENV PORT=8080
EXPOSE 8080

# The `application` Gradle plugin generates a launcher script at bin/server.
ENTRYPOINT ["/app/bin/server"]
