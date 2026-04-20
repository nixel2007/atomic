package dev.atomic.app.settings

import kotlinx.browser.localStorage

private class LocalStorageSettings : AppSettings {
    override fun getString(key: String, default: String): String =
        localStorage.getItem(key) ?: default

    override fun putString(key: String, value: String) {
        localStorage.setItem(key, value)
    }
}

actual fun createAppSettings(): AppSettings = LocalStorageSettings()
