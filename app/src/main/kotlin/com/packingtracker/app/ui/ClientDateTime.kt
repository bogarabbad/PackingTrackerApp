package com.packingtracker.app.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object ClientDateTime {
    private val displayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    private val localParsers = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    )

    fun format(raw: String?, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""

        val zoned = parseToZoned(value, zoneId) ?: return value
        return displayFormatter.format(zoned.withZoneSameInstant(zoneId))
    }

    private fun parseToZoned(value: String, zoneId: ZoneId): ZonedDateTime? {
        try {
            return Instant.parse(value).atZone(zoneId)
        } catch (_: DateTimeParseException) {
            // continue
        }

        try {
            return OffsetDateTime.parse(value).toZonedDateTime()
        } catch (_: DateTimeParseException) {
            // continue
        }

        try {
            return ZonedDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            // continue
        }

        val normalized = value.replace(' ', 'T')
        for (parser in localParsers) {
            try {
                return LocalDateTime.parse(normalized, parser).atZone(ZoneId.of("UTC"))
            } catch (_: DateTimeParseException) {
                // try next
            }
        }

        return null
    }
}
