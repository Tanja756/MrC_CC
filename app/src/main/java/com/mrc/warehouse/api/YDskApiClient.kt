package com.mrc.warehouse.api

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mrc.warehouse.util.SessionManager
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

/**
 * API-клиент, который загружает данные с Яндекс.Диска из трёх JSON-файлов,
 * выгруженных с 1С сервера.
 *
 * Файлы на диске (обновляются сервером раз в ~10 мин):
 * - /{login}/hashes.json      → хеши данных (сверяем, чтобы не грузить неизменённые файлы)
 * - /{login}/references.json  → справочники: склады, товары, клиенты
 * - /{login}/tasks.json       → задачи: user, free, closed
 * - /{login}/warehouse.json   → остатки по складам (ключ — GUID склада)
 *
 * Поддерживаемые методы (читаются из кэшированных JSON):
 * - getStorages()
 * - getProducts()
 * - getClients()
 * - getTasksUser()
 * - getTasksUnallocated()
 * - getClosedTasksUser()
 * - getBalances(storageGuid)
 *
 * Неподдерживаемые методы делегируются на OneSApiClient (прямой HTTP-запрос к 1С):
 * - login(), getSalary(), getMovements()
 * - taskTake(), taskClose(), getTaskAttachments()
 * - PPR методы, getTaskDocuments()
 */
