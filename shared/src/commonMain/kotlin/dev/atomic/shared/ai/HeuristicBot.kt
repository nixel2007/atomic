package dev.atomic.shared.ai

import dev.atomic.shared.engine.GameEngine
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.model.Pos
import kotlin.random.Random

internal class HeuristicBot(private val rnd: Random) : Bot {
    override fun chooseMove(state: GameState): Pos {
        val moves = GameEngine.legalMoves(state)
        requireLegalMoves(state, moves)
        val me = state.currentPlayerIndex
        var bestScore = Int.MIN_VALUE
        val bestMoves = mutableListOf<Pos>()
        for (m in moves) {
            val next = GameEngine.applyMove(state, m)
            val score = Heuristic.evaluate(next, me)
            if (score > bestScore) {
                bestScore = score
                bestMoves.clear()
                bestMoves += m
            } else if (score == bestScore) {
                bestMoves += m
            }
        }
        return bestMoves[rnd.nextInt(bestMoves.size)]
    }
}
