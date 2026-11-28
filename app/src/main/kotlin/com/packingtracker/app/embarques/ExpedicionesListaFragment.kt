package com.packingtracker.app.embarques

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
import com.packingtracker.app.api.Expedicion
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.api.PageMeta
import com.packingtracker.app.api.PagedResult
import com.packingtracker.app.api.UnidadTransporte
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.databinding.DialogExpedicionesListaBinding
import com.packingtracker.app.databinding.ItemExpedicionBinding
import com.packingtracker.app.ui.ClientDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExpedicionesListaFragment : Fragment() {
    fun interface Listener {
        fun onExpedicionSelected(expedicion: Expedicion)
    }

    fun interface Host {
        fun closeExpedicionesLista()
    }

    private var _binding: DialogExpedicionesListaBinding? = null
    private val binding get() = _binding!!

    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore
    private var listener: Listener? = null
    private var host: Host? = null

    private val adapter = ExpedicionesAdapter { row ->
        listener?.onExpedicionSelected(row.expedicion)
        host?.closeExpedicionesLista()
    }
    private var unidadesOrdered: List<UnidadTransporte> = emptyList()
    private var unidadesById: Map<Int, UnidadTransporte> = emptyMap()
    private var selectedUnidadId: Int? = null
    private var selectedAbierta: Boolean? = true
    private var suppressUnidadCallback = false
    private var suppressEstadoCallback = false
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
        _binding = DialogExpedicionesListaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configStore = AppConfigStore(requireContext())
        sessionStore = UserSessionStore(requireContext())

        binding.closeButton.setOnClickListener { host?.closeExpedicionesLista() }
        binding.expedicionesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.expedicionesRecycler.adapter = adapter
        binding.expedicionesRecycler.addItemDecoration(
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
        binding.filterUnidadSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressUnidadCallback) return
                val newId = if (position <= 0) null else unidadesOrdered.getOrNull(position - 1)?.id
                if (newId == selectedUnidadId) return
                selectedUnidadId = newId
                loadPage(1)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.filterEstadoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressEstadoCallback) return
                val newValue = when (position) {
                    1 -> true
                    2 -> false
                    else -> null
                }
                if (newValue == selectedAbierta) return
                selectedAbierta = newValue
                loadPage(1)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        setupUnidadSpinner(emptyList())
        setupEstadoSpinner()
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
                Toast.makeText(
                    requireContext(),
                    R.string.embarques_list_filter_invalid_id,
                    Toast.LENGTH_SHORT,
                ).show()
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
            showError(getString(R.string.embarques_error_auth))
            return
        }

        val filterId = selectedFilterId()
        val filterUnidadId = selectedUnidadId
        val filterAbierta = selectedAbierta
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(config.serverUrl)
                    if (unidadesById.isEmpty()) {
                        unidadesOrdered = api.listUnidadesTransporte(credentials)
                            .sortedBy { it.displayLabel().lowercase() }
                        unidadesById = unidadesOrdered.associateBy { it.id }
                    }
                    if (filterId != null) {
                        lookupExpedicion(api, credentials, filterId, filterUnidadId, filterAbierta)
                    } else {
                        api.listExpediciones(
                            credentials = credentials,
                            page = page,
                            items = PAGE_SIZE,
                            idUnidad = filterUnidadId,
                            abierta = filterAbierta,
                        )
                    }
                }
            }

            if (!isAdded || _binding == null) return@launch

            result.onSuccess { paged ->
                if (unidadesOrdered.isNotEmpty() &&
                    binding.filterUnidadSpinner.adapter?.count != unidadesOrdered.size + 1
                ) {
                    setupUnidadSpinner(unidadesOrdered)
                }
                currentPage = paged.meta.page
                totalPages = paged.meta.pages.coerceAtLeast(if (paged.items.isEmpty()) 0 else 1)
                totalItems = paged.meta.total
                if (paged.items.isEmpty() && currentPage <= 1) {
                    adapter.submit(emptyList())
                    showEmpty()
                } else {
                    adapter.submit(
                        paged.items.map { expedicion ->
                            ExpedicionRow(
                                expedicion = expedicion,
                                unidadNombre = unidadesById[expedicion.idUnidad]?.displayLabel()
                                    ?: getString(
                                        R.string.embarques_list_unidad_fallback,
                                        expedicion.idUnidad,
                                    ),
                                meta = buildMeta(expedicion),
                            )
                        },
                    )
                    binding.pageInfoText.text = getString(
                        R.string.embarques_list_page_info,
                        currentPage,
                        totalPages.coerceAtLeast(1),
                        totalItems,
                    )
                    binding.prevPageButton.isEnabled = currentPage > 1 && filterId == null
                    binding.nextPageButton.isEnabled = currentPage < totalPages && filterId == null
                    showContent()
                }
            }.onFailure { error ->
                showError(mapError(error))
            }
        }
    }

    private fun lookupExpedicion(
        api: PackingTrackerApi,
        credentials: ApiCredentials,
        expedicionId: Int,
        filterUnidadId: Int?,
        filterAbierta: Boolean?,
    ): PagedResult<Expedicion> {
        val expedicion = try {
            api.getExpedicion(credentials, expedicionId)
        } catch (error: ApiException) {
            if (error.code == "not_found" || error.httpStatus == 404) {
                return emptyPage()
            }
            throw error
        }
        if (filterUnidadId != null && expedicion.idUnidad != filterUnidadId) {
            return emptyPage()
        }
        if (filterAbierta != null && expedicion.abierta != filterAbierta) {
            return emptyPage()
        }
        return PagedResult(
            items = listOf(expedicion),
            meta = PageMeta(page = 1, items = 1, total = 1, pages = 1),
        )
    }

    private fun emptyPage(): PagedResult<Expedicion> {
        return PagedResult(
            items = emptyList(),
            meta = PageMeta(page = 1, items = PAGE_SIZE, total = 0, pages = 0),
        )
    }

    private fun setupUnidadSpinner(unidades: List<UnidadTransporte>) {
        val labels = buildList {
            add(getString(R.string.embarques_list_filter_unidad_all))
            addAll(unidades.map { it.displayLabel() })
        }
        suppressUnidadCallback = true
        binding.filterUnidadSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val selectedIndex = selectedUnidadId?.let { id ->
            unidades.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        binding.filterUnidadSpinner.setSelection(selectedIndex, false)
        suppressUnidadCallback = false
    }

    private fun setupEstadoSpinner() {
        val labels = listOf(
            getString(R.string.embarques_list_filter_estado_all),
            getString(R.string.embarques_list_filter_estado_abiertas),
            getString(R.string.embarques_list_filter_estado_cerradas),
        )
        suppressEstadoCallback = true
        binding.filterEstadoSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val selectedIndex = when (selectedAbierta) {
            true -> 1
            false -> 2
            null -> 0
        }
        binding.filterEstadoSpinner.setSelection(selectedIndex, false)
        suppressEstadoCallback = false
    }

    private fun buildMeta(expedicion: Expedicion): String {
        val estado = if (expedicion.abierta) {
            getString(R.string.embarques_estatus_abierta)
        } else {
            getString(R.string.embarques_estatus_cerrada)
        }
        return getString(
            R.string.embarques_list_meta,
            estado,
            ClientDateTime.format(expedicion.creadoEn),
        )
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
                    getString(R.string.embarques_error_auth)
                else -> getString(R.string.embarques_error_generic, error.code)
            }
            else -> getString(R.string.embarques_error_network)
        }
    }

    private data class ExpedicionRow(
        val expedicion: Expedicion,
        val unidadNombre: String,
        val meta: String,
    )

    private class ExpedicionesAdapter(
        private val onClick: (ExpedicionRow) -> Unit,
    ) : RecyclerView.Adapter<ExpedicionesAdapter.Holder>() {
        private val rows = mutableListOf<ExpedicionRow>()

        fun submit(items: List<ExpedicionRow>) {
            rows.clear()
            rows.addAll(items)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemExpedicionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return Holder(binding, onClick)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        class Holder(
            private val binding: ItemExpedicionBinding,
            private val onClick: (ExpedicionRow) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: ExpedicionRow) {
                binding.expedicionIdText.text = binding.root.context.getString(
                    R.string.embarques_list_id,
                    row.expedicion.id,
                )
                binding.expedicionUnidadText.text = row.unidadNombre
                binding.expedicionMetaText.text = row.meta
                binding.root.setOnClickListener { onClick(row) }
            }
        }
    }

    companion object {
        const val TAG = "ExpedicionesListaOverlay"
        private const val PAGE_SIZE = 20

        fun newInstance(): ExpedicionesListaFragment = ExpedicionesListaFragment()
    }
}
