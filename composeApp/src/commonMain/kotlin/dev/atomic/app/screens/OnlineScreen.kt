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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import atomic.composeapp.generated.resources.*
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
import dev.atomic.shared.net.RoomInfo
import dev.atomic.shared.net.ServerMessage
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val FRAME_DELAY_MS = 140L
private const val SCROLL_BOTTOM_THRESHOLD_PX = 10
private const val CHAT_VISIBLE_THRESHOLD_PX = 60

private sealed interface Stage {
    data object Idle : Stage
    data class Lobby(val code: String, val maxSeats: Int, val ready: Boolean) : Stage
    data class Playing(val state: GameState) : Stage
    data class GameOver(val state: GameState, val winnerSeat: Int) : Stage
    /** Spectating a room — [state] is null if the game hasn't started yet. */
    data class Watching(val code: String, val maxSeats: Int, val state: GameState?) : Stage
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
    var isPrivate by remember { mutableStateOf(false) }
    var roomList by remember { mutableStateOf<List<RoomInfo>?>(null) }

    var stage by remember { mutableStateOf<Stage>(Stage.Idle) }
    var seat by remember { mutableStateOf(-1) }
    var players by remember { mutableStateOf<List<Player>>(emptyList()) }
    var readySeats by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var banner by remember { mutableStateOf<String?>(null) }
    var chatLines by remember { mutableStateOf<List<ChatLine>>(emptyList()) }
    var chatDraft by remember { mutableStateOf("") }
    var chatSeenCount by remember { mutableIntStateOf(0) }
    val unreadChat by remember { derivedStateOf { (chatLines.size - chatSeenCount).coerceAtLeast(0) } }

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
        chatSeenCount = 0
        previousState = null
        displayBoard = null
        pendingUpdate = null
        lastMove = null
        explodingAtoms = emptyList()
    }

    suspend fun playerName(s: Int): String = players.firstOrNull { it.index == s }?.name ?: getString(Res.string.online_seat_fallback, s)
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
                is ServerMessage.WatchStarted -> {
                    seat = -1
                    players = msg.players
                    readySeats = emptySet()
                    val watchState = msg.state
                    stage = Stage.Watching(msg.code, msg.maxSeats, watchState)
                    if (watchState != null) {
                        previousState = watchState
                        displayBoard = watchState.board
                    }
                }
                is ServerMessage.RoomList -> {
                    roomList = msg.rooms
                }
                is ServerMessage.PlayerJoined -> {
                    players = (players.filter { it.index != msg.player.index } + msg.player)
                        .sortedBy { it.index }
                }
                is ServerMessage.PlayerLeft -> {
                    val leavingName = playerName(msg.seat)
                    players = players.filter { it.index != msg.seat }
                    readySeats = readySeats - msg.seat
                    banner = getString(Res.string.online_player_left, leavingName)
                }
                is ServerMessage.PlayerDisconnected -> {
                    banner = getString(Res.string.online_player_disconnected, playerName(msg.seat), msg.graceSeconds)
                }
                is ServerMessage.PlayerRejoined -> {
                    banner = getString(Res.string.online_player_rejoined, playerName(msg.seat))
                }
                is ServerMessage.PlayerReady -> {
                    readySeats = readySeats + msg.seat
                }
                is ServerMessage.PlayerNotReady -> {
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
                    val current = stage
                    stage = if (current is Stage.Watching) {
                        current.copy(state = msg.state)
                    } else {
                        Stage.Playing(msg.state)
                    }
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
        val current = stage
        stage = if (current is Stage.Watching) {
            current.copy(state = upd.state)
        } else {
            Stage.Playing(upd.state)
        }
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
        Text(stringResource(Res.string.screen_online), fontSize = 28.sp, fontWeight = FontWeight.Bold)

        StatusLine(status, linkError)
        banner?.let { Text(it, color = Color(0xFFFFAB91)) }

        when (val s = stage) {
            Stage.Idle -> IdlePanel(
                host = host, onHostChange = { host = it },
                nickname = nickname, onNicknameChange = { nickname = it },
                seats = createSeats, onSeatsChange = { createSeats = it },
                isPrivate = isPrivate, onPrivateChange = { isPrivate = it },
                joinCode = joinCode, onJoinCodeChange = { joinCode = it },
                roomList = roomList,
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
                        nickname = nickname.ifBlank { "Player" },
                        isPrivate = isPrivate
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
                onWatch = { code ->
                    banner = null
                    settings.putString(SettingKeys.RELAY_URL, host)
                    client.connect(host)
                    suspendSendWatch(client = client, code = code)
                },
                onRefreshRooms = {
                    roomList = null
                    settings.putString(SettingKeys.RELAY_URL, host)
                    client.connect(host)
                    suspendSendListRooms(client = client)
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
                    onNotReady = {
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
                    unreadCount = unreadChat,
                    onDraftChange = { chatDraft = it },
                    onSend = {
                        val text = chatDraft.trim()
                        if (text.isNotEmpty()) {
                            client.send(ClientMessage.Chat(text))
                            chatDraft = ""
                            // +1: the server will echo our message back, pre-mark it as seen.
                            chatSeenCount = chatLines.size + 1
                        }
                    },
                    onRead = { chatSeenCount = chatLines.size }
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
                    unreadChat = unreadChat,
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
                    unreadCount = unreadChat,
                    onDraftChange = { chatDraft = it },
                    onSend = {
                        val text = chatDraft.trim()
                        if (text.isNotEmpty()) {
                            client.send(ClientMessage.Chat(text))
                            chatDraft = ""
                            // +1: the server will echo our message back, pre-mark it as seen.
                            chatSeenCount = chatLines.size + 1
                        }
                    },
                    onRead = { chatSeenCount = chatLines.size }
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

            is Stage.Watching -> {
                WatchingPanel(
                    code = s.code,
                    players = players,
                    maxSeats = s.maxSeats,
                    state = s.state,
                    displayBoard = if (s.state != null) (displayBoard ?: s.state.board) else null,
                    animating = animating,
                    lastMove = lastMove,
                    explodingAtoms = explodingAtoms,
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
                    unreadCount = unreadChat,
                    onDraftChange = { chatDraft = it },
                    onSend = {
                        val text = chatDraft.trim()
                        if (text.isNotEmpty()) {
                            client.send(ClientMessage.Chat(text))
                            chatDraft = ""
                            chatSeenCount = chatLines.size + 1
                        }
                    },
                    onRead = { chatSeenCount = chatLines.size }
                )
            }
        }
    }
}

@Composable
private fun StatusLine(status: LinkStatus, error: String?) {
    val (label, color) = when (status) {
        LinkStatus.Idle -> stringResource(Res.string.status_not_connected) to Color.Gray
        LinkStatus.Connecting -> stringResource(Res.string.status_connecting) to Color(0xFFFDD835)
        LinkStatus.Connected -> stringResource(Res.string.status_connected) to Color(0xFF66BB6A)
        LinkStatus.Reconnecting -> stringResource(Res.string.status_reconnecting) to Color(0xFFFDD835)
        LinkStatus.Closed -> stringResource(Res.string.status_disconnected) to Color.Gray
        LinkStatus.Failed -> stringResource(
            Res.string.status_failed,
            error ?: stringResource(Res.string.status_failed_default)
        ) to Color(0xFFE57373)
    }
    Text(label, color = color)
}

@Composable
private fun IdlePanel(
    host: String, onHostChange: (String) -> Unit,
    nickname: String, onNicknameChange: (String) -> Unit,
    seats: Int, onSeatsChange: (Int) -> Unit,
    isPrivate: Boolean, onPrivateChange: (Boolean) -> Unit,
    joinCode: String, onJoinCodeChange: (String) -> Unit,
    roomList: List<RoomInfo>?,
    customLevel: Level?,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onWatch: (String) -> Unit,
    onRefreshRooms: () -> Unit,
    onBack: () -> Unit
) {
    OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        label = { Text(stringResource(Res.string.label_server_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = nickname,
        onValueChange = onNicknameChange,
        label = { Text(stringResource(Res.string.label_nickname)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Text(stringResource(Res.string.label_create_room), fontWeight = FontWeight.Bold)
    if (customLevel != null) {
        Text(
            stringResource(
                Res.string.online_custom_level,
                customLevel.width,
                customLevel.height,
                customLevel.blocked.size
            ),
            color = Color(0xFF90CAF9)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2, 3, 4).forEach { n ->
            FilterChip(
                selected = seats == n,
                onClick = { onSeatsChange(n) },
                label = { Text(stringResource(Res.string.online_seats_chip, n)) }
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Checkbox(
            checked = isPrivate,
            onCheckedChange = onPrivateChange
        )
        Text(stringResource(Res.string.online_private_room_hint))
    }
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.online_create_room_btn, seats))
    }

    Spacer(Modifier.height(4.dp))
    Text(stringResource(Res.string.label_join_room_by_code), fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = joinCode,
        onValueChange = { onJoinCodeChange(it.filter { c -> c.isDigit() }.take(6)) },
        label = { Text(stringResource(Res.string.label_join_code)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = onJoin,
        enabled = joinCode.length == 6,
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(Res.string.btn_join)) }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(Res.string.label_public_rooms), fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onRefreshRooms) { Text(stringResource(Res.string.btn_refresh)) }
    }
    when {
        roomList == null -> Text(stringResource(Res.string.online_rooms_hint_refresh), color = Color.Gray)
        roomList.isEmpty() -> Text(stringResource(Res.string.online_rooms_empty), color = Color.Gray)
        else -> roomList.forEach { room ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1D21))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(room.code, fontWeight = FontWeight.Bold)
                    Text(
                        if (room.inProgress)
                            stringResource(Res.string.online_room_in_progress, room.playerCount, room.maxSeats)
                        else
                            stringResource(Res.string.online_room_waiting, room.playerCount, room.maxSeats),
                        color = if (room.inProgress) Color(0xFF66BB6A) else Color(0xFFFDD835),
                        fontSize = 12.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!room.inProgress) {
                        OutlinedButton(
                            onClick = { onJoinCodeChange(room.code); onJoin() }
                        ) { Text(stringResource(Res.string.btn_join)) }
                    }
                    Button(onClick = { onWatch(room.code) }) { Text(stringResource(Res.string.btn_watch)) }
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.btn_back)) }
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
    onNotReady: () -> Unit,
    onLeave: () -> Unit
) {
    Text(stringResource(Res.string.label_room_code), color = Color.Gray)
    Text(code, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    Text(
        stringResource(Res.string.online_share_code_hint),
        color = Color.Gray
    )

    Text(
        stringResource(Res.string.online_seats_status, players.size, maxSeats, readySeats.size, maxSeats),
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
                    if (p.index == seat) append(" ${stringResource(Res.string.online_you)}")
                },
                fontWeight = if (p.index == seat) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(end = 8.dp)
            )
            if (p.index in readySeats) {
                Text(stringResource(Res.string.online_ready), color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
            } else {
                Text(stringResource(Res.string.online_waiting), color = Color.Gray)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ready) {
            OutlinedButton(onClick = onNotReady) { Text(stringResource(Res.string.btn_cancel_ready)) }
        } else {
            Button(onClick = onReady) { Text(stringResource(Res.string.btn_im_ready)) }
        }
        OutlinedButton(onClick = onLeave) { Text(stringResource(Res.string.btn_leave)) }
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
    unreadChat: Int,
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
                Text(stringResource(Res.string.online_you_name, it.name))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unreadChat > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFDD835))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "💬 $unreadChat",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                if (yourTurn) stringResource(Res.string.online_your_turn) else stringResource(Res.string.online_turn, turn.name),
                color = if (yourTurn) Color(0xFF66BB6A) else Color.Gray,
                fontWeight = if (yourTurn) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    BoardView(
        state = render,
        onCellTap = onTap,
        lastMove = lastMove,
        explodingAtoms = explodingAtoms,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onLeave) { Text(stringResource(Res.string.btn_leave_room)) }
}

@Composable
private fun GameOverPanel(
    state: GameState,
    winnerSeat: Int,
    onBack: () -> Unit
) {
    val winner = state.players[winnerSeat]
    Text(
        stringResource(Res.string.game_winner, winner.name),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Color(winner.color.toInt())
    )
    BoardView(state = state, onCellTap = {}, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Button(onClick = onBack) { Text(stringResource(Res.string.btn_back_to_lobby)) }
}

@Composable
private fun ChatPanel(
    lines: List<ChatLine>,
    draft: String,
    selfSeat: Int,
    unreadCount: Int,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRead: () -> Unit
) {
    val scrollState = rememberScrollState()
    val windowInfo = LocalWindowInfo.current

    // Separate flag that captures whether the user was at the bottom *before* new content arrives.
    // isAtBottom from derivedStateOf can flip false when maxValue grows with new content,
    // causing auto-scroll to miss. autoScrollEnabled is only changed by user scroll interactions.
    var autoScrollEnabled by remember { mutableStateOf(true) }

    val isAtBottom by remember {
        derivedStateOf {
            scrollState.value >= scrollState.maxValue - SCROLL_BOTTOM_THRESHOLD_PX || scrollState.maxValue == 0
        }
    }

    // Sync autoScrollEnabled with user reaching / leaving the bottom.
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) autoScrollEnabled = true
    }

    // Track whether this panel is visible in the outer viewport.
    var panelYInWindow by remember { mutableIntStateOf(0) }
    var panelHeight by remember { mutableIntStateOf(0) }
    val isPanelVisible by remember {
        derivedStateOf {
            val windowHeight = windowInfo.containerSize.height
            val visibleTop = panelYInWindow.coerceAtLeast(0)
            val visibleBottom = (panelYInWindow + panelHeight).coerceAtMost(windowHeight)
            visibleBottom - visibleTop >= CHAT_VISIBLE_THRESHOLD_PX
        }
    }

    // Auto-scroll to the latest message when the user was at the bottom before new content arrived.
    // Use Int.MAX_VALUE so Compose clamps to the real maxValue after the new content is laid out.
    LaunchedEffect(lines.size) {
        if (autoScrollEnabled) scrollState.animateScrollTo(Int.MAX_VALUE)
        else autoScrollEnabled = false // user scrolled up — keep auto-scroll disabled
    }

    // Notify parent when all messages are in view (panel visible + inner scroll at bottom).
    LaunchedEffect(isAtBottom, isPanelVisible) {
        if (isAtBottom && isPanelVisible) onRead()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                panelYInWindow = coords.positionInWindow().y.toInt()
                panelHeight = coords.size.height
            }
    ) {
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(Res.string.label_chat), fontWeight = FontWeight.Bold)
            if (unreadCount > 0 && (!isAtBottom || !isPanelVisible)) {
                Text(
                    stringResource(Res.string.chat_new_count, unreadCount),
                    color = Color(0xFFFDD835),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1D21))
                .verticalScroll(scrollState)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (lines.isEmpty()) {
                Text(stringResource(Res.string.chat_empty), color = Color.Gray, fontSize = 12.sp)
            } else {
                lines.forEach { line ->
                    Row {
                        Text(
                            text = line.name + (if (line.seat == selfSeat) " ${stringResource(Res.string.online_you)}" else "") + ": ",
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
                placeholder = { Text(stringResource(Res.string.chat_placeholder)) },
                maxLines = 4,
                // IME (soft keyboard) send action — covers Android/iOS virtual keyboards.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    // Hardware keyboard Enter — covers desktop and physical keyboards.
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            !event.isShiftPressed
                        ) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    }
            )
            Button(onClick = onSend, enabled = draft.isNotBlank()) { Text(stringResource(Res.string.btn_send)) }
        }
    }
}

