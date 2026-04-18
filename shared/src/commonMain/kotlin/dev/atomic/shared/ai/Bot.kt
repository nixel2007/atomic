package dev.atomic.shared.ai

import dev.atomic.shared.engine.BotDifficulty
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.model.Pos
import kotlin.random.Random

interface Bot {
    /** Pick the next move for [GameState.currentPlayerIndex]. */
    fun chooseMove(state: GameState): Pos

    companion object {
        fun of(difficulty: BotDifficulty, seed: Long = 0xC0FFEEL): Bot = when (difficulty) {
            BotDifficulty.Easy -> RandomBot(Random(seed))
            BotDifficulty.Medium -> HeuristicBot(Random(seed))
            BotDifficulty.Hard -> MinimaxBot(depth = 2, rnd = Random(seed))
        }
    }
}

internal fun requireLegalMoves(state: GameState, moves: List<Pos>) {
    require(moves.isNotEmpty()) {
        "bot cannot move: no legal moves for player ${state.currentPlayerIndex}"
    }
}
