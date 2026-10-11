package com.invocta.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallInfo>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val providerOutputItems: JsonArray? = null
) {
    companion object {
        fun system(content: String) = ChatMessage("system", JsonPrimitive(content))
        fun user(content: String) = ChatMessage("user", JsonPrimitive(content))
        fun assistant(content: String) = ChatMessage("assistant", JsonPrimitive(content))
        fun assistantWithToolCalls(content: String?, toolCalls: List<ToolCallInfo>, providerOutputItems: JsonArray? = null) =
            ChatMessage("assistant", content?.let { JsonPrimitive(it) }, toolCalls, providerOutputItems = providerOutputItems)
        fun toolResult(toolCallId: String, result: String) =
            ChatMessage("tool", JsonPrimitive(result), toolCallId = toolCallId)

        fun userWithImage(text: String, base64Image: String, mimeType: String = "image/png"): ChatMessage {
            val parts = kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text))
                })
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", kotlinx.serialization.json.buildJsonObject {
                        put("url", JsonPrimitive("data:$mimeType;base64,$base64Image"))
                        put("detail", JsonPrimitive("high"))
                    })
                })
            }
            return ChatMessage("user", parts)
        }
    }
}

@Serializable
data class ToolCallInfo(
    val id: String,
    val type: String = "function",
    val function: FunctionCallInfo,
    val thoughtSignature: String? = null
)

@Serializable
data class FunctionCallInfo(val name: String, val arguments: String)
