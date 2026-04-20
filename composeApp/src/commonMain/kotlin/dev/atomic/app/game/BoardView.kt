package dev.atomic.app.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import dev.atomic.shared.engine.Board
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.model.Direction
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos

/** An atom in flight during an explosion wave, carrying its start/end cell and player colour. */
data class ExplodingAtom(val fromPos: Pos, val toPos: Pos, val color: Color)

/**
 * Computes the atoms that should fly during the transition *from* [board] to the next wave.
 * Every cell that is at or above critical mass contributes one flying atom per playable neighbour.
 */
fun computeExplodingAtoms(board: Board, level: Level, players: List<Player>): List<ExplodingAtom> {
    val atoms = mutableListOf<ExplodingAtom>()
    for (y in 0 until level.height) {
        for (x in 0 until level.width) {
            val pos = Pos(x, y)
            if (!level.isPlayable(pos)) continue
            val count = board.countAt(pos)
            val cm = level.criticalMass(pos)
            if (cm > 0 && count >= cm) {
                val owner = board.ownerAt(pos)
                val color = if (owner != Board.NO_OWNER) Color(players[owner].color.toInt()) else Color.Gray
                for (d in Direction.entries) {
                    val np = Pos(pos.x + d.dx, pos.y + d.dy)
                    if (level.isPlayable(np)) {
                        atoms += ExplodingAtom(fromPos = pos, toPos = np, color = color)
                    }
                }
            }
        }
    }
    return atoms
}

/** Duration in milliseconds for atoms to fly from source cell to destination during explosion. */
private const val EXPLOSION_ANIM_MS = 120

private val MAX_BOARD_WIDTH = 540.dp
private val LAST_MOVE_HIGHLIGHT_COLOR = Color(0xFFFFD740)
private val LAST_MOVE_BORDER_WIDTH = 2.dp
private val DEFAULT_BORDER_WIDTH = 1.dp

