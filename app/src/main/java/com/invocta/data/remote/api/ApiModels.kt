package com.invocta.data.remote.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * API Models for Invocta Server communication
 */

// ==================== COMMON ====================

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null
)

@Serializable
data class ApiError(
    val message: String,
    val code: String? = null
)

// ==================== AI CHAT COMPLETION MODELS ====================

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ApiChatCompletionRequest(
    val messages: List<ApiChatMessageContent>,
    val temperature: Float? = null,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @SerialName("top_p")
    val topP: Float? = null,
    @SerialName("frequency_penalty")
    val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Float? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stream: Boolean = false,  // Always encode stream field
    val tools: List<ApiToolDefinition>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null
)

/**
 * Chat message content for API requests.
 *
 * Note: tool_calls and tool_call_id are ONLY included when actually present.
 * The server rejects messages with these fields set to null.
 *
 * Use the factory methods to create appropriate message types:
 * - forSystemOrUser() for system/user messages (no tool fields)
 * - forAssistantWithToolCalls() for assistant messages with tool calls
 * - forToolResult() for tool result messages
 */
@Serializable
data class ApiChatMessageContent(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ApiToolCallInfo>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
) {
    companion object {
        /**
         * Create a system or user message (no tool-related fields)
         */
        fun forSystemOrUser(role: String, content: JsonElement): ApiChatMessageContent {
            return ApiChatMessageContent(role = role, content = content)
        }

        /**
         * Create an assistant message with tool calls
         */
        fun forAssistantWithToolCalls(content: JsonElement?, toolCalls: List<ApiToolCallInfo>): ApiChatMessageContent {
            return ApiChatMessageContent(role = "assistant", content = content, toolCalls = toolCalls)
        }

        /**
         * Create a tool result message
         */
        fun forToolResult(toolCallId: String, result: JsonElement): ApiChatMessageContent {
            return ApiChatMessageContent(role = "tool", content = result, toolCallId = toolCallId)
        }
    }
}

@Serializable
data class ApiToolDefinition(
    val type: String = "function",
    val function: ApiFunctionDefinition
)

@Serializable
data class ApiFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
data class ApiToolCallInfo(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCallInfo
)

@Serializable
data class ApiFunctionCallInfo(
    val name: String,
    val arguments: String
)

@Serializable
data class ApiChatCompletionResponse(
    val id: String,
    @SerialName("object")
    val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ApiChoice>,
    val usage: ApiUsage? = null
)

@Serializable
data class ApiChoice(
    val index: Int,
    val message: ApiResponseMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ApiResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ApiToolCallInfo>? = null
)

@Serializable
data class ApiUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

// ==================== STREAMING MODELS ====================

/**
 * Server-Sent Events (SSE) streaming chunk from OpenAI
 * Each chunk contains a delta of the response being built
 */
@Serializable
data class ApiStreamChunk(
    val id: String? = null,
    @SerialName("object")
    val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ApiStreamChoice>? = null,
    val usage: ApiUsage? = null
)

@Serializable
data class ApiStreamChoice(
    val index: Int = 0,
    val delta: ApiStreamDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ApiStreamDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ApiStreamToolCall>? = null
)

/**
 * Tool call chunk in streaming mode
 * Tool calls arrive progressively - index identifies which call, 
 * and function.name/arguments may come in separate chunks
 */
@Serializable
data class ApiStreamToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ApiStreamFunctionCall? = null
)

@Serializable
data class ApiStreamFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

// ==================== SPEECH MODELS (TTS/STT) ====================

/**
 * Available TTS voices - Azure Neural and MAI-Voice-1
 * MAI-Voice-1 voices (en-US only) offer enhanced style/emotion support
 * Neural voices provide standard high-quality speech for all languages
 */
