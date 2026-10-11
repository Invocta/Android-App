package com.invocta.data.remote.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Azure OpenAI Chat Completion API models
 * Based on Azure OpenAI REST API specification
 * Supports tool/function calling
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatCompletionRequest(
    val messages: List<ChatMessage>,
    val model: String? = null, // Required for Foundry API, optional for classic Azure OpenAI
    val temperature: Float? = null, // null = use model default (required for gpt-5-mini)
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int = 800,
    @SerialName("top_p")
    val topP: Float? = null, // null = use model default
    @SerialName("frequency_penalty")
    val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Float? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null // "auto", "none", or specific tool
)

/**
 * Chat message that supports both text-only and multimodal (text + image) content.
 * For vision requests, use contentParts instead of content.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCallInfo>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun system(content: String) = ChatMessage(
            role = "system",
            content = kotlinx.serialization.json.JsonPrimitive(content)
        )

        fun user(content: String) = ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive(content)
        )

        fun assistant(content: String) = ChatMessage(
            role = "assistant",
            content = kotlinx.serialization.json.JsonPrimitive(content)
        )

        fun assistantWithToolCalls(content: String?, toolCalls: List<ToolCallInfo>) =
            ChatMessage(
                role = "assistant",
                content = content?.let { kotlinx.serialization.json.JsonPrimitive(it) },
                toolCalls = toolCalls
            )

        fun toolResult(toolCallId: String, result: String) =
            ChatMessage(
                role = "tool",
                content = kotlinx.serialization.json.JsonPrimitive(result),
                toolCallId = toolCallId
            )

        /**
         * Create a user message with both text and an image (base64 encoded).
         * Used for vision-based screen reading.
         */
        fun userWithImage(text: String, base64Image: String, mimeType: String = "image/png"): ChatMessage {
            val contentParts = kotlinx.serialization.json.buildJsonArray {
                // Text part
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                    put("text", kotlinx.serialization.json.JsonPrimitive(text))
                })
                // Image part
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("image_url"))
                    put("image_url", kotlinx.serialization.json.buildJsonObject {
                        put("url", kotlinx.serialization.json.JsonPrimitive("data:$mimeType;base64,$base64Image"))
                        put("detail", kotlinx.serialization.json.JsonPrimitive("high"))
                    })
                })
            }
            return ChatMessage(role = "user", content = contentParts)
        }
    }
}

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
data class ToolCallInfo(
    val id: String,
    val type: String = "function",
    val function: FunctionCallInfo
)

@Serializable
data class FunctionCallInfo(
    val name: String,
    val arguments: String
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object")
    val objectType: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ResponseMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCallInfo>? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

@Serializable
data class AzureErrorResponse(
    val error: AzureError
)

@Serializable
data class AzureError(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null
)

