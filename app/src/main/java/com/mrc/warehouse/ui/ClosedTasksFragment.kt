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

class ClosedTasksFragment : Fragment(), SearchSortCallback {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private lateinit var adapter: TasksAdapter

    private var allTasks: List<TaskItem> = emptyList()
    private var currentSortMode = "deadline"

    override fun onSearchToggle() {
        val isVisible = binding.cardSearch.visibility == View.VISIBLE
        binding.cardSearch.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (!isVisible) {
            binding.etSearch.requestFocus()
        }
    }

    override fun onSortToggle() {
        val isVisible = binding.cardSort.visibility == View.VISIBLE
        binding.cardSort.visibility = if (isVisible) View.GONE else View.VISIBLE
    }
    
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
            onViewTaskClick = { task -> showViewTaskDialog(task) },
            onCardClick = { task -> showViewTaskDialog(task) }
        )
        binding.rvTasks.adapter = adapter

        currentSortMode = session.sortModeClosedTasks
        binding.chipSortDeadline.isChecked = currentSortMode == "deadline"
        binding.chipSortCreation.isChecked = currentSortMode == "creation"
        binding.chipSortPriority.isChecked = currentSortMode == "priority"

        binding.tvTitle.visibility = View.GONE
        binding.chipSortCreation.setOnClickListener { setSortMode("creation") }
        binding.chipSortDeadline.setOnClickListener { setSortMode("deadline") }
        binding.chipSortPriority.setOnClickListener { setSortMode("priority") }

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

                val client = session.createApiClient(requireContext())
                val response = client.getClosedTasksUser()
                val serverTasks = response.tasks ?: emptyList<TaskItem>()

                // Сохраняем в кэш
                session.cachedTasksClosedJson = Gson().toJson(serverTasks)
                session.updateSyncTimestamp()
                session.markAutoSyncPerformed()
                
                // Загружаем обратно через getCachedTasksClosed, чтобы применилась
                // информация о локально сохранённых местоположениях (hasLocation)
                allTasks = session.getCachedTasksClosed()

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

    private fun loadTasks(force: Boolean = false) {
        val cached = session.getCachedTasksClosed()
        if (cached.isNotEmpty()) {
            allTasks = cached
            updateUiAfterLoad()
        } else {
            // Прямое обновление UI — здесь мы в главном потоке, так как loadTasks вызван из onCreateView
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = "Загрузка данных..."
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Автоматический запрос — проверяем лимит частоты через canPerformAutoSync()
            if (!force && !session.canPerformAutoSync()) {
                withContext(Dispatchers.Main) {
                    if (allTasks.isEmpty()) {
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Нет соединения или данные устарели"
                    } else {
                        binding.tvError.visibility = View.GONE
                    }
                }
                return@launch
            }

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

                val client = session.createApiClient(requireContext())
                val response = client.getClosedTasksUser()
                val serverTasks = response.tasks ?: emptyList<TaskItem>()
                val serverJson = Gson().toJson(serverTasks)

                val cachedJson = session.cachedTasksClosedJson
                if (serverJson == cachedJson) {
                    withContext(Dispatchers.Main) {
                        if (allTasks.isEmpty() && serverTasks.isEmpty()) {
                            binding.tvError.visibility = View.VISIBLE
                            binding.tvError.text = "Нет закрытых заявок."
                        } else {
                            binding.tvError.visibility = View.GONE
                        }
                    }
                    return@launch
                }

                // Сохраняем в кэш
                session.cachedTasksClosedJson = serverJson
                session.updateSyncTimestamp()
                session.markAutoSyncPerformed()
                
                // Загружаем обратно через getCachedTasksClosed, чтобы применилась
                // информация о локально сохранённых местоположениях (hasLocation)
                allTasks = session.getCachedTasksClosed()

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
            onViewTaskClick = { task -> showViewTaskDialog(task) },
            onCardClick = { task -> showViewTaskDialog(task) }
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
            "creation" -> sorted.sortWith(compareByDescending<TaskItem> { dateToSortKey(it.date) }.thenBy { it.priority ?: 0 })
            "deadline" -> sorted.sortWith(compareByDescending<TaskItem> { dateToSortKey(it.period) }.thenBy { it.priority ?: 0 })
            "priority" -> sorted.sortWith(compareByDescending<TaskItem> { it.priority ?: 0 }.thenByDescending { dateToSortKey(it.period) })
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

    private fun showViewTaskDialog(task: TaskItem) {
        val dialog = TaskDetailDialogFragment.newInstance(
            task = task,
            mode = TaskDetailDialogFragment.DialogMode.CLOSED_TASK
        )
        dialog.show(parentFragmentManager, "TaskDetailDialog")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}