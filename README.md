# Atomic

Kotlin Multiplatform clone of the classic **Chain Reaction** game (known on
Nokia phones as *Atomic*). Runs on Android, iOS, Desktop and the browser
(Compose/wasmJs) from a single Compose Multiplatform UI; online play goes
through a tiny Ktor relay server that also serves the web client.

## Features

- **Chain Reaction rules:** 2–4 players, place an atom in an empty or own
  cell, reach critical mass to explode and convert neighbours. Corridors on
  blocked-cell levels legitimately have critical mass 1.
- **Explosion modes:** *Wave* (simultaneous tick-based) or *Recursive*,
  selectable per match.
- **Local modes (no internet):** hot-seat and vs bot with three difficulties
  (Easy/Random, Medium/Heuristic, Hard/Minimax depth 2).
- **Online play:** 6-digit room codes via a lightweight relay server.
- **Level editor:** configurable size and blocked cells.
- **Animated cascades:** each explosion wave is rendered as a step so the
  board reaction is actually visible.

## Project layout

```
atomic/
├── shared/          # KMP: engine, AI, protocol (pure Kotlin, no UI)
├── composeApp/      # KMP library: shared Compose UI (Android/iOS/Desktop)
├── androidApp/      # Android application wrapper
├── server/          # Ktor relay server (JVM)
├── gradle/          # Version catalog + wrapper
└── docs/            # Extra docs (see docs/RELAY.md)
```

`shared` is pure Kotlin and is depended on by both the app and the server,
so the wire protocol types are literally the same classes on both ends.

## Requirements

- **JDK 21** (Temurin recommended). Android compilation targets bytecode 17
  because D8/R8 desugaring doesn't support higher yet.
- **Android SDK 36** with `build-tools;36.0.0` for APK builds.
- **Xcode 15+** for iOS (not required for other platforms).
- The Gradle wrapper is checked in — no system Gradle needed.

## Building & running

### Desktop

```sh
./gradlew :composeApp:run
```

### Android

Build a debug APK and install it on a connected device:

```sh
./gradlew :androidApp:installDebug
```

Or a release APK (signed with the committed shared debug keystore so local
builds and CI produce install-compatible APKs):

```sh
./gradlew :androidApp:assembleRelease
# => androidApp/build/outputs/apk/release/androidApp-release.apk
```

`androidApp/keystore/shared-debug.keystore` is intentionally checked in —
without it, Android refuses to install a build over one signed with a
different key ("conflicts with existing package"). Since this project has
no Play Store release track, a shared debug-style key is fine; swap in a
real release keystore when that changes.

CI produces the same artifact via the **Build APK** workflow
(`workflow_dispatch` trigger, 1-day artifact retention).

### iOS

The `iosApp/` directory holds the SwiftUI wrapper. The Xcode project
is generated from `iosApp/project.yml` by
[XcodeGen](https://github.com/yonaskolb/XcodeGen) so only the YAML is
committed — run once:

```sh
brew install xcodegen
cd iosApp && xcodegen generate && open iosApp.xcodeproj
```

A pre-build phase invokes
`:composeApp:embedAndSignAppleFrameworkForXcode`, so no manual
framework build is needed. See `iosApp/README.md` for signing setup.

If you only want the framework (e.g. for a non-Xcode toolchain):

```sh
./gradlew :composeApp:linkDebugFrameworkIosArm64
```

### Web (wasmJs)

Run the browser-hosted version locally:

```sh
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
# serves http://localhost:8080 via webpack-dev-server
```

Produce a static bundle for hosting:

```sh
./gradlew :composeApp:wasmJsBrowserDistribution
# => composeApp/build/dist/wasmJs/productionExecutable/
#      index.html, composeApp.js, composeApp.wasm, skiko.{js,wasm}, …
```

The relay server's Dockerfile builds this bundle in a dedicated stage and
copies it into `/app/web`; Ktor serves it via `staticFiles("/")` when
`WEB_DIR` (default `/app/web`) exists. On Railway the single service then
handles both the `wss://` relay and the static web client — same origin,
no CORS setup.

### Tests

```sh
./gradlew :shared:jvmTest :server:test :composeApp:desktopTest
```

## Online play

Atomic uses a small **relay server** to shuttle moves between clients.
The server holds no durable state — rooms live in memory and are garbage
collected when everyone leaves.

- **Run it locally:** `./gradlew :server:run` (listens on `localhost:8080`)
- **Docker / cloud deploy:** see **[docs/RELAY.md](docs/RELAY.md)** for a
  Dockerfile, `docker-compose`, and step-by-step cloud deploy guides.

Once the server is reachable, open **Online** in the app, enter the server
URL (defaults to `wss://atomic-server-production.up.railway.app/game` — a
free Railway-hosted instance; swap in your own deploy or `ws://localhost:8080/game`
for a local server), and either create a room (the app shows a 6-digit code)
or join one with a code shared by a friend.

## Wire protocol

Messages are JSON with a class discriminator field `"t"`. See
`shared/src/commonMain/kotlin/dev/atomic/shared/net/Protocol.kt` for the
full sealed hierarchy. In short:

| Direction | Message       | Purpose |
|-----------|---------------|---------|
| C → S     | `CreateRoom`  | new room with level/settings/seats |
| C → S     | `JoinRoom`    | join by 6-digit code |
| C → S     | `SetReady`    | signal readiness in the lobby |
| C → S     | `MakeMove`    | place an atom at `Pos` |
| C → S     | `LeaveRoom`   | voluntarily leave |
| C → S     | `Chat`        | free-form text |
| S → C     | `RoomCreated` / `RoomJoined` | your seat + current players |
| S → C     | `PlayerJoined` / `PlayerLeft` | lobby deltas |
| S → C     | `GameStarted` | initial `GameState` when all seated & ready |
| S → C     | `GameUpdated` | authoritative state after every move |
| S → C     | `GameOver`    | winning seat |
| S → C     | `ErrorMessage`| room-not-found, bad move, etc. |

The server is authoritative: it validates every move through the same
`GameEngine.applyMove` that runs locally, then broadcasts the full
`GameState`. Clients don't replay history.

## Architecture notes

- `shared/engine/GameEngine.kt` is the single source of truth for game
  logic. `applyMoveTraced` returns the per-wave snapshots of a move's
  cascade (used by the client to animate and available to anyone that
  wants a replayable trace); `applyMove` is the fast path used by bots
  and the server.
- `shared/ai/` has three bots behind a common `Bot` interface.
- `composeApp/` is **not** an Android application — under AGP 9 + KMP the
  `com.android.application` plugin can no longer be combined with the
  multiplatform plugin, so `:composeApp` is a multiplatform **library**
  (Android AAR + iOS framework + Desktop JAR), and `:androidApp` wraps it
  with a pure Android `MainActivity`.

## CI

- **Tests** (`.github/workflows/tests.yml`) — runs on push/PR/manual,
  executes `:shared:jvmTest` and `:server:test`.
- **Build APK** (`.github/workflows/build-apk.yml`) — manual trigger,
  produces a release APK as a 1-day artifact.
- **Publish Server Image** (`.github/workflows/publish-server-image.yml`) —
  on `main` pushes, version tags (`v*`) or manual trigger, builds
  `server/Dockerfile` and pushes to Docker Hub. Required repo secrets:
  `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`. Optional repo variable:
  `DOCKERHUB_REPOSITORY` (for example `yourname/atomic-server`), otherwise
  defaults to `${DOCKERHUB_USERNAME}/atomic-server`.

## License

MIT — see [LICENSE](LICENSE).
