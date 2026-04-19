package dev.atomic.app.online

import dev.atomic.shared.net.ClientMessage
import dev.atomic.shared.net.ServerMessage
import dev.atomic.shared.net.decodeServerMessage
import dev.atomic.shared.net.encode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Connection lifecycle exposed to the UI. */
enum class LinkStatus { Idle, Connecting, Connected, Closed, Failed }

/**
 * Tiny WebSocket wrapper around the relay protocol. Owns a single
 * [HttpClient] and at most one live session — calling [connect] while a
 * session is already open closes the old one first.
 *
 * Everything is hot: connect once per screen, [send] from anywhere on the
 * owning coroutine, observe [events] and [status] as flows.
 */
class MatchClient(private val scope: CoroutineScope) {

    private val http: HttpClient = HttpClient { install(WebSockets) }

    private val _status = MutableStateFlow(LinkStatus.Idle)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerMessage> = _events.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var runner: Job? = null
    // Buffered so UI can enqueue before the session is up; drained as soon as
    // we're connected.
    private val outbox = Channel<ClientMessage>(Channel.BUFFERED)

    fun connect(url: String) {
        runner?.cancel()
        _lastError.value = null
        _status.value = LinkStatus.Connecting
        runner = scope.launch {
            try {
                http.webSocketSession(urlString = url).also { s ->
                    session = s
                    _status.value = LinkStatus.Connected
                    val sender = launch {
                        for (msg in outbox) s.send(Frame.Text(msg.encode()))
                    }
                    try {
                        for (frame in s.incoming) {
                            if (frame is Frame.Text) {
                                val parsed = runCatching { decodeServerMessage(frame.readText()) }.getOrNull()
                                if (parsed != null) _events.emit(parsed)
                            }
                        }
                    } finally {
                        sender.cancel()
                        session = null
                        _status.value = LinkStatus.Closed
                    }
                }
            } catch (t: Throwable) {
                session = null
                _lastError.value = t.message ?: t::class.simpleName ?: "connection failed"
                _status.value = LinkStatus.Failed
            }
        }
    }

    suspend fun send(message: ClientMessage) {
        outbox.send(message)
    }

    fun disconnect() {
        runner?.cancel()
        scope.launch {
            runCatching { session?.close() }
            session = null
        }
    }

    fun shutdown() {
        disconnect()
        http.close()
    }
}
