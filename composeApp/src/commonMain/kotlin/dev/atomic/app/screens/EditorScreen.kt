package dev.atomic.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atomic.composeapp.generated.resources.Res
import atomic.composeapp.generated.resources.btn_back
import atomic.composeapp.generated.resources.btn_clear
import atomic.composeapp.generated.resources.btn_play
import atomic.composeapp.generated.resources.btn_play_online
import atomic.composeapp.generated.resources.editor_cm_sample
import atomic.composeapp.generated.resources.editor_hint
import atomic.composeapp.generated.resources.label_bot_difficulty
import atomic.composeapp.generated.resources.label_explosions
import atomic.composeapp.generated.resources.label_mode
import atomic.composeapp.generated.resources.label_players
import atomic.composeapp.generated.resources.label_size
import atomic.composeapp.generated.resources.menu_hot_seat
import atomic.composeapp.generated.resources.mode_vs_bot
import atomic.composeapp.generated.resources.screen_editor
import dev.atomic.app.GameConfig
import dev.atomic.app.GameMode
import dev.atomic.app.Navigator
import dev.atomic.app.Screen
import dev.atomic.app.game.BoardView
import dev.atomic.shared.engine.BotDifficulty
import dev.atomic.shared.engine.ExplosionMode
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import org.jetbrains.compose.resources.stringResource

@Composable
fun EditorScreen(nav: Navigator) {
    var width by remember { mutableStateOf(6) }
    var height by remember { mutableStateOf(9) }
    var blocked by remember { mutableStateOf<Set<Pos>>(emptySet()) }
    var players by remember { mutableStateOf(2) }
    var mode by remember { mutableStateOf(GameMode.HotSeat) }
    var difficulty by remember { mutableStateOf(BotDifficulty.Medium) }
    var explosion by remember { mutableStateOf(ExplosionMode.Wave) }
    val level = remember(width, height, blocked) {
        Level(id = "draft", name = "Draft", width = width, height = height, blocked = blocked)
    }
    val preview = remember(level) {
        GameState.initial(
            level = level,
            settings = GameSettings(),
            players = listOf(
                Player(0, "P1", 0xFFE53935L),
                Player(1, "P2", 0xFF1E88E5L)
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(Res.string.screen_editor), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.editor_hint))

        Text(stringResource(Res.string.label_size))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5 to 7, 6 to 9, 8 to 10, 10 to 14).forEach { (w, h) ->
                FilterChip(
                    selected = width == w && height == h,
                    onClick = { width = w; height = h; blocked = blocked.filter { it.x < w && it.y < h }.toSet() },
                    label = { Text("${w}x$h") }
                )
            }
        }

        BoardView(
            state = preview,
            onCellTap = { pos ->
                blocked = if (pos in blocked) blocked - pos else blocked + pos
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text(stringResource(Res.string.editor_cm_sample,
            level.criticalMass(Pos(width / 2, height / 2)),
            level.criticalMass(Pos(0, 0))))

        Text(stringResource(Res.string.label_players))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3, 4).forEach { n ->
                FilterChip(
                    selected = players == n,
                    onClick = { players = n },
                    label = { Text("$n") }
                )
            }
        }

        Text(stringResource(Res.string.label_mode))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == GameMode.HotSeat,
                onClick = { mode = GameMode.HotSeat },
                label = { Text(stringResource(Res.string.menu_hot_seat)) }
            )
            FilterChip(
                selected = mode == GameMode.VsBot,
                onClick = { mode = GameMode.VsBot },
                label = { Text(stringResource(Res.string.mode_vs_bot)) }
            )
        }

        if (mode == GameMode.VsBot) {
            Text(stringResource(Res.string.label_bot_difficulty))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotDifficulty.entries.forEach { d ->
                    FilterChip(
                        selected = difficulty == d,
                        onClick = { difficulty = d },
                        label = { Text(d.displayName()) }
                    )
                }
            }
        }

        Text(stringResource(Res.string.label_explosions))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplosionMode.entries.forEach { e ->
                FilterChip(
                    selected = explosion == e,
                    onClick = { explosion = e },
                    label = { Text(e.displayName()) }
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nav.back() }) { Text(stringResource(Res.string.btn_back)) }
            OutlinedButton(onClick = { blocked = emptySet() }) { Text(stringResource(Res.string.btn_clear)) }
            Button(
                onClick = {
                    nav.go(
                        Screen.Game(
                            GameConfig(
                                mode = mode,
                                playerCount = players,
                                boardWidth = width,
                                boardHeight = height,
                                explosionMode = explosion,
                                botDifficulty = difficulty,
                                level = level
                            )
                        )
                    )
                }
            ) { Text(stringResource(Res.string.btn_play)) }
            Button(onClick = { nav.go(Screen.Online(customLevel = level)) }) {
                Text(stringResource(Res.string.btn_play_online))
            }
        }
    }
}
