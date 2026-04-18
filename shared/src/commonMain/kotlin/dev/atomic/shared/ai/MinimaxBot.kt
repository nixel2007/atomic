package dev.atomic.shared.ai

import dev.atomic.shared.engine.GameEngine
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.model.Pos
import kotlin.random.Random

/**
 * Paranoid minimax: we assume the very next active player will play the move
 * that hurts us most, and pick our move to maximise the resulting score.
 * [depth] is measured in plies — depth=2 means "my move then opponent's reply".
 */
internal class MinimaxBot(private val depth: Int, private val rnd: Random) : Bot {

    override fun chooseMove(state: GameState): Pos {
        val me = state.currentPlayerIndex
        val moves = GameEngine.legalMoves(state)
        requireLegalMoves(state, moves)
        var bestScore = Int.MIN_VALUE
        val best = mutableListOf<Pos>()
        var alpha = Int.MIN_VALUE
        val beta = Int.MAX_VALUE
        for (m in moves) {
            val next = GameEngine.applyMove(state, m)
            val score = minimax(next, me, depth - 1, alpha, beta)
            if (score > bestScore) {
                bestScore = score
                best.clear()
                best += m
            } else if (score == bestScore) {
                best += m
            }
            if (score > alpha) alpha = score
        }
        return best[rnd.nextInt(best.size)]
    }

    private fun minimax(state: GameState, me: Int, depthLeft: Int, alpha: Int, beta: Int): Int {
        if (depthLeft == 0 || state.isOver) return Heuristic.evaluate(state, me)
        val moves = GameEngine.legalMoves(state)
        if (moves.isEmpty()) return Heuristic.evaluate(state, me)
        val maximising = state.currentPlayerIndex == me
        var a = alpha
        var b = beta
        var best = if (maximising) Int.MIN_VALUE else Int.MAX_VALUE
        for (m in moves) {
            val next = GameEngine.applyMove(state, m)
            val score = minimax(next, me, depthLeft - 1, a, b)
            if (maximising) {
                if (score > best) best = score
                if (best > a) a = best
            } else {
                if (score < best) best = score
                if (best < b) b = best
            }
            if (b <= a) break
        }
        return best
    }
}
