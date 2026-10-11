package com.invocta.domain.model

/**
 * Represents the current state of the assistant
 */
sealed class AssistantState {
    data object Idle : AssistantState()
    data object Listening : AssistantState()
    /** Waiting for transcription result after user stopped speaking */
    data object Transcribing : AssistantState()
    data object Processing : AssistantState()
    data object Speaking : AssistantState()
    data class Error(val message: String) : AssistantState()
}

