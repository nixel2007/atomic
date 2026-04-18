package dev.atomic.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class Pos(val x: Int, val y: Int)

@Serializable
enum class Direction(val dx: Int, val dy: Int) {
    Up(0, -1),
    Down(0, 1),
    Left(-1, 0),
    Right(1, 0)
}
