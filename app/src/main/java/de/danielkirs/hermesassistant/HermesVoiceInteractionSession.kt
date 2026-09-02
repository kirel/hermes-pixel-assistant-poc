package de.danielkirs.hermesassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * A Material-inspired voice and text conversation surface. A sent Hermes run is
 * server-owned: dismissing this sheet only hides UI and stops listening, never
 * cancels remote work that was already accepted by Hermes.
 */
class HermesVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val connectionStore = HermesConnectionStore(context)
    private val density = context.resources.displayMetrics.density

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var waveform: WaveformView
    private lateinit var messages: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var composer: EditText
    private lateinit var textToSpeech: TextToSpeech

    private var activeAgentText: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsInitialized = false
    private var submitted = false
    private var uiDismissed = false
    private var pendingSpeech: String? = null
    private var lastPartialText = ""
    private var dragStartY = 0f

    private val surfaceColor = Color.rgb(20, 24, 34)
    private val surfaceContainerColor = Color.rgb(37, 43, 57)
    private val userBubbleColor = Color.rgb(91, 78, 155)
    private val composerColor = Color.rgb(31, 37, 51)
    private val primaryText = Color.rgb(245, 241, 255)
    private val secondaryText = Color.rgb(204, 198, 220)
    private val accentColor = Color.rgb(208, 188, 255)

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
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
            background = roundedBackground(surfaceColor, dp(30))
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

            addView(createHeader(), matchWidth())

            status = TextView(context).apply {
                text = "Ich höre zu"
                setTextColor(primaryText)
                textSize = 17f
                gravity = Gravity.CENTER
            }
            addView(status, matchWidth(top = 6))

            waveform = WaveformView(context).apply {
                contentDescription = "Audiopegel"
                setAccentColor(accentColor)
            }
            addView(waveform, matchWidth(height = dp(38), top = 4))

            transcript = TextView(context).apply {
                setTextColor(secondaryText)
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 2
            }
            addView(transcript, matchWidth(top = 2))

            messageScroll = ScrollView(context).apply {
                isFillViewport = true
                visibility = View.GONE
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            messages = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
            messageScroll.addView(messages, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(messageScroll, matchWidth(top = 6))

            addView(createComposer(), matchWidth(top = 8))
        }

        return FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    dismissOverlay()
                    true
                } else true
            }
            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(18)
            })
        }
    }

    private fun createHeader(): View {
        val header = FrameLayout(context)
        val handle = View(context).apply {
            background = roundedBackground(Color.rgb(111, 106, 123), dp(3))
        }
        header.addView(handle, FrameLayout.LayoutParams(dp(42), dp(5), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val brand = TextView(context).apply {
            text = "✦  Hermes"
            setTextColor(primaryText)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(brand, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), Gravity.START or Gravity.BOTTOM
        ))

        val close = TextView(context).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(secondaryText)
            contentDescription = "Assistant schließen"
            setOnClickListener { dismissOverlay() }
        }
        header.addView(close, FrameLayout.LayoutParams(dp(40), dp(32), Gravity.END or Gravity.BOTTOM))
        return header
    }

    private fun createComposer(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        composer = EditText(context).apply {
            hint = "Noch etwas?"
            setHintTextColor(Color.rgb(171, 164, 187))
            setTextColor(primaryText)
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = roundedBackground(composerColor, dp(23))
            setPadding(dp(16), 0, dp(10), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitTypedText()
                    true
                } else false
            }
        }
        row.addView(composer, LinearLayout.LayoutParams(0, dp(48), 1f))

        val voice = MicrophoneButton(context, accentColor, surfaceColor).apply {
            contentDescription = "Sprachaufnahme starten"
            setOnClickListener {
                if (!submitted) startRecognition()
            }
        }
        row.addView(voice, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })

        val send = TextView(context).apply {
            text = "Senden"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(surfaceColor)
            background = roundedBackground(accentColor, dp(22))
            contentDescription = "Nachricht senden"
            setOnClickListener { submitTypedText() }
        }
        row.addView(send, LinearLayout.LayoutParams(dp(68), dp(48)).apply { leftMargin = dp(8) })
        return row
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        uiDismissed = false
        submitted = false
        lastPartialText = ""
        pendingSpeech = null
        activeAgentText = null
        transcript.text = ""
        transcript.visibility = View.VISIBLE
        messages.removeAllViews()
        messageScroll.visibility = View.GONE
        waveform.setLevel(0f)
        textToSpeech.stop()
        startRecognition()
    }

    private fun startRecognition() {
        if (submitted) return
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
        lastPartialText = ""
        transcript.text = ""
        status.text = "Ich höre zu"
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
                    if (!uiDismissed && !text.isNullOrBlank()) transcript.text = text
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.ifBlank { null } ?: lastPartialText.ifBlank { null }
                    if (text == null) {
                        if (!uiDismissed) status.text = "Ich habe nichts verstanden"
                    } else submitToHermes(text, activeConnection, spoken = true)
                }
                override fun onError(error: Int) {
                    if (submitted || uiDismissed) return
                    if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastPartialText.isNotBlank()) {
                        submitToHermes(lastPartialText, activeConnection, spoken = true)
                    } else {
                        status.text = recognitionErrorText(error)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMAN.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200)
            })
        }
    }

    private fun submitTypedText() {
        val text = composer.text.toString().trim()
        if (text.isBlank() || submitted) return
        val connection = connectionStore.load()
        if (connection == null) {
            status.text = "Hermes-Verbindung bitte in der App einrichten"
            return
        }
        composer.text?.clear()
        submitToHermes(text, connection, spoken = false)
    }

    private fun submitToHermes(text: String, connection: HermesConnection, spoken: Boolean) {
        if (submitted) return
        submitted = true
        speechRecognizer?.cancel()
        waveform.setLevel(0f)
        transcript.text = ""
        status.text = "Hermes denkt nach …"
        addUserBubble(text, spoken)
        activeAgentText = null

        HermesRunClient(connection).start(text, object : HermesRunListener {
            private val streamed = StringBuilder()

            override fun onStarted(runId: String) = Unit

            override fun onDelta(delta: String) {
                streamed.append(delta)
                postToUi {
                    status.text = "Hermes antwortet …"
                    val target = activeAgentText ?: addAgentBubble().also { activeAgentText = it }
                    target.text = streamed.toString()
                    scrollMessagesToBottom()
                }
            }

            override fun onCompleted(output: String) {
                val finalOutput = output.ifBlank { streamed.toString() }
                postToUi {
                    status.text = "Bereit"
                    val target = activeAgentText ?: addAgentBubble().also { activeAgentText = it }
                    target.text = finalOutput
                    scrollMessagesToBottom()
                    submitted = false
                    if (finalOutput.isNotBlank()) speakFinal(finalOutput)
                }
            }

            override fun onFailed(message: String) {
                postToUi {
                    status.text = message
                    submitted = false
                    waveform.setLevel(0f)
                }
            }
        })
    }

    private fun addUserBubble(text: String, spoken: Boolean) {
        ensureChatVisible()
        val body = TextView(context).apply {
            this.text = if (spoken) "$text\n\n⌁  Gesprochen" else text
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1f)
            background = roundedBackground(userBubbleColor, dp(22))
            setPadding(dp(15), dp(10), dp(15), dp(10))
        }
        val wrapper = FrameLayout(context)
        wrapper.addView(body, FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.72f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END
        ))
        messages.addView(wrapper, matchWidth(top = 8))
        scrollMessagesToBottom()
    }

    private fun addAgentBubble(): TextView {
        ensureChatVisible()
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val label = TextView(context).apply {
            text = "✦  Hermes"
            setTextColor(accentColor)
            textSize = 12f
            setPadding(dp(12), 0, 0, dp(4))
        }
        wrapper.addView(label)
        val body = TextView(context).apply {
            setTextColor(primaryText)
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1f)
            background = roundedBackground(surfaceContainerColor, dp(22))
            setPadding(dp(15), dp(11), dp(15), dp(11))
        }
        wrapper.addView(body, LinearLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.80f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        messages.addView(wrapper, matchWidth(top = 12))
        scrollMessagesToBottom()
        return body
    }

    private fun ensureChatVisible() {
        if (messageScroll.visibility == View.VISIBLE) return
        messageScroll.visibility = View.VISIBLE
        messageScroll.layoutParams = (messageScroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = maxOf(dp(220), (context.resources.displayMetrics.heightPixels * 0.48f).toInt())
        }
        transcript.visibility = View.GONE
    }

    private fun scrollMessagesToBottom() {
        messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
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
        // Do not call the Hermes stop endpoint here; accepted remote work must survive UI teardown.
        speechRecognizer?.destroy()
        textToSpeech.stop()
        textToSpeech.shutdown()
        super.onDestroy()
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun matchWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * density).toInt()

    private fun recognitionErrorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ich habe nichts verstanden"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Android-Spracherkennung ist nicht erreichbar"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofonzugriff fehlt"
        else -> "Spracherkennung fehlgeschlagen"
    }
}

