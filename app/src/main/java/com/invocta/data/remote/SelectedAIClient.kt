package com.invocta.data.remote

import android.util.Log
import com.invocta.data.remote.model.ChatMessage
import com.invocta.domain.model.AiProvider
import com.invocta.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

class SelectedAIClient(
    private val preferences: PreferencesRepository,
    private val local: AIClient,
    private val studio: AIClient,
    private val openAi: AIClient
) : AIClient {
    private suspend fun selected(): AIClient = when (preferences.getPreferences().first().aiProvider) {
        AiProvider.LOCAL -> {
            Log.i("SelectedAIClient", "Using on-device Gemma")
            local
        }
        AiProvider.GOOGLE_AI_STUDIO -> {
            Log.i("SelectedAIClient", "Using Google AI Studio: ${GoogleAiStudioAIClient.MODEL}")
            studio
        }
        AiProvider.OPENAI -> {
            Log.i("SelectedAIClient", "Using OpenAI: ${OpenAiAIClient.MODEL}")
            openAi
        }
    }

    override suspend fun chat(systemPrompt: String, conversationHistory: List<ChatMessage>, userMessage: String): Result<String> =
        selected().chat(systemPrompt, conversationHistory, userMessage)

    override suspend fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?
    ): Result<AIResponseWithTools> = selected().chatWithTools(systemPrompt, messages, tools, temperature)

    override fun chatWithToolsStream(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?,
        onContentDelta: ((delta: String, accumulated: String) -> Unit)?
    ): Flow<StreamingResponse> = flow {
        emitAll(selected().chatWithToolsStream(systemPrompt, messages, tools, temperature, onContentDelta))
    }
}
