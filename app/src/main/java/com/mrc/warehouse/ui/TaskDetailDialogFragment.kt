package com.mrc.warehouse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mrc.warehouse.R
import com.mrc.warehouse.api.AttachmentData
import com.mrc.warehouse.api.TaskCloseRequest
import com.mrc.warehouse.api.TaskItem
import com.mrc.warehouse.databinding.DialogTaskDetailBinding
import com.mrc.warehouse.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.annotation.SuppressLint
import androidx.fragment.app.DialogFragment

/**
 * BottomSheetDialogFragment для просмотра и управления заявкой.
 *
 * Режимы:
 * - FREE_TASK: свободная заявка (кнопки: Взять в работу, Копировать, Назад)
 * - USER_TASK: моя заявка (вложение, PDF → Закрыть заявку, Копировать, Назад)
 * - CLOSED_TASK: закрытая заявка (кнопки: Копировать, Назад)
 */
class TaskDetailDialogFragment : DialogFragment() {

    private var _binding: DialogTaskDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Параметры
    private var task: TaskItem? = null
    private var mode: DialogMode = DialogMode.CLOSED_TASK
    private var onTakeTask: ((TaskItem) -> Unit)? = null
    private var onTaskClosed: (() -> Unit)? = null

    // File picker
    private var selectedFiles = mutableListOf<FileAttachment>()
    private val anyFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) addFileToSelection(uri)
    }
    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) addFileToSelection(uri)
    }

    private data class FileAttachment(
        val uri: Uri,
        val fileName: String,
        val extension: String
    )

    // Геолокация
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0

    enum class DialogMode {
        FREE_TASK,
        USER_TASK,
        CLOSED_TASK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_MrCWarehouse_Dialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTaskDetailBinding.inflate(inflater, container, false)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val task = task ?: run { dismiss(); return }

        // Секция 1: Наименование
        binding.tvTaskName.text = task.name ?: "Без названия"

        // Секция 2: Описание (очистка HTML)
        val description = task.description
        if (description.isNullOrBlank()) {
            binding.tvTaskDescription.text = "Нет описания"
        } else {
            binding.tvTaskDescription.text = stripHtml(description)
        }

        // Секция 3: Комментарий
        configureCommentSection(task)

        // Секция 4: Кнопки
        configureButtons(task)

        // Назад
        binding.btnBack.setOnClickListener { dismiss() }
    }

    // ====================== Комментарий ======================

    private fun configureCommentSection(task: TaskItem) {
        when (mode) {
            DialogMode.FREE_TASK -> {
                binding.sectionComment.visibility = View.GONE
                // Контейнер файлов тоже скрываем
                binding.tvFilesSectionTitle.visibility = View.GONE
                binding.filesContainer.visibility = View.GONE
            }
            DialogMode.CLOSED_TASK -> {
                binding.commentLayout.visibility = View.GONE
                binding.tvCommentText.visibility = View.VISIBLE
                binding.tvFilesSectionTitle.visibility = View.GONE
                binding.filesContainer.visibility = View.GONE
                // Показываем closeComment
                val commentText = task.closeComment
                if (commentText.isNullOrBlank()) {
                    binding.tvCommentText.text = "Комментарий не оставлен"
                } else {
                    binding.tvCommentText.text = stripHtml(commentText)
                }
            }
            DialogMode.USER_TASK -> {
                binding.commentLayout.visibility = View.VISIBLE
                binding.tvCommentText.visibility = View.GONE
                // Контейнер файлов показываем, если есть файлы
                updateFilesUi()
            }
        }
    }

    // ====================== Кнопки ======================

    private fun configureButtons(task: TaskItem) {
        binding.btnTakeTask.visibility = View.GONE
        binding.btnCloseTask.visibility = View.GONE
        binding.btnAddAttachment.visibility = View.GONE
        binding.btnAddPdf.visibility = View.GONE
        binding.btnCopy.visibility = View.VISIBLE

        when (mode) {
            DialogMode.FREE_TASK -> {
                binding.btnTakeTask.visibility = View.VISIBLE
                binding.btnTakeTask.setOnClickListener {
                    onTakeTask?.invoke(task)
                    dismiss()
                }
            }
            DialogMode.USER_TASK -> {
                binding.btnCloseTask.visibility = View.VISIBLE
                binding.btnAddAttachment.visibility = View.VISIBLE
                binding.btnAddPdf.visibility = View.VISIBLE

                binding.btnAddAttachment.setOnClickListener {
                    anyFilePickerLauncher.launch(arrayOf("*/*"))
                }
                binding.btnAddPdf.setOnClickListener {
                    pdfPickerLauncher.launch(arrayOf("application/pdf"))
                }

                binding.btnCloseTask.setOnClickListener {
                    onCloseTaskClick(task)
                }
            }
            DialogMode.CLOSED_TASK -> { /* только копировать + назад */ }
        }

        binding.btnCopy.setOnClickListener { copyTaskInfo(task) }
    }

    // ====================== Закрытие заявки ======================

    private fun onCloseTaskClick(task: TaskItem) {
        val comment = binding.etComment.text?.toString()?.trim() ?: ""
        val hasComment = comment.isNotBlank()
        val hasFiles = selectedFiles.isNotEmpty()

        if (!hasComment && !hasFiles) {
            binding.tvValidationError.visibility = View.VISIBLE
            return
        }
        binding.tvValidationError.visibility = View.GONE

        val guid = task.guid
        if (guid.isNullOrBlank()) {
            showError("Ошибка", "У заявки отсутствует идентификатор")
            return
        }

        val ticketNumber = extractTicketNumber(task.name) ?: task.number ?: "?"

        // Показываем диалог загрузки
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle("Завершение заявки $ticketNumber")
            .setMessage("Выполняется...")
            .setCancelable(false)
            .show()

        dismiss()

        // Получаем геолокацию и отправляем
        requestLocationAndClose(guid, ticketNumber, comment, loadingDialog)
    }

    private fun requestLocationAndClose(
        guid: String,
        ticketNumber: String,
        comment: String,
        loadingDialog: AlertDialog
    ) {
        if (hasLocationPermission()) {
            getCurrentLocation { lat, lng ->
                executeClose(guid, ticketNumber, comment, lat, lng, loadingDialog)
            }
        } else {
            requestLocationPermission(guid, ticketNumber, comment, loadingDialog)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(callback: (Double, Double) -> Unit) {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude
                    callback(location.latitude, location.longitude)
                } else {
                    callback(0.0, 0.0)
                }
            }
            .addOnFailureListener { callback(0.0, 0.0) }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            // Данные для закрытия уже сохранены в замыкании
        }

    private fun requestLocationPermission(
        guid: String,
        ticketNumber: String,
        comment: String,
        loadingDialog: AlertDialog
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle("Разрешение на геолокацию")
            .setMessage("Для завершения заявки требуется определить ваше местоположение. Разрешить доступ к геолокации?")
            .setPositiveButton("Разрешить") { _, _ ->
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                // Продолжаем без координат
                executeClose(guid, ticketNumber, comment, 0.0, 0.0, loadingDialog)
            }
            .setNegativeButton("Отмена") { _, _ ->
                executeClose(guid, ticketNumber, comment, 0.0, 0.0, loadingDialog)
            }
            .show()
    }

    private fun executeClose(
        guid: String,
        ticketNumber: String,
        comment: String,
        latitude: Double,
        longitude: Double,
        loadingDialog: AlertDialog
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = session.createApiClient()
                val attachments = readAttachments()

                val request = TaskCloseRequest(
                    attachments = attachments,
                    comment = comment,
                    guid = guid,
                    latitude = latitude,
                    longitude = longitude
                )

                // Сохраняем локацию
                if (latitude != 0.0 && longitude != 0.0) {
                    session.saveTaskLocation(guid, latitude, longitude)
                }

                val success = client.taskClose(request)

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    if (success) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("✅ Заявка отправлена")
                            .setMessage("Данные по заявке $ticketNumber отправлены. После проверки менеджером она будет закрыта.")
                            .setPositiveButton("OK") { _, _ ->
                                onTaskClosed?.invoke()
                            }
                            .show()
                    } else {
                        showError("Ошибка сервера", "Сервер вернул ошибку при отправке заявки $ticketNumber. Попробуйте позже.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showError("Ошибка", "Не удалось отправить заявку: ${e.message}")
                }
            }
        }
    }

    // ====================== Файлы ======================

    private fun addFileToSelection(uri: Uri) {
        val fileName = getFileName(uri) ?: "file"
        val ext = when {
            fileName.endsWith(".pdf", ignoreCase = true) -> "pdf"
            else -> "bin"
        }
        if (selectedFiles.any { it.uri == uri }) return
        selectedFiles.add(FileAttachment(uri, fileName, ext))
        updateFilesUi()
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            if (nameIndex >= 0) it.getString(nameIndex) else null
        }
    }

    private fun updateFilesUi() {
        if (selectedFiles.isEmpty()) {
            binding.tvFilesSectionTitle.visibility = View.GONE
            binding.filesContainer.visibility = View.GONE
            return
        }
        binding.tvFilesSectionTitle.visibility = View.VISIBLE
        binding.filesContainer.visibility = View.VISIBLE
        binding.filesContainer.removeAllViews()

        selectedFiles.forEachIndexed { index, file ->
            val row = LayoutInflater.from(requireContext())
                .inflate(android.R.layout.simple_list_item_1, binding.filesContainer, false) as TextView
            row.text = "${index + 1}. 📄 ${file.fileName}"
            row.setTextColor(resources.getColor(com.mrc.warehouse.R.color.text_primary, null))
            row.textSize = 13f
            row.setPadding(0, 8, 0, 8)
            row.setOnClickListener { removeFile(index) }
            row.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_close_clear_cancel, 0)
            row.compoundDrawablePadding = 8
            binding.filesContainer.addView(row)
        }
    }

    private fun removeFile(index: Int) {
        if (index in selectedFiles.indices) {
            selectedFiles.removeAt(index)
            updateFilesUi()
        }
    }

    private fun readAttachments(): List<AttachmentData> {
        return selectedFiles.mapNotNull { file ->
            try {
                val inputStream = requireContext().contentResolver.openInputStream(file.uri)
                val bytes = inputStream?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    AttachmentData(data = base64, extension = file.extension)
                } else null
            } catch (e: Exception) { null }
        }
    }

    // ====================== Утилиты ======================

    private fun stripHtml(html: String): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html).toString().trim()
        }
    }

    private fun copyTaskInfo(task: TaskItem) {
        val clientsMap = session.clients
            .filter { it.guid != null && it.name != null && it.guid != "00000000-0000-0000-0000-000000000000" }
            .associate { it.guid!! to it.name!! }
        val priorityMap = session.priorities
            .filter { it.value != null && it.name != null }
            .associate { it.value!! to it.name!! }

        val sb = StringBuilder()
        sb.append("📋 Название: ${task.name ?: "—"}\n")
        sb.append("🔄 Номер: ${task.number ?: "—"}\n")
        sb.append("📊 Статус: ${task.status ?: "—"}\n")
        sb.append("🏢 Подразделение: ${task.nameDepartment ?: "—"}\n")
        sb.append("👤 Клиент: ${clientsMap[task.guidClient] ?: task.guidClient ?: "—"}\n")
        sb.append("👤 Исполнитель: ${task.user ?: "—"}\n")
        sb.append("📅 Дата создания: ${TasksAdapter.formatDate(task.date)}\n")
        sb.append("⏰ Срок: ${TasksAdapter.formatDate(task.period)}\n")
        sb.append("🔢 Приоритет: ${priorityMap[task.priority] ?: task.priority?.toString() ?: "—"}\n")
        if (task.hasAttachments == true) sb.append("\n📎 Вложения: есть\n")
        val desc = task.description
        if (!desc.isNullOrBlank()) sb.append("\n\n📄 Описание:\n${stripHtml(desc)}")

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("task", sb.toString().trim()))
    }

    private fun extractTicketNumber(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("[А-ЯЁа-яё]{2}-\\d{6}")
        return regex.find(text)?.value
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            task: TaskItem,
            mode: DialogMode = DialogMode.CLOSED_TASK,
            onTakeTask: ((TaskItem) -> Unit)? = null,
            onTaskClosed: (() -> Unit)? = null
        ): TaskDetailDialogFragment {
            val fragment = TaskDetailDialogFragment()
            fragment.task = task
            fragment.mode = mode
            fragment.onTakeTask = onTakeTask
            fragment.onTaskClosed = onTaskClosed
            return fragment
        }
    }
}