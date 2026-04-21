package dev.atomic.app.screens

import androidx.compose.runtime.Composable
import atomic.composeapp.generated.resources.*
import dev.atomic.shared.engine.BotDifficulty
import dev.atomic.shared.engine.ExplosionMode
import org.jetbrains.compose.resources.stringResource

@Composable
fun ExplosionMode.displayName(): String = stringResource(
    when (this) {
        ExplosionMode.Wave -> Res.string.explosion_mode_wave
        ExplosionMode.Recursive -> Res.string.explosion_mode_recursive
    }
)

@Composable
fun BotDifficulty.displayName(): String = stringResource(
    when (this) {
        BotDifficulty.Easy -> Res.string.bot_difficulty_easy
        BotDifficulty.Medium -> Res.string.bot_difficulty_medium
        BotDifficulty.Hard -> Res.string.bot_difficulty_hard
    }
)
