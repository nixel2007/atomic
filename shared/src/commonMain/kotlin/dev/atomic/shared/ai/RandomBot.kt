package dev.atomic.shared.ai

import dev.atomic.shared.engine.GameEngine
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.model.Pos
import kotlin.random.Random

internal class RandomBot(private val rnd: Random) : Bot {
    override fun chooseMove(state: GameState): Pos {
        val moves = GameEngine.legalMoves(state)
        requireLegalMoves(state, moves)
        return moves[rnd.nextInt(moves.size)]
    }
}
