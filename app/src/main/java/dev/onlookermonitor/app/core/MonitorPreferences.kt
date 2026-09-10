package dev.onlookermonitor.app.core

import android.content.Context
import dev.onlookermonitor.app.overlay.PrivacyShieldMode

import dev.onlookermonitor.app.protectedapps.SensitiveAppCatalog

object MonitorPreferences {
    private const val PREFS_NAME = "onlooker_monitor_prefs"
    private const val KEY_SHIELD_MODE = "privacy_shield_mode"
    private const val KEY_PROTECTED_APPS = "protected_app_packages"
    private const val KEY_CAPTURE_ONLOOKERS = "capture_onlookers_enabled"

    fun getShieldMode(context: Context): PrivacyShieldMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SHIELD_MODE, PrivacyShieldMode.BLACK_SCREEN.name)
        return runCatching { PrivacyShieldMode.valueOf(name ?: "") }.getOrDefault(PrivacyShieldMode.BLACK_SCREEN)
    }

    fun setShieldMode(context: Context, mode: PrivacyShieldMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SHIELD_MODE, mode.name).apply()
    }

    fun isCaptureOnlookersEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CAPTURE_ONLOOKERS, false)
    }

    fun setCaptureOnlookersEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CAPTURE_ONLOOKERS, enabled).apply()
    }

    /**
     * The set of package names the user chose to monitor ("protected apps"), or **null when the
     * user has never configured the list**.
     *
     * `null` and an empty set mean different things and must not be conflated:
     *  - `null`  = not configured yet. Fallback to default sensitive apps catalog.
     *  - empty set = the user configured the list and deliberately chose nothing (fail-closed).
     *
     * Prefer [shouldMonitorApp] over reading this directly so the rule stays in one place.
     * Returns a defensive copy; the value stored in [android.content.SharedPreferences] must never
     * be mutated in place.
     */
    fun getProtectedApps(context: Context): Set<String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PROTECTED_APPS, null)?.toSet()
    }

    fun setProtectedApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_PROTECTED_APPS, packages.toSet()).apply()
    }

    /**
     * Whether [pkg] should be monitored. When the protected-apps list has never been
     * configured ([getProtectedApps] is `null`), checks against [SensitiveAppCatalog.getDefaultProtectedApps].
     * Once the user has configured the list, only packages in it return `true`.
     */
    fun shouldMonitorApp(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return true
        val protectedApps = getProtectedApps(context)
        if (protectedApps != null) {
            return protectedApps.contains(pkg)
        }
        return SensitiveAppCatalog.getDefaultProtectedApps(context).contains(pkg)
    }
}
