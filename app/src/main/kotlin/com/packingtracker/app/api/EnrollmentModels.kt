package com.packingtracker.app.api

data class EnrollmentRequest(
    val token: String,
    val identificador: String,
)

data class Dispositivo(
    val id: Int,
    val idTipo: Int,
    val identificador: String,
    val nombre: String?,
    val activo: Boolean,
    val creadoEn: String,
    val actualizadoEn: String,
)

data class AutorizacionToken(
    val id: Int,
    val idDispositivo: Int?,
    val token: String,
    val nombre: String,
    val activo: Boolean,
    val requiereAuth: Boolean,
    val expiraEn: String?,
    val ultimoUsoEn: String?,
    val creadoEn: String,
    val actualizadoEn: String,
)

data class EnrollmentResult(
    val dispositivo: Dispositivo,
    val token: AutorizacionToken,
    val creado: Boolean,
    val httpStatus: Int,
)

data class DeviceSession(
    val requiereAuth: Boolean,
    val dispositivoId: Int,
    val dispositivoIdentificador: String,
    val dispositivoNombre: String?,
    val tokenId: Int,
    val tokenNombre: String,
)

data class LoginRequest(
    val codigo: String,
    val contrasena: String,
)

data class LoginResult(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Int,
    val usuarioId: Int,
    val usuarioCodigo: String,
    val usuarioNombre: String,
)
