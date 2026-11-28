package com.packingtracker.app.embarques

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.packingtracker.app.R
import com.packingtracker.app.api.ApiCredentials
import com.packingtracker.app.api.ApiCredentialsResolver
import com.packingtracker.app.api.ApiException
import com.packingtracker.app.api.CreateExpedicionLoteRequest
import com.packingtracker.app.api.CreateExpedicionRequest
import com.packingtracker.app.api.Expedicion
import com.packingtracker.app.api.ExpedicionCarga
import com.packingtracker.app.api.ExpedicionCargaLote
import com.packingtracker.app.api.Lote
import com.packingtracker.app.api.LoteEstatus
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.api.Ubicacion
import com.packingtracker.app.api.UnidadTransporte
import com.packingtracker.app.api.UpdateExpedicionRequest
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.databinding.ActivityEmbarquesBinding
import com.packingtracker.app.databinding.ItemLoteContenidoBinding
import com.packingtracker.app.databinding.ItemUnidadBinding
import com.packingtracker.app.login.LoginActivity
import com.packingtracker.app.ui.ClientDateTime
import com.packingtracker.app.ui.UserSessionBar
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmbarquesActivity :
    AppCompatActivity(),
    ExpedicionesListaFragment.Listener,
    ExpedicionesListaFragment.Host,
    LotesEmbarqueListaFragment.Listener,
    LotesEmbarqueListaFragment.Host {

    private lateinit var binding: ActivityEmbarquesBinding
    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore

    private var unidades: List<UnidadTransporte> = emptyList()
    private var selectedUnidad: UnidadTransporte? = null
    private var loadedExpedicion: LoadedExpedicion? = null
    private val unidadButtons = mutableMapOf<Int, MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmbarquesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = AppConfigStore(this)
        sessionStore = UserSessionStore(this)

        EmbarquesDrawer.bind(
            activity = this,
            drawerLayout = binding.drawerLayout,
            toolbar = binding.toolbar,
            drawerContent = binding.drawerContent,
            onConsultarExpediciones = { openExpedicionesModal() },
            onBeforeBack = {
                closeLotesEmbarqueListaIfOpen() || closeExpedicionesListaIfOpen()
            },
        )

        binding.retryButton.setOnClickListener { loadCatalog() }
        binding.createButton.setOnClickListener { createExpedicion() }
        binding.clearButton.setOnClickListener { clearSelection() }
        binding.deleteExpedicionButton.setOnClickListener { confirmDeleteExpedicion() }
        binding.cerrarButton.setOnClickListener { confirmCerrarExpedicion() }
        binding.cargaRefreshButton.setOnClickListener { refreshCarga() }
        binding.loteSearchButton.setOnClickListener { openLotesEmbarqueModal() }
        binding.expedicionIdInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitEditorAction(actionId, event)) {
                lookupExpedicionFromInput()
                true
            } else {
                false
            }
        }
        binding.loteScanInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitEditorAction(actionId, event)) {
                submitLoteScan()
                true
            } else {
                false
            }
        }
        binding.salidaClaveInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitEditorAction(actionId, event)) {
                submitSalidaScan()
                true
            } else {
                false
            }
        }

        updateManagedUi()
        updateActionButtons(busy = false)
        loadCatalog()
    }

    private fun isSubmitEditorAction(actionId: Int, event: KeyEvent?): Boolean {
        val isAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
            actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_GO
        val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
            event.action == KeyEvent.ACTION_DOWN
        return isAction || isEnter
    }

    override fun onResume() {
        super.onResume()
        UserSessionBar.bind(binding.userSession)
    }

    override fun onExpedicionSelected(expedicion: Expedicion) {
        binding.expedicionIdInput.setText(expedicion.id.toString())
        lookupExpedicionById(expedicion.id)
    }

    override fun onLoteEmbarqueSelected(lote: Lote) {
        binding.loteScanInput.setText(lote.id.toString())
        embarcarLote(lote.id)
    }

    private fun openLotesEmbarqueModal() {
        val loaded = loadedExpedicion ?: return
        if (!loaded.abierta) {
            Toast.makeText(this, R.string.embarques_error_cerrada, Toast.LENGTH_LONG).show()
            return
        }
        val ubicacionId = sessionStore.load().usuarioUbicacionId
        if (ubicacionId == null) {
            Toast.makeText(this, R.string.embarques_lote_search_no_ubicacion, Toast.LENGTH_LONG).show()
            return
        }
        if (supportFragmentManager.findFragmentByTag(LotesEmbarqueListaFragment.TAG) != null) {
            binding.lotesEmbarqueOverlay.visibility = View.VISIBLE
            return
        }
        binding.lotesEmbarqueOverlay.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.lotesEmbarqueOverlay,
                LotesEmbarqueListaFragment.newInstance(ubicacionId),
                LotesEmbarqueListaFragment.TAG,
            )
            .commit()
    }

    override fun closeLotesEmbarqueLista() {
        supportFragmentManager.findFragmentByTag(LotesEmbarqueListaFragment.TAG)?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commit()
        }
        binding.lotesEmbarqueOverlay.visibility = View.GONE
    }

    private fun closeLotesEmbarqueListaIfOpen(): Boolean {
        if (binding.lotesEmbarqueOverlay.visibility != View.VISIBLE) {
            return false
        }
        closeLotesEmbarqueLista()
        return true
    }

    private fun openExpedicionesModal() {
        if (supportFragmentManager.findFragmentByTag(ExpedicionesListaFragment.TAG) != null) {
            binding.expedicionesListaOverlay.visibility = View.VISIBLE
            return
        }
        binding.expedicionesListaOverlay.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.expedicionesListaOverlay,
                ExpedicionesListaFragment.newInstance(),
                ExpedicionesListaFragment.TAG,
            )
            .commit()
    }

    override fun closeExpedicionesLista() {
        supportFragmentManager.findFragmentByTag(ExpedicionesListaFragment.TAG)?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commit()
        }
        binding.expedicionesListaOverlay.visibility = View.GONE
    }

    private fun closeExpedicionesListaIfOpen(): Boolean {
        if (binding.expedicionesListaOverlay.visibility != View.VISIBLE) {
            return false
        }
        closeExpedicionesLista()
        return true
    }

    private fun loadCatalog() {
        val config = configStore.load()
        val session = sessionStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, session)
        if (credentials == null) {
            if (config.requiereAuth) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
            showError(getString(R.string.embarques_error_auth))
            return
        }

        showLoading()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(config.serverUrl).listUnidadesTransporte(credentials)
                }
            }
            result.onSuccess { list ->
                unidades = list.sortedBy { it.displayLabel().lowercase() }
                renderUnidades()
                showWorkspace()
                updateActionButtons(busy = false)
            }.onFailure { error ->
                showError(mapError(error))
            }
        }
    }

    private fun renderUnidades() {
        binding.unidadesList.removeAllViews()
        unidadButtons.clear()
        if (unidades.isEmpty()) {
            binding.unidadesEmptyText.visibility = View.VISIBLE
            return
        }
        binding.unidadesEmptyText.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        unidades.forEach { unidad ->
            val item = ItemUnidadBinding.inflate(inflater, binding.unidadesList, false)
            item.unidadButton.text = unidad.displayLabel()
            item.unidadButton.setOnClickListener { selectUnidad(unidad) }
            unidadButtons[unidad.id] = item.unidadButton
            binding.unidadesList.addView(item.root)
        }
        refreshUnidadButtonStyles()
        updateSelectionUi()
    }

    private fun selectUnidad(unidad: UnidadTransporte) {
        if (loadedExpedicion != null) return
        selectedUnidad = unidad
        updateSelectionUi()
        refreshUnidadButtonStyles()
        updateActionButtons(busy = false)
    }

    private fun updateSelectionUi() {
        val unidad = selectedUnidad
        if (unidad == null) {
            binding.unidadSectionTitle.setText(R.string.embarques_filter_label)
            binding.toolbar.title = getString(R.string.embarques_nav_create)
            return
        }
        binding.unidadSectionTitle.text =
            getString(R.string.embarques_filter_label_selected, unidad.displayLabel())
        if (loadedExpedicion == null) {
            binding.toolbar.title = getString(R.string.embarques_nav_create)
        }
    }

    private fun refreshUnidadButtonStyles() {
        val selectedId = selectedUnidad?.id
        val selectedBg = ContextCompat.getColor(this, R.color.primary)
        val idleStroke = ContextCompat.getColor(this, R.color.on_background)
        unidadButtons.forEach { (id, button) ->
            val selected = id == selectedId
            button.strokeColor = ColorStateList.valueOf(
                if (selected) selectedBg else idleStroke,
            )
            button.setTextColor(
                if (selected) selectedBg else idleStroke,
            )
        }
    }

    private fun lookupExpedicionFromInput() {
        val raw = binding.expedicionIdInput.text?.toString().orEmpty().trim()
        val id = raw.filter { it.isDigit() }.toIntOrNull()
        if (id == null || id < 1) {
            Toast.makeText(this, R.string.embarques_expedicion_invalid_id, Toast.LENGTH_SHORT).show()
            return
        }
        binding.expedicionIdInput.setText(id.toString())
        lookupExpedicionById(id)
    }

    private fun lookupExpedicionById(expedicionId: Int) {
        val credentials = resolveCredentials() ?: return
        hideKeyboard()
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    loadFullExpedicion(
                        PackingTrackerApi(configStore.load().serverUrl),
                        credentials,
                        expedicionId,
                    )
                }
            }
            setBusy(false)
            result.onSuccess { snapshot ->
                applyExpedicion(snapshot)
                Toast.makeText(
                    this@EmbarquesActivity,
                    getString(R.string.embarques_loaded, snapshot.carga.expedicion.id),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                if (error is ApiException &&
                    (error.code == "not_found" || error.httpStatus == 404)
                ) {
                    clearManaged(keepInput = true)
                    Toast.makeText(
                        this@EmbarquesActivity,
                        getString(R.string.embarques_expedicion_not_found, expedicionId),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(this@EmbarquesActivity, mapError(error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadFullExpedicion(
        api: PackingTrackerApi,
        credentials: ApiCredentials,
        expedicionId: Int,
    ): ExpedicionSnapshot {
        val carga = api.getExpedicionCarga(credentials, expedicionId)
        val ubicacionSalida = carga.expedicion.ubicacionSalidaId?.let { id ->
            runCatching { api.getUbicacion(credentials, id) }.getOrNull()
        }
        return ExpedicionSnapshot(carga = carga, ubicacionSalida = ubicacionSalida)
    }

    private fun applyExpedicion(snapshot: ExpedicionSnapshot) {
        val expedicion = snapshot.carga.expedicion
        val unidad = unidades.firstOrNull { it.id == expedicion.idUnidad }
        selectedUnidad = unidad
        loadedExpedicion = LoadedExpedicion(
            expedicion = expedicion,
            unidadNombre = unidad?.displayLabel()
                ?: getString(R.string.embarques_list_unidad_fallback, expedicion.idUnidad),
            carga = snapshot.carga,
            ubicacionSalida = snapshot.ubicacionSalida,
        )
        if (binding.expedicionIdInput.text?.toString() != expedicion.id.toString()) {
            binding.expedicionIdInput.setText(expedicion.id.toString())
        }
        binding.toolbar.title = getString(R.string.embarques_list_id, expedicion.id)
        updateSelectionUi()
        refreshUnidadButtonStyles()
        updateManagedUi()
        updateActionButtons(busy = false)
    }

    private fun createExpedicion() {
        val unidad = selectedUnidad ?: return
        val credentials = resolveCredentials() ?: return
        val session = sessionStore.load()
        if (!session.hasUbicacion) {
            Toast.makeText(this, R.string.error_usuario_sin_ubicacion, Toast.LENGTH_LONG).show()
            return
        }
        val usuarioId = session.usuarioId
        val request = CreateExpedicionRequest(
            idUnidad = unidad.id,
            ubicacionSalidaId = session.usuarioUbicacionId,
            usuarioId = usuarioId,
            idOrigenUsuario = if (usuarioId != null) ORIGEN_USUARIO_INTERNO else null,
        )
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    val created = api.createExpedicion(credentials, request)
                    loadFullExpedicion(api, credentials, created.id)
                }
            }
            setBusy(false)
            result.onSuccess { snapshot ->
                applyExpedicion(snapshot)
                Toast.makeText(
                    this@EmbarquesActivity,
                    getString(R.string.embarques_created_toast, snapshot.carga.expedicion.id),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmbarquesActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitLoteScan() {
        val loaded = loadedExpedicion ?: return
        if (!loaded.abierta) {
            Toast.makeText(this, R.string.embarques_error_cerrada, Toast.LENGTH_LONG).show()
            return
        }
        val raw = binding.loteScanInput.text?.toString().orEmpty().trim()
        val loteId = raw.filter { it.isDigit() }.toIntOrNull()
        if (loteId == null || loteId < 1) {
            Toast.makeText(this, R.string.embarques_lote_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        binding.loteScanInput.setText("")
        embarcarLote(loteId)
        binding.loteScanInput.requestFocus()
    }

    private fun embarcarLote(loteId: Int) {
        val loaded = loadedExpedicion ?: return
        val credentials = resolveCredentials() ?: return
        val session = sessionStore.load()
        val usuarioId = session.usuarioId
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    val detalle = api.getLoteDetalle(credentials, loteId)
                    if (detalle.estatus != LoteEstatus.DISPONIBLE || detalle.lote.padreId != null) {
                        throw ApiException(code = "lote_not_embarcable", httpStatus = 409)
                    }
                    api.createExpedicionLote(
                        credentials,
                        CreateExpedicionLoteRequest(
                            idExpedicion = loaded.expedicion.id,
                            idLote = loteId,
                            usuarioId = usuarioId,
                            idOrigenUsuario = if (usuarioId != null) ORIGEN_USUARIO_INTERNO else null,
                        ),
                    )
                    loadFullExpedicion(api, credentials, loaded.expedicion.id)
                }
            }
            setBusy(false)
            result.onSuccess { snapshot ->
                applyExpedicion(snapshot)
                Toast.makeText(
                    this@EmbarquesActivity,
                    getString(R.string.embarques_lote_added, loteId),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmbarquesActivity, mapEmbarqueError(error, loteId), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitSalidaScan() {
        val loaded = loadedExpedicion ?: return
        if (!loaded.abierta) {
            Toast.makeText(this, R.string.embarques_error_cerrada, Toast.LENGTH_LONG).show()
            return
        }
        if (!sessionStore.load().hasUbicacion) {
            Toast.makeText(this, R.string.error_usuario_sin_ubicacion, Toast.LENGTH_LONG).show()
            return
        }
        val clave = binding.salidaClaveInput.text?.toString().orEmpty().trim()
        if (clave.isEmpty()) return
        binding.salidaClaveInput.setText("")
        assignSalida(clave)
        binding.salidaClaveInput.requestFocus()
    }

    private fun assignSalida(clave: String) {
        val loaded = loadedExpedicion ?: return
        val credentials = resolveCredentials() ?: return
        val session = sessionStore.load()
        val usuarioId = session.usuarioId
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    val ubicacion = api.findUbicacionByCodigo(credentials, clave)
                        ?: throw ApiException(code = "ubicacion_not_found", httpStatus = 404)
                    api.updateExpedicion(
                        credentials,
                        loaded.expedicion.id,
                        UpdateExpedicionRequest(
                            idUnidad = loaded.expedicion.idUnidad,
                            fechaCierre = loaded.expedicion.fechaCierre,
                            idUbicacionSalida = loaded.expedicion.idUbicacionSalida,
                            ubicacionSalidaId = ubicacion.id,
                            usuarioId = usuarioId,
                            idOrigenUsuario = if (usuarioId != null) ORIGEN_USUARIO_INTERNO else null,
                        ),
                    )
                    loadFullExpedicion(api, credentials, loaded.expedicion.id)
                }
            }
            setBusy(false)
            result.onSuccess { snapshot ->
                applyExpedicion(snapshot)
                Toast.makeText(
                    this@EmbarquesActivity,
                    R.string.embarques_salida_asignada,
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmbarquesActivity, mapSalidaError(error, clave), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmCerrarExpedicion() {
        val loaded = loadedExpedicion ?: return
        if (!loaded.abierta) {
            Toast.makeText(this, R.string.embarques_cerrar_blocked, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.embarques_cerrar_confirm_title)
            .setMessage(getString(R.string.embarques_cerrar_confirm_message, loaded.expedicion.id))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.embarques_cerrar) { _, _ ->
                cerrarExpedicion(loaded)
            }
            .show()
    }

    private fun cerrarExpedicion(loaded: LoadedExpedicion) {
        val credentials = resolveCredentials() ?: return
        val session = sessionStore.load()
        val usuarioId = session.usuarioId
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    api.updateExpedicion(
                        credentials,
                        loaded.expedicion.id,
                        UpdateExpedicionRequest(
                            idUnidad = loaded.expedicion.idUnidad,
                            fechaCierre = Instant.now().toString(),
                            idUbicacionSalida = loaded.expedicion.idUbicacionSalida,
                            ubicacionSalidaId = loaded.expedicion.ubicacionSalidaId,
                            usuarioId = usuarioId,
                            idOrigenUsuario = if (usuarioId != null) ORIGEN_USUARIO_INTERNO else null,
                        ),
                    )
                    loadFullExpedicion(api, credentials, loaded.expedicion.id)
                }
            }
            setBusy(false)
            result.onSuccess { snapshot ->
                applyExpedicion(snapshot)
                Toast.makeText(
                    this@EmbarquesActivity,
                    getString(R.string.embarques_cerrada_toast, snapshot.carga.expedicion.id),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmbarquesActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteExpedicion() {
        val loaded = loadedExpedicion ?: return
        if (loaded.directLotes.isNotEmpty()) {
            Toast.makeText(this, R.string.embarques_delete_blocked, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.embarques_delete_confirm_title)
            .setMessage(getString(R.string.embarques_delete_confirm_message, loaded.expedicion.id))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.embarques_delete) { _, _ ->
                deleteExpedicion(loaded)
            }
            .show()
    }

    private fun deleteExpedicion(loaded: LoadedExpedicion) {
        val credentials = resolveCredentials() ?: return
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(configStore.load().serverUrl)
                        .deleteExpedicion(credentials, loaded.expedicion.id)
                }
            }
            setBusy(false)
            result.onSuccess {
                clearManaged(keepInput = false)
                Toast.makeText(
                    this@EmbarquesActivity,
                    getString(R.string.embarques_deleted, loaded.expedicion.id),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmbarquesActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRemoveLote(lote: ExpedicionCargaLote) {
        val embarqueId = lote.embarqueId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.empaque_contenido_delete)
            .setMessage(getString(R.string.embarques_lote_remove_confirm, lote.id))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.empaque_contenido_delete) { _, _ ->
                removeLote(embarqueId)
            }
            .show()
    }

    private fun removeLote(embarqueId: Int) {
        val loaded = loadedExpedicion ?: return
        val credentials = resolveCredentials() ?: return
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    api.deleteExpedicionLote(credentials, embarqueId)
                    loadFullExpedicion(api, credentials, loaded.expedicion.id)
                }
            }
            setBusy(false)
            result.onSuccess { snapshot ->
                applyExpedicion(snapshot)
                Toast.makeText(
                    this@EmbarquesActivity,
                    R.string.embarques_lote_removed,
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmbarquesActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshCarga(showToast: Boolean = true) {
        val loaded = loadedExpedicion ?: return
        val credentials = resolveCredentials() ?: return
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    loadFullExpedicion(
                        PackingTrackerApi(configStore.load().serverUrl),
                        credentials,
                        loaded.expedicion.id,
                    )
                }
            }
            setBusy(false)
            result.onSuccess { snapshot ->
                applyExpedicion(snapshot)
                if (showToast) {
                    Toast.makeText(
                        this@EmbarquesActivity,
                        R.string.empaque_contenido_refreshed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(this@EmbarquesActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearSelection() {
        val hasContent = loadedExpedicion != null ||
            !binding.expedicionIdInput.text.isNullOrBlank()
        if (!hasContent) return
        clearManaged(keepInput = false)
        Toast.makeText(this, R.string.embarques_selection_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun clearManaged(keepInput: Boolean = false) {
        loadedExpedicion = null
        binding.loteScanInput.setText("")
        binding.salidaClaveInput.setText("")
        if (!keepInput) {
            binding.expedicionIdInput.setText("")
        }
        binding.toolbar.title = getString(R.string.embarques_nav_create)
        updateSelectionUi()
        refreshUnidadButtonStyles()
        updateManagedUi()
        updateActionButtons(busy = false)
    }

    private fun updateManagedUi() {
        val loaded = loadedExpedicion
        if (loaded == null) {
            binding.expedicionInfoSection.visibility = View.GONE
            binding.salidaSection.visibility = View.GONE
            binding.loteScanSection.visibility = View.GONE
            binding.cargaSection.visibility = View.GONE
            binding.cerrarButton.visibility = View.GONE
            binding.blockedText.visibility = View.GONE
            binding.cargaList.removeAllViews()
            return
        }

        binding.expedicionUnidadValue.text = loaded.unidadNombre
        binding.expedicionCreadoValue.text = ClientDateTime.format(loaded.expedicion.creadoEn)
        binding.expedicionCierreValue.text = loaded.expedicion.fechaCierre?.let {
            ClientDateTime.format(it)
        } ?: getString(R.string.embarques_sin_cierre)
        binding.expedicionEstatusText.text = if (loaded.abierta) {
            getString(R.string.embarques_estatus_abierta)
        } else {
            getString(R.string.embarques_estatus_cerrada)
        }
        binding.expedicionInfoSection.visibility = View.VISIBLE

        binding.salidaValue.text = loaded.ubicacionSalida?.displayLabel()
            ?: getString(R.string.embarques_sin_salida)
        binding.salidaSection.visibility = View.VISIBLE
        binding.loteScanSection.visibility = if (loaded.abierta) View.VISIBLE else View.GONE
        binding.cerrarButton.visibility = if (loaded.abierta) View.VISIBLE else View.GONE
        renderCarga(loaded)

        if (!loaded.abierta) {
            binding.blockedText.visibility = View.VISIBLE
            binding.blockedText.setText(R.string.embarques_cerrar_blocked)
        } else if (loaded.directLotes.isNotEmpty()) {
            binding.blockedText.visibility = View.VISIBLE
            binding.blockedText.setText(R.string.embarques_delete_blocked)
        } else {
            binding.blockedText.visibility = View.GONE
        }
    }

    private fun renderCarga(loaded: LoadedExpedicion) {
        binding.cargaSection.visibility = View.VISIBLE
        binding.cargaTitle.text = getString(
            R.string.embarques_carga_title,
            loaded.carga.resumen.lotes,
            loaded.carga.resumen.productos,
        )
        binding.cargaList.removeAllViews()
        val directos = loaded.directLotes
        if (directos.isEmpty()) {
            binding.cargaEmpty.visibility = View.VISIBLE
            binding.cargaEmpty.setText(R.string.embarques_carga_empty)
            return
        }
        binding.cargaEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        directos.forEach { lote ->
            val row = ItemLoteContenidoBinding.inflate(inflater, binding.cargaList, false)
            val tipoNombre = lote.tipo?.nombre
                ?: getString(R.string.empaque_list_tipo_fallback, lote.idTipo)
            row.contenidoLineText.text = getString(
                R.string.embarques_carga_lote,
                lote.id,
                tipoNombre,
            )
            if (loaded.abierta && lote.embarqueId != null) {
                row.contenidoDeleteButton.visibility = View.VISIBLE
                row.contenidoDeleteButton.setOnClickListener { confirmRemoveLote(lote) }
            } else {
                row.contenidoDeleteButton.visibility = View.GONE
            }
            binding.cargaList.addView(row.root)
        }
    }

    private fun updateActionButtons(busy: Boolean) {
        val hasExpedicion = loadedExpedicion != null
        val canCreate = !busy && !hasExpedicion && selectedUnidad != null &&
            sessionStore.load().hasUbicacion
        val canDelete = !busy && hasExpedicion &&
            (loadedExpedicion?.directLotes?.isEmpty() == true)
        val canClear = !busy && (hasExpedicion || !binding.expedicionIdInput.text.isNullOrBlank())
        val canChangeUnidad = !busy && !hasExpedicion

        if (hasExpedicion) {
            binding.createButton.visibility = View.GONE
            binding.createButton.isEnabled = false
            binding.deleteExpedicionButton.visibility = View.VISIBLE
            binding.deleteExpedicionButton.isEnabled = canDelete
        } else {
            binding.deleteExpedicionButton.visibility = View.GONE
            binding.deleteExpedicionButton.isEnabled = false
            binding.createButton.visibility = View.VISIBLE
            binding.createButton.isEnabled = canCreate
        }

        binding.expedicionIdInput.isEnabled = !busy
        binding.clearButton.isEnabled = canClear
        binding.clearButton.alpha = if (canClear) 1f else 0.35f
        binding.createButton.alpha = if (canCreate) 1f else 0.35f
        binding.deleteExpedicionButton.alpha = if (canDelete) 1f else 0.35f
        binding.unidadesScroll.alpha = if (canChangeUnidad) 1f else 0.55f
        unidadButtons.values.forEach { button ->
            button.isEnabled = canChangeUnidad
            button.alpha = if (canChangeUnidad) 1f else 0.7f
        }

        val hasUbicacion = sessionStore.load().hasUbicacion
        val canEditAbierta = !busy && hasExpedicion && (loadedExpedicion?.abierta == true)
        binding.loteScanInput.isEnabled = canEditAbierta
        binding.loteSearchButton.isEnabled = canEditAbierta && hasUbicacion
        binding.loteSearchButton.alpha = if (canEditAbierta && hasUbicacion) 1f else 0.35f
        binding.salidaClaveInput.isEnabled = canEditAbierta && hasUbicacion
        binding.cerrarButton.isEnabled = canEditAbierta
        binding.cargaRefreshButton.isEnabled = !busy && hasExpedicion
    }

    private fun resolveCredentials(): ApiCredentials? {
        val credentials = ApiCredentialsResolver.resolve(configStore.load(), sessionStore.load())
        if (credentials == null) {
            Toast.makeText(this, R.string.embarques_error_auth, Toast.LENGTH_LONG).show()
        }
        return credentials
    }

    private fun setBusy(busy: Boolean) {
        binding.createProgress.visibility = if (busy) View.VISIBLE else View.GONE
        updateActionButtons(busy)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.expedicionIdInput.windowToken, 0)
    }

    private fun showLoading() {
        binding.loadingContainer.visibility = View.VISIBLE
        binding.workspaceContainer.visibility = View.GONE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showWorkspace() {
        binding.loadingContainer.visibility = View.GONE
        binding.workspaceContainer.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loadingContainer.visibility = View.GONE
        binding.workspaceContainer.visibility = View.GONE
        binding.errorContainer.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    private fun mapError(error: Throwable): String {
        return when (error) {
            is ApiException -> when (error.code) {
                "unauthorized", "invalid_token", "token_expired" ->
                    getString(R.string.embarques_error_auth)
                "unidad_ya_abierta" -> getString(R.string.embarques_error_unidad_abierta)
                "expedicion_cerrada" -> getString(R.string.embarques_error_cerrada)
                "lote_ya_embarcado" -> getString(R.string.embarques_error_lote_embarcado)
                "record_in_use", "http_409" -> getString(R.string.embarques_error_in_use)
                "usuario_sin_ubicacion" -> getString(R.string.error_usuario_sin_ubicacion)
                else -> getString(R.string.embarques_error_generic, error.code)
            }
            else -> getString(R.string.embarques_error_network)
        }
    }

    private fun mapEmbarqueError(error: Throwable, loteId: Int): String {
        return when (error) {
            is ApiException -> when (error.code) {
                "lote_not_embarcable", "not_found", "http_404" ->
                    getString(R.string.embarques_lote_not_embarcable, loteId)
                else -> mapError(error)
            }
            else -> getString(R.string.embarques_error_network)
        }
    }

    private fun mapSalidaError(error: Throwable, clave: String): String {
        return when (error) {
            is ApiException -> when (error.code) {
                "ubicacion_not_found" -> getString(R.string.embarques_salida_not_found, clave)
                else -> mapError(error)
            }
            else -> getString(R.string.embarques_error_network)
        }
    }

    private data class ExpedicionSnapshot(
        val carga: ExpedicionCarga,
        val ubicacionSalida: Ubicacion? = null,
    )

    private data class LoadedExpedicion(
        val expedicion: Expedicion,
        val unidadNombre: String,
        val carga: ExpedicionCarga,
        val ubicacionSalida: Ubicacion? = null,
    ) {
        val abierta: Boolean
            get() = expedicion.abierta

        val directLotes: List<ExpedicionCargaLote>
            get() = carga.lotes.filter { it.embarcadoDirecto }
    }

    companion object {
        private const val ORIGEN_USUARIO_INTERNO = 1
    }
}
