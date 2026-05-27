package com.mrc.warehouse.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.mrc.warehouse.R
import com.mrc.warehouse.api.OneSApiClient
import com.mrc.warehouse.api.TaskItem
import com.mrc.warehouse.databinding.FragmentTasksBinding
import com.mrc.warehouse.util.NetworkUtil
import com.mrc.warehouse.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClosedTasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private lateinit var adapter: TasksAdapter

    private var allTasks: List<TaskItem> = emptyList()
    private var currentSortMode = "deadline"

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
            onViewTaskClick = { task -> showViewTaskDialog(task) }
        )
        binding.rvTasks.adapter = adapter

        // Restore saved sort mode, default to deadline
        currentSortMode = session.sortModeClosedTasks
        binding.chipSortDeadline.isChecked = currentSortMode == "deadline"
        binding.chipSortCreation.isChecked = currentSortMode == "creation"
        binding.chipSortPriority.isChecked = currentSortMode == "priority"

        binding.tvTitle.text = "Закрытые заявки"
        binding.chipSortCreation.setOnClickListener { setSortMode("creation") }
        binding.chipSortDeadline.setOnClickListener { setSortMode("deadline") }
        binding.chipSortPriority.setOnClickListener { setSortMode("priority") }

        // Search toggle: show/hide search bar
        binding.btnSearchToggle.setOnClickListener {
            val isVisible = binding.cardSearch.visibility == View.VISIBLE
            binding.cardSearch.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etSearch.requestFocus()
            }
        }

        // Swipe-to-refresh: force reload from server
        binding.swipeRefresh.setOnRefreshListener {
            refreshFromServer()
        }
        binding.swipeRefresh.setColorSchemeColors(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary)
        )

        // Search with TextWatcher so clearing the field resets the filter
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilters() }
        })

        loadTasks()
    }

    /** Pull-to-refresh: force reload from server, ignoring cache comparison */
    private fun refreshFromServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!NetworkUtil.isOnline(requireContext())) {
                    withContext(Dispatchers.Main) {
                        binding.swipeRefresh.isRefreshing = false
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Нет соединения с сервером."
                    }
                    return@launch
                }

                val client = session.createApiClient()
                val response = client.getClosedTasksUser()
                val serverTasks = response.tasks ?: emptyList()

                session.cachedTasksClosedJson = Gson().toJson(serverTasks)
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
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "Ошибка: ${e.message ?: "нет соединения"}. Показаны сохранённые данные."
                }
            }
        }
    }

    private fun loadTasks() {
        // 1. Immediately show cached data (no waiting)
        val cached = session.getCachedTasksClosed()
        if (cached.isNotEmpty()) {
            allTasks = cached
            updateUiAfterLoad()
        }

        // 2. In background, try to fetch fresh data from server
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!NetworkUtil.isOnline(requireContext())) {
                    withContext(Dispatchers.Main) {
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Нет соединения с сервером. Показаны сохранённые данные."
                    }
                    return@launch
                }

                val client = session.createApiClient()
                val response = client.getClosedTasksUser()
                val serverTasks = response.tasks ?: emptyList()
                val serverJson = Gson().toJson(serverTasks)

                // Compare with cached — only update UI if data actually changed
                val cachedJson = session.cachedTasksClosedJson
                if (serverJson == cachedJson) return@launch

                // Data changed — update cache and UI
                session.cachedTasksClosedJson = serverJson
                session.updateSyncTimestamp()
                allTasks = serverTasks

                withContext(Dispatchers.Main) {
                    binding.tvError.visibility = View.GONE
                    updateUiAfterLoad()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "Нет соединения с сервером. Показаны сохранённые данные."
                }
            }
        }
    }

    private fun updateUiAfterLoad() {
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
            onViewTaskClick = { task -> showViewTaskDialog(task) }
        )
        binding.rvTasks.adapter = adapter
        applyFilters()
    }

    private fun setSortMode(mode: String) {
        currentSortMode = mode
        session.sortModeClosedTasks = mode
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
            // Closed tasks — newest first, then highest priority
            "creation" -> sorted.sortWith(compareByDescending<TaskItem> { dateToSortKey(it.date) }.thenBy { it.priority ?: 0 })
            "deadline" -> sorted.sortWith(compareByDescending<TaskItem> { dateToSortKey(it.period) }.thenBy { it.priority ?: 0 })
            "priority" -> sorted.sortWith(compareByDescending<TaskItem> { it.priority ?: 0 }.thenByDescending { dateToSortKey(it.period) })
        }
        return sorted
    }

    /** Convert "dd.MM.yyyy HH:mm:ss" → "yyyyMMddHHmmss" for correct string-based date sorting */
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

    private fun showViewTaskDialog(task: TaskItem) {
        val ctx = requireContext()
        val sb = StringBuilder()

        sb.append("📋 <b>Название:</b> ${task.name ?: "—"}\n\n")
        sb.append("🆔 <b>Номер:</b> ${task.number ?: "—"}\n")
        sb.append("📊 <b>Статус:</b> ${task.status ?: "—"}\n")
        sb.append("🏢 <b>Подразделение:</b> ${task.nameDepartment ?: "—"}\n")
        sb.append("👤 <b>Клиент:</b> ${session.clients.firstOrNull { it.guid == task.guidClient }?.name ?: task.guidClient ?: "—"}\n")
        sb.append("👤 <b>Исполнитель:</b> ${task.user ?: "—"}\n")
        sb.append("📅 <b>Дата создания:</b> ${TasksAdapter.formatDate(task.date)}\n")
        sb.append("⏰ <b>Срок:</b> ${TasksAdapter.formatDate(task.period)}\n")
        sb.append("🔢 <b>Приоритет:</b> ${session.priorities.firstOrNull { it.value == task.priority }?.name ?: task.priority?.toString() ?: "—"}\n")
        if (task.hasAttachments == true) {
            sb.append("\n📎 <b>Вложения:</b> есть\n")
        }

        val description = task.description
        if (!description.isNullOrBlank()) {
            sb.append("\n\n📄 <b>Описание:</b>\n$description")
        }

        AlertDialog.Builder(ctx)
            .setTitle("👁 Просмотр заявки")
            .setMessage(sb.toString().trim())
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("📋 Копировать") { _, _ ->
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("task", sb.toString().trim()))
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}