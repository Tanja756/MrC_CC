package com.mrc.warehouse.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrc.warehouse.api.BalanceItem
import com.mrc.warehouse.api.StorageItem
import com.mrc.warehouse.databinding.FragmentWarehouseBinding
import com.mrc.warehouse.util.NetworkUtil
import com.mrc.warehouse.util.PdfExportHelper
import com.mrc.warehouse.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WarehouseFragment : Fragment() {

    private var _binding: FragmentWarehouseBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private lateinit var adapter: BalancesAdapter

    private var allBalances: List<BalanceItem> = emptyList()
    private var activeFilter: String? = null
    private var selectedStorage: StorageItem? = null
    private val pdfExportAuthority by lazy { "${requireContext().packageName}.fileprovider" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWarehouseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.rvBalances.layoutManager = LinearLayoutManager(requireContext())
        adapter = BalancesAdapter(emptyList())
        binding.rvBalances.adapter = adapter

        val storages = session.storages
        val storageNames = mutableListOf("-- Не выбран --")
        storageNames.addAll(storages.map { it.name ?: "?" })

        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, storageNames)
        binding.spinnerStorage.adapter = spinnerAdapter

        binding.spinnerStorage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    selectedStorage = null
                    allBalances = emptyList()
                    adapter.updateData(emptyList())
                } else {
                    selectedStorage = storages[position - 1]
                    loadBalances(selectedStorage!!)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnRefresh.setOnClickListener {
            val pos = binding.spinnerStorage.selectedItemPosition
            if (pos > 0) {
                selectedStorage = storages[pos - 1]
                loadBalances(selectedStorage!!, force = true)
            }
        }

        binding.btnSearchToggle.setOnClickListener {
            val isVisible = binding.cardSearch.visibility == View.VISIBLE
            binding.cardSearch.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etSearch.requestFocus()
            }
        }

        binding.btnFilterToggle.setOnClickListener {
            val isVisible = binding.cardFilters.visibility == View.VISIBLE
            binding.cardFilters.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        binding.etSearch.setOnKeyListener { _, _, _ -> applyFilters(); false }
        binding.etSearch.setOnFocusChangeListener { _, _ -> applyFilters() }

        binding.btnFilterEquipment.setOnClickListener {
            activeFilter = "equipment"
            applyFilters()
        }
        binding.btnFilterZip.setOnClickListener {
            activeFilter = "zip"
            applyFilters()
        }
        binding.btnFilterReset.setOnClickListener {
            activeFilter = null
            applyFilters()
        }

        binding.btnExportPdf.setOnClickListener { exportPdf() }
    }

    /**
     * Загрузка остатков с сервера или из кэша.
     * @param force если true, игнорирует интервал авто-синхронизации (принудительное обновление)
     */
    private fun loadBalances(storage: StorageItem, force: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Автоматический запрос — проверяем лимит частоты через canPerformAutoSync()
            if (!force && !session.canPerformAutoSync()) {
                loadCachedBalances(storage)
                return@launch
            }

            try {
                if (NetworkUtil.isOnline(requireContext())) {
                    val client = session.createApiClient()
                    val balances = client.getBalances(storage.guid ?: "")

                    session.setCachedBalances(storage.guid ?: "", balances)
                    session.updateSyncTimestamp()
                    session.markAutoSyncPerformed()

                    withContext(Dispatchers.Main) {
                        allBalances = balances
                        binding.tvError.visibility = View.GONE
                        applyFilters()
                    }
                } else {
                    loadCachedBalances(storage)
                }
            } catch (e: Exception) {
                loadCachedBalances(storage)
            }
        }
    }

    private suspend fun loadCachedBalances(storage: StorageItem) {
        val cached = session.getCachedBalances(storage.guid ?: "")
        withContext(Dispatchers.Main) {
            if (cached.isNotEmpty()) {
                allBalances = cached
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "Нет соединения с сервером. Показаны сохранённые данные."
                applyFilters()
            } else {
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "Нет соединения с сервером и нет сохранённых данных."
                allBalances = emptyList()
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        var filtered = allBalances.toList()

        when (activeFilter) {
            "equipment" -> filtered = filtered.filter { !it.seriesName.isNullOrBlank() }
            "zip" -> filtered = filtered.filter { it.seriesName.isNullOrBlank() }
        }

        val searchText = binding.etSearch.text.toString().lowercase().trim()
        if (searchText.isNotEmpty()) {
            filtered = filtered.filter { item ->
                val fields = listOf(item.productName, item.seriesName, item.inventoryNumber)
                fields.any { it != null && it.lowercase().contains(searchText) }
            }
        }

        adapter.updateData(filtered)
    }

    private fun exportPdf() {
        val storage = selectedStorage
        if (storage == null) {
            Toast.makeText(requireContext(), "Сначала выберите склад", Toast.LENGTH_SHORT).show()
            return
        }
        if (allBalances.isEmpty()) {
            Toast.makeText(requireContext(), "Нет данных для экспорта", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val file = PdfExportHelper.exportToPdf(
                context = requireContext(),
                storage = storage,
                allBalances = allBalances,
                authority = pdfExportAuthority
            )
            PdfExportHelper.sharePdf(requireContext(), file, pdfExportAuthority)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Ошибка экспорта PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}