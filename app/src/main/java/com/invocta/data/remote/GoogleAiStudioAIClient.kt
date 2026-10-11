package com.invocta.data.remote

import com.invocta.data.remote.model.ChatMessage
import com.invocta.domain.tools.ToolCall
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import java.util.UUID

class GoogleAiStudioAIClient(private val keyStore: GoogleAiStudioKeyStore) : AIClient {
    companion object {
        const val MODEL = "gemma-4-26b-a4b-it"
        private const val URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun chat(systemPrompt: String, conversationHistory: List<ChatMessage>, userMessage: String): Result<String> =
        chatWithTools(systemPrompt, conversationHistory + ChatMessage.user(userMessage), emptyList(), 0.7f)
            .map { it.content ?: "" }

    override suspend fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        temperature: Float?
    ): Result<AIResponseWithTools> = try {
        val key = keyStore.get()?.takeIf(String::isNotBlank)
            ?: return Result.failure(IllegalStateException("Add your Google AI Studio API key in Settings."))
        val response = postWithServerRetry("$URL:generateContent", key, requestBody(systemPrompt, messages, tools, temperature).toString())
        if (!response.status.isSuccess()) {
            Result.failure(IllegalStateException(statusMessage(response.status.value)))
        } else {
            Result.success(parseResponse(json.parseToJsonElement(response.bodyAsText()).jsonObject))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.failure(IllegalStateException("Could not connect to Google AI Studio."))
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
            onFailure = { emit(StreamingResponse.Error(it.message ?: "Google AI Studio request failed.")) }
        )
    }

    private suspend fun postWithServerRetry(url: String, key: String, body: String): HttpResponse {
        repeat(2) { attempt ->
            val response = http.post(url) {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status.value !in 500..599 || attempt == 1) return response
            response.bodyAsText()
            delay(750)
        }
        error("Unreachable")
    }

    private fun statusMessage(status: Int): String = when (status) {
        400 -> "Google AI Studio rejected the request. Check the model and tool settings."
        401, 403 -> "Google AI Studio rejected the API key. Check it in Settings."
        429 -> "Google AI Studio rate limit reached. Try again later."
        else -> "Google AI Studio request failed (HTTP $status)."
    }

    private fun requestBody(systemPrompt: String, messages: List<ChatMessage>, tools: List<Map<String, Any>>, temperature: Float?) = buildJsonObject {
        put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(systemPrompt)) }) }) })
        put("contents", buildJsonArray {
            val namesById = messages.flatMap { it.toolCalls.orEmpty() }.associate { it.id to it.function.name }
            messages.forEach { message ->
                val parts = buildJsonArray {
                    if (message.toolCallId != null) {
                        add(buildJsonObject { put("functionResponse", buildJsonObject {
                            put("name", JsonPrimitive(namesById[message.toolCallId] ?: "tool"))
                            if (!message.toolCallId.startsWith("local-")) put("id", JsonPrimitive(message.toolCallId))
                            put("response", buildJsonObject { put("result", message.content ?: JsonNull) })
                        }) })
                    } else {
                        when (val value = message.content) {
                            is JsonArray -> value.forEach { part ->
                                val item = part as? JsonObject ?: return@forEach
                                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                                    "text" -> add(buildJsonObject { put("text", item["text"] ?: JsonPrimitive("")) })
                                    "image_url" -> {
                                        val uri = (item["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                                        val mime = uri.substringAfter("data:", "").substringBefore(";")
                                        val data = uri.substringAfter("base64,", "")
                                        if (mime.startsWith("image/") && data.isNotEmpty()) add(buildJsonObject {
                                            put("inlineData", buildJsonObject { put("mimeType", JsonPrimitive(mime)); put("data", JsonPrimitive(data)) })
                                        })
                                    }
                                }
                            }
                            null -> Unit
                            else -> add(buildJsonObject { put("text", value) })
                        }
                        message.toolCalls.orEmpty().forEach { call -> add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", JsonPrimitive(call.function.name))
                                if (!call.id.startsWith("local-")) put("id", JsonPrimitive(call.id))
                                put("args", runCatching { json.parseToJsonElement(call.function.arguments) }.getOrElse { JsonObject(emptyMap()) })
                            })
                            call.thoughtSignature?.let { put("thoughtSignature", JsonPrimitive(it)) }
                        }) }
                    }
                }
                if (parts.isNotEmpty()) add(buildJsonObject {
                    put("role", JsonPrimitive(if (message.role == "assistant") "model" else "user"))
                    put("parts", parts)
                })
            }
        })
        put("generationConfig", buildJsonObject {
            put("temperature", JsonPrimitive(temperature ?: 0.3f))
            put("thinkingConfig", buildJsonObject { put("thinkingLevel", JsonPrimitive("minimal")) })
        })
        val declarations = tools.mapNotNull { (it["function"] as? Map<*, *>)?.let(::mapToJson) }
        if (declarations.isNotEmpty()) put("tools", buildJsonArray { add(buildJsonObject { put("functionDeclarations", JsonArray(declarations)) }) })
    }

    private fun mapToJson(value: Map<*, *>): JsonObject = JsonObject(value.entries.mapNotNull { (key, item) ->
        (key as? String)?.let { it to anyToJson(item) }
    }.toMap())

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> mapToJson(value)
        is List<*> -> JsonArray(value.map(::anyToJson))
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun parseResponse(response: JsonObject): AIResponseWithTools {
        val candidate = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        val text = parts.mapNotNull { part ->
            val obj = part.jsonObject
            if (obj["thought"]?.jsonPrimitive?.contentOrNull == "true") null else obj["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("").takeIf(String::isNotEmpty)
        val calls = parts.mapNotNull { part ->
            val obj = part.jsonObject
            val call = obj["functionCall"] as? JsonObject ?: return@mapNotNull null
            val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolCall(
                id = call["id"]?.jsonPrimitive?.contentOrNull ?: "local-${UUID.randomUUID()}",
                name = name,
                arguments = (call["args"] ?: JsonObject(emptyMap())).toString(),
                thoughtSignature = obj["thoughtSignature"]?.jsonPrimitive?.contentOrNull
            )
        }
        return AIResponseWithTools(text, calls, candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull)
    }
}
