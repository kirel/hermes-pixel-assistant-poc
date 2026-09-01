package de.danielkirs.hermesassistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class HermesConnection(
    val host: String,
    val port: Int,
    val apiKey: String,
    val conversationId: String,
    val memorySessionKey: String
) {
    val baseUrl: String
        get() = "http://$host:$port"
}

/** Stores the server address separately from the API secret.
 * The API key is AES-GCM encrypted using a non-exportable Android Keystore key.
 */
class HermesConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): HermesConnection? {
        val host = preferences.getString(KEY_HOST, null) ?: return null
        val port = preferences.getInt(KEY_PORT, -1)
        val encryptedKey = preferences.getString(KEY_API_KEY, null) ?: return null
        if (port !in 1..65535) return null
        val apiKey = try {
            decrypt(encryptedKey)
        } catch (_: Exception) {
            preferences.edit().remove(KEY_API_KEY).apply()
            return null
        }
        val clientId = preferences.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_CLIENT_ID, it).apply()
        }
        return HermesConnection(
            host = host,
            port = port,
            apiKey = apiKey,
            conversationId = "pixel-$clientId",
            memorySessionKey = "daniel:pixel-assistant:$clientId"
        )
    }

    fun save(host: String, port: Int, apiKey: String) {
        preferences.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_API_KEY, encrypt(apiKey))
            .apply()
    }

    fun test(connection: HermesConnection, callback: (String) -> Unit) {
        Thread {
            val message = try {
                val request = (URL("${connection.baseUrl}/v1/capabilities").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7_000
                    readTimeout = 7_000
                    setRequestProperty("Authorization", "Bearer ${connection.apiKey}")
                }
                val code = request.responseCode
                request.disconnect()
                if (code in 200..299) "Verbindung erfolgreich"
                else "Server antwortet mit HTTP $code"
            } catch (_: Exception) {
                "Keine Verbindung – Adresse, Port und Tailscale prüfen"
            }
            callback(message)
        }.apply {
            name = "HermesConnectionTest"
            start()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "hermes_connection"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_API_KEY = "encrypted_api_key"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_ALIAS = "hermes_assistant_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
