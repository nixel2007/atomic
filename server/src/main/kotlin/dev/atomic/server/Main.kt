package dev.atomic.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("atomic-server")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
    }
    // Ensure index.html is always re-validated so new deployments are picked up.
    // The Service Worker handles offline / fast-repeat-load caching itself.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = context.request.path()
        if (path == "/" || path == "/index.html") {
            context.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        }
    }
    val rooms = RoomManager()
    val janitor = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    janitor.launch {
        while (true) {
            delay(5.minutes)
            val evicted = rooms.sweepIdle()
            if (evicted > 0) log.info("janitor evicted $evicted idle room(s), ${rooms.roomCount} remaining")
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        get("/metrics") {
            call.respondText("atomic_rooms ${rooms.roomCount}\n")
        }
        gameWebSocket(rooms)

        // Serves the prebuilt wasmJs bundle (composeApp.js + composeApp.wasm +
        // index.html + skiko assets) alongside the relay, so the web client
        // and the WebSocket share origin and no CORS setup is needed.
        val webDir = System.getenv("WEB_DIR")?.let(::File) ?: File("/app/web")
        if (webDir.isDirectory) {
            log.info("serving static web assets from $webDir")
            staticFiles("/", webDir) { default("index.html") }
        } else {
            log.info("no web bundle at $webDir — skipping static route")
        }
    }
}
