# ---- Stage 1a: build the wasmJs web bundle -----------------------------------
# The web bundle is built alongside the server in a dedicated stage so that
# the Node/webpack toolchain doesn't end up in the final runtime image.
FROM eclipse-temurin:21-jdk AS webbuild
WORKDIR /src

# Kotlin/Wasm downloads a Node.js toolchain that is dynamically linked against
# libatomic; install it so `kotlinWasmToolingSetup` can launch node.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libatomic1 \
 && rm -rf /var/lib/apt/lists/*

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY shared shared
COPY composeApp composeApp

# settings.gradle.kts includes :server and :androidApp; Gradle requires
# project directories to exist even when we only build :composeApp.
RUN mkdir -p server androidApp

RUN chmod +x gradlew \
 && ./gradlew --no-daemon :composeApp:wasmJsBrowserDistribution

# ---- Stage 1b: build the server distribution ---------------------------------
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

# settings.gradle.kts includes :composeApp and :androidApp; Gradle requires
# project directories to exist even when we only build :server.
RUN mkdir -p composeApp androidApp

RUN chmod +x gradlew \
 && ./gradlew --no-daemon :server:installDist

# ---- Stage 2: minimal runtime image ------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /src/server/build/install/server/ /app/
COPY --from=webbuild /src/composeApp/build/dist/wasmJs/productionExecutable/ /app/web/

ENV PORT=8080
ENV WEB_DIR=/app/web
EXPOSE 8080

# The `application` Gradle plugin generates a launcher script at bin/server.
ENTRYPOINT ["/app/bin/server"]
