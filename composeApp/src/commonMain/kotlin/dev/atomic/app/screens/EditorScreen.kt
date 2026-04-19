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
import dev.atomic.app.GameConfig
import dev.atomic.app.GameMode
import dev.atomic.app.Navigator
import dev.atomic.app.Screen
import dev.atomic.app.game.BoardView
import dev.atomic.shared.engine.ExplosionMode
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos

@Composable
fun EditorScreen(nav: Navigator) {
    var width by remember { mutableStateOf(6) }
    var height by remember { mutableStateOf(9) }
    var blocked by remember { mutableStateOf<Set<Pos>>(emptySet()) }
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
        Text("Level editor", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Tap a cell to toggle it blocked. Critical mass updates automatically.")

        Text("Size")
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

        Text("Critical mass sample: centre = ${level.criticalMass(Pos(width / 2, height / 2))}, " +
            "corner = ${level.criticalMass(Pos(0, 0))}")

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nav.back() }) { Text("Back") }
            OutlinedButton(onClick = { blocked = emptySet() }) { Text("Clear") }
            Button(
                onClick = {
                    nav.go(
                        Screen.Game(
                            GameConfig(
                                mode = GameMode.HotSeat,
                                playerCount = 2,
                                boardWidth = width,
                                boardHeight = height,
                                explosionMode = ExplosionMode.Wave,
                                level = level
                            )
                        )
                    )
                }
            ) { Text("Play") }
            Button(onClick = { nav.go(Screen.Online(customLevel = level)) }) {
                Text("Play online")
            }
        }
    }
}
