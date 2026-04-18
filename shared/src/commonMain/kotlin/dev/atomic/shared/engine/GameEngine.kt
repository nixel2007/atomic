package dev.atomic.shared.engine

import dev.atomic.shared.model.Direction
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos

object GameEngine {

    fun isLegalMove(state: GameState, p: Pos): Boolean {
        if (state.isOver) return false
        if (!state.level.isPlayable(p)) return false
        val owner = state.board.ownerAt(p)
        return owner == Board.NO_OWNER || owner == state.currentPlayerIndex
    }

    fun legalMoves(state: GameState): List<Pos> {
        if (state.isOver) return emptyList()
        val result = mutableListOf<Pos>()
        for (y in 0 until state.level.height) {
            for (x in 0 until state.level.width) {
                val p = Pos(x, y)
                if (isLegalMove(state, p)) result += p
            }
        }
        return result
    }

    fun applyMove(state: GameState, p: Pos): GameState {
        require(isLegalMove(state, p)) {
            "illegal move at $p for player ${state.currentPlayerIndex}"
        }
        val level = state.level
        val w = level.width
        val h = level.height
        val player = state.currentPlayerIndex

        val owners = state.board.owners.toIntArray()
        val counts = state.board.counts.toIntArray()
        val cm = IntArray(w * h) { i -> level.criticalMass(Pos(i % w, i / w)) }

        val placedIdx = p.y * w + p.x
        owners[placedIdx] = player
        counts[placedIdx] += 1

        resolveExplosions(owners, counts, cm, level, state.settings.explosionMode)

        val newBoard = Board(w, h, owners.toList(), counts.toList())

        val updatedPlayers = state.players.toMutableList()
        updatedPlayers[player] = updatedPlayers[player].copy(hasPlayed = true)
        for (i in updatedPlayers.indices) {
            val pl = updatedPlayers[i]
            if (pl.active && pl.hasPlayed && newBoard.atomsOf(i) == 0) {
                updatedPlayers[i] = pl.copy(active = false)
            }
        }

        val actives = updatedPlayers.filter { it.active }
        val winner = if (actives.size == 1) actives[0].index else null

        val nextPlayer = if (winner != null) {
            player
        } else {
            var n = (player + 1) % updatedPlayers.size
            while (!updatedPlayers[n].active) n = (n + 1) % updatedPlayers.size
            n
        }

        return state.copy(
            players = updatedPlayers,
            board = newBoard,
            currentPlayerIndex = nextPlayer,
            turnsPlayed = state.turnsPlayed + 1,
            winner = winner
        )
    }

    private fun resolveExplosions(
        owners: IntArray,
        counts: IntArray,
        cm: IntArray,
        level: Level,
        mode: ExplosionMode
    ) {
        when (mode) {
            ExplosionMode.Wave -> resolveWave(owners, counts, cm, level)
            ExplosionMode.Recursive -> resolveRecursive(owners, counts, cm, level)
        }
    }

    private fun resolveWave(owners: IntArray, counts: IntArray, cm: IntArray, level: Level) {
        val w = level.width
        while (true) {
            val batch = mutableListOf<Int>()
            for (i in counts.indices) if (cm[i] > 0 && counts[i] >= cm[i]) batch += i
            if (batch.isEmpty()) return
            val sources = IntArray(batch.size) { owners[batch[it]] }
            for (i in batch) {
                counts[i] -= cm[i]
                if (counts[i] == 0) owners[i] = Board.NO_OWNER
            }
            for (k in batch.indices) {
                distribute(batch[k], sources[k], owners, counts, level, w)
            }
        }
    }

    private fun resolveRecursive(
        owners: IntArray,
        counts: IntArray,
        cm: IntArray,
        level: Level
    ) {
        val w = level.width
        val queue = ArrayDeque<Int>()
        for (i in counts.indices) if (cm[i] > 0 && counts[i] >= cm[i]) queue.addLast(i)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (cm[i] == 0 || counts[i] < cm[i]) continue
            val srcOwner = owners[i]
            counts[i] -= cm[i]
            if (counts[i] == 0) owners[i] = Board.NO_OWNER
            distribute(i, srcOwner, owners, counts, level, w) { ni ->
                if (cm[ni] > 0 && counts[ni] >= cm[ni]) queue.addLast(ni)
            }
        }
    }

    private inline fun distribute(
        i: Int,
        srcOwner: Int,
        owners: IntArray,
        counts: IntArray,
        level: Level,
        w: Int,
        onReceiver: (Int) -> Unit = {}
    ) {
        val px = i % w
        val py = i / w
        for (d in Direction.entries) {
            val nx = px + d.dx
            val ny = py + d.dy
            val np = Pos(nx, ny)
            if (!level.isPlayable(np)) continue
            val ni = ny * w + nx
            counts[ni] += 1
            owners[ni] = srcOwner
            onReceiver(ni)
        }
    }
}
