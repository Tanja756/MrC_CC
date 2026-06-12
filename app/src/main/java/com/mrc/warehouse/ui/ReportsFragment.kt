package com.mrc.warehouse.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mrc.warehouse.R
import com.mrc.warehouse.api.TaskItem
import com.mrc.warehouse.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reports, container, false)
        val btnGenerate = view.findViewById<View>(R.id.btnGenerateTestReport)
        btnGenerate.setOnClickListener {
            generateActiveTasksReport()
        }
        return view
    }

    private fun generateActiveTasksReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = SessionManager(requireContext())

                // 1. Загружаем заявки в работе (статус не "Завершена")
                val allTasks = session.getCachedTasksUser()
                val activeTasks = allTasks.filter { task ->
                    task.status != "Завершена" && task.status != "Закрыта"
                }

                // 2. Сортируем по сроку выполнения (сначала самые срочные)
                val sortedTasks = activeTasks.sortedWith(
                    compareBy<TaskItem> { dateToSortKey(it.period) }
                        .thenByDescending { it.priority ?: 0 }
                )

                // 3. Получаем справочники
                val clientsMap = session.clients
                    .filter { it.guid != null && it.name != null && it.guid != "00000000-0000-0000-0000-000000000000" }
                    .associate { it.guid!! to it.name!! }
                val priorityMap = session.priorities
                    .filter { it.value != null && it.name != null }
                    .associate { it.value!! to it.name!! }

                // 4. Генерируем PDF
                val file = TasksReportExporter.exportTasksReport(
                    context = requireContext(),
                    tasks = sortedTasks,
                    clients = clientsMap,
                    priorities = priorityMap,
                    authority = "${requireContext().packageName}.fileprovider"
                )

                withContext(Dispatchers.Main) {
                    TasksReportExporter.sharePdf(
                        ctx = requireContext(),
                        file = file,
                        authority = "${requireContext().packageName}.fileprovider"
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
}
