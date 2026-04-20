package dev.atomic.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val root = document.getElementById("composeAppRoot")
        ?: error("index.html is missing the #composeAppRoot host element")
    ComposeViewport(root) { App() }
}
