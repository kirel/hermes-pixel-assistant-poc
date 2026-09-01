package de.danielkirs.hermesassistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required Android recognition-service declaration for a complete
 * VoiceInteractionService. Speech recognition is intentionally out of scope
 * for this POC; assistant invocation always returns OK without listening.
 */
class HermesRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit
}
