package dev.atomic.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
    }
}