class YDskApiClient(
    private val context: Context,
    private val session: SessionManager
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val yandexPrefs: SharedPreferences =
        context.getSharedPreferences("yandex_disk", Context.MODE_PRIVATE)

    /** Токен Яндекс.Диска OAuth */
    private val yandexToken: String?
        get() = yandexPrefs.getString("access_token", null)

    /** Минимальный интервал между загрузками файлов с диска (10 минут) */
    private val cacheTtlMs = 10 * 60 * 1000L

    // ---- Кэш in-memory ----
    private var cachedData: CachedDiskData? = null
    private var lastCacheTime: Long = 0

    // ---- OneSApiClient для fallback (создаётся лениво) ----
    private val fallbackClient: OneSApiClient by lazy { session.createDirectApiClient() }

    // ================ Внутренние структуры кэша ================

    /** Справочники (из references.json) */
    private data class CachedReferencesData(
        val storages: List<StorageItem>?,
        val products: List<ProductItem>?,
        val clients: List<ClientItem>?
    )

    /** Задачи (из tasks.json) */
    private data class CachedTasksData(
        val tasksUser: TasksResponse?,
        val tasksUnallocated: TasksResponse?,
        val tasksClosed: TasksResponse?
    )

    /** Полный кэш с диска */
    private data class CachedDiskData(
        val references: CachedReferencesData?,
        val tasks: CachedTasksData?,
        /** Остатки по складам: key = storageGuid, value = список остатков */
        val warehouse: Map<String, List<BalanceItem>>?,
        /** Хеши файлов из hashes.json для определения изменений */
        val cachedHashes: Map<String, String>? = null
    )

    /** Временная структура для парсинга tasks.json */
    private data class TasksFileStructure(
        val user: TasksResponse? = null,
        val free: TasksResponse? = null,
        val closed: TasksResponse? = null
    )

    // ================ Публичные методы (поддерживаемые) ================

    @Throws(Exception::class)
    fun login(): LoginResponse? {
        // Для логина всегда используем прямой запрос к 1С
        return fallbackClient.login()
    }

    /** Список складов */
    fun getStorages(): List<StorageItem> {
        return ensureCache()?.references?.storages ?: fallbackClient.getStorages()
    }

    /** Справочник товаров */
    fun getProducts(): List<ProductItem> {
        return ensureCache()?.references?.products ?: fallbackClient.getProducts()
    }

    /** Справочник клиентов */
    fun getClients(): List<ClientItem> {
        return ensureCache()?.references?.clients ?: fallbackClient.getClients()
    }

    /** Задачи пользователя (в работе) */
    fun getTasksUser(): TasksResponse {
        val cached = ensureCache()?.tasks?.tasksUser
        if (cached != null) return cached
        return fallbackClient.getTasksUser()
    }

    /** Свободные (нераспределённые) задачи */
    fun getTasksUnallocated(): TasksResponse {
        val cached = ensureCache()?.tasks?.tasksUnallocated
        if (cached != null) return cached
        return fallbackClient.getTasksUnallocated()
    }

    /** Закрытые заявки пользователя */
    fun getClosedTasksUser(): TasksResponse {
        val cached = ensureCache()?.tasks?.tasksClosed
        if (cached != null) return cached
        return fallbackClient.getClosedTasksUser()
    }

    /** Остатки по складу (читаются из warehouse.json) */
    fun getBalances(storageGuid: String): List<BalanceItem> {
        val warehouse = ensureCache()?.warehouse
        if (warehouse != null) {
            val balances = warehouse[storageGuid]
            if (balances != null) return balances
        }
        return fallbackClient.getBalances(storageGuid)
    }

    // ================ Fallback-методы (делегируются на OneSApiClient) ================

    /** Взятие свободной заявки (POST) */
    fun taskTake(guid: String): TaskTakeResponse {
        return fallbackClient.taskTake(guid)
    }

    /** Закрытие заявки (POST) */
    fun taskClose(requestBody: TaskCloseRequest): Boolean {
        return fallbackClient.taskClose(requestBody)
    }

    /** Зарплата за период */
    fun getSalary(startDate: String, endDate: String): SalaryResponse {
        return fallbackClient.getSalary(startDate, endDate)
    }

    /** Движения по складу */
    fun getMovements(storageGuid: String, startDate: String, endDate: String): List<StorageMovement> {
        return fallbackClient.getMovements(storageGuid, startDate, endDate)
    }

    // ===================== PPR methods (fallback) =====================

    fun getPprList(year: Int, quarter: Int, nameDepartment: String = ""): PprListResponse? {
        return fallbackClient.getPprList(year, quarter, nameDepartment)
    }

    fun pprClose(requestBody: PprCloseRequest): Boolean {
        return fallbackClient.pprClose(requestBody)
    }

    fun getPprDepartments(year: Int, quarter: Int): PprDepartmentsResponse? {
        return fallbackClient.getPprDepartments(year, quarter)
    }

    fun pprAdd(task: Map<String, Any?>): PprAddResponse? {
        return fallbackClient.pprAdd(task)
    }

    fun getTaskAttachments(guid: String): AttachmentsResponse? {
        return fallbackClient.getTaskAttachments(guid)
    }

    fun getTaskDocuments(guid: String): ByteArray? {
        return fallbackClient.getTaskDocuments(guid)
    }

    // ================ Работа с Яндекс.Диском ================

    /**
     * Принудительная загрузка свежих файлов с Яндекс.Диска.
     * Вызывать после авторизации в Яндекс.Диске или принудительно.
     */
    @Throws(Exception::class)
    fun forceRefresh() {
        lastCacheTime = 0
        ensureCache()
    }

    /**
     * Возвращает кэшированные данные, при необходимости загружая с диска.
     * Если выбран прямой канал (dataChannel == 0), сразу возвращаем null — fallback на OneSApiClient.
     *
     * Оптимизация через hashes.json:
     * 1. Загружаем hashes.json
     * 2. Сравниваем хеши с сохранёнными — если не изменились, пропускаем загрузку
     * 3. Если хеш изменился (или нет кэша) — загружаем только изменившиеся файлы
     */
    private fun ensureCache(): CachedDiskData? {
        // Если канал = 0 (прямой 1С), не используем диск
        if (session.dataChannel == 0) return null

        val login = session.username
        if (login.isBlank()) return null

        val token = yandexToken
        if (token.isNullOrEmpty()) return null

        val now = System.currentTimeMillis()

        // 1. Если кэш есть и TTL не истёк — возвращаем как есть
        if (cachedData != null && (now - lastCacheTime) < cacheTtlMs) {
            return cachedData
        }

        return try {
            // 2. Загружаем hashes.json
            val hashesContent = downloadFileFromDisk(token, login, "hashes.json")
            val newHashes: Map<String, String>? = if (hashesContent != null) {
                parseHashesData(hashesContent)
            } else null

            // 3. Сравниваем хеши с сохранёнными
            val oldHashes = cachedData?.cachedHashes
            val hashesUnchanged = newHashes != null && oldHashes != null && newHashes == oldHashes

            if (hashesUnchanged && cachedData != null) {
                // Данные не изменились — просто обновляем время кэша
                lastCacheTime = now
                return cachedData
            }

            // 4. Определяем, какие файлы нужно загрузить
            // Если кэша нет или hashes.json не загрузился — грузим всё
            val shouldLoadReferences = newHashes == null || oldHashes == null ||
                newHashes["references.json"] != oldHashes["references.json"]
            val shouldLoadTasks = newHashes == null || oldHashes == null ||
                newHashes["tasks.json"] != oldHashes["tasks.json"]
            val shouldLoadWarehouse = newHashes == null || oldHashes == null ||
                newHashes["warehouse.json"] != oldHashes["warehouse.json"]

            val references = if (shouldLoadReferences) {
                val content = downloadFileFromDisk(token, login, "references.json")
                if (content != null) parseReferencesData(content) else cachedData?.references
            } else {
                cachedData?.references
            }

            val tasks = if (shouldLoadTasks) {
                val content = downloadFileFromDisk(token, login, "tasks.json")
                if (content != null) parseTasksData(content) else cachedData?.tasks
            } else {
                cachedData?.tasks
            }

            val warehouse = if (shouldLoadWarehouse) {
                val content = downloadFileFromDisk(token, login, "warehouse.json")
                if (content != null) parseWarehouseData(content) else cachedData?.warehouse
            } else {
                cachedData?.warehouse
            }

            // 5. Сохраняем результат
            val parsed = CachedDiskData(
                references = references,
                tasks = tasks,
                warehouse = warehouse,
                cachedHashes = newHashes ?: oldHashes
            )
            cachedData = parsed
            lastCacheTime = now
            parsed
        } catch (e: Exception) {
            // При ошибке используем старый кэш, если есть
            cachedData
        }
    }

    /**
     * Скачивает содержимое файла /{login}/{fileName} с Яндекс.Диска
     */
    private fun downloadFileFromDisk(token: String, login: String, fileName: String): String? {
        return try {
            // 1. Получаем download URL
            val filePath = "/$login/$fileName"
            val downloadUrl = getDownloadUrl(token, filePath) ?: return null

            // 2. Скачиваем файл
            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()

            val downloadResponse = client.newCall(downloadRequest).execute()
            if (downloadResponse.isSuccessful) {
                downloadResponse.body?.string()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Получает URL для скачивания файла с Яндекс.Диска
     */
    private fun getDownloadUrl(token: String, path: String): String? {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val request = Request.Builder()
            .url("https://cloud-api.yandex.net/v1/disk/resources/download?path=$encodedPath")
            .get()
            .addHeader("Authorization", "OAuth $token")
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            return json.get("href")?.asString
        }
        return null
    }

    /**
     * Парсит references.json → CachedReferencesData
     * Секции: storages, products, clients
     */
    private fun parseReferencesData(jsonContent: String): CachedReferencesData? {
        return try {
            val root = gson.fromJson(jsonContent, JsonObject::class.java) ?: return null

            val storages: List<StorageItem>? = try {
                val arr = root.getAsJsonArray("storages")
                if (arr != null) {
                    gson.fromJson(arr, object : TypeToken<List<StorageItem>>() {}.type)
                } else null
            } catch (e: Exception) { null }

            val products: List<ProductItem>? = try {
                val arr = root.getAsJsonArray("products")
                if (arr != null) {
                    gson.fromJson(arr, object : TypeToken<List<ProductItem>>() {}.type)
                } else null
            } catch (e: Exception) { null }

            val clients: List<ClientItem>? = try {
                val arr = root.getAsJsonArray("clients")
                if (arr != null) {
                    gson.fromJson(arr, object : TypeToken<List<ClientItem>>() {}.type)
                } else null
            } catch (e: Exception) { null }

            CachedReferencesData(
                storages = storages,
                products = products,
                clients = clients
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Парсит hashes.json → Map<String, String>
     * Структура: {"references.json": "sha256", "tasks.json": "sha256", "warehouse.json": "sha256"}
     */
    private fun parseHashesData(jsonContent: String): Map<String, String>? {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(jsonContent, type)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Парсит tasks.json → CachedTasksData
     * Структура: { "user": TasksResponse, "free": TasksResponse, "closed": TasksResponse }
     */
    private fun parseTasksData(jsonContent: String): CachedTasksData? {
        return try {
            val root = gson.fromJson(jsonContent, TasksFileStructure::class.java) ?: return null

            CachedTasksData(
                tasksUser = root.user,
                tasksUnallocated = root.free,
                tasksClosed = root.closed
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Парсит warehouse.json → Map<String, List<BalanceItem>>
     * Ключ — GUID склада, значение — массив остатков
     */
    private fun parseWarehouseData(jsonContent: String): Map<String, List<BalanceItem>>? {
        return try {
            val type = object : TypeToken<Map<String, List<BalanceItem>>>() {}.type
            gson.fromJson(jsonContent, type)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Проверяет, есть ли токен Яндекс.Диска (авторизован ли пользователь)
     */
    fun isYandexAuthorized(): Boolean {
        return !yandexToken.isNullOrEmpty()
    }

    /**
     * Очищает кэш и принудительно перезагрузит данные при следующем запросе
     */
    fun invalidateCache() {
        cachedData = null
        lastCacheTime = 0
    }
}