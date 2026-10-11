package com.invocta.data.remote

import com.invocta.data.remote.model.ChatMessage
import com.invocta.domain.tools.ToolCall
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiAIClient(private val keyStore: OpenAiKeyStore) : AIClient {
    companion object { const val MODEL = "gpt-6-luna" }

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun chat(systemPrompt: String, conversationHistory: List<ChatMessage>, userMessage: String): Result<String> =
        chatWithTools(systemPrompt, conversationHistory + ChatMessage.user(userMessage), emptyList(), null)
            .map { it.content ?: "" }

    override suspend fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?
    ): Result<AIResponseWithTools> = try {
        val key = keyStore.get()?.takeIf(String::isNotBlank)
            ?: return Result.failure(IllegalStateException("Add your OpenAI API key in Settings."))
        val response = http.post("https://api.openai.com/v1/responses") {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(requestBody(systemPrompt, messages, tools).toString())
        }
        if (!response.status.isSuccess()) {
            Result.failure(IllegalStateException(statusMessage(response.status.value)))
        } else {
            val result = parseResponse(json.parseToJsonElement(response.bodyAsText()).jsonObject)
            if (result.content == null && result.toolCalls.isEmpty())
                Result.failure(IllegalStateException("OpenAI returned no response."))
            else Result.success(result)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.failure(IllegalStateException("Could not connect to OpenAI."))
    }

    override fun chatWithToolsStream(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?,
        onContentDelta: ((delta: String, accumulated: String) -> Unit)?
    ): Flow<StreamingResponse> = flow {
        chatWithTools(systemPrompt, messages, tools, temperature).fold(
            onSuccess = { emit(StreamingResponse.Complete(it)) },
            onFailure = { emit(StreamingResponse.Error(it.message ?: "OpenAI request failed.")) }
        )
    }

    private fun statusMessage(status: Int): String = when (status) {
        400 -> "OpenAI rejected the request. Check your model access and try again."
        401 -> "OpenAI rejected the API key. Check it in Settings."
        403 -> "This OpenAI key cannot access $MODEL."
        429 -> "OpenAI rate limit or quota reached. Check your API usage."
        else -> "OpenAI request failed (HTTP $status)."
    }

    private fun requestBody(systemPrompt: String, messages: List<ChatMessage>, tools: List<Map<String, Any>>) = buildJsonObject {
        put("model", JsonPrimitive(MODEL))
        put("service_tier", JsonPrimitive("default"))
        put("instructions", JsonPrimitive(systemPrompt))
        put("store", JsonPrimitive(false))
        put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        put("reasoning", buildJsonObject {
            put("effort", JsonPrimitive("medium"))
            put("mode", JsonPrimitive("standard"))
            put("summary", JsonPrimitive("auto"))
        })
        put("text", buildJsonObject {
            put("format", buildJsonObject { put("type", JsonPrimitive("text")) })
            put("verbosity", JsonPrimitive("medium"))
        })
        put("parallel_tool_calls", JsonPrimitive(false))
        put("input", buildJsonArray {
            messages.forEach { message ->
                when {
                    message.toolCallId != null -> add(buildJsonObject {
                        put("type", JsonPrimitive("function_call_output"))
                        put("call_id", JsonPrimitive(message.toolCallId))
                        put("output", JsonPrimitive(message.content?.jsonPrimitive?.contentOrNull ?: ""))
                    })
                    message.providerOutputItems != null -> message.providerOutputItems.forEach(::add)
                    message.role == "user" || message.role == "assistant" -> add(buildJsonObject {
                        put("role", JsonPrimitive(message.role))
                        put("content", message.toOpenAiContent())
                    })
                }
            }
        })
        val functions = tools.mapNotNull { (it["function"] as? Map<*, *>)?.let(::functionTool) }
        if (functions.isNotEmpty()) put("tools", JsonArray(functions))
    }

    private fun ChatMessage.toOpenAiContent(): JsonElement = when (val value = content) {
        is JsonArray -> buildJsonArray {
            value.forEach { item ->
                val part = item as? JsonObject ?: return@forEach
                when (part["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> add(buildJsonObject {
                        put("type", JsonPrimitive("input_text"))
                        put("text", part["text"] ?: JsonPrimitive(""))
                    })
                    "image_url" -> {
                        val url = (part["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                        add(buildJsonObject {
                            put("type", JsonPrimitive("input_image"))
                            put("image_url", JsonPrimitive(url))
                        })
                    }
                }
            }
        }
        null -> JsonPrimitive("")
        else -> value
    }

    private fun functionTool(function: Map<*, *>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("name", JsonPrimitive(function["name"]?.toString() ?: ""))
        put("description", JsonPrimitive(function["description"]?.toString() ?: ""))
        put("parameters", anyToJson(function["parameters"]))
        put("strict", JsonPrimitive(false))
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.mapNotNull { (key, item) ->
            (key as? String)?.let { it to anyToJson(item) }
        }.toMap())
        is List<*> -> JsonArray(value.map(::anyToJson))
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun parseResponse(response: JsonObject): AIResponseWithTools {
        val output = response["output"] as? JsonArray ?: JsonArray(emptyList())
        val calls = output.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "function_call") return@mapNotNull null
            val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolCall(callId, name, obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
        }
        val content = output.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") return@mapNotNull null
            (obj["content"] as? JsonArray).orEmpty().mapNotNull { part ->
                val text = part as? JsonObject ?: return@mapNotNull null
                text["text"]?.jsonPrimitive?.contentOrNull ?: text["refusal"]?.jsonPrimitive?.contentOrNull
            }.joinToString("")
        }.joinToString("").takeIf(String::isNotBlank)
        return AIResponseWithTools(content, calls, response["status"]?.jsonPrimitive?.contentOrNull, output)
    }
}
