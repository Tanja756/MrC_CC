package com.mrc.warehouse.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mrc.warehouse.R
import com.mrc.warehouse.api.TaskItem
import com.mrc.warehouse.databinding.ItemTaskCardBinding
import com.mrc.warehouse.util.SessionManager
import java.text.SimpleDateFormat
import java.util.*

class TasksAdapter(
    private var tasks: List<TaskItem>,
    private val clientsMap: Map<String, String>,
    private val priorityMap: Map<Int, String>,
    private val onDescriptionClick: (String?) -> Unit = {},
    private val onTakeTaskClick: ((TaskItem) -> Unit)? = null,
    private val onCompleteTaskClick: ((TaskItem) -> Unit)? = null,
    private val onViewTaskClick: ((TaskItem) -> Unit)? = null,
    private val onCardClick: ((TaskItem) -> Unit)? = null,
    private val onPinToggle: ((TaskItem) -> Unit)? = null
) : RecyclerView.Adapter<TasksAdapter.TaskViewHolder>() {

    // Multi-select mode
    var selectable: Boolean = false
        set(value) {
            field = value
            if (!value) selectedTaskGuids.clear()
            notifyDataSetChanged()
        }
    val selectedTaskGuids: MutableSet<String> = mutableSetOf()
    var onSelectionChanged: (() -> Unit)? = null
    var onEnterSelectMode: (() -> Unit)? = null

    fun updateData(newTasks: List<TaskItem>) {
        tasks = newTasks
        selectedTaskGuids.retainAll(newTasks.mapNotNull { it.guid })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(tasks[position])
    }

    override fun getItemCount() = tasks.size

    inner class TaskViewHolder(private val binding: ItemTaskCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private fun shareTask(task: TaskItem) {
            val ctx = binding.root.context
            val shareText = buildString {
                append("📋 Заявка: ${task.name ?: "Без названия"}\n")
                append("Статус: ${task.status ?: "—"}\n")
                append("Подразделение: ${task.nameDepartment ?: "—"}\n")
                append("Клиент: ${clientsMap[task.guidClient] ?: task.guidClient ?: "—"}\n")
                append("Дата создания: ${formatDate(task.date)}\n")
                append("Срок: ${formatDate(task.period)}\n")
                append("Приоритет: ${priorityMap[task.priority] ?: task.priority?.toString() ?: "—"}\n")
                if (!task.description.isNullOrBlank()) {
                    append("Описание: ${task.description}\n")
                }
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText.trim())
                putExtra(Intent.EXTRA_SUBJECT, "Заявка: ${task.name ?: "Без названия"}")
            }
            ctx.startActivity(Intent.createChooser(intent, "Поделиться заявкой"))
        }

        fun bind(task: TaskItem) {
            val ctx = binding.root.context
            val session = SessionManager(ctx)

            binding.tvTaskName.text = task.name ?: "Без названия"
            binding.tvDepartment.text = "Подр.: ${task.nameDepartment ?: "—"}"
            binding.tvClient.text = "Клиент: ${clientsMap[task.guidClient] ?: task.guidClient ?: "—"}"

            // ---- Multi-select checkbox ----
            val guid = task.guid
            if (selectable && guid != null) {
                binding.cbTaskSelect.visibility = View.VISIBLE
                binding.cbTaskSelect.isChecked = guid in selectedTaskGuids
                binding.root.setOnClickListener {
                    if (guid in selectedTaskGuids) {
                        selectedTaskGuids.remove(guid)
                    } else {
                        selectedTaskGuids.add(guid)
                    }
                    binding.cbTaskSelect.isChecked = guid in selectedTaskGuids
                    onSelectionChanged?.invoke()
                }
            } else {
                binding.cbTaskSelect.visibility = View.GONE
                if (onCardClick != null) {
                    binding.root.setOnClickListener { onCardClick(task) }
                } else {
                    binding.root.setOnClickListener(null)
                }
            }

            // Dates with time
            binding.tvDateCreated.text = "Созд.: ${formatDate(task.date)}"
            binding.tvDeadline.text = "Срок: ${formatDate(task.period)}"

            // ---- Reset state ----
            binding.tvTaskName.background = null
            binding.tvDeadline.setBackgroundResource(0)
            binding.tvDeadline.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.text_secondary)
            )
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(binding.root.context, R.color.bg_card)
            )
            binding.root.alpha = 1.0f
            binding.layoutAttachmentIndicator.visibility = View.GONE

            // ---- Gray out completed tasks (solid color, no transparency) ----
            val isCompleted = task.status == "Завершена"
            if (isCompleted) {
                binding.root.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.bg_completed)
                )
                binding.tvPriorityBadge.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.bg_completed)
                )
                binding.tvStatusBadge.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.bg_completed)
                )
            }
            else {
                // ---- Deadline urgency: highlight the task name (same approach for both levels) ----
                val urgency = getDeadlineUrgencyLevel(task.period)
                when (urgency) {
                    3,2 -> { // less than 2 hours — red background on name
                        binding.tvTaskName.setBackgroundColor(
                            ContextCompat.getColor(binding.root.context, R.color.bg_error)
                        )
                        binding.tvDeadline.setTextColor(
                            ContextCompat.getColor(binding.root.context, R.color.status_urgent)
                        )
                        binding.root.setCardBackgroundColor(
                            ContextCompat.getColor(binding.root.context, R.color.bg_error)
                        )
                        binding.tvDeadline.setBackgroundResource(R.drawable.chip_badge_bg)
                    }

                    1 -> { // less than 4 hours — yellow background on name
                        binding.tvTaskName.setBackgroundColor(
                            ContextCompat.getColor(binding.root.context, R.color.bg_warning)
                        )
                        binding.tvDeadline.setTextColor(
                            ContextCompat.getColor(binding.root.context, R.color.accent_dark)
                        )
                        binding.root.setCardBackgroundColor(
                            ContextCompat.getColor(binding.root.context, R.color.bg_warning)
                        )
                        binding.tvDeadline.setBackgroundResource(R.drawable.chip_badge_bg)
                    }

                    else -> {
                        // Keep defaults: white card, no highlight
                    }
                }
            }

            // ---- Pin icon ----
            if (onPinToggle != null && guid != null) {
                val isPinned = session.isTaskPinned(guid)
                binding.ivPin.visibility = View.VISIBLE
                binding.ivPin.setImageResource(
                    if (isPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin_outline
                )
                binding.ivPin.setOnClickListener {
                    if (isPinned) {
                        session.removePinnedTask(guid)
                    } else {
                        session.addPinnedTask(guid)
                    }
                    onPinToggle(task)
                }
            } else {
                binding.ivPin.visibility = View.GONE
            }

            // ---- Long press context menu (Share / Select / Pin) ----
            binding.root.setOnLongClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, "Поделиться")
                if (onTakeTaskClick != null) {
                    popup.menu.add(0, 2, 0, "Выбрать")
                }
                // Pin / Unpin item in long-press menu (only when onPinToggle is set, i.e. in "Мои заявки")
                if (onPinToggle != null && guid != null) {
                    val isPinned = session.isTaskPinned(guid)
                    popup.menu.add(0, 3, 0, if (isPinned) "Открепить" else "Закрепить")
                }
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            shareTask(task)
                            true
                        }
                        2 -> {
                            if (task.guid != null) {
                                selectedTaskGuids.add(task.guid)
                            }
                            selectable = true
                            onEnterSelectMode?.invoke()
                            onSelectionChanged?.invoke()
                            true
                        }
                        3 -> {
                            if (guid != null) {
                                if (session.isTaskPinned(guid)) {
                                    session.removePinnedTask(guid)
                                } else {
                                    session.addPinnedTask(guid)
                                }
                                onPinToggle?.invoke(task)
                            }
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
                true
            }

            binding.tvStatusBadge.text = "Статус: ${task.status ?: "—"}"
            binding.tvPriorityBadge.text = "Приоритет: ${priorityMap[task.priority] ?: task.priority?.toString() ?: "—"}"

            // ---- Attachment indicator (for closed tasks) ----
            if (task.hasAttachments == true) {
                binding.layoutAttachmentIndicator.visibility = View.VISIBLE
            }
            
            // ---- Location indicator (for closed tasks) ----
            if (onViewTaskClick != null && task.hasLocation) {
                binding.ivLocationIndicator.visibility = View.VISIBLE
                binding.ivLocationIndicator.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.status_success)
                )
            } else if (onViewTaskClick != null) {
                binding.ivLocationIndicator.visibility = View.GONE
                binding.ivLocationIndicator.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.status_error)
                )
            } else {
                binding.ivLocationIndicator.visibility = View.GONE
            }

            // ---- Action buttons ----

            // "Завершить" button – visible for user tasks (when callback provided)
            if (onCompleteTaskClick != null && task.guid != null && !isCompleted) {
                binding.btnCompleteTask.visibility = View.VISIBLE
                binding.btnCompleteTask.setOnClickListener { onCompleteTaskClick(task) }
            } else {
                binding.btnCompleteTask.visibility = View.GONE
            }

            // "Взять" button – only for free tasks
            if (onTakeTaskClick != null && task.guid != null) {
                binding.btnTakeTask.visibility = View.VISIBLE
                binding.btnTakeTask.setOnClickListener { onTakeTaskClick(task) }
            } else {
                binding.btnTakeTask.visibility = View.GONE
            }

            // Main button: "Описание" for normal tasks, "Просмотр" for closed tasks
            if (onViewTaskClick != null) {
                binding.btnOpenDescription.text = "Просмотр"
                binding.btnOpenDescription.setOnClickListener {
                    onViewTaskClick(task)
                }
            } else {
                binding.btnOpenDescription.text = "Описание"
                binding.btnOpenDescription.setOnClickListener {
                    onDescriptionClick(task.description)
                }
            }
        }
    }

    companion object {
        fun formatDate(dateStr: String?): String {
            if (dateStr.isNullOrBlank()) return "—"
            return try {
                val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
                val date = sdf.parse(dateStr)
                if (date != null) {
                    val outFmt = SimpleDateFormat("dd.MM.yy HH:mm", Locale.US)
                    outFmt.format(date)
                } else {
                    // fallback: just show as-is but truncate seconds
                    dateStr.replace(Regex(":\\d{2}$"), "")
                }
            } catch (e: Exception) {
                dateStr.replace(Regex(":\\d{2}$"), "")
            }
        }

        private fun parseMskDate(dateStr: String?): Date? {
            if (dateStr.isNullOrBlank()) return null
            return try {
                val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("Europe/Moscow")
                sdf.parse(dateStr)
            } catch (e: Exception) { null }
        }

        /**
         * Returns urgency level based on how close the deadline is:
         * 0 = more than 4 hours away or no deadline
         * 1 = less than 4 hours (yellow warning)
         * 2 = less than 2 hours (red urgent)
         */
        fun getDeadlineUrgencyLevel(periodStr: String?): Int {
            val deadline = parseMskDate(periodStr) ?: return 0
            val diff = deadline.time - System.currentTimeMillis()
            return when {
                diff <= 0 -> 3                    // просрочено
                diff < 2 * 60 * 60 * 1000L -> 2  // < 2 часов
                diff < 4 * 60 * 60 * 1000L -> 1  // < 4 часов
                else -> 0                         // всё в порядке
            }
        }

        /** Legacy: true if less than 2 hours */
        fun isDeadlineUrgent(periodStr: String?): Boolean {
            return getDeadlineUrgencyLevel(periodStr) == 2
        }
    }
}