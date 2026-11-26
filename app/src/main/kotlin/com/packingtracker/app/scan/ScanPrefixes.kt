package com.packingtracker.app.scan

/**
 * Catálogo estático API ↔ Android (`PrefijosEscaneo` en openapi.yaml).
 * Solo uso interno: no se muestra al usuario.
 */
enum class ScanAction {
    ENROLLMENT,
}

data class ScanPrefix(
    val prefix: String,
    val action: ScanAction,
)

data class ParsedScan(
    val action: ScanAction,
    val payload: String,
)

object ScanPrefixes {
    val ENROLLMENT = ScanPrefix(
        prefix = "PTENR|",
        action = ScanAction.ENROLLMENT,
    )

    val all: List<ScanPrefix> = listOf(ENROLLMENT)

    fun parse(raw: String): ParsedScan? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val match = all.firstOrNull { value.startsWith(it.prefix) } ?: return null
        val payload = value.removePrefix(match.prefix)
        if (payload.isEmpty()) return null
        return ParsedScan(action = match.action, payload = payload)
    }

    /** Quita el prefijo de enrollment si viene; si no, deja el valor. */
    fun normalizeEnrollmentToken(raw: String): String {
        val value = raw.trim()
        return if (value.startsWith(ENROLLMENT.prefix)) {
            value.removePrefix(ENROLLMENT.prefix)
        } else {
            value
        }
    }
}
