package com.packingtracker.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.config.ConfigActivity
import com.packingtracker.app.databinding.ActivityMainBinding
import com.packingtracker.app.empaque.EmpaqueActivity
import com.packingtracker.app.login.LoginActivity
import com.packingtracker.app.ui.UserSessionBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = AppConfigStore(this)
        sessionStore = UserSessionStore(this)

        binding.gateConfigButton.setOnClickListener { openConfig() }
        binding.homeEmpaqueButton.setOnClickListener { openEmpaque() }
        binding.menuButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.drawerContent.drawerEmpaqueButton.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            openEmpaque()
        }
        binding.drawerContent.drawerConfigButton.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            openConfig()
        }
        binding.drawerContent.drawerLogoutButton.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            logoutUser()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        resolveStartup()
    }

    private fun openConfig() {
        startActivity(Intent(this, ConfigActivity::class.java))
    }

    private fun openEmpaque() {
        startActivity(Intent(this, EmpaqueActivity::class.java))
    }

    private fun logoutUser() {
        sessionStore.clear()
        Toast.makeText(this, R.string.nav_logout_done, Toast.LENGTH_SHORT).show()
        resolveStartup()
    }

    private fun resolveStartup() {
        val config = configStore.load()
        if (!config.enrolled || config.token.isBlank() || config.serverUrl.isBlank()) {
            showGate()
            return
        }

        showLoading()
        lifecycleScope.launch {
            val sessionResult = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(config.serverUrl).getDeviceSession(config.token)
                }
            }

            sessionResult.onSuccess { session ->
                val updated = config.copy(
                    enrolled = true,
                    dispositivoId = session.dispositivoId,
                    dispositivoNombre = session.dispositivoNombre,
                    tokenNombre = session.tokenNombre,
                    requiereAuth = session.requiereAuth,
                )
                configStore.save(updated)

                if (session.requiereAuth) {
                    val userSession = sessionStore.load()
                    if (!userSession.isValid()) {
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        return@onSuccess
                    }
                    showHome(userLabel = userSession.usuarioNombre ?: userSession.usuarioCodigo)
                } else {
                    sessionStore.clear()
                    showHome(userLabel = null)
                }
            }.onFailure {
                showGate()
                Toast.makeText(
                    this@MainActivity,
                    R.string.startup_session_error,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showLoading() {
        binding.topBar.visibility = View.GONE
        binding.loadingContainer.visibility = View.VISIBLE
        binding.gateContainer.visibility = View.GONE
        binding.homeContainer.visibility = View.GONE
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    private fun showGate() {
        binding.topBar.visibility = View.GONE
        binding.loadingContainer.visibility = View.GONE
        binding.gateContainer.visibility = View.VISIBLE
        binding.homeContainer.visibility = View.GONE
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    private fun showHome(userLabel: String?) {
        binding.topBar.visibility = View.VISIBLE
        binding.loadingContainer.visibility = View.GONE
        binding.gateContainer.visibility = View.GONE
        binding.homeContainer.visibility = View.VISIBLE
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)

        val hasUser = !userLabel.isNullOrBlank()
        binding.drawerContent.drawerLogoutButton.visibility =
            if (hasUser) View.VISIBLE else View.GONE
        UserSessionBar.bind(binding.userSession)
    }
}
