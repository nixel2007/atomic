package dev.atomic.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import atomic.composeapp.generated.resources.btn_start
import atomic.composeapp.generated.resources.label_board_size
import atomic.composeapp.generated.resources.label_bot_difficulty
import atomic.composeapp.generated.resources.label_explosions
import atomic.composeapp.generated.resources.label_players
import atomic.composeapp.generated.resources.menu_hot_seat
import atomic.composeapp.generated.resources.menu_vs_ai
import dev.atomic.app.GameConfig
import dev.atomic.app.GameMode
import dev.atomic.app.Navigator
import dev.atomic.app.Screen
import dev.atomic.app.settings.AppSettingsHolder
import dev.atomic.app.settings.SettingKeys
import dev.atomic.shared.engine.BotDifficulty
import dev.atomic.shared.engine.ExplosionMode
import org.jetbrains.compose.resources.stringResource

@Composable
fun SetupScreen(nav: Navigator, mode: GameMode) {
    val settings = remember { AppSettingsHolder.instance }
    var players by remember { mutableStateOf(settings.getInt(SettingKeys.LAST_PLAYERS, 2).coerceIn(2, 4)) }
    var width by remember { mutableStateOf(settings.getInt(SettingKeys.LAST_BOARD_W, 6)) }
    var height by remember { mutableStateOf(settings.getInt(SettingKeys.LAST_BOARD_H, 9)) }
    var explosion by remember {
        mutableStateOf(
            ExplosionMode.entries.firstOrNull { it.name == settings.getString(SettingKeys.LAST_EXPLOSION, "") }
                ?: ExplosionMode.Wave
        )
    }
    var difficulty by remember {
        mutableStateOf(
            BotDifficulty.entries.firstOrNull { it.name == settings.getString(SettingKeys.LAST_DIFFICULTY, "") }
                ?: BotDifficulty.Medium
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (mode == GameMode.HotSeat) stringResource(Res.string.menu_hot_seat) else stringResource(Res.string.menu_vs_ai),
            fontSize = 28.sp, fontWeight = FontWeight.Bold
        )
        Text(stringResource(Res.string.label_players))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3, 4).forEach { n ->
                FilterChip(selected = players == n, onClick = { players = n }, label = { Text("$n") })
            }
        }

        Text(stringResource(Res.string.label_board_size))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5 to 7, 6 to 9, 8 to 10).forEach { (w, h) ->
                FilterChip(
                    selected = width == w && height == h,
                    onClick = { width = w; height = h },
                    label = { Text("${w}x$h") }
                )
            }
        }

        Text(stringResource(Res.string.label_explosions))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplosionMode.entries.forEach { mode ->
                FilterChip(
                    selected = explosion == mode,
                    onClick = { explosion = mode },
                    label = { Text(mode.displayName()) }
                )
            }
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

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { nav.back() }) { Text(stringResource(Res.string.btn_back)) }
            Button(
                onClick = {
                    settings.putInt(SettingKeys.LAST_PLAYERS, players)
                    settings.putInt(SettingKeys.LAST_BOARD_W, width)
                    settings.putInt(SettingKeys.LAST_BOARD_H, height)
                    settings.putString(SettingKeys.LAST_EXPLOSION, explosion.name)
                    settings.putString(SettingKeys.LAST_DIFFICULTY, difficulty.name)
                    nav.go(Screen.Game(
                        GameConfig(
                            mode = mode,
                            playerCount = players,
                            boardWidth = width,
                            boardHeight = height,
                            explosionMode = explosion,
                            botDifficulty = difficulty
                        )
                    ))
                }
            ) { Text(stringResource(Res.string.btn_start)) }
        }
    }
}
