package de.danielkirs.hermesassistant

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var connectionStore: HermesConnectionStore
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var connectionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionStore = HermesConnectionStore(this)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
        render()
    }

    private fun render() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun layoutParams(topMargin: Int = 0) = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dp(topMargin) }

        val savedConnection = connectionStore.load()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "Hermes Assistant POC"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = "Verbindung zu deinem Hermes API-Server über Tailscale"
            textSize = 16f
        }, layoutParams(6))

        content.addView(TextView(this).apply {
            text = "Tailscale-Adresse"
            textSize = 14f
        }, layoutParams(28))
        hostInput = EditText(this).apply {
            hint = "100.x.x.x, Hostname oder Adresse:Port"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(savedConnection?.host.orEmpty())
        }
        content.addView(hostInput, layoutParams(4))

        content.addView(TextView(this).apply {
            text = "Port"
            textSize = 14f
        }, layoutParams(16))
        portInput = EditText(this).apply {
            hint = "8642"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(savedConnection?.port?.toString() ?: "8642")
        }
        content.addView(portInput, layoutParams(4))

        content.addView(TextView(this).apply {
            text = "Hermes API-Key"
            textSize = 14f
        }, layoutParams(16))
        apiKeyInput = EditText(this).apply {
            hint = if (savedConnection == null) "Aus 1Password einfügen" else "Gespeichert – nur zum Ersetzen eingeben"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setAutofillHints("password")
        }
        content.addView(apiKeyInput, layoutParams(4))
        content.addView(TextView(this).apply {
            text = "Der API-Key wird mit dem Android Keystore verschlüsselt gespeichert und nicht angezeigt oder protokolliert. Die Adresse wird als HTTP-Verbindung über dein privates Tailnet verwendet."
            textSize = 12f
        }, layoutParams(6))

        connectionStatus = TextView(this).apply {
            text = if (savedConnection == null) "Noch nicht verbunden" else "Konfiguration gespeichert"
            textSize = 14f
        }
        content.addView(connectionStatus, layoutParams(16))

        content.addView(Button(this).apply {
            text = "Speichern"
            setOnClickListener { saveConnection(testAfterSave = false) }
        }, layoutParams(4))
        content.addView(Button(this).apply {
            text = "Speichern und Verbindung testen"
            setOnClickListener { saveConnection(testAfterSave = true) }
        }, layoutParams(8))

        content.addView(TextView(this).apply {
            text = "Assistant"
            textSize = 18f
        }, layoutParams(32))
        content.addView(Button(this).apply {
            text = "Als Standard-Assistent auswählen"
            setOnClickListener { requestAssistantRole() }
        }, layoutParams(8))

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun saveConnection(testAfterSave: Boolean) {
        val endpoint = hostInput.text.toString().trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        val parsedEndpoint = parseEndpoint(endpoint)
        val host = parsedEndpoint.first
        val port = parsedEndpoint.second ?: portInput.text.toString().toIntOrNull()
        val existing = connectionStore.load()
        val apiKey = apiKeyInput.text.toString().trim().ifBlank { existing?.apiKey.orEmpty() }

        when {
            host.isBlank() -> connectionStatus.text = "Bitte Tailscale-Adresse eingeben"
            port == null || port !in 1..65535 -> connectionStatus.text = "Bitte einen gültigen Port eingeben"
            apiKey.isBlank() -> connectionStatus.text = "Bitte API-Key aus 1Password einfügen"
            else -> {
                connectionStore.save(host, port, apiKey)
                apiKeyInput.text?.clear()
                connectionStatus.text = "Konfiguration gespeichert"
                if (testAfterSave) {
                    connectionStatus.text = "Verbindung wird getestet …"
                    connectionStore.load()?.let { storedConnection ->
                        connectionStore.test(storedConnection) { message ->
                            runOnUiThread { connectionStatus.text = message }
                        }
                    }
                }
            }
        }
    }

    private fun parseEndpoint(value: String): Pair<String, Int?> {
        if (value.startsWith("[")) {
            val closingBracket = value.indexOf(']')
            if (closingBracket > 0) {
                val host = value.substring(0, closingBracket + 1)
                val port = value.substring(closingBracket + 1).removePrefix(":").toIntOrNull()
                return host to port
            }
        }
        val separator = value.lastIndexOf(':')
        if (separator > 0 && value.indexOf(':') == separator) {
            val embeddedPort = value.substring(separator + 1).toIntOrNull()
            if (embeddedPort != null) return value.substring(0, separator) to embeddedPort
        }
        return value to null
    }

    private fun requestAssistantRole() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), REQUEST_ASSISTANT_ROLE)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 1
        const val REQUEST_ASSISTANT_ROLE = 2
    }
}
