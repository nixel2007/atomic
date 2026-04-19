package dev.atomic.app.settings

import platform.Foundation.NSUserDefaults

private class UserDefaultsSettings(private val defaults: NSUserDefaults) : AppSettings {
    override fun getString(key: String, default: String): String =
        defaults.stringForKey(key) ?: default

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}

actual fun createAppSettings(): AppSettings =
    UserDefaultsSettings(NSUserDefaults.standardUserDefaults)
