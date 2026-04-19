package dev.atomic.app.settings

import java.util.prefs.Preferences

private class PrefsSettings(private val node: Preferences) : AppSettings {
    override fun getString(key: String, default: String): String = node.get(key, default)
    override fun putString(key: String, value: String) {
        node.put(key, value)
        node.flush()
    }
}

actual fun createAppSettings(): AppSettings =
    PrefsSettings(Preferences.userRoot().node("dev/atomic/app"))
