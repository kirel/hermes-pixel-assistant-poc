package de.danielkirs.hermesassistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

/**
 * A Material-inspired voice and text conversation surface. A sent Hermes run is
 * server-owned: dismissing this sheet only hides UI and stops listening, never
 * cancels remote work that was already accepted by Hermes.
 */
class HermesVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val connectionStore = HermesConnectionStore(context)
    private val queuePreferences = context.getSharedPreferences(PENDING_QUEUE_PREFERENCES, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density

    private lateinit var status: TextView
    private lateinit var voiceLabel: TextView
    private lateinit var transcript: TextView
    private lateinit var waveform: WaveformView
    private lateinit var voiceInputContainer: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var composer: EditText
    private lateinit var sheetPanel: LinearLayout
    private lateinit var rootContainer: FrameLayout
    private lateinit var textToSpeech: TextToSpeech

    private var activeAgentText: TextView? = null
    private var voiceIndicatorAnimator: ValueAnimator? = null
    private var thinkingAnimator: ValueAnimator? = null
    private var activeRunId: String? = null
    private var steeringPending: PendingMessage? = null
    private val pendingSteers = ArrayDeque<PendingMessage>()
    private val pendingMessages = ArrayDeque<PendingMessage>()
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsInitialized = false
    private var submitted = false
    private var uiDismissed = false
    private var sessionVisible = false
    private var sessionInitialized = false
    private var suppressActiveRunSpeech = false
    private var pendingSpeech: String? = null
    private var lastPartialText = ""
    private var dragStartY = 0f
    private var dragStartTranslationY = 0f
    private var rootTouchStartY = 0f
    private var isDraggingSheet = false
    private var initialListeningDeadlineMs = 0L
    private var recognitionStartedAtMs = 0L
    private var recognitionGeneration = 0
    private var recognitionActive = false
    private var recognitionRestartPending = false
    private var injectedAudioAllowedForTurn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private var audioRecord: AudioRecord? = null
    private var audioPipeRead: ParcelFileDescriptor? = null
    private var audioPipeOutput: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var audioCaptureThread: Thread? = null
    private var keyboardHeightPx = 0
    private var sheetKeyboardOffsetPx = 0
    private var desiredChatHeightPx = 0
    private var historyOffset = 0
    private var historyLoading = false
    private var historyExhausted = false
    private var initialHistoryRendered = false
    private var chatTouchedByUser = false
    private val deferredHistory = ArrayDeque<HermesHistoryMessage>()
    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val surfaceColor = Color.rgb(20, 24, 34)
    private val surfaceContainerColor = Color.rgb(37, 43, 57)
    private val userBubbleColor = Color.rgb(91, 78, 155)
    private val composerColor = Color.rgb(31, 37, 51)
    private val primaryText = Color.rgb(245, 241, 255)
    private val secondaryText = Color.rgb(204, 198, 220)
    private val accentColor = Color.rgb(208, 188, 255)

    private data class PendingBubble(val state: TextView, val steerButton: TextView)

    private data class PendingMessage(
        val text: String,
        val spoken: Boolean,
        val state: TextView,
        val steerButton: TextView
    )

    private companion object {
        const val RECOGNITION_LOG_TAG = "HermesSpeech"
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val AUDIO_CHUNK_BYTES = 3_200
        const val PENDING_QUEUE_PREFERENCES = "pending_message_queue"
        const val PENDING_QUEUE_KEY = "messages"
    }

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

    override fun onCreate() {
        super.onCreate()
        window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onCreateContentView(): View {
        sheetPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
            background = roundedBackground(surfaceColor, dp(30))

            addView(createHeader(), matchWidth())

            messageScroll = ScrollView(context).apply {
                isFillViewport = true
                visibility = View.GONE
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) chatTouchedByUser = true
                    false
                }
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    if (initialHistoryRendered && chatTouchedByUser && scrollY <= dp(32)) loadOlderHistoryPage()
                }
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

            addView(createInlineStatus(), matchWidth(top = 6))
            addView(createVoiceInputIndicator(), matchWidth(height = 0, top = 6))
            addView(createComposer(), matchWidth(top = 8))
        }

        rootContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        rootTouchStartY = event.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val startedInBottomGestureZone = rootTouchStartY >= view.height - dp(72)
                        if (startedInBottomGestureZone && rootTouchStartY - event.y > dp(36)) {
                            animateDismissSheet()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        animateDismissSheet()
                        true
                    }
                    else -> true
                }
            }
            addView(sheetPanel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(36)
            })
        }
        rootContainer.post { installKeyboardAwareLayout() }
        return rootContainer
    }

    private fun createHeader(): View {
        val header = FrameLayout(context).apply {
            setOnTouchListener { _, event -> handleSheetDrag(event) }
        }
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
            setOnClickListener { animateDismissSheet() }
        }
        header.addView(close, FrameLayout.LayoutParams(dp(40), dp(32), Gravity.END or Gravity.BOTTOM))
        return header
    }

    private fun createInlineStatus(): View {
        status = TextView(context).apply {
            setTextColor(secondaryText)
            textSize = 13f
            visibility = View.GONE
            alpha = 0f
            background = roundedBackground(surfaceContainerColor, dp(17))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        return FrameLayout(context).apply {
            addView(status, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START
            ))
        }
    }

    private fun createVoiceInputIndicator(): View {
        voiceInputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(surfaceContainerColor, dp(18))
        }
        voiceLabel = TextView(context).apply {
            text = "Ich höre zu"
            setTextColor(accentColor)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        voiceInputContainer.addView(voiceLabel, matchWidth())
        waveform = WaveformView(context).apply {
            contentDescription = "Audiopegel"
            setAccentColor(accentColor)
        }
        voiceInputContainer.addView(waveform, matchWidth(height = dp(32), top = 2))
        transcript = TextView(context).apply {
            setTextColor(secondaryText)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
        }
        voiceInputContainer.addView(transcript, matchWidth(top = 2))
        return voiceInputContainer
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
                if (!submitted) requestFreshRecognition()
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
        val reuseExistingSession = sessionInitialized
        val reinvokedWhileVisible = sessionVisible && !uiDismissed
        uiDismissed = false
        sheetPanel.animate().cancel()
        sheetPanel.translationY = 0f
        sessionVisible = true
        if (reuseExistingSession) {
            Log.i(
                RECOGNITION_LOG_TAG,
                if (reinvokedWhileVisible) "session-reinvoked:restart-voice" else "session-reopened:preserve-state"
            )
            requestFreshRecognition(forceRestart = true)
            return
        }
        sessionInitialized = true
        submitted = false
        recognitionActive = false
        recognitionRestartPending = false
        lastPartialText = ""
        pendingSpeech = null
        thinkingAnimator?.cancel()
        thinkingAnimator = null
        activeAgentText = null
        activeRunId = null
        steeringPending = null
        pendingSteers.clear()
        pendingMessages.clear()
        desiredChatHeightPx = 0
        historyOffset = 0
        historyLoading = false
        historyExhausted = false
        initialHistoryRendered = false
        chatTouchedByUser = false
        deferredHistory.clear()
        status.animate().cancel()
        status.visibility = View.GONE
        status.alpha = 0f
        voiceIndicatorAnimator?.cancel()
        voiceInputContainer.visibility = View.GONE
        voiceInputContainer.alpha = 0f
        voiceInputContainer.layoutParams = (voiceInputContainer.layoutParams as LinearLayout.LayoutParams).apply { height = 0 }
        transcript.text = ""
        messages.removeAllViews()
        messageScroll.visibility = View.GONE
        waveform.setLevel(0f)
        textToSpeech.stop()
        startRecognition()
        loadHistoryPage(initial = true)
        restorePendingMessages()
    }

    private fun requestFreshRecognition(forceRestart: Boolean = false) {
        // Barge-in: never let the assistant's own TTS feed back into STT.
        textToSpeech.stop()
        pendingSpeech = null
        if ((recognitionActive || recognitionRestartPending) && !forceRestart) {
            voiceLabel.text = "Ich höre bereits zu …"
            showVoiceInputIndicator()
            traceRecognition("manual-start-ignored:already-active")
            return
        }
        if (forceRestart && submitted) suppressActiveRunSpeech = true
        recognitionRestartPending = true
        stopRecognition()
        hideInlineStatus()
        voiceLabel.text = "Ich höre zu"
        rootContainer.postDelayed({
            recognitionRestartPending = false
            if (!uiDismissed) startRecognition()
        }, 140)
    }

    private fun stopRecognition() {
        recognitionActive = false
        recognitionGeneration += 1
        stopInjectedAudioCapture()
        speechRecognizer?.let { recognizer ->
            try { recognizer.cancel() } catch (_: Exception) { }
            try { recognizer.destroy() } catch (_: Exception) { }
        }
        speechRecognizer = null
    }

    private fun retryRecognitionWithinWindow(reason: String): Boolean {
        if (SystemClock.elapsedRealtime() >= initialListeningDeadlineMs || uiDismissed) return false
        traceRecognition("retry:$reason")
        recognitionRestartPending = true
        voiceLabel.text = "Ich höre noch zu …"
        showVoiceInputIndicator()
        stopRecognition()
        rootContainer.postDelayed({
            recognitionRestartPending = false
            if (!uiDismissed) startRecognition(continueInitialWindow = true)
        }, 120)
        return true
    }

    private fun startRecognition(continueInitialWindow: Boolean = false) {
        if (recognitionActive) return
        recognitionRestartPending = false
        if (!continueInitialWindow) {
            initialListeningDeadlineMs = SystemClock.elapsedRealtime() + 10_000L
            injectedAudioAllowedForTurn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        }
        val connection = connectionStore.load()
        when {
            connection == null -> {
                showInlineStatus("Hermes-Verbindung bitte in der App einrichten")
                return
            }
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                showInlineStatus("Mikrofonzugriff in der App erlauben")
                return
            }
            !SpeechRecognizer.isRecognitionAvailable(context) -> {
                showInlineStatus("Android-Spracherkennung ist nicht verfügbar")
                return
            }
        }

        val activeConnection = connection ?: return
        lastPartialText = ""
        transcript.text = ""
        hideInlineStatus()
        voiceLabel.text = "Ich höre zu"
        showVoiceInputIndicator()
        stopRecognition()
        recognitionStartedAtMs = SystemClock.elapsedRealtime()
        val generation = ++recognitionGeneration
        val injectedAudioSource = if (injectedAudioAllowedForTurn) {
            startInjectedAudioCapture(generation)
        } else {
            null
        }
        val usingInjectedAudio = injectedAudioSource != null
        var speechStarted = false
        traceRecognition("start:audioSource=${if (usingInjectedAudio) "buffered" else "recognizer-mic"}")
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (generation == recognitionGeneration) traceRecognition("ready")
                }
                override fun onBeginningOfSpeech() {
                    if (generation != recognitionGeneration) return
                    speechStarted = true
                    traceRecognition("speech-begin")
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (generation != recognitionGeneration) return
                    traceRecognition("speech-end:began=$speechStarted partialChars=${lastPartialText.length}")
                    if (lastPartialText.isNotBlank()) {
                        hideVoiceInputIndicator()
                        if (!uiDismissed) showInlineStatus("Verarbeite Sprache …")
                    } else {
                        voiceLabel.text = "Ich höre noch zu …"
                    }
                }
                override fun onRmsChanged(rmsdB: Float) {
                    if (generation != recognitionGeneration) return
                    if (!uiDismissed) waveform.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (generation != recognitionGeneration) return
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        lastPartialText = text
                        traceRecognition("partial:chars=${text.length}")
                    }
                    if (!uiDismissed && !text.isNullOrBlank()) transcript.text = text
                }
                override fun onResults(results: Bundle?) {
                    if (generation != recognitionGeneration) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.ifBlank { null } ?: lastPartialText.ifBlank { null }
                    traceRecognition("results:chars=${text?.length ?: 0}")
                    if (text == null) {
                        if (!retryRecognitionWithinWindow("empty-results")) {
                            stopRecognition()
                            hideVoiceInputIndicator()
                            if (!uiDismissed) showInlineStatus("Ich habe nichts verstanden")
                        }
                    } else acceptRecognizedText(text, activeConnection)
                }
                override fun onError(error: Int) {
                    if (generation != recognitionGeneration || uiDismissed) return
                    traceRecognition("error:${recognitionErrorName(error)} partialChars=${lastPartialText.length}")
                    val retryableInitialError = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastPartialText.isNotBlank()) {
                        acceptRecognizedText(lastPartialText, activeConnection)
                    } else if (usingInjectedAudio &&
                        lastPartialText.isBlank() &&
                        (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_CLIENT)
                    ) {
                        injectedAudioAllowedForTurn = false
                        retryRecognitionWithinWindow("buffered-audio-fallback:${recognitionErrorName(error)}")
                    } else if (retryableInitialError && retryRecognitionWithinWindow(recognitionErrorName(error))) {
                        // Retry scheduled within the still-active initial listening window.
                    } else {
                        stopRecognition()
                        hideVoiceInputIndicator()
                        showInlineStatus(recognitionErrorText(error))
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMAN.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200)
                if (injectedAudioSource != null) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, injectedAudioSource)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AUDIO_SAMPLE_RATE_HZ)
                }
            })
        }
        recognitionActive = true
    }

    @SuppressLint("MissingPermission")
    private fun startInjectedAudioCapture(generation: Int): ParcelFileDescriptor? {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) return null

        var record: AudioRecord? = null
        var readPipe: ParcelFileDescriptor? = null
        var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
        return try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AUDIO_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer * 2, AUDIO_CHUNK_BYTES * 2)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            val pipes = ParcelFileDescriptor.createPipe()
            readPipe = pipes[0]
            output = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
            audioRecord = record
            audioPipeRead = readPipe
            audioPipeOutput = output
            record.startRecording()
            val captureRecord = record
            val captureOutput = output
            audioCaptureThread = Thread({
                val buffer = ByteArray(AUDIO_CHUNK_BYTES)
                try {
                    while (!Thread.currentThread().isInterrupted && generation == recognitionGeneration) {
                        val count = captureRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) break
                        captureOutput.write(buffer, 0, count)
                        val level = pcmLevel(buffer, count)
                        postToUi {
                            if (generation == recognitionGeneration) waveform.setLevel(level)
                        }
                    }
                } catch (_: Exception) {
                    // Closing the pipe or AudioRecord is the normal way to stop this blocking loop.
                }
            }, "HermesSpeechAudio").apply { start() }
            readPipe
        } catch (error: Exception) {
            Log.w(RECOGNITION_LOG_TAG, "buffered audio unavailable; using recognizer microphone", error)
            try { output?.close() } catch (_: Exception) { }
            try { readPipe?.close() } catch (_: Exception) { }
            try { record?.release() } catch (_: Exception) { }
            audioRecord = null
            audioPipeRead = null
            audioPipeOutput = null
            audioCaptureThread = null
            null
        }
    }

    private fun stopInjectedAudioCapture() {
        val record = audioRecord
        audioRecord = null
        try { record?.stop() } catch (_: Exception) { }
        try { audioPipeOutput?.close() } catch (_: Exception) { }
        audioPipeOutput = null
        try { audioPipeRead?.close() } catch (_: Exception) { }
        audioPipeRead = null
        audioCaptureThread?.interrupt()
        audioCaptureThread = null
        try { record?.release() } catch (_: Exception) { }
    }

    private fun pcmLevel(buffer: ByteArray, count: Int): Float {
        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < count) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort().toInt()
            sumSquares += sample.toDouble() * sample.toDouble()
            samples += 1
            index += 2
        }
        if (samples == 0) return 0f
        val rms = kotlin.math.sqrt(sumSquares / samples) / Short.MAX_VALUE
        return (rms * 8.0).toFloat().coerceIn(0f, 1f)
    }

    private fun acceptRecognizedText(text: String, connection: HermesConnection) {
        stopRecognition()
        hideVoiceInputIndicator()
        waveform.setLevel(0f)
        transcript.text = ""
        hideInlineStatus()
        if (submitted || pendingMessages.isNotEmpty() || steeringPending != null) {
            enqueueMessage(text, spoken = true)
        } else {
            submitToHermes(text, connection, spoken = true)
        }
    }

    private fun submitTypedText() {
        val text = composer.text.toString().trim()
        if (text.isBlank()) return
        val connection = connectionStore.load()
        if (connection == null) {
            showInlineStatus("Hermes-Verbindung bitte in der App einrichten")
            return
        }
        composer.text?.clear()
        if (submitted || pendingMessages.isNotEmpty() || steeringPending != null) {
            enqueueMessage(text, spoken = false)
            if (!submitted) submitPendingBatch()
        } else {
            submitToHermes(text, connection, spoken = false)
        }
    }

    private fun submitToHermes(
        text: String,
        connection: HermesConnection,
        spoken: Boolean,
        queuedMessages: List<PendingMessage> = emptyList()
    ) {
        if (submitted) return
        submitted = true
        hideVoiceInputIndicator()
        stopRecognition()
        waveform.setLevel(0f)
        transcript.text = ""
        hideInlineStatus()
        if (queuedMessages.isEmpty()) {
            addUserBubble(text, spoken)
        } else {
            queuedMessages.forEach { queuedMessage ->
                queuedMessage.state.text = "Wird gemeinsam gesendet …"
                queuedMessage.steerButton.visibility = View.GONE
            }
        }
        activeAgentText = addAgentBubble().apply {
            this.text = "Hermes denkt nach …"
            startThinkingIndicator(this)
        }

        HermesRunClient(connection).start(text, object : HermesRunListener {
            private val streamed = StringBuilder()

            override fun onStarted(runId: String) {
                postToMain { activeRunId = runId }
            }

            override fun onDelta(delta: String) {
                streamed.append(delta)
                postToMain {
                    hideInlineStatus()
                    stopThinkingIndicator()
                    val target = activeAgentText ?: addAgentBubble().also { activeAgentText = it }
                    target.text = streamed.toString()
                    scrollMessagesToBottom()
                }
            }

            override fun onCompleted(output: String) {
                val finalOutput = output.ifBlank { streamed.toString() }
                postToMain {
                    hideInlineStatus()
                    stopThinkingIndicator()
                    val target = activeAgentText ?: addAgentBubble().also { activeAgentText = it }
                    target.text = finalOutput
                    scrollMessagesToBottom()
                    submitted = false
                    activeRunId = null
                    val shouldSpeak = !suppressActiveRunSpeech
                    suppressActiveRunSpeech = false
                    if (!uiDismissed && shouldSpeak && finalOutput.isNotBlank()) speakFinal(finalOutput)
                    rootContainer.postDelayed({ submitPendingBatch() }, 120)
                }
            }

            override fun onFailed(message: String) {
                postToMain {
                    stopThinkingIndicator()
                    if (!uiDismissed) showInlineStatus(message)
                    submitted = false
                    activeRunId = null
                    suppressActiveRunSpeech = false
                    waveform.setLevel(0f)
                    rootContainer.postDelayed({ submitPendingBatch() }, 120)
                }
            }
        })
    }

    private fun enqueueMessage(text: String, spoken: Boolean) {
        val bubble = addUserBubble(text, spoken, queued = true) ?: return
        val pending = PendingMessage(text, spoken, bubble.state, bubble.steerButton)
        bubble.steerButton.setOnClickListener { steerPendingMessage(pending) }
        pendingMessages.addLast(pending)
        persistPendingMessages()
        scrollMessagesToBottom()
    }

    private fun submitPendingBatch() {
        if (submitted || steeringPending != null || pendingSteers.isNotEmpty() || pendingMessages.isEmpty()) return
        val batch = pendingMessages.toList()
        val connection = connectionStore.load()
        if (connection == null) {
            batch.forEach { pending ->
                pending.state.text = "Verbindung erforderlich"
                pending.steerButton.visibility = View.GONE
            }
            if (!uiDismissed) showInlineStatus("Hermes-Verbindung bitte in der App einrichten")
            return
        }
        pendingMessages.clear()
        persistPendingMessages()
        val combinedText = batch.joinToString(separator = "\n") { pending -> "- ${pending.text}" }
        submitToHermes(
            combinedText,
            connection,
            spoken = batch.all { it.spoken },
            queuedMessages = batch
        )
    }

    private fun steerPendingMessage(pending: PendingMessage) {
        if (activeRunId == null || !submitted || pending == steeringPending || pendingSteers.contains(pending)) return
        pending.state.text = "Steer wartet …"
        pending.steerButton.isEnabled = false
        pendingSteers.addLast(pending)
        processNextPendingSteer()
    }

    private fun processNextPendingSteer() {
        if (steeringPending != null || pendingSteers.isEmpty()) return
        val pending = pendingSteers.removeFirst()
        val runId = activeRunId
        val connection = connectionStore.load()
        if (runId == null || connection == null || !submitted) {
            pending.state.text = "Wartet"
            pending.steerButton.isEnabled = true
            pendingSteers.forEach { queued ->
                queued.state.text = "Wartet"
                queued.steerButton.isEnabled = true
            }
            pendingSteers.clear()
            if (!submitted) submitPendingBatch()
            return
        }
        steeringPending = pending
        pending.state.text = "Wird als Hinweis ergänzt …"
        HermesRunClient(connection).steer(runId, pending.text) { accepted, message ->
            postToMain {
                steeringPending = null
                if (accepted) {
                    pendingMessages.remove(pending)
                    persistPendingMessages()
                    pending.state.text = message
                    pending.steerButton.visibility = View.GONE
                } else {
                    pending.state.text = "Wartet"
                    pending.steerButton.isEnabled = true
                }
                processNextPendingSteer()
                if (!submitted && steeringPending == null && pendingSteers.isEmpty()) submitPendingBatch()
            }
        }
    }

    private fun persistPendingMessages() {
        val serialized = JSONArray()
        pendingMessages.forEach { pending ->
            serialized.put(JSONObject().apply {
                put("text", pending.text)
                put("spoken", pending.spoken)
            })
        }
        queuePreferences.edit().putString(PENDING_QUEUE_KEY, serialized.toString()).apply()
    }

    private fun restorePendingMessages() {
        if (pendingMessages.isNotEmpty()) return
        val raw = queuePreferences.getString(PENDING_QUEUE_KEY, null) ?: return
        val restored = try {
            JSONArray(raw)
        } catch (_: Exception) {
            queuePreferences.edit().remove(PENDING_QUEUE_KEY).apply()
            return
        }
        for (index in 0 until restored.length()) {
            val item = restored.optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isEmpty()) continue
            val spoken = item.optBoolean("spoken", false)
            val bubble = addUserBubble(text, spoken, queued = true) ?: continue
            val pending = PendingMessage(text, spoken, bubble.state, bubble.steerButton)
            bubble.steerButton.setOnClickListener { steerPendingMessage(pending) }
            pendingMessages.addLast(pending)
        }
        if (pendingMessages.isNotEmpty()) {
            Log.i(RECOGNITION_LOG_TAG, "queue-restored:count=${pendingMessages.size}")
            scrollMessagesToBottom()
        }
    }

    private fun addUserBubble(text: String, spoken: Boolean, queued: Boolean = false): PendingBubble? {
        ensureChatVisible()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val body = TextView(context).apply {
            this.text = if (spoken) "$text\n\n⌁  Gesprochen" else text
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1f)
            background = roundedBackground(userBubbleColor, dp(22))
            setPadding(dp(15), dp(10), dp(15), dp(10))
        }
        column.addView(body, LinearLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.72f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        var pendingBubble: PendingBubble? = null
        if (queued) {
            val metadata = LinearLayout(context).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            val state = TextView(context).apply {
                this.text = "Wartet"
                setTextColor(secondaryText)
                textSize = 12f
                setPadding(0, dp(5), dp(8), 0)
            }
            val steer = TextView(context).apply {
                this.text = "Als Hinweis ergänzen"
                setTextColor(accentColor)
                textSize = 12f
                background = roundedBackground(surfaceContainerColor, dp(14))
                setPadding(dp(10), dp(5), dp(10), dp(5))
                contentDescription = "Als Hinweis in den laufenden Auftrag ergänzen"
            }
            metadata.addView(state)
            metadata.addView(steer)
            column.addView(metadata, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
            pendingBubble = PendingBubble(state, steer)
        }

        val wrapper = FrameLayout(context)
        wrapper.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END
        ))
        messages.addView(wrapper, matchWidth(top = 8))
        animateMessageEntry(wrapper, fromRight = true)
        scrollMessagesToBottom()
        return pendingBubble
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
        animateMessageEntry(wrapper, fromRight = false)
        scrollMessagesToBottom()
        return body
    }

    private fun startThinkingIndicator(view: View) {
        thinkingAnimator?.cancel()
        thinkingAnimator = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 720
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation -> view.alpha = animation.animatedValue as Float }
            start()
        }
    }

    private fun stopThinkingIndicator() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
        activeAgentText?.alpha = 1f
    }

    private fun showInlineStatus(text: String) {
        status.animate().cancel()
        status.text = text
        if (status.visibility != View.VISIBLE) {
            status.visibility = View.VISIBLE
            status.alpha = 0f
            status.translationY = dp(8).toFloat()
        }
        status.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideInlineStatus() {
        if (status.visibility != View.VISIBLE) return
        status.animate().cancel()
        status.animate()
            .alpha(0f)
            .translationY(dp(6).toFloat())
            .setDuration(120)
            .withEndAction {
                status.visibility = View.GONE
                status.translationY = 0f
            }
            .start()
    }

    private fun showVoiceInputIndicator() {
        voiceIndicatorAnimator?.cancel()
        val params = voiceInputContainer.layoutParams as LinearLayout.LayoutParams
        val startHeight = params.height.coerceAtLeast(0)
        val targetHeight = dp(96)
        voiceInputContainer.visibility = View.VISIBLE
        voiceInputContainer.alpha = if (startHeight == 0) 0f else voiceInputContainer.alpha
        voiceIndicatorAnimator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val height = animation.animatedValue as Int
                voiceInputContainer.layoutParams = (voiceInputContainer.layoutParams as LinearLayout.LayoutParams).apply {
                    this.height = height
                }
                voiceInputContainer.alpha = height / targetHeight.toFloat()
                voiceInputContainer.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyKeyboardLayout()
                }
            })
            start()
        }
    }

    private fun hideVoiceInputIndicator() {
        if (voiceInputContainer.visibility != View.VISIBLE) return
        voiceIndicatorAnimator?.cancel()
        val startHeight = (voiceInputContainer.layoutParams as LinearLayout.LayoutParams).height.coerceAtLeast(0)
        voiceIndicatorAnimator = ValueAnimator.ofInt(startHeight, 0).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val height = animation.animatedValue as Int
                voiceInputContainer.layoutParams = (voiceInputContainer.layoutParams as LinearLayout.LayoutParams).apply {
                    this.height = height
                }
                voiceInputContainer.alpha = if (startHeight == 0) 0f else height / startHeight.toFloat()
                voiceInputContainer.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    voiceInputContainer.visibility = View.GONE
                    voiceInputContainer.alpha = 0f
                    applyKeyboardLayout()
                }
            })
            start()
        }
    }

    private fun loadOlderHistoryPage() {
        if (deferredHistory.isNotEmpty()) {
            val deferred = deferredHistory.toList()
            deferredHistory.clear()
            prependHistory(deferred, scrollToBottom = false)
            return
        }
        loadHistoryPage(initial = false)
    }

    private fun loadHistoryPage(initial: Boolean) {
        if (historyLoading || historyExhausted) return
        val connection = connectionStore.load() ?: return
        historyLoading = true
        HermesRunClient(connection).loadHistory(limit = 60, offset = historyOffset) { history, returned, error ->
            postToUi {
                historyLoading = false
                if (error != null) return@postToUi
                historyOffset += returned
                if (returned < 60) historyExhausted = true
                val visibleMessages = if (initial) {
                    val initialVisible = history.takeLast(12)
                    deferredHistory.addAll(history.dropLast(initialVisible.size))
                    initialVisible
                } else {
                    history
                }
                if (visibleMessages.isEmpty()) return@postToUi
                ensureChatVisible()
                prependHistory(visibleMessages, scrollToBottom = initial)
                if (initial) {
                    rootContainer.postDelayed({
                        if (!uiDismissed) {
                            scrollMessagesToBottom()
                            initialHistoryRendered = true
                        }
                    }, 320)
                }
            }
        }
    }

    private fun prependHistory(history: List<HermesHistoryMessage>, scrollToBottom: Boolean) {
        val priorHeight = messages.height
        val priorScroll = messageScroll.scrollY
        for (message in history.asReversed()) {
            val bubble = when (message.role) {
                "user" -> createHistoryUserBubble(message.content)
                "assistant" -> createHistoryAgentBubble(message.content)
                else -> null
            } ?: continue
            messages.addView(bubble, 0, matchWidth(top = 8))
        }
        messages.post {
            if (scrollToBottom) {
                messageScroll.fullScroll(View.FOCUS_DOWN)
            } else {
                messageScroll.scrollTo(0, (messages.height - priorHeight + priorScroll).coerceAtLeast(0))
            }
        }
    }

    private fun createHistoryUserBubble(text: String): View {
        val body = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1f)
            background = roundedBackground(userBubbleColor, dp(22))
            setPadding(dp(15), dp(10), dp(15), dp(10))
        }
        return FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(
                (context.resources.displayMetrics.widthPixels * 0.72f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END
            ))
        }
    }

    private fun createHistoryAgentBubble(text: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = "✦  Hermes"
                setTextColor(accentColor)
                textSize = 12f
                setPadding(dp(12), 0, 0, dp(4))
            })
            addView(TextView(context).apply {
                this.text = text
                setTextColor(primaryText)
                textSize = 16f
                setLineSpacing(dp(2).toFloat(), 1f)
                background = roundedBackground(surfaceContainerColor, dp(22))
                setPadding(dp(15), dp(11), dp(15), dp(11))
            }, LinearLayout.LayoutParams(
                (context.resources.displayMetrics.widthPixels * 0.80f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun installKeyboardAwareLayout() {
        rootContainer.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            rootContainer.getWindowVisibleDisplayFrame(visibleFrame)
            val fullHeight = rootContainer.rootView.height
            val frameInset = (fullHeight - visibleFrame.bottom).coerceAtLeast(0)
            val resizedInset = (fullHeight - rootContainer.height).coerceAtLeast(0)
            val keyboard = maxOf(frameInset, resizedInset).takeIf { it > dp(120) } ?: 0
            val offset = if (resizedInset > dp(120)) 0 else keyboard
            if (keyboard != keyboardHeightPx || offset != sheetKeyboardOffsetPx) {
                keyboardHeightPx = keyboard
                sheetKeyboardOffsetPx = offset
                applyKeyboardLayout()
            }
        }
    }

    private fun applyKeyboardLayout() {
        val sheetParams = sheetPanel.layoutParams as FrameLayout.LayoutParams
        val keyboardSafeGap = if (keyboardHeightPx > 0) dp(12) else 0
        val targetMargin = dp(36) + sheetKeyboardOffsetPx + keyboardSafeGap
        if (sheetParams.bottomMargin != targetMargin) {
            sheetParams.bottomMargin = targetMargin
            sheetPanel.layoutParams = sheetParams
        }
        if (messageScroll.visibility == View.VISIBLE) {
            animateChatHeight(resolvedChatHeight())
        }
    }

    private fun resolvedChatHeight(): Int {
        if (desiredChatHeightPx == 0) return dp(220)
        val fullHeight = rootContainer.rootView.height
        val visibleHeight = if (keyboardHeightPx > 0) fullHeight - keyboardHeightPx else rootContainer.height
        // Measure the real non-scrollable sheet content (header, status, waveform and composer)
        // instead of relying on a fixed estimate that changes with keyboard/vendor UI.
        val fixedSheetContent = (sheetPanel.height - messageScroll.height).coerceAtLeast(dp(230))
        val safeGap = if (keyboardHeightPx > 0) dp(20) else dp(8)
        return desiredChatHeightPx.coerceAtMost(maxOf(dp(120), visibleHeight - fixedSheetContent - safeGap))
    }

    private fun animateChatHeight(targetHeight: Int) {
        val params = messageScroll.layoutParams as LinearLayout.LayoutParams
        if (params.height == targetHeight) return
        ValueAnimator.ofInt(params.height.coerceAtLeast(0), targetHeight).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                messageScroll.layoutParams = (messageScroll.layoutParams as LinearLayout.LayoutParams).apply {
                    height = animation.animatedValue as Int
                }
                messageScroll.requestLayout()
            }
            start()
        }
    }

    private fun ensureChatVisible() {
        if (messageScroll.visibility == View.VISIBLE) return
        desiredChatHeightPx = maxOf(dp(220), (context.resources.displayMetrics.heightPixels * 0.48f).toInt())
        val targetHeight = resolvedChatHeight()
        messageScroll.visibility = View.VISIBLE
        messageScroll.alpha = 0f
        messageScroll.translationY = dp(20).toFloat()
        messageScroll.layoutParams = (messageScroll.layoutParams as LinearLayout.LayoutParams).apply { height = 0 }
        ValueAnimator.ofInt(0, targetHeight).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                messageScroll.layoutParams = (messageScroll.layoutParams as LinearLayout.LayoutParams).apply {
                    height = animation.animatedValue as Int
                }
                messageScroll.requestLayout()
            }
            start()
        }
        messageScroll.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateMessageEntry(view: View, fromRight: Boolean) {
        view.alpha = 0f
        view.translationX = if (fromRight) dp(20).toFloat() else -dp(20).toFloat()
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun scrollMessagesToBottom() {
        messageScroll.post {
            messageScroll.fullScroll(View.FOCUS_DOWN)
            // The chat sheet itself can still be expanding when a message arrives.
            // Repeat after that layout pass so new output never leaves the user at the top.
            messageScroll.postDelayed({ messageScroll.fullScroll(View.FOCUS_DOWN) }, 260)
        }
    }

    private fun postToUi(block: () -> Unit) {
        context.mainExecutor.execute {
            if (!uiDismissed) block()
        }
    }

    private fun postToMain(block: () -> Unit) {
        context.mainExecutor.execute(block)
    }

    private fun speakFinal(text: String) {
        if (!ttsInitialized) {
            pendingSpeech = text
            return
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-final-response")
    }

    private fun handleSheetDrag(event: MotionEvent): Boolean {
        if (uiDismissed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sheetPanel.animate().cancel()
                dragStartY = event.rawY
                dragStartTranslationY = sheetPanel.translationY
                isDraggingSheet = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = (event.rawY - dragStartY).coerceAtLeast(0f)
                if (delta > dragSlop) isDraggingSheet = true
                if (isDraggingSheet) sheetPanel.translationY = dragStartTranslationY + delta
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dismissThreshold = maxOf(dp(104).toFloat(), sheetPanel.height * 0.24f)
                if (isDraggingSheet && sheetPanel.translationY >= dismissThreshold) {
                    animateDismissSheet()
                } else {
                    sheetPanel.animate()
                        .translationY(0f)
                        .setDuration(260)
                        .setInterpolator(OvershootInterpolator(0.65f))
                        .start()
                }
                return true
            }
        }
        return false
    }

    private fun animateDismissSheet() {
        if (uiDismissed) return
        uiDismissed = true
        stopRecognition()
        waveform.setLevel(0f)
        val target = maxOf(rootContainer.height, sheetPanel.height).toFloat() + dp(36)
        sheetPanel.animate()
            .translationY(target)
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction { hide() }
            .start()
    }

    private fun dismissOverlay() {
        uiDismissed = true
        stopRecognition()
        waveform.setLevel(0f)
        hide()
    }

    override fun onHide() {
        uiDismissed = true
        sessionVisible = false
        stopRecognition()
        super.onHide()
    }

    override fun onDestroy() {
        // Do not call the Hermes stop endpoint here; accepted remote work must survive UI teardown.
        sessionVisible = false
        stopRecognition()
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

    private fun traceRecognition(event: String) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - recognitionStartedAtMs).coerceAtLeast(0L)
        val remaining = (initialListeningDeadlineMs - now).coerceAtLeast(0L)
        Log.i(RECOGNITION_LOG_TAG, "generation=$recognitionGeneration elapsedMs=$elapsed remainingMs=$remaining event=$event")
    }

    private fun recognitionErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        else -> "UNKNOWN_$error"
    }

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
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()
    private var targetLevel = 0f
    private var displayedLevel = 0f
    private var accent = Color.rgb(208, 188, 255)

    fun setAccentColor(color: Int) {
        accent = color
        invalidate()
    }

    fun setLevel(newLevel: Float) {
        targetLevel = newLevel.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        displayedLevel += (targetLevel - displayedLevel) * 0.18f
        val centerY = height / 2f
        val phase = SystemClock.uptimeMillis() / 430f
        val colors = intArrayOf(
            Color.rgb(67, 214, 255),
            Color.rgb(77, 231, 184),
            Color.rgb(250, 220, 91),
            Color.rgb(255, 139, 104),
            Color.rgb(238, 101, 210),
            accent,
            Color.rgb(112, 139, 255)
        )
        paint.shader = android.graphics.LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            colors,
            null,
            android.graphics.Shader.TileMode.CLAMP
        )

        val frequencies = floatArrayOf(1.15f, 1.62f, 2.08f)
        for (layer in frequencies.indices) {
            val amplitude = height * (0.07f + displayedLevel * 0.32f) * (1f - layer * 0.16f)
            buildWavePath(frequencies[layer], phase + layer * 1.7f, amplitude, centerY)

            paint.alpha = 32 - layer * 6
            paint.strokeWidth = height * (0.17f - layer * 0.025f)
            canvas.drawPath(path, paint)

            paint.alpha = 220 - layer * 38
            paint.strokeWidth = height * (0.055f - layer * 0.008f)
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        paint.alpha = 255

        // Keep the layered waves gently flowing while the voice indicator is visible.
        if (visibility == View.VISIBLE && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun buildWavePath(frequency: Float, phase: Float, amplitude: Float, centerY: Float) {
        path.reset()
        var x = 0f
        while (x <= width) {
            val progress = x / width.toFloat()
            val envelope = kotlin.math.sin(Math.PI * progress).toFloat().coerceAtLeast(0f)
            val primary = kotlin.math.sin(progress * Math.PI.toFloat() * 2f * frequency + phase)
            val detail = kotlin.math.sin(progress * Math.PI.toFloat() * 4f - phase * 0.72f) * 0.24f
            val y = centerY + (primary + detail) * amplitude * envelope
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 3f
        }
    }
}
