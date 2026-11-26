package com.packingtracker.app

import android.app.Application
import com.packingtracker.app.config.DeviceIdentityStore
import com.packingtracker.app.config.ThemePreferences

class PackingTrackerApp : Application() {
    lateinit var deviceUuid: String
        private set

    override fun onCreate() {
        super.onCreate()
        ThemePreferences(this).apply()
        deviceUuid = DeviceIdentityStore(this).getOrCreate()
    }
}
