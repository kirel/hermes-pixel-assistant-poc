package de.danielkirs.hermesassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * One voice turn: Android/GMS speech recognition -> Hermes Runs API -> Android TTS.
 * Hiding this overlay stops listening but deliberately never stops a run that has
 * already been accepted by Hermes; the server remains the execution owner.
 */
class HermesVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val connectionStore = HermesConnectionStore(context)

    private lateinit var status: TextView
    private lateinit var response: TextView
    private lateinit var waveform: WaveformView
    private lateinit var textToSpeech: TextToSpeech

    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsInitialized = false
    private var submitted = false
    private var uiDismissed = false
    private var pendingSpeech: String? = null
    private var lastPartialText = ""
    private var dragStartY = 0f

    init {
        textToSpeech = TextToSpeech(context) { result ->
            ttsInitialized = result == TextToSpeech.SUCCESS
            if (ttsInitialized) {
                textToSpeech.language = Locale.GERMAN
                pendingSpeech?.let(::speakFinal)
                pendingSpeech = null
            }
        }
    }

    override fun onCreateContentView(): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    dismissOverlay()
                    true
                } else true
            }

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(16), dp(28), dp(22))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(android.graphics.Color.rgb(29, 29, 34))
                }
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dragStartY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (event.rawY - dragStartY > dp(72)) dismissOverlay()
                            true
                        }
                        else -> true
                    }
                }

                status = TextView(context).apply {
                    text = "Ich höre zu …"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER
                }
                addView(status, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

                waveform = WaveformView(context).apply { contentDescription = "Audiopegel" }
                addView(waveform, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
                ).apply { topMargin = dp(8) })

                response = TextView(context).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    maxLines = 5
                }
                addView(response, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }

            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(20)
            })
        }
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        uiDismissed = false
        submitted = false
        lastPartialText = ""
        pendingSpeech = null
        response.text = ""
        waveform.setLevel(0f)
        textToSpeech.stop()
        startRecognition()
    }

    private fun startRecognition() {
        val connection = connectionStore.load()
        when {
            connection == null -> {
                status.text = "Hermes-Verbindung bitte in der App einrichten"
                return
            }
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                status.text = "Mikrofonzugriff in der App erlauben"
                return
            }
            !SpeechRecognizer.isRecognitionAvailable(context) -> {
                status.text = "Android-Spracherkennung ist nicht verfügbar"
                return
            }
        }

        val activeConnection = connection ?: return
        status.text = "Ich höre zu …"
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!uiDismissed && !submitted) status.text = "Verarbeite Sprache …"
                }
                override fun onRmsChanged(rmsdB: Float) {
                    if (!uiDismissed) waveform.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) lastPartialText = text
                    if (!uiDismissed && !text.isNullOrBlank()) response.text = text
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.ifBlank { null } ?: lastPartialText.ifBlank { null }
                    if (text == null) {
                        if (!uiDismissed) status.text = "Ich habe nichts verstanden"
                    } else submitToHermes(text, activeConnection)
                }
                override fun onError(error: Int) {
                    if (submitted || uiDismissed) return
                    if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastPartialText.isNotBlank()) {
                        submitToHermes(lastPartialText, activeConnection)
                    } else {
                        status.text = recognitionErrorText(error)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(RecognizerIntent.ACTION_RECOGNIZE_SPEECH.let {
                android.content.Intent(it).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMAN.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200)
                }
            })
        }
    }

    private fun submitToHermes(text: String, connection: HermesConnection) {
        if (submitted) return
        submitted = true
        waveform.setLevel(0f)
        status.text = "Hermes denkt nach …"
        // The transcript is input, not the assistant answer. Clear it before streaming.
        response.text = ""
        HermesRunClient(connection).start(text, object : HermesRunListener {
            private val streamed = StringBuilder()

            override fun onStarted(runId: String) = Unit

            override fun onDelta(delta: String) {
                streamed.append(delta)
                postToUi {
                    status.text = "Hermes antwortet …"
                    response.text = streamed.toString()
                }
            }

            override fun onCompleted(output: String) {
                val finalOutput = output.ifBlank { streamed.toString() }
                postToUi {
                    status.text = "Fertig"
                    response.text = finalOutput
                    if (finalOutput.isNotBlank()) speakFinal(finalOutput)
                }
            }

            override fun onFailed(message: String) {
                postToUi {
                    status.text = message
                    waveform.setLevel(0f)
                }
            }
        })
    }

    private fun postToUi(block: () -> Unit) {
        context.mainExecutor.execute {
            if (!uiDismissed) block()
        }
    }

    private fun speakFinal(text: String) {
        if (!ttsInitialized) {
            pendingSpeech = text
            return
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-final-response")
    }

    private fun dismissOverlay() {
        uiDismissed = true
        speechRecognizer?.cancel()
        waveform.setLevel(0f)
        hide()
    }

    override fun onHide() {
        uiDismissed = true
        speechRecognizer?.cancel()
        super.onHide()
    }

    override fun onDestroy() {
        // Never call the Hermes stop endpoint here: an accepted remote run must survive UI teardown.
        speechRecognizer?.destroy()
        textToSpeech.stop()
        textToSpeech.shutdown()
        super.onDestroy()
    }

    private fun recognitionErrorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ich habe nichts verstanden"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Android-Spracherkennung ist nicht erreichbar"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofonzugriff fehlt"
        else -> "Spracherkennung fehlgeschlagen"
    }
}

private class WaveformView(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(165, 148, 255)
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var level = 0f

    fun setLevel(newLevel: Float) {
        level = newLevel.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val bars = 21
        val gap = width / (bars * 2f)
        val centerY = height / 2f
        val baseHeight = height * 0.14f
        for (index in 0 until bars) {
            val distance = kotlin.math.abs(index - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val envelope = 1f - distance * 0.55f
            val barHeight = baseHeight + level * height * 0.78f * envelope
            paint.strokeWidth = gap * 0.72f
            val x = gap + index * gap * 2f
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }
    }
}
