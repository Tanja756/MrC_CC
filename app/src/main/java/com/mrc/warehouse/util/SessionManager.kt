package com.mrc.warehouse.util

import com.mrc.warehouse.api.TaskLocation

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mrc.warehouse.api.BalanceItem
import com.mrc.warehouse.api.ClientItem
import com.mrc.warehouse.api.PriorityItem
import com.mrc.warehouse.api.StorageItem
import com.mrc.warehouse.api.TaskItem

/**
 * Manages session data using SharedPreferences.
 * Mirrors the Django session storage.
 *
 * IMPORTANT: All setters use commit() (not apply()) to ensure writes
 * are persisted synchronously before navigating to the next Activity.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mrc_session", Context.MODE_PRIVATE)
    private val gson = Gson()

    var isAuthenticated: Boolean
        get() = prefs.getBoolean(KEY_AUTH, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTH, value).commit() }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_USERNAME, value).commit() }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PASSWORD, value).commit() }

    var serverHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HOST, value).commit() }

    var serverPort: String
        get() = prefs.getString(KEY_PORT, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PORT, value).commit() }

    var dbName: String
        get() = prefs.getString(KEY_DB_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DB_NAME, value).commit() }

    // ---- Proxy settings ----
    var proxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PROXY_HOST, value).commit() }

    var proxyPort: String
        get() = prefs.getString(KEY_PROXY_PORT, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PROXY_PORT, value).commit() }

    var proxyUser: String
        get() = prefs.getString(KEY_PROXY_USER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PROXY_USER, value).commit() }

    var proxyPassword: String
        get() = prefs.getString(KEY_PROXY_PASSWORD, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PROXY_PASSWORD, value).commit() }

    /** 0 = Нет прокси, 1 = HTTP, 2 = SOCKS5 */
    var proxyType: Int
        get() = prefs.getInt(KEY_PROXY_TYPE, 0)
        set(value) { prefs.edit().putInt(KEY_PROXY_TYPE, value).commit() }

    /** Is the server configuration non-empty? */
    val hasServerConfig: Boolean
        get() = serverHost.isNotBlank() && serverPort.isNotBlank() && dbName.isNotBlank()

    val baseUrl: String
        get() = "http://${serverHost}:${serverPort}"

    var priorities: List<PriorityItem>
        get() {
            val json = prefs.getString(KEY_PRIORITIES, "[]") ?: "[]"
            val type = object : TypeToken<List<PriorityItem>>() {}.type
            return gson.fromJson(json, type)
        }
        set(value) { prefs.edit().putString(KEY_PRIORITIES, gson.toJson(value)).commit() }

    var clients: List<ClientItem>
        get() {
            val json = prefs.getString(KEY_CLIENTS, "[]") ?: "[]"
            val type = object : TypeToken<List<ClientItem>>() {}.type
            return gson.fromJson(json, type)
        }
        set(value) { prefs.edit().putString(KEY_CLIENTS, gson.toJson(value)).commit() }

    var storages: List<StorageItem>
        get() {
            val json = prefs.getString(KEY_STORAGES, "[]") ?: "[]"
            val type = object : TypeToken<List<StorageItem>>() {}.type
            return gson.fromJson(json, type)
        }
        set(value) { prefs.edit().putString(KEY_STORAGES, gson.toJson(value)).commit() }

    var productsJson: String
        get() = prefs.getString(KEY_PRODUCTS, "[]") ?: "[]"
        set(value) { prefs.edit().putString(KEY_PRODUCTS, value).commit() }

    // ---- Sort mode per fragment ----
    var sortModeTasks: String
        get() = prefs.getString(KEY_SORT_TASKS, "deadline") ?: "deadline"
        set(value) { prefs.edit().putString(KEY_SORT_TASKS, value).commit() }

    var sortModeFreeTasks: String
        get() = prefs.getString(KEY_SORT_FREE, "deadline") ?: "deadline"
        set(value) { prefs.edit().putString(KEY_SORT_FREE, value).commit() }

    var sortModeClosedTasks: String
        get() = prefs.getString(KEY_SORT_CLOSED, "deadline") ?: "deadline"
        set(value) { prefs.edit().putString(KEY_SORT_CLOSED, value).commit() }

    /** Whether background notifications for new free tasks are enabled */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).commit() }

    /** Whether deadline-based notifications (< 2 hours) are enabled */
    var notifyByDeadline: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DEADLINE, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFY_DEADLINE, value).commit() }

    /** Polling interval in minutes (1, 2, 5, 10, 15, 30) */
    var pollIntervalMinutes: Int
        get() = prefs.getInt(KEY_POLL_INTERVAL, 10)
        set(value) { prefs.edit().putInt(KEY_POLL_INTERVAL, value).commit() }

    /** Hour (0-23) when monitoring starts each day (default 7) */
    var monitoringStartHour: Int
        get() = prefs.getInt(KEY_MONITORING_START_HOUR, 7)
        set(value) { prefs.edit().putInt(KEY_MONITORING_START_HOUR, value).commit() }

    /** Hour (0-23) when monitoring ends each day (default 23) */
    var monitoringEndHour: Int
        get() = prefs.getInt(KEY_MONITORING_END_HOUR, 23)
        set(value) { prefs.edit().putInt(KEY_MONITORING_END_HOUR, value).commit() }

    /** Whether balance change monitoring is enabled */
    var balanceMonitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_BALANCE_MONITORING, false)
        set(value) { prefs.edit().putBoolean(KEY_BALANCE_MONITORING, value).commit() }

    /** GUID of the storage to monitor for balance changes */
    var monitoredStorageGuid: String
        get() = prefs.getString(KEY_MONITORED_STORAGE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MONITORED_STORAGE, value).commit() }

    /** Display name of the monitored storage */
    var monitoredStorageName: String
        get() = prefs.getString(KEY_MONITORED_STORAGE_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MONITORED_STORAGE_NAME, value).commit() }

    /** JSON snapshot of last known balances (for diff detection) */
    var lastBalancesJson: String
        get() = prefs.getString(KEY_LAST_BALANCES, "[]") ?: "[]"
        set(value) { prefs.edit().putString(KEY_LAST_BALANCES, value).commit() }

    /** Timestamp (epoch millis) of last successful poll */
    var lastPollTimestamp: Long
        get() = prefs.getLong(KEY_LAST_POLL_TS, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_POLL_TS, value).commit() }

    // ===================== Rate limiting for auto-sync =====================

    /** Минимальный интервал между автоматическими запросами (60 секунд) */
    private var lastAutoSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_AUTO_SYNC, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_AUTO_SYNC, value).commit() }

    /**
     * Проверяет, можно ли выполнить автоматический запрос к серверу.
     * Между автоматическими запросами должно пройти не менее MIN_AUTO_SYNC_INTERVAL_MS.
     */
    fun canPerformAutoSync(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastAutoSyncTimestamp) >= MIN_AUTO_SYNC_INTERVAL_MS
    }

    /** Отмечает время выполнения автоматического запроса */
    fun markAutoSyncPerformed() {
        lastAutoSyncTimestamp = System.currentTimeMillis()
    }

    /** Флаг: нужно ли принудительно обновить "Мои заявки" при следующем переключении вкладки */
    var pendingForceTasksRefresh: Boolean
        get() = prefs.getBoolean(KEY_PENDING_FORCE_TASKS_REFRESH, false)
        set(value) { prefs.edit().putBoolean(KEY_PENDING_FORCE_TASKS_REFRESH, value).commit() }

    // ===================== Pseudo-offline support =====================

    /** Timestamp (epoch millis) of last successful full data sync */
    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TS, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC_TS, value).commit() }

    /** Timestamp (epoch millis) of last successful references sync (storages, clients, products) */
    var lastReferencesSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_REFERENCES_SYNC_TS, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_REFERENCES_SYNC_TS, value).commit() }

    /** Formatted date-time string of last sync (for display) */
    var lastSyncDisplay: String
        get() = prefs.getString(KEY_LAST_SYNC_DISPLAY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_SYNC_DISPLAY, value).commit() }

    /** Cached user tasks (JSON) for offline display */
    var cachedTasksUserJson: String
        get() = prefs.getString(KEY_CACHED_TASKS_USER, "[]") ?: "[]"
        set(value) { prefs.edit().putString(KEY_CACHED_TASKS_USER, value).commit() }

    /** Cached unallocated (free) tasks (JSON) for offline display */
    var cachedTasksFreeJson: String
        get() = prefs.getString(KEY_CACHED_TASKS_FREE, "[]") ?: "[]"
        set(value) { prefs.edit().putString(KEY_CACHED_TASKS_FREE, value).commit() }

    /** Cached closed tasks (JSON) for offline display */
    var cachedTasksClosedJson: String
        get() = prefs.getString(KEY_CACHED_TASKS_CLOSED, "[]") ?: "[]"
        set(value) { prefs.edit().putString(KEY_CACHED_TASKS_CLOSED, value).commit() }

    /** Cached task locations (JSON) */
    private var cachedTaskLocationsJson: String
        get() = prefs.getString(KEY_TASK_LOCATIONS, "[]") ?: "[]"
        set(value) { prefs.edit().putString(KEY_TASK_LOCATIONS, value).commit() }

    /** Cached salary data (JSON) for offline display */
    var cachedSalaryJson: String
        get() = prefs.getString(KEY_CACHED_SALARY, "{}") ?: "{}"
        set(value) { prefs.edit().putString(KEY_CACHED_SALARY, value).commit() }

    /**
     * Per-storage cached balances.
     * Internal format: JSON map of storageGuid -> JSON array of BalanceItem.
     */
    private var cachedBalancesMapJson: String
        get() = prefs.getString(KEY_CACHED_BALANCES_MAP, "{}") ?: "{}"
        set(value) { prefs.edit().putString(KEY_CACHED_BALANCES_MAP, value).commit() }

    /** Save balances for a specific storage GUID */
    fun setCachedBalances(storageGuid: String, balances: List<BalanceItem>) {
        val map = parseBalancesMap()
        map[storageGuid] = balances
        cachedBalancesMapJson = gson.toJson(map)
    }

    /** Get cached balances for a specific storage GUID */
    fun getCachedBalances(storageGuid: String): List<BalanceItem> {
        val map = parseBalancesMap()
        return map[storageGuid] ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBalancesMap(): MutableMap<String, List<BalanceItem>> {
        val type = object : TypeToken<Map<String, List<BalanceItem>>>() {}.type
        val result: Map<String, List<BalanceItem>>? = gson.fromJson(cachedBalancesMapJson, type)
        return (result as? MutableMap<String, List<BalanceItem>>) ?: mutableMapOf()
    }

    /** Cached movements (JSON) per storage for reports */
    var cachedMovementsJson: String
        get() = prefs.getString(KEY_CACHED_MOVEMENTS, "{}") ?: "{}"
        set(value) { prefs.edit().putString(KEY_CACHED_MOVEMENTS, value).commit() }

    /** Save movements for a specific storage GUID */
    fun setCachedMovements(storageGuid: String, movements: List<com.mrc.warehouse.api.StorageMovement>) {
        val map = parseMovementsMap()
        map[storageGuid] = movements
        cachedMovementsJson = gson.toJson(map)
    }

    /** Get cached movements for a specific storage GUID */
    fun getCachedMovements(storageGuid: String): List<com.mrc.warehouse.api.StorageMovement> {
        val map = parseMovementsMap()
        return map[storageGuid] ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMovementsMap(): MutableMap<String, List<com.mrc.warehouse.api.StorageMovement>> {
        val type = object : TypeToken<Map<String, List<com.mrc.warehouse.api.StorageMovement>>>() {}.type
        val result: Map<String, List<com.mrc.warehouse.api.StorageMovement>>? = gson.fromJson(cachedMovementsJson, type)
        return (result as? MutableMap<String, List<com.mrc.warehouse.api.StorageMovement>>) ?: mutableMapOf()
    }

    /** Флаг: credentials успешно зарегистрированы на внешнем сайте */
    var isClX23Registered: Boolean
        get() = prefs.getBoolean(KEY_CL_X23_REGISTERED, false)
        set(value) { prefs.edit().putBoolean(KEY_CL_X23_REGISTERED, value).commit() }

    /** Create OneSApiClient with current proxy settings (прямой канал) */
    fun createDirectApiClient(siteBaseUrl: String = ""): com.mrc.warehouse.api.OneSApiClient {
        return com.mrc.warehouse.api.OneSApiClient(
            username, password, baseUrl, dbName,
            siteBaseUrl = siteBaseUrl,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUser = proxyUser,
            proxyPassword = proxyPassword,
            proxyType = proxyType
        )
    }

    /** Helper: parse cached tasks from JSON */
    fun getCachedTasksUser(): List<TaskItem> {
        val type = object : TypeToken<List<TaskItem>>() {}.type
        val tasks = gson.fromJson<List<TaskItem>>(cachedTasksUserJson, type)
        return updateTasksWithLocationStatus(tasks)
    }

    fun getCachedTasksFree(): List<TaskItem> {
        val type = object : TypeToken<List<TaskItem>>() {}.type
        return gson.fromJson(cachedTasksFreeJson, type)
    }

    fun getCachedTasksClosed(): List<TaskItem> {
        val type = object : TypeToken<List<TaskItem>>() {}.type
        val tasks = gson.fromJson<List<TaskItem>>(cachedTasksClosedJson, type)
        return updateTasksWithLocationStatus(tasks)
    }

    /**
     * Обновляет задачи, добавляя информацию о наличии местоположения
     */
    private fun updateTasksWithLocationStatus(tasks: List<TaskItem>): List<TaskItem> {
        val locations = getTaskLocations()
        val locationGuids = locations.map { it.taskGuid }.toSet()
        
        return tasks.map { task ->
            if (task.guid != null && locationGuids.contains(task.guid)) {
                task.hasLocation = true
            }
            task
        }
    }

    /**
     * Сохраняет местоположение заявки
     */
    fun saveTaskLocation(taskGuid: String, latitude: Double, longitude: Double) {
        val locations = getTaskLocations().toMutableList()
        // Удаляем старое местоположение, если есть
        locations.removeAll { it.taskGuid == taskGuid }
        // Добавляем новое
        locations.add(TaskLocation(taskGuid, latitude, longitude))
        cachedTaskLocationsJson = gson.toJson(locations)
    }

    /**
     * Получает все сохраненные местоположения заявок
     */
    fun getTaskLocations(): List<TaskLocation> {
        val type = object : TypeToken<List<TaskLocation>>() {}.type
        return gson.fromJson(cachedTaskLocationsJson, type)
    }

    /** Helper: update sync timestamp to now */
    fun updateSyncTimestamp() {
        val now = System.currentTimeMillis()
        lastSyncTimestamp = now
        lastSyncDisplay = formatTimestamp(now)
    }

    /** Helper: update references sync timestamp to now */
    fun updateReferencesSyncTimestamp() {
        val now = System.currentTimeMillis()
        lastReferencesSyncTimestamp = now
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale("ru"))
        return sdf.format(java.util.Date(millis))
    }

    // ===================== Data channel =====================

    /** 0 = прямой запрос к 1С (OneSApiClient), 1 = Яндекс.Диск (YDskApiClient) */
    var dataChannel: Int
        get() = prefs.getInt(KEY_DATA_CHANNEL, 0)
        set(value) { prefs.edit().putInt(KEY_DATA_CHANNEL, value).commit() }

    /**
     * Фабрика: создаёт клиент с учётом выбранного канала данных.
     * YDskApiClient умеет сам делегировать неподдерживаемые методы на OneSApiClient.
     */
    fun createApiClient(context: android.content.Context): com.mrc.warehouse.api.YDskApiClient {
        return com.mrc.warehouse.api.YDskApiClient(context, this)
    }

    // ===================== Pinned tasks =====================

    /**
     * Добавляет GUID задачи в список закреплённых.
     */
    fun addPinnedTask(guid: String) {
        val set = getPinnedTasks().toMutableSet()
        set.add(guid)
        prefs.edit().putStringSet(KEY_PINNED_TASKS, set).commit()
    }

    /**
     * Удаляет GUID задачи из списка закреплённых.
     */
    fun removePinnedTask(guid: String) {
        val set = getPinnedTasks().toMutableSet()
        set.remove(guid)
        prefs.edit().putStringSet(KEY_PINNED_TASKS, set).commit()
    }

    /**
     * Возвращает Set закреплённых GUID задач.
     */
    fun getPinnedTasks(): Set<String> {
        return prefs.getStringSet(KEY_PINNED_TASKS, emptySet()) ?: emptySet()
    }

    /**
     * Проверяет, закреплена ли задача с указанным GUID.
     */
    fun isTaskPinned(guid: String): Boolean = getPinnedTasks().contains(guid)

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        /** Минимальный интервал между автоматическими запросами (60 секунд) */
        const val MIN_SYNC_INTERVAL_MS = 60_000L

        /** Минимальный интервал между авто‑синхронизациями (60 секунд) */
        const val MIN_AUTO_SYNC_INTERVAL_MS = 60_000L

        /** Интервал для справочных данных (склады, клиенты, товары) – 20 минут */
        const val MIN_REFERENCES_SYNC_INTERVAL_MS = 1_200_000L

        private const val KEY_AUTH = "is_authenticated"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_DB_NAME = "db_name"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_USER = "proxy_user"
        private const val KEY_PROXY_PASSWORD = "proxy_password"
        private const val KEY_PROXY_TYPE = "proxy_type"
        private const val KEY_PRIORITIES = "priorities"
        private const val KEY_CLIENTS = "clients"
        private const val KEY_STORAGES = "storages"
        private const val KEY_PRODUCTS = "products"
        private const val KEY_SORT_TASKS = "sort_mode_tasks"
        private const val KEY_SORT_FREE = "sort_mode_free"
        private const val KEY_SORT_CLOSED = "sort_mode_closed"
        private const val KEY_NOTIFY_DEADLINE = "notify_by_deadline"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_POLL_INTERVAL = "poll_interval_minutes"
        private const val KEY_BALANCE_MONITORING = "balance_monitoring_enabled"
        private const val KEY_MONITORED_STORAGE = "monitored_storage_guid"
        private const val KEY_MONITORED_STORAGE_NAME = "monitored_storage_name"
        private const val KEY_LAST_BALANCES = "last_balances_json"
        private const val KEY_LAST_POLL_TS = "last_poll_timestamp"
        private const val KEY_MONITORING_START_HOUR = "monitoring_start_hour"
        private const val KEY_MONITORING_END_HOUR = "monitoring_end_hour"

        // Rate limiting
        private const val KEY_LAST_AUTO_SYNC = "last_auto_sync_timestamp"
        private const val KEY_PENDING_FORCE_TASKS_REFRESH = "pending_force_tasks_refresh"

        // Data channel
        private const val KEY_DATA_CHANNEL = "data_channel"

        // Cl.x-23.ru registration
        private const val KEY_CL_X23_REGISTERED = "cl_x23_registered"

        // Pinned tasks
        private const val KEY_PINNED_TASKS = "pinned_tasks"

        // Offline cache keys
        private const val KEY_LAST_SYNC_TS = "last_sync_timestamp"
        private const val KEY_LAST_REFERENCES_SYNC_TS = "last_references_sync_timestamp"
        private const val KEY_LAST_SYNC_DISPLAY = "last_sync_display"
        private const val KEY_CACHED_TASKS_USER = "cached_tasks_user"
        private const val KEY_CACHED_TASKS_FREE = "cached_tasks_free"
        private const val KEY_CACHED_TASKS_CLOSED = "cached_tasks_closed"
        private const val KEY_CACHED_BALANCES_MAP = "cached_balances_map"
        private const val KEY_CACHED_SALARY = "cached_salary"
        private const val KEY_CACHED_MOVEMENTS = "cached_movements"
        private const val KEY_TASK_LOCATIONS = "task_locations"
    }
}