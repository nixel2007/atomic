package dev.atomic.app

import dev.atomic.shared.engine.BotDifficulty
import dev.atomic.shared.engine.ExplosionMode

enum class GameMode { HotSeat, VsBot }

data class GameConfig(
    val mode: GameMode,
    val playerCount: Int,
    val boardWidth: Int,
    val boardHeight: Int,
    val explosionMode: ExplosionMode,
    val botDifficulty: BotDifficulty = BotDifficulty.Medium,
    /** Player seat controlled by the human in VsBot mode. */
    val humanSeat: Int = 0
)

sealed interface Screen {
    data object Menu : Screen
    data class Setup(val mode: GameMode) : Screen
    data class Game(val config: GameConfig) : Screen
    data object Online : Screen
    data object Editor : Screen
}

interface Navigator {
    fun go(next: Screen)
    fun back()
}
