package dev.atomic.app.settings

/**
 * Tiny per-user preference store. Platform-backed by SharedPreferences
 * (Android), java.util.prefs (Desktop) and NSUserDefaults (iOS). No secrets
 * — just UX state the app wants to remember between launches.
 */
interface AppSettings {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int =
        getString(key, default.toString()).toIntOrNull() ?: default
    fun putInt(key: String, value: Int) = putString(key, value.toString())
}

expect fun createAppSettings(): AppSettings

object AppSettingsHolder {
    val instance: AppSettings by lazy { createAppSettings() }
}

object SettingKeys {
    const val RELAY_URL = "relay.url"
    const val NICKNAME = "player.nickname"
    const val LAST_BOARD_W = "last.board.w"
    const val LAST_BOARD_H = "last.board.h"
    const val LAST_PLAYERS = "last.players"
    const val LAST_EXPLOSION = "last.explosion"
    const val LAST_DIFFICULTY = "last.difficulty"
}
