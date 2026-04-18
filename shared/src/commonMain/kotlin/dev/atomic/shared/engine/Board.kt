package dev.atomic.shared.engine

import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import kotlinx.serialization.Serializable

/**
 * Parallel arrays of cell owner and atom count, sized [width]*[height].
 * [owners] holds the player index that owns the cell, or [NO_OWNER] if empty.
 */
@Serializable
data class Board(
    val width: Int,
    val height: Int,
    val owners: List<Int>,
    val counts: List<Int>
) {
    init {
        require(owners.size == width * height)
        require(counts.size == width * height)
    }

    fun index(p: Pos): Int = p.y * width + p.x
    fun ownerAt(p: Pos): Int = owners[index(p)]
    fun countAt(p: Pos): Int = counts[index(p)]

    fun atomsOf(player: Int): Int {
        var sum = 0
        for (i in owners.indices) if (owners[i] == player) sum += counts[i]
        return sum
    }

    companion object {
        const val NO_OWNER = -1

        fun empty(level: Level): Board {
            val n = level.width * level.height
            return Board(
                width = level.width,
                height = level.height,
                owners = List(n) { NO_OWNER },
                counts = List(n) { 0 }
            )
        }
    }
}
