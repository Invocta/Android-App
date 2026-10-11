package com.invocta.assistant

import android.service.voice.VoiceInteractionService

/**
 * Main Voice Interaction Service that handles assistant activation
 * This service is the entry point when the user activates the assistant
 */
class AssistantVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Service is ready to handle voice interactions
    }

    override fun onShutdown() {
        super.onShutdown()
        // Clean up resources
    }
}