private class MicrophoneButton(context: Context, private val fillColor: Int, private val iconColor: Int) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        paint.color = fillColor
        canvas.drawCircle(centerX, centerY, minOf(width, height) * 0.46f, paint)

        paint.color = iconColor
        paint.style = android.graphics.Paint.Style.FILL
        val capsuleWidth = width * 0.18f
        val capsuleHeight = height * 0.34f
        val capsule = android.graphics.RectF(
            centerX - capsuleWidth / 2f,
            centerY - capsuleHeight * 0.62f,
            centerX + capsuleWidth / 2f,
            centerY + capsuleHeight * 0.38f
        )
        canvas.drawRoundRect(capsule, capsuleWidth, capsuleWidth, paint)

        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = width * 0.055f
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        val arc = android.graphics.RectF(
            centerX - width * 0.22f,
            centerY - height * 0.10f,
            centerX + width * 0.22f,
            centerY + height * 0.26f
        )
        canvas.drawArc(arc, 0f, 180f, false, paint)
        canvas.drawLine(centerX, centerY + height * 0.25f, centerX, centerY + height * 0.33f, paint)
        canvas.drawLine(centerX - width * 0.11f, centerY + height * 0.33f, centerX + width * 0.11f, centerY + height * 0.33f, paint)
        paint.style = android.graphics.Paint.Style.FILL
    }
}

private class WaveformView(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var level = 0f
    private var accent = Color.rgb(208, 188, 255)

    fun setAccentColor(color: Int) {
        accent = color
        invalidate()
    }

    fun setLevel(newLevel: Float) {
        level = newLevel.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val bars = 21
        val gap = width / (bars * 2f)
        val centerY = height / 2f
        val baseHeight = height * 0.15f
        for (index in 0 until bars) {
            val distance = kotlin.math.abs(index - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val envelope = 1f - distance * 0.55f
            val barHeight = baseHeight + level * height * 0.76f * envelope
            paint.color = Color.argb((150 + 105 * envelope).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            paint.strokeWidth = gap * 0.72f
            val x = gap + index * gap * 2f
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }
    }
}
