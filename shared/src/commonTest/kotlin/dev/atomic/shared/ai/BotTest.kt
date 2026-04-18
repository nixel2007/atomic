package dev.atomic.shared.ai

import dev.atomic.shared.engine.BotDifficulty
import dev.atomic.shared.engine.GameEngine
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.engine.PlayerKind
import dev.atomic.shared.model.Level
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotTest {

    private val players = listOf(
        Player(0, "A", 0xFFFF0000L),
        Player(1, "B", 0xFF0000FFL, kind = PlayerKind.Bot, difficulty = BotDifficulty.Medium)
    )

    @Test
    fun allBotsReturnLegalMovesInOpening() {
        val level = Level.rectangular("t", "t", 5, 5)
        val game = GameState.initial(level, GameSettings(), players)
        for (d in BotDifficulty.entries) {
            val move = Bot.of(d, seed = 1).chooseMove(game)
            assertTrue(GameEngine.isLegalMove(game, move), "$d bot picked illegal move $move")
        }
    }

    @Test
    fun mediumBotFinishesGameAgainstRandom() {
        val level = Level.rectangular("t", "t", 4, 4)
        var game = GameState.initial(level, GameSettings(), players)
        val bots = mapOf(
            0 to Bot.of(BotDifficulty.Easy, seed = 42),
            1 to Bot.of(BotDifficulty.Medium, seed = 7)
        )
        var steps = 0
        while (!game.isOver && steps < 300) {
            val move = bots.getValue(game.currentPlayerIndex).chooseMove(game)
            game = GameEngine.applyMove(game, move)
            steps++
        }
        assertNotNull(game.winner, "game did not converge to a winner within $steps steps")
    }
}
