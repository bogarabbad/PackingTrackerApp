package com.packingtracker.app.empaque

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.packingtracker.app.MainActivity
import com.packingtracker.app.databinding.DrawerContentEmpaqueBinding

object EmpaqueDrawer {
    fun bind(
        activity: AppCompatActivity,
        drawerLayout: DrawerLayout,
        toolbar: MaterialToolbar,
        drawerContent: DrawerContentEmpaqueBinding,
        onConsultarLotes: () -> Unit,
    ) {
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        drawerContent.drawerCreateLoteButton.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        drawerContent.drawerListaLotesButton.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            onConsultarLotes()
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
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        isEnabled = false
                        activity.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }
}
