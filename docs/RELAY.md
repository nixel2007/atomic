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
(fly.io, Railway, Render, Heroku, Cloud Run — they all do).

## Deploying to fly.io (free tier)

fly.io runs Docker images on small VMs, has a generous free allowance, and
handles WebSockets + TLS out of the box — a good fit for a stateless relay.

1. **Install the CLI** and sign in:

   ```sh
   curl -L https://fly.io/install.sh | sh
   fly auth signup        # or: fly auth login
   ```

2. **Launch the app.** From the repo root, with `server/fly.toml` already
   checked in, just point `fly launch` at it — it'll detect the Dockerfile
   and create the app without generating a new config:

   ```sh
   fly launch --config server/fly.toml --dockerfile server/Dockerfile \
              --copy-config --no-deploy
   ```

   Pick a unique app name when prompted (e.g. `atomic-relay-yourhandle`)
   and edit `server/fly.toml`'s `app = "..."` line to match. Choose a
   region close to your players.

3. **Deploy:**

   ```sh
   fly deploy --config server/fly.toml --dockerfile server/Dockerfile
   ```

   First deploy takes a few minutes (Gradle downloads the world). Subsequent
   deploys reuse the layer cache and are much faster.

4. **Verify:**

   ```sh
   curl https://<your-app>.fly.dev/health          # => ok
   ```

5. **Point the app at it.** In the Online screen, enter:

   ```
   wss://<your-app>.fly.dev/game
   ```

   Note `wss://` (TLS) rather than `ws://`. fly.io terminates TLS at its edge
   and `force_https = true` in `fly.toml` upgrades plain HTTP automatically.

### Costs & auto-stop

`fly.toml` sets `auto_stop_machines = "stop"` and `min_machines_running = 0`,
so the VM sleeps when nobody's connected and wakes on the next request. This
keeps usage inside the free allowance for casual play. The first player after
a period of inactivity will see a ~2 s cold start; after that everyone is on
the warm process.

If you'd rather always be hot, set `min_machines_running = 1`.

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

- **Logs:** `fly logs -a <your-app>` (fly) or `docker logs <container>`.
  The server logs one line per client connect/disconnect and one per room
  lifecycle event.
- **Scale up:** relay is CPU-light; memory is the constraint (every room
  holds a `GameState`). 256 MB is comfortable for hundreds of concurrent
  rooms. Bump `memory` in `fly.toml` if needed.
- **Restarting the server kicks everyone out.** This is by design — rooms
  are ephemeral and clients re-join with a fresh code. There is no
  reconnection / resume logic yet.
