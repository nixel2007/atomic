package dev.atomic.server

import dev.atomic.shared.engine.GameEngine
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.engine.PlayerKind
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import dev.atomic.shared.net.ErrorCode
import dev.atomic.shared.net.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class RoomManager {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val rnd = Random.Default

    suspend fun create(
        level: Level,
        settings: GameSettings,
        seats: Int,
        host: Session,
        nickname: String
    ): Room {
        require(seats in 2..4)
        val code = newCode()
        val room = Room(code, level, settings, seats)
        rooms[code] = room
        val seat = room.admit(host, nickname)
        host.send(ServerMessage.RoomCreated(code, seat!!, room.players, room.maxSeats))
        return room
    }

    /**
     * Tries to rejoin an existing seat (same nickname as one currently in its
     * disconnect grace window). If no such seat exists, admits the session as
     * a new player. Returns true iff the session is now attached to the room.
     */
    suspend fun join(code: String, session: Session, nickname: String): Boolean {
        val room = rooms[code] ?: return false
        val rejoinSeat = room.tryRejoin(session, nickname)
        if (rejoinSeat != null) {
            val resumeState = room.currentState()
            session.send(ServerMessage.RoomJoined(room.code, rejoinSeat, room.players, room.maxSeats, room.readySeats()))
            if (resumeState != null) session.send(ServerMessage.GameStarted(resumeState))
            room.announceRejoined(rejoinSeat)
            return true
        }
        val seat = room.admit(session, nickname) ?: return false
        session.send(ServerMessage.RoomJoined(room.code, seat, room.players, room.maxSeats, room.readySeats()))
        room.announceJoined(seat)
        return true
    }

    fun evict(room: Room) {
        if (rooms.remove(room.code, room)) room.close()
    }

    private fun newCode(): String {
        while (true) {
            val code = buildString(6) { repeat(6) { append(rnd.nextInt(10)) } }
            if (!rooms.containsKey(code)) return code
        }
    }
}

