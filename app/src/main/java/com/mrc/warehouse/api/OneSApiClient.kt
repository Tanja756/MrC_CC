package com.mrc.warehouse.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Android port of the Python OneSApiClient.
 * Uses OkHttp directly (no Retrofit) – same style as the Django client uses requests.
 *
 * All methods now check response.isSuccessful and return empty defaults
 * on failure instead of risking corrupt data from error pages.
 *
 * @param baseUrl e.g. "http://cloud.my.ru:1234"
 * @param dbName  e.g. "db_work"
 */
class OneSApiClient(
    private val username: String,
    private val password: String,
    private val baseUrl: String,
    private val dbName: String,
    private val siteBaseUrl: String = "", // базовый URL внешнего сайта
    private val proxyHost: String = "",
    private val proxyPort: String = "",
    private val proxyUser: String = "",
    private val proxyPassword: String = "",
    private val proxyType: Int = 0 // 0=none, 1=HTTP, 2=SOCKS5
) {

    companion object {
        private const val USER_AGENT = "okhttp/4.2.1"
    }

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val userPass = "$username:$password"
                val encoded = java.util.Base64.getEncoder().encodeToString(userPass.toByteArray(Charsets.UTF_8))
                val auth = "Basic $encoded"
                val request = original.newBuilder()
                    .header("Authorization", auth)
                    .header("User-Agent", USER_AGENT)
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }

        // Configure proxy if set
        if ((proxyType != 0) && (proxyHost.isNotBlank() && proxyPort.isNotBlank())) {
            val port = proxyPort.toIntOrNull() ?: 0
            if (port > 0) {
                val proxyTypeJava = when (proxyType) {
                    2 -> Proxy.Type.SOCKS
                    else -> Proxy.Type.HTTP
                }
                val proxy = Proxy(proxyTypeJava, InetSocketAddress(proxyHost, port))
                builder.proxy(proxy)

                // If proxy requires authentication
                if (proxyUser.isNotBlank()) {
                    builder.proxyAuthenticator { _, response ->
                        val credential = okhttp3.Credentials.basic(proxyUser, proxyPassword)
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            }
        }

        builder.build()
    }

    private fun apiUrl(path: String) = "$baseUrl/$dbName/hs/api/v1/$path"

    /** Проверка авторизации: GET /hs/api/v1/login */
    @Throws(Exception::class)
    fun login(): LoginResponse? {
        val url = apiUrl("login")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            return response.body?.string()?.let { gson.fromJson(it, LoginResponse::class.java) }
        }
        throw RuntimeException("Сервер вернул код ${response.code}: ${response.body?.string()}")
    }

    /** Список складов */
    fun getStorages(): List<StorageItem> {
        val url = apiUrl("storages")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: "[]"
        val type = object : TypeToken<List<StorageItem>>() {}.type
        return gson.fromJson(body, type)
    }

    /** Остатки по складу */
    fun getBalances(storageGuid: String): List<BalanceItem> {
        val url = apiUrl("balances-report?storage=$storageGuid")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: "[]"
        val type = object : TypeToken<List<BalanceItem>>() {}.type
        return gson.fromJson(body, type)
    }

    /** Справочник товаров */
    fun getProducts(): List<ProductItem> {
        val url = apiUrl("products")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: "[]"
        val type = object : TypeToken<List<ProductItem>>() {}.type
        return gson.fromJson(body, type)
    }

    /** Задачи пользователя */
    fun getTasksUser(): TasksResponse {
        val url = apiUrl("tasks-user")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return TasksResponse()
        val body = response.body?.string() ?: "{}"
        return gson.fromJson(body, TasksResponse::class.java)
    }

    /** Клиенты */
    fun getClients(): List<ClientItem> {
        val url = apiUrl("clients")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: "[]"
        val type = object : TypeToken<List<ClientItem>>() {}.type
        return gson.fromJson(body, type)
    }

    /** Свободные (нераспределённые) задачи */
    fun getTasksUnallocated(): TasksResponse {
        val url = apiUrl("tasks-unallocated")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return TasksResponse()
        val body = response.body?.string() ?: "{}"
        return gson.fromJson(body, TasksResponse::class.java)
    }

    /** Взятие свободной заявки: POST /hs/api/v1/task-take с {"guid":"..."} */
    fun taskTake(guid: String): TaskTakeResponse {
        val url = apiUrl("task-take")
        val json = gson.toJson(mapOf("guid" to guid))
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            return try {
                gson.fromJson(errorBody, TaskTakeResponse::class.java) ?: TaskTakeResponse(error = "HTTP ${response.code}")
            } catch (e: Exception) {
                TaskTakeResponse(error = "HTTP ${response.code}: $errorBody")
            }
        }
        val respBody = response.body?.string() ?: "{}"
        return gson.fromJson(respBody, TaskTakeResponse::class.java) ?: TaskTakeResponse()
    }

    /** Закрытие заявки с комментарием и/или вложениями: POST /hs/api/v1/task-close */
    fun taskClose(requestBody: TaskCloseRequest): Boolean {
        val url = apiUrl("task-close")
        val json = gson.toJson(requestBody)
        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()
        return response.isSuccessful
    }

    /** Закрытые заявки пользователя */
    fun getClosedTasksUser(): TasksResponse {
        val url = apiUrl("closed-tasks-user")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return TasksResponse()
        val body = response.body?.string() ?: "{}"
        return gson.fromJson(body, TasksResponse::class.java)
    }

    /** Зарплата за период */
    fun getSalary(startDate: String, endDate: String): SalaryResponse {
        val url = apiUrl("salary?start_date=$startDate&end_date=$endDate")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return SalaryResponse()
        val body = response.body?.string() ?: "{}"
        return gson.fromJson(body, SalaryResponse::class.java)
    }

    /** Движения по складу (приходы/списания) */
    fun getMovements(storageGuid: String, startDate: String, endDate: String): List<StorageMovement> {
        val url = apiUrl("movements?storage=$storageGuid&start_date=$startDate&end_date=$endDate")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: "[]"
        val type = object : TypeToken<List<StorageMovement>>() {}.type
        return gson.fromJson(body, type)
    }

    // ===================== PPR API methods =====================

    /** Получить список ППР-заявок за квартал: GET /hs/api/v1/ppr_list
     *  @param nameDepartment опциональный фильтр точного соответствия по подразделению
     *  Возвращает null при ошибке HTTP (сервис недоступен),
     *  пустой список при успешном ответе без заявок. */
    fun getPprList(year: Int, quarter: Int, nameDepartment: String = ""): PprListResponse? {
        var url = apiUrl("ppr_list?year=$year&quarter=$quarter")
        if (nameDepartment.isNotBlank()) {
            url += "&name_department=${java.net.URLEncoder.encode(nameDepartment, "UTF-8")}"
        }
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: "{}"
        return try {
            gson.fromJson(body, PprListResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }


    /** Закрыть ППР-заявку: POST /hs/api/v1/ppr_close */
    fun pprClose(requestBody: PprCloseRequest): Boolean {
        val url = apiUrl("ppr_close")
        val json = gson.toJson(requestBody)
        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()
        return response.isSuccessful
    }

    /** Получить список подразделений для ППР за квартал: GET /hs/api/v1/ppr_departments */
    fun getPprDepartments(year: Int, quarter: Int): PprDepartmentsResponse? {
        val url = apiUrl("ppr_departments?year=$year&quarter=$quarter")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: "{}"
        return try {
            gson.fromJson(body, PprDepartmentsResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** Получить вложения для заявки: GET /hs/api/v1/tasks-attachment?guid=... */
    fun getTaskAttachments(guid: String): AttachmentsResponse? {
        val url = apiUrl("tasks-attachment?guid=$guid")
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: "{}"
        return try {
            gson.fromJson(body, AttachmentsResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** Добавить ППР-заявку (одиночную): POST /hs/api/v1/ppr_add */
    fun pprAdd(task: Map<String, Any?>): PprAddResponse? {
        val url = apiUrl("ppr_add")
        val json = gson.toJson(task)
        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()
        val respBody = response.body?.string() ?: "{}"
        return try {
            gson.fromJson(respBody, PprAddResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // ===================== Site API =====================

    /**
     * Создаёт OkHttpClient для запросов к внешнему сайту (без Basic Auth).
     * Используется для registerCredentialsOnSite() и getTaskDocuments().
     */
    private fun buildSiteClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Применяем настройки прокси (если настроены)
        if ((proxyType != 0) && (proxyHost.isNotBlank() && proxyPort.isNotBlank())) {
            val port = proxyPort.toIntOrNull() ?: 0
            if (port > 0) {
                val proxyTypeJava = when (proxyType) {
                    2 -> Proxy.Type.SOCKS
                    else -> Proxy.Type.HTTP
                }
                val proxy = Proxy(proxyTypeJava, InetSocketAddress(proxyHost, port))
                builder.proxy(proxy)

                if (proxyUser.isNotBlank()) {
                    builder.proxyAuthenticator { _, response ->
                        val credential = okhttp3.Credentials.basic(proxyUser, proxyPassword)
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            }
        }

        return builder.build()
    }

    /**
     * Регистрирует credentials пользователя на внешнем сайте.
     * POST {siteBaseUrl}/api/register-credentials
     * Вызывается после успешной OAuth-авторизации Яндекс.Диска.
     */
    fun registerCredentialsOnSite(): Boolean {
        if (siteBaseUrl.isBlank()) return false
        val url = "$siteBaseUrl/api/register-credentials"
        val requestBody = ClX23RegisterRequest(
            username = username,
            password = password
        )
        val json = gson.toJson(requestBody)
        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)

        val tempClient = buildSiteClient()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            val response = tempClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Запросить zip-архив с PDF-документами (АВР, ФН, М15) по задаче.
     * POST {siteBaseUrl}/api/tasks/documents
     *
     * @return ByteArray с содержимым zip-архива, или null при ошибке
     */
    fun getTaskDocuments(guid: String): ByteArray? {
        if (siteBaseUrl.isBlank()) throw RuntimeException("siteBaseUrl не настроен")
        val url = "$siteBaseUrl/api/tasks/documents"
        val requestBody = DocumentsRequest(
            guid = guid,
            login = username,
            password = password,
            includeAct = true,
            includeFn = true,
            includeM15 = true
        )
        val json = gson.toJson(requestBody)
        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)

        val docClient = buildSiteClient()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val response = docClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "нет данных"
            throw RuntimeException("HTTP ${response.code}: $errorBody")
        }
        return response.body?.bytes()
    }
}