@Composable
fun BoardView(
    state: GameState,
    onCellTap: (Pos) -> Unit,
    modifier: Modifier = Modifier,
    lastMove: Pos? = null,
    explodingAtoms: List<ExplodingAtom> = emptyList(),
    interactive: Boolean = true
) {
    val level = state.level
    val aspect = level.width.toFloat() / level.height.toFloat()

    val explosionProgress = remember { Animatable(0f) }
    LaunchedEffect(explodingAtoms) {
        if (explodingAtoms.isNotEmpty()) {
            explosionProgress.snapTo(0f)
            explosionProgress.animateTo(
                1f,
                animationSpec = tween(durationMillis = EXPLOSION_ANIM_MS, easing = LinearEasing)
            )
        } else {
            explosionProgress.snapTo(0f)
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Fit the board into the parent's bounded dimension(s) while preserving aspect ratio.
        // Cap at 540 dp to prevent oversized cells on wide desktop / unbounded-height containers.
        val rawWidth = if (constraints.hasBoundedHeight) {
            val maxByWidth = maxWidth
            val maxByHeight = maxHeight * aspect
            if (maxByWidth <= maxByHeight) maxByWidth else maxByHeight
        } else {
            maxWidth
        }
        val boardWidth = rawWidth.coerceAtMost(MAX_BOARD_WIDTH)

        Box(
            modifier = Modifier.width(boardWidth).aspectRatio(aspect),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(4.dp)
            ) {
                for (y in 0 until level.height) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        for (x in 0 until level.width) {
                            val p = Pos(x, y)
                            Cell(
                                state = state,
                                pos = p,
                                onTap = { onCellTap(p) },
                                isLastMove = p == lastMove,
                                interactive = interactive,
                                modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                            )
                        }
                    }
                }
            }

            // Overlay: flying atoms during explosion animation
            if (explodingAtoms.isNotEmpty()) {
                val progress = explosionProgress.value
                Canvas(modifier = Modifier.matchParentSize()) {
                    val boardPad = 4.dp.toPx()
                    val cellW = (size.width - 2 * boardPad) / level.width
                    val cellH = (size.height - 2 * boardPad) / level.height
                    val r = minOf(cellW, cellH) * 0.16f
                    for (atom in explodingAtoms) {
                        val fromX = boardPad + atom.fromPos.x * cellW + cellW / 2
                        val fromY = boardPad + atom.fromPos.y * cellH + cellH / 2
                        val toX = boardPad + atom.toPos.x * cellW + cellW / 2
                        val toY = boardPad + atom.toPos.y * cellH + cellH / 2
                        val cx = fromX + (toX - fromX) * progress
                        val cy = fromY + (toY - fromY) * progress
                        drawCircle(color = atom.color, radius = r, center = Offset(cx, cy))
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    state: GameState,
    pos: Pos,
    onTap: () -> Unit,
    isLastMove: Boolean,
    interactive: Boolean,
    modifier: Modifier
) {
    val level = state.level
    val playable = level.isPlayable(pos)
    val owner = if (playable) state.board.ownerAt(pos) else Board.NO_OWNER
    val count = if (playable) state.board.countAt(pos) else 0
    val criticalMass = if (playable) level.criticalMass(pos) else 0
    val targetOwnerColor = if (owner != Board.NO_OWNER)
        Color(state.players[owner].color.toInt()) else Color.Transparent
    val ownerColor by animateColorAsState(
        targetOwnerColor,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing)
    )
    val bg = if (!playable) Color(0xFF111111) else Color(0xFF22262B)
    val borderColor = when {
        isLastMove -> LAST_MOVE_HIGHLIGHT_COLOR
        !playable  -> Color(0xFF222222)
        else       -> Color(0xFF3A4048)
    }
    val borderWidth = if (isLastMove) LAST_MOVE_BORDER_WIDTH else DEFAULT_BORDER_WIDTH

    // Scale animation whenever the atom count changes — gives a bouncy "pop".
    val badgeScale by animateFloatAsState(
        targetValue = if (count == 0) 0f else 1f,
        animationSpec = tween(durationMillis = 180, easing = EaseOutBack)
    )

    // Supercritical cells pulse to telegraph the upcoming explosion.
    val isSupercritical = playable && criticalMass > 0 && count >= criticalMass
    val pulse by rememberInfiniteTransition(label = "supercritical")
        .animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
    val pulseScale = if (isSupercritical) pulse else 1f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .then(if (playable && interactive) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (playable && count > 0) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize(0.82f)
                    .scale(badgeScale * pulseScale)
            ) {
                drawAtomDots(count, ownerColor)
            }
        }
    }
}

/** Draws 1–4 coloured circles inside [DrawScope] arranged to look like atom orbitals. */
private fun DrawScope.drawAtomDots(count: Int, color: Color) {
    val d = size.minDimension
    val r = d * 0.21f
    val gap = r * 1.15f
    val cx = size.width / 2
    val cy = size.height / 2
    when (count) {
        1 -> drawCircle(color = color, radius = r * 1.35f, center = Offset(cx, cy))
        2 -> {
            drawCircle(color = color, radius = r, center = Offset(cx - gap * 0.6f, cy))
            drawCircle(color = color, radius = r, center = Offset(cx + gap * 0.6f, cy))
        }
        3 -> {
            drawCircle(color = color, radius = r, center = Offset(cx, cy - gap * 0.6f))
            drawCircle(color = color, radius = r, center = Offset(cx - gap * 0.55f, cy + gap * 0.4f))
            drawCircle(color = color, radius = r, center = Offset(cx + gap * 0.55f, cy + gap * 0.4f))
        }
        else -> {
            drawCircle(color = color, radius = r, center = Offset(cx - gap * 0.55f, cy - gap * 0.55f))
            drawCircle(color = color, radius = r, center = Offset(cx + gap * 0.55f, cy - gap * 0.55f))
            drawCircle(color = color, radius = r, center = Offset(cx - gap * 0.55f, cy + gap * 0.55f))
            drawCircle(color = color, radius = r, center = Offset(cx + gap * 0.55f, cy + gap * 0.55f))
        }
    }
}
