package dev.atomic.shared.net

import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import kotlinx.serialization.Serializable

/**
 * Messages sent from a client to the relay server. Each client maintains a
 * single WebSocket connection and identifies itself via [nickname] on join.
 */
@Serializable
sealed interface ClientMessage {

    @Serializable
    data class CreateRoom(
        val level: Level,
        val settings: GameSettings,
        val seats: Int,
        val nickname: String
    ) : ClientMessage

    @Serializable
    data class JoinRoom(
        val code: String,
        val nickname: String
    ) : ClientMessage

    @Serializable
    data object SetReady : ClientMessage

    @Serializable
    data object CancelReady : ClientMessage

    @Serializable
    data class MakeMove(val pos: Pos) : ClientMessage

    @Serializable
    data object LeaveRoom : ClientMessage

    @Serializable
    data class Chat(val text: String) : ClientMessage
}

/**
 * Messages sent from the relay server to a client. The server is intentionally
 * dumb — it broadcasts the full [GameState] after every move so clients never
 * need to replay history to synchronise.
 */
@Serializable
sealed interface ServerMessage {

    @Serializable
    data class RoomCreated(
        val code: String,
        val seat: Int,
        val players: List<Player>,
        val maxSeats: Int,
        val readySeats: List<Int> = emptyList()
    ) : ServerMessage

    @Serializable
    data class RoomJoined(
        val code: String,
        val seat: Int,
        val players: List<Player>,
        val maxSeats: Int,
        val readySeats: List<Int> = emptyList()
    ) : ServerMessage

    @Serializable
    data class PlayerJoined(val player: Player) : ServerMessage

    @Serializable
    data class PlayerLeft(val seat: Int) : ServerMessage

    @Serializable
    data class PlayerDisconnected(val seat: Int, val graceSeconds: Int) : ServerMessage

    @Serializable
    data class PlayerRejoined(val seat: Int) : ServerMessage

    @Serializable
    data class PlayerReady(val seat: Int) : ServerMessage

    @Serializable
    data class PlayerNotReady(val seat: Int) : ServerMessage

    @Serializable
    data class GameStarted(val state: GameState) : ServerMessage

    @Serializable
    data class GameUpdated(val state: GameState, val lastMove: Pos, val bySeat: Int) : ServerMessage

    @Serializable
    data class GameOver(val state: GameState, val winnerSeat: Int) : ServerMessage

    @Serializable
    data class Chat(val fromSeat: Int, val text: String) : ServerMessage

    @Serializable
    data class ErrorMessage(val code: ErrorCode, val message: String) : ServerMessage
}

@Serializable
enum class ErrorCode {
    RoomNotFound,
    RoomFull,
    InvalidMove,
    NotYourTurn,
    BadRequest,
    InternalError
}
