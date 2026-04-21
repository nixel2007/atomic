package dev.atomic.app.online

import dev.atomic.shared.net.ClientMessage
import dev.atomic.shared.net.ErrorCode
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
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Connection lifecycle exposed to the UI.
 *
 * [Reconnecting] is a transient state between waves of a retrying connection
 * loop — the client has stored resume info and will keep trying to rejoin
 * the same room until [LinkStatus.Connected] succeeds or the user leaves
 * intentionally.
 */
enum class LinkStatus { Idle, Connecting, Connected, Reconnecting, Closed, Failed }

/** Where to resume after an unexpected socket drop. */
private data class ResumePoint(val url: String, val code: String, val nickname: String)

/**
 * Tiny WebSocket wrapper around the relay protocol. Owns a single
 * [HttpClient] and at most one live session — calling [connect] while a
 * session is already open closes the old one first.
 *
 * The internal coroutine scope is process-owned ([SupervisorJob] +
 * [Dispatchers.Default]), so the connection survives Activity recreation and
 * minimise-then-resume on Android. Callers never dispose the client.
 *
 * Auto-reconnect: once [setResumePoint] is called (typically on RoomCreated /
 * RoomJoined), a socket drop triggers exponential-backoff reconnect attempts
 * that re-send `JoinRoom(code, nickname)` on success. The server-side grace
 * window keeps the seat reserved while this plays out.
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
    private var outbox: Channel<ClientMessage> = Channel(Channel.BUFFERED)

    @Volatile private var lastUrl: String? = null
    @Volatile private var resume: ResumePoint? = null
    @Volatile private var intentionalClose = false

    init {
        // Collect RoomNotFound so a stale resume doesn't drive the loop forever.
        scope.launch {
            events.collect { msg ->
                if (msg is ServerMessage.ErrorMessage && msg.code == ErrorCode.RoomNotFound) {
                    resume = null
                }
            }
        }
    }

    fun connect(url: String) {
        intentionalClose = false
        resume = null
        lastUrl = url
        _status.value = LinkStatus.Connecting
        _lastError.value = null
        scope.launch {
            runner?.cancelAndJoin()
            runner = scope.launch { connectionLoop() }
        }
    }

    fun send(message: ClientMessage) {
        scope.launch { runCatching { outbox.send(message) } }
    }

    /**
     * Waits for the current connect attempt to reach a terminal state
     * ([LinkStatus.Connected], [LinkStatus.Failed] or [LinkStatus.Closed]) and,
     * if it succeeded, runs [block] on the client's own scope. Use this so UI
     * callbacks can "connect + send CreateRoom/JoinRoom" without owning a
     * coroutine themselves.
     */
    fun scheduleAfterConnect(block: suspend MatchClient.() -> Unit) {
        scope.launch {
            val terminal = status.first {
                it == LinkStatus.Connected || it == LinkStatus.Failed || it == LinkStatus.Closed
            }
            if (terminal == LinkStatus.Connected) block()
        }
    }

    /**
     * Record a room to rejoin on any future unexpected drop. Call this once
     * the server confirms the seat (after RoomCreated / RoomJoined arrives).
     */
    fun setResumePoint(code: String, nickname: String) {
        val url = lastUrl ?: return
        resume = ResumePoint(url, code, nickname)
    }

    /** Call before an intentional leave so the client doesn't try to rejoin. */
    fun clearResumePoint() {
        resume = null
    }

    fun disconnect() {
        intentionalClose = true
        resume = null
        scope.launch {
            runCatching { session?.close() }
            runner?.cancel()
            session = null
            _status.value = LinkStatus.Closed
        }
    }

    private suspend fun connectionLoop() {
        var attempt = 0
        var reconnecting = false
        while (true) {
            val url = resume?.url ?: lastUrl ?: return
            if (reconnecting) {
                _status.value = LinkStatus.Reconnecting
                delay(backoffMillis(attempt))
            } else {
                _status.value = LinkStatus.Connecting
            }
            _lastError.value = null
            val localOutbox = Channel<ClientMessage>(Channel.BUFFERED)
            outbox = localOutbox
            val errored = try {
                http.webSocketSession(urlString = url).also { s ->
                    session = s
                    _status.value = LinkStatus.Connected
                    attempt = 0
                    resume?.let {
                        s.send(Frame.Text(ClientMessage.JoinRoom(it.code, it.nickname).encode()))
                    }
                    coroutineScope {
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
                        }
                    }
                }
                false
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                session = null
                _lastError.value = t.message ?: t::class.simpleName ?: "connection failed"
                true
            }
            if (intentionalClose) {
                _status.value = LinkStatus.Closed
                return
            }
            if (resume == null) {
                // No active seat to resume — the initial handshake failed.
                // Bail out so a bad URL or a dead server doesn't loop forever.
                _status.value = if (errored) LinkStatus.Failed else LinkStatus.Closed
                return
            }
            // We were in a room — keep trying until the session comes back,
            // the user intentionally leaves, or the process dies. Users often
            // background the app for longer than the 8-attempt / ~2 min
            // backoff would otherwise permit.
            attempt++
            reconnecting = true
        }
    }

    companion object {
        internal fun backoffMillis(attempt: Int): Long {
            // 1s, 2s, 4s, 8s, 16s, then flat 30s.
            val exp = 1000L shl (attempt - 1).coerceIn(0, 5)
            return exp.coerceAtMost(30_000L)
        }
    }
}

/** Process-wide singleton. Created lazily on first access; never disposed. */
object MatchClientHolder {
    val instance: MatchClient by lazy { MatchClient() }
}
