package com.packingtracker.app.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.packingtracker.app.MainActivity
import com.packingtracker.app.R
import com.packingtracker.app.api.ApiException
import com.packingtracker.app.api.LoginRequest
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.auth.UserSession
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.config.ConfigActivity
import com.packingtracker.app.databinding.ActivityLoginBinding
import com.packingtracker.app.ui.UserSessionBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = AppConfigStore(this)
        sessionStore = UserSessionStore(this)

        binding.loginButton.setOnClickListener { submitLogin() }
        binding.loginConfigButton.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        UserSessionBar.bind(binding.userSession)
        val config = configStore.load()
        if (!config.enrolled) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
            finish()
        }
    }

    private fun submitLogin() {
        val config = configStore.load()
        val codigo = binding.codigoInput.text?.toString().orEmpty().trim()
        val contrasena = binding.contrasenaInput.text?.toString().orEmpty()

        binding.codigoLayout.error = null
        binding.contrasenaLayout.error = null

        if (codigo.isBlank()) {
            binding.codigoLayout.error = getString(R.string.login_codigo_required)
            return
        }
        if (contrasena.isBlank()) {
            binding.contrasenaLayout.error = getString(R.string.login_contrasena_required)
            return
        }
        if (config.serverUrl.isBlank()) {
            Toast.makeText(this, R.string.config_server_url_required, Toast.LENGTH_LONG).show()
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(config.serverUrl).login(
                        LoginRequest(codigo = codigo, contrasena = contrasena)
                    )
                }
            }
            setBusy(false)

            result.onSuccess { login ->
                val expiresAt = System.currentTimeMillis() + (login.expiresInSeconds * 1000L)
                sessionStore.save(
                    UserSession(
                        accessToken = login.accessToken,
                        expiresAtEpochMs = expiresAt,
                        usuarioId = login.usuarioId,
                        usuarioCodigo = login.usuarioCodigo,
                        usuarioNombre = login.usuarioNombre,
                        usuarioUbicacionId = login.usuarioUbicacionId,
                    )
                )
                startActivity(
                    Intent(this@LoginActivity, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                )
                finish()
            }.onFailure { error ->
                val message = when {
                    error is ApiException && error.code == "invalid_credentials" ->
                        getString(R.string.login_error_credentials)
                    error is ApiException ->
                        getString(R.string.login_error_generic, error.code)
                    else -> getString(R.string.login_error_network)
                }
                Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.loginProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !busy
        binding.loginConfigButton.isEnabled = !busy
        binding.codigoInput.isEnabled = !busy
        binding.contrasenaInput.isEnabled = !busy
    }
}
