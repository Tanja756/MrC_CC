package com.mrc.warehouse

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.mrc.warehouse.databinding.ActivityDiskBinding
import com.mrc.warehouse.util.SessionManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Properties

class DiskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiskBinding
    private lateinit var session: SessionManager
    private val prefs by lazy { getSharedPreferences("yandex_disk", Context.MODE_PRIVATE) }
    private val client = OkHttpClient()

    private val credentials: Pair<String, String> by lazy {
        loadCredentials()
    }

    private val clientId: String get() = credentials.first
    private val clientSecret: String get() = credentials.second
    private val authUrl: String by lazy {
        "https://oauth.yandex.ru/authorize?response_type=code&client_id=$clientId"
    }

    companion object {
        private const val TOKEN_URL = "https://oauth.yandex.ru/token"
        private const val REDIRECT_URI = "https://oauth.yandex.ru/verification_code"
        private const val PREF_ACCESS_TOKEN = "access_token"
        private const val YANDEX_DISK_API = "https://cloud-api.yandex.net/v1/disk/resources"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        updateTokenStatus()

        binding.btnAuthorize.setOnClickListener {
            log("Нажатие: Авторизоваться")
            startAuth()
        }

    }

    /**
     * Загружает CLIENT_ID и CLIENT_SECRET из assets/yandex_credentials.properties
     */
    private fun loadCredentials(): Pair<String, String> {
        return try {
            val props = Properties()
            val stream = assets.open("yandex_credentials.properties")
            props.load(stream)
            stream.close()
            val clientId = props.getProperty("client_id")
            val clientSecret = props.getProperty("client_secret")
            if (clientId.isNullOrEmpty() || clientSecret.isNullOrEmpty()) {
                throw IllegalStateException("client_id или client_secret не найдены в yandex_credentials.properties")
            }
            Pair(clientId, clientSecret)
        } catch (e: Exception) {
            log("Ошибка загрузки credentials: ${e.message}")
            throw RuntimeException("Не удалось загрузить yandex_credentials.properties из assets", e)
        }
    }

    private fun log(msg: String) {
    }

    private fun updateTokenStatus() {
        val token = prefs.getString(PREF_ACCESS_TOKEN, null)
        if (token != null && token.isNotEmpty()) {
            binding.tvTokenStatus.text = "Авторизован"
            binding.tvTokenStatus.setTextColor(0xFF4CAF50.toInt())
            binding.btnAuthorize.text = "Сменить аккаунт"
            log("Яндекс.Диск: авторизован (токен есть)")
        } else {
            binding.tvTokenStatus.text = "Не авторизован"
            binding.tvTokenStatus.setTextColor(0xFFFF5252.toInt())
            binding.btnAuthorize.text = "Авторизоваться"
            log("Яндекс.Диск: не авторизован")
        }
    }

    private fun startAuth() {
        log("Запуск OAuth-авторизации Яндекс.Диска")

        // Очищаем куки WebView, чтобы можно было войти в другой аккаунт Яндекса
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        binding.webViewAuth.clearCache(true)
        binding.webViewAuth.clearHistory()

        binding.webViewAuth.visibility = android.view.View.VISIBLE
        binding.webViewAuth.settings.javaScriptEnabled = true
        binding.webViewAuth.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                log("WebView загрузил: $url")
                if (url != null && url.startsWith(REDIRECT_URI)) {
                    val code = extractCode(url)
                    if (code != null) {
                        log("Получен код авторизации")
                        view?.stopLoading()
                        binding.webViewAuth.visibility = android.view.View.GONE
                        exchangeCodeForToken(code)
                    } else {
                        log("Не удалось извлечь код из URL: $url")
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                log("WebView загрузил страницу: $url")
            }
        }
        binding.webViewAuth.loadUrl(authUrl)
    }

    private fun extractCode(url: String): String? {
        return try {
            val query = url.substringAfter("?")
            query.split("&")
                .find { it.startsWith("code=") }
                ?.substringAfter("=")
        } catch (e: Exception) {
            log("Ошибка извлечения кода: ${e.message}")
            null
        }
    }

    private fun exchangeCodeForToken(code: String) {
        log("Обмен кода на токен...")
        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Ошибка сети при получении токена: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val accessToken = json.optString("access_token")
                    if (accessToken.isNotEmpty()) {
                        prefs.edit().putString(PREF_ACCESS_TOKEN, accessToken).apply()
                        log("Токен успешно получен и сохранён")
                        runOnUiThread { updateTokenStatus() }
                    } else {
                        log("Ошибка: токен не найден в ответе: $body")
                    }
                } else {
                    log("Ошибка получения токена ($response): $body")
                }
            }
        })
    }

    // ================= Яндекс.Диск API: запись файла =================

    private fun getToken(): String? = prefs.getString(PREF_ACCESS_TOKEN, null)

    private fun writeCredentialsToDisk(login: String, password: String) {
        val token = getToken()
        if (token.isNullOrEmpty()) {
            log("Ошибка: не авторизован в Яндекс.Диске")
            return
        }

        val folderPath = "/$login"
        val filePath = "$folderPath/creds.txt"
        val content = "$login:$password"

        log("Создание папки: $folderPath")
        createFolder(token, folderPath) { success ->
            if (success) {
                log("Папка создана/существует. Получение URL для загрузки...")
                getUploadUrl(token, filePath) { uploadUrl ->
                    if (uploadUrl != null) {
                        log("Загрузка файла creds.txt...")
                        uploadFile(uploadUrl, content, login)
                    } else {
                        log("Ошибка получения URL для загрузки")
                    }
                }
            } else {
                log("Ошибка создания папки на Яндекс.Диске")
            }
        }
    }

    private fun createFolder(token: String, path: String, onResult: (Boolean) -> Unit) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val request = Request.Builder()
            .url("$YANDEX_DISK_API?path=$encodedPath")
            .put(RequestBody.create(null, ""))
            .addHeader("Authorization", "OAuth $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Ошибка сети при создании папки: ${e.message}")
                onResult(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = response.body?.string() ?: ""
                response.close()
                log("Создание папки: HTTP $code")
                if (code == 201) {
                    log("Папка создана")
                    onResult(true)
                } else if (code == 409) {
                    log("Папка уже существует")
                    onResult(true)
                } else {
                    log("Ошибка создания папки ($code): $body")
                    onResult(false)
                }
            }
        })
    }

    private fun getUploadUrl(token: String, path: String, onResult: (String?) -> Unit) {
        val request = Request.Builder()
            .url("${YANDEX_DISK_API}/upload?path=${java.net.URLEncoder.encode(path, "UTF-8")}&overwrite=true")
            .get()
            .addHeader("Authorization", "OAuth $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Ошибка сети при получении URL загрузки: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val href = json.optString("href")
                    if (href.isNotEmpty()) {
                        log("URL для загрузки получен")
                        onResult(href)
                    } else {
                        log("Пустой href в ответе: $body")
                        onResult(null)
                    }
                } else {
                    log("Ошибка получения URL загрузки ($response): $body")
                    onResult(null)
                }
                response.close()
            }
        })
    }

    // ================= Яндекс.Диск API: чтение файла =================

    private fun readCredentialsFromDisk(login: String) {
        val token = getToken()
        if (token.isNullOrEmpty()) {
            log("Ошибка: не авторизован в Яндекс.Диске")
            return
        }

        val filePath = "/$login/creds.txt"
        log("Чтение файла: $filePath")

        getDownloadUrl(token, filePath) { downloadUrl ->
            if (downloadUrl != null) {
                log("Скачивание файла...")
                downloadFile(downloadUrl, login)
            } else {
                log("Ошибка получения URL для скачивания")
            }
        }
    }

    private fun getDownloadUrl(token: String, path: String, onResult: (String?) -> Unit) {
        val request = Request.Builder()
            .url("${YANDEX_DISK_API}/download?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .get()
            .addHeader("Authorization", "OAuth $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Ошибка сети при получении URL скачивания: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val href = json.optString("href")
                    if (href.isNotEmpty()) {
                        log("URL для скачивания получен")
                        onResult(href)
                    } else {
                        log("Пустой href в ответе: $body")
                        onResult(null)
                    }
                } else {
                    log("Ошибка получения URL скачивания ($response): $body")
                    onResult(null)
                }
                response.close()
            }
        })
    }

    private fun downloadFile(downloadUrl: String, login: String) {
        val request = Request.Builder()
            .url(downloadUrl)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Ошибка сети при скачивании файла: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: ""
                    log("--- Содержимое creds.txt ---")
                    for (line in content.lines()) {
                        log("  $line")
                    }
                    log("--- Конец файла ---")
                } else {
                    log("Ошибка скачивания файла: HTTP ${response.code}")
                }
                response.close()
            }
        })
    }

    private fun uploadFile(uploadUrl: String, content: String, login: String) {
        val requestBody = content.toRequestBody("text/plain".toMediaType())

        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Ошибка сети при загрузке файла: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()
                if (code in 200..299) {
                    log("Файл creds.txt успешно загружен в папку «$login»!")
                } else {
                    log("Ошибка загрузки файла: HTTP $code")
                }
            }
        })
    }
}