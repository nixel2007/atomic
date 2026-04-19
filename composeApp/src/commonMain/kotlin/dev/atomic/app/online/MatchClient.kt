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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Connection lifecycle exposed to the UI. */
enum class LinkStatus { Idle, Connecting, Connected, Closed, Failed }

/**
 * Tiny WebSocket wrapper around the relay protocol. Owns a single
 * [HttpClient] and at most one live session — calling [connect] while a
 * session is already open closes the old one first.
 *
 * The internal coroutine scope is process-owned ([SupervisorJob] +
 * [Dispatchers.Default]), so the connection survives Activity recreation and
 * minimise-then-resume on Android. Callers never dispose the client.
 */
class MatchClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http: HttpClient = HttpClient { install(WebSockets) }

    private val _status = MutableStateFlow(LinkStatus.Idle)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerMessage> = _events.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var runner: Job? = null
    // Re-created on each connect so stale messages from a previous session
    // don't leak into a new one.
    private var outbox: Channel<ClientMessage> = Channel(Channel.BUFFERED)

    fun connect(url: String) {
        // Flip status synchronously so scheduleAfterConnect observers don't
        // mistake a leftover terminal state from a previous attempt for the
        // current one.
        _status.value = LinkStatus.Connecting
        _lastError.value = null
        scope.launch {
            runner?.cancelAndJoin()
            val localOutbox = Channel<ClientMessage>(Channel.BUFFERED)
            outbox = localOutbox
            runner = scope.launch {
                try {
                    http.webSocketSession(urlString = url).also { s ->
                        session = s
                        _status.value = LinkStatus.Connected
                        val sender = launch {
                            for (msg in localOutbox) s.send(Frame.Text(msg.encode()))
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
                            localOutbox.close()
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
    }

    fun send(message: ClientMessage) {
        scope.launch {
            runCatching { outbox.send(message) }
        }
    }

    /**
     * Waits for the current connect attempt to reach a terminal state
     * ([LinkStatus.Connected], [LinkStatus.Failed] or [LinkStatus.Closed]) and,
     * if it succeeded, runs [block] on the client's own scope. Use this so UI
     * callbacks can "connect + send CreateRoom/JoinRoom" without owning a
     * coroutine themselves.
     *
     * [connect] flips the status to [LinkStatus.Connecting] synchronously,
     * so calling this immediately after connect correctly waits for the new
     * attempt, not any leftover terminal value from a previous one.
     */
    fun scheduleAfterConnect(block: suspend MatchClient.() -> Unit) {
        scope.launch {
            val terminal = status.first {
                it == LinkStatus.Connected || it == LinkStatus.Failed || it == LinkStatus.Closed
            }
            if (terminal == LinkStatus.Connected) block()
        }
    }

    fun disconnect() {
        scope.launch {
            runCatching { session?.close() }
            runner?.cancel()
            session = null
        }
    }
}

/** Process-wide singleton. Created lazily on first access; never disposed. */
object MatchClientHolder {
    val instance: MatchClient by lazy { MatchClient() }
}
