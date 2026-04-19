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

    fun applyMove(state: GameState, p: Pos): GameState = apply(state, p, collectFrames = false).finalState

    /**
     * Same as [applyMove] but also returns the board snapshot after each
     * explosion wave so the UI can animate the cascade. The first frame is
     * the board right after placement (before any explosion); the last
     * frame equals [MoveResult.finalState.board].
     */
    fun applyMoveAnimated(state: GameState, p: Pos): MoveResult = apply(state, p, collectFrames = true)

    private fun apply(state: GameState, p: Pos, collectFrames: Boolean): MoveResult {
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

        val frames: MutableList<Board>? = if (collectFrames) mutableListOf() else null
        frames?.add(snapshot(w, h, owners, counts))

        resolveExplosions(owners, counts, cm, level, state.settings.explosionMode, frames)

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

        val finalState = state.copy(
            players = updatedPlayers,
            board = newBoard,
            currentPlayerIndex = nextPlayer,
            turnsPlayed = state.turnsPlayed + 1,
            winner = winner
        )
        return MoveResult(frames ?: emptyList(), finalState)
    }

    private fun snapshot(w: Int, h: Int, owners: IntArray, counts: IntArray): Board =
        Board(w, h, owners.toList(), counts.toList())

    private fun resolveExplosions(
        owners: IntArray,
        counts: IntArray,
        cm: IntArray,
        level: Level,
        mode: ExplosionMode,
        frames: MutableList<Board>?
    ) {
        when (mode) {
            ExplosionMode.Wave -> resolveWave(owners, counts, cm, level, frames)
            ExplosionMode.Recursive -> resolveRecursive(owners, counts, cm, level, frames)
        }
    }

    private fun resolveWave(
        owners: IntArray,
        counts: IntArray,
        cm: IntArray,
        level: Level,
        frames: MutableList<Board>?
    ) {
        val w = level.width
        val h = level.height
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
            frames?.add(snapshot(w, h, owners, counts))
            if (hasSingleOwner(owners, counts)) return
        }
    }

    private fun resolveRecursive(
        owners: IntArray,
        counts: IntArray,
        cm: IntArray,
        level: Level,
        frames: MutableList<Board>?
    ) {
        val w = level.width
        val h = level.height
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
            frames?.add(snapshot(w, h, owners, counts))
            if (hasSingleOwner(owners, counts)) return
        }
    }

    /**
     * Once a single player owns every non-empty cell the match is decided and
     * further cascades would only shuffle their own atoms around — with
     * supercritical boards the cascade never terminates. Checked *after* each
     * wave so the very first explosion (when only the mover has atoms) still
     * fires; bailing out earlier would swallow opening-move corridor chains.
     */
    private fun hasSingleOwner(owners: IntArray, counts: IntArray): Boolean {
        var seen = Board.NO_OWNER
        for (i in counts.indices) {
            if (counts[i] == 0) continue
            val o = owners[i]
            if (seen == Board.NO_OWNER) seen = o
            else if (o != seen) return false
        }
        return seen != Board.NO_OWNER
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