class Room(
    val code: String,
    val level: Level,
    val settings: GameSettings,
    val maxSeats: Int
) {
    private val mutex = Mutex()
    private val occupants = mutableMapOf<Int, Occupant>()
    private var state: GameState? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Occupant(
        var session: Session?,
        val player: Player,
        var ready: Boolean = false,
        var graceJob: Job? = null
    )

    val players: List<Player> get() = occupants.values.sortedBy { it.player.index }.map { it.player }

    fun readySeats(): List<Int> = occupants.values.filter { it.ready }.map { it.player.index }.sorted()

    suspend fun currentState(): GameState? = mutex.withLock { state }

    /**
     * Reserves a seat for [session]. Returns the seat index, or null if the
     * room is full or a game is already running. Does not send any messages
     * — the caller is responsible for announcing the join.
     */
    suspend fun admit(session: Session, nickname: String): Int? = mutex.withLock {
        if (state != null) return@withLock null
        val seat = (0 until maxSeats).firstOrNull { it !in occupants.keys } ?: return@withLock null
        val player = Player(
            index = seat,
            name = nickname.ifBlank { "player$seat" },
            color = seatColor(seat),
            kind = PlayerKind.Human
        )
        occupants[seat] = Occupant(session, player)
        session.attach(this, seat)
        seat
    }

    /**
     * If an occupant with [nickname] is currently in its disconnect grace
     * window (session is null, graceJob is active), swap in the new session,
     * cancel the timer and return the seat. Otherwise null.
     */
    suspend fun tryRejoin(session: Session, nickname: String): Int? = mutex.withLock {
        val target = occupants.values.firstOrNull { it.session == null && it.player.name == nickname }
            ?: return@withLock null
        target.graceJob?.cancel()
        target.graceJob = null
        target.session = session
        session.attach(this, target.player.index)
        target.player.index
    }

    suspend fun announceJoined(seat: Int) {
        val player = mutex.withLock { occupants[seat]?.player } ?: return
        broadcastExcept(seat, ServerMessage.PlayerJoined(player))
    }

    suspend fun announceRejoined(seat: Int) {
        broadcastExcept(seat, ServerMessage.PlayerRejoined(seat))
    }

    suspend fun markReady(seat: Int) {
        val startState: GameState? = mutex.withLock {
            val occ = occupants[seat] ?: return
            if (occ.ready) return
            occ.ready = true
            if (occupants.size == maxSeats && occupants.values.all { it.ready }) {
                val snapshot = GameState.initial(level, settings, players)
                state = snapshot
                snapshot
            } else null
        }
        broadcast(ServerMessage.PlayerReady(seat))
        if (startState != null) broadcast(ServerMessage.GameStarted(startState))
    }

    suspend fun applyMove(seat: Int, pos: Pos) {
        val (updated, isOver) = mutex.withLock {
            val current = state
            if (current == null) {
                send(seat, ServerMessage.ErrorMessage(ErrorCode.BadRequest, "game not started"))
                return
            }
            if (current.currentPlayerIndex != seat) {
                send(seat, ServerMessage.ErrorMessage(ErrorCode.NotYourTurn, "wait for your turn"))
                return
            }
            if (!GameEngine.isLegalMove(current, pos)) {
                send(seat, ServerMessage.ErrorMessage(ErrorCode.InvalidMove, "illegal move"))
                return
            }
            val next = GameEngine.applyMove(current, pos)
            state = next
            next to next.isOver
        }
        if (isOver) {
            broadcast(ServerMessage.GameOver(updated, updated.winner!!))
        } else {
            broadcast(ServerMessage.GameUpdated(updated, pos, seat))
        }
    }

    /**
     * Socket-close path. Keeps the seat reserved for [graceSeconds] so the
     * same nickname can resume via [tryRejoin]. If the grace window elapses
     * without a reconnect, evicts the seat and calls [onEmpty] if the room
     * is now empty so the route can remove the Room from the manager.
     */
    suspend fun disconnect(seat: Int, graceSeconds: Int = GRACE_SECONDS, onEmpty: suspend () -> Unit) {
        val announce = mutex.withLock {
            val occ = occupants[seat] ?: return
            occ.session = null
            occ.graceJob?.cancel()
            occ.graceJob = scope.launch {
                delay(graceSeconds.seconds)
                val empty = evictSeat(seat)
                if (empty) onEmpty()
            }
            true
        }
        if (announce) broadcast(ServerMessage.PlayerDisconnected(seat, graceSeconds))
    }

    /** Explicit LeaveRoom: evict the seat immediately. */
    suspend fun leave(seat: Int): Boolean {
        val empty = evictSeat(seat)
        return empty
    }

    private suspend fun evictSeat(seat: Int): Boolean {
        val nowEmpty = mutex.withLock {
            val occ = occupants.remove(seat) ?: return@withLock occupants.isEmpty()
            occ.graceJob?.cancel()
            occupants.isEmpty()
        }
        broadcast(ServerMessage.PlayerLeft(seat))
        return nowEmpty
    }

    suspend fun chat(fromSeat: Int, text: String) {
        broadcast(ServerMessage.Chat(fromSeat, text.take(280)))
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun send(seat: Int, message: ServerMessage) {
        occupants[seat]?.session?.send(message)
    }

    private suspend fun broadcast(message: ServerMessage) {
        occupants.values.forEach { it.session?.send(message) }
    }

    private suspend fun broadcastExcept(seat: Int, message: ServerMessage) {
        occupants.forEach { (s, occ) -> if (s != seat) occ.session?.send(message) }
    }

    companion object {
        private const val GRACE_SECONDS = 30

        private val PALETTE = longArrayOf(
            0xFFE53935L, 0xFF1E88E5L, 0xFF43A047L, 0xFFFDD835L
        )

        private fun seatColor(seat: Int): Long = PALETTE[seat.coerceIn(0, PALETTE.lastIndex)]
    }
}
