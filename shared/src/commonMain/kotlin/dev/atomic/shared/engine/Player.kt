package dev.atomic.shared.engine

import kotlinx.serialization.Serializable

@Serializable
enum class PlayerKind { Human, Bot }

@Serializable
data class Player(
    val index: Int,
    val name: String,
    /** Packed ARGB colour, stored as Long so it survives JSON round-trips. */
    val color: Long,
    val kind: PlayerKind = PlayerKind.Human,
    val difficulty: BotDifficulty? = null,
    val active: Boolean = true,
    val hasPlayed: Boolean = false
) {
    init {
        require(index in 0..3) { "only 2-4 players are supported" }
        if (kind == PlayerKind.Bot) require(difficulty != null) { "bot player needs difficulty" }
    }
}

@Serializable
enum class BotDifficulty { Easy, Medium, Hard }
