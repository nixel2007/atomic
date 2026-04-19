package dev.atomic.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atomic.app.Navigator
import dev.atomic.app.game.BoardView
import dev.atomic.app.online.LinkStatus
import dev.atomic.app.online.MatchClient
import dev.atomic.shared.engine.Board
import dev.atomic.shared.engine.GameEngine
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import dev.atomic.shared.net.ClientMessage
import dev.atomic.shared.net.ServerMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val FRAME_DELAY_MS = 140L

private sealed interface Stage {
    data object Idle : Stage
    data class Lobby(val code: String, val maxSeats: Int, val ready: Boolean) : Stage
    data class Playing(val state: GameState) : Stage
    data class GameOver(val state: GameState, val winnerSeat: Int) : Stage
}

@Composable
fun OnlineScreen(nav: Navigator) {
    val scope = rememberCoroutineScope()
    val client = remember { MatchClient(scope) }
    DisposableEffect(client) { onDispose { client.shutdown() } }

    var host by remember { mutableStateOf("ws://localhost:8080/game") }
    var nickname by remember { mutableStateOf("Player") }
    var joinCode by remember { mutableStateOf("") }
    var createSeats by remember { mutableStateOf(2) }

    var stage by remember { mutableStateOf<Stage>(Stage.Idle) }
    var seat by remember { mutableStateOf(-1) }
    var players by remember { mutableStateOf<List<Player>>(emptyList()) }
    var banner by remember { mutableStateOf<String?>(null) }

    // Animation state for cascades driven by server GameUpdated events.
    var displayBoard by remember { mutableStateOf<Board?>(null) }
    var animating by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<ServerMessage.GameUpdated?>(null) }
    var previousState by remember { mutableStateOf<GameState?>(null) }

    val status by client.status.collectAsState()
    val linkError by client.lastError.collectAsState()

    fun resetToIdle() {
        stage = Stage.Idle
        seat = -1
        players = emptyList()
        previousState = null
        displayBoard = null
        pendingUpdate = null
    }

    LaunchedEffect(client) {
        client.events.collect { msg ->
            when (msg) {
                is ServerMessage.RoomCreated -> {
                    seat = msg.seat
                    players = msg.players
                    stage = Stage.Lobby(msg.code, msg.maxSeats, ready = false)
                }
                is ServerMessage.RoomJoined -> {
                    seat = msg.seat
                    players = msg.players
                    stage = Stage.Lobby(msg.code, msg.maxSeats, ready = false)
                }
                is ServerMessage.PlayerJoined -> {
                    players = (players.filter { it.index != msg.player.index } + msg.player)
                        .sortedBy { it.index }
                }
                is ServerMessage.PlayerLeft -> {
                    players = players.filter { it.index != msg.seat }
                    banner = "Seat ${msg.seat} left"
                }
                is ServerMessage.GameStarted -> {
                    players = msg.state.players
                    previousState = msg.state
                    displayBoard = msg.state.board
                    stage = Stage.Playing(msg.state)
                }
                is ServerMessage.GameUpdated -> pendingUpdate = msg
                is ServerMessage.GameOver -> {
                    pendingUpdate = null
                    previousState = msg.state
                    displayBoard = msg.state.board
                    stage = Stage.GameOver(msg.state, msg.winnerSeat)
                }
                is ServerMessage.Chat -> Unit
                is ServerMessage.ErrorMessage -> banner = msg.message
            }
        }
    }

    // Animate each GameUpdated by replaying applyMoveAnimated against the
    // previous state using the server-provided lastMove. The engine is
    // deterministic, so the final board matches msg.state.board.
    LaunchedEffect(pendingUpdate) {
        val upd = pendingUpdate ?: return@LaunchedEffect
        val prev = previousState ?: upd.state
        animating = true
        val result = GameEngine.applyMoveAnimated(prev, upd.lastMove)
        for ((i, frame) in result.frames.withIndex()) {
            displayBoard = frame
            if (i < result.frames.size - 1) delay(FRAME_DELAY_MS)
        }
        displayBoard = upd.state.board
        previousState = upd.state
        stage = Stage.Playing(upd.state)
        animating = false
        pendingUpdate = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Online", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        StatusLine(status, linkError)
        banner?.let { Text(it, color = Color(0xFFFFAB91)) }

        when (val s = stage) {
            Stage.Idle -> IdlePanel(
                host = host, onHostChange = { host = it },
                nickname = nickname, onNicknameChange = { nickname = it },
                seats = createSeats, onSeatsChange = { createSeats = it },
                joinCode = joinCode, onJoinCodeChange = { joinCode = it },
                onCreate = {
                    banner = null
                    client.connect(host)
                    scope.launch {
                        if (awaitReady(client)) {
                            client.send(
                                ClientMessage.CreateRoom(
                                    level = Level.rectangular("p", "pickup", 6, 9),
                                    settings = GameSettings(),
                                    seats = createSeats,
                                    nickname = nickname.ifBlank { "Player" }
                                )
                            )
                        }
                    }
                },
                onJoin = {
                    banner = null
                    client.connect(host)
                    scope.launch {
                        if (awaitReady(client)) {
                            client.send(
                                ClientMessage.JoinRoom(
                                    code = joinCode.filter { it.isDigit() }.take(6),
                                    nickname = nickname.ifBlank { "Player" }
                                )
                            )
                        }
                    }
                },
                onBack = { nav.back() }
            )

            is Stage.Lobby -> LobbyPanel(
                code = s.code,
                seat = seat,
                players = players,
                maxSeats = s.maxSeats,
                ready = s.ready,
                onReady = {
                    stage = s.copy(ready = true)
                    scope.launch { client.send(ClientMessage.SetReady) }
                },
                onLeave = {
                    scope.launch { client.send(ClientMessage.LeaveRoom) }
                    client.disconnect()
                    resetToIdle()
                }
            )

            is Stage.Playing -> PlayingPanel(
                state = s.state,
                displayBoard = displayBoard ?: s.state.board,
                seat = seat,
                animating = animating,
                onTap = { pos ->
                    if (animating || s.state.isOver) return@PlayingPanel
                    if (s.state.currentPlayerIndex != seat) return@PlayingPanel
                    if (!GameEngine.isLegalMove(s.state, pos)) return@PlayingPanel
                    scope.launch { client.send(ClientMessage.MakeMove(pos)) }
                },
                onLeave = {
                    scope.launch { client.send(ClientMessage.LeaveRoom) }
                    client.disconnect()
                    resetToIdle()
                }
            )

            is Stage.GameOver -> GameOverPanel(
                state = s.state,
                winnerSeat = s.winnerSeat,
                onBack = {
                    scope.launch { client.send(ClientMessage.LeaveRoom) }
                    client.disconnect()
                    resetToIdle()
                }
            )
        }
    }
}

