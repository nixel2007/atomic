package dev.atomic.shared.ai

import dev.atomic.shared.engine.Board
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.model.Direction
import dev.atomic.shared.model.Pos

internal object Heuristic {

    private const val WIN_SCORE = 1_000_000
    private const val LOSS_SCORE = -1_000_000

    /**
     * Score the position from [me]'s point of view. Higher is better.
     * Combines material (atoms owned), threats (near-critical own cells next
     * to opponents) and liabilities (own cells next to opponents that are one
     * atom away from exploding towards us).
     */
    fun evaluate(state: GameState, me: Int): Int {
        state.winner?.let { return if (it == me) WIN_SCORE else LOSS_SCORE }

        val board = state.board
        val level = state.level
        val w = level.width
        val h = level.height

        var myAtoms = 0
        var oppAtoms = 0
        var threat = 0
        var liability = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = Pos(x, y)
                val owner = board.ownerAt(p)
                if (owner == Board.NO_OWNER) continue
                val count = board.countAt(p)
                val cm = level.criticalMass(p)
                if (owner == me) {
                    myAtoms += count
                    if (count == cm - 1) threat += bonusNearEnemy(board, level, p, me)
                    liability += vulnerabilityAround(board, level, p, me)
                } else {
                    oppAtoms += count
                }
            }
        }
        return (myAtoms - oppAtoms) * 5 + threat * 2 - liability * 4
    }

    private fun bonusNearEnemy(board: Board, level: dev.atomic.shared.model.Level, p: Pos, me: Int): Int {
        var b = 0
        for (d in Direction.entries) {
            val np = Pos(p.x + d.dx, p.y + d.dy)
            if (!level.isPlayable(np)) continue
            val o = board.ownerAt(np)
            if (o != Board.NO_OWNER && o != me) b += board.countAt(np)
        }
        return b
    }

    /**
     * For each enemy cell adjacent to [p] that is one atom away from going
     * critical, this cell will be captured on the enemy's next turn.
     */
    private fun vulnerabilityAround(board: Board, level: dev.atomic.shared.model.Level, p: Pos, me: Int): Int {
        var v = 0
        for (d in Direction.entries) {
            val np = Pos(p.x + d.dx, p.y + d.dy)
            if (!level.isPlayable(np)) continue
            val o = board.ownerAt(np)
            if (o == Board.NO_OWNER || o == me) continue
            if (board.countAt(np) == level.criticalMass(np) - 1) v += board.countAt(p)
        }
        return v
    }
}
