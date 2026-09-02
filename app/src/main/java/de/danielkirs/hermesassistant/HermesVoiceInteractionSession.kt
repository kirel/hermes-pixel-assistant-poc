package de.danielkirs.hermesassistant

import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
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
import java.util.ArrayDeque
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
    private lateinit var sheetPanel: LinearLayout
    private lateinit var rootContainer: FrameLayout
    private lateinit var textToSpeech: TextToSpeech

    private var activeAgentText: TextView? = null
    private var activeRunId: String? = null
    private var steeringPending: PendingMessage? = null
    private val pendingMessages = ArrayDeque<PendingMessage>()
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsInitialized = false
    private var submitted = false
    private var uiDismissed = false
    private var pendingSpeech: String? = null
    private var lastPartialText = ""
    private var dragStartY = 0f
    private var dragStartTranslationY = 0f
    private var rootTouchStartY = 0f
    private var isDraggingSheet = false
    private var initialListeningDeadlineMs = 0L
    private var recognitionGeneration = 0
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
        uiDismissed = false
        sheetPanel.animate().cancel()
        sheetPanel.translationY = 0f
        submitted = false
        lastPartialText = ""
        pendingSpeech = null
        activeAgentText = null
        activeRunId = null
        steeringPending = null
        pendingMessages.clear()
        desiredChatHeightPx = 0
        historyOffset = 0
        historyLoading = false
        historyExhausted = false
        initialHistoryRendered = false
        chatTouchedByUser = false
        deferredHistory.clear()
        transcript.text = ""
        transcript.visibility = View.VISIBLE
        messages.removeAllViews()
        messageScroll.visibility = View.GONE
        waveform.setLevel(0f)
        textToSpeech.stop()
        startRecognition()
        loadHistoryPage(initial = true)
    }

    private fun requestFreshRecognition() {
        stopRecognition()
        status.text = "Ich höre zu — lass dir Zeit"
        rootContainer.postDelayed({
            if (!submitted && !uiDismissed) startRecognition()
        }, 140)
    }

    private fun stopRecognition() {
        recognitionGeneration += 1
        speechRecognizer?.let { recognizer ->
            try { recognizer.cancel() } catch (_: Exception) { }
            try { recognizer.destroy() } catch (_: Exception) { }
        }
        speechRecognizer = null
    }

    private fun startRecognition(continueInitialWindow: Boolean = false) {
        if (submitted) return
        if (!continueInitialWindow) {
            initialListeningDeadlineMs = SystemClock.elapsedRealtime() + 6_000L
        }
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
        status.text = "Ich höre zu — lass dir Zeit"
        stopRecognition()
        val generation = ++recognitionGeneration
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (generation != recognitionGeneration) return
                    if (!uiDismissed && !submitted) status.text = "Verarbeite Sprache …"
                }
                override fun onRmsChanged(rmsdB: Float) {
                    if (generation != recognitionGeneration) return
                    if (!uiDismissed) waveform.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (generation != recognitionGeneration) return
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) lastPartialText = text
                    if (!uiDismissed && !text.isNullOrBlank()) transcript.text = text
                }
                override fun onResults(results: Bundle?) {
                    if (generation != recognitionGeneration) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.ifBlank { null } ?: lastPartialText.ifBlank { null }
                    if (text == null) {
                        if (!uiDismissed) status.text = "Ich habe nichts verstanden"
                    } else submitToHermes(text, activeConnection, spoken = true)
                }
                override fun onError(error: Int) {
                    if (generation != recognitionGeneration || submitted || uiDismissed) return
                    val retryableInitialError = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastPartialText.isNotBlank()) {
                        submitToHermes(lastPartialText, activeConnection, spoken = true)
                    } else if (retryableInitialError && SystemClock.elapsedRealtime() < initialListeningDeadlineMs) {
                        // Google recognizers may apply a very short initial-silence timeout.
                        // Keep the listening window alive without making the user restart the gesture.
                        status.text = "Ich höre noch zu …"
                        stopRecognition()
                        rootContainer.postDelayed({
                            if (!submitted && !uiDismissed) startRecognition(continueInitialWindow = true)
                        }, 90)
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
        if (text.isBlank()) return
        val connection = connectionStore.load()
        if (connection == null) {
            status.text = "Hermes-Verbindung bitte in der App einrichten"
            return
        }
        composer.text?.clear()
        if (submitted || pendingMessages.isNotEmpty() || steeringPending != null) {
            enqueueMessage(text, spoken = false)
            if (!submitted) startNextQueuedMessage()
        } else {
            submitToHermes(text, connection, spoken = false)
        }
    }

    private fun submitToHermes(
        text: String,
        connection: HermesConnection,
        spoken: Boolean,
        queuedMessage: PendingMessage? = null
    ) {
        if (submitted) return
        submitted = true
        stopRecognition()
        waveform.setLevel(0f)
        transcript.text = ""
        status.text = "Hermes denkt nach …"
        if (queuedMessage == null) {
            addUserBubble(text, spoken)
        } else {
            queuedMessage.state.text = "Wird gesendet …"
            queuedMessage.steerButton.visibility = View.GONE
        }
        activeAgentText = addAgentBubble().apply { this.text = "…" }

        HermesRunClient(connection).start(text, object : HermesRunListener {
            private val streamed = StringBuilder()

            override fun onStarted(runId: String) {
                postToUi { activeRunId = runId }
            }

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
                    activeRunId = null
                    if (finalOutput.isNotBlank()) speakFinal(finalOutput)
                    rootContainer.postDelayed({ startNextQueuedMessage() }, 120)
                }
            }

            override fun onFailed(message: String) {
                postToUi {
                    status.text = message
                    submitted = false
                    activeRunId = null
                    waveform.setLevel(0f)
                    rootContainer.postDelayed({ startNextQueuedMessage() }, 120)
                }
            }
        })
    }

    private fun enqueueMessage(text: String, spoken: Boolean) {
        val bubble = addUserBubble(text, spoken, queued = true) ?: return
        val pending = PendingMessage(text, spoken, bubble.state, bubble.steerButton)
        bubble.steerButton.setOnClickListener { steerPendingMessage(pending) }
        pendingMessages.addLast(pending)
        status.text = "Hermes arbeitet — Nachricht vorgemerkt"
    }

    private fun startNextQueuedMessage() {
        if (submitted || steeringPending != null || pendingMessages.isEmpty()) return
        val next = pendingMessages.removeFirst()
        val connection = connectionStore.load()
        if (connection == null) {
            next.state.text = "Verbindung erforderlich"
            next.steerButton.visibility = View.GONE
            status.text = "Hermes-Verbindung bitte in der App einrichten"
            return
        }
        submitToHermes(next.text, connection, next.spoken, queuedMessage = next)
    }

    private fun steerPendingMessage(pending: PendingMessage) {
        val runId = activeRunId
        val connection = connectionStore.load()
        if (runId == null || connection == null || !submitted || steeringPending != null) return
        steeringPending = pending
        pending.state.text = "Wird als Hinweis ergänzt …"
        pending.steerButton.isEnabled = false
        HermesRunClient(connection).steer(runId, pending.text) { accepted, message ->
            postToUi {
                steeringPending = null
                if (accepted) {
                    pendingMessages.remove(pending)
                    pending.state.text = message
                    pending.steerButton.visibility = View.GONE
                } else {
                    pending.state.text = "Wartet"
                    pending.steerButton.isEnabled = true
                }
                if (!submitted) startNextQueuedMessage()
            }
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
        transcript.visibility = View.GONE
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
        stopRecognition()
        super.onHide()
    }

    override fun onDestroy() {
        // Do not call the Hermes stop endpoint here; accepted remote work must survive UI teardown.
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
        val bars = 21
        displayedLevel += (targetLevel - displayedLevel) * 0.22f
        if (kotlin.math.abs(targetLevel - displayedLevel) > 0.01f) postInvalidateOnAnimation()
        val gap = width / (bars * 2f)
        val centerY = height / 2f
        val baseHeight = height * 0.15f
        for (index in 0 until bars) {
            val distance = kotlin.math.abs(index - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val envelope = 1f - distance * 0.55f
            val barHeight = baseHeight + displayedLevel * height * 0.76f * envelope
            paint.color = Color.argb((150 + 105 * envelope).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            paint.strokeWidth = gap * 0.72f
            val x = gap + index * gap * 2f
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }
    }
}
