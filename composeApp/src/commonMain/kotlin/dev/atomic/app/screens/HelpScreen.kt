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
import dev.atomic.app.Navigator
import dev.atomic.app.game.BoardView
import dev.atomic.shared.engine.Board
import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.engine.GameState
import dev.atomic.shared.engine.Player
import dev.atomic.shared.engine.PlayerKind
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos

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
    cells: List<Triple<Pos, Int, Int>>,   // (pos, ownerIndex, count)
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

private val TABS = listOf("Placement", "Critical Mass", "Capture", "Victory")

@Composable
fun HelpScreen(nav: Navigator) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("How to Play", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { nav.back() }) { Text("Close") }
        }

        Spacer(Modifier.height(4.dp))

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            TABS.forEachIndexed { i, title ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = { Text(title) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (selectedTab) {
                0 -> PlacementTab()
                1 -> CriticalMassTab()
                2 -> CaptureTab()
                3 -> VictoryTab()
            }
        }
    }
}

@Composable
private fun PlacementTab() {
    // 3×3 board: Red has 1 atom at center (1,1); Blue has 1 atom at (2,2).
    // Empty cell (0,0) highlighted as a candidate move for Red.
    val state = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                Triple(Pos(1, 1), 0, 1),
                Triple(Pos(2, 2), 1, 1),
            )
        )
    }
    RuleSlide(
        headline = "Placing an Atom",
        description = "On your turn tap any empty cell or a cell you already own to place one atom there. " +
                "You cannot place on a cell owned by another player.",
        content = {
            BoardView(
                state = state,
                onCellTap = {},
                lastMove = Pos(0, 0),
                modifier = Modifier.size(180.dp),
            )
        }
    )
}

@Composable
private fun CriticalMassTab() {
    // Left board: corner (0,0) has 2 red atoms → at critical mass (CM=2 for corner).
    // Right board: after explosion — (0,0) empty, (1,0) and (0,1) each have 1 red atom.
    val before = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(Triple(Pos(0, 0), 0, 2))
        )
    }
    val after = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                Triple(Pos(1, 0), 0, 1),
                Triple(Pos(0, 1), 0, 1),
            )
        )
    }
    RuleSlide(
        headline = "Critical Mass & Explosion",
        description = "Each cell has a critical mass equal to its number of neighbours: " +
                "2 for corners, 3 for edges, and 4 for interior cells. " +
                "When a cell reaches its critical mass it explodes — losing all its atoms, " +
                "which fly outward to every neighbouring cell.",
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardView(
                    state = before,
                    onCellTap = {},
                    modifier = Modifier.size(120.dp),
                )
                Text("→", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                BoardView(
                    state = after,
                    onCellTap = {},
                    modifier = Modifier.size(120.dp),
                )
            }
        }
    )
}

@Composable
private fun CaptureTab() {
    // Left board: Blue has 2 atoms at edge cell (1,0) (CM=3? no, in 3x3 edge has CM=3).
    // We use a 4×3 board so (1,0) has CM=3. Blue placed a 3rd atom → it explodes.
    // Actually easier: use 3×3. Blue edge cell (1,0) already at 3 atoms (CM=3).
    // But CM=3 needs 3 atoms; and after explosion (1,0) → 0, neighbors (0,0),(2,0),(1,1) each +1.
    // (0,0) was Red 1 atom → becomes Blue.
    // Let's use a simpler 3×2 board where (1,0) is an edge cell (CM=3 — has neighbors (0,0),(2,0),(1,1)):
    // 3×3 board: Blue 3 atoms at (1,0), Red 1 atom at (0,0)
    val before = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                Triple(Pos(1, 0), 1, 3),  // Blue at critical mass
                Triple(Pos(0, 0), 0, 1),  // Red about to be captured
            ),
            currentPlayer = 1
        )
    }
    val after = remember {
        illustrationState(
            width = 3, height = 3,
            cells = listOf(
                Triple(Pos(0, 0), 1, 2),  // was Red, now Blue
                Triple(Pos(2, 0), 1, 1),
                Triple(Pos(1, 1), 1, 1),
            ),
            currentPlayer = 0
        )
    }
    RuleSlide(
        headline = "Capturing Cells",
        description = "Atoms that fly out of an explosion always carry the owner's colour. " +
                "If they land on a cell owned by a different player, that cell and its atoms " +
                "are immediately converted to the attacker's colour — and a chain reaction can follow!",
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardView(
                    state = before,
                    onCellTap = {},
                    modifier = Modifier.size(120.dp),
                )
                Text("→", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                BoardView(
                    state = after,
                    onCellTap = {},
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
                Triple(Pos(0, 0), 0, 1), Triple(Pos(1, 0), 0, 2), Triple(Pos(2, 0), 0, 1),
                Triple(Pos(0, 1), 0, 2), Triple(Pos(1, 1), 0, 3), Triple(Pos(2, 1), 0, 2),
                Triple(Pos(0, 2), 0, 1), Triple(Pos(1, 2), 0, 2), Triple(Pos(2, 2), 0, 1),
            ),
            players = HELP_PLAYERS_4,
        )
    }
    RuleSlide(
        headline = "Victory",
        description = "The last player who still has atoms on the board wins. " +
                "A player is eliminated the moment all of their atoms are captured. " +
                "Games support 2 to 4 players — the more players, the more chain reactions!",
        content = {
            BoardView(
                state = state,
                onCellTap = {},
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
