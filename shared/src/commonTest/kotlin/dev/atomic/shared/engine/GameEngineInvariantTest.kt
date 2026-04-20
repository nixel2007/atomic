package dev.atomic.shared.engine

import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Invariants that must hold across any sequence of legal moves. The fuzzer
 * plays thousands of random games; a single assertion failure pins down
 * which transition violated the invariant, which is cheaper than building
 * dedicated fixtures for every edge case.
 */
class GameEngineInvariantTest {

    private val players = listOf(
        Player(index = 0, name = "A", color = 0xFFFF0000L),
        Player(index = 1, name = "B", color = 0xFF0000FFL)
    )

    private val levels = listOf(
        Level.rectangular("r2x2", "2x2", 2, 2),
        Level.rectangular("r3x3", "3x3", 3, 3),
        Level.rectangular("r4x3", "4x3", 4, 3),
        Level(
            id = "corridor", name = "corridor", width = 3, height = 3,
            blocked = setOf(Pos(0, 0), Pos(2, 0), Pos(0, 1), Pos(2, 1))
        )
    )

    private fun totalAtoms(s: GameState): Int = s.board.counts.sum()

    private fun play(state: GameState, move: Pos): GameState {
        assertTrue(GameEngine.isLegalMove(state, move), "move $move illegal")
        val before = state
        val after = GameEngine.applyMove(state, move)

        // Invariant 1: the board only ever grows by +1 atom per move
        // (cascades distribute but don't create atoms).
        assertEquals(totalAtoms(before) + 1, totalAtoms(after),
            "atom count must grow by exactly 1 per move")

        // Invariant 2: turnsPlayed monotonically increases.
        assertEquals(before.turnsPlayed + 1, after.turnsPlayed)

        // Invariant 3: every non-empty cell is owned by an active player.
        for (i in after.board.counts.indices) {
            if (after.board.counts[i] > 0) {
                val owner = after.board.owners[i]
                assertTrue(owner in after.players.indices, "cell $i has bogus owner $owner")
                assertTrue(after.players[owner].active,
                    "cell $i owned by eliminated player $owner")
            } else {
                assertEquals(Board.NO_OWNER, after.board.owners[i],
                    "empty cell $i must have NO_OWNER")
            }
        }

        // Invariant 4: if the game is over, exactly one player is still active.
        if (after.winner != null) {
            assertEquals(1, after.players.count { it.active })
            assertEquals(after.winner, after.players.single { it.active }.index)
        }

        // Invariant 5: if the game is not over, the next player to move is active.
        if (!after.isOver) {
            assertTrue(after.currentPlayer.active, "next player must be active")
        }

        return after
    }

    private fun randomLegalPlayout(seed: Long, level: Level, mode: ExplosionMode): GameState {
        val rng = Random(seed)
        var state = GameState.initial(level, GameSettings(mode), players)
        var guard = 0
        while (!state.isOver && guard < 200) {
            val moves = GameEngine.legalMoves(state)
            if (moves.isEmpty()) break
            val pick = moves[rng.nextInt(moves.size)]
            state = play(state, pick)
            guard++
        }
        return state
    }

    @Test
    fun invariantsHoldAcrossRandomGames_wave() {
        for (seed in 0L until 50L) {
            for (level in levels) {
                randomLegalPlayout(seed, level, ExplosionMode.Wave)
            }
        }
    }

    @Test
    fun invariantsHoldAcrossRandomGames_recursive() {
        for (seed in 0L until 50L) {
            for (level in levels) {
                randomLegalPlayout(seed, level, ExplosionMode.Recursive)
            }
        }
    }

