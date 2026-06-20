package com.mrc.warehouse

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.mrc.warehouse.api.OneSApiClient
import com.mrc.warehouse.databinding.ActivityLoginBinding
import com.mrc.warehouse.util.NetworkUtil
import com.mrc.warehouse.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                showError("Введите имя и пароль")
                return@setOnClickListener
            }

            if (!session.hasServerConfig) {
                showError("Не настроено подключение к серверу. Нажмите ⚙ и укажите сервер, порт и имя базы.")
                return@setOnClickListener
            }

            doLogin(username, password)
        }

        binding.btnContinue.setOnClickListener {
            val username = session.username
            val password = session.password
            if (username.isNotEmpty() && password.isNotEmpty()) {
                if (!session.hasServerConfig) {
                    showError("Не настроено подключение к серверу. Нажмите ⚙ и укажите сервер, порт и имя базы.")
                    return@setOnClickListener
                }
                if (NetworkUtil.isOnline(this)) {
                    doLogin(username, password)
                } else {
                    proceedToMain()
                }
            } else {
                showLoginForm()
            }
        }

        binding.tvSwitchUser.setOnClickListener {
            showLoginForm()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        if (session.isAuthenticated) {
            val savedUser = session.username
            if (savedUser.isNotEmpty()) {
                showSavedSession(savedUser)
            } else {
                showLoginForm()
            }
        }

        if (!session.hasServerConfig) {
            binding.tvSubtitle.post {
                showSettingsDialog()
            }
        }
    }

    private fun showSavedSession(username: String) {
        binding.tvSubtitle.text = "С возвращением!"
        binding.tvSavedUsername.text = username
        binding.layoutSavedSession.visibility = View.VISIBLE
        binding.layoutLoginForm.visibility = View.GONE
        binding.tvError.visibility = View.GONE
    }

    private fun showLoginForm() {
        binding.tvSubtitle.text = "Войдите в учётную запись"
        binding.layoutSavedSession.visibility = View.GONE
        binding.layoutLoginForm.visibility = View.VISIBLE
        binding.etUsername.setText("")
        binding.etPassword.setText("")
        hideError()
        session.clear()
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this, R.style.Theme_MrCWarehouse_Dialog)
        builder.setTitle("Настройки сервера")

        val layout = layoutInflater.inflate(R.layout.dialog_server_settings, null)
        val etHost = layout.findViewById<EditText>(R.id.etServerHost)
        val etPort = layout.findViewById<EditText>(R.id.etServerPort)
        val etDbName = layout.findViewById<EditText>(R.id.etDbName)

        val cbUseYDisk = layout.findViewById<android.widget.CheckBox>(R.id.cbUseYDisk)
        val spinnerProxyType = layout.findViewById<Spinner>(R.id.spinnerProxyType)
        val etProxyHost = layout.findViewById<EditText>(R.id.etProxyHost)
        val etProxyPort = layout.findViewById<EditText>(R.id.etProxyPort)
        val etProxyUser = layout.findViewById<EditText>(R.id.etProxyUser)
        val etProxyPassword = layout.findViewById<EditText>(R.id.etProxyPassword)

        cbUseYDisk.isChecked = session.dataChannel == 1
        etHost.setText(session.serverHost)
        etPort.setText(session.serverPort)
        etDbName.setText(session.dbName)

        val proxyTypes = arrayOf("Нет прокси", "HTTP", "SOCKS5")
        // Кастомный адаптер для прокси-спиннера (тёмный текст)
        val proxyTypeAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, proxyTypes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@LoginActivity, R.color.text_primary))
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as TextView).setTextColor(ContextCompat.getColor(this@LoginActivity, R.color.text_primary))
                return view
            }
        }
        proxyTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProxyType.adapter = proxyTypeAdapter
        spinnerProxyType.setSelection(session.proxyType.coerceIn(0, 2))

        etProxyHost.setText(session.proxyHost)
        etProxyPort.setText(session.proxyPort)
        etProxyUser.setText(session.proxyUser)
        etProxyPassword.setText(session.proxyPassword)

        fun updateProxyFieldsVisibility() {
            val visible = spinnerProxyType.selectedItemPosition > 0
            etProxyHost.visibility = if (visible) View.VISIBLE else View.GONE
            (etProxyHost.parent.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
            etProxyPort.visibility = if (visible) View.VISIBLE else View.GONE
            (etProxyPort.parent.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
            etProxyUser.visibility = if (visible) View.VISIBLE else View.GONE
            (etProxyUser.parent.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
            etProxyPassword.visibility = if (visible) View.VISIBLE else View.GONE
            (etProxyPassword.parent.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
        }

        updateProxyFieldsVisibility()
        spinnerProxyType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateProxyFieldsVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        builder.setView(layout)
        builder.setPositiveButton("Сохранить") { _, _ ->
            session.serverHost = etHost.text.toString().trim()
            session.serverPort = etPort.text.toString().trim()
            session.dbName = etDbName.text.toString().trim()

            session.dataChannel = if (cbUseYDisk.isChecked) 1 else 0
            session.proxyType = spinnerProxyType.selectedItemPosition
            session.proxyHost = etProxyHost.text.toString().trim()
            session.proxyPort = etProxyPort.text.toString().trim()
            session.proxyUser = etProxyUser.text.toString().trim()
            session.proxyPassword = etProxyPassword.text.toString().trim()

            hideError()
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

    private fun doLogin(username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        binding.btnContinue.isEnabled = false
        hideError()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OneSApiClient(
                    username, password, session.baseUrl, session.dbName,
                    proxyHost = session.proxyHost,
                    proxyPort = session.proxyPort,
                    proxyUser = session.proxyUser,
                    proxyPassword = session.proxyPassword,
                    proxyType = session.proxyType
                )
                val result = client.login()

                if (result != null) {
                    session.isAuthenticated = true
                    session.username = username
                    session.password = password
                    session.priorities = result.priorities ?: emptyList()

                    loadAdditionalData(client)

                    session.updateSyncTimestamp()

                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        binding.btnContinue.isEnabled = true
                        startActivity(MainActivity.newIntent(this@LoginActivity))
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        binding.btnContinue.isEnabled = true
                        showError("Неверное имя пользователя или пароль")
                    }
                }
            } catch (e: Exception) {
                if (session.isAuthenticated &&
                    session.username == username &&
                    session.password == password
                ) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        binding.btnContinue.isEnabled = true
                        proceedToMain()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        binding.btnContinue.isEnabled = true
                        showError("Ошибка соединения: ${e.message}")
                    }
                }
            }
        }
    }

    private fun proceedToMain() {
        startActivity(MainActivity.newIntent(this@LoginActivity))
        finish()
    }

    private suspend fun loadAdditionalData(client: OneSApiClient) {
        try {
            val clients = client.getClients()
            session.clients = clients
        } catch (_: Exception) {}

        try {
            val storages = client.getStorages()
            session.storages = storages
        } catch (_: Exception) {}

        try {
            val products = client.getProducts()
            session.productsJson = Gson().toJson(products)
        } catch (_: Exception) {}
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }
}