package com.mrc.warehouse.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mrc.warehouse.MainActivity
import com.mrc.warehouse.api.BalanceItem
import com.mrc.warehouse.api.OneSApiClient
import com.mrc.warehouse.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.util.Calendar
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FreeTasksPollingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null
    private var knownTaskGuids: MutableSet<String> = mutableSetOf()
    private var notifiedUrgentGuids: MutableSet<String> = mutableSetOf() // user task GUIDs already notified for <2h deadline
    private var notifiedUrgentFreeGuids: MutableSet<String> = mutableSetOf() // free task GUIDs already notified for <2h deadline
    private var consecutiveFailures = 0
    private var pollIntervalMs = POLL_INTERVAL_MS

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val session = SessionManager(this)

        // Check if any relevant setting is enabled — if not, stop immediately
        if (!session.isAuthenticated || (!session.notificationsEnabled && !session.notifyByDeadline)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Use configured poll interval
        pollIntervalMs = (session.pollIntervalMinutes.coerceIn(1, 30)) * 60_000L

        // Restore failure count and last known balances from saved state
        val prefs = getSharedPreferences("polling_state", Context.MODE_PRIVATE)
        consecutiveFailures = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)

        // Load known task GUIDs
        val savedTaskGuids = prefs.getStringSet(KEY_KNOWN_GUIDS, emptySet()) ?: emptySet()
        knownTaskGuids = savedTaskGuids.toMutableSet()

        // Load already-notified urgent task GUIDs
        val savedUrgentGuids = prefs.getStringSet(KEY_URGENT_NOTIFIED, emptySet()) ?: emptySet()
        notifiedUrgentGuids = savedUrgentGuids.toMutableSet()

        // Load already-notified urgent free task GUIDs
        val savedUrgentFreeGuids = prefs.getStringSet(KEY_URGENT_FREE_NOTIFIED, emptySet()) ?: emptySet()
        notifiedUrgentFreeGuids = savedUrgentFreeGuids.toMutableSet()

        // If first run, seed with current free tasks to avoid spamming on first poll
        if (knownTaskGuids.isEmpty()) {
            scope.launch { seedKnownGuids() }
        }

        pollingJob?.cancel()
        pollingJob = scope.launch {
            var wasInError = consecutiveFailures > 0
            while (true) {
                try {
                    // Check operating hours — skip work if outside allowed window
                    val s = SessionManager(this@FreeTasksPollingService)
                    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val startHour = s.monitoringStartHour
                    val endHour = s.monitoringEndHour
                    val withinHours = when {
                        startHour <= endHour -> currentHour in startHour until endHour
                        else -> currentHour >= startHour || currentHour < endHour // overnight range
                    }

                    if (!withinHours) {
                        // Outside working hours — sleep 30 minutes and re-check
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID,
                            NotificationCompat.Builder(this@FreeTasksPollingService, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_menu_edit)
                                .setContentTitle("MrCheck: CC")
                                .setContentText("Мониторинг приостановлен (${startHour}:00–${endHour}:00)")
                                .setPriority(NotificationCompat.PRIORITY_LOW)
                                .setOngoing(true)
                                .build())
                        delay(30 * 60_000L)
                        continue
                    }

                    checkForNewFreeTasksAndBalances()
                    // On success: reset failures and update timestamp
                    consecutiveFailures = 0
                    pollIntervalMs = (session.pollIntervalMinutes.coerceIn(1, 30)) * 60_000L
                    session.lastPollTimestamp = System.currentTimeMillis()

                    // Only update notification if recovering from error state
                    if (wasInError) {
                        wasInError = false
                        val notification = buildForegroundNotification(session.lastPollTimestamp)
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, notification)
                    }
                } catch (e: UnknownHostException) {
                    consecutiveFailures++
                    wasInError = true
                    handleNetworkError("DNS: ${e.message}")
                } catch (e: ConnectException) {
                    consecutiveFailures++
                    wasInError = true
                    handleNetworkError("Сервер недоступен: ${e.message}")
                } catch (e: java.net.SocketTimeoutException) {
                    consecutiveFailures++
                    wasInError = true
                    handleNetworkError("Таймаут: ${e.message}")
                } catch (e: Exception) {
                    // Check if the user logged out
                    val s = SessionManager(this@FreeTasksPollingService)
                    if (!s.isAuthenticated) {
                        stopSelf()
                        return@launch
                    }
                    consecutiveFailures++
                    wasInError = true
                    handleNetworkError("Ошибка: ${e.message}")
                }
                delay(pollIntervalMs)
            }
        }

        return START_STICKY
    }

    private fun handleNetworkError(message: String) {
        // Exponential backoff: 1min, 2min, 4min, 8min ... max 30min
        val backoffMinutes = minOf(1L shl minOf(consecutiveFailures, 5), 30L)
        pollIntervalMs = backoffMinutes * 60_000L

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("MrCheck: CC")
            .setContentText("Ошибка: $message (повтор через ${backoffMinutes}мин)")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun seedKnownGuids() {
        try {
            val session = SessionManager(this)
            if (!session.isAuthenticated || !session.hasServerConfig) return

            val client = session.createApiClient(this)
            val response = client.getTasksUnallocated()
            val tasks = response.tasks ?: emptyList()
            knownTaskGuids = tasks.mapNotNull { it.guid }.toMutableSet()
            saveKnownGuids()
        } catch (_: Exception) { /* silently retry on next poll */ }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun checkForNewFreeTasksAndBalances() {
        val session = SessionManager(this)

        // Stop if user logged out
        if (!session.isAuthenticated) {
            stopSelf()
            return
        }

        if (!session.hasServerConfig) return

        // Skip if no network
        if (!isNetworkAvailable()) {
            throw UnknownHostException("No network connection")
        }

        val client = session.createApiClient(this)

        // === 1. Check for new free tasks ===
        checkFreeTasks(client, session)

        // === 2. Check for urgent user tasks (deadline < 2 hours) ===
        if (session.notifyByDeadline) {
            checkUrgentUserTasks(client, session)
        }

        // === 3. Check for urgent free tasks (deadline < 2 hours) ===
        if (session.notifyByDeadline) {
            checkUrgentFreeTasks(client, session)
        }

        // === 4. Check for balance changes ===
        if (session.balanceMonitoringEnabled && session.monitoredStorageGuid.isNotEmpty()) {
            checkBalanceChanges(client, session)
        }
    }

    /**
     * Проверяет заявки пользователя со сроком менее 2 часов.
     * Уведомление выдаётся однократно для каждой заявки.
     */
    private fun checkUrgentUserTasks(client: com.mrc.warehouse.api.YDskApiClient, session: SessionManager) {
        try {
            val response = client.getTasksUser()
            val tasks = response.tasks ?: emptyList()

            // Сохраняем данные задач пользователя в кэш только если данные не пустые,
            // чтобы пустой ответ из-за временной ошибки не затёр существующий кэш
            if (tasks.isNotEmpty()) {
                session.cachedTasksUserJson = Gson().toJson(tasks)
                session.updateSyncTimestamp()
            }

            val now = Calendar.getInstance()

            for (task in tasks) {
                val guid = task.guid ?: continue
                val deadlineStr = task.period ?: continue

                // Парсим дату "dd.MM.yyyy HH:mm:ss"
                val deadline = parseDeadline(deadlineStr) ?: continue

                val diffMs = deadline.time - now.timeInMillis
                val diffHours = diffMs / (60 * 60 * 1000.0)

                // Если осталось менее 2 часов, срок ещё не прошёл, и ещё не уведомляли
                if (diffHours in 0.0..<2.0 && guid !in notifiedUrgentGuids) {
                    notifyUrgentTask(task)
                    notifiedUrgentGuids.add(guid)
                    saveUrgentNotified()
                }
            }
        } catch (_: Exception) {
            // Пропускаем ошибки — это не критично
        }
    }

    private fun parseDeadline(dateStr: String): Date? {
        return try {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru"))
            sdf.parse(dateStr)
        } catch (e: Exception) { null }
    }

    private fun notifyUrgentTask(task: com.mrc.warehouse.api.TaskItem) {
        val taskNumber = task.number ?: "—"
        val taskName = task.name ?: "Без названия"
        val session = SessionManager(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "tasks")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, URGENT_NOTIFICATION_ID + task.guid.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Срочная заявка!")
            .setContentText("№$taskNumber: $taskName — менее 2 часов до срока")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("№$taskNumber: $taskName\nОсталось менее 2 часов до срока выполнения!"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(URGENT_NOTIFICATION_ID + task.guid.hashCode(), notification)
    }

    private fun saveUrgentNotified() {
        val prefs = getSharedPreferences("polling_state", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_URGENT_NOTIFIED, notifiedUrgentGuids).apply()
    }

    /**
     * Проверяет свободные заявки со сроком менее 2 часов.
     * Уведомление выдаётся однократно для каждой заявки.
     */
    private fun checkUrgentFreeTasks(client: com.mrc.warehouse.api.YDskApiClient, session: SessionManager) {
        try {
            val response = client.getTasksUnallocated()
            val tasks = response.tasks ?: emptyList()

            val now = Calendar.getInstance()

            for (task in tasks) {
                val guid = task.guid ?: continue
                val deadlineStr = task.period ?: continue

                val deadline = parseDeadline(deadlineStr) ?: continue

                val diffMs = deadline.time - now.timeInMillis
                val diffHours = diffMs / (60 * 60 * 1000.0)

                // Если осталось менее 2 часов, срок ещё не прошёл, и ещё не уведомляли
                if (diffHours in 0.0..<2.0 && guid !in notifiedUrgentFreeGuids) {
                    notifyUrgentFreeTask(task)
                    notifiedUrgentFreeGuids.add(guid)
                    saveUrgentFreeNotified()
                }
            }
        } catch (_: Exception) {
            // Пропускаем ошибки — это не критично
        }
    }

    private fun saveUrgentFreeNotified() {
        val prefs = getSharedPreferences("polling_state", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_URGENT_FREE_NOTIFIED, notifiedUrgentFreeGuids).apply()
    }

    private fun notifyUrgentFreeTask(task: com.mrc.warehouse.api.TaskItem) {
        val taskNumber = task.number ?: "—"
        val taskName = task.name ?: "Без названия"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "free_tasks")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, URGENT_FREE_NOTIFICATION_ID + task.guid.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Срочная свободная заявка!")
            .setContentText("№$taskNumber: $taskName — менее 2 часов до срока")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("№$taskNumber: $taskName\nОсталось менее 2 часов до срока выполнения!"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(URGENT_FREE_NOTIFICATION_ID + task.guid.hashCode(), notification)
    }

    private fun checkFreeTasks(client: com.mrc.warehouse.api.YDskApiClient, session: SessionManager) {
        val response = client.getTasksUnallocated()
        val currentTasks = response.tasks ?: emptyList()
        val currentGuids = currentTasks.mapNotNull { it.guid }.toSet()

        // Сохраняем данные свободных заявок в кэш только если ответ не пустой,
        // чтобы пустой ответ из-за временной ошибки не затёр существующий кэш
        if (currentTasks.isNotEmpty()) {
            session.cachedTasksFreeJson = Gson().toJson(currentTasks)
            session.updateSyncTimestamp()
        }

        // Находим новые GUID
        val newGuids = currentGuids - knownTaskGuids
        if (newGuids.isNotEmpty()) {
            val newTasks = currentTasks.filter { it.guid in newGuids }
            for (task in newTasks) {
                notifyNewTask(task)
            }
            knownTaskGuids.addAll(newGuids)
            saveKnownGuids()
        }

        // Удаляем GUID, которых больше нет (задачи взяты другими)
        knownTaskGuids.retainAll(currentGuids)
        saveKnownGuids()
    }

    private fun checkBalanceChanges(client: com.mrc.warehouse.api.YDskApiClient, session: SessionManager) {
        val gson = Gson()
        val currentBalances = client.getBalances(session.monitoredStorageGuid)
        val currentBalanceMap = currentBalances
            .filter { it.productName != null }
            .associate { Pair(it.productName!!, it.balance ?: 0) }

        // Загружаем предыдущие остатки из сохранённого состояния
        val prevJson = session.lastBalancesJson
        val type = object : TypeToken<List<BalanceItem>>() {}.type
        val prevBalances: List<BalanceItem> = try {
            gson.fromJson(prevJson, type)
        } catch (e: Exception) { emptyList() }
        val prevBalanceMap = prevBalances
            .filter { it.productName != null }
            .associate { Pair(it.productName!!, it.balance ?: 0) }

        // Находим уменьшения (списано) и увеличения (добавлено)
        val decreasedItems = mutableListOf<Pair<String, Int>>() // productName -> разница
        val increasedItems = mutableListOf<Pair<String, Int>>()

        for ((productName, currentQty) in currentBalanceMap) {
            val prevQty = prevBalanceMap[productName] ?: 0
            val diff = currentQty - prevQty
            when {
                diff < 0 -> decreasedItems.add(productName to -diff)
                diff > 0 -> increasedItems.add(productName to diff)
            }
        }

        // Проверяем товары, которые исчезли из списка
        for ((productName, prevQty) in prevBalanceMap) {
            if (!currentBalanceMap.containsKey(productName) && prevQty > 0) {
                decreasedItems.add(productName to prevQty)
            }
        }

        // Сохраняем текущие остатки для следующего сравнения
        // ДЕЛАЕМ ЭТО ДО УВЕДОМЛЕНИЙ, чтобы не потерять данные при ошибке в уведомлении
        if (currentBalances.isNotEmpty()) {
            session.lastBalancesJson = gson.toJson(currentBalances)
        }

        val storageName = session.monitoredStorageName.ifBlank { session.monitoredStorageGuid }

        // Отправляем уведомления о списании
        if (decreasedItems.isNotEmpty()) {
            val title = "Списание со склада «$storageName»"

            if (decreasedItems.size == 1) {
                val (name, qty) = decreasedItems[0]
                val msg = "Списано: $name — $qty шт"
                notifyBalanceChange(title, msg, null, NOTIFICATION_ID_DECREASE)
            } else {
                val lines = decreasedItems.joinToString("\n") { (name, qty) ->
                    "• $name — $qty шт"
                }
                notifyBalanceChange(title, "Списано ${decreasedItems.size} позиций", lines, NOTIFICATION_ID_DECREASE)
            }
        }

        // Отправляем уведомления об увеличении (все увеличения, не только новые товары)
        if (increasedItems.isNotEmpty()) {
            val title = "Поступление на склад «$storageName»"

            if (increasedItems.size == 1) {
                val (name, qty) = increasedItems[0]
                val msg = "Поступило: $name +$qty шт"
                notifyBalanceChange(title, msg, null, NOTIFICATION_ID_INCREASE)
            } else {
                val lines = increasedItems.joinToString("\n") { (name, qty) ->
                    "• $name +$qty шт"
                }
                notifyBalanceChange(title, "Поступило ${increasedItems.size} позиций", lines, NOTIFICATION_ID_INCREASE)
            }
        }
    }

    private fun notifyBalanceChange(title: String, summary: String, bigText: String?, notificationId: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "warehouse")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, builder.build())
    }

    private fun notifyNewTask(task: com.mrc.warehouse.api.TaskItem) {
        val taskName = task.name ?: "Без названия"
        val taskNumber = task.number ?: "—"
        val session = SessionManager(this)
        val clientName = session.clients
            .filter { it.guid != null && it.name != null }
            .associate { it.guid!! to it.name!! }
            .let { map -> map[task.guidClient] ?: task.guidClient ?: "" }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "free_tasks")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, task.guid.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Новая свободная заявка")
            .setContentText("№$taskNumber: $taskName · $clientName")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("№$taskNumber: $taskName\nКлиент: $clientName"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(task.guid.hashCode(), notification)
    }

    /**
     * Build the foreground notification showing last update time.
     * @param lastPollTimestamp epoch millis, or null for initial "запуск..."
     */
    private fun buildForegroundNotification(lastPollTimestamp: Long?): android.app.Notification {
        val timeStr = if (lastPollTimestamp != null && lastPollTimestamp > 0L) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            sdf.format(Date(lastPollTimestamp))
        } else {
            null
        }
        val contentText = if (timeStr != null) {
            "Проверка новых заявок... [$timeStr]"
        } else {
            "Проверка новых заявок..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("MrCheck: CC")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Новые заявки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о появлении новых свободных заявок и изменениях на складе"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun saveKnownGuids() {
        val prefs = getSharedPreferences("polling_state", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_KNOWN_GUIDS, knownTaskGuids)
            .putInt(KEY_CONSECUTIVE_FAILURES, consecutiveFailures)
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Save state before dying
        val prefs = getSharedPreferences("polling_state", Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CONSECUTIVE_FAILURES, consecutiveFailures).apply()

        pollingJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "free_tasks_polling"
        private const val NOTIFICATION_ID = 1001
        private const val BALANCE_NOTIFICATION_ID = 1002
        private const val NOTIFICATION_ID_DECREASE = 1002
        private const val NOTIFICATION_ID_INCREASE = 1003
        private const val POLL_INTERVAL_MS = 60_000L // 1 minute default
        private const val ACTION_STOP = "com.mrc.warehouse.STOP_POLLING"
        private const val KEY_KNOWN_GUIDS = "known_free_task_guids"
        private const val KEY_URGENT_NOTIFIED = "urgent_notified_guids"
        private const val KEY_URGENT_FREE_NOTIFIED = "urgent_free_notified_guids"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
        private const val URGENT_NOTIFICATION_ID = 2000
        private const val URGENT_FREE_NOTIFICATION_ID = 3000

        fun start(context: Context) {
            val intent = Intent(context, FreeTasksPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FreeTasksPollingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}