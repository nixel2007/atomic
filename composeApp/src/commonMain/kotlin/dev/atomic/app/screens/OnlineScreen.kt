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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
import dev.atomic.app.Navigator
import dev.atomic.app.game.BoardView
import dev.atomic.app.game.ExplodingAtom
import dev.atomic.app.game.computeExplodingAtoms
import dev.atomic.app.online.LinkStatus
import dev.atomic.app.online.MatchClientHolder
import dev.atomic.app.settings.AppSettingsHolder
import dev.atomic.app.settings.SettingKeys
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

private const val FRAME_DELAY_MS = 140L

private sealed interface Stage {
    data object Idle : Stage
    data class Lobby(val code: String, val maxSeats: Int, val ready: Boolean) : Stage
    data class Playing(val state: GameState) : Stage
    data class GameOver(val state: GameState, val winnerSeat: Int) : Stage
}

private data class ChatLine(val seat: Int, val name: String, val color: Long, val text: String)

@Composable
fun OnlineScreen(nav: Navigator, customLevel: Level? = null) {
    val client = remember { MatchClientHolder.instance }
    val settings = remember { AppSettingsHolder.instance }

    var host by remember {
        mutableStateOf(settings.getString(
            SettingKeys.RELAY_URL,
            "wss://atomic-server-production.up.railway.app/game"
        ))
    }
    var nickname by remember { mutableStateOf(settings.getString(SettingKeys.NICKNAME, "Player")) }
    var joinCode by remember { mutableStateOf("") }
    var createSeats by remember { mutableStateOf(2) }

    var stage by remember { mutableStateOf<Stage>(Stage.Idle) }
    var seat by remember { mutableStateOf(-1) }
    var players by remember { mutableStateOf<List<Player>>(emptyList()) }
    var readySeats by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var banner by remember { mutableStateOf<String?>(null) }
    var chatLines by remember { mutableStateOf<List<ChatLine>>(emptyList()) }
    var chatDraft by remember { mutableStateOf("") }

    // Animation state for cascades driven by server GameUpdated events.
    var displayBoard by remember { mutableStateOf<Board?>(null) }
    var animating by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<ServerMessage.GameUpdated?>(null) }
    var previousState by remember { mutableStateOf<GameState?>(null) }
    var lastMove by remember { mutableStateOf<Pos?>(null) }
    var explodingAtoms by remember { mutableStateOf<List<ExplodingAtom>>(emptyList()) }

    val status by client.status.collectAsState()
    val linkError by client.lastError.collectAsState()

    fun resetToIdle() {
        stage = Stage.Idle
        seat = -1
        players = emptyList()
        readySeats = emptySet()
        chatLines = emptyList()
        chatDraft = ""
        previousState = null
        displayBoard = null
        pendingUpdate = null
        lastMove = null
        explodingAtoms = emptyList()
    }

    fun playerName(s: Int): String = players.firstOrNull { it.index == s }?.name ?: "seat $s"
    fun playerColor(s: Int): Long = players.firstOrNull { it.index == s }?.color ?: 0xFFFFFFFFL

    LaunchedEffect(client) {
        client.events.collect { msg ->
            when (msg) {
                is ServerMessage.RoomCreated -> {
                    seat = msg.seat
                    players = msg.players
                    readySeats = msg.readySeats.toSet()
                    client.setResumePoint(msg.code, nickname.ifBlank { "Player" })
                    stage = Stage.Lobby(msg.code, msg.maxSeats, ready = false)
                }
                is ServerMessage.RoomJoined -> {
                    seat = msg.seat
                    players = msg.players
                    readySeats = msg.readySeats.toSet()
                    client.setResumePoint(msg.code, nickname.ifBlank { "Player" })
                    stage = Stage.Lobby(msg.code, msg.maxSeats, ready = msg.seat in msg.readySeats)
                }
                is ServerMessage.PlayerJoined -> {
                    players = (players.filter { it.index != msg.player.index } + msg.player)
                        .sortedBy { it.index }
                }
                is ServerMessage.PlayerLeft -> {
                    players = players.filter { it.index != msg.seat }
                    readySeats = readySeats - msg.seat
                    banner = "${playerName(msg.seat)} left"
                }
                is ServerMessage.PlayerDisconnected -> {
                    banner = "${playerName(msg.seat)} lost connection (${msg.graceSeconds}s to rejoin)"
                }
                is ServerMessage.PlayerRejoined -> {
                    banner = "${playerName(msg.seat)} reconnected"
                }
                is ServerMessage.PlayerReady -> {
                    readySeats = readySeats + msg.seat
                }
                is ServerMessage.PlayerUnready -> {
                    readySeats = readySeats - msg.seat
                    val current = stage
                    if (msg.seat == seat && current is Stage.Lobby) {
                        stage = current.copy(ready = false)
                    }
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
                is ServerMessage.Chat -> {
                    chatLines = (chatLines + ChatLine(
                        seat = msg.fromSeat,
                        name = playerName(msg.fromSeat),
                        color = playerColor(msg.fromSeat),
                        text = msg.text
                    )).takeLast(50)
                }
                is ServerMessage.ErrorMessage -> banner = msg.message
            }
        }
    }

    // Animate each GameUpdated by replaying applyMoveTraced against the
    // previous state using the server-provided lastMove.
    LaunchedEffect(pendingUpdate) {
        val upd = pendingUpdate ?: return@LaunchedEffect
        val prev = previousState ?: upd.state
        animating = true
        val result = GameEngine.applyMoveTraced(prev, upd.lastMove)
        for ((i, wave) in result.waves.withIndex()) {
            displayBoard = wave
            if (i < result.waves.size - 1) {
                explodingAtoms = computeExplodingAtoms(wave, prev.level, prev.players)
                delay(FRAME_DELAY_MS)
            }
        }
        explodingAtoms = emptyList()
        lastMove = if (result.waves.size == 1) upd.lastMove else null
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
                customLevel = customLevel,
                onCreate = {
                    banner = null
                    settings.putString(SettingKeys.RELAY_URL, host)
                    settings.putString(SettingKeys.NICKNAME, nickname.ifBlank { "Player" })
                    client.connect(host)
                    suspendSendCreate(
                        client = client,
                        level = customLevel
                            ?: Level.rectangular("p", "pickup", 6, 9),
                        seats = createSeats,
                        nickname = nickname.ifBlank { "Player" }
                    )
                },
                onJoin = {
                    banner = null
                    settings.putString(SettingKeys.RELAY_URL, host)
                    settings.putString(SettingKeys.NICKNAME, nickname.ifBlank { "Player" })
                    client.connect(host)
                    suspendSendJoin(
                        client = client,
                        code = joinCode.filter { it.isDigit() }.take(6),
                        nickname = nickname.ifBlank { "Player" }
                    )
                },
                onBack = { nav.back() }
            )

            is Stage.Lobby -> {
                LobbyPanel(
                    code = s.code,
                    seat = seat,
                    players = players,
                    readySeats = readySeats,
                    maxSeats = s.maxSeats,
                    ready = s.ready,
                    onReady = {
                        stage = s.copy(ready = true)
                        client.send(ClientMessage.SetReady)
                    },
                    onUnready = {
                        stage = s.copy(ready = false)
                        client.send(ClientMessage.CancelReady)
                    },
                    onLeave = {
                        client.send(ClientMessage.LeaveRoom)
                        client.disconnect()
                        resetToIdle()
                    }
                )
                ChatPanel(
                    lines = chatLines,
                    draft = chatDraft,
                    selfSeat = seat,
                    onDraftChange = { chatDraft = it },
                    onSend = {
                        val text = chatDraft.trim()
                        if (text.isNotEmpty()) {
                            client.send(ClientMessage.Chat(text))
                            chatDraft = ""
                        }
                    }
                )
            }

            is Stage.Playing -> {
                PlayingPanel(
                    state = s.state,
                    displayBoard = displayBoard ?: s.state.board,
                    seat = seat,
                    animating = animating,
                    lastMove = lastMove,
                    explodingAtoms = explodingAtoms,
                    onTap = { pos ->
                        if (animating || s.state.isOver) return@PlayingPanel
                        if (s.state.currentPlayerIndex != seat) return@PlayingPanel
                        if (!GameEngine.isLegalMove(s.state, pos)) return@PlayingPanel
                        client.send(ClientMessage.MakeMove(pos))
                    },
                    onLeave = {
                        client.send(ClientMessage.LeaveRoom)
                        client.disconnect()
                        resetToIdle()
                    }
                )
                ChatPanel(
                    lines = chatLines,
                    draft = chatDraft,
                    selfSeat = seat,
                    onDraftChange = { chatDraft = it },
                    onSend = {
                        val text = chatDraft.trim()
                        if (text.isNotEmpty()) {
                            client.send(ClientMessage.Chat(text))
                            chatDraft = ""
                        }
                    }
                )
            }

            is Stage.GameOver -> GameOverPanel(
                state = s.state,
                winnerSeat = s.winnerSeat,
                onBack = {
                    client.send(ClientMessage.LeaveRoom)
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
        LinkStatus.Reconnecting -> "reconnecting…" to Color(0xFFFDD835)
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
    customLevel: Level?,
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
    if (customLevel != null) {
        Text(
            "Using your custom ${customLevel.width}×${customLevel.height} level " +
                "(${customLevel.blocked.size} blocked).",
            color = Color(0xFF90CAF9)
        )
    }
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
        onValueChange = { onJoinCodeChange(it.filter { c -> c.isDigit() }.take(6)) },
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
    readySeats: Set<Int>,
    maxSeats: Int,
    ready: Boolean,
    onReady: () -> Unit,
    onUnready: () -> Unit,
    onLeave: () -> Unit
) {
    Text("Room code", color = Color.Gray)
    Text(code, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    Text(
        "Share this code with friends. Game starts when everyone is ready.",
        color = Color.Gray
    )

    Text(
        "Seats (${players.size}/$maxSeats) — ${readySeats.size}/$maxSeats ready",
        fontWeight = FontWeight.Bold
    )
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
                text = buildString {
                    append(p.name)
                    if (p.index == seat) append(" (you)")
                },
                fontWeight = if (p.index == seat) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(end = 8.dp)
            )
            if (p.index in readySeats) {
                Text("ready", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
            } else {
                Text("waiting", color = Color.Gray)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ready) {
            OutlinedButton(onClick = onUnready) { Text("Cancel ready") }
        } else {
            Button(onClick = onReady) { Text("I'm ready") }
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
    lastMove: Pos?,
    explodingAtoms: List<ExplodingAtom>,
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

    BoardView(
        state = render,
        onCellTap = onTap,
        lastMove = lastMove,
        explodingAtoms = explodingAtoms,
        modifier = Modifier.fillMaxWidth()
    )

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

@Composable
private fun ChatPanel(
    lines: List<ChatLine>,
    draft: String,
    selfSeat: Int,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Spacer(Modifier.height(4.dp))
    Text("Chat", fontWeight = FontWeight.Bold)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1D21))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (lines.isEmpty()) {
            Text("No messages yet.", color = Color.Gray, fontSize = 12.sp)
        } else {
            lines.forEach { line ->
                Row {
                    Text(
                        text = line.name + (if (line.seat == selfSeat) " (you)" else "") + ": ",
                        color = Color(line.color.toInt()),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(line.text, color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { onDraftChange(it.take(280)) },
            placeholder = { Text("Say something…") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = onSend, enabled = draft.isNotBlank()) { Text("Send") }
    }
}

/** Spawns a coroutine on [MatchClientHolder.instance]'s own scope (via its
 *  [send] / [connect] lambdas) that waits for a Connected link then fires the
 *  CreateRoom request. Kept out of the composable body so scope ownership is
 *  unambiguous. */
private fun suspendSendCreate(
    client: dev.atomic.app.online.MatchClient,
    level: Level,
    seats: Int,
    nickname: String
) {
    client.scheduleAfterConnect {
        send(
            ClientMessage.CreateRoom(
                level = level,
                settings = GameSettings(),
                seats = seats,
                nickname = nickname
            )
        )
    }
}

private fun suspendSendJoin(
    client: dev.atomic.app.online.MatchClient,
    code: String,
    nickname: String
) {
    client.scheduleAfterConnect {
        send(ClientMessage.JoinRoom(code = code, nickname = nickname))
    }
}
