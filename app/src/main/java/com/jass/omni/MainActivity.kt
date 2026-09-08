package com.jass.omni

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jass.omni.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
    @Volatile private var generating = false
    private var streamConnection: HttpURLConnection? = null
    private var activeAssistantView: TextView? = null
    private var activeAssistantText = StringBuilder()

    companion object {
        private const val BASE_URL = "https://integrate.api.nvidia.com/v1"
        private const val DEFAULT_MODEL = "meta/llama-3.1-8b-instruct"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        hideSystemBars()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        securePrefs = SecurePrefs(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == 100) { showSettings(); true } else false
        }
        binding.toolbar.menu.add(0, 100, 0, "Settings")
            .setIcon(android.R.drawable.ic_menu_preferences).setShowAsAction(2)

        models.add(prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        setupModelSpinner()
        binding.refreshModels.setOnClickListener { refreshModels() }
        binding.sendButton.setOnClickListener { if (generating) stopGeneration() else sendMessage() }
        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        binding.status.text = if (securePrefs.getApiKey().isBlank()) {
            "Add NVIDIA API key in Settings"
        } else {
            "NVIDIA NIM • Ready"
        }
        if (securePrefs.getApiKey().isNotBlank()) refreshModels(silent = true)
        binding.messageInput.requestFocus()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        binding.modelSpinner.adapter = adapter
    }

    private fun showSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 10, 40, 0)
        }
        val keyInput = EditText(this).apply {
            hint = "NVIDIA API key"
            inputType = 0x00000081
            setText(securePrefs.getApiKey())
            setSelectAllOnFocus(true)
        }
        val info = TextView(this).apply {
            text = "NVIDIA NIM\n$BASE_URL\n\nYour API key is encrypted with Android Keystore and never bundled with the app."
            setPadding(0, 16, 0, 16)
            setTextColor(Color.DKGRAY)
        }
        container.addView(keyInput)
        container.addView(info)
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                securePrefs.putApiKey(keyInput.text.toString().trim())
                binding.status.text = if (securePrefs.getApiKey().isBlank()) "Add NVIDIA API key in Settings" else "NVIDIA NIM • Ready"
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
                val json = requestJson("$BASE_URL/models", key, "GET", null)
                val data = json.optJSONArray("data") ?: JSONArray()
                val found = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(found::add)
                }
                found.distinct()
            }.onSuccess { found ->
                runOnUiThread {
                    if (found.isNotEmpty()) {
                        models = found.toMutableList()
                        setupModelSpinner()
                        val saved = prefs.getString("model", DEFAULT_MODEL)
                        binding.modelSpinner.setSelection(models.indexOf(saved).takeIf { it >= 0 } ?: 0)
                    }
                    binding.status.text = "NVIDIA NIM • ${models.size} models"
                    binding.refreshModels.isEnabled = true
                }
            }.onFailure { error ->
                runOnUiThread {
                    binding.refreshModels.isEnabled = true
                    binding.status.text = "NVIDIA NIM • Ready"
                    if (!silent) toast(error.message ?: "Unable to load models")
                }
            }
        }
    }

    private fun sendMessage(): Boolean {
        if (generating) return true
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
        addUserMessage(text)
        binding.messageInput.setText("")
        startGenerationUi()

        executor.execute {
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        messages.takeLast(20).forEach { (role, content) ->
                            put(JSONObject().put("role", role).put("content", content))
                        }
                    })
                    put("temperature", 0.7)
                    put("max_tokens", 4096)
                    put("stream", true)
                }
                streamChat(key, body.toString())
            } catch (e: Exception) {
                runOnUiThread { finishGeneration(error = e.message ?: "Request failed") }
            }
        }
        return true
    }

    private fun startGenerationUi() {
        generating = true
        activeAssistantText = StringBuilder()
        runOnUiThread {
            binding.sendButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            binding.sendButton.contentDescription = "Stop generation"
            binding.sendButton.isEnabled = true
            binding.refreshModels.isEnabled = false
            binding.status.text = "Generating…"
            addAssistantMessage("")
        }
    }

    private fun streamChat(apiKey: String, body: String) {
        val connection = (URL("$BASE_URL/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 0
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            doInput = true
            doOutput = true
        }
        streamConnection = connection
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(errorText).optString("detail") }.getOrDefault(errorText)
                throw IllegalStateException("HTTP $code: ${detail.ifBlank { "NIM request failed" }}")
            }

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (generating) {
                    val raw = reader.readLine() ?: break
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content").orEmpty()
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) {
                        activeAssistantText.append(delta)
                        val snapshot = activeAssistantText.toString()
                        runOnUiThread { updateAssistantMessage(snapshot) }
                    }
                }
            }
            if (generating) {
                messages += "assistant" to activeAssistantText.toString()
                runOnUiThread { finishGeneration() }
            }
        } finally {
            streamConnection = null
            connection.disconnect()
        }
    }

    private fun stopGeneration() {
        if (!generating) return
        generating = false
        streamConnection?.disconnect()
        if (activeAssistantText.isNotBlank()) messages += "assistant" to activeAssistantText.toString()
        finishGeneration()
    }

    private fun finishGeneration(error: String? = null) {
        generating = false
        binding.sendButton.setImageResource(android.R.drawable.ic_menu_send)
        binding.sendButton.contentDescription = "Send message"
        binding.sendButton.isEnabled = true
        binding.refreshModels.isEnabled = true
        binding.status.text = if (error == null) "NVIDIA NIM • Ready" else "Request failed"
        if (!error.isNullOrBlank()) {
            updateAssistantMessage("Sorry, something went wrong.\n\n$error")
        }
        activeAssistantView = null
        activeAssistantText = StringBuilder()
        scrollToBottom()
    }

    private fun addUserMessage(text: String) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 8)
            alpha = 0f
            translationY = 12f
        }
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.BLACK)
            background = getDrawable(com.jass.omni.R.drawable.bg_user_message)
            setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        wrapper.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        binding.messagesContainer.addView(wrapper)
        animateIn(wrapper)
        scrollToBottom()
    }

    private fun addAssistantMessage(text: String) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, 10, 0, 10)
            alpha = 0f
        }
        val answer = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.BLACK)
            setLineSpacing(0f, 1.12f)
            setTextIsSelectable(true)
            setPadding(0, 2, 0, 2)
            minHeight = 24
        }
        wrapper.addView(answer, LinearLayout.LayoutParams(-1, -2))
        binding.messagesContainer.addView(wrapper)
        activeAssistantView = answer
        animateIn(wrapper)
        scrollToBottom()
    }

    private fun updateAssistantMessage(text: String) {
        activeAssistantView?.text = text
        activeAssistantView?.animate()?.alpha(1f)?.setDuration(80)?.start()
        scrollToBottom()
    }

    private fun animateIn(view: View) {
        view.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun scrollToBottom() {
        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun requestJson(url: String, apiKey: String, method: String, body: String?): JSONObject {
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
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: ${text.ifBlank { "NIM request failed" }}")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        generating = false
        streamConnection?.disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }
}
