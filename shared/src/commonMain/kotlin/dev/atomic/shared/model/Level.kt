package dev.atomic.shared.model

import kotlinx.serialization.Serializable

/**
 * A Chain Reaction board. [blocked] cells cannot hold atoms and do not count
 * as neighbours for critical-mass purposes, which is what enables "corridor"
 * cells with critical mass 1.
 */
@Serializable
data class Level(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val blocked: Set<Pos> = emptySet()
) {
    init {
        require(width in MIN_SIDE..MAX_SIDE) { "width must be in $MIN_SIDE..$MAX_SIDE" }
        require(height in MIN_SIDE..MAX_SIDE) { "height must be in $MIN_SIDE..$MAX_SIDE" }
        require(blocked.all { inBounds(it) }) { "blocked cells must be inside the grid" }
    }

    fun inBounds(p: Pos): Boolean = p.x in 0 until width && p.y in 0 until height

    fun isPlayable(p: Pos): Boolean = inBounds(p) && p !in blocked

    /** Number of in-bounds, non-blocked orthogonal neighbours. */
    fun criticalMass(p: Pos): Int {
        if (!isPlayable(p)) return 0
        var c = 0
        for (d in Direction.entries) {
            val np = Pos(p.x + d.dx, p.y + d.dy)
            if (isPlayable(np)) c++
        }
        return c
    }

    companion object {
        const val MIN_SIDE = 2
        const val MAX_SIDE = 20

        fun rectangular(id: String, name: String, width: Int, height: Int): Level =
            Level(id = id, name = name, width = width, height = height)
    }
}
