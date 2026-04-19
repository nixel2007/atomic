package dev.atomic.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.atomic.app.App
import dev.atomic.app.settings.initAndroidSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initAndroidSettings(this)
        setContent { App() }
    }
}
