package de.danielkirs.hermesassistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Recognition-service declaration required by the Android assistant role.
 * Interactive turns use the platform SpeechRecognizer from
 * HermesVoiceInteractionSession; direct binds are rejected explicitly.
 */
class HermesRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit
}
