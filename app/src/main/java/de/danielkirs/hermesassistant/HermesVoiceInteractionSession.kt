package de.danielkirs.hermesassistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Deliberately minimal POC session: every system assistant invocation renders
 * and speaks exactly "OK". No microphone, network, screen capture, or tools.
 */
class HermesVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var shown = false
    private var initialized = false
    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context) { status ->
            initialized = status == TextToSpeech.SUCCESS
            if (initialized) {
                textToSpeech.language = Locale.GERMAN
                if (shown) speakOk()
            }
        }
    }

    override fun onCreateContentView(): View {
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            val padding = (32 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = "OK"
                textSize = 36f
                gravity = Gravity.CENTER
            })
        }
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        shown = true
        if (initialized) speakOk()
    }

    private fun speakOk() {
        textToSpeech.speak("OK", TextToSpeech.QUEUE_FLUSH, null, "hermes-poc-ok")
    }

    override fun onDestroy() {
        textToSpeech.stop()
        textToSpeech.shutdown()
        super.onDestroy()
    }
}
