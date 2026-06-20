package com.mrc.warehouse.ui

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.mrc.warehouse.R
import com.mrc.warehouse.api.AttachmentData
import com.mrc.warehouse.api.TaskCloseRequest
import com.mrc.warehouse.api.TaskItem
import com.mrc.warehouse.databinding.DialogTaskCloseBinding
import com.mrc.warehouse.databinding.FragmentTasksBinding
import com.mrc.warehouse.util.NetworkUtil
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
import java.io.File
import java.io.FileOutputStream


class TasksFragment : Fragment(), SearchSortCallback {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private lateinit var adapter: TasksAdapter

    private var allTasks: List<TaskItem> = emptyList()
    private var currentSortMode = "deadline"
    private var isOffline = false

    // GPS position
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    // Поля для отложенного завершения задачи после получения координат
    private var pendingTask: TaskItem? = null
    private var pendingTicketNumber: String = ""
    private var pendingComment: String = ""
    private var pendingLoadingDialog: AlertDialog? = null
    // Индикатор местоположения в диалоге закрытия
    private var locationStatusTextView: TextView? = null
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0

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

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }    // Лаунчер для запроса геолокации

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                // Разрешение получено – пробуем получить координаты
                getCurrentLocation { lat, lng ->
                    // Обновляем индикатор если диалог открыт
                    if (locationStatusTextView != null) {
                        updateLocationStatusIndicator()
                    } else {
                        continueWithCoordinates(lat, lng)
                    }
                }
            } else {
                // Отказ – продолжаем без координат
                if (locationStatusTextView != null) {
                    updateLocationStatusIndicator()
                } else {
                    continueWithCoordinates(0.0, 0.0)
                }
            }
        }
    @SuppressLint("MissingPermission") // разрешение уже проверено перед вызовом
    private fun getCurrentLocation(callback: (Double, Double) -> Unit) {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude
                    callback(location.latitude, location.longitude)
                } else {
                    currentLatitude = 0.0
                    currentLongitude = 0.0
                    callback(0.0, 0.0)
                }
            }
            .addOnFailureListener {
                currentLatitude = 0.0
                currentLongitude = 0.0
                callback(0.0, 0.0)
            }
    }
    private fun continueWithCoordinates(latitude: Double, longitude: Double) {
        val task = pendingTask ?: run {
            // Если нет сохранённой задачи, скрываем диалог и выходим
            pendingLoadingDialog?.dismiss()
            return
        }
        val ticketNumber = pendingTicketNumber
        val comment = pendingComment
        val loadingDialog = pendingLoadingDialog

        // Обновляем координаты и индикатор местоположения
        currentLatitude = latitude
        currentLongitude = longitude
        updateLocationStatusIndicator()

        // Очищаем временные переменные
        pendingTask = null
        pendingTicketNumber = ""
        pendingComment = ""
        pendingLoadingDialog = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val guid = task.guid ?: return@launch
                // Сохраняем местоположение в локальной базе
                if (latitude != 0.0 && longitude != 0.0) {
                    session.saveTaskLocation(guid, latitude, longitude)
                }
                executeTaskClose(guid, ticketNumber, comment, latitude, longitude, loadingDialog!!)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog?.dismiss()
                    showError("Ошибка", "Не удалось завершить заявку: ${e.message}")
                }
            }
        }
    }
    // ---- Task close dialog state ----
    private var closeDialog: AlertDialog? = null
    private var selectedFiles = mutableListOf<FileAttachment>()
    private var dialogBinding: DialogTaskCloseBinding? = null

    private data class FileAttachment(
        val uri: Uri,
        val fileName: String,
        val extension: String
    )

    // File picker launchers
    private val anyFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            addFileToSelection(uri)
        }
    }
    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            addFileToSelection(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
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
            onCompleteTaskClick = { task -> completeTask(task) },
            onCardClick = { task -> showUserTaskDetailDialog(task) },
            onPinToggle = { applyFilters() },
            onRequestDocumentsClick = { task -> requestTaskDocuments(task) }
        )
        binding.rvTasks.adapter = adapter

        // Restore saved sort mode, default to deadline
        currentSortMode = session.sortModeTasks
        binding.chipSortDeadline.isChecked = currentSortMode == "deadline"
        binding.chipSortCreation.isChecked = currentSortMode == "creation"
        binding.chipSortPriority.isChecked = currentSortMode == "priority"

        binding.tvTitle.visibility = View.GONE
        binding.chipSortCreation.setOnClickListener { setSortMode("creation") }
        binding.chipSortDeadline.setOnClickListener { setSortMode("deadline") }
        binding.chipSortPriority.setOnClickListener { setSortMode("priority") }

        // Swipe-to-refresh: force reload from server
        binding.swipeRefresh.setOnRefreshListener {
            refreshFromServer()
        }
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.primary)
        )

        // Search with TextWatcher so clearing the field resets the filter
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilters() }
        })

        loadTasks()
    }

    // ========================== Load tasks ==========================

    /** Pull-to-refresh: force reload from server, ignoring cache comparison */
    private fun refreshFromServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!NetworkUtil.isOnline(requireContext())) {
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.swipeRefresh.isRefreshing = false
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Нет соединения с сервером."
                    }
                    return@launch
                }

                val client = session.createApiClient(requireContext())
                val response = client.getTasksUser()
                val serverTasks = response.tasks ?: emptyList()

                session.cachedTasksUserJson = Gson().toJson(serverTasks)
                session.updateSyncTimestamp()
                session.markAutoSyncPerformed()
                allTasks = serverTasks

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    isOffline = false
                    binding.tvError.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    updateUiAfterLoad()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.swipeRefresh.isRefreshing = false
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "Ошибка: ${e.message ?: "нет соединения"}. Показаны сохранённые данные."
                }
            }
        }
    }

    /**
     * Загрузка задач с кэша и фоновым обновлением с сервера.
     * @param force если true, игнорирует интервал авто-синхронизации (принудительное обновление)
     */
    private fun loadTasks(force: Boolean = false) {
        // 1. Immediately show cached data (no waiting)
        val cached = session.getCachedTasksUser()
        if (cached.isNotEmpty()) {
            allTasks = cached
            isOffline = true
            updateUiAfterLoad()
        }

        // 2. In background, try to fetch fresh data from server
        CoroutineScope(Dispatchers.IO).launch {
            // Автоматический запрос — проверяем лимит частоты через canPerformAutoSync()
            if (!force && !session.canPerformAutoSync()) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.tvError.visibility = if (allTasks.isEmpty()) View.VISIBLE else View.GONE
                    if (allTasks.isEmpty()) binding.tvError.text = "Нет соединения или данные устарели"
                }
                return@launch
            }

            try {
                if (!NetworkUtil.isOnline(requireContext())) {
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Нет соединения с сервером. Показаны сохранённые данные."
                    }
                    return@launch
                }

                val client = session.createApiClient(requireContext())
                val response = client.getTasksUser()
                val serverTasks = response.tasks ?: emptyList()
                val serverJson = Gson().toJson(serverTasks)

                // Compare with cached — only update UI if data actually changed
                val cachedJson = session.cachedTasksUserJson
                if (serverJson == cachedJson) return@launch // no change, keep current UI

                // Data changed — update cache and UI
                session.cachedTasksUserJson = serverJson
                session.updateSyncTimestamp()
                session.markAutoSyncPerformed()
                allTasks = serverTasks

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    isOffline = false
                    binding.tvError.visibility = View.GONE
                    updateUiAfterLoad()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "Нет соединения с сервером. Показаны сохранённые данные."
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
            onCompleteTaskClick = { task -> completeTask(task) },
            onCardClick = { task -> showUserTaskDetailDialog(task) },
            onPinToggle = { applyFilters() },
            onRequestDocumentsClick = { task -> requestTaskDocuments(task) }
        )
        binding.rvTasks.adapter = adapter
        applyFilters()
    }

    private fun setSortMode(mode: String) {
        currentSortMode = mode
        session.sortModeTasks = mode
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
            .let { list ->
                val pinnedGuids = session.getPinnedTasks()
                val pinned = list.filter { it.guid != null && pinnedGuids.contains(it.guid) }
                val unpinned = list.filter { it.guid == null || !pinnedGuids.contains(it.guid) }
                sortTasks(pinned, currentSortMode) + sortTasks(unpinned, currentSortMode)
            }

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

    // ========================== Task close dialog ==========================

    private fun completeTask(task: TaskItem) {
        val guid = task.guid
        if (guid.isNullOrBlank()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Ошибка")
                .setMessage("У заявки отсутствует идентификатор")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        selectedFiles.clear()
        dialogBinding = DialogTaskCloseBinding.inflate(LayoutInflater.from(requireContext()))

        val ticketNumber = extractTicketNumber(task.name) ?: task.number ?: "?"
        dialogBinding!!.tvDialogTitle.text = "Завершение заявки $ticketNumber"

        // Initialize location status indicator
        locationStatusTextView = dialogBinding!!.tvLocationStatus
        updateLocationStatusIndicator()

        // Check and request location permission before starting location tracking
        if (hasLocationPermission()) {
            startLocationTracking()
        } else {
            requestLocationPermission()
        }

        // Add file buttons
        dialogBinding!!.btnAddFile.setOnClickListener {
            pdfPickerLauncher.launch(arrayOf("application/pdf"))
        }
        dialogBinding!!.btnAddAttachment.setOnClickListener {
            anyFilePickerLauncher.launch(arrayOf("*/*"))
        }

        // Validation error hidden initially
        dialogBinding!!.tvValidationError.visibility = View.GONE

        closeDialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding!!.root)
            .setCancelable(false)
            .setPositiveButton("Завершить", null) // Set later to prevent auto-dismiss
            .setNegativeButton("Отмена") { _, _ ->
                closeDialog = null
                dialogBinding = null
                selectedFiles.clear()
            }
            .show()

        // Override positive button to handle validation
        closeDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            onCloseConfirmClick(task, ticketNumber)
        }
    }

    private fun requestLocationPermission() {
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
            }
            .setNegativeButton("Отмена") { _, _ ->
                // Продолжаем без геолокации
                updateLocationStatusIndicator()
            }
            .show()
    }

    private fun startLocationTracking() {
        if (hasLocationPermission()) {
            getCurrentLocation { lat, lng ->
                // Update UI on main thread
                if (_binding != null) {
                    updateLocationStatusIndicator()
                }
            }
        } else {
            requestLocationPermission()
        }
    }

    private fun updateLocationStatusIndicator() {
        locationStatusTextView?.let { tv ->
            if (currentLatitude == 0.0 && currentLongitude == 0.0) {
                tv.text = "Не установлено"
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
            } else {
                tv.text = "Установлено"
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_success))
            }
        }
    }

    private fun onCloseConfirmClick(task: TaskItem, ticketNumber: String) {
        val comment = dialogBinding?.etComment?.text?.toString()?.trim() ?: ""
        val hasComment = comment.isNotBlank()
        val hasFiles = selectedFiles.isNotEmpty()

        if (!hasComment && !hasFiles) {
            dialogBinding?.tvValidationError?.visibility = View.VISIBLE
            return
        }
        dialogBinding?.tvValidationError?.visibility = View.GONE

        // Закрываем диалог
        closeDialog?.dismiss()
        closeDialog = null
        dialogBinding = null

        // Сохраняем состояние
        pendingTask = task
        pendingTicketNumber = ticketNumber
        pendingComment = comment

        // Показываем диалог загрузки
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle("Завершение заявки $ticketNumber")
            .setMessage("Выполняется...")
            .setCancelable(false)
            .show()
        pendingLoadingDialog = loadingDialog

        // Проверяем разрешение и получаем координаты
        if (hasLocationPermission()) {
            getCurrentLocation { lat, lng ->
                continueWithCoordinates(lat, lng)
            }
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    private suspend fun executeTaskClose(
        guid: String,
        ticketNumber: String,
        comment: String,
        latitude: Double,
        longitude: Double,
        loadingDialog: AlertDialog
    ) {
        val client = session.createApiClient(requireContext())

        // Read selected files into base64 attachments
        val attachments = readAttachments()

        // Send close request (no task-is-closed check, no retry queue)
        val request = TaskCloseRequest(
            attachments = attachments,
            comment = comment,
            guid = guid
        )

        try {
            val success = client.taskClose(request)
            withContext(Dispatchers.Main) {
                loadingDialog.dismiss()
                if (success) {
                    // Удаляем задачу из списка сразу
                    allTasks = allTasks.filter { it.guid != guid }
                    updateUiAfterLoad()
                    AlertDialog.Builder(requireContext())
                        .setTitle("✅ Заявка отправлена")
                        .setMessage("Данные по заявке $ticketNumber отправлены. После проверки менеджером она будет закрыта.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Ошибка сервера")
                        .setMessage("Сервер вернул ошибку при отправке заявки $ticketNumber. Попробуйте позже.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (_binding == null) {
                    loadingDialog.dismiss()
                    return@withContext
                }
                loadingDialog.dismiss()
                showError("Ошибка", "Не удалось отправить заявку: ${e.message}")
            }
        }
    }

    // ========================== File handling ==========================

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
                    else -> "jpg" // по умолчанию для других image/*
                }
            }
            else -> {
                // fallback по имени файла
                val name = getFileName(uri) ?: "file.bin"
                name.substringAfterLast(".", "bin")
            }
        }
    }

    private fun addFileToSelection(uri: Uri) {
        val fileName = getFileName(uri) ?: "file"
        val ext = getFileExtension(uri)

        // Avoid duplicates
        if (selectedFiles.any { it.uri == uri }) return

        selectedFiles.add(FileAttachment(uri, fileName, ext))
        updateFilesList()
    }

    private fun removeFile(index: Int) {
        if (index in selectedFiles.indices) {
            selectedFiles.removeAt(index)
            updateFilesList()
        }
    }

    private fun updateFilesList() {
        val container = dialogBinding?.filesContainer ?: return
        container.removeAllViews()

        selectedFiles.forEachIndexed { index, file ->
            val row = LayoutInflater.from(requireContext())
                .inflate(android.R.layout.simple_list_item_1, container, false) as TextView
            row.text = "${index + 1}. 📄 ${file.fileName}"
            row.setTextColor(resources.getColor(com.mrc.warehouse.R.color.text_primary, null))
            row.textSize = 13f
            row.setPadding(0, 8, 0, 8)

            // Remove button as an icon
            row.setOnClickListener {
                removeFile(index)
            }
            row.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_close_clear_cancel, 0)
            row.compoundDrawablePadding = 8

            container.addView(row)
        }
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            if (nameIndex >= 0) it.getString(nameIndex) else null
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
            } catch (e: Exception) {
                null
            }
        }
    }

    // ========================== Request task documents ==========================

    /**
     * Запросить zip-архив с PDF-документами (АВР, ФН, М15) с сервера,
     * сохранить в Downloads и открыть через стандартный обработчик.
     */
    private fun requestTaskDocuments(task: TaskItem) {
        val guid = task.guid
        if (guid.isNullOrBlank()) {
            showError("Ошибка", "У заявки отсутствует идентификатор")
            return
        }

        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle("Загрузка документов")
            .setMessage("Получение документов...")
            .setCancelable(false)
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = session.createApiClient(requireContext())
                val zipBytes = client.getTaskDocuments(guid)

                if (zipBytes == null || zipBytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        loadingDialog.dismiss()
                        showError("Ошибка", "Сервер вернул пустой ответ или произошла ошибка")
                    }
                    return@launch
                }

                // Имя файла: documents-{первые 8 символов GUID}.zip
                val safeGuidPrefix = guid.take(8).replace(Regex("[^a-zA-Z0-9]"), "_")
                val fileName = "documents-$safeGuidPrefix.zip"
                val mimeType = "application/zip"

                // 1) Сохраняем во временный кэш для немедленного открытия
                val cacheDir = File(requireContext().cacheDir, "documents")
                cacheDir.mkdirs()
                val cacheFile = File(cacheDir, fileName)
                FileOutputStream(cacheFile).use { fos ->
                    fos.write(zipBytes)
                    fos.flush()
                }

                // 2) В фоне копируем в Downloads для постоянного хранения
                copyZipToDownloads(zipBytes, fileName, mimeType)

                // 3) Открываем папку Downloads в файловом менеджере
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    if (_binding == null) return@withContext
                    AlertDialog.Builder(requireContext())
                        .setTitle("✅ Документы сохранены")
                        .setMessage("Архив \"$fileName\" сохранён в папке Downloads.\n\nЧтобы открыть PDF, распакуйте архив любым архиватором (например, RAR, 7Zipper).")
                        .setPositiveButton("Открыть папку") { _, _ ->
                            try {
                                val downloadsUri: Uri
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    downloadsUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
                                } else {
                                    downloadsUri = Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                                }
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(downloadsUri, "resource/folder")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            } catch (_: Exception) {
                                // Если не удалось открыть папку — пробуем открыть сам файл
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        requireContext(),
                                        "${requireContext().packageName}.fileprovider",
                                        cacheFile
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mimeType)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    // Ничего не делаем
                                }
                            }
                        }
                        .setNegativeButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    if (_binding == null) return@withContext
                    showError("Ошибка", "Не удалось получить документы: ${e.message}")
                }
            }
        }
    }

    /**
     * Копирует zip-архив в папку Downloads в фоне.
     */
    private fun copyZipToDownloads(fileBytes: ByteArray, fileName: String, mimeType: String) {
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

    // ========================== Detail dialog (user task) ==========================

    private fun showUserTaskDetailDialog(task: TaskItem) {
        val taskGuid = task.guid
        val dialog = TaskDetailDialogFragment.newInstance(
            task = task,
            mode = TaskDetailDialogFragment.DialogMode.USER_TASK,
            onTaskClosed = {
                // После закрытия заявки удаляем из списка сразу
                if (taskGuid != null) {
                    allTasks = allTasks.filter { it.guid != taskGuid }
                    updateUiAfterLoad()
                }
            }
        )
        dialog.show(parentFragmentManager, "TaskDetailDialog")
    }

    // ========================== Description dialog ==========================

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

    // ========================== Error helper ==========================

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ========================== Ticket number extraction ==========================

    /** Извлекает номер вида ХХ-000000 (2 русские буквы, дефис, 6 цифр) из строки */
    private fun extractTicketNumber(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("[А-ЯЁа-яё]{2}-\\d{6}")
        return regex.find(text)?.value
    }

    override fun onResume() {
        super.onResume()
        // Если был установлен флаг принудительного обновления (например, после взятия задачи),
        // выполняем принудительную загрузку и сбрасываем флаг
        if (session.pendingForceTasksRefresh) {
            session.pendingForceTasksRefresh = false
            loadTasks(force = true)
        } else {
            loadTasks(force = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        closeDialog?.dismiss()
        closeDialog = null
        dialogBinding = null
        pendingLoadingDialog?.dismiss()
        pendingLoadingDialog = null
        _binding = null
    }
}