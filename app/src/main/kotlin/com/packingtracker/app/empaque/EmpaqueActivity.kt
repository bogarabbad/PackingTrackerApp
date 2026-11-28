package com.packingtracker.app.empaque

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.packingtracker.app.R
import com.packingtracker.app.api.ApiCredentials
import com.packingtracker.app.api.ApiCredentialsResolver
import com.packingtracker.app.api.ApiException
import com.packingtracker.app.api.CreateLoteProductoRequest
import com.packingtracker.app.api.CreateLoteReferenciaRequest
import com.packingtracker.app.api.CreateLoteRequest
import com.packingtracker.app.api.CreateLoteUbicacionRequest
import com.packingtracker.app.api.Lote
import com.packingtracker.app.api.LoteContenido
import com.packingtracker.app.api.LoteDeleteBlockers
import com.packingtracker.app.api.LoteDetalle
import com.packingtracker.app.api.LoteEstatus
import com.packingtracker.app.api.LoteProducto
import com.packingtracker.app.api.LoteReferencia
import com.packingtracker.app.api.LoteTipo
import com.packingtracker.app.api.LoteUbicacion
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.api.ReferenciaTipo
import com.packingtracker.app.api.Ubicacion
import com.packingtracker.app.api.UnidadMedida
import com.packingtracker.app.api.UpdateLoteProductoRequest
import com.packingtracker.app.api.UpdateLoteReferenciaRequest
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.databinding.ActivityEmpaqueBinding
import com.packingtracker.app.databinding.ItemLoteContenidoBinding
import com.packingtracker.app.databinding.ItemLoteProductoBinding
import com.packingtracker.app.databinding.ItemLoteReferenciaBinding
import com.packingtracker.app.databinding.ItemLoteTipoBinding
import com.packingtracker.app.login.LoginActivity
import com.packingtracker.app.ui.ClientDateTime
import com.packingtracker.app.ui.UserSessionBar
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmpaqueActivity : AppCompatActivity(), LotesListaFragment.Listener, LotesListaFragment.Host {
    private lateinit var binding: ActivityEmpaqueBinding
    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore

    private var tipos: List<LoteTipo> = emptyList()
    private var unidadesMedidaById: Map<Int, UnidadMedida> = emptyMap()
    private var referenciasTiposById: Map<Int, ReferenciaTipo> = emptyMap()
    private var unidadesForTipo: List<UnidadMedida> = emptyList()
    private var selectedTipo: LoteTipo? = null
    private var loadedLote: LoadedLote? = null
    private var filterFinales: Boolean = true
    private var productosFilterQuery: String = ""
    private var referenciasFilterQuery: String = ""
    private val tipoButtons = mutableMapOf<Int, MaterialButton>()
    private val quantityFormat = DecimalFormat(
        "0.###",
        DecimalFormatSymbols(Locale.US),
    )
    private var suppressProductosFilterCallback = false
    private var suppressReferenciasFilterCallback = false

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
            onBeforeBack = { closeLotesListaIfOpen() },
        )

        binding.retryButton.setOnClickListener { loadCatalog() }
        binding.createButton.setOnClickListener { createLote() }
        binding.clearButton.setOnClickListener { clearSelection() }
        binding.printEtiquetaButton.setOnClickListener { openLoteEtiquetaPdf() }
        binding.deleteLoteButton.setOnClickListener { confirmDeleteManagedLote() }
        binding.filterFinalButton.setOnClickListener { setFilter(finales = true) }
        binding.filterNoFinalButton.setOnClickListener { setFilter(finales = false) }
        binding.referenciasRefreshButton.setOnClickListener { refreshReferencias() }
        binding.productosRefreshButton.setOnClickListener { refreshProductos() }
        binding.loteIdInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitEditorAction(actionId, event)) {
                lookupLoteFromInput()
                true
            } else {
                false
            }
        }
        binding.productosScanInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitEditorAction(actionId, event)) {
                submitProductoScan()
                true
            } else {
                false
            }
        }
        binding.referenciasScanInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitEditorAction(actionId, event)) {
                submitReferenciaScan()
                true
            } else {
                false
            }
        }
        binding.destinoClaveInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitEditorAction(actionId, event)) {
                submitDestinoScan()
                true
            } else {
                false
            }
        }
        binding.productosFilterInput.addTextChangedListener(
            simpleWatcher { text ->
                if (suppressProductosFilterCallback) return@simpleWatcher
                productosFilterQuery = text.trim()
                loadedLote?.takeIf { it.tipoEsFinal }?.let { renderLoteContents(it) }
            },
        )
        binding.referenciasFilterInput.addTextChangedListener(
            simpleWatcher { text ->
                if (suppressReferenciasFilterCallback) return@simpleWatcher
                referenciasFilterQuery = text.trim()
                loadedLote?.let { renderLoteReferencias(it) }
            },
        )

        applyFilterIconStyles()
        updateManagedLoteUi()
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

    private fun simpleWatcher(onChanged: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChanged(s?.toString().orEmpty())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UserSessionBar.bind(binding.userSession)
    }

    override fun onLoteSelected(lote: Lote, tipoNombre: String) {
        binding.loteIdInput.setText(lote.id.toString())
        lookupLoteById(lote.id)
    }

    private fun openLotesModal() {
        if (supportFragmentManager.findFragmentByTag(LotesListaFragment.TAG) != null) {
            binding.lotesListaOverlay.visibility = View.VISIBLE
            return
        }
        binding.lotesListaOverlay.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.lotesListaOverlay, LotesListaFragment.newInstance(), LotesListaFragment.TAG)
            .commit()
    }

    override fun closeLotesLista() {
        supportFragmentManager.findFragmentByTag(LotesListaFragment.TAG)?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commit()
        }
        binding.lotesListaOverlay.visibility = View.GONE
    }

    private fun closeLotesListaIfOpen(): Boolean {
        if (binding.lotesListaOverlay.visibility != View.VISIBLE) {
            return false
        }
        closeLotesLista()
        return true
    }

    private fun lookupLoteFromInput() {
        val raw = binding.loteIdInput.text?.toString().orEmpty().trim()
        val id = raw.filter { it.isDigit() }.toIntOrNull()
        if (id == null || id < 1) {
            Toast.makeText(this, R.string.empaque_lote_invalid_id, Toast.LENGTH_SHORT).show()
            return
        }
        binding.loteIdInput.setText(id.toString())
        lookupLoteById(id)
    }

    private fun lookupLoteById(loteId: Int) {
        val config = configStore.load()
        val session = sessionStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, session)
        if (credentials == null) {
            Toast.makeText(this, R.string.empaque_error_auth, Toast.LENGTH_LONG).show()
            return
        }

        hideKeyboard()
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    loadFullLote(PackingTrackerApi(config.serverUrl), credentials, loteId)
                }
            }
            setCreating(false)
            result.onSuccess { snapshot ->
                applyLoteDetalle(snapshot)
                Toast.makeText(
                    this@EmpaqueActivity,
                    getString(
                        R.string.empaque_loaded_lote,
                        snapshot.detalle.lote.id,
                        tipoNombreFor(snapshot.detalle.lote.idTipo),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                if (error is ApiException && (error.code == "not_found" || error.httpStatus == 404)) {
                    clearManagedLote(keepInput = true)
                    Toast.makeText(
                        this@EmpaqueActivity,
                        getString(R.string.empaque_lote_not_found, loteId),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(this@EmpaqueActivity, mapError(error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadFullLote(
        api: PackingTrackerApi,
        credentials: ApiCredentials,
        loteId: Int,
    ): LoteSnapshot {
        val detalle = api.getLoteDetalle(credentials, loteId)
        val blockers = api.getLoteDeleteBlockers(credentials, loteId)
        val tipoEsFinal = tipos.firstOrNull { it.id == detalle.lote.idTipo }?.esFinal == true
        if (tipoEsFinal && unidadesMedidaById.isEmpty()) {
            unidadesMedidaById = api.listUnidadesMedida(credentials).associateBy { it.id }
        }
        if (referenciasTiposById.isEmpty()) {
            referenciasTiposById = api.listReferenciasTipos(credentials).associateBy { it.id }
        }
        val unidadesTipo = if (tipoEsFinal) {
            api.listLotesTiposUnidades(credentials, idTipo = detalle.lote.idTipo)
                .mapNotNull { unidadesMedidaById[it.idUnidad] }
        } else {
            emptyList()
        }
        val referencias = api.listLotesReferencias(credentials, idLote = loteId).items
        val contenido = if (tipoEsFinal) {
            LoteContenido(
                productos = api.listLotesProductos(credentials, idLote = loteId).items,
                referencias = referencias,
            )
        } else {
            LoteContenido(
                hijos = api.listLotes(credentials, page = 1, items = 100, padreId = loteId).items,
                referencias = referencias,
            )
        }
        val origen = loadLoteUbicacion(api, credentials, loteId, ID_TIPO_ORIGEN)
        val destino = loadLoteUbicacion(api, credentials, loteId, ID_TIPO_DESTINO)
        return LoteSnapshot(
            detalle = detalle,
            blockers = blockers,
            contenido = contenido,
            unidadesTipo = unidadesTipo,
            origenRelacion = origen.first,
            origenUbicacion = origen.second,
            destinoRelacion = destino.first,
            destinoUbicacion = destino.second,
        )
    }

    private fun loadLoteUbicacion(
        api: PackingTrackerApi,
        credentials: ApiCredentials,
        loteId: Int,
        idTipo: Int,
    ): Pair<LoteUbicacion?, Ubicacion?> {
        val relacion = api.listLotesUbicaciones(
            credentials = credentials,
            idLote = loteId,
            idTipo = idTipo,
            page = 1,
            items = 1,
        ).items.firstOrNull() ?: return null to null
        val ubicacion = runCatching {
            api.getUbicacion(credentials, relacion.ubicacionId)
        }.getOrNull()
        return relacion to ubicacion
    }

    private fun applyLoteDetalle(snapshot: LoteSnapshot) {
        val detalle = snapshot.detalle
        val tipo = tipos.firstOrNull { it.id == detalle.lote.idTipo }
        unidadesForTipo = snapshot.unidadesTipo
        loadedLote = LoadedLote(
            lote = detalle.lote,
            tipoNombre = tipo?.nombre ?: getString(R.string.empaque_list_tipo_fallback, detalle.lote.idTipo),
            tipoEsFinal = tipo?.esFinal ?: false,
            estatus = detalle.estatus,
            expedicionId = detalle.expedicionId,
            recepcionId = detalle.recepcionId,
            deleteBlockers = snapshot.blockers,
            contenido = snapshot.contenido,
            origenRelacion = snapshot.origenRelacion,
            origenUbicacion = snapshot.origenUbicacion,
            destinoRelacion = snapshot.destinoRelacion,
            destinoUbicacion = snapshot.destinoUbicacion,
        )
        if (binding.loteIdInput.text?.toString() != detalle.lote.id.toString()) {
            binding.loteIdInput.setText(detalle.lote.id.toString())
        }
        syncTipoFromLoadedLote()
        updateManagedLoteUi()
        updateActionButtons(busy = false)
    }

    private fun syncTipoFromLoadedLote() {
        val loaded = loadedLote ?: return
        val tipo = tipos.firstOrNull { it.id == loaded.lote.idTipo } ?: return
        if (filterFinales != tipo.esFinal) {
            filterFinales = tipo.esFinal
            applyFilterIconStyles()
            renderTipos()
        }
        selectedTipo = tipo
        updateSelectionUi()
        refreshTipoButtonStyles()
    }

    private fun tipoNombreFor(idTipo: Int): String {
        return tipos.firstOrNull { it.id == idTipo }?.nombre
            ?: getString(R.string.empaque_list_tipo_fallback, idTipo)
    }

    private fun clearManagedLote(keepInput: Boolean = false) {
        loadedLote = null
        unidadesForTipo = emptyList()
        productosFilterQuery = ""
        referenciasFilterQuery = ""
        suppressProductosFilterCallback = true
        suppressReferenciasFilterCallback = true
        binding.productosFilterInput.setText("")
        binding.referenciasFilterInput.setText("")
        binding.productosScanInput.setText("")
        binding.referenciasScanInput.setText("")
        binding.destinoClaveInput.setText("")
        suppressProductosFilterCallback = false
        suppressReferenciasFilterCallback = false
        if (!keepInput) {
            binding.loteIdInput.setText("")
        }
        updateManagedLoteUi()
        updateActionButtons(busy = false)
    }

    private fun clearSelection() {
        val hasContent = loadedLote != null ||
            !binding.loteIdInput.text.isNullOrBlank()
        if (!hasContent) return
        clearManagedLote(keepInput = false)
        Toast.makeText(this, R.string.empaque_selection_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun updateManagedLoteUi() {
        val loaded = loadedLote
        if (loaded == null) {
            binding.loteInfoSection.visibility = View.GONE
            binding.loteTipoValue.text = ""
            binding.lotePadreValue.text = ""
            binding.loteCreadoValue.text = ""
            binding.loteEstatusText.text = ""
            binding.ubicacionesSection.visibility = View.GONE
            binding.origenValue.text = ""
            binding.destinoValue.text = ""
            binding.destinoClaveInput.setText("")
            binding.referenciasScanSection.visibility = View.GONE
            binding.loteReferenciasSection.visibility = View.GONE
            binding.loteReferenciasList.removeAllViews()
            binding.loteReferenciasEmpty.visibility = View.GONE
            binding.productosScanSection.visibility = View.GONE
            binding.loteContentsSection.visibility = View.GONE
            binding.loteContentsList.removeAllViews()
            binding.loteContentsEmpty.visibility = View.GONE
            binding.productosFilterInput.visibility = View.GONE
            binding.productosRefreshButton.visibility = View.GONE
            binding.deleteBlockedText.visibility = View.GONE
            binding.deleteBlockedText.text = ""
            return
        }

        val clase = if (loaded.tipoEsFinal) {
            getString(R.string.empaque_lote_detail_final)
        } else {
            getString(R.string.empaque_lote_detail_no_final)
        }
        binding.loteTipoValue.text = getString(
            R.string.empaque_lote_detail_tipo,
            loaded.tipoNombre,
            clase,
        )
        binding.lotePadreValue.text = loaded.lote.padreId?.let {
            getString(R.string.empaque_lote_detail_padre, it)
        } ?: getString(R.string.empaque_lote_detail_sin_padre)
        binding.loteCreadoValue.text = getString(
            R.string.empaque_lote_detail_creado,
            ClientDateTime.format(loaded.lote.creadoEn),
        )
        binding.loteEstatusText.text = formatEstatus(loaded)
        binding.loteInfoSection.visibility = View.VISIBLE
        renderUbicaciones(loaded)
        renderLoteContents(loaded)
        renderLoteReferencias(loaded)

        when {
            !loaded.canDeleteByEstatus -> {
                binding.deleteBlockedText.visibility = View.VISIBLE
                binding.deleteBlockedText.text = getString(R.string.empaque_delete_blocked_embarcado)
            }
            !loaded.deleteBlockers.canDelete -> {
                binding.deleteBlockedText.visibility = View.VISIBLE
                binding.deleteBlockedText.text = getString(
                    R.string.empaque_delete_blocked,
                    formatDeleteBlockers(loaded.deleteBlockers),
                )
            }
            else -> {
                binding.deleteBlockedText.visibility = View.GONE
                binding.deleteBlockedText.text = ""
            }
        }
    }

    private fun renderUbicaciones(loaded: LoadedLote) {
        binding.ubicacionesSection.visibility = View.VISIBLE
        binding.origenValue.text = when {
            loaded.origenUbicacion != null -> loaded.origenUbicacion.displayLabel()
            loaded.origenRelacion != null ->
                getString(R.string.empaque_destino_ubicacion_fallback, loaded.origenRelacion.ubicacionId)
            else -> getString(R.string.empaque_origen_sin_asignar)
        }
        binding.destinoValue.text = when {
            loaded.destinoUbicacion != null -> loaded.destinoUbicacion.displayLabel()
            loaded.destinoRelacion != null ->
                getString(R.string.empaque_destino_ubicacion_fallback, loaded.destinoRelacion.ubicacionId)
            else -> getString(R.string.empaque_destino_sin_asignar)
        }
    }

    private fun renderLoteContents(loaded: LoadedLote) {
        binding.loteContentsSection.visibility = View.VISIBLE
        binding.loteContentsList.removeAllViews()

        if (loaded.tipoEsFinal) {
            binding.productosScanSection.visibility = View.VISIBLE
            binding.productosRefreshButton.visibility = View.VISIBLE
            binding.productosFilterInput.visibility = View.VISIBLE
            bindProductosScanUnidades()
            val productos = filterProductos(loaded.contenido.productos)
            binding.loteContentsTitle.text = getString(
                R.string.empaque_lote_contents_productos,
                loaded.contenido.productos.size,
            )
            if (productos.isEmpty()) {
                binding.loteContentsEmpty.visibility = View.VISIBLE
                binding.loteContentsEmpty.setText(R.string.empaque_lote_contents_productos_empty)
            } else {
                binding.loteContentsEmpty.visibility = View.GONE
                productos.forEach { producto ->
                    addProductoRow(producto)
                }
            }
            return
        }

        binding.productosScanSection.visibility = View.GONE
        binding.productosRefreshButton.visibility = View.GONE
        binding.productosFilterInput.visibility = View.GONE
        val hijos = loaded.contenido.hijos
        binding.loteContentsTitle.text = getString(
            R.string.empaque_lote_contents_hijos,
            hijos.size,
        )
        if (hijos.isEmpty()) {
            binding.loteContentsEmpty.visibility = View.VISIBLE
            binding.loteContentsEmpty.setText(R.string.empaque_lote_contents_hijos_empty)
        } else {
            binding.loteContentsEmpty.visibility = View.GONE
            hijos.forEach { hijo ->
                addContenidoLine(
                    text = getString(
                        R.string.empaque_lote_contenido_hijo,
                        hijo.id,
                        tipoNombreFor(hijo.idTipo),
                    ),
                    parent = binding.loteContentsList,
                )
            }
        }
    }

    private fun renderLoteReferencias(loaded: LoadedLote) {
        val referenciasAll = loaded.contenido.referencias
        val referencias = filterReferencias(referenciasAll)
        binding.referenciasScanSection.visibility = View.VISIBLE
        binding.loteReferenciasSection.visibility = View.VISIBLE
        binding.loteReferenciasList.removeAllViews()
        bindReferenciasScanTipos()
        binding.loteReferenciasTitle.text = getString(
            R.string.empaque_lote_contents_referencias,
            referenciasAll.size,
        )
        if (referencias.isEmpty()) {
            binding.loteReferenciasEmpty.visibility = View.VISIBLE
            binding.loteReferenciasEmpty.setText(R.string.empaque_lote_contents_referencias_empty)
            return
        }
        binding.loteReferenciasEmpty.visibility = View.GONE
        referencias.forEach { referencia ->
            addReferenciaRow(referencia)
        }
    }

    private fun filterProductos(productos: List<LoteProducto>): List<LoteProducto> {
        val query = productosFilterQuery.trim()
        if (query.isEmpty()) return productos
        return productos.filter { it.claveProducto.contains(query, ignoreCase = true) }
    }

    private fun filterReferencias(referencias: List<LoteReferencia>): List<LoteReferencia> {
        val query = referenciasFilterQuery.trim()
        if (query.isEmpty()) return referencias
        return referencias.filter { referencia ->
            referencia.referencia.contains(query, ignoreCase = true) ||
                referencia.descripcion.orEmpty().contains(query, ignoreCase = true) ||
                (referenciasTiposById[referencia.idTipo]?.nombre.orEmpty()
                    .contains(query, ignoreCase = true))
        }
    }

    private fun bindProductosScanUnidades() {
        val labels = unidadesForTipo.map { it.codigo.ifBlank { it.nombre } }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val previous = binding.productosScanUnidadSpinner.selectedItemPosition
        binding.productosScanUnidadSpinner.adapter = adapter
        if (labels.isNotEmpty()) {
            binding.productosScanUnidadSpinner.setSelection(previous.coerceIn(0, labels.lastIndex))
        }
    }

    private fun bindReferenciasScanTipos() {
        val tiposRef = referenciasTiposOrdered()
        val labels = tiposRef.map { it.nombre }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val previous = binding.referenciasScanTipoSpinner.selectedItemPosition
        binding.referenciasScanTipoSpinner.adapter = adapter
        if (labels.isNotEmpty()) {
            binding.referenciasScanTipoSpinner.setSelection(previous.coerceIn(0, labels.lastIndex))
        }
    }

    private fun referenciasTiposOrdered(): List<ReferenciaTipo> {
        return referenciasTiposById.values.sortedBy { it.nombre.lowercase(Locale.getDefault()) }
    }

    private fun addContenidoLine(
        text: String,
        parent: ViewGroup,
    ) {
        val item = ItemLoteContenidoBinding.inflate(LayoutInflater.from(this), parent, false)
        item.contenidoLineText.text = text
        item.contenidoDeleteButton.visibility = View.GONE
        parent.addView(item.root)
    }

    private fun addProductoRow(producto: LoteProducto) {
        val item = ItemLoteProductoBinding.inflate(
            LayoutInflater.from(this),
            binding.loteContentsList,
            false,
        )
        item.productoClaveInput.setText(producto.claveProducto)
        item.productoCantidadInput.setText(quantityFormat.format(producto.cantidad))
        val labels = unidadesForTipo.map { it.codigo.ifBlank { it.nombre } }
        val unidades = if (unidadesForTipo.any { it.id == producto.idUnidad }) {
            unidadesForTipo
        } else {
            val current = unidadesMedidaById[producto.idUnidad]
            if (current != null) unidadesForTipo + current else unidadesForTipo
        }
        val unidadLabels = unidades.map { it.codigo.ifBlank { it.nombre } }
        item.productoUnidadSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            unidadLabels.ifEmpty { labels },
        )
        val selectedIndex = unidades.indexOfFirst { it.id == producto.idUnidad }.takeIf { it >= 0 } ?: 0
        if (unidades.isNotEmpty()) {
            item.productoUnidadSpinner.setSelection(selectedIndex)
        }
        item.productoSaveButton.visibility = View.GONE

        val refreshDirty = {
            val clave = item.productoClaveInput.text?.toString().orEmpty().trim()
            val cantidad = item.productoCantidadInput.text?.toString()?.trim()?.toDoubleOrNull()
            val unidad = unidades.getOrNull(item.productoUnidadSpinner.selectedItemPosition)
            val dirty = clave != producto.claveProducto ||
                cantidad == null ||
                cantidad != producto.cantidad ||
                unidad?.id != producto.idUnidad
            item.productoSaveButton.visibility = if (dirty) View.VISIBLE else View.GONE
        }
        item.productoClaveInput.addTextChangedListener(simpleWatcher { refreshDirty() })
        item.productoCantidadInput.addTextChangedListener(simpleWatcher { refreshDirty() })
        item.productoUnidadSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshDirty()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        item.productoSaveButton.setOnClickListener {
            val clave = item.productoClaveInput.text?.toString().orEmpty().trim()
            val cantidad = item.productoCantidadInput.text?.toString()?.trim()?.toDoubleOrNull()
            val unidad = unidades.getOrNull(item.productoUnidadSpinner.selectedItemPosition)
            if (clave.isEmpty() || cantidad == null || cantidad <= 0.0 || unidad == null) {
                Toast.makeText(this, R.string.empaque_producto_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            updateProducto(
                producto.id,
                UpdateLoteProductoRequest(
                    idLote = producto.idLote,
                    claveProducto = clave,
                    cantidad = cantidad,
                    idUnidad = unidad.id,
                ),
            )
        }
        item.productoDeleteButton.setOnClickListener { confirmDeleteProducto(producto) }
        binding.loteContentsList.addView(item.root)
    }

    private fun addReferenciaRow(referencia: LoteReferencia) {
        val item = ItemLoteReferenciaBinding.inflate(
            LayoutInflater.from(this),
            binding.loteReferenciasList,
            false,
        )
        val tiposRef = referenciasTiposOrdered()
        item.referenciaTipoSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            tiposRef.map { it.nombre },
        )
        val selectedIndex = tiposRef.indexOfFirst { it.id == referencia.idTipo }.takeIf { it >= 0 } ?: 0
        if (tiposRef.isNotEmpty()) {
            item.referenciaTipoSpinner.setSelection(selectedIndex)
        }
        item.referenciaValorInput.setText(referencia.referencia)
        item.referenciaSaveButton.visibility = View.GONE

        val refreshDirty = {
            val tipo = tiposRef.getOrNull(item.referenciaTipoSpinner.selectedItemPosition)
            val valor = item.referenciaValorInput.text?.toString().orEmpty().trim()
            val dirty = tipo?.id != referencia.idTipo || valor != referencia.referencia
            item.referenciaSaveButton.visibility = if (dirty) View.VISIBLE else View.GONE
        }
        item.referenciaValorInput.addTextChangedListener(simpleWatcher { refreshDirty() })
        item.referenciaTipoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshDirty()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        item.referenciaSaveButton.setOnClickListener {
            val tipo = tiposRef.getOrNull(item.referenciaTipoSpinner.selectedItemPosition)
            val valor = item.referenciaValorInput.text?.toString().orEmpty().trim()
            if (tipo == null || valor.isEmpty()) {
                Toast.makeText(this, R.string.empaque_referencia_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            updateReferencia(
                referencia.id,
                UpdateLoteReferenciaRequest(
                    idLote = referencia.idLote,
                    idTipo = tipo.id,
                    referencia = valor,
                    descripcion = referencia.descripcion,
                ),
            )
        }
        item.referenciaDeleteButton.setOnClickListener { confirmDeleteReferencia(referencia) }
        binding.loteReferenciasList.addView(item.root)
    }

    private fun refreshReferencias(showToast: Boolean = true) {
        val loaded = loadedLote ?: return
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    if (referenciasTiposById.isEmpty()) {
                        referenciasTiposById = api.listReferenciasTipos(credentials).associateBy { it.id }
                    }
                    api.listLotesReferencias(credentials, idLote = loaded.lote.id).items
                }
            }
            setCreating(false)
            result.onSuccess { referencias ->
                loadedLote = loaded.copy(contenido = loaded.contenido.copy(referencias = referencias))
                updateManagedLoteUi()
                if (showToast) {
                    Toast.makeText(
                        this@EmpaqueActivity,
                        R.string.empaque_contenido_refreshed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshProductos(showToast: Boolean = true) {
        val loaded = loadedLote ?: return
        if (!loaded.tipoEsFinal) return
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    if (unidadesMedidaById.isEmpty()) {
                        unidadesMedidaById = api.listUnidadesMedida(credentials).associateBy { it.id }
                    }
                    api.listLotesProductos(credentials, idLote = loaded.lote.id).items
                }
            }
            setCreating(false)
            result.onSuccess { productos ->
                loadedLote = loaded.copy(contenido = loaded.contenido.copy(productos = productos))
                updateManagedLoteUi()
                if (showToast) {
                    Toast.makeText(
                        this@EmpaqueActivity,
                        R.string.empaque_contenido_refreshed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitProductoScan() {
        val loaded = loadedLote ?: return
        if (!loaded.tipoEsFinal) return
        val clave = binding.productosScanInput.text?.toString().orEmpty().trim()
        if (clave.isEmpty()) return
        if (unidadesForTipo.isEmpty()) {
            Toast.makeText(this, R.string.empaque_producto_no_unidades, Toast.LENGTH_LONG).show()
            return
        }
        val unidad = unidadesForTipo.getOrNull(binding.productosScanUnidadSpinner.selectedItemPosition)
            ?: unidadesForTipo.first()
        binding.productosScanInput.setText("")
        createProducto(
            CreateLoteProductoRequest(
                idLote = loaded.lote.id,
                claveProducto = clave,
                cantidad = 1.0,
                idUnidad = unidad.id,
            ),
        )
        binding.productosScanInput.requestFocus()
    }

    private fun submitDestinoScan() {
        val loaded = loadedLote ?: return
        if (!loaded.canDeleteByEstatus) {
            Toast.makeText(this, R.string.empaque_destino_blocked_estatus, Toast.LENGTH_LONG).show()
            return
        }
        if (!sessionStore.load().hasUbicacion) {
            Toast.makeText(this, R.string.error_usuario_sin_ubicacion, Toast.LENGTH_LONG).show()
            return
        }
        val clave = binding.destinoClaveInput.text?.toString().orEmpty().trim()
        if (clave.isEmpty()) return
        binding.destinoClaveInput.setText("")
        assignDestino(clave)
        binding.destinoClaveInput.requestFocus()
    }

    private fun assignDestino(claveUbicacion: String) {
        val loaded = loadedLote ?: return
        val credentials = resolveCredentials() ?: return
        val session = sessionStore.load()
        val usuarioId = session.usuarioId
        val idOrigenUsuario = if (usuarioId != null) ORIGEN_USUARIO_INTERNO else null
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(configStore.load().serverUrl)
                    val ubicacion = api.findUbicacionByCodigo(credentials, claveUbicacion)
                        ?: throw ApiException(code = "ubicacion_not_found", httpStatus = 404)
                    val request = CreateLoteUbicacionRequest(
                        idLote = loaded.lote.id,
                        ubicacionId = ubicacion.id,
                        idTipo = ID_TIPO_DESTINO,
                        usuarioId = usuarioId,
                        idOrigenUsuario = idOrigenUsuario,
                    )
                    val relacion = loaded.destinoRelacion
                    val saved = if (relacion == null) {
                        api.createLoteUbicacion(credentials, request)
                    } else {
                        api.updateLoteUbicacion(credentials, relacion.id, request)
                    }
                    val blockers = api.getLoteDeleteBlockers(credentials, loaded.lote.id)
                    Triple(saved, ubicacion, blockers)
                }
            }
            setCreating(false)
            result.onSuccess { (relacion, ubicacion, blockers) ->
                val current = loadedLote ?: return@onSuccess
                loadedLote = current.copy(
                    destinoRelacion = relacion,
                    destinoUbicacion = ubicacion,
                    deleteBlockers = blockers,
                )
                updateManagedLoteUi()
                updateActionButtons(busy = false)
                val message = if (current.destinoRelacion == null) {
                    R.string.empaque_destino_asignado
                } else {
                    R.string.empaque_destino_actualizado
                }
                Toast.makeText(this@EmpaqueActivity, message, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapDestinoError(error, claveUbicacion), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitReferenciaScan() {
        val loaded = loadedLote ?: return
        val valor = binding.referenciasScanInput.text?.toString().orEmpty().trim()
        if (valor.isEmpty()) return
        val tiposRef = referenciasTiposOrdered()
        if (tiposRef.isEmpty()) {
            Toast.makeText(this, R.string.empaque_referencia_no_tipos, Toast.LENGTH_LONG).show()
            return
        }
        val tipo = tiposRef.getOrNull(binding.referenciasScanTipoSpinner.selectedItemPosition)
            ?: tiposRef.first()
        val session = sessionStore.load()
        binding.referenciasScanInput.setText("")
        createReferencia(
            CreateLoteReferenciaRequest(
                idLote = loaded.lote.id,
                idTipo = tipo.id,
                referencia = valor,
                usuarioId = session.usuarioId,
                idOrigenUsuario = if (session.usuarioId != null) ORIGEN_USUARIO_INTERNO else null,
            ),
        )
        binding.referenciasScanInput.requestFocus()
    }

    private fun createProducto(request: CreateLoteProductoRequest) {
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(configStore.load().serverUrl).createLoteProducto(credentials, request)
                }
            }
            setCreating(false)
            result.onSuccess {
                Toast.makeText(this@EmpaqueActivity, R.string.empaque_producto_added, Toast.LENGTH_SHORT).show()
                refreshProductos(showToast = false)
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapProductoError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createReferencia(request: CreateLoteReferenciaRequest) {
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(configStore.load().serverUrl).createLoteReferencia(credentials, request)
                }
            }
            setCreating(false)
            result.onSuccess {
                Toast.makeText(this@EmpaqueActivity, R.string.empaque_referencia_added, Toast.LENGTH_SHORT).show()
                refreshReferencias(showToast = false)
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapReferenciaError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateProducto(id: Int, request: UpdateLoteProductoRequest) {
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(configStore.load().serverUrl)
                        .updateLoteProducto(credentials, id, request)
                }
            }
            setCreating(false)
            result.onSuccess {
                Toast.makeText(this@EmpaqueActivity, R.string.empaque_producto_updated, Toast.LENGTH_SHORT).show()
                refreshProductos(showToast = false)
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapProductoError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateReferencia(id: Int, request: UpdateLoteReferenciaRequest) {
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(configStore.load().serverUrl)
                        .updateLoteReferencia(credentials, id, request)
                }
            }
            setCreating(false)
            result.onSuccess {
                Toast.makeText(this@EmpaqueActivity, R.string.empaque_referencia_updated, Toast.LENGTH_SHORT).show()
                refreshReferencias(showToast = false)
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapReferenciaError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteProducto(producto: LoteProducto) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.empaque_contenido_delete)
            .setMessage(getString(R.string.empaque_producto_delete_confirm, producto.claveProducto))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.empaque_contenido_delete) { _, _ ->
                deleteProducto(producto.id)
            }
            .show()
    }

    private fun confirmDeleteReferencia(referencia: LoteReferencia) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.empaque_contenido_delete)
            .setMessage(getString(R.string.empaque_referencia_delete_confirm, referencia.referencia))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.empaque_contenido_delete) { _, _ ->
                deleteReferencia(referencia.id)
            }
            .show()
    }

    private fun deleteProducto(id: Int) {
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(configStore.load().serverUrl).deleteLoteProducto(credentials, id)
                }
            }
            setCreating(false)
            result.onSuccess {
                Toast.makeText(this@EmpaqueActivity, R.string.empaque_producto_deleted, Toast.LENGTH_SHORT).show()
                refreshProductos(showToast = false)
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapProductoError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteReferencia(id: Int) {
        val credentials = resolveCredentials() ?: return
        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PackingTrackerApi(configStore.load().serverUrl).deleteLoteReferencia(credentials, id)
                }
            }
            setCreating(false)
            result.onSuccess {
                Toast.makeText(this@EmpaqueActivity, R.string.empaque_referencia_deleted, Toast.LENGTH_SHORT).show()
                refreshReferencias(showToast = false)
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapReferenciaError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resolveCredentials(): ApiCredentials? {
        val credentials = ApiCredentialsResolver.resolve(configStore.load(), sessionStore.load())
        if (credentials == null) {
            Toast.makeText(this, R.string.empaque_error_auth, Toast.LENGTH_LONG).show()
        }
        return credentials
    }

    private fun formatEstatus(loaded: LoadedLote): String {
        return when (loaded.estatus) {
            LoteEstatus.DISPONIBLE -> getString(R.string.empaque_lote_estatus_disponible)
            LoteEstatus.CONTENIDO -> getString(R.string.empaque_lote_estatus_contenido)
            LoteEstatus.EMBARCADO -> {
                val expedicionId = loaded.expedicionId
                if (expedicionId != null) {
                    getString(R.string.empaque_lote_estatus_embarcado, expedicionId)
                } else {
                    getString(R.string.empaque_lote_estatus_embarcado_simple)
                }
            }
            LoteEstatus.RECIBIDO -> {
                val recepcionId = loaded.recepcionId
                if (recepcionId != null) {
                    getString(R.string.empaque_lote_estatus_recibido, recepcionId)
                } else {
                    getString(R.string.empaque_lote_estatus_recibido_simple)
                }
            }
        }
    }

    private fun updateActionButtons(busy: Boolean) {
        val hasLote = loadedLote != null
        val canCreate = !busy && !hasLote && selectedTipo != null && sessionStore.load().hasUbicacion
        val canDelete = !busy && hasLote && (loadedLote?.canDelete == true)
        val canPrint = !busy && hasLote
        val canClear = !busy && (hasLote || !binding.loteIdInput.text.isNullOrBlank())
        val canChangeTipo = !busy && !hasLote

        if (hasLote) {
            binding.createButton.visibility = View.GONE
            binding.createButton.isEnabled = false
            binding.deleteLoteButton.visibility = View.VISIBLE
            binding.deleteLoteButton.isEnabled = canDelete
            binding.printEtiquetaButton.visibility = View.VISIBLE
            binding.printEtiquetaButton.isEnabled = canPrint
        } else {
            binding.deleteLoteButton.visibility = View.GONE
            binding.deleteLoteButton.isEnabled = false
            binding.printEtiquetaButton.visibility = View.GONE
            binding.printEtiquetaButton.isEnabled = false
            binding.createButton.visibility = View.VISIBLE
            binding.createButton.isEnabled = canCreate
        }

        binding.loteIdInput.isEnabled = !busy
        binding.clearButton.isEnabled = canClear
        binding.clearButton.alpha = if (canClear) 1f else 0.35f
        binding.createButton.alpha = if (canCreate) 1f else 0.35f
        binding.deleteLoteButton.alpha = if (canDelete) 1f else 0.35f
        binding.printEtiquetaButton.alpha = if (canPrint) 1f else 0.35f
        updateTipoControlsEnabled(canChangeTipo)
        val canEditContenido = !busy && hasLote
        binding.referenciasRefreshButton.isEnabled = canEditContenido
        binding.productosRefreshButton.isEnabled = canEditContenido
        binding.productosFilterInput.isEnabled = canEditContenido
        binding.productosScanInput.isEnabled = canEditContenido
        binding.productosScanUnidadSpinner.isEnabled = canEditContenido
        binding.referenciasFilterInput.isEnabled = canEditContenido
        binding.referenciasScanInput.isEnabled = canEditContenido
        binding.referenciasScanTipoSpinner.isEnabled = canEditContenido
        val canEditDestino = canEditContenido &&
            (loadedLote?.canDeleteByEstatus == true) &&
            sessionStore.load().hasUbicacion
        binding.destinoClaveInput.isEnabled = canEditDestino
    }

    private fun updateTipoControlsEnabled(enabled: Boolean) {
        binding.filterFinalButton.isEnabled = enabled
        binding.filterNoFinalButton.isEnabled = enabled
        binding.filterFinalButton.alpha = if (enabled) 1f else 0.4f
        binding.filterNoFinalButton.alpha = if (enabled) 1f else 0.4f
        binding.tiposScroll.alpha = if (enabled) 1f else 0.55f
        tipoButtons.values.forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.7f
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.loteIdInput.windowToken, 0)
    }

    private fun formatDeleteBlockers(blockers: LoteDeleteBlockers): String {
        val parts = buildList {
            if (blockers.hasProductos) add(getString(R.string.empaque_delete_blocked_productos))
            if (blockers.hasReferencias) add(getString(R.string.empaque_delete_blocked_referencias))
            if (blockers.hasHijos) add(getString(R.string.empaque_delete_blocked_hijos))
            if (blockers.hasUbicaciones) add(getString(R.string.empaque_delete_blocked_ubicaciones))
        }
        return parts.joinToString(", ")
    }

    private fun confirmDeleteManagedLote() {
        val loaded = loadedLote ?: return
        if (!loaded.canDeleteByEstatus) {
            Toast.makeText(this, R.string.empaque_delete_blocked_embarcado, Toast.LENGTH_LONG).show()
            return
        }
        if (!loaded.deleteBlockers.canDelete) {
            Toast.makeText(
                this,
                getString(R.string.empaque_delete_blocked, formatDeleteBlockers(loaded.deleteBlockers)),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
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
                clearManagedLote(keepInput = false)
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
                    PackingTrackerApi(config.serverUrl).listLotesTipos(credentials)
                }
            }

            result.onSuccess { loadedTipos ->
                tipos = loadedTipos.filter { it.activo }.sortedBy { it.nombre.lowercase() }
                if (tipos.isEmpty()) {
                    showError(getString(R.string.empaque_error_no_tipos))
                } else {
                    filterFinales = true
                    selectedTipo = defaultTipoCaja()
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

    private fun defaultTipoCaja(): LoteTipo? {
        return tipos.firstOrNull {
            it.esFinal && it.codigo.equals(CODIGO_TIPO_CAJA, ignoreCase = true)
        } ?: tipos.firstOrNull { it.esFinal }
    }

    private fun setFilter(finales: Boolean) {
        if (loadedLote != null) return
        if (filterFinales == finales) return
        filterFinales = finales
        applyFilterIconStyles()

        val stillVisible = selectedTipo?.let { it.esFinal == filterFinales } == true
        if (!stillVisible) {
            selectedTipo = if (finales) defaultTipoCaja() else null
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
        updateTipoControlsEnabled(enabled = loadedLote == null)
    }

    private fun selectTipo(tipo: LoteTipo) {
        if (loadedLote != null) return
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
            updateActionButtons(busy = false)
            return
        }

        binding.tipoSectionTitle.text = getString(R.string.empaque_filter_label_selected, tipo.nombre)
        updateActionButtons(busy = false)
    }

    private fun openLoteEtiquetaPdf() {
        val loaded = loadedLote ?: return
        val config = configStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, sessionStore.load())
        if (credentials == null) {
            Toast.makeText(this, R.string.empaque_error_auth, Toast.LENGTH_LONG).show()
            return
        }

        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val etiqueta = config.etiquetaTamano
                    val bytes = PackingTrackerApi(config.serverUrl)
                        .downloadLoteEtiquetaPdf(
                            credentials = credentials,
                            id = loaded.lote.id,
                            anchoMm = etiqueta.anchoMm,
                            altoMm = etiqueta.altoMm,
                        )
                    val file = File(cacheDir, "lote-${loaded.lote.id}-etiqueta.pdf")
                    file.writeBytes(bytes)
                    file
                }
            }
            setCreating(false)
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(
                    this@EmpaqueActivity,
                    "${packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(intent)
                    Toast.makeText(
                        this@EmpaqueActivity,
                        R.string.empaque_etiqueta_pdf_opened,
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        this@EmpaqueActivity,
                        R.string.empaque_etiqueta_pdf_no_viewer,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { error ->
                val message = when (error) {
                    is ApiException -> getString(R.string.empaque_etiqueta_pdf_error)
                    else -> getString(R.string.empaque_error_network)
                }
                Toast.makeText(this@EmpaqueActivity, message, Toast.LENGTH_LONG).show()
            }
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
        if (!session.hasUbicacion) {
            Toast.makeText(this, R.string.error_usuario_sin_ubicacion, Toast.LENGTH_LONG).show()
            return
        }

        val loaded = loadedLote
        val padreId = if (
            tipo.esFinal &&
            loaded != null &&
            !loaded.tipoEsFinal &&
            loaded.canDeleteByEstatus
        ) {
            loaded.lote.id
        } else {
            null
        }

        val usuarioId = session.usuarioId
        val request = CreateLoteRequest(
            idTipo = tipo.id,
            padreId = padreId,
            usuarioId = usuarioId,
            idOrigenUsuario = if (usuarioId != null) ORIGEN_USUARIO_INTERNO else null,
        )

        setCreating(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(config.serverUrl)
                    val created = api.createLote(credentials, request)
                    ensureOrigenFromUsuario(
                        api = api,
                        credentials = credentials,
                        loteId = created.id,
                        usuarioId = usuarioId,
                        usuarioUbicacionId = session.usuarioUbicacionId,
                    )
                    loadFullLote(api, credentials, created.id)
                }
            }
            setCreating(false)
            result.onSuccess { snapshot ->
                applyLoteDetalle(snapshot)
                Toast.makeText(
                    this@EmpaqueActivity,
                    getString(R.string.empaque_created_toast, snapshot.detalle.lote.id),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(this@EmpaqueActivity, mapError(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setCreating(busy: Boolean) {
        binding.createProgress.visibility = if (busy) View.VISIBLE else View.GONE
        updateActionButtons(busy)
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
                "invalid_padre_id" -> getString(R.string.empaque_error_padre)
                "invalid_tipo_lote" -> getString(R.string.empaque_error_reglas)
                "lote_has_productos" -> getString(R.string.empaque_error_delete_productos)
                "lote_has_referencias" -> getString(R.string.empaque_error_delete_referencias)
                "lote_has_hijos" -> getString(R.string.empaque_error_delete_hijos)
                "lote_has_ubicaciones" -> getString(R.string.empaque_error_delete_ubicaciones)
                "record_in_use", "http_409" -> getString(R.string.empaque_error_delete_in_use)
                "usuario_sin_ubicacion" -> getString(R.string.error_usuario_sin_ubicacion)
                "not_found", "http_404" -> getString(R.string.empaque_error_generic, error.code)
                else -> getString(R.string.empaque_error_generic, error.code)
            }
            else -> getString(R.string.empaque_error_network)
        }
    }

    private fun mapProductoError(error: Throwable): String {
        return when (error) {
            is ApiException -> when (error.code) {
                "unauthorized", "invalid_token", "token_expired" ->
                    getString(R.string.empaque_error_auth)
                "invalid_clave_producto", "invalid_cantidad", "invalid_id_unidad",
                "invalid_id_lote", "invalid_tipo_lote", "invalid_producto",
                -> getString(R.string.empaque_error_producto, error.code)
                else -> getString(R.string.empaque_error_producto, error.code)
            }
            else -> getString(R.string.empaque_error_network)
        }
    }

    private fun mapReferenciaError(error: Throwable): String {
        return when (error) {
            is ApiException -> when (error.code) {
                "unauthorized", "invalid_token", "token_expired" ->
                    getString(R.string.empaque_error_auth)
                else -> getString(R.string.empaque_error_referencia, error.code)
            }
            else -> getString(R.string.empaque_error_network)
        }
    }

    private fun mapDestinoError(error: Throwable, clave: String): String {
        return when (error) {
            is ApiException -> when (error.code) {
                "unauthorized", "invalid_token", "token_expired" ->
                    getString(R.string.empaque_error_auth)
                "ubicacion_not_found" -> getString(R.string.empaque_destino_not_found, clave)
                "destino_ya_definido" -> getString(R.string.empaque_error_destino_ya_definido)
                "invalid_ubicacion_id" -> getString(R.string.empaque_error_destino_ubicacion)
                "usuario_sin_ubicacion" -> getString(R.string.error_usuario_sin_ubicacion)
                else -> getString(R.string.empaque_error_destino, error.code)
            }
            else -> getString(R.string.empaque_error_network)
        }
    }

    private fun ensureOrigenFromUsuario(
        api: PackingTrackerApi,
        credentials: ApiCredentials,
        loteId: Int,
        usuarioId: Int?,
        usuarioUbicacionId: Int?,
    ) {
        if (usuarioId == null || usuarioUbicacionId == null) {
            return
        }
        val existing = api.listLotesUbicaciones(
            credentials = credentials,
            idLote = loteId,
            idTipo = ID_TIPO_ORIGEN,
            page = 1,
            items = 1,
        ).items.firstOrNull()
        if (existing != null) {
            return
        }
        api.createLoteUbicacion(
            credentials,
            CreateLoteUbicacionRequest(
                idLote = loteId,
                ubicacionId = usuarioUbicacionId,
                idTipo = ID_TIPO_ORIGEN,
                usuarioId = usuarioId,
                idOrigenUsuario = ORIGEN_USUARIO_INTERNO,
            ),
        )
    }

    private data class LoteSnapshot(
        val detalle: LoteDetalle,
        val blockers: LoteDeleteBlockers,
        val contenido: LoteContenido,
        val unidadesTipo: List<UnidadMedida> = emptyList(),
        val origenRelacion: LoteUbicacion? = null,
        val origenUbicacion: Ubicacion? = null,
        val destinoRelacion: LoteUbicacion? = null,
        val destinoUbicacion: Ubicacion? = null,
    )

    private data class LoadedLote(
        val lote: Lote,
        val tipoNombre: String,
        val tipoEsFinal: Boolean,
        val estatus: LoteEstatus = LoteEstatus.DISPONIBLE,
        val expedicionId: Int? = null,
        val recepcionId: Int? = null,
        val deleteBlockers: LoteDeleteBlockers = LoteDeleteBlockers(),
        val contenido: LoteContenido = LoteContenido(),
        val origenRelacion: LoteUbicacion? = null,
        val origenUbicacion: Ubicacion? = null,
        val destinoRelacion: LoteUbicacion? = null,
        val destinoUbicacion: Ubicacion? = null,
    ) {
        val canDeleteByEstatus: Boolean
            get() = estatus == LoteEstatus.DISPONIBLE || estatus == LoteEstatus.CONTENIDO

        val canDelete: Boolean
            get() = canDeleteByEstatus && deleteBlockers.canDelete
    }

    companion object {
        private const val ORIGEN_USUARIO_INTERNO = 1
        private const val ID_TIPO_ORIGEN = 1
        private const val ID_TIPO_DESTINO = 2
        private const val CODIGO_TIPO_CAJA = "caja"
    }
}
