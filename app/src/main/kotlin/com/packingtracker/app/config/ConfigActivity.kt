package com.packingtracker.app.config

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.packingtracker.app.PackingTrackerApp
import com.packingtracker.app.R
import com.packingtracker.app.api.ApiException
import com.packingtracker.app.api.EnrollmentRequest
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.databinding.ActivityConfigBinding
import com.packingtracker.app.scan.ScanAction
import com.packingtracker.app.scan.ScanPrefixes
import com.packingtracker.app.ui.UserSessionBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class ConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigBinding
    private lateinit var store: AppConfigStore

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startQrScan()
        } else {
            Toast.makeText(this, R.string.config_camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        applyScannedPayload(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = AppConfigStore(this)
        val themePreferences = ThemePreferences(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.deviceUuidInput.setText(deviceUuid())
        binding.appVersionInput.setText(appVersionLabel())

        val config = store.load()
        binding.serverUrlInput.setText(config.serverUrl)
        binding.tokenInput.setText(config.token)
        renderEnrollmentState(config)

        binding.darkThemeSwitch.setOnCheckedChangeListener(null)
        binding.darkThemeSwitch.isChecked = themePreferences.load() == AppThemeMode.DARK
        binding.darkThemeSwitch.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) AppThemeMode.DARK else AppThemeMode.LIGHT
            themePreferences.save(mode)
            themePreferences.apply(mode)
        }

        binding.tokenLayout.setEndIconOnClickListener { requestCameraAndScan() }
        binding.scanQrButton.setOnClickListener { requestCameraAndScan() }
        binding.enrollButton.setOnClickListener { enrollDevice() }
        binding.saveLocalButton.setOnClickListener { saveLocalPreferences() }
    }

    override fun onResume() {
        super.onResume()
        UserSessionBar.bind(binding.userSession)
    }

    private fun applyScannedPayload(raw: String) {
        val parsed = ScanPrefixes.parse(raw)
        when (parsed?.action) {
            ScanAction.ENROLLMENT -> {
                binding.tokenInput.setText(parsed.payload)
                binding.tokenInput.setSelection(parsed.payload.length)
            }
            null -> {
                // Compatibilidad: QR antiguo solo con el token, o pegado manual.
                val token = ScanPrefixes.normalizeEnrollmentToken(raw)
                if (token.isBlank()) {
                    Toast.makeText(this, R.string.config_scan_unrecognized, Toast.LENGTH_LONG).show()
                    return
                }
                binding.tokenInput.setText(token)
                binding.tokenInput.setSelection(token.length)
            }
        }
    }

    private fun renderEnrollmentState(config: AppConfig) {
        if (config.enrolled && config.dispositivoId != null) {
            val nombre = config.dispositivoNombre?.takeIf { it.isNotBlank() }
            binding.enrollmentStatusText.text = if (nombre != null) {
                getString(R.string.config_enrollment_ok_named, nombre, config.dispositivoId)
            } else {
                getString(R.string.config_enrollment_ok, config.dispositivoId)
            }
            binding.enrollButton.setText(R.string.config_enroll_again)
            binding.dispositivoIdLayout.visibility = View.VISIBLE
            binding.tokenNombreLayout.visibility = View.VISIBLE
            binding.dispositivoIdInput.setText(config.dispositivoId.toString())
            binding.tokenNombreInput.setText(config.tokenNombre.orEmpty())
        } else {
            binding.enrollmentStatusText.setText(R.string.config_enrollment_idle)
            binding.enrollButton.setText(R.string.config_enroll)
            binding.dispositivoIdLayout.visibility = View.GONE
            binding.tokenNombreLayout.visibility = View.GONE
        }
    }

    private fun requestCameraAndScan() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startQrScan()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startQrScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.config_scan_qr_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(true)
            .setBarcodeImageEnabled(false)
        qrScanLauncher.launch(options)
    }

    private fun saveLocalPreferences() {
        val draft = readDraftOrNull(requireEnrollmentFields = false) ?: return
        val current = store.load()
        store.save(
            current.copy(
                serverUrl = draft.serverUrl,
                token = draft.token,
            )
        )
        Toast.makeText(this, R.string.config_saved_local, Toast.LENGTH_SHORT).show()
    }

    private fun enrollDevice() {
        val draft = readDraftOrNull(requireEnrollmentFields = true) ?: return
        setEnrolling(true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(draft.serverUrl).enroll(
                        EnrollmentRequest(
                            token = draft.token,
                            identificador = deviceUuid(),
                        )
                    )
                }
            }

            setEnrolling(false)

            result.onSuccess { enrollment ->
                val saved = AppConfig(
                    serverUrl = draft.serverUrl,
                    token = enrollment.token.token,
                    enrolled = true,
                    dispositivoId = enrollment.dispositivo.id,
                    dispositivoNombre = enrollment.dispositivo.nombre,
                    tokenNombre = enrollment.token.nombre,
                    requiereAuth = enrollment.token.requiereAuth,
                )
                store.save(saved)
                binding.tokenInput.setText(saved.token)
                renderEnrollmentState(saved)

                val message = if (enrollment.creado) {
                    R.string.config_enrollment_created
                } else {
                    R.string.config_enrollment_linked
                }
                Toast.makeText(this@ConfigActivity, message, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                val message = when (error) {
                    is ApiException -> enrollmentErrorMessage(error.code)
                    else -> getString(R.string.config_enrollment_network)
                }
                Toast.makeText(this@ConfigActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class Draft(
        val serverUrl: String,
        val token: String,
    )

    private fun readDraftOrNull(requireEnrollmentFields: Boolean): Draft? {
        val serverUrl = binding.serverUrlInput.text?.toString().orEmpty().trim()
        val token = ScanPrefixes.normalizeEnrollmentToken(
            binding.tokenInput.text?.toString().orEmpty()
        )

        binding.serverUrlLayout.error = null
        binding.tokenLayout.error = null

        if (!requireEnrollmentFields) {
            if (serverUrl.isNotBlank() && !isValidServerUrl(serverUrl)) {
                binding.serverUrlLayout.error = getString(R.string.config_server_url_invalid)
                return null
            }
            binding.tokenInput.setText(token)
            return Draft(serverUrl = serverUrl, token = token)
        }

        if (serverUrl.isBlank()) {
            binding.serverUrlLayout.error = getString(R.string.config_server_url_required)
            return null
        }
        if (!isValidServerUrl(serverUrl)) {
            binding.serverUrlLayout.error = getString(R.string.config_server_url_invalid)
            return null
        }
        if (token.isBlank()) {
            binding.tokenLayout.error = getString(R.string.config_token_required)
            return null
        }

        binding.tokenInput.setText(token)
        return Draft(serverUrl = serverUrl, token = token)
    }

    private fun setEnrolling(busy: Boolean) {
        binding.enrollProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.enrollButton.isEnabled = !busy
        binding.scanQrButton.isEnabled = !busy
        binding.saveLocalButton.isEnabled = !busy
        binding.serverUrlInput.isEnabled = !busy
        binding.tokenInput.isEnabled = !busy
        if (busy) {
            binding.enrollmentStatusText.setText(R.string.config_enrollment_busy)
        }
    }

    private fun enrollmentErrorMessage(code: String): String = when (code) {
        "token_not_found" -> getString(R.string.config_enrollment_error_token_not_found)
        "token_inactive" -> getString(R.string.config_enrollment_error_token_inactive)
        "token_expired" -> getString(R.string.config_enrollment_error_token_expired)
        "token_not_free" -> getString(R.string.config_enrollment_error_token_not_free)
        "duplicate_identificador" -> getString(R.string.config_enrollment_error_duplicate_identificador)
        "seed_tipo_missing" -> getString(R.string.config_enrollment_error_seed_tipo_missing)
        "invalid_body" -> getString(R.string.config_enrollment_error_invalid_body)
        "invalid_token" -> getString(R.string.config_enrollment_error_invalid_token)
        "invalid_identificador" -> getString(R.string.config_enrollment_error_invalid_identificador)
        else -> getString(R.string.config_enrollment_error_generic, code)
    }

    private fun deviceUuid(): String {
        val app = application as? PackingTrackerApp
        return app?.deviceUuid ?: DeviceIdentityStore(this).getOrCreate()
    }

    private fun appVersionLabel(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return getString(R.string.config_app_version_value, info.versionName ?: "?", versionCode)
    }

    private fun isValidServerUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase()
            !uri.host.isNullOrBlank() && (scheme == "http" || scheme == "https")
        } catch (_: Exception) {
            false
        }
    }
}
