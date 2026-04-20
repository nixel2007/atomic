package dev.atomic.app

import dev.atomic.shared.engine.BotDifficulty
import dev.atomic.shared.engine.ExplosionMode
import dev.atomic.shared.model.Level

enum class GameMode { HotSeat, VsBot }

data class GameConfig(
    val mode: GameMode,
    val playerCount: Int,
    val boardWidth: Int,
    val boardHeight: Int,
    val explosionMode: ExplosionMode,
    val botDifficulty: BotDifficulty = BotDifficulty.Medium,
    /** Player seat controlled by the human in VsBot mode. */
    val humanSeat: Int = 0,
    /** Optional custom level (from the editor). When null, a plain rectangular
     *  level of the configured size is used. */
    val level: Level? = null
)

sealed interface Screen {
    data object Menu : Screen
    data class Setup(val mode: GameMode) : Screen
    data class Game(val config: GameConfig) : Screen
    /** [customLevel] pre-fills the "create room" form when launched from the editor. */
    data class Online(val customLevel: Level? = null) : Screen
    data object Editor : Screen
    data object Help : Screen
}

interface Navigator {
    fun go(next: Screen)
    fun back()
}
