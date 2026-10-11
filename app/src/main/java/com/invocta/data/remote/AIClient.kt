package com.invocta.data.remote

import com.invocta.data.remote.model.ChatMessage
import com.invocta.domain.tools.ToolCall
import kotlinx.coroutines.flow.Flow

/**
 * Response from AI with potential tool calls
 */
data class AIResponseWithTools(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?
)

/**
 * Interface for AI chat completion operations
 * All AI requests are proxied through the Invocta Server
 */
interface AIClient {
    /**
     * Simple chat request with conversation history (no tools)
     */
    suspend fun chat(
        systemPrompt: String,
        conversationHistory: List<ChatMessage>,
        userMessage: String
    ): Result<String>

    /**
     * Chat request with tool calling support
     * Returns the AI response which may include tool calls
     */
    suspend fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float? = 0.3f
    ): Result<AIResponseWithTools>

    /**
     * Streaming chat request with tool calling support
     * Returns a Flow that emits progressive content updates and completes with tool calls
     * @param onContentDelta Called for each content chunk (for progressive UI updates)
     * @return Flow that completes with the final AIResponseWithTools
     */
    fun chatWithToolsStream(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float? = 0.3f,
        onContentDelta: ((delta: String, accumulated: String) -> Unit)? = null
    ): Flow<StreamingResponse>
}

/**
 * Represents streaming response states
 */
sealed class StreamingResponse {
    /**
     * Intermediate content update during streaming
     */
    data class ContentUpdate(
        val delta: String,
        val accumulated: String
    ) : StreamingResponse()
    
    /**
     * Final complete response (either text or tool calls)
     */
    data class Complete(
        val response: AIResponseWithTools
    ) : StreamingResponse()
    
    /**
     * Error during streaming
     */
    data class Error(
        val message: String,
        val exception: Exception? = null
    ) : StreamingResponse()
}

