package dev.atomic.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import atomic.composeapp.generated.resources.Res
import atomic.composeapp.generated.resources.btn_back
import atomic.composeapp.generated.resources.btn_back_to_menu
import atomic.composeapp.generated.resources.btn_play_again
import atomic.composeapp.generated.resources.game_final_turn_count
import atomic.composeapp.generated.resources.game_player_out
import atomic.composeapp.generated.resources.game_players_eliminated
import atomic.composeapp.generated.resources.game_turn
import atomic.composeapp.generated.resources.game_winner
import atomic.composeapp.generated.resources.player_bot_prefix
import atomic.composeapp.generated.resources.player_human_prefix
import dev.atomic.app.GameConfig
import dev.atomic.app.GameMode
import dev.atomic.app.Navigator
import dev.atomic.app.game.BoardView
import dev.atomic.app.game.ExplodingAtom
import dev.atomic.app.game.computeExplodingAtoms
import dev.atomic.shared.ai.Bot
import dev.atomic.shared.engine.GameEngine
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.engine.PlayerKind
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private val PALETTE = longArrayOf(
    0xFFE53935L, 0xFF1E88E5L, 0xFF43A047L, 0xFFFDD835L
)

private const val MAX_GAME_SCREEN_WIDTH_DP = 640
private const val FRAME_DELAY_MS = 140L

@Composable
fun GameScreen(nav: Navigator, config: GameConfig) {
    val botPrefix = stringResource(Res.string.player_bot_prefix)
    val humanPrefix = stringResource(Res.string.player_human_prefix)
    val players = remember(config, botPrefix, humanPrefix) { buildPlayers(config, botPrefix, humanPrefix) }
    var state by remember(config, botPrefix, humanPrefix) {
        mutableStateOf(
            GameState.initial(
                level = config.level
                    ?: Level.rectangular("p", "pickup", config.boardWidth, config.boardHeight),
                settings = GameSettings(config.explosionMode),
                players = players
            )
        )
    }
    var displayBoard by remember(config) { mutableStateOf(state.board) }
    var animating by remember(config) { mutableStateOf(false) }
    var pendingMove by remember(config) { mutableStateOf<Pos?>(null) }
    var lastMove by remember(config) { mutableStateOf<Pos?>(null) }
    var explodingAtoms by remember(config) { mutableStateOf<List<ExplodingAtom>>(emptyList()) }

    // Keep the rendered board in sync when the game is reset.
    LaunchedEffect(state) {
        if (!animating) displayBoard = state.board
    }

    // Apply a queued move (human tap or bot pick) with a stepped cascade.
    LaunchedEffect(pendingMove) {
        val move = pendingMove ?: return@LaunchedEffect
        if (!GameEngine.isLegalMove(state, move)) {
            pendingMove = null
            return@LaunchedEffect
        }
        animating = true
        val result = GameEngine.applyMoveTraced(state, move)
        for ((i, wave) in result.waves.withIndex()) {
            displayBoard = wave
            if (i < result.waves.size - 1) {
                explodingAtoms = computeExplodingAtoms(wave, state.level, state.players)
                delay(FRAME_DELAY_MS)
            }
        }
        explodingAtoms = emptyList()
        lastMove = if (result.waves.size == 1) move else null
        state = result.finalState
        displayBoard = result.finalState.board
        animating = false
        pendingMove = null
    }

    // AI auto-play when the current seat belongs to a bot.
    LaunchedEffect(state, animating) {
        if (animating || state.isOver) return@LaunchedEffect
        val cp = state.currentPlayer
        if (cp.kind == PlayerKind.Bot) {
            delay(350)
            val diff = cp.difficulty ?: return@LaunchedEffect
            pendingMove = Bot.of(diff).chooseMove(state)
        }
    }

    val renderState = remember(state, displayBoard) { state.copy(board = displayBoard) }

    Box(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = Modifier.widthIn(max = MAX_GAME_SCREEN_WIDTH_DP.dp).fillMaxHeight()) {
            TurnBar(state)
            Spacer(Modifier.height(8.dp))
            BoardView(
                state = renderState,
                onCellTap = { pos ->
                    if (animating || state.isOver) return@BoardView
                    if (state.currentPlayer.kind != PlayerKind.Human) return@BoardView
                    if (!GameEngine.isLegalMove(state, pos)) return@BoardView
                    pendingMove = pos
                },
                lastMove = lastMove,
                explodingAtoms = explodingAtoms,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.back() }) { Text(stringResource(Res.string.btn_back)) }
            }
        }
    }

    if (state.isOver) {
        val w = state.players[state.winner!!]
        AlertDialog(
            onDismissRequest = {},
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .width(22.dp).height(22.dp)
                            .clip(CircleShape)
                            .background(Color(w.color.toInt()))
                    )
                    Text(stringResource(Res.string.game_winner, w.name), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(stringResource(Res.string.game_final_turn_count, state.turnsPlayed))
                    val eliminated = state.players.count { !it.active }
                    if (eliminated > 0) Text(stringResource(Res.string.game_players_eliminated, eliminated))
                }
            },
            confirmButton = {
                Button(onClick = {
                    state = GameState.initial(state.level, state.settings, buildPlayers(config, botPrefix, humanPrefix))
                }) { Text(stringResource(Res.string.btn_play_again)) }
            },
            dismissButton = {
                TextButton(onClick = { nav.back() }) { Text(stringResource(Res.string.btn_back_to_menu)) }
            }
        )
    }
}

@Composable
private fun TurnBar(state: GameState) {
    val outText = stringResource(Res.string.game_player_out)
    val turnText = stringResource(Res.string.game_turn, state.turnsPlayed + 1)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            state.players.forEach { p ->
                val dim = !p.active
                val col = Color(p.color.toInt())
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .width(18.dp).height(18.dp)
                        .clip(CircleShape)
                        .background(if (dim) col.copy(alpha = 0.25f) else col)
                )
                Text(
                    text = if (!p.active) "${p.name} $outText" else p.name,
                    modifier = Modifier.padding(end = 12.dp),
                    color = if (p.index == state.currentPlayerIndex && !state.isOver) Color.White else Color.Gray,
                    fontWeight = if (p.index == state.currentPlayerIndex && !state.isOver) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        Text(turnText, color = Color.Gray)
    }
}

private fun buildPlayers(config: GameConfig, botPrefix: String, humanPrefix: String): List<Player> = List(config.playerCount) { i ->
    val isBot = config.mode == GameMode.VsBot && i != config.humanSeat
    Player(
        index = i,
        name = if (isBot) "$botPrefix ${i + 1}" else "$humanPrefix ${i + 1}",
        color = PALETTE[i],
        kind = if (isBot) PlayerKind.Bot else PlayerKind.Human,
        difficulty = if (isBot) config.botDifficulty else null
    )
}
