package com.packingtracker.app.config

import android.content.Context
import java.util.UUID

/**
 * Identificador estable del equipo. Se genera una sola vez y no se vuelve a escribir.
 * Sobrevive a actualizaciones de la app; se pierde solo si se desinstala.
 */
class DeviceIdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        val existing = prefs.getString(KEY_DEVICE_UUID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val created = UUID.randomUUID().toString()
        val saved = prefs.edit()
            .putString(KEY_DEVICE_UUID, created)
            .commit()

        if (!saved) {
            val raced = prefs.getString(KEY_DEVICE_UUID, null)
            if (!raced.isNullOrBlank()) {
                return raced
            }
            error("No se pudo persistir el UUID del equipo")
        }

        // Si otro hilo escribió primero, devolvemos el valor ya guardado.
        return prefs.getString(KEY_DEVICE_UUID, created) ?: created
    }

    companion object {
        const val PREFS_NAME = "packing_tracker_device_identity"
        private const val KEY_DEVICE_UUID = "device_uuid"
    }
}
