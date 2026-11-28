package com.packingtracker.app.api

data class Expedicion(
    val id: Int,
    val idUnidad: Int,
    val fechaCierre: String?,
    val idUbicacionSalida: Int?,
    val ubicacionSalidaId: Int?,
    val usuarioId: Int?,
    val idOrigenUsuario: Int?,
    val creadoEn: String,
    val actualizadoEn: String,
) {
    val abierta: Boolean
        get() = fechaCierre.isNullOrBlank()
}

data class ExpedicionLote(
    val id: Int,
    val idExpedicion: Int,
    val idLote: Int,
    val embarcadoEn: String?,
    val usuarioId: Int?,
    val idOrigenUsuario: Int?,
    val creadoEn: String,
    val actualizadoEn: String,
)

data class ExpedicionCargaResumenPorTipo(
    val idTipo: Int,
    val codigo: String,
    val nombre: String,
    val esFinal: Boolean,
    val lotes: Int,
    val productos: Int,
)

data class ExpedicionCargaResumen(
    val lotes: Int,
    val productos: Int,
    val porTipo: List<ExpedicionCargaResumenPorTipo> = emptyList(),
)

data class ExpedicionCargaLoteTipo(
    val id: Int,
    val codigo: String,
    val nombre: String,
    val esFinal: Boolean,
)

data class ExpedicionCargaLote(
    val id: Int,
    val idTipo: Int,
    val padreId: Int?,
    val idUnidad: Int?,
    val embarqueId: Int?,
    val embarcadoEn: String?,
    val embarcadoDirecto: Boolean,
    val tipo: ExpedicionCargaLoteTipo?,
)

data class ExpedicionCarga(
    val expedicion: Expedicion,
    val resumen: ExpedicionCargaResumen,
    val lotes: List<ExpedicionCargaLote> = emptyList(),
)

data class CreateExpedicionRequest(
    val idUnidad: Int,
    val fechaCierre: String? = null,
    val idUbicacionSalida: Int? = null,
    val ubicacionSalidaId: Int? = null,
    val usuarioId: Int? = null,
    val idOrigenUsuario: Int? = null,
)

data class UpdateExpedicionRequest(
    val idUnidad: Int,
    val fechaCierre: String? = null,
    val idUbicacionSalida: Int? = null,
    val ubicacionSalidaId: Int? = null,
    val usuarioId: Int? = null,
    val idOrigenUsuario: Int? = null,
)

data class CreateExpedicionLoteRequest(
    val idExpedicion: Int,
    val idLote: Int,
    val embarcadoEn: String? = null,
    val usuarioId: Int? = null,
    val idOrigenUsuario: Int? = null,
)
