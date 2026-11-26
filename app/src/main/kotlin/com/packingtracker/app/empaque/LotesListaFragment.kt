package com.packingtracker.app.empaque

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.packingtracker.app.R
import com.packingtracker.app.api.ApiCredentials
import com.packingtracker.app.api.ApiCredentialsResolver
import com.packingtracker.app.api.ApiException
import com.packingtracker.app.api.Lote
import com.packingtracker.app.api.LoteEstatus
import com.packingtracker.app.api.LoteTipo
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.api.PageMeta
import com.packingtracker.app.api.PagedResult
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.databinding.DialogLotesListaBinding
import com.packingtracker.app.databinding.ItemLoteBinding
import com.packingtracker.app.ui.ClientDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LotesListaFragment : Fragment() {
    fun interface Listener {
        fun onLoteSelected(lote: Lote, tipoNombre: String)
    }

    fun interface Host {
        fun closeLotesLista()
    }

    private var _binding: DialogLotesListaBinding? = null
    private val binding get() = _binding!!

    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore
    private var listener: Listener? = null
    private var host: Host? = null

    private val adapter = LotesAdapter { row ->
        listener?.onLoteSelected(row.lote, row.tipoNombre)
        host?.closeLotesLista()
    }
    private var tiposOrdered: List<LoteTipo> = emptyList()
    private var tiposById: Map<Int, LoteTipo> = emptyMap()
    private var selectedTipoId: Int? = null
    private var suppressTipoCallback = false
    private var currentPage = 1
    private var totalPages = 1
    private var totalItems = 0

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? Listener ?: context as? Listener
        host = parentFragment as? Host ?: context as? Host
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogLotesListaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configStore = AppConfigStore(requireContext())
        sessionStore = UserSessionStore(requireContext())

        binding.closeButton.setOnClickListener { host?.closeLotesLista() }
        binding.lotesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.lotesRecycler.adapter = adapter
        binding.lotesRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL),
        )
        binding.retryButton.setOnClickListener { loadPage(currentPage) }
        binding.prevPageButton.setOnClickListener {
            if (currentPage > 1) loadPage(currentPage - 1)
        }
        binding.nextPageButton.setOnClickListener {
            if (currentPage < totalPages) loadPage(currentPage + 1)
        }
        binding.filterSearchButton.setOnClickListener { applyFilters() }
        binding.filterIdInput.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isSearch || isEnter) {
                applyFilters()
                true
            } else {
                false
            }
        }
        binding.filterTipoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressTipoCallback) return
                val newTipoId = if (position <= 0) null else tiposOrdered.getOrNull(position - 1)?.id
                if (newTipoId == selectedTipoId) return
                selectedTipoId = newTipoId
                loadPage(1)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        setupTipoSpinner(emptyList())
        loadPage(1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
        host = null
    }

    private fun applyFilters() {
        hideKeyboard()
        val raw = binding.filterIdInput.text?.toString().orEmpty().trim()
        if (raw.isNotEmpty()) {
            val id = raw.filter { it.isDigit() }.toIntOrNull()
            if (id == null || id < 1) {
                Toast.makeText(requireContext(), R.string.empaque_list_filter_invalid_id, Toast.LENGTH_SHORT).show()
                return
            }
            binding.filterIdInput.setText(id.toString())
        }
        loadPage(1)
    }

    private fun selectedFilterId(): Int? {
        val raw = binding.filterIdInput.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) return null
        return raw.filter { it.isDigit() }.toIntOrNull()?.takeIf { it >= 1 }
    }

    private fun loadPage(page: Int) {
        val config = configStore.load()
        val session = sessionStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, session)
        if (credentials == null) {
            showError(getString(R.string.empaque_error_auth))
            return
        }

        val filterId = selectedFilterId()
        val filterTipoId = selectedTipoId
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(config.serverUrl)
                    if (tiposById.isEmpty()) {
                        tiposOrdered = api.listLotesTipos(credentials).sortedBy { it.nombre.lowercase() }
                        tiposById = tiposOrdered.associateBy { it.id }
                    }
                    if (filterId != null) {
                        lookupFloorLote(api, credentials, filterId, filterTipoId)
                    } else {
                        api.listLotes(
                            credentials,
                            page = page,
                            items = PAGE_SIZE,
                            idTipo = filterTipoId,
                            embarcable = true,
                        )
                    }
                }
            }

            if (!isAdded || _binding == null) return@launch

            result.onSuccess { paged ->
                if (tiposOrdered.isNotEmpty() && binding.filterTipoSpinner.adapter?.count != tiposOrdered.size + 1) {
                    setupTipoSpinner(tiposOrdered)
                }
                currentPage = paged.meta.page
                totalPages = paged.meta.pages.coerceAtLeast(if (paged.items.isEmpty()) 0 else 1)
                totalItems = paged.meta.total
                if (paged.items.isEmpty() && currentPage <= 1) {
                    adapter.submit(emptyList())
                    showEmpty()
                } else {
                    adapter.submit(
                        paged.items.map { lote ->
                            LoteRow(
                                lote = lote,
                                tipoNombre = tiposById[lote.idTipo]?.nombre
                                    ?: getString(R.string.empaque_list_tipo_fallback, lote.idTipo),
                                meta = buildMeta(lote),
                            )
                        },
                    )
                    binding.pageInfoText.text = getString(
                        R.string.empaque_list_page_info,
                        currentPage,
                        totalPages.coerceAtLeast(1),
                        totalItems,
                    )
                    binding.prevPageButton.isEnabled = currentPage > 1 && filterId == null
                    binding.nextPageButton.isEnabled = currentPage < totalPages && filterId == null
                    showContent()
                }
            }.onFailure { error ->
                when {
                    error is FilterMessageException -> {
                        adapter.submit(emptyList())
                        showEmpty()
                        Toast.makeText(requireContext(), error.message, Toast.LENGTH_LONG).show()
                    }
                    else -> showError(mapError(error))
                }
            }
        }
    }

    private fun lookupFloorLote(
        api: PackingTrackerApi,
        credentials: ApiCredentials,
        loteId: Int,
        filterTipoId: Int?,
    ): PagedResult<Lote> {
        val detalle = try {
            api.getLoteDetalle(credentials, loteId)
        } catch (error: ApiException) {
            if (error.code == "not_found" || error.httpStatus == 404) {
                return emptyPage()
            }
            throw error
        }
        if (detalle.estatus != LoteEstatus.DISPONIBLE || detalle.lote.padreId != null) {
            throw FilterMessageException(
                getString(R.string.empaque_list_filter_id_not_floor, loteId),
            )
        }
        if (filterTipoId != null && detalle.lote.idTipo != filterTipoId) {
            throw FilterMessageException(
                getString(R.string.empaque_list_filter_id_tipo_mismatch, loteId),
            )
        }
        return PagedResult(
            items = listOf(detalle.lote),
            meta = PageMeta(page = 1, items = 1, total = 1, pages = 1),
        )
    }

    private fun emptyPage(): PagedResult<Lote> {
        return PagedResult(
            items = emptyList(),
            meta = PageMeta(page = 1, items = PAGE_SIZE, total = 0, pages = 0),
        )
    }

    private fun setupTipoSpinner(tipos: List<LoteTipo>) {
        val labels = buildList {
            add(getString(R.string.empaque_list_filter_tipo_all))
            addAll(tipos.map { it.nombre })
        }
        suppressTipoCallback = true
        binding.filterTipoSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val selectedIndex = selectedTipoId?.let { id ->
            tipos.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        binding.filterTipoSpinner.setSelection(selectedIndex, false)
        suppressTipoCallback = false
    }

    private fun buildMeta(lote: Lote): String {
        val padre = lote.padreId?.let {
            getString(R.string.empaque_list_padre, it)
        } ?: getString(R.string.empaque_list_sin_padre)
        return getString(R.string.empaque_list_meta, padre, ClientDateTime.format(lote.creadoEn))
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.filterIdInput.windowToken, 0)
    }

    private fun showLoading() {
        binding.loadingContainer.visibility = View.VISIBLE
        binding.contentContainer.visibility = View.GONE
        binding.emptyContainer.visibility = View.GONE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showContent() {
        binding.loadingContainer.visibility = View.GONE
        binding.contentContainer.visibility = View.VISIBLE
        binding.emptyContainer.visibility = View.GONE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showEmpty() {
        binding.loadingContainer.visibility = View.GONE
        binding.contentContainer.visibility = View.GONE
        binding.emptyContainer.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loadingContainer.visibility = View.GONE
        binding.contentContainer.visibility = View.GONE
        binding.emptyContainer.visibility = View.GONE
        binding.errorContainer.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    private fun mapError(error: Throwable): String {
        return when (error) {
            is ApiException -> when (error.code) {
                "unauthorized", "invalid_token", "token_expired" ->
                    getString(R.string.empaque_error_auth)
                else -> getString(R.string.empaque_error_generic, error.code)
            }
            else -> getString(R.string.empaque_error_network)
        }
    }

    private class FilterMessageException(message: String) : Exception(message)

    private data class LoteRow(
        val lote: Lote,
        val tipoNombre: String,
        val meta: String,
    )

    private class LotesAdapter(
        private val onClick: (LoteRow) -> Unit,
    ) : RecyclerView.Adapter<LotesAdapter.Holder>() {
        private val rows = mutableListOf<LoteRow>()

        fun submit(items: List<LoteRow>) {
            rows.clear()
            rows.addAll(items)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemLoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding, onClick)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        class Holder(
            private val binding: ItemLoteBinding,
            private val onClick: (LoteRow) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: LoteRow) {
                binding.loteIdText.text = binding.root.context.getString(
                    R.string.empaque_lote_id,
                    row.lote.id,
                )
                binding.loteTipoText.text = row.tipoNombre
                binding.loteMetaText.text = row.meta
                binding.root.setOnClickListener { onClick(row) }
            }
        }
    }

    companion object {
        const val TAG = "LotesListaOverlay"
        private const val PAGE_SIZE = 20

        fun newInstance(): LotesListaFragment = LotesListaFragment()
    }
}
