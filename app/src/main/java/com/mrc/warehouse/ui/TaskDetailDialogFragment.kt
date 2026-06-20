package com.mrc.warehouse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.mrc.warehouse.R
import com.mrc.warehouse.api.AttachmentItem
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
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.fragment.app.DialogFragment
import java.io.File
import java.io.FileOutputStream

/**
 * BottomSheetDialogFragment для просмотра и управления заявкой.
 *
 * Режимы:
 * - FREE_TASK: свободная заявка (кнопки: Взять в работу, Копировать, Назад)
 * - USER_TASK: моя заявка (вложение, PDF → Закрыть заявку, Копировать, Назад)
 * - CLOSED_TASK: закрытая заявка (кнопки: Копировать, Назад)
 *   + отображение вложений с возможностью скачать/открыть
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

        // Секция 4: Вложения (для закрытых заявок)
        configureAttachmentsSection(task)

        // Секция 5: Кнопки
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

    // ====================== Вложения (для закрытых заявок) ======================

    /**
     * Настраивает секцию вложений для закрытых заявок.
     * Если у заявки есть вложения (hasAttachments == true),
     * показываем секцию с кнопкой "Загрузить вложения".
     * Загрузка происходит только по нажатию кнопки.
     */
    private fun configureAttachmentsSection(task: TaskItem) {
        if (mode != DialogMode.CLOSED_TASK || task.hasAttachments != true) {
            binding.sectionAttachments.visibility = View.GONE
            return
        }

        binding.sectionAttachments.visibility = View.VISIBLE
        binding.attachmentsContainer.removeAllViews()
        binding.btnLoadAttachments.visibility = View.VISIBLE
        binding.attachmentsProgress.visibility = View.GONE

        binding.btnLoadAttachments.setOnClickListener {
            binding.btnLoadAttachments.visibility = View.GONE
            binding.attachmentsProgress.visibility = View.VISIBLE
            binding.attachmentsContainer.removeAllViews()
            loadAttachments(task.guid ?: "")
        }
    }

    /**
     * Загружает список вложений с сервера.
     */
    private fun loadAttachments(guid: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = session.createApiClient(requireContext())
                val response = client.getTaskAttachments(guid)
                val attachments = response?.attachments?.filter { it.content != null } ?: emptyList()

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    binding.attachmentsProgress.visibility = View.GONE
                    displayAttachments(attachments)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    binding.attachmentsProgress.visibility = View.GONE
                    binding.btnLoadAttachments.visibility = View.VISIBLE
                    binding.attachmentsContainer.removeAllViews()
                    val errorText = TextView(requireContext()).apply {
                        text = "Ошибка загрузки вложений: ${e.message}"
                        setTextColor(resources.getColor(R.color.status_error, null))
                        textSize = 13f
                        setPadding(0, 8, 0, 8)
                    }
                    binding.attachmentsContainer.addView(errorText)
                }
            }
        }
    }

    /**
     * Отображает список вложений с кнопкой загрузки и открытия.
     */
    private fun displayAttachments(attachments: List<AttachmentItem>) {
        binding.attachmentsContainer.removeAllViews()

        if (attachments.isEmpty()) {
            val noAttachText = TextView(requireContext()).apply {
                text = "Нет доступных вложений"
                setTextColor(resources.getColor(R.color.text_secondary, null))
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            binding.attachmentsContainer.addView(noAttachText)
            return
        }

        attachments.forEachIndexed { index, attachment ->
            val fileName = attachment.filename ?: "file_${index + 1}"
            val fileType = attachment.filetype ?: "application/octet-stream"
            val content = attachment.content ?: return@forEachIndexed

            // Строка файла с иконкой загрузки справа
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 6, 0, 6)
            }

            val fileInfo = TextView(requireContext()).apply {
                text = "📎 $fileName"
                setTextColor(resources.getColor(R.color.text_primary, null))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            rowLayout.addView(fileInfo)

            val btnDownload = MaterialButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.attachment_icon_size),
                    resources.getDimensionPixelSize(R.dimen.attachment_icon_size)
                )
                icon = resources.getDrawable(R.drawable.ic_download, requireContext().theme)
                iconSize = 24
                text = ""
                setTextColor(resources.getColor(R.color.white, null))
                backgroundTintList = resources.getColorStateList(R.color.secondary, null)
                iconTint = resources.getColorStateList(R.color.white, null)
                setOnClickListener {
                    saveAndOpenAttachment(content, fileName, fileType)
                }
            }
            rowLayout.addView(btnDownload)

            binding.attachmentsContainer.addView(rowLayout)

            // Разделитель (кроме последнего)
            if (index < attachments.size - 1) {
                val divider = View(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1
                    )
                    setBackgroundColor(resources.getColor(R.color.divider, null))
                    setPadding(0, 4, 0, 4)
                }
                binding.attachmentsContainer.addView(divider)
            }
        }
    }

    /**
     * Определяет MIME-тип по расширению файла.
     */
    private fun getMimeTypeFromExtension(fileName: String): String? {
        val ext = getExtensionFromFileName(fileName) ?: return null
        return when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> null
        }
    }

    /**
     * Сохраняет вложение в Downloads, открывает через стандартное приложение.
     * Сначала сохраняет во временный кэш для гарантированного открытия через FileProvider,
     * затем в фоне копирует в папку Downloads.
     */
    private fun saveAndOpenAttachment(
        base64Content: String,
        fileName: String,
        fileType: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Декодируем base64
                val cleanBase64 = base64Content.substringAfter("base64,").trim()
                val fileBytes = java.util.Base64.getMimeDecoder().decode(cleanBase64)

                // Определяем корректный MIME-тип
                val resolvedMimeType = getMimeTypeFromExtension(fileName)
                    ?: if (fileType != "application/octet-stream") fileType
                    else "application/octet-stream"

                val safeFileName = fileName.replace(Regex("[^a-zA-Zа-яА-Я0-9._\\- ]"), "_")

                // 1) Сохраняем во временный кэш для немедленного открытия
                val cacheDir = File(requireContext().cacheDir, "attachments")
                cacheDir.mkdirs()
                val cacheFile = File(cacheDir, safeFileName)
                FileOutputStream(cacheFile).use { fos ->
                    fos.write(fileBytes)
                    fos.flush()
                }

                // 2) В фоне копируем в Downloads для постоянного хранения
                copyToDownloads(fileBytes, safeFileName, resolvedMimeType)

                // 3) Открываем через FileProvider (гарантированно работает)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    try {
                        val uri = FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.fileprovider",
                            cacheFile
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, resolvedMimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        // Пытаемся запустить — если нет приложения, вылетит исключение
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Если не удалось открыть — сообщаем, что файл сохранён
                        AlertDialog.Builder(requireContext())
                            .setTitle("Файл сохранён")
                            .setMessage("Файл \"$fileName\" сохранён в папке Downloads. Откройте его через проводник.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    AlertDialog.Builder(requireContext())
                        .setTitle("Ошибка")
                        .setMessage("Не удалось сохранить или открыть файл: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /**
     * Копирует файл в папку Downloads в фоне.
     */
    private fun copyToDownloads(fileBytes: ByteArray, fileName: String, mimeType: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = requireContext().contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { os ->
                            os.write(fileBytes)
                            os.flush()
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val file = File(downloadsDir, fileName)
                    var targetFile = file
                    var counter = 1
                    while (targetFile.exists()) {
                        val dotIndex = fileName.lastIndexOf('.')
                        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
                        val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""
                        targetFile = File(downloadsDir, "${baseName}_($counter)$ext")
                        counter++
                    }
                    FileOutputStream(targetFile).use { fos ->
                        fos.write(fileBytes)
                        fos.flush()
                    }
                }
            } catch (_: Exception) {
                // Файл уже есть в кэше — ошибка копирования не критична
            }
        }
    }

    private fun getExtensionFromFileName(fileName: String): String? {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex >= 0 && dotIndex < fileName.length - 1) {
            fileName.substring(dotIndex + 1).lowercase()
        } else null
    }

    private fun getExtensionFromMimeType(mimeType: String): String? {
        return when {
            mimeType.contains("pdf") -> "pdf"
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("bmp") -> "bmp"
            mimeType.contains("msword") || mimeType.contains("document") -> "docx"
            mimeType.contains("spreadsheet") || mimeType.contains("excel") -> "xlsx"
            mimeType.contains("text") -> "txt"
            mimeType.contains("zip") -> "zip"
            mimeType.contains("octet-stream") -> null
            else -> null
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
                val client = session.createApiClient(requireContext())
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
                    if (!isAdded) return@withContext
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
                    if (!isAdded) return@withContext
                    showError("Ошибка", "Не удалось отправить заявку: ${e.message}")
                }
            }
        }
    }

    // ====================== Файлы (для закрытия заявки) ======================

    private fun getFileExtension(uri: Uri): String {
        val context = requireContext()
        val mime = context.contentResolver.getType(uri)
        return when {
            mime == "application/pdf" -> "pdf"
            mime?.startsWith("image/") == true -> {
                val subtype = mime.substringAfter("/")
                when (subtype) {
                    "jpeg" -> "jpg"
                    "png" -> "png"
                    "gif" -> "gif"
                    "webp" -> "webp"
                    "bmp" -> "bmp"
                    else -> "jpg"
                }
            }
            else -> {
                val name = getFileName(uri) ?: "file.bin"
                name.substringAfterLast(".", "bin")
            }
        }
    }

    private fun addFileToSelection(uri: Uri) {
        val fileName = getFileName(uri) ?: "file"
        val ext = getFileExtension(uri)
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