@Composable
private fun WatchingPanel(
    code: String,
    players: List<Player>,
    maxSeats: Int,
    state: GameState?,
    displayBoard: Board?,
    animating: Boolean,
    lastMove: Pos?,
    explodingAtoms: List<ExplodingAtom>,
    onLeave: () -> Unit
) {
    Text(stringResource(Res.string.online_watching_room, code), fontWeight = FontWeight.Bold, fontSize = 20.sp)

    if (state == null) {
        // Pre-game: show who's in the room waiting for game to start.
        Text(
            stringResource(Res.string.online_watching_waiting, players.size, maxSeats),
            color = Color.Gray
        )
        players.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .width(14.dp).height(14.dp)
                        .clip(CircleShape)
                        .background(Color(p.color.toInt()))
                )
                Text(p.name)
            }
        }
    } else {
        // In-game: show the board read-only.
        val render = remember(state, displayBoard) { state.copy(board = displayBoard ?: state.board) }
        val turn = state.currentPlayer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(Res.string.online_spectating), color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .width(14.dp).height(14.dp)
                        .clip(CircleShape)
                        .background(Color(turn.color.toInt()))
                )
                Text(stringResource(Res.string.online_turn, turn.name), color = Color.Gray)
            }
        }
        BoardView(
            state = render,
            onCellTap = {},
            lastMove = lastMove,
            explodingAtoms = explodingAtoms,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }
    OutlinedButton(onClick = onLeave) { Text(stringResource(Res.string.btn_stop_watching)) }
}

/** Spawns a coroutine on [MatchClientHolder.instance]'s own scope (via its
 *  [send] / [connect] lambdas) that waits for a Connected link then fires the
 *  CreateRoom request. Kept out of the composable body so scope ownership is
 *  unambiguous. */
private fun suspendSendCreate(
    client: dev.atomic.app.online.MatchClient,
    level: Level,
    seats: Int,
    nickname: String,
    isPrivate: Boolean = false
) {
    client.scheduleAfterConnect {
        send(
            ClientMessage.CreateRoom(
                level = level,
                settings = GameSettings(),
                seats = seats,
                nickname = nickname,
                isPrivate = isPrivate
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

private fun suspendSendWatch(
    client: dev.atomic.app.online.MatchClient,
    code: String
) {
    client.scheduleAfterConnect {
        send(ClientMessage.WatchRoom(code = code))
    }
}

private fun suspendSendListRooms(client: dev.atomic.app.online.MatchClient) {
    client.scheduleAfterConnect {
        send(ClientMessage.ListRooms)
    }
}
