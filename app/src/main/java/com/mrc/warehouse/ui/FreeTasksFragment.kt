package com.mrc.warehouse.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.mrc.warehouse.R
import com.mrc.warehouse.api.TaskItem
import com.mrc.warehouse.databinding.FragmentTasksBinding
import com.mrc.warehouse.util.NetworkUtil
import com.mrc.warehouse.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FreeTasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private lateinit var adapter: TasksAdapter

    private var allTasks: List<TaskItem> = emptyList()
    private var currentSortMode = "deadline"
    private var isTakingTask = false
    private var isSelectMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.rvTasks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        val clientsMap = session.clients
            .filter { it.guid != null && it.name != null && it.guid != "00000000-0000-0000-0000-000000000000" }
            .associate { it.guid!! to it.name!! }
        val priorityMap = session.priorities
            .filter { it.value != null && it.name != null }
            .associate { it.value!! to it.name!! }

        adapter = TasksAdapter(
            tasks = emptyList(),
            clientsMap = clientsMap,
            priorityMap = priorityMap,
            onDescriptionClick = { desc -> showDescriptionDialog(desc) },
            onTakeTaskClick = { task -> takeTask(task) }
        )
        binding.rvTasks.adapter = adapter

        currentSortMode = session.sortModeFreeTasks
        binding.chipSortDeadline.isChecked = currentSortMode == "deadline"
        binding.chipSortCreation.isChecked = currentSortMode == "creation"
        binding.chipSortPriority.isChecked = currentSortMode == "priority"

        binding.tvTitle.text = "Свободные заявки"
        binding.chipSortCreation.setOnClickListener { setSortMode("creation") }
        binding.chipSortDeadline.setOnClickListener { setSortMode("deadline") }
        binding.chipSortPriority.setOnClickListener { setSortMode("priority") }

        binding.btnFilterToggle.setOnClickListener {
            val isVisible = binding.cardSort.visibility == View.VISIBLE
            binding.cardSort.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        adapter.onEnterSelectMode = {
            isSelectMode = true
            adapter.onSelectionChanged = { updateBulkActionBar() }
            binding.fabBulkCancel.visibility = View.VISIBLE
            binding.fabBulkTake.visibility = View.VISIBLE
            updateBulkActionBar()
        }
        adapter.onSelectionChanged = { updateBulkActionBar() }

        binding.fabBulkCancel.setOnClickListener {
            isSelectMode = false
            adapter.selectable = false
            binding.fabBulkCancel.visibility = View.GONE
            binding.fabBulkTake.visibility = View.GONE
        }

        binding.fabBulkTake.setOnClickListener {
            val selected = allTasks.filter { it.guid in adapter.selectedTaskGuids }
            if (selected.isNotEmpty()) {
                bulkTakeTasks(selected)
            }
        }

        binding.btnSearchToggle.setOnClickListener {
            val isVisible = binding.cardSearch.visibility == View.VISIBLE
            binding.cardSearch.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etSearch.requestFocus()
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            refreshFromServer()
        }
        binding.swipeRefresh.setColorSchemeColors(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary)
        )

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilters() }
        })

        loadTasks()
    }

    private fun refreshFromServer() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.tvError.visibility = View.GONE
            }
            try {
                if (!NetworkUtil.isOnline(requireContext())) {
                    withContext(Dispatchers.Main) {
                        binding.swipeRefresh.isRefreshing = false
                        if (allTasks.isEmpty()) {
                            binding.tvError.visibility = View.VISIBLE
                            binding.tvError.text = "Нет соединения с сервером и нет сохранённых данных."
                        } else {
                            binding.tvError.visibility = View.GONE
                        }
                    }
                    return@launch
                }

                val client = session.createApiClient()
                val response = client.getTasksUnallocated()
                val serverTasks = response.tasks ?: emptyList<TaskItem>()

                session.cachedTasksFreeJson = Gson().toJson(serverTasks)
                session.updateSyncTimestamp()
                allTasks = serverTasks

                withContext(Dispatchers.Main) {
                    binding.tvError.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    updateUiAfterLoad()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.swipeRefresh.isRefreshing = false
                    if (allTasks.isEmpty()) {
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Ошибка загрузки: ${e.message ?: "нет соединения"}. Нет сохранённых данных."
                    } else {
                        binding.tvError.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun loadTasks() {
        val cached = session.getCachedTasksFree()
        if (cached.isNotEmpty()) {
            allTasks = cached
            updateUiAfterLoad()
        } else {
            // Здесь мы в главном потоке — можно напрямую менять UI
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = "Загрузка данных..."
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!NetworkUtil.isOnline(requireContext())) {
                    withContext(Dispatchers.Main) {
                        if (allTasks.isEmpty()) {
                            binding.tvError.visibility = View.VISIBLE
                            binding.tvError.text = "Нет соединения с сервером и нет сохранённых данных."
                        } else {
                            binding.tvError.visibility = View.GONE
                        }
                    }
                    return@launch
                }

                val client = session.createApiClient()
                val response = client.getTasksUnallocated()
                val serverTasks = response.tasks ?: emptyList<TaskItem>()
                val serverJson = Gson().toJson(serverTasks)

                val cachedJson = session.cachedTasksFreeJson
                if (serverJson == cachedJson) {
                    withContext(Dispatchers.Main) {
                        if (allTasks.isEmpty() && serverTasks.isEmpty()) {
                            binding.tvError.visibility = View.VISIBLE
                            binding.tvError.text = "Нет свободных заявок."
                        } else {
                            binding.tvError.visibility = View.GONE
                        }
                    }
                    return@launch
                }

                session.cachedTasksFreeJson = serverJson
                session.updateSyncTimestamp()
                allTasks = serverTasks

                withContext(Dispatchers.Main) {
                    binding.tvError.visibility = View.GONE
                    updateUiAfterLoad()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (allTasks.isEmpty()) {
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Ошибка загрузки: ${e.message ?: "нет соединения"}. Нет сохранённых данных."
                    } else {
                        binding.tvError.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateUiAfterLoad() {
        if (_binding == null) return

        val clientsMap = session.clients
            .filter { it.guid != null && it.name != null && it.guid != "00000000-0000-0000-0000-000000000000" }
            .associate { it.guid!! to it.name!! }
        val priorityMap = session.priorities
            .filter { it.value != null && it.name != null }
            .associate { it.value!! to it.name!! }

        adapter = TasksAdapter(
            tasks = allTasks,
            clientsMap = clientsMap,
            priorityMap = priorityMap,
            onDescriptionClick = { desc -> showDescriptionDialog(desc) },
            onTakeTaskClick = { task -> takeTask(task) }
        )
        adapter.selectable = isSelectMode
        if (isSelectMode) {
            adapter.selectedTaskGuids.clear()
            adapter.onSelectionChanged = { updateBulkActionBar() }
            binding.fabBulkCancel.visibility = View.VISIBLE
            binding.fabBulkTake.visibility = View.VISIBLE
            updateBulkActionBar()
        }
        binding.rvTasks.adapter = adapter
        applyFilters()
    }

    private fun takeTask(task: TaskItem) {
        if (isTakingTask) return
        val ticketNumber = extractTicketNumber(task.name) ?: task.number ?: "?"
        AlertDialog.Builder(requireContext())
            .setTitle("Взять заявку $ticketNumber")
            .setMessage("Взять заявку $ticketNumber в работу?")
            .setPositiveButton("Да") { d, _ ->
                d.dismiss()
                executeTakeTask(task, ticketNumber)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun executeTakeTask(task: TaskItem, ticketNumber: String) {
        val guid = task.guid
        if (guid.isNullOrBlank()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Ошибка")
                .setMessage("У заявки $ticketNumber отсутствует идентификатор")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        isTakingTask = true
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle("Взятие заявки $ticketNumber")
            .setMessage("Выполняется...")
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = session.createApiClient()
                val result = client.taskTake(guid)

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    if (result.error != null) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Ошибка")
                            .setMessage(result.error)
                            .setPositiveButton("OK") { _, _ -> isTakingTask = false }
                            .show()
                        return@withContext
                    }

                    if (result.status.equals("Выполнить", ignoreCase = true)) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("✅ Заявка взята")
                            .setMessage("Заявка $ticketNumber успешно взята в работу")
                            .setPositiveButton("OK") { _, _ ->
                                allTasks = allTasks.filter { it.guid != task.guid }
                                updateUiAfterLoad()
                                isTakingTask = false
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val c = session.createApiClient()
                                        val resp = c.getTasksUnallocated()
                                        session.cachedTasksFreeJson = Gson().toJson(resp.tasks ?: emptyList<TaskItem>())
                                        session.updateSyncTimestamp()
                                        withContext(Dispatchers.Main) {
                                            allTasks = resp.tasks ?: emptyList()
                                            updateUiAfterLoad()
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            .setOnDismissListener { isTakingTask = false }
                            .show()
                    } else {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Результат")
                            .setMessage("Статус: ${result.status ?: "не определён"}")
                            .setPositiveButton("OK") { _, _ -> isTakingTask = false }
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    isTakingTask = false
                    AlertDialog.Builder(requireContext())
                        .setTitle("Ошибка соединения")
                        .setMessage("Не удалось выполнить запрос: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun extractTicketNumber(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("[А-ЯБ-яёЁ]{2}-\\d{6}")
        return regex.find(text)?.value
    }

    private fun setSortMode(mode: String) {
        currentSortMode = mode
        session.sortModeFreeTasks = mode
        binding.chipSortCreation.isChecked = mode == "creation"
        binding.chipSortDeadline.isChecked = mode == "deadline"
        binding.chipSortPriority.isChecked = mode == "priority"
        applyFilters()
    }

    private fun applyFilters() {
        val searchText = binding.etSearch.text.toString().lowercase()
        val clientsMap = session.clients
            .filter { it.guid != null && it.name != null && it.guid != "00000000-0000-0000-0000-000000000000" }
            .associate { it.guid!! to it.name!! }

        val filtered = allTasks
            .filter { task ->
                if (searchText.isBlank()) return@filter true
                val fields = listOf(
                    task.number, task.name, task.status,
                    task.nameDepartment, task.user,
                    clientsMap[task.guidClient] ?: ""
                )
                fields.any { it != null && it.lowercase().contains(searchText) }
            }
            .let { sortTasks(it, currentSortMode) }

        adapter.updateData(filtered)
        binding.tvNoResults.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sortTasks(tasks: List<TaskItem>, mode: String): List<TaskItem> {
        val sorted = tasks.toMutableList()
        when (mode) {
            "creation" -> sorted.sortWith(compareBy<TaskItem> { dateToSortKey(it.date) }.thenByDescending { it.priority ?: 0 })
            "deadline" -> sorted.sortWith(compareBy<TaskItem> { dateToSortKey(it.period) }.thenByDescending { it.priority ?: 0 })
            "priority" -> sorted.sortWith(compareBy<TaskItem> { it.priority ?: 0 }.thenBy { dateToSortKey(it.period) })
        }
        return sorted
    }

    private fun dateToSortKey(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return ""
        return try {
            val parts = dateStr.split(" ")
            if (parts.size < 2) return dateStr
            val dateParts = parts[0].split(".")
            val timeParts = parts[1].split(":")
            if (dateParts.size < 3 || timeParts.size < 2) return dateStr
            "${dateParts[2]}${dateParts[1]}${dateParts[0]}${timeParts[0]}${timeParts[1]}${timeParts.getOrElse(2) { "00" }}"
        } catch (e: Exception) { dateStr }
    }

    private fun showDescriptionDialog(description: String?) {
        val text = if (description.isNullOrBlank()) "Нет описания" else description
        val tv = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_description, null) as TextView
        tv.text = text
        AlertDialog.Builder(requireContext(), R.style.Theme_MrCWarehouse_Dialog)
            .setTitle("Описание заявки")
            .setView(tv)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun updateBulkActionBar() {
        val count = adapter.selectedTaskGuids.size
        binding.fabBulkTake.isEnabled = count > 0
        binding.fabBulkTake.alpha = if (count > 0) 1.0f else 0.5f
    }

    private fun bulkTakeTasks(tasks: List<TaskItem>) {
        if (isTakingTask) return
        isTakingTask = true

        val total = tasks.size
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle("Взятие заявок")
            .setMessage("Выполняется... 0 из $total")
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var succeeded = 0
            var failed = 0
            val successGuids = mutableListOf<String>()

            for (pair in tasks.withIndex()) {
                val index = pair.index
                val task = pair.value
                val guid = task.guid
                if (guid.isNullOrBlank()) {
                    failed++
                    continue
                }
                try {
                    val client = session.createApiClient()
                    val result = client.taskTake(guid)
                    if (result.error != null) {
                        failed++
                    } else if (result.status.equals("Выполнить", ignoreCase = true)) {
                        succeeded++
                        successGuids.add(guid)
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    failed++
                }
                withContext(Dispatchers.Main) {
                    loadingDialog.setMessage("Выполняется... ${index + 1} из $total")
                }
            }

            withContext(Dispatchers.Main) {
                loadingDialog.dismiss()
                isTakingTask = false

                if (successGuids.isNotEmpty()) {
                    allTasks = allTasks.filter { it.guid !in successGuids }
                    updateUiAfterLoad()
                }

                isSelectMode = false
                adapter.selectable = false
                binding.fabBulkCancel.visibility = View.GONE
                binding.fabBulkTake.visibility = View.GONE

                val resultMsg = if (failed == 0) {
                    "✅ Успешно взято: $succeeded"
                } else {
                    "✅ Успешно: $succeeded\n❌ Ошибок: $failed"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Результат")
                    .setMessage(resultMsg)
                    .setPositiveButton("OK") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val c = session.createApiClient()
                                val resp = c.getTasksUnallocated()
                                session.cachedTasksFreeJson = Gson().toJson(resp.tasks ?: emptyList<TaskItem>())
                                session.updateSyncTimestamp()
                                withContext(Dispatchers.Main) {
                                    allTasks = resp.tasks ?: emptyList()
                                    updateUiAfterLoad()
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}