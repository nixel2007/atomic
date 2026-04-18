package dev.atomic.shared.engine

import dev.atomic.shared.model.Level
import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val level: Level,
    val settings: GameSettings,
    val players: List<Player>,
    val board: Board,
    val currentPlayerIndex: Int,
    val turnsPlayed: Int,
    val winner: Int? = null
) {
    val currentPlayer: Player get() = players[currentPlayerIndex]
    val isOver: Boolean get() = winner != null

    companion object {
        fun initial(level: Level, settings: GameSettings, players: List<Player>): GameState {
            require(players.size in 2..4) { "2 to 4 players required" }
            require(players.map { it.index } == players.indices.toList()) {
                "player indices must be 0..${players.size - 1} in order"
            }
            return GameState(
                level = level,
                settings = settings,
                players = players,
                board = Board.empty(level),
                currentPlayerIndex = 0,
                turnsPlayed = 0
            )
        }
    }
}
