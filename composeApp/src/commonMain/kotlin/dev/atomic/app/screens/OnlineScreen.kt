package dev.atomic.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atomic.app.Navigator

@Composable
fun OnlineScreen(nav: Navigator) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Online", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Room code join & creation UI goes here. The relay server and\n" +
                "WebSocket protocol are already implemented; hooking up the client\n" +
                "is the next milestone.",
            modifier = Modifier.padding(vertical = 16.dp)
        )
        OutlinedButton(onClick = { nav.back() }) { Text("Back") }
    }
}
