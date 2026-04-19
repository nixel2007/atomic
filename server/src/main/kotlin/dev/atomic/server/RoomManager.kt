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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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

    suspend fun join(code: String, session: Session, nickname: String): Boolean {
        val room = rooms[code] ?: return false
        val seat = room.admit(session, nickname) ?: return false
        session.send(ServerMessage.RoomJoined(room.code, seat, room.players, room.maxSeats, room.readySeats()))
        room.announceJoined(seat)
        return true
    }

    fun evict(room: Room) {
        rooms.remove(room.code, room)
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

    data class Occupant(val session: Session, val player: Player, var ready: Boolean = false)

    val players: List<Player> get() = occupants.values.sortedBy { it.player.index }.map { it.player }

    fun readySeats(): List<Int> = occupants.values.filter { it.ready }.map { it.player.index }.sorted()

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

    suspend fun announceJoined(seat: Int) {
        val player = mutex.withLock { occupants[seat]?.player } ?: return
        broadcastExcept(seat, ServerMessage.PlayerJoined(player))
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

    /** @return true if the room is now empty and should be evicted. */
    suspend fun leave(seat: Int): Boolean {
        val nowEmpty = mutex.withLock {
            occupants.remove(seat)
            occupants.isEmpty()
        }
        broadcast(ServerMessage.PlayerLeft(seat))
        return nowEmpty
    }

    suspend fun chat(fromSeat: Int, text: String) {
        broadcast(ServerMessage.Chat(fromSeat, text.take(280)))
    }

    private suspend fun send(seat: Int, message: ServerMessage) {
        occupants[seat]?.session?.send(message)
    }

    private suspend fun broadcast(message: ServerMessage) {
        occupants.values.forEach { it.session.send(message) }
    }

    private suspend fun broadcastExcept(seat: Int, message: ServerMessage) {
        occupants.forEach { (s, occ) -> if (s != seat) occ.session.send(message) }
    }

    companion object {
        private val PALETTE = longArrayOf(
            0xFFE53935L, 0xFF1E88E5L, 0xFF43A047L, 0xFFFDD835L
        )

        private fun seatColor(seat: Int): Long = PALETTE[seat.coerceIn(0, PALETTE.lastIndex)]
    }
}
