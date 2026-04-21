package dev.atomic.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atomic.composeapp.generated.resources.*
import dev.atomic.app.GameMode
import dev.atomic.app.Navigator
import dev.atomic.app.Screen
import org.jetbrains.compose.resources.stringResource

@Composable
fun MainMenuScreen(nav: Navigator) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Atomic", fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.app_subtitle), fontSize = 16.sp)
        Spacer(Modifier.height(48.dp))
        MenuButton(stringResource(Res.string.menu_hot_seat)) { nav.go(Screen.Setup(GameMode.HotSeat)) }
        MenuButton(stringResource(Res.string.menu_vs_ai)) { nav.go(Screen.Setup(GameMode.VsBot)) }
        MenuButton(stringResource(Res.string.menu_online)) { nav.go(Screen.Online()) }
        MenuButton(stringResource(Res.string.menu_level_editor)) { nav.go(Screen.Editor) }
        MenuButton(stringResource(Res.string.menu_how_to_play)) { nav.go(Screen.Help) }
    }
}

@Composable
private fun MenuButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 220.dp).padding(vertical = 6.dp)
    ) { Text(label) }
}
