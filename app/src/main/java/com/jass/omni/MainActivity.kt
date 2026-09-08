package com.jass.omni

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jass.omni.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var securePrefs: SecurePrefs
    private val prefs by lazy { getSharedPreferences("omni", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val messages = mutableListOf<Pair<String, String>>()
    private var models = mutableListOf<String>()

    companion object {
        private const val BASE_URL = "https://integrate.api.nvidia.com/v1"
        private const val DEFAULT_MODEL = "meta/llama-3.1-8b-instruct"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        securePrefs = SecurePrefs(this)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == 100) { showSettings(); true } else false
        }
        binding.toolbar.menu.add(0, 100, 0, "Settings")
            .setIcon(android.R.drawable.ic_menu_preferences).setShowAsAction(2)

        models.add(prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        setupModelSpinner()
        binding.refreshModels.setOnClickListener { refreshModels() }
        binding.sendButton.setOnClickListener { sendMessage() }
        binding.messageInput.setOnEditorActionListener { _, _, _ -> sendMessage(); true }

        if (securePrefs.getApiKey().isBlank()) {
            binding.status.text = "Add your NVIDIA API key in Settings"
        } else {
            binding.status.text = "NVIDIA NIM • Ready"
            refreshModels(silent = true)
        }
    }

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        binding.modelSpinner.adapter = adapter
    }

    private fun showSettings() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 10, 40, 0)
        }
        val keyInput = android.widget.EditText(this).apply {
            hint = "NVIDIA API key"
            inputType = 0x00000081
            setText(securePrefs.getApiKey())
        }
        val info = TextView(this).apply {
            text = "Base URL\n$BASE_URL\n\nYour key is stored using Android Keystore encryption."
            setPadding(0, 16, 0, 16)
        }
        container.addView(keyInput)
        container.addView(info)
        AlertDialog.Builder(this)
            .setTitle("NVIDIA NIM")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                securePrefs.putApiKey(keyInput.text.toString().trim())
                binding.status.text = if (securePrefs.getApiKey().isBlank()) "Add your NVIDIA API key in Settings" else "NVIDIA NIM • Ready"
                if (securePrefs.getApiKey().isNotBlank()) refreshModels()
            }
            .show()
    }

    private fun refreshModels(silent: Boolean = false) {
        val key = securePrefs.getApiKey()
        if (key.isBlank()) {
            if (!silent) toast("Add your NVIDIA API key first")
            return
        }
        binding.refreshModels.isEnabled = false
        binding.status.text = "Loading models…"
        executor.execute {
            runCatching {
                val json = request("$BASE_URL/models", key, "GET", null)
                val data = json.optJSONArray("data") ?: JSONArray()
                val found = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(found::add)
                }
                found
            }.onSuccess { found ->
                runOnUiThread {
                    if (found.isNotEmpty()) {
                        models = found.distinct().toMutableList()
                        setupModelSpinner()
                        val saved = prefs.getString("model", DEFAULT_MODEL)
                        val index = models.indexOf(saved).takeIf { it >= 0 } ?: 0
                        binding.modelSpinner.setSelection(index)
                    }
                    binding.status.text = "NVIDIA NIM • ${models.size} models"
                    binding.refreshModels.isEnabled = true
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.refreshModels.isEnabled = true
                    binding.status.text = "Model refresh failed"
                    if (!silent) toast(error.message ?: "Unable to load models")
                }
            }
        }
    }

    private fun sendMessage(): Boolean {
        val text = binding.messageInput.text.toString().trim()
        if (text.isBlank()) return true
        val key = securePrefs.getApiKey()
        if (key.isBlank()) {
            toast("Add your NVIDIA API key in Settings")
            showSettings()
            return true
        }
        val model = binding.modelSpinner.selectedItem?.toString()?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        prefs.edit().putString("model", model).apply()
        messages += "user" to text
        addBubble("You", text, true)
        binding.messageInput.setText("")
        binding.sendButton.isEnabled = false
        binding.refreshModels.isEnabled = false
        binding.status.text = "Thinking…"
        executor.execute {
            runCatching {
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        messages.takeLast(20).forEach { (role, content) ->
                            put(JSONObject().put("role", role).put("content", content))
                        }
                    })
                    put("temperature", 0.7)
                    put("max_tokens", 2048)
                    put("stream", false)
                }
                val json = request("$BASE_URL/chat/completions", key, "POST", body.toString())
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?.takeIf { it.isNotBlank() } ?: error("NIM returned an empty response")
            }.onSuccess { reply ->
                messages += "assistant" to reply
                runOnUiThread {
                    addBubble("Omni", reply, false)
                    binding.status.text = "NVIDIA NIM • Ready"
                    binding.sendButton.isEnabled = true
                    binding.refreshModels.isEnabled = true
                }
            }.onFailure { error ->
                runOnUiThread {
                    addBubble("Error", error.message ?: "Request failed", false)
                    binding.status.text = "Request failed"
                    binding.sendButton.isEnabled = true
                    binding.refreshModels.isEnabled = true
                }
            }
        }
        return true
    }

    private fun request(url: String, apiKey: String, method: String, body: String?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doInput = true
            if (method == "POST") doOutput = true
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrDefault(text)
                error("HTTP ${connection.responseCode}: ${detail.ifBlank { "NIM request failed" }}")
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun addBubble(label: String, text: String, user: Boolean) {
        val wrapper = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = if (user) Gravity.END else Gravity.START
            setPadding(8, 8, 8, 8)
        }
        val title = TextView(this).apply { this.text = label; textSize = 12f; alpha = 0.65f }
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(20, 14, 20, 14)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.primary_text_light))
            setBackgroundResource(android.R.drawable.editbox_background)
        }
        wrapper.addView(title)
        wrapper.addView(bubble)
        binding.messagesContainer.addView(wrapper)
        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
