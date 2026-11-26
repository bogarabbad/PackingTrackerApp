package com.packingtracker.app.config

import android.content.Context

class AppConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig(
        serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
        token = prefs.getString(KEY_TOKEN, "") ?: "",
        enrolled = prefs.getBoolean(KEY_ENROLLED, false),
        dispositivoId = prefs.getIntOrNull(KEY_DISPOSITIVO_ID),
        dispositivoNombre = prefs.getString(KEY_DISPOSITIVO_NOMBRE, null),
        tokenNombre = prefs.getString(KEY_TOKEN_NOMBRE, null),
        requiereAuth = prefs.getBoolean(KEY_REQUIERE_AUTH, true),
    )

    fun save(config: AppConfig) {
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, config.token.trim())
            .remove(KEY_SCAN_PREFIX)
            .putBoolean(KEY_ENROLLED, config.enrolled)
            .putIntOrClear(KEY_DISPOSITIVO_ID, config.dispositivoId)
            .putString(KEY_DISPOSITIVO_NOMBRE, config.dispositivoNombre)
            .putString(KEY_TOKEN_NOMBRE, config.tokenNombre)
            .putBoolean(KEY_REQUIERE_AUTH, config.requiereAuth)
            .apply()
    }

    private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? {
        if (!contains(key)) return null
        val value = getInt(key, -1)
        return if (value > 0) value else null
    }

    private fun android.content.SharedPreferences.Editor.putIntOrClear(
        key: String,
        value: Int?,
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putInt(key, value)
    }

    companion object {
        private const val PREFS_NAME = "packing_tracker_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_SCAN_PREFIX = "scan_prefix"
        private const val KEY_ENROLLED = "enrolled"
        private const val KEY_DISPOSITIVO_ID = "dispositivo_id"
        private const val KEY_DISPOSITIVO_NOMBRE = "dispositivo_nombre"
        private const val KEY_TOKEN_NOMBRE = "token_nombre"
        private const val KEY_REQUIERE_AUTH = "requiere_auth"
    }
}