enum class TtsVoice(val displayName: String, val description: String, val recommended: Boolean = false) {
    // MAI-Voice-1 voices (en-US only, enhanced style support)
    EN_US_IRIS_MAI("Iris", "Female, many styles (US)", true),
    EN_US_JUNE_MAI("June", "Female, professional (US)"),
    EN_US_GRANT_MAI("Grant", "Male, many styles (US)", true),
    EN_US_JASPER_MAI("Jasper", "Male, various styles (US)"),
    // Standard Neural voices - US English
    EN_US_JENNY("Jenny", "Female, friendly (US)"),
    EN_US_ARIA("Aria", "Female, professional (US)"),
    EN_US_SARA("Sara", "Female, warm (US)"),
    EN_US_GUY("Guy", "Male (US)"),
    EN_US_DAVIS("Davis", "Male, deep (US)"),
    // Standard Neural voices - UK English
    EN_GB_SONIA("Sonia", "Female (UK)"),
    EN_GB_RYAN("Ryan", "Male (UK)"),
    EN_GB_LIBBY("Libby", "Female (UK)"),
    // Standard Neural voices - Indian English
    EN_IN_NEERJA("Neerja", "Female (India)", true),
    EN_IN_PRABHAT("Prabhat", "Male (India)"),
    // Standard Neural voices - Hindi
    HI_IN_SWARA("Swara", "Female (Hindi)"),
    HI_IN_MADHUR("Madhur", "Male (Hindi)"),
    // Standard Neural voices - Marathi
    MR_IN_AAROHI("Aarohi", "Female (Marathi)"),
    MR_IN_MANOHAR("Manohar", "Male (Marathi)");
    
    val apiName: String get() = when (this) {
        EN_US_IRIS_MAI -> "en-US-Iris:MAI-Voice-1"
        EN_US_JUNE_MAI -> "en-US-June:MAI-Voice-1"
        EN_US_GRANT_MAI -> "en-US-Grant:MAI-Voice-1"
        EN_US_JASPER_MAI -> "en-US-Jasper:MAI-Voice-1"
        EN_US_JENNY -> "en-US-JennyNeural"
        EN_US_ARIA -> "en-US-AriaNeural"
        EN_US_SARA -> "en-US-SaraNeural"
        EN_US_GUY -> "en-US-GuyNeural"
        EN_US_DAVIS -> "en-US-DavisNeural"
        EN_GB_SONIA -> "en-GB-SoniaNeural"
        EN_GB_RYAN -> "en-GB-RyanNeural"
        EN_GB_LIBBY -> "en-GB-LibbyNeural"
        EN_IN_NEERJA -> "en-IN-NeerjaNeural"
        EN_IN_PRABHAT -> "en-IN-PrabhatNeural"
        HI_IN_SWARA -> "hi-IN-SwaraNeural"
        HI_IN_MADHUR -> "hi-IN-MadhurNeural"
        MR_IN_AAROHI -> "mr-IN-AarohiNeural"
        MR_IN_MANOHAR -> "mr-IN-ManoharNeural"
    }
}

/**
 * TTS output formats - Azure Speech Service formats
 * Use MP3_24K_48K for faster streaming with good quality
 */
enum class TtsOutputFormat(val mimeType: String) {
    MP3_24K_48K("audio/mpeg"),  // Faster streaming, good quality (recommended)
    MP3_24K_96K("audio/mpeg"),
    MP3_24K_160K("audio/mpeg"),
    MP3_48K_192K("audio/mpeg"),
    OGG_24K_OPUS("audio/ogg"),
    PCM_24K("audio/wav");
    
    val apiName: String get() = when (this) {
        MP3_24K_48K -> "audio-24khz-48kbitrate-mono-mp3"
        MP3_24K_96K -> "audio-24khz-96kbitrate-mono-mp3"
        MP3_24K_160K -> "audio-24khz-160kbitrate-mono-mp3"
        MP3_48K_192K -> "audio-48khz-192kbitrate-mono-mp3"
        OGG_24K_OPUS -> "ogg-24khz-16bit-mono-opus"
        PCM_24K -> "riff-24khz-16bit-mono-pcm"
    }
}

/**
 * Text-to-Speech request
 */
@Serializable
data class ApiTtsRequest(
    val input: String,
    val voice: String? = null,  // Optional - let server auto-select based on language
    @SerialName("response_format")
    val responseFormat: String = "audio-24khz-48kbitrate-mono-mp3",  // Faster streaming
    val language: String? = null,  // Language hint for voice selection (e.g., "en-US", "hi-IN")
    val rate: String? = null
)

/**
 * Speech-to-Text response
 */
@Serializable
data class ApiSttResponse(
    val text: String,
    val language: String? = null,
    val duration: Float? = null
)

/**
 * Speech status response
 */
@Serializable
data class ApiSpeechStatus(
    val enabled: Boolean,
    val ttsModel: String,
    val sttModel: String,
    val defaultVoice: String
)

/**
 * TTS voice info from API
 */
@Serializable
data class ApiVoiceInfo(
    val id: String,
    val name: String,
    val description: String,
    val recommended: Boolean = false
)

/**
 * Voices list response
 */
@Serializable
data class ApiVoicesResponse(
    val voices: List<ApiVoiceInfo>
)
