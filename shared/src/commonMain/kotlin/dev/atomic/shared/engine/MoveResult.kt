package dev.atomic.shared.engine

/**
 * Result of an animated move. [frames] contains per-wave board snapshots:
 * the first frame is the board right after placement (pre-cascade) and the
 * last equals [finalState.board]. When there is no cascade there is exactly
 * one frame.
 */
data class MoveResult(
    val frames: List<Board>,
    val finalState: GameState
)
