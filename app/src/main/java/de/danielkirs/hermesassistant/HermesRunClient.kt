package de.danielkirs.hermesassistant

import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

interface HermesRunListener {
    fun onStarted(runId: String)
    fun onDelta(delta: String)
    fun onCompleted(output: String)
    fun onFailed(message: String)
}

/**
 * A run is owned by Hermes once POST /v1/runs returns. This client may stop
 * reading SSE when the assistant overlay is dismissed without stopping that
 * server-side work. A later app version can reattach using the stored run ID.
 */
class HermesRunClient(private val connection: HermesConnection) {
    fun start(input: String, listener: HermesRunListener) {
        Thread {
            try {
                val runId = createRun(input)
                listener.onStarted(runId)
                streamEvents(runId, listener)
            } catch (error: Exception) {
                listener.onFailed(error.message ?: "Hermes ist nicht erreichbar")
            }
        }.apply {
            name = "HermesRun"
            start()
        }
    }

    fun steer(runId: String, input: String, callback: (accepted: Boolean, message: String) -> Unit) {
        Thread {
            try {
                val request = openConnection("/v1/runs/$runId/steer", "POST").apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject().put("input", input).toString()
                OutputStreamWriter(request.outputStream, Charsets.UTF_8).use { it.write(body) }
                val code = request.responseCode
                val response = readBody(request, code)
                request.disconnect()
                if (code in 200..299 && JSONObject(response).optBoolean("accepted", false)) {
                    callback(true, "Als Hinweis ergänzt")
                } else {
                    callback(false, "Hinweis konnte nicht ergänzt werden")
                }
            } catch (_: Exception) {
                callback(false, "Hinweis konnte nicht ergänzt werden")
            }
        }.apply {
            name = "HermesSteer"
            start()
        }
    }

    private fun createRun(input: String): String {
        val body = JSONObject().apply {
            put("input", input)
            put("session_id", connection.conversationId)
        }.toString()
        val request = openConnection("/v1/runs", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        OutputStreamWriter(request.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = request.responseCode
        val response = readBody(request, code)
        request.disconnect()
        if (code !in 200..299) throw IllegalStateException("Hermes antwortet mit HTTP $code")
        return JSONObject(response).getString("run_id")
    }

    private fun streamEvents(runId: String, listener: HermesRunListener) {
        val request = openConnection("/v1/runs/$runId/events", "GET").apply {
            setRequestProperty("Accept", "text/event-stream")
            readTimeout = 0
        }
        val code = request.responseCode
        if (code !in 200..299) {
            val message = readBody(request, code)
            request.disconnect()
            throw IllegalStateException("Event-Stream: HTTP $code ${message.take(120)}")
        }

        var eventName = ""
        val data = StringBuilder()
        request.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                when {
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isBlank() -> {
                        if (data.isNotEmpty()) {
                            handleEvent(eventName, data.toString(), listener)
                            data.clear()
                        }
                        eventName = ""
                    }
                }
            }
        }
        request.disconnect()
    }

    private fun handleEvent(eventName: String, data: String, listener: HermesRunListener) {
        val event = JSONObject(data)
        when (eventName.ifBlank { event.optString("event") }) {
            "message.delta", "assistant.delta" -> event.optString("delta").takeIf { it.isNotEmpty() }?.let(listener::onDelta)
            "run.completed" -> listener.onCompleted(event.optString("output"))
            "run.failed", "error" -> listener.onFailed(event.optString("error", event.optString("message", "Hermes-Run fehlgeschlagen")))
            "run.cancelled" -> listener.onFailed("Auftrag abgebrochen")
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        return (URL("${connection.baseUrl}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${connection.apiKey}")
            setRequestProperty("X-Hermes-Session-Key", connection.memorySessionKey)
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    }
}
