package dev.atomic.app.settings

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "atomic.settings"

private var appContext: Context? = null

/**
 * Must be called once before any settings read/write on Android. Called from
 * the host Activity (application context is captured, so the Activity itself
 * can be recycled safely).
 */
fun initAndroidSettings(context: Context) {
    appContext = context.applicationContext
}

private class SharedPrefsSettings(private val prefs: SharedPreferences) : AppSettings {
    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

actual fun createAppSettings(): AppSettings {
    val ctx = requireNotNull(appContext) {
        "initAndroidSettings must be called from MainActivity before accessing AppSettings"
    }
    return SharedPrefsSettings(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
}
