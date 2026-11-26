package com.packingtracker.app.empaque

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.packingtracker.app.R
import com.packingtracker.app.api.ApiCredentialsResolver
import com.packingtracker.app.api.ApiException
import com.packingtracker.app.api.Lote
import com.packingtracker.app.api.LoteTipo
import com.packingtracker.app.api.PackingTrackerApi
import com.packingtracker.app.auth.UserSessionStore
import com.packingtracker.app.config.AppConfigStore
import com.packingtracker.app.databinding.DialogLotesListaBinding
import com.packingtracker.app.databinding.ItemLoteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LotesListaDialogFragment : DialogFragment() {
    fun interface Listener {
        fun onLoteSelected(lote: Lote, tipoNombre: String)
    }

    private var _binding: DialogLotesListaBinding? = null
    private val binding get() = _binding!!

    private lateinit var configStore: AppConfigStore
    private lateinit var sessionStore: UserSessionStore
    private var listener: Listener? = null

    private val adapter = LotesAdapter { row ->
        listener?.onLoteSelected(row.lote, row.tipoNombre)
        dismiss()
    }
    private var tiposById: Map<Int, LoteTipo> = emptyMap()
    private var currentPage = 1
    private var totalPages = 1
    private var totalItems = 0

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? Listener ?: context as? Listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_PackingTracker_FullScreenDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also { dialog ->
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
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

        binding.closeButton.setOnClickListener { dismiss() }
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

        loadPage(1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun loadPage(page: Int) {
        val config = configStore.load()
        val session = sessionStore.load()
        val credentials = ApiCredentialsResolver.resolve(config, session)
        if (credentials == null) {
            showError(getString(R.string.empaque_error_auth))
            return
        }

        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = PackingTrackerApi(config.serverUrl)
                    if (tiposById.isEmpty()) {
                        tiposById = api.listLotesTipos(credentials).associateBy { it.id }
                    }
                    api.listLotes(credentials, page = page, items = PAGE_SIZE)
                }
            }

            if (!isAdded || _binding == null) return@launch

            result.onSuccess { paged ->
                currentPage = paged.meta.page
                totalPages = paged.meta.pages.coerceAtLeast(if (paged.items.isEmpty()) 0 else 1)
                totalItems = paged.meta.total
                if (paged.items.isEmpty() && currentPage <= 1) {
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
                    binding.prevPageButton.isEnabled = currentPage > 1
                    binding.nextPageButton.isEnabled = currentPage < totalPages
                    showContent()
                }
            }.onFailure { error ->
                showError(mapError(error))
            }
        }
    }

    private fun buildMeta(lote: Lote): String {
        val padre = lote.padreId?.let {
            getString(R.string.empaque_list_padre, it)
        } ?: getString(R.string.empaque_list_sin_padre)
        return getString(R.string.empaque_list_meta, padre, formatCreatedAt(lote.creadoEn))
    }

    private fun formatCreatedAt(raw: String): String {
        return raw.replace('T', ' ').take(19).ifBlank { raw }
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
        const val TAG = "LotesListaDialog"
        private const val PAGE_SIZE = 20

        fun newInstance(): LotesListaDialogFragment = LotesListaDialogFragment()
    }
}
