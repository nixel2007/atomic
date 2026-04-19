package dev.atomic.app.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atomic.shared.engine.Board
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.model.Pos

@Composable
fun BoardView(
    state: GameState,
    onCellTap: (Pos) -> Unit,
    modifier: Modifier = Modifier
) {
    val level = state.level
    val aspect = level.width.toFloat() / level.height.toFloat()
    Column(
        modifier = modifier.fillMaxWidth().aspectRatio(aspect).padding(4.dp)
    ) {
        for (y in 0 until level.height) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (x in 0 until level.width) {
                    val p = Pos(x, y)
                    Cell(
                        state = state,
                        pos = p,
                        onTap = { onCellTap(p) },
                        modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                    )
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
    val border = if (!playable) Color(0xFF222222) else Color(0xFF3A4048)

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
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .then(if (playable) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (playable && count > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.72f)
                    .scale(badgeScale * pulseScale)
                    .clip(CircleShape)
                    .background(ownerColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
