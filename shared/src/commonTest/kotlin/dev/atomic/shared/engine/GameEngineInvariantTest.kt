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

    /**
     * Applies [move] to [state] and asserts every post-move invariant before
     * returning the new state. Used as the step function of the fuzzer so a
     * failure pins down the exact transition that broke an invariant.
     */
    private fun playAndAssertInvariants(state: GameState, move: Pos): GameState {
        // given
        assertTrue(GameEngine.isLegalMove(state, move), "move $move illegal")
        val before = state

        // when
        val after = GameEngine.applyMove(state, move)

        // then — atoms grow by exactly one, cascades only redistribute
        assertEquals(totalAtoms(before) + 1, totalAtoms(after),
            "atom count must grow by exactly 1 per move")

        // then — turn counter monotonically increases
        assertEquals(before.turnsPlayed + 1, after.turnsPlayed)

        // then — every non-empty cell is owned by an active player
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

        // then — if the game is over, exactly one active player remains
        if (after.winner != null) {
            assertEquals(1, after.players.count { it.active })
            assertEquals(after.winner, after.players.single { it.active }.index)
        }

        // then — if the game is not over, the next mover is active
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
            state = playAndAssertInvariants(state, pick)
            guard++
        }
        return state
    }

    @Test
    fun invariantsHoldAcrossRandomGames_wave() {
        // given — a fresh two-player game on each level
        // when  — 50 seeded random playouts in Wave mode
        // then  — every step satisfies the invariants enforced by
        //         playAndAssertInvariants
        for (seed in 0L until 50L) {
            for (level in levels) {
                randomLegalPlayout(seed, level, ExplosionMode.Wave)
            }
        }
    }

    @Test
    fun invariantsHoldAcrossRandomGames_recursive() {
        // given — a fresh two-player game on each level
        // when  — 50 seeded random playouts in Recursive mode
        // then  — every step satisfies the invariants
        for (seed in 0L until 50L) {
            for (level in levels) {
                randomLegalPlayout(seed, level, ExplosionMode.Recursive)
            }
        }
    }

    @Test
    fun waveAndRecursiveAgreeAcrossRandomGames() {
        for (seed in 0L until 30L) {
            for (level in levels) {
                // given — paired states driven by the same move sequence
                val rng = Random(seed)
                var sWave = GameState.initial(level, GameSettings(ExplosionMode.Wave), players)
                var sRec = GameState.initial(level, GameSettings(ExplosionMode.Recursive), players)

                // when — play identical legal moves on both engines
                var guard = 0
                while (!sWave.isOver && !sRec.isOver && guard < 100) {
                    val moves = GameEngine.legalMoves(sWave)
                    if (moves.isEmpty()) break
                    val pick = moves[rng.nextInt(moves.size)]
                    sWave = GameEngine.applyMove(sWave, pick)
                    sRec = GameEngine.applyMove(sRec, pick)

                    // then — Wave and Recursive converge on the same board
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
        // given — a primed corner so the next move triggers a cascade
        var g = GameState.initial(
            Level.rectangular("r", "r", 3, 3),
            GameSettings(ExplosionMode.Wave),
            players
        )
        g = GameEngine.applyMove(g, Pos(0, 0))
        g = GameEngine.applyMove(g, Pos(2, 2))

        // when — the same cascading move is applied plain and traced
        val plain = GameEngine.applyMove(g, Pos(0, 0))
        val traced = GameEngine.applyMoveTraced(g, Pos(0, 0))

        // then — finalState matches applyMove and the trace is populated
        assertEquals(plain, traced.finalState)
        assertTrue(traced.waves.isNotEmpty(), "cascading move should emit wave snapshots")
    }

    @Test
    fun applyMoveIsDeterministic() {
        // given — identical seed + level + mode on two independent playouts
        val level = Level.rectangular("r", "r", 4, 3)

        // when — both playouts run to completion
        val a = randomLegalPlayout(seed = 12345L, level = level, mode = ExplosionMode.Wave)
        val b = randomLegalPlayout(seed = 12345L, level = level, mode = ExplosionMode.Wave)

        // then — the resulting states match byte-for-byte
        assertEquals(a.board.owners, b.board.owners)
        assertEquals(a.board.counts, b.board.counts)
        assertEquals(a.turnsPlayed, b.turnsPlayed)
        assertEquals(a.winner, b.winner)
    }

    @Test
    fun illegalMoveOnOpponentCellIsRejectedByLegalMoves() {
        // given — P0 has claimed (0,0); it is now P1's turn
        var state = GameState.initial(
            Level.rectangular("r", "r", 3, 3),
            GameSettings(),
            players
        )
        state = GameEngine.applyMove(state, Pos(0, 0))

        // when — we enumerate the legal moves for P1
        val moves = GameEngine.legalMoves(state)

        // then — (0,0) is not among them
        assertTrue(Pos(0, 0) !in moves, "P1 must not be allowed to place on P0's cell")
    }

    @Test
    fun legalMovesEmptyAfterWin() {
        // given — a forced win on a 2x2 board
        var state = GameState.initial(
            Level.rectangular("s", "s", 2, 2),
            GameSettings(),
            players
        )
        state = GameEngine.applyMove(state, Pos(0, 0))
        state = GameEngine.applyMove(state, Pos(0, 1))

        // when — the move that decides the match is played
        state = GameEngine.applyMove(state, Pos(0, 0))

        // then — game is over and no further legal moves exist
        assertNotNull(state.winner)
        assertEquals(emptyList(), GameEngine.legalMoves(state))
    }

    @Test
    fun firstPlayerCannotLoseBeforeSecondPlayerMoves() {
        // given — a pristine two-player game
        val state = GameState.initial(
            Level.rectangular("r", "r", 3, 3),
            GameSettings(),
            players
        )

        // when — only P0 has made a single move
        val after = GameEngine.applyMove(state, Pos(0, 0))

        // then — the match cannot be decided; elimination requires hasPlayed
        assertNull(after.winner, "game cannot be over after the opening move")
        assertTrue(after.players.all { it.active || !it.hasPlayed })
    }
}
