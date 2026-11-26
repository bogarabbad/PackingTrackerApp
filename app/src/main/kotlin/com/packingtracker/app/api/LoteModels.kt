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

enum class LoteEstatus {
    DISPONIBLE,
    CONTENIDO,
    EMBARCADO,
    RECIBIDO,
}

data class LoteDetalle(
    val lote: Lote,
    val estatus: LoteEstatus,
    val expedicionId: Int? = null,
    val recepcionId: Int? = null,
)

data class LoteProducto(
    val id: Int,
    val idLote: Int,
    val claveProducto: String,
    val cantidad: Double,
    val idUnidad: Int,
)

data class LoteReferencia(
    val id: Int,
    val idLote: Int,
    val idTipo: Int,
    val referencia: String,
    val descripcion: String?,
)

data class ReferenciaTipo(
    val id: Int,
    val codigo: String,
    val nombre: String,
    val activo: Boolean,
)

data class UnidadMedida(
    val id: Int,
    val codigo: String,
    val nombre: String,
    val activo: Boolean,
)

data class LoteContenido(
    val productos: List<LoteProducto> = emptyList(),
    val hijos: List<Lote> = emptyList(),
    val referencias: List<LoteReferencia> = emptyList(),
)

data class CreateLoteProductoRequest(
    val idLote: Int,
    val claveProducto: String,
    val cantidad: Double,
    val idUnidad: Int,
)

data class UpdateLoteProductoRequest(
    val idLote: Int,
    val claveProducto: String,
    val cantidad: Double,
    val idUnidad: Int,
)

data class CreateLoteReferenciaRequest(
    val idLote: Int,
    val idTipo: Int,
    val referencia: String,
    val descripcion: String? = null,
    val usuarioId: Int? = null,
    val idOrigenUsuario: Int? = null,
)

data class UpdateLoteReferenciaRequest(
    val idLote: Int,
    val idTipo: Int,
    val referencia: String,
    val descripcion: String? = null,
)

data class LoteTipoUnidad(
    val id: Int,
    val idTipo: Int,
    val idUnidad: Int,
)

data class CreateLoteRequest(
    val idTipo: Int,
    val padreId: Int? = null,
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

data class LoteDeleteBlockers(
    val hasProductos: Boolean = false,
    val hasReferencias: Boolean = false,
    val hasHijos: Boolean = false,
    val hasUbicaciones: Boolean = false,
) {
    val canDelete: Boolean
        get() = !hasProductos && !hasReferencias && !hasHijos && !hasUbicaciones
}