    @Test
    fun waveAndRecursiveAgreeAcrossRandomGames() {
        // Pair playouts: same seed, same level, same move sequence driven by
        // legalMoves ordering. Wave vs Recursive should converge on the same
        // board after every move.
        for (seed in 0L until 30L) {
            for (level in levels) {
                val rng = Random(seed)
                var sWave = GameState.initial(level, GameSettings(ExplosionMode.Wave), players)
                var sRec = GameState.initial(level, GameSettings(ExplosionMode.Recursive), players)
                var guard = 0
                while (!sWave.isOver && !sRec.isOver && guard < 100) {
                    val moves = GameEngine.legalMoves(sWave)
                    if (moves.isEmpty()) break
                    val pick = moves[rng.nextInt(moves.size)]
                    sWave = GameEngine.applyMove(sWave, pick)
                    sRec = GameEngine.applyMove(sRec, pick)
                    assertEquals(sWave.board.owners, sRec.board.owners,
                        "owners diverge (seed=$seed level=${level.id} turn=$guard)")
                    assertEquals(sWave.board.counts, sRec.board.counts,
                        "counts diverge (seed=$seed level=${level.id} turn=$guard)")
                    assertEquals(sWave.winner, sRec.winner)
                    guard++
                }
            }
        }
    }

    @Test
    fun applyMoveTracedFinalStateMatchesApplyMove() {
        val state = GameState.initial(
            Level.rectangular("r", "r", 3, 3),
            GameSettings(ExplosionMode.Wave),
            players
        )
        // Build a board with a primed corner so the traced move actually cascades.
        var g = state
        g = GameEngine.applyMove(g, Pos(0, 0))
        g = GameEngine.applyMove(g, Pos(2, 2))
        val plain = GameEngine.applyMove(g, Pos(0, 0))
        val traced = GameEngine.applyMoveTraced(g, Pos(0, 0))
        assertEquals(plain, traced.finalState)
        assertTrue(traced.waves.isNotEmpty(), "cascading move should emit wave snapshots")
    }

    @Test
    fun applyMoveIsDeterministic() {
        val level = Level.rectangular("r", "r", 4, 3)
        val a = randomLegalPlayout(seed = 12345L, level = level, mode = ExplosionMode.Wave)
        val b = randomLegalPlayout(seed = 12345L, level = level, mode = ExplosionMode.Wave)
        assertEquals(a.board.owners, b.board.owners)
        assertEquals(a.board.counts, b.board.counts)
        assertEquals(a.turnsPlayed, b.turnsPlayed)
        assertEquals(a.winner, b.winner)
    }

    @Test
    fun illegalMoveOnOpponentCellIsRejectedByLegalMoves() {
        var state = GameState.initial(
            Level.rectangular("r", "r", 3, 3),
            GameSettings(),
            players
        )
        state = GameEngine.applyMove(state, Pos(0, 0)) // P0 claims (0,0)
        val moves = GameEngine.legalMoves(state)
        assertTrue(Pos(0, 0) !in moves, "P1 must not be allowed to place on P0's cell")
    }

    @Test
    fun legalMovesEmptyAfterWin() {
        var state = GameState.initial(
            Level.rectangular("s", "s", 2, 2),
            GameSettings(),
            players
        )
        state = GameEngine.applyMove(state, Pos(0, 0))
        state = GameEngine.applyMove(state, Pos(0, 1))
        state = GameEngine.applyMove(state, Pos(0, 0))
        assertNotNull(state.winner)
        assertEquals(emptyList(), GameEngine.legalMoves(state))
    }

    @Test
    fun firstPlayerCannotLoseBeforeSecondPlayerMoves() {
        // Until every seat has played at least one move, no-one can be
        // eliminated — players start with 0 atoms and are only marked
        // inactive once they've played AND been reduced back to zero.
        val state = GameState.initial(
            Level.rectangular("r", "r", 3, 3),
            GameSettings(),
            players
        )
        val after = GameEngine.applyMove(state, Pos(0, 0))
        assertNull(after.winner, "game cannot be over after the opening move")
        assertTrue(after.players.all { it.active || !it.hasPlayed })
    }
}
