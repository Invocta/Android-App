package com.invocta.data.local.ai

import android.util.Base64
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.invocta.data.remote.AIClient
import com.invocta.data.remote.AIResponseWithTools
import com.invocta.data.remote.StreamingResponse
import com.invocta.data.remote.model.ChatMessage
import com.invocta.domain.tools.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class LocalGemmaAIClient(private val modelManager: LocalGemmaModelManager) : AIClient {
    private val generationMutex = Mutex()
    override suspend fun chat(
        systemPrompt: String,
        conversationHistory: List<ChatMessage>,
        userMessage: String
    ): Result<String> = runCatching {
        generationMutex.withLock {
            modelManager.withSelectedEngine { engine ->
                var history = conversationHistory.map { it.toLiteRtMessage() }
                var response: String? = null
                while (response == null) {
                    try {
                        response = engine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(systemPrompt),
                                initialMessages = history,
                                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                                automaticToolCalling = false
                            )
                        ).use { it.sendMessage(userMessage).toString() }
                    } catch (error: Exception) {
                        if (!error.isContextLimitError() || history.isEmpty()) throw error
                        history = if (history.size > 2) history.takeLast(2) else emptyList()
                    }
                }
                response
            }
        }
    }.onFailure { if (it is CancellationException) throw it }

    override suspend fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?
    ): Result<AIResponseWithTools> = runCatching {
        val result = generate(systemPrompt, messages, tools, temperature).toList().lastOrNull()
            ?: error("Gemma returned no response.")
        result.toAppResponse()
    }.onFailure { if (it is CancellationException) throw it }

    override fun chatWithToolsStream(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?,
        onContentDelta: ((delta: String, accumulated: String) -> Unit)?
    ): Flow<StreamingResponse> = flow {
        try {
            val chunks = StringBuilder()
            var finalMessage: Message? = null
            generate(systemPrompt, messages, tools, temperature).collect { message ->
                finalMessage = message
                val delta = message.toString()
                if (delta.isNotEmpty()) {
                    chunks.append(delta)
                    onContentDelta?.invoke(delta, chunks.toString())
                    emit(StreamingResponse.ContentUpdate(delta, chunks.toString()))
                }
            }
            val response = finalMessage?.toAppResponse() ?: AIResponseWithTools(null, emptyList(), null)
            emit(StreamingResponse.Complete(response.copy(content = chunks.toString().takeIf(String::isNotBlank) ?: response.content)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emit(StreamingResponse.Error(error.message ?: "Gemma could not generate a response.", error))
        }
    }

    private fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        toolSpecs: List<Map<String, Any>>,
        temperature: Float?
    ): Flow<Message> = flow {
        require(messages.isNotEmpty()) { "A message is required." }
        generationMutex.withLock {
            modelManager.withSelectedEngine { engine ->
                val toolNamesByCallId = messages.flatMap { it.toolCalls.orEmpty() }.associate { it.id to it.function.name }
                val openApiTools = toolSpecs.mapNotNull { spec ->
                    val function = spec["function"] as? Map<*, *> ?: return@mapNotNull null
                    val name = function["name"] as? String ?: return@mapNotNull null
                    val definition = buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("description", JsonPrimitive(function["description"] as? String ?: ""))
                        put("parameters", function["parameters"].toJsonElement())
                    }
                    tool(object : OpenApiTool {
                        override fun getToolDescriptionJsonString() = Json.encodeToString(JsonObject.serializer(), definition)
                        override fun execute(paramsJsonString: String): String =
                            error("Device tools must be executed by the permission-aware ToolExecutor.")
                    })
                }
                var history = messages.dropLast(1).map { it.toLiteRtMessage(toolNamesByCallId) }
                val last = messages.last().toLiteRtMessage(toolNamesByCallId)
                while (true) {
                    try {
                        engine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(systemPrompt),
                                initialMessages = history,
                                tools = openApiTools,
                                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = (temperature ?: 0.3f).toDouble()),
                                automaticToolCalling = false
                            )
                        ).use { conversation ->
                            callbackFlow {
                                val completed = AtomicBoolean(false)
                                try {
                                    conversation.sendMessageAsync(last, object : MessageCallback {
                                        override fun onMessage(message: Message) { trySend(message) }
                                        override fun onDone() { completed.set(true); close() }
                                        override fun onError(throwable: Throwable) { close(throwable) }
                                    })
                                } catch (error: Exception) {
                                    close(error)
                                }
                                awaitClose { if (!completed.get()) runCatching { conversation.cancelProcess() } }
                            }.collect { emit(it) }
                        }
                        break
                    } catch (error: Exception) {
                        if (!error.isContextLimitError() || history.isEmpty()) throw error
                        // shortcut: retry with only recent context; add summarization when long chats need continuity.
                        history = if (history.size > 2) history.takeLast(2) else emptyList()
                    }
                }
            }
        }
    }

    private fun Throwable.isContextLimitError(): Boolean =
        generateSequence(this) { it.cause }.any { it.message?.contains("Input token ids are too long") == true }

    private fun ChatMessage.toLiteRtMessage(toolNamesById: Map<String, String> = emptyMap()): Message {
        if (toolCallId != null) {
            return Message.tool(Contents.of(Content.ToolResponse(toolNamesById[toolCallId] ?: "tool", content.asText())))
        }
        val parts = when (val value = content) {
            is JsonArray -> value.mapNotNull { part ->
                val objectPart = part as? JsonObject ?: return@mapNotNull null
                when ((objectPart["type"] as? JsonPrimitive)?.content) {
                    "text" -> Content.Text(objectPart["text"].asText())
                    "image_url" -> {
                        val url = ((objectPart["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val encoded = url.substringAfter("base64,", "")
                        if (encoded.isEmpty()) null else Content.ImageBytes(Base64.decode(encoded, Base64.DEFAULT))
                    }
                    else -> null
                }
            }
            else -> listOf(Content.Text(value.asText()))
        }
        val contents = Contents.of(parts)
        val liteRtCalls = toolCalls.orEmpty().map { call ->
            val arguments = runCatching { Json.parseToJsonElement(call.function.arguments).toKotlinValue() as? Map<String, Any?> }.getOrNull().orEmpty()
            com.google.ai.edge.litertlm.ToolCall(call.function.name, arguments)
        }
        return when (role) {
            "system" -> Message.system(contents)
            "assistant" -> Message.model(contents, liteRtCalls)
            else -> Message.user(contents)
        }
    }

    private fun Message.toAppResponse() = AIResponseWithTools(
        content = toString().takeIf(String::isNotBlank),
        toolCalls = toolCalls.map { call ->
            ToolCall(UUID.randomUUID().toString(), call.name, Json.encodeToString(JsonObject.serializer(), call.arguments.toJsonObject()))
        },
        finishReason = if (toolCalls.isEmpty()) "stop" else "tool_calls"
    )

    private fun JsonElement?.asText(): String = when (this) {
        null -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonPrimitive("")
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        is JsonObject -> entries.associate { it.key to it.value.toKotlinValue() }
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive -> when {
            isString -> content
            content == "true" || content == "false" -> content.toBoolean()
            content.toLongOrNull() != null -> content.toLong()
            content.toDoubleOrNull() != null -> content.toDouble()
            else -> content
        }
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(entries.associate { it.key to it.value.toJsonElement() })
}
