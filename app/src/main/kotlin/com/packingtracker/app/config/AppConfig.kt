package com.packingtracker.app.config

data class AppConfig(
    val serverUrl: String = "",
    val token: String = "",
    val enrolled: Boolean = false,
    val dispositivoId: Int? = null,
    val dispositivoNombre: String? = null,
    val tokenNombre: String? = null,
    val requiereAuth: Boolean = true,
)
