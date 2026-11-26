package com.packingtracker.app.api

data class LoteTipo(
    val id: Int,
    val codigo: String,
    val nombre: String,
    val activo: Boolean,
    val esFinal: Boolean,
)

data class UnidadTransporte(
    val id: Int,
    val codigo: String,
    val placas: String?,
    val numeroEconomico: String?,
    val activo: Boolean,
) {
    fun displayLabel(): String {
        val parts = listOfNotNull(
            codigo.takeIf { it.isNotBlank() },
            placas?.takeIf { it.isNotBlank() }?.let { "Placas $it" },
            numeroEconomico?.takeIf { it.isNotBlank() }?.let { "Eco $it" },
        )
        return parts.joinToString(" · ").ifBlank { "Unidad #$id" }
    }
}

data class Lote(
    val id: Int,
    val idTipo: Int,
    val padreId: Int?,
    val idUnidad: Int?,
    val usuarioId: Int?,
    val idOrigenUsuario: Int?,
    val creadoEn: String,
    val actualizadoEn: String,
)

data class CreateLoteRequest(
    val idTipo: Int,
    val padreId: Int? = null,
    val idUnidad: Int? = null,
    val usuarioId: Int? = null,
    val idOrigenUsuario: Int? = null,
)

data class ApiCredentials(
    val authorizationBearer: String,
    val deviceToken: String? = null,
)

data class PageMeta(
    val page: Int,
    val items: Int,
    val total: Int,
    val pages: Int,
)

data class PagedResult<T>(
    val items: List<T>,
    val meta: PageMeta,
)
