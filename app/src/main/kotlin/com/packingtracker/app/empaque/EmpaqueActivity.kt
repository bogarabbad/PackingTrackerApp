package com.packingtracker.app.empaque

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.packingtracker.app.R
import com.packingtracker.app.api.ApiCredentialsResolver
import com.packingtracker.app.api.ApiException
import com.packingtracker.app.api.CreateLoteRequest
import com.packingtracker.app.api.Lote
import com.packingtracker.app.api.LoteTipo
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.api.UnidadTransporte
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.databinding.ActivityEmpaqueBinding
import com.packingtracker.app.databinding.ItemLoteTipoBinding
import com.packingtracker.app.login.LoginActivity
import com.packingtracker.app.ui.UserSessionBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmpaqueActivity : AppCompatActivity(), LotesListaDialogFragment.Listener {
    private lateinit var binding: ActivityEmpaqueBinding
    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore

    private var tipos: List<LoteTipo> = emptyList()
    private var unidades: List<UnidadTransporte> = emptyList()
    private var selectedTipo: LoteTipo? = null
    private var loadedLote: LoadedLote? = null
    private var filterFinales: Boolean = true
    private var unidadOptions: List<UnidadOption> = emptyList()
    private val tipoButtons = mutableMapOf<Int, MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmpaqueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = AppConfigStore(this)
        sessionStore = UserSessionStore(this)

        EmpaqueDrawer.bind(
            activity = this,
            drawerLayout = binding.drawerLayout,
            toolbar = binding.toolbar,
            drawerContent = binding.drawerContent,
            onConsultarLotes = { openLotesModal() },
        )

        binding.retryButton.setOnClickListener { loadCatalog() }
        binding.createButton.setOnClickListener { createLote() }
        binding.clearButton.setOnClickListener { clearForm() }
        binding.deleteLoteButton.setOnClickListener { confirmDeleteManagedLote() }
        binding.filterFinalButton.setOnClickListener { setFilter(finales = true) }
        binding.filterNoFinalButton.setOnClickListener { setFilter(finales = false) }

        applyFilterIconStyles()
        updateManagedLoteUi()
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        UserSessionBar.bind(binding.userSession)
    }

    override fun onLoteSelected(lote: Lote, tipoNombre: String) {
        loadManagedLote(lote, tipoNombre)
        Toast.makeText(
            this,
            getString(R.string.empaque_loaded_lote, lote.id, tipoNombre),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openLotesModal() {
        if (supportFragmentManager.findFragmentByTag(LotesListaDialogFragment.TAG) != null) {
            return
        }
        LotesListaDialogFragment.newInstance()
            .show(supportFragmentManager, LotesListaDialogFragment.TAG)
    }

    private fun loadManagedLote(lote: Lote, tipoNombre: String) {
        val tipo = tipos.firstOrNull { it.id == lote.idTipo }
        loadedLote = LoadedLote(
            lote = lote,
            tipoNombre = tipoNombre,
            tipoEsFinal = tipo?.esFinal ?: false,
        )
        updateManagedLoteUi()
    }

    private fun clearManagedLote() {
        loadedLote = null
        updateManagedLoteUi()
    }

    private fun clearForm() {
        selectedTipo = null
        clearManagedLote()
        binding.unidadSpinner.adapter = null
        unidadOptions = emptyList()
        filterFinales = true
        applyFilterIconStyles()
        renderTipos()
        updateSelectionUi()
        binding.tiposScroll.scrollTo(0, 0)
        Toast.makeText(this, R.string.empaque_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun updateManagedLoteUi() {
        val loaded = loadedLote
        if (loaded == null) {
            binding.loteGestionEmptyText.visibility = View.VISIBLE
            binding.loteGestionContent.visibility = View.GONE
            binding.loteIdInput.setText("")
            binding.loteTipoInput.setText("")
            return
        }
        binding.loteGestionEmptyText.visibility = View.GONE
        binding.loteGestionContent.visibility = View.VISIBLE
        binding.loteIdInput.setText(loaded.lote.id.toString())
        binding.loteTipoInput.setText(loaded.tipoNombre)
    }

    private fun confirmDeleteManagedLote() {
        val loaded = loadedLote ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.empaque_gestion_delete_confirm_title)
            .setMessage(getString(R.string.empaque_gestion_delete_confirm_message, loaded.lote.id))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.empaque_gestion_delete) { _, _ ->
                deleteManagedLote(loaded)
            }
            .show()
    }

    private fun deleteManagedLote(loaded: LoadedLote) {
        val config = configStore.load()
        val session = sessionStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, session)
        if (credentials == null) {
            Toast.makeText(this, R.string.empaque_error_auth, Toast.LENGTH_LONG).show()
            return
        }

        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(config.serverUrl).deleteLote(credentials, loaded.lote.id)
                }
            }
            setCreating(false)
            result.onSuccess {
                clearManagedLote()
                Toast.makeText(
                    this@EmpaqueActivity,
                    getString(R.string.empaque_gestion_deleted, loaded.lote.id),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
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
            showError(getString(R.string.empaque_error_auth))
            return
        }

        showLoading()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(config.serverUrl)
                    val loadedTipos = api.listLotesTipos(credentials)
                    val loadedUnidades = api.listUnidadesTransporte(credentials)
                    loadedTipos to loadedUnidades
                }
            }

            result.onSuccess { (loadedTipos, loadedUnidades) ->
                tipos = loadedTipos.filter { it.activo }.sortedBy { it.nombre.lowercase() }
                unidades = loadedUnidades.filter { it.activo }.sortedBy { it.displayLabel().lowercase() }
                if (tipos.isEmpty()) {
                    showError(getString(R.string.empaque_error_no_tipos))
                } else {
                    filterFinales = true
                    selectedTipo = null
                    applyFilterIconStyles()
                    renderTipos()
                    updateSelectionUi()
                    showWorkspace()
                }
            }.onFailure { error ->
                showError(mapError(error))
            }
        }
    }

    private fun setFilter(finales: Boolean) {
        if (filterFinales == finales) return
        filterFinales = finales
        applyFilterIconStyles()

        val stillVisible = selectedTipo?.let { it.esFinal == filterFinales } == true
        if (!stillVisible) {
            selectedTipo = null
        }
        renderTipos()
        updateSelectionUi()
        binding.tiposScroll.scrollTo(0, 0)
    }

    private fun applyFilterIconStyles() {
        styleFilterIcon(binding.filterFinalButton, selected = filterFinales)
        styleFilterIcon(binding.filterNoFinalButton, selected = !filterFinales)
    }

    private fun styleFilterIcon(button: ImageButton, selected: Boolean) {
        button.setBackgroundResource(
            if (selected) R.drawable.bg_filter_icon_selected else R.drawable.bg_filter_icon_idle,
        )
        button.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.on_background),
        )
    }

    private fun styleTipoButton(button: MaterialButton, selected: Boolean) {
        val onBackground = ContextCompat.getColor(this, R.color.on_background)
        val surface = ContextCompat.getColor(this, R.color.surface)
        val primary = ContextCompat.getColor(this, R.color.primary)
        val onPrimary = ContextCompat.getColor(this, R.color.on_primary)
        if (selected) {
            button.setBackgroundColor(primary)
            button.setTextColor(onPrimary)
            button.strokeWidth = 0
        } else {
            button.setBackgroundColor(surface)
            button.setTextColor(onBackground)
            button.strokeWidth = resources.getDimensionPixelSize(R.dimen.empaque_stroke)
            button.strokeColor = ColorStateList.valueOf(onBackground)
        }
    }

    private fun renderTipos() {
        binding.tiposList.removeAllViews()
        tipoButtons.clear()

        val filtered = tipos.filter { it.esFinal == filterFinales }
        binding.tiposEmptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        filtered.forEach { tipo ->
            val item = ItemLoteTipoBinding.inflate(inflater, binding.tiposList, false)
            item.tipoButton.text = tipo.nombre
            item.tipoButton.setOnClickListener { selectTipo(tipo) }
            tipoButtons[tipo.id] = item.tipoButton
            binding.tiposList.addView(item.root)
        }
        refreshTipoButtonStyles()
    }

    private fun selectTipo(tipo: LoteTipo) {
        selectedTipo = tipo
        updateSelectionUi()
        refreshTipoButtonStyles()
    }

    private fun refreshTipoButtonStyles() {
        val selectedId = selectedTipo?.id
        tipoButtons.forEach { (id, button) ->
            styleTipoButton(button, selected = id == selectedId)
        }
    }

    private fun updateSelectionUi() {
        val tipo = selectedTipo
        if (tipo == null) {
            binding.tipoSectionTitle.setText(R.string.empaque_filter_label)
            binding.unidadSection.visibility = View.GONE
            binding.unidadSpinner.adapter = null
            unidadOptions = emptyList()
            binding.createButton.isEnabled = false
            return
        }

        binding.tipoSectionTitle.text = getString(R.string.empaque_filter_label_selected, tipo.nombre)
        binding.createButton.isEnabled = true

        if (tipo.esFinal) {
            binding.unidadSection.visibility = View.GONE
            binding.unidadSpinner.adapter = null
            unidadOptions = emptyList()
        } else {
            binding.unidadSection.visibility = View.VISIBLE
            unidadOptions = listOf(UnidadOption(null, getString(R.string.empaque_unidad_none))) +
                unidades.map { UnidadOption(it.id, it.displayLabel()) }
            binding.unidadSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                unidadOptions.map { it.label },
            )
        }
    }

    private fun createLote() {
        val tipo = selectedTipo ?: return
        val config = configStore.load()
        val session = sessionStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, session)
        if (credentials == null) {
            Toast.makeText(this, R.string.empaque_error_auth, Toast.LENGTH_LONG).show()
            return
        }

        val selectedUnidadId = if (!tipo.esFinal && unidadOptions.isNotEmpty()) {
            unidadOptions.getOrNull(binding.unidadSpinner.selectedItemPosition)?.id
        } else {
            null
        }

        val loaded = loadedLote
        val padreId = if (tipo.esFinal && loaded != null && !loaded.tipoEsFinal) {
            loaded.lote.id
        } else {
            null
        }

        val usuarioId = session.usuarioId
        val request = CreateLoteRequest(
            idTipo = tipo.id,
            padreId = padreId,
            idUnidad = selectedUnidadId,
            usuarioId = usuarioId,
            idOrigenUsuario = if (usuarioId != null) ORIGEN_USUARIO_INTERNO else null,
        )

        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(config.serverUrl).createLote(credentials, request)
                }
            }
            setCreating(false)
            result.onSuccess { lote ->
                loadManagedLote(lote, tipo.nombre)
                Toast.makeText(
                    this@EmpaqueActivity,
                    getString(R.string.empaque_created_toast, lote.id),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setCreating(busy: Boolean) {
        binding.createProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.createButton.isEnabled = !busy && selectedTipo != null
        binding.clearButton.isEnabled = !busy
        binding.deleteLoteButton.isEnabled = !busy
        binding.filterFinalButton.isEnabled = !busy
        binding.filterNoFinalButton.isEnabled = !busy
        binding.unidadSpinner.isEnabled = !busy
        tipoButtons.values.forEach { it.isEnabled = !busy }
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
                    getString(R.string.empaque_error_auth)
                "invalid_id_tipo" -> getString(R.string.empaque_error_tipo)
                "invalid_id_unidad" -> getString(R.string.empaque_error_unidad)
                "invalid_padre_id" -> getString(R.string.empaque_error_padre)
                "invalid_tipo_lote" -> getString(R.string.empaque_error_reglas)
                "record_in_use", "http_409" -> getString(R.string.empaque_error_delete_in_use)
                "not_found", "http_404" -> getString(R.string.empaque_error_generic, error.code)
                else -> getString(R.string.empaque_error_generic, error.code)
            }
            else -> getString(R.string.empaque_error_network)
        }
    }

    private data class UnidadOption(val id: Int?, val label: String)

    private data class LoadedLote(
        val lote: Lote,
        val tipoNombre: String,
        val tipoEsFinal: Boolean,
    )

    companion object {
        private const val ORIGEN_USUARIO_INTERNO = 1
    }
}
