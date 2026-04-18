package dev.atomic.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.atomic.app.screens.EditorScreen
import dev.atomic.app.screens.GameScreen
import dev.atomic.app.screens.MainMenuScreen
import dev.atomic.app.screens.OnlineScreen
import dev.atomic.app.screens.SetupScreen

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface {
            var screen by remember { mutableStateOf<Screen>(Screen.Menu) }
            val nav = object : Navigator {
                override fun go(next: Screen) { screen = next }
                override fun back() { screen = Screen.Menu }
            }
            when (val s = screen) {
                Screen.Menu -> MainMenuScreen(nav)
                is Screen.Setup -> SetupScreen(nav, s.mode)
                is Screen.Game -> GameScreen(nav, s.config)
                Screen.Online -> OnlineScreen(nav)
                Screen.Editor -> EditorScreen(nav)
            }
        }
    }
}
