package com.packingtracker.app.auth

import android.content.Context

data class UserSession(
    val accessToken: String = "",
    val expiresAtEpochMs: Long = 0L,
    val usuarioId: Int? = null,
    val usuarioCodigo: String? = null,
    val usuarioNombre: String? = null,
) {
    fun isValid(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (accessToken.isBlank()) return false
        if (expiresAtEpochMs <= 0L) return true
        return nowEpochMs < expiresAtEpochMs
    }
}

class UserSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): UserSession = UserSession(
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, "") ?: "",
        expiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, 0L),
        usuarioId = prefs.getIntOrNull(KEY_USUARIO_ID),
        usuarioCodigo = prefs.getString(KEY_USUARIO_CODIGO, null),
        usuarioNombre = prefs.getString(KEY_USUARIO_NOMBRE, null),
    )

    fun save(session: UserSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochMs)
            .putIntOrClear(KEY_USUARIO_ID, session.usuarioId)
            .putString(KEY_USUARIO_CODIGO, session.usuarioCodigo)
            .putString(KEY_USUARIO_NOMBRE, session.usuarioNombre)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
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
        private const val PREFS_NAME = "packing_tracker_user_session"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USUARIO_ID = "usuario_id"
        private const val KEY_USUARIO_CODIGO = "usuario_codigo"
        private const val KEY_USUARIO_NOMBRE = "usuario_nombre"
    }
}
