package com.packingtracker.app.embarques

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.os.bundleOf
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
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.databinding.DialogLotesEmbarqueBinding
import com.packingtracker.app.databinding.ItemLoteBinding
import com.packingtracker.app.ui.ClientDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LotesEmbarqueListaFragment : Fragment() {
    fun interface Listener {
        fun onLoteEmbarqueSelected(lote: Lote)
    }

    fun interface Host {
        fun closeLotesEmbarqueLista()
    }

    private var _binding: DialogLotesEmbarqueBinding? = null
    private val binding get() = _binding!!

    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore
    private var listener: Listener? = null
    private var host: Host? = null

    private val adapter = LotesAdapter { row ->
        listener?.onLoteEmbarqueSelected(row.lote)
        host?.closeLotesEmbarqueLista()
    }
    private var allRows: List<LoteRow> = emptyList()
    private var tiposById: Map<Int, LoteTipo> = emptyMap()

    private val ubicacionId: Int
        get() = requireArguments().getInt(ARG_UBICACION_ID)

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
        _binding = DialogLotesEmbarqueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configStore = AppConfigStore(requireContext())
        sessionStore = UserSessionStore(requireContext())

        binding.closeButton.setOnClickListener { host?.closeLotesEmbarqueLista() }
        binding.lotesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.lotesRecycler.adapter = adapter
        binding.lotesRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL),
        )
        binding.retryButton.setOnClickListener { loadLotes() }
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

        loadLotes()
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
                    R.string.empaque_list_filter_invalid_id,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            binding.filterIdInput.setText(id.toString())
            renderRows(allRows.filter { it.lote.id == id })
        } else {
            renderRows(allRows)
        }
    }

    private fun loadLotes() {
        val config = configStore.load()
        val session = sessionStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, session)
        if (credentials == null) {
            showError(getString(R.string.embarques_error_auth))
            return
        }

        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    loadEmbarcablePorUbicacion(
                        PackingTrackerApi(config.serverUrl),
                        credentials,
                        ubicacionId,
                    )
                }
            }

            if (!isAdded || _binding == null) return@launch

            result.onSuccess { rows ->
                allRows = rows
                val filterId = binding.filterIdInput.text?.toString().orEmpty()
                    .filter { it.isDigit() }
                    .toIntOrNull()
                    ?.takeIf { it >= 1 }
                renderRows(
                    if (filterId != null) rows.filter { it.lote.id == filterId } else rows,
                )
            }.onFailure { error ->
                showError(mapError(error))
            }
        }
    }

    private fun loadEmbarcablePorUbicacion(
        api: PackingTrackerApi,
        credentials: ApiCredentials,
        ubicacionId: Int,
    ): List<LoteRow> {
        if (tiposById.isEmpty()) {
            tiposById = api.listLotesTipos(credentials).associateBy { it.id }
        }
        val relaciones = api.listLotesUbicaciones(
            credentials = credentials,
            idTipo = ID_TIPO_ORIGEN,
            ubicacionId = ubicacionId,
            page = 1,
            items = FETCH_LIMIT,
        ).items
        if (relaciones.isEmpty()) {
            return emptyList()
        }
        val embarcableIds = api.listLotes(
            credentials = credentials,
            page = 1,
            items = FETCH_LIMIT,
            embarcable = true,
        ).items.map { it.id }.toSet()

        return relaciones
            .map { it.idLote }
            .distinct()
            .filter { it in embarcableIds }
            .mapNotNull { loteId ->
                val detalle = try {
                    api.getLoteDetalle(credentials, loteId)
                } catch (error: ApiException) {
                    if (error.code == "not_found" || error.httpStatus == 404) {
                        return@mapNotNull null
                    }
                    throw error
                }
                if (detalle.estatus != LoteEstatus.DISPONIBLE || detalle.lote.padreId != null) {
                    return@mapNotNull null
                }
                val lote = detalle.lote
                LoteRow(
                    lote = lote,
                    tipoNombre = tiposById[lote.idTipo]?.nombre
                        ?: getString(R.string.empaque_list_tipo_fallback, lote.idTipo),
                    meta = getString(
                        R.string.empaque_list_meta,
                        getString(R.string.empaque_list_sin_padre),
                        ClientDateTime.format(lote.creadoEn),
                    ),
                )
            }
            .sortedByDescending { it.lote.id }
    }

    private fun renderRows(rows: List<LoteRow>) {
        if (rows.isEmpty()) {
            adapter.submit(emptyList())
            showEmpty()
            return
        }
        adapter.submit(rows)
        binding.pageInfoText.text = getString(
            R.string.embarques_list_page_info,
            1,
            1,
            rows.size,
        )
        showContent()
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
        binding.emptyMessage.setText(R.string.embarques_lote_search_empty)
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
        const val TAG = "LotesEmbarqueListaOverlay"
        private const val ARG_UBICACION_ID = "ubicacion_id"
        private const val ID_TIPO_ORIGEN = 1
        private const val FETCH_LIMIT = 100

        fun newInstance(ubicacionId: Int): LotesEmbarqueListaFragment {
            return LotesEmbarqueListaFragment().apply {
                arguments = bundleOf(ARG_UBICACION_ID to ubicacionId)
            }
        }
    }
}
