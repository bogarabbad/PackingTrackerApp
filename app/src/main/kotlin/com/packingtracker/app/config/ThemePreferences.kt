package com.packingtracker.app.config

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode {
    LIGHT,
    DARK,
}

class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppThemeMode {
        val raw = prefs.getString(KEY_MODE, MODE_LIGHT) ?: MODE_LIGHT
        return if (raw == MODE_DARK) AppThemeMode.DARK else AppThemeMode.LIGHT
    }

    fun save(mode: AppThemeMode) {
        prefs.edit()
            .putString(KEY_MODE, if (mode == AppThemeMode.DARK) MODE_DARK else MODE_LIGHT)
            .apply()
    }

    fun apply(mode: AppThemeMode = load()) {
        val nightMode = when (mode) {
            AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    companion object {
        private const val PREFS_NAME = "packing_tracker_theme"
        private const val KEY_MODE = "theme_mode"
        private const val MODE_LIGHT = "light"
        private const val MODE_DARK = "dark"
    }
}
