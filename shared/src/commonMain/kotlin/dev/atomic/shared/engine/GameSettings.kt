package dev.atomic.shared.engine

import kotlinx.serialization.Serializable

@Serializable
enum class ExplosionMode {
    /** All critical cells explode simultaneously, then we look again. */
    Wave,

    /** One critical cell explodes at a time, following a deterministic queue. */
    Recursive
}

@Serializable
data class GameSettings(
    val explosionMode: ExplosionMode = ExplosionMode.Wave
)
