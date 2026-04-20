# Atomic relay server

The relay is a small Ktor service that brokers online matches: clients connect
over WebSocket, create or join a room by 6-digit code, and the server forwards
moves and game state between them. It holds **no durable state** — rooms live
in memory and disappear when the last player leaves.

- Binary: `:server` Gradle module, `main` = `dev.atomic.server.MainKt`
- Listens on `0.0.0.0:${PORT:-8080}`
- Routes:
  - `GET /health` → `200 ok` (used by load balancers / uptime checks)
  - `WS /game` → the relay protocol (see README → *Wire protocol*)

## Running locally

```sh
./gradlew :server:run
```

That's it. The server is reachable at `ws://localhost:8080/game`. In the app,
open **Online**, leave the default URL, and create a room.

## Docker

The `server/Dockerfile` is a two-stage build: it resolves Gradle dependencies
and produces an `installDist` distribution in stage 1, then copies the result
into a slim JRE image. The build context is the repo root because the server
depends on `:shared`.

```sh
# from the repo root
docker build -f server/Dockerfile -t atomic-relay .
docker run --rm -p 8080:8080 atomic-relay
```

Or via Compose:

```sh
docker compose up --build
```

The `PORT` env var overrides the listen port if your platform injects one
(Railway, Render, Heroku, Cloud Run — they all do).

## Other hosts

The same Docker image runs anywhere that accepts a container and lets you
expose a port + WebSocket upgrades:

- **Railway / Render:** point at the Dockerfile, set `PORT` from the platform
  variable, expose the port in their dashboard.
- **Google Cloud Run:** `gcloud run deploy --source server` works, but Cloud
  Run's request timeout caps a single WS connection at 60 minutes. Fine for
  casual play, not for long idle lobbies.
- **Self-hosted (VPS, home server):** `docker compose up -d` + a reverse
  proxy (Caddy, nginx) in front for TLS.

## Operations

- **Logs:** `docker logs <container>`.
  The server logs one line per client connect/disconnect and one per room
  lifecycle event.
- **Scale up:** relay is CPU-light; memory is the constraint (every room
  holds a `GameState`). 256 MB is comfortable for hundreds of concurrent
  rooms.
- **Restarting the server kicks everyone out.** This is by design — rooms
  are ephemeral and clients re-join with a fresh code. There is no
  reconnection / resume logic yet.