@Composable
private fun StatusLine(status: LinkStatus, error: String?) {
    val (label, color) = when (status) {
        LinkStatus.Idle -> "not connected" to Color.Gray
        LinkStatus.Connecting -> "connecting…" to Color(0xFFFDD835)
        LinkStatus.Connected -> "connected" to Color(0xFF66BB6A)
        LinkStatus.Closed -> "disconnected" to Color.Gray
        LinkStatus.Failed -> "failed: ${error ?: "connection error"}" to Color(0xFFE57373)
    }
    Text(label, color = color)
}

@Composable
private fun IdlePanel(
    host: String, onHostChange: (String) -> Unit,
    nickname: String, onNicknameChange: (String) -> Unit,
    seats: Int, onSeatsChange: (Int) -> Unit,
    joinCode: String, onJoinCodeChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit
) {
    OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        label = { Text("Server URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = nickname,
        onValueChange = onNicknameChange,
        label = { Text("Nickname") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Text("Create room", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2, 3, 4).forEach { n ->
            FilterChip(
                selected = seats == n,
                onClick = { onSeatsChange(n) },
                label = { Text("$n seats") }
            )
        }
    }
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Text("Create $seats-seat room")
    }

    Spacer(Modifier.height(4.dp))
    Text("Join room", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = joinCode,
        onValueChange = onJoinCodeChange,
        label = { Text("6-digit code") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = onJoin,
        enabled = joinCode.length == 6,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Join") }

    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = onBack) { Text("Back") }
}

@Composable
private fun LobbyPanel(
    code: String,
    seat: Int,
    players: List<Player>,
    maxSeats: Int,
    ready: Boolean,
    onReady: () -> Unit,
    onLeave: () -> Unit
) {
    Text("Room code", color = Color.Gray)
    Text(code, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    Text(
        "Share this code with friends. Game starts when everyone is ready.",
        color = Color.Gray
    )

    Text("Seats (${players.size}/$maxSeats)", fontWeight = FontWeight.Bold)
    players.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .width(18.dp).height(18.dp)
                    .clip(CircleShape)
                    .background(Color(p.color.toInt()))
            )
            Text(
                if (p.index == seat) "${p.name} (you)" else p.name,
                fontWeight = if (p.index == seat) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onReady, enabled = !ready) {
            Text(if (ready) "Ready ✓" else "I'm ready")
        }
        OutlinedButton(onClick = onLeave) { Text("Leave") }
    }
}

@Composable
private fun PlayingPanel(
    state: GameState,
    displayBoard: Board,
    seat: Int,
    animating: Boolean,
    onTap: (Pos) -> Unit,
    onLeave: () -> Unit
) {
    val render = remember(state, displayBoard) { state.copy(board = displayBoard) }
    val you = state.players.getOrNull(seat)
    val turn = state.currentPlayer
    val yourTurn = state.currentPlayerIndex == seat && !state.isOver && !animating

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            you?.let {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .width(16.dp).height(16.dp)
                        .clip(CircleShape)
                        .background(Color(it.color.toInt()))
                )
                Text("you: ${it.name}")
            }
        }
        Text(
            if (yourTurn) "your turn" else "turn: ${turn.name}",
            color = if (yourTurn) Color(0xFF66BB6A) else Color.Gray,
            fontWeight = if (yourTurn) FontWeight.Bold else FontWeight.Normal
        )
    }

    BoardView(state = render, onCellTap = onTap, modifier = Modifier.fillMaxWidth())

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onLeave) { Text("Leave room") }
}

@Composable
private fun GameOverPanel(
    state: GameState,
    winnerSeat: Int,
    onBack: () -> Unit
) {
    val winner = state.players[winnerSeat]
    Text(
        "${winner.name} wins!",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Color(winner.color.toInt())
    )
    BoardView(state = state, onCellTap = {}, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Button(onClick = onBack) { Text("Back to lobby") }
}

/** Suspends until the WebSocket session is Connected. Returns false if the
 *  link failed or closed before becoming ready. */
private suspend fun awaitReady(client: MatchClient): Boolean {
    val final = client.status.first { s ->
        s == LinkStatus.Connected || s == LinkStatus.Failed || s == LinkStatus.Closed
    }
    return final == LinkStatus.Connected
}
