package com.mrc.warehouse.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.mrc.warehouse.databinding.FragmentSalaryBinding
import com.mrc.warehouse.util.NetworkUtil
import com.mrc.warehouse.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

class SalaryFragment : Fragment() {

    private var _binding: FragmentSalaryBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private lateinit var adapter: SalaryAdapter

    private var currentYear = LocalDate.now().year
    private var currentMonth = LocalDate.now().monthValue

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSalaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.rvSalary.layoutManager = LinearLayoutManager(requireContext())
        adapter = SalaryAdapter(emptyList())
        binding.rvSalary.adapter = adapter

        updateMonthDisplay()
        loadSalary()

        binding.btnPrevMonth.setOnClickListener {
            val ym = YearMonth.of(currentYear, currentMonth).minusMonths(1)
            currentYear = ym.year
            currentMonth = ym.monthValue
            updateMonthDisplay()
            loadSalary()
        }

        binding.btnNextMonth.setOnClickListener {
            val ym = YearMonth.of(currentYear, currentMonth).plusMonths(1)
            currentYear = ym.year
            currentMonth = ym.monthValue
            updateMonthDisplay()
            loadSalary()
        }
    }

    private fun updateMonthDisplay() {
        val month = java.time.Month.of(currentMonth)
        val monthName = "${month.getDisplayName(TextStyle.FULL, Locale("ru"))} $currentYear"
        binding.tvMonthName.text = monthName.replaceFirstChar { it.titlecase(Locale("ru")) }
    }

    private fun loadSalary() {
        val startDate = "${currentYear}-${String.format("%02d", currentMonth)}-01"
        val endDay = YearMonth.of(currentYear, currentMonth).lengthOfMonth()
        val endDate = "${currentYear}-${String.format("%02d", currentMonth)}-${endDay}"

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (NetworkUtil.isOnline(requireContext())) {
                    val client = session.createApiClient(requireContext())
                    val response = client.getSalary(startDate, endDate)
                    val data = response.data ?: emptyList()
                    val total = response.totalAmount ?: 0.0

                    val cacheMap = mapOf(
                        "data" to data,
                        "total" to total,
                        "monthKey" to "$currentYear-${String.format("%02d", currentMonth)}"
                    )
                    session.cachedSalaryJson = Gson().toJson(cacheMap)
                    session.updateSyncTimestamp()

                    withContext(Dispatchers.Main) {
                        binding.tvError.visibility = View.GONE
                        adapter.updateData(data, total)
                        binding.tvNoData.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
                    }
                } else {
                    loadCachedSalary(startDate, endDate)
                }
            } catch (e: Exception) {
                loadCachedSalary(startDate, endDate)
            }
        }
    }

    private suspend fun loadCachedSalary(startDate: String, endDate: String) {
        try {
            val cacheStr = session.cachedSalaryJson
            if (cacheStr == "{}") {
                withContext(Dispatchers.Main) {
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "Нет соединения с сервером и нет сохранённых данных."
                }
                return
            }

            val cacheMap = com.google.gson.reflect.TypeToken.getParameterized(
                Map::class.java, String::class.java, Any::class.java
            ).type
            val cached: Map<String, Any> = Gson().fromJson(cacheStr, cacheMap)

            val monthKey = "$currentYear-${String.format("%02d", currentMonth)}"
            if (cached["monthKey"] != monthKey) {
                withContext(Dispatchers.Main) {
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "Нет соединения с сервером. Нет данных за этот месяц."
                }
                return
            }

            val dataJson = Gson().toJson(cached["data"])
            val total = (cached["total"] as? Double) ?: 0.0
            val type = object : com.google.gson.reflect.TypeToken<List<com.mrc.warehouse.api.SalaryItem>>() {}.type
            val data: List<com.mrc.warehouse.api.SalaryItem> = Gson().fromJson(dataJson, type)

            withContext(Dispatchers.Main) {
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "Нет соединения с сервером. Показаны сохранённые данные."
                adapter.updateData(data, total)
                binding.tvNoData.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "Ошибка загрузки данных: ${e.message}"
                binding.tvNoData.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}