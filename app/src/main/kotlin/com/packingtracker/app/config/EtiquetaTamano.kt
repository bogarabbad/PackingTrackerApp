package com.packingtracker.app.config

import com.packingtracker.app.R

/**
 * Tamaños de etiqueta física para lotes finales.
 * Las medidas están en milímetros (papel térmico / sticker).
 */
enum class EtiquetaTamanoPreset(
    val anchoMm: Int,
    val altoMm: Int,
    val labelRes: Int,
) {
    MM_50_25(50, 25, R.string.config_etiqueta_size_50x25),
    MM_50_30(50, 30, R.string.config_etiqueta_size_50x30),
    MM_60_40(60, 40, R.string.config_etiqueta_size_60x40),
    MM_100_50(100, 50, R.string.config_etiqueta_size_100x50),
    MM_100_70(100, 70, R.string.config_etiqueta_size_100x70),
    CUSTOM(0, 0, R.string.config_etiqueta_size_custom);

    val isCustom: Boolean get() = this == CUSTOM

    companion object {
        val DEFAULT = MM_100_70

        fun fromMillimeters(anchoMm: Int, altoMm: Int): EtiquetaTamanoPreset {
            return entries.firstOrNull {
                !it.isCustom && it.anchoMm == anchoMm && it.altoMm == altoMm
            } ?: CUSTOM
        }
    }
}

data class EtiquetaTamano(
    val anchoMm: Int = EtiquetaTamanoPreset.DEFAULT.anchoMm,
    val altoMm: Int = EtiquetaTamanoPreset.DEFAULT.altoMm,
) {
    val preset: EtiquetaTamanoPreset
        get() = EtiquetaTamanoPreset.fromMillimeters(anchoMm, altoMm)

    companion object {
        const val MIN_MM = 20
        const val MAX_MM = 200

        fun sanitize(anchoMm: Int, altoMm: Int): EtiquetaTamano {
            return EtiquetaTamano(
                anchoMm = anchoMm.coerceIn(MIN_MM, MAX_MM),
                altoMm = altoMm.coerceIn(MIN_MM, MAX_MM),
            )
        }
    }
}
