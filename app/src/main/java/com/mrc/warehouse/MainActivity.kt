package com.mrc.warehouse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import com.mrc.warehouse.databinding.ActivityMainBinding
import com.mrc.warehouse.ui.TasksSheetFragment
import com.mrc.warehouse.service.FreeTasksPollingService
import com.mrc.warehouse.util.SessionManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: SessionManager

    // Ланчер разрешения на уведомления
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted) {
            FreeTasksPollingService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        // Update toolbar sync status
        updateSyncStatus()

        // Получаем NavController через NavHostFragment
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val navController = navHostFragment.navController

        // Set up menu button (hamburger icon) — shows PopupMenu on click
        binding.btnMenu.setOnClickListener { v ->
            showPopoverMenu(v)
        }

        // === Обработка кнопки "Назад" через OnBackPressedDispatcher ===
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentDestination = navController.currentDestination
                if (currentDestination?.id != R.id.navigation_tasks) {
                    navController.popBackStack(R.id.navigation_tasks, false)
                } else {
                    val dialog = AlertDialog.Builder(this@MainActivity, R.style.Theme_MrCWarehouse_Dialog)
                        .setTitle("Выход")
                        .setMessage("Вы действительно хотите выйти из приложения?")
                        .setPositiveButton("Да") { _, _ ->
                            finishAffinity()
                        }
                        .setNegativeButton("Нет", null)
                        .show()
                    dialog.setOnShowListener {
                        val titleView = dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)
                            ?: dialog.findViewById<TextView>(android.R.id.title)
                        titleView?.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    }
                }
            }
        })

        // Start or stop background polling based on user preference
        updatePollingService()
    }

    private fun showPopoverMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, R.id.navigation_tasks, 0, "Заявки")
            menu.add(0, R.id.navigation_warehouse, 0, "Склад")
            menu.add(0, R.id.navigation_salary, 0, "Зарплата")
            menu.add(0, R.id.navigation_reports, 0, "Отчеты")
            menu.add(0, R.id.navigation_settings, 0, "Настройки")

            setOnMenuItemClickListener { item ->
                handleMenuItemClick(item)
                true
            }

            show()
        }
    }

    private fun handleMenuItemClick(item: android.view.MenuItem) {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val navController = navHostFragment.navController

        when (item.itemId) {
            R.id.navigation_tasks -> {
                val sheet = TasksSheetFragment()
                sheet.show(supportFragmentManager, "TasksSheet")
            }
            R.id.navigation_warehouse -> {
                navController.navigate(R.id.navigation_warehouse)
            }
            R.id.navigation_salary -> {
                navController.navigate(R.id.navigation_salary)
            }
            R.id.navigation_reports -> {
                navController.navigate(R.id.navigation_reports)
            }
            R.id.navigation_settings -> {
                showAppSettingsDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePollingService()
        updateSyncStatus()
    }

    private fun updateSyncStatus() {
        val syncDisplay = session.lastSyncDisplay
        if (syncDisplay.isNotEmpty()) {
            binding.tvSyncStatus.text = "данные от $syncDisplay"
            binding.tvSyncStatus.visibility = View.VISIBLE
        } else {
            binding.tvSyncStatus.visibility = View.GONE
        }
    }

    private fun updatePollingService() {
        if (session.isAuthenticated && session.notificationsEnabled) {
            requestNotificationPermissionAndStartService()
        } else {
            FreeTasksPollingService.stop(this)
        }
    }

    private fun requestNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        FreeTasksPollingService.start(this)
    }

    private fun showAppSettingsDialog() {
        if (!session.isAuthenticated) {
            AlertDialog.Builder(this)
                .setTitle("Необходима авторизация")
                .setMessage("Настройки мониторинга доступны только после входа в систему.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val builder = AlertDialog.Builder(this, R.style.Theme_MrCWarehouse_Dialog)
        builder.setTitle("Настройки приложения")

        val layout = layoutInflater.inflate(R.layout.dialog_app_settings, null)
        val spinnerPollInterval = layout.findViewById<android.widget.Spinner>(R.id.spinnerPollInterval)
        val spinnerStartHour = layout.findViewById<android.widget.Spinner>(R.id.spinnerStartHour)
        val spinnerEndHour = layout.findViewById<android.widget.Spinner>(R.id.spinnerEndHour)
        val cbNotifications = layout.findViewById<android.widget.CheckBox>(R.id.cbNotifications)
        val cbBalanceMonitoring = layout.findViewById<android.widget.CheckBox>(R.id.cbBalanceMonitoring)
        val tvStorageLabel = layout.findViewById<android.widget.TextView>(R.id.tvStorageLabel)
        val spinnerStorage = layout.findViewById<android.widget.Spinner>(R.id.spinnerStorage)

        // ---- Poll interval spinner ----
        val intervalValues = intArrayOf(1, 2, 5, 10, 15, 30)
        val intervalLabels = intervalValues.map { "$it мин" }.toTypedArray()
        val intervalAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, intervalLabels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                return view
            }
        }
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPollInterval.adapter = intervalAdapter
        val currentInterval = session.pollIntervalMinutes
        val intervalIdx = intervalValues.indexOfFirst { it >= currentInterval }.coerceAtLeast(0)
        spinnerPollInterval.setSelection(intervalIdx)

        // ---- Operating hours spinners (0..23) ----
        val hourLabels = Array(24) { i -> String.format("%02d", i) }
        fun createHourAdapter() = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, hourLabels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                return view
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerStartHour.adapter = createHourAdapter()
        spinnerStartHour.setSelection(session.monitoringStartHour.coerceIn(0, 23))
        spinnerEndHour.adapter = createHourAdapter()
        spinnerEndHour.setSelection(session.monitoringEndHour.coerceIn(0, 23))

        // ---- Notifications checkbox ----
        cbNotifications.isChecked = session.notificationsEnabled

        // ---- Balance monitoring checkbox ----
        cbBalanceMonitoring.isChecked = session.balanceMonitoringEnabled
        updateBalanceMonitoringViews(cbBalanceMonitoring, tvStorageLabel, spinnerStorage)

        cbBalanceMonitoring.setOnCheckedChangeListener { _, isChecked ->
            updateBalanceMonitoringViews(cbBalanceMonitoring, tvStorageLabel, spinnerStorage)
        }

        // Storage spinner for balance monitoring
        val storages = session.storages
        val storageNames = mutableListOf("-- Не выбран --")
        storageNames.addAll(storages.map { it.name ?: "?" })
        val storageAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, storageNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                return view
            }
        }
        storageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStorage.adapter = storageAdapter

        val savedGuid = session.monitoredStorageGuid
        if (savedGuid.isNotEmpty()) {
            val savedIdx = storages.indexOfFirst { it.guid == savedGuid }
            if (savedIdx >= 0) {
                spinnerStorage.setSelection(savedIdx + 1)
            }
        }

        builder.setView(layout)
        builder.setPositiveButton("Сохранить") { _, _ ->
            session.notificationsEnabled = cbNotifications.isChecked
            session.pollIntervalMinutes = intervalValues[spinnerPollInterval.selectedItemPosition]
            session.monitoringStartHour = spinnerStartHour.selectedItemPosition
            session.monitoringEndHour = spinnerEndHour.selectedItemPosition
            session.balanceMonitoringEnabled = cbBalanceMonitoring.isChecked

            if (cbBalanceMonitoring.isChecked && spinnerStorage.selectedItemPosition > 0) {
                val storageIdx = spinnerStorage.selectedItemPosition - 1
                val selected = storages[storageIdx]
                session.monitoredStorageGuid = selected.guid ?: ""
                session.monitoredStorageName = selected.name ?: ""
            } else {
                session.monitoredStorageGuid = ""
                session.monitoredStorageName = ""
                session.lastBalancesJson = "[]"
            }

            updatePollingService()
        }
        builder.setNegativeButton("Отмена", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            val titleView = dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)
                ?: dialog.findViewById<TextView>(android.R.id.title)
            titleView?.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
        dialog.show()
        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun updateBalanceMonitoringViews(
        cb: android.widget.CheckBox,
        tvLabel: android.widget.TextView,
        spinner: android.widget.Spinner
    ) {
        val visible = cb.isChecked
        tvLabel.visibility = if (visible) View.VISIBLE else View.GONE
        spinner.visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
    }
}