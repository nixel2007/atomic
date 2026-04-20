package dev.atomic.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atomic.composeapp.generated.resources.Res
import atomic.composeapp.generated.resources.btn_close
import atomic.composeapp.generated.resources.help_capture_desc
import atomic.composeapp.generated.resources.help_capture_headline
import atomic.composeapp.generated.resources.help_critical_mass_desc
import atomic.composeapp.generated.resources.help_critical_mass_headline
import atomic.composeapp.generated.resources.help_placement_desc
import atomic.composeapp.generated.resources.help_placement_headline
import atomic.composeapp.generated.resources.help_tab_capture
import atomic.composeapp.generated.resources.help_tab_critical_mass
import atomic.composeapp.generated.resources.help_tab_placement
import atomic.composeapp.generated.resources.help_tab_victory
import atomic.composeapp.generated.resources.help_victory_desc
import atomic.composeapp.generated.resources.help_victory_headline
import atomic.composeapp.generated.resources.screen_help
import dev.atomic.app.Navigator
import dev.atomic.app.game.BoardView
import dev.atomic.shared.engine.Board
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.engine.PlayerKind
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A single cell to pre-populate in an illustration board. */
private data class CellSnapshot(val pos: Pos, val ownerIndex: Int, val count: Int)

private val HELP_PLAYERS = listOf(
    Player(0, "P1", 0xFFE53935L, PlayerKind.Human),
    Player(1, "P2", 0xFF1E88E5L, PlayerKind.Human),
)

private val HELP_PLAYERS_4 = listOf(
    Player(0, "P1", 0xFFE53935L, PlayerKind.Human),
    Player(1, "P2", 0xFF1E88E5L, PlayerKind.Human),
    Player(2, "P3", 0xFF43A047L, PlayerKind.Human),
    Player(3, "P4", 0xFFFDD835L, PlayerKind.Human),
)

/** Creates a static [GameState] for illustration purposes. */
private fun illustrationState(
    width: Int,
    height: Int,
    cells: List<CellSnapshot>,
    players: List<Player> = HELP_PLAYERS,
    currentPlayer: Int = 0,
): GameState {
    val level = Level.rectangular("help", "help", width, height)
    val n = width * height
    val owners = IntArray(n) { Board.NO_OWNER }
    val counts = IntArray(n) { 0 }
    for ((pos, owner, count) in cells) {
        val idx = pos.y * width + pos.x
        owners[idx] = owner
        counts[idx] = count
    }
    return GameState(
        level = level,
        settings = GameSettings(),
        players = players,
        board = Board(width, height, owners.toList(), counts.toList()),
        currentPlayerIndex = currentPlayer,
        turnsPlayed = 0,
    )
}

private enum class HelpTab(val titleRes: StringResource) {
    Placement(Res.string.help_tab_placement),
    CriticalMass(Res.string.help_tab_critical_mass),
    Capture(Res.string.help_tab_capture),
    Victory(Res.string.help_tab_victory),
}

@Composable
fun HelpScreen(nav: Navigator) {
    var selectedTab by remember { mutableStateOf(HelpTab.Placement) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(Res.string.screen_help), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { nav.back() }) { Text(stringResource(Res.string.btn_close)) }
        }

        Spacer(Modifier.height(4.dp))

        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            HelpTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(stringResource(tab.titleRes)) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (selectedTab) {
                HelpTab.Placement -> PlacementTab()
                HelpTab.CriticalMass -> CriticalMassTab()
                HelpTab.Capture -> CaptureTab()
                HelpTab.Victory -> VictoryTab()
            }
        }
    }
}

@Composable
private fun PlacementTab() {
    // 3×3 board: Red has 1 atom at center (1,1); Blue has 1 atom at (2,2).
    // The empty cell at (0,0) is highlighted using BoardView's last-move styling
    // to draw attention to a legal placement example for Red.
    val state = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                CellSnapshot(Pos(1, 1), ownerIndex = 0, count = 1),
                CellSnapshot(Pos(2, 2), ownerIndex = 1, count = 1),
            )
        )
    }
    RuleSlide(
        headline = stringResource(Res.string.help_placement_headline),
        description = stringResource(Res.string.help_placement_desc),
        content = {
            BoardView(
                state = state,
                onCellTap = {},
                lastMove = Pos(0, 0),
                interactive = false,
                modifier = Modifier.size(180.dp),
            )
        }
    )
}

