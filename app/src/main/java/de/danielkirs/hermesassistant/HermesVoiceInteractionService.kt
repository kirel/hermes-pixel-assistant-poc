package de.danielkirs.hermesassistant

import android.service.voice.VoiceInteractionService

/**
 * Entry point registered with Android's public default-assistant role.
 * Android keeps the selected service ready for system assistant invocations.
 */
class HermesVoiceInteractionService : VoiceInteractionService()
