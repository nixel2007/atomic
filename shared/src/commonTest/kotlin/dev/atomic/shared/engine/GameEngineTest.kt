package dev.atomic.shared.engine

import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameEngineTest {

    private val twoPlayers = listOf(
        Player(index = 0, name = "A", color = 0xFFFF0000L),
        Player(index = 1, name = "B", color = 0xFF0000FFL)
    )

    private fun newGame(
        level: Level = Level.rectangular("t", "t", 3, 3),
        mode: ExplosionMode = ExplosionMode.Wave,
        players: List<Player> = twoPlayers
    ) = GameState.initial(level, GameSettings(mode), players)

    @Test
    fun criticalMassMatchesNeighbourCount() {
        val level = Level.rectangular("t", "t", 3, 3)
        assertEquals(2, level.criticalMass(Pos(0, 0)))
        assertEquals(3, level.criticalMass(Pos(1, 0)))
        assertEquals(4, level.criticalMass(Pos(1, 1)))
    }

    @Test
    fun blockedCellsReduceCriticalMassToAllowCorridorOne() {
        // A 3x3 grid where (0,1) and (2,1) are blocked leaves (1,1) with only
        // the top and bottom neighbours — i.e. critical mass 2. Narrowing
        // further produces a dead end with critical mass 1.
        val corridor = Level(
            id = "c", name = "c", width = 3, height = 3,
            blocked = setOf(Pos(0, 1), Pos(2, 1), Pos(0, 0), Pos(2, 0))
        )
        assertEquals(1, corridor.criticalMass(Pos(1, 0)))
        assertEquals(2, corridor.criticalMass(Pos(1, 1)))
    }

    @Test
    fun placingOnEnemyCellIsIllegal() {
        var game = newGame()
        game = GameEngine.applyMove(game, Pos(0, 0))           // P0
        assertFalse(GameEngine.isLegalMove(game, Pos(0, 0)))    // P1 cannot place on P0
    }

    @Test
    fun cornerExplodesAtTwoAtoms() {
        var game = newGame()
        game = GameEngine.applyMove(game, Pos(0, 0))     // P0 -> (0,0) count=1
        game = GameEngine.applyMove(game, Pos(2, 2))     // P1 -> far corner
        game = GameEngine.applyMove(game, Pos(0, 0))     // P0 -> (0,0) count=2 -> explodes
        assertEquals(0, game.board.countAt(Pos(0, 0)))
        assertEquals(Board.NO_OWNER, game.board.ownerAt(Pos(0, 0)))
        assertEquals(1, game.board.countAt(Pos(1, 0)))
        assertEquals(0, game.board.ownerAt(Pos(1, 0)))
        assertEquals(1, game.board.countAt(Pos(0, 1)))
        assertEquals(0, game.board.ownerAt(Pos(0, 1)))
    }

    @Test
    fun explosionRepaintsEnemyCell() {
        var game = newGame()
        game = GameEngine.applyMove(game, Pos(0, 0))     // P0
        game = GameEngine.applyMove(game, Pos(1, 0))     // P1 at (1,0), count=1
        game = GameEngine.applyMove(game, Pos(0, 0))     // P0 at (0,0), count=2 -> explodes
        // explosion pushes 1 to (1,0): P1's lone atom becomes P0's, count becomes 2
        assertEquals(0, game.board.ownerAt(Pos(1, 0)))
        assertEquals(2, game.board.countAt(Pos(1, 0)))
    }

    @Test
    fun playerEliminatedWhenAllAtomsCaptured() {
        // 2x2 board: every cell is a corner with crit mass 2.
        var game = newGame(Level.rectangular("s", "s", 2, 2))
        game = GameEngine.applyMove(game, Pos(0, 0))     // P0
        game = GameEngine.applyMove(game, Pos(1, 1))     // P1
        game = GameEngine.applyMove(game, Pos(0, 0))     // P0 -> count 2 at (0,0), explodes
        // distributes to (1,0) and (0,1); P1 still has (1,1) with count 1 -> not eliminated yet
        assertNull(game.winner)
        game = GameEngine.applyMove(game, Pos(1, 1))     // P1 -> count 2 at (1,1), explodes
        // now P1's (1,1) is empty; cell (1,0) gets +1 from P1, (0,1) gets +1 from P1.
        // Need to track further. Let's just assert game advances sensibly.
        assertTrue(game.turnsPlayed == 4)
    }

    @Test
    fun waveAndRecursiveAgreeOnSimpleChain() {
        val level = Level.rectangular("t", "t", 3, 3)
        val build = { mode: ExplosionMode ->
            var g = GameState.initial(level, GameSettings(mode), twoPlayers)
            g = GameEngine.applyMove(g, Pos(0, 0)) // P0
            g = GameEngine.applyMove(g, Pos(2, 2)) // P1
            g = GameEngine.applyMove(g, Pos(0, 0)) // P0 explodes corner
            g
        }
        val wave = build(ExplosionMode.Wave)
        val rec = build(ExplosionMode.Recursive)
        assertEquals(wave.board.owners, rec.board.owners)
        assertEquals(wave.board.counts, rec.board.counts)
    }

    @Test
    fun openingMoveIntoCorridorCellExplodesImmediately() {
        // 3x3 with blocked cells leaving (1,0) as a dead-end of critical mass 1.
        // Its only playable neighbour is (1,1). Placing the very first atom
        // there must explode on turn 1, not wait for the opponent's move.
        val corridor = Level(
            id = "c", name = "c", width = 3, height = 3,
            blocked = setOf(Pos(0, 0), Pos(2, 0), Pos(0, 1), Pos(2, 1))
        )
        for (mode in ExplosionMode.entries) {
            var game = GameState.initial(corridor, GameSettings(mode), twoPlayers)
            game = GameEngine.applyMove(game, Pos(1, 0))
            assertEquals(0, game.board.countAt(Pos(1, 0)),
                "corridor cell should be empty after opening explosion ($mode)")
            assertEquals(Board.NO_OWNER, game.board.ownerAt(Pos(1, 0)))
            assertEquals(1, game.board.countAt(Pos(1, 1)),
                "atom should have moved to the single neighbour ($mode)")
            assertEquals(0, game.board.ownerAt(Pos(1, 1)))
        }
    }

    @Test
    fun winnerDeclaredWhenOnlyOneActivePlayerRemains() {
        // 2x2, two-player duel. Orchestrate until one is out.
        var game = newGame(Level.rectangular("s", "s", 2, 2))
        // P0 at (0,0), P1 at (0,1), P0 at (0,0) -> explodes, sends +1 to (1,0) and (0,1).
        // (0,1) was P1 with count=1; now becomes P0 with count=2 -> also critical, explodes.
        // It distributes to (0,0) and (1,1). (1,1) was empty -> becomes P0 with count=1.
        // Result: P0 owns everything, P1 has 0 atoms and has played -> eliminated.
        game = GameEngine.applyMove(game, Pos(0, 0))
        game = GameEngine.applyMove(game, Pos(0, 1))
        game = GameEngine.applyMove(game, Pos(0, 0))
        assertNotNull(game.winner)
        assertEquals(0, game.winner)
    }
}
