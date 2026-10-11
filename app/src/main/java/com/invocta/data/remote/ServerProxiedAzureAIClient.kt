package com.invocta.data.remote

import android.util.Log
import com.invocta.data.remote.api.ApiChatCompletionRequest
import com.invocta.data.remote.api.ApiChatMessageContent
import com.invocta.data.remote.api.ApiFunctionDefinition
import com.invocta.data.remote.api.ApiToolCallInfo
import com.invocta.data.remote.api.ApiToolDefinition
import com.invocta.data.remote.api.InvoctaApiClient
import com.invocta.data.remote.api.StreamEvent
import com.invocta.data.remote.model.ChatMessage
import com.invocta.domain.tools.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Azure AI Client that proxies requests through the Invocta Server
 * This eliminates the need for client-side API keys
 */
class ServerProxiedAzureAIClient(
    private val apiClient: InvoctaApiClient
) : AIClient {

    companion object {
        private const val TAG = "ServerProxiedAI"
    }

    /**
     * Simple chat request with conversation history (no tools)
     * Proxied through the server
     */
    override suspend fun chat(
        systemPrompt: String,
        conversationHistory: List<ChatMessage>,
        userMessage: String
    ): Result<String> {
        val messages = buildList {
            add(ApiChatMessageContent.forSystemOrUser(
                role = "system",
                content = JsonPrimitive(systemPrompt)
            ))
            addAll(conversationHistory.map { it.toApiFormat() })
            add(ApiChatMessageContent.forSystemOrUser(
                role = "user",
                content = JsonPrimitive(userMessage)
            ))
        }

        val request = ApiChatCompletionRequest(
            messages = messages,
            temperature = 0.7f,
            maxCompletionTokens = 800
        )

        return apiClient.chatCompletion(request).map { response ->
            response.choices.firstOrNull()?.message?.content
                ?: "I couldn't generate a response. Please try again."
        }
    }

    /**
     * Chat request with tool calling support
     * Returns the AI response which may include tool calls
     */
    override suspend fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?
    ): Result<AIResponseWithTools> {
        Log.d(TAG, "chatWithTools: ${messages.size} messages, ${tools.size} tools")

        val allMessages = buildList {
            add(ApiChatMessageContent.forSystemOrUser(
                role = "system",
                content = JsonPrimitive(systemPrompt)
            ))
            addAll(messages.map { msg ->
                Log.d(TAG, "Converting message: role=${msg.role}, hasContent=${msg.content != null}, hasToolCalls=${msg.toolCalls != null}")
                msg.toApiFormat()
            })
        }

        // Convert tool definitions to API format
        val apiTools = tools.mapIndexed { index, toolMap ->
            @Suppress("UNCHECKED_CAST")
            val function = toolMap["function"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val parameters = function["parameters"] as Map<String, Any>

            if (index < 3) {
                Log.d(TAG, "Tool[$index]: name=${function["name"]}, params keys=${parameters.keys}")
            }

            ApiToolDefinition(
                type = "function",
                function = ApiFunctionDefinition(
                    name = function["name"] as String,
                    description = function["description"] as String,
                    parameters = mapToJsonElement(parameters)
                )
            )
        }

        Log.d(TAG, "Converted ${apiTools.size} tools")

        val request = ApiChatCompletionRequest(
            messages = allMessages,
            temperature = temperature,
            maxCompletionTokens = 800,
            tools = apiTools,
            toolChoice = "auto"
        )

        return apiClient.chatCompletion(request).map { response ->
            val choice = response.choices.firstOrNull()
            AIResponseWithTools(
                content = choice?.message?.content,
                toolCalls = choice?.message?.toolCalls?.map { tc ->
                    com.invocta.domain.tools.ToolCall(
                        id = tc.id,
                        name = tc.function.name,
                        arguments = tc.function.arguments
                    )
                } ?: emptyList(),
                finishReason = choice?.finishReason
            )
        }
    }

    /**
     * Streaming chat request with tool calling support
     * Returns a Flow that emits progressive content updates and completes with tool calls
     */
    override fun chatWithToolsStream(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?,
        onContentDelta: ((delta: String, accumulated: String) -> Unit)?
    ): Flow<StreamingResponse> = flow {
        Log.d(TAG, "chatWithToolsStream: ${messages.size} messages, ${tools.size} tools")

        val allMessages = buildList {
            add(ApiChatMessageContent.forSystemOrUser(
                role = "system",
                content = JsonPrimitive(systemPrompt)
            ))
            addAll(messages.map { msg -> msg.toApiFormat() })
        }

        // Convert tool definitions to API format
        val apiTools = tools.map { toolMap ->
            @Suppress("UNCHECKED_CAST")
            val function = toolMap["function"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val parameters = function["parameters"] as Map<String, Any>

            ApiToolDefinition(
                type = "function",
                function = ApiFunctionDefinition(
                    name = function["name"] as String,
                    description = function["description"] as String,
                    parameters = mapToJsonElement(parameters)
                )
            )
        }

        val request = ApiChatCompletionRequest(
            messages = allMessages,
            temperature = temperature,
            maxCompletionTokens = 800,
            tools = apiTools,
            toolChoice = "auto",
            stream = true
        )

        // Collect stream events
        apiClient.chatCompletionStream(request).collect { event ->
            when (event) {
                is StreamEvent.ContentDelta -> {
                    // Emit content update and call the callback
                    emit(StreamingResponse.ContentUpdate(event.delta, event.accumulated))
                    onContentDelta?.invoke(event.delta, event.accumulated)
                }
                is StreamEvent.Complete -> {
                    // Convert to AIResponseWithTools
                    val response = AIResponseWithTools(
                        content = event.content,
                        toolCalls = event.toolCalls?.map { tc ->
                            com.invocta.domain.tools.ToolCall(
                                id = tc.id,
                                name = tc.function.name,
                                arguments = tc.function.arguments
                            )
                        } ?: emptyList(),
                        finishReason = event.finishReason
                    )
                    emit(StreamingResponse.Complete(response))
                }
                is StreamEvent.Error -> {
                    emit(StreamingResponse.Error(event.message))
                }
            }
        }
    }

    /**
     * Convert ChatMessage to API format
     * Uses appropriate constructor to avoid sending null tool_calls/tool_call_id
     */
    private fun ChatMessage.toApiFormat(): ApiChatMessageContent {
        val toolCallId = this.toolCallId
        val toolCalls = this.toolCalls
        
        return when {
            // Tool result message (has toolCallId)
            toolCallId != null -> {
                ApiChatMessageContent.forToolResult(
                    toolCallId = toolCallId,
                    result = this.content ?: JsonPrimitive("")
                )
            }
            // Assistant message with tool calls
            toolCalls != null && toolCalls.isNotEmpty() -> {
                ApiChatMessageContent.forAssistantWithToolCalls(
                    content = this.content,
                    toolCalls = toolCalls.map { tc ->
                        ApiToolCallInfo(
                            id = tc.id,
                            type = tc.type,
                            function = com.invocta.data.remote.api.ApiFunctionCallInfo(
                                name = tc.function.name,
                                arguments = tc.function.arguments
                            )
                        )
                    }
                )
            }
            // Regular system/user/assistant message (no tool stuff)
            else -> {
                ApiChatMessageContent.forSystemOrUser(
                    role = this.role,
                    content = this.content ?: JsonPrimitive("")
                )
            }
        }
    }

    /**
     * Convert a Map<String, Any> to JsonElement recursively
     */
    @Suppress("UNCHECKED_CAST")
    private fun mapToJsonElement(map: Map<String, Any>): JsonElement {
        return buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    is Map<*, *> -> {
                        // Check if map contains null values
                        val mapValue = value as Map<String, Any>
                        put(key, mapToJsonElement(mapValue))
                    }
                    is List<*> -> put(key, listToJsonArray(value))
                    null -> {
                        // Skip null values - don't add to JSON
                        Log.w(TAG, "Skipping null value for key: $key")
                    }
                    else -> {
                        Log.d(TAG, "Converting value for key '$key': type=${value::class.simpleName}, value=${value.toString().take(50)}")
                        put(key, JsonPrimitive(value.toString()))
                    }
                }
            }
        }
    }

    /**
     * Convert a List to JsonArray recursively
     */
    @Suppress("UNCHECKED_CAST")
    private fun listToJsonArray(list: List<*>): kotlinx.serialization.json.JsonArray {
        return buildJsonArray {
            list.forEach { item ->
                when (item) {
                    is String -> add(JsonPrimitive(item))
                    is Number -> add(JsonPrimitive(item))
                    is Boolean -> add(JsonPrimitive(item))
                    is Map<*, *> -> add(mapToJsonElement(item as Map<String, Any>))
                    is List<*> -> add(listToJsonArray(item))
                    null -> add(kotlinx.serialization.json.JsonNull)
                    else -> add(JsonPrimitive(item.toString()))
                }
            }
        }
    }
}




