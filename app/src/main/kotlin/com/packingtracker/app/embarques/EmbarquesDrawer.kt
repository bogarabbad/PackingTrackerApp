package com.packingtracker.app.embarques

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.packingtracker.app.MainActivity
import com.packingtracker.app.databinding.DrawerContentEmbarquesBinding

object EmbarquesDrawer {
    fun bind(
        activity: AppCompatActivity,
        drawerLayout: DrawerLayout,
        toolbar: MaterialToolbar,
        drawerContent: DrawerContentEmbarquesBinding,
        onConsultarExpediciones: () -> Unit,
        onBeforeBack: () -> Boolean = { false },
    ) {
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        drawerContent.drawerCreateExpedicionButton.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        drawerContent.drawerListaExpedicionesButton.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            onConsultarExpediciones()
        }

        drawerContent.drawerHomeButton.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
            activity.finish()
        }

        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        onBeforeBack() -> Unit
                        drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                            drawerLayout.closeDrawer(GravityCompat.START)
                        }
                        else -> {
                            isEnabled = false
                            activity.onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            },
        )
    }
}
