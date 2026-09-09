package dev.onlookermonitor.app.core

import android.content.Context
import dev.onlookermonitor.app.overlay.PrivacyShieldMode

object MonitorPreferences {
    private const val PREFS_NAME = "onlooker_monitor_prefs"
    private const val KEY_SHIELD_MODE = "privacy_shield_mode"

    fun getShieldMode(context: Context): PrivacyShieldMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SHIELD_MODE, PrivacyShieldMode.BLACK_SCREEN.name)
        return runCatching { PrivacyShieldMode.valueOf(name ?: "") }.getOrDefault(PrivacyShieldMode.BLACK_SCREEN)
    }

    fun setShieldMode(context: Context, mode: PrivacyShieldMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SHIELD_MODE, mode.name).apply()
    }
}