@Composable
private fun CriticalMassTab() {
    // Left board: corner (0,0) has 2 Red atoms — exactly its critical mass (CM=2).
    // Right board: after explosion — (0,0) is empty; (1,0) and (0,1) each gained 1 Red atom.
    val before = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(CellSnapshot(Pos(0, 0), ownerIndex = 0, count = 2))
        )
    }
    val after = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                CellSnapshot(Pos(1, 0), ownerIndex = 0, count = 1),
                CellSnapshot(Pos(0, 1), ownerIndex = 0, count = 1),
            )
        )
    }
    RuleSlide(
        headline = stringResource(Res.string.help_critical_mass_headline),
        description = stringResource(Res.string.help_critical_mass_desc),
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardView(
                    state = before,
                    onCellTap = {},
                    interactive = false,
                    modifier = Modifier.size(120.dp),
                )
                Text("→", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                BoardView(
                    state = after,
                    onCellTap = {},
                    interactive = false,
                    modifier = Modifier.size(120.dp),
                )
            }
        }
    )
}

@Composable
private fun CaptureTab() {
    // 3×3 example: before the explosion, Blue has 3 atoms at edge cell (1,0)
    // and Red has 1 atom at (0,0). After Blue explodes, its atoms spread to
    // (0,0), (2,0), and (1,1), capturing (0,0) and turning it Blue.
    val before = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                CellSnapshot(Pos(1, 0), ownerIndex = 1, count = 3),  // Blue at critical mass
                CellSnapshot(Pos(0, 0), ownerIndex = 0, count = 1),  // Red about to be captured
            ),
            currentPlayer = 1
        )
    }
    val after = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                CellSnapshot(Pos(0, 0), ownerIndex = 1, count = 2),  // was Red, now Blue
                CellSnapshot(Pos(2, 0), ownerIndex = 1, count = 1),
                CellSnapshot(Pos(1, 1), ownerIndex = 1, count = 1),
            ),
            currentPlayer = 0
        )
    }
    RuleSlide(
        headline = stringResource(Res.string.help_capture_headline),
        description = stringResource(Res.string.help_capture_desc),
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardView(
                    state = before,
                    onCellTap = {},
                    interactive = false,
                    modifier = Modifier.size(120.dp),
                )
                Text("→", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                BoardView(
                    state = after,
                    onCellTap = {},
                    interactive = false,
                    modifier = Modifier.size(120.dp),
                )
            }
        }
    )
}

@Composable
private fun VictoryTab() {
    // 3×3 board fully owned by Red.
    val state = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                CellSnapshot(Pos(0, 0), ownerIndex = 0, count = 1),
                CellSnapshot(Pos(1, 0), ownerIndex = 0, count = 2),
                CellSnapshot(Pos(2, 0), ownerIndex = 0, count = 1),
                CellSnapshot(Pos(0, 1), ownerIndex = 0, count = 2),
                CellSnapshot(Pos(1, 1), ownerIndex = 0, count = 3),
                CellSnapshot(Pos(2, 1), ownerIndex = 0, count = 2),
                CellSnapshot(Pos(0, 2), ownerIndex = 0, count = 1),
                CellSnapshot(Pos(1, 2), ownerIndex = 0, count = 2),
                CellSnapshot(Pos(2, 2), ownerIndex = 0, count = 1),
            ),
            players = HELP_PLAYERS_4,
        )
    }
    RuleSlide(
        headline = stringResource(Res.string.help_victory_headline),
        description = stringResource(Res.string.help_victory_desc),
        content = {
            BoardView(
                state = state,
                onCellTap = {},
                interactive = false,
                modifier = Modifier.size(180.dp),
            )
        }
    )
}

@Composable
private fun RuleSlide(
    headline: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(headline, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        content()
        Text(
            text = description,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

