package dev.atomic.shared.engine

/**
 * Output of [GameEngine.applyMoveTraced]. [waves] is the sequence of board
 * snapshots taken between cascade waves: the first entry is the board right
 * after placement (before any explosion), the last entry equals
 * [finalState]'s board, and each intermediate entry is the state after one
 * explosion wave. When the move causes no cascade there is exactly one entry.
 */
data class MoveResult(
    val waves: List<Board>,
    val finalState: GameState
)
