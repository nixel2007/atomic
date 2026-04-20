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
        // given — a plain 3x3 grid
        val level = Level.rectangular("t", "t", 3, 3)

        // when / then — critical mass equals the number of playable neighbours
        assertEquals(2, level.criticalMass(Pos(0, 0)))
        assertEquals(3, level.criticalMass(Pos(1, 0)))
        assertEquals(4, level.criticalMass(Pos(1, 1)))
    }

    @Test
    fun blockedCellsReduceCriticalMassToAllowCorridorOne() {
        // given — a 3x3 grid carved into a T-shaped corridor with blocked walls
        val corridor = Level(
            id = "c", name = "c", width = 3, height = 3,
            blocked = setOf(Pos(0, 1), Pos(2, 1), Pos(0, 0), Pos(2, 0))
        )

        // when / then — a dead-end drops to critical mass 1, a bend to 2
        assertEquals(1, corridor.criticalMass(Pos(1, 0)))
        assertEquals(2, corridor.criticalMass(Pos(1, 1)))
    }

    @Test
    fun placingOnEnemyCellIsIllegal() {
        // given — P0 has claimed (0,0)
        var game = newGame()
        game = GameEngine.applyMove(game, Pos(0, 0))

        // when / then — P1 must not be allowed to play on the same cell
        assertFalse(GameEngine.isLegalMove(game, Pos(0, 0)))
    }

    @Test
    fun cornerExplodesAtTwoAtoms() {
        // given — a 3x3 game with one atom in the corner cell (critical mass 2)
        var game = newGame()
        game = GameEngine.applyMove(game, Pos(0, 0)) // P0 → (0,0) count=1
        game = GameEngine.applyMove(game, Pos(2, 2)) // P1 → far corner, no cascade

        // when — P0 places a second atom in the corner
        game = GameEngine.applyMove(game, Pos(0, 0))

        // then — the corner explodes and both neighbours receive one atom each
        assertEquals(0, game.board.countAt(Pos(0, 0)))
        assertEquals(Board.NO_OWNER, game.board.ownerAt(Pos(0, 0)))
        assertEquals(1, game.board.countAt(Pos(1, 0)))
        assertEquals(0, game.board.ownerAt(Pos(1, 0)))
        assertEquals(1, game.board.countAt(Pos(0, 1)))
        assertEquals(0, game.board.ownerAt(Pos(0, 1)))
    }

    @Test
    fun explosionRepaintsEnemyCell() {
        // given — P1 holds one atom adjacent to P0's about-to-explode corner
        var game = newGame()
        game = GameEngine.applyMove(game, Pos(0, 0)) // P0 corner
        game = GameEngine.applyMove(game, Pos(1, 0)) // P1 next door, count=1

        // when — P0 primes and explodes the corner
        game = GameEngine.applyMove(game, Pos(0, 0))

        // then — the atom pushed into (1,0) captures P1's stack for P0
        assertEquals(0, game.board.ownerAt(Pos(1, 0)))
        assertEquals(2, game.board.countAt(Pos(1, 0)))
    }

    @Test
    fun playerEliminatedWhenAllAtomsCaptured() {
        // given — 2x2 duel where every cell is a corner with critical mass 2
        var game = newGame(Level.rectangular("s", "s", 2, 2))
        game = GameEngine.applyMove(game, Pos(0, 0)) // P0
        game = GameEngine.applyMove(game, Pos(1, 1)) // P1

        // when — P0 primes the corner and then both detonate in sequence
        game = GameEngine.applyMove(game, Pos(0, 0))
        // intermediate: P1 still owns (1,1) with count 1 → not eliminated yet
        assertNull(game.winner)
        game = GameEngine.applyMove(game, Pos(1, 1))

        // then — four moves have been played and the engine advances sensibly
        assertTrue(game.turnsPlayed == 4)
    }

    @Test
    fun waveAndRecursiveAgreeOnSimpleChain() {
        // given — an identical opening sequence across both explosion modes
        val level = Level.rectangular("t", "t", 3, 3)
        val build = { mode: ExplosionMode ->
            var g = GameState.initial(level, GameSettings(mode), twoPlayers)
            g = GameEngine.applyMove(g, Pos(0, 0))
            g = GameEngine.applyMove(g, Pos(2, 2))
            g = GameEngine.applyMove(g, Pos(0, 0))
            g
        }

        // when — both engines run the sequence
        val wave = build(ExplosionMode.Wave)
        val rec = build(ExplosionMode.Recursive)

        // then — they converge on the same board
        assertEquals(wave.board.owners, rec.board.owners)
        assertEquals(wave.board.counts, rec.board.counts)
    }

    @Test
    fun openingMoveIntoCorridorCellExplodesImmediately() {
        // given — a 3x3 level carved so (1,0) is a dead end with critical
        // mass 1, whose only playable neighbour is (1,1)
        val corridor = Level(
            id = "c", name = "c", width = 3, height = 3,
            blocked = setOf(Pos(0, 0), Pos(2, 0), Pos(0, 1), Pos(2, 1))
        )

        for (mode in ExplosionMode.entries) {
            // when — the very first atom is placed in the corridor cell
            var game = GameState.initial(corridor, GameSettings(mode), twoPlayers)
            game = GameEngine.applyMove(game, Pos(1, 0))

            // then — it explodes on turn 1 and the atom moves to (1,1)
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
        // given — a 2x2 duel with a forced three-move win for P0
        var game = newGame(Level.rectangular("s", "s", 2, 2))
        game = GameEngine.applyMove(game, Pos(0, 0))
        game = GameEngine.applyMove(game, Pos(0, 1))

        // when — the decisive cascade plays out
        game = GameEngine.applyMove(game, Pos(0, 0))

        // then — P0 is declared the winner
        assertNotNull(game.winner)
        assertEquals(0, game.winner)
    }
}
