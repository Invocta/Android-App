package com.invocta.data.remote.api

import android.util.Log
import com.invocta.BuildConfig
import com.invocta.data.remote.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * API Client for Invocta Server
 * Handles all communication with the backend server including sessions, messages, and AI proxy
 */
class InvoctaApiClient {

    companion object {
        private const val TAG = "InvoctaApiClient"
        // API URL is configured in local.properties or uses default production URL
        private val BASE_URL = BuildConfig.INVOCTA_API_URL
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true   // Encode all properties including defaults (needed for tool type field)
        explicitNulls = false   // Don't serialize null values
    }

    private val httpClient by lazy {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    /**
     * OkHttp-based client for SSE streaming
     * The Android engine buffers entire responses, OkHttp properly supports streaming
     */
    private val streamingClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000  // 5 min for long streaming responses
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 300_000
            }
        }
    }

    /**
     * Get the current Supabase auth token, waiting for auth to be ready.
     * This ensures we don't return null while auth is still loading from storage.
     */
    private suspend fun getAuthToken(): String? {
        return try {
            // Use the provider's method that waits for auth to be ready
            SupabaseClientProvider.getAuthToken()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get auth token", e)
            null
        }
    }

    // ==================== AI ENDPOINT ====================

    /**
     * Send a chat completion request through the server (proxied to Azure AI)
     */
    suspend fun chatCompletion(request: ApiChatCompletionRequest): Result<ApiChatCompletionResponse> {
        return try {
            val token = getAuthToken()
            if (token == null) {
                Log.w(TAG, "Auth required: chatCompletion - user not authenticated")
                return Result.failure(AuthRequiredException())
            }

            // Debug: Log the full request being sent
            val requestJson = json.encodeToString(ApiChatCompletionRequest.serializer(), request)
            Log.d(TAG, "Chat completion request (messages count): ${request.messages.size}")
            Log.d(TAG, "Chat completion request (tools count): ${request.tools?.size ?: 0}")
            
            // Log first and last part of request to see tools
            if (requestJson.length > 2000) {
                Log.d(TAG, "Request start: ${requestJson.take(1500)}")
                Log.d(TAG, "Request end: ${requestJson.takeLast(500)}")
            } else {
                Log.d(TAG, "Full request: $requestJson")
            }

            // Send the pre-serialized JSON string directly to ensure exact format
            val response: HttpResponse = httpClient.post("$BASE_URL/ai/chat/completions") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(requestJson)  // Send as pre-serialized string
            }

            if (response.status.isSuccess()) {
                val apiResponse = response.body<ApiResponse<ApiChatCompletionResponse>>()
                if (apiResponse.success && apiResponse.data != null) {
                    Result.success(apiResponse.data)
                } else {
                    Result.failure(ApiException(apiResponse.error?.message ?: "AI request failed"))
                }
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Chat completion failed: ${response.status.value} - $errorBody")

                // Try to parse error response
                val errorMessage = try {
                    val apiResponse = json.decodeFromString<ApiResponse<Any>>(errorBody)
                    apiResponse.error?.message ?: "AI request failed"
                } catch (e: Exception) {
                    "AI request failed: ${response.status.value}"
                }
                Result.failure(ApiException(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat completion error", e)
            Result.failure(ApiException("Network error: ${e.message}"))
        }
    }

    /**
     * Stream chat completion via Server-Sent Events (SSE)
     * Returns a Flow of streaming events for progressive response display
     */
    fun chatCompletionStream(request: ApiChatCompletionRequest): Flow<StreamEvent> = callbackFlow {
        val token = getAuthToken()
        if (token == null) {
            Log.w(TAG, "Auth required: chatCompletionStream - user not authenticated")
            trySend(StreamEvent.Error("Not authenticated"))
            close()
            return@callbackFlow
        }

        // Enable streaming in request
        val streamRequest = request.copy(stream = true)
        val requestJson = json.encodeToString(ApiChatCompletionRequest.serializer(), streamRequest)
        
        Log.d(TAG, "Starting streaming chat completion (messages: ${request.messages.size}, tools: ${request.tools?.size ?: 0})")

        try {
            // Prepare the streaming request using OkHttp client (proper SSE support)
            val statement: HttpStatement = streamingClient.preparePost("$BASE_URL/ai/chat/completions") {
                header("Authorization", "Bearer $token")
                header("Accept", "text/event-stream")
                header("Accept-Encoding", "identity") // Disable compression - SSE doesn't work with gzip
                contentType(ContentType.Application.Json)
                setBody(requestJson)
            }

            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "Stream error: ${response.status.value} - $errorBody")
                    trySend(StreamEvent.Error("Stream failed: ${response.status.value}"))
                    return@execute
                }

                // Accumulators for building the complete response
                val contentBuilder = StringBuilder()
                val toolCallsAccumulator = mutableMapOf<Int, AccumulatedToolCall>()
                var finishReason: String? = null

                // Read the SSE stream
                val channel = response.bodyAsChannel()
                var lineCount = 0
                var dataLineCount = 0
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    lineCount++
                    
                    // Skip empty lines and comments
                    if (line.isBlank() || line.startsWith(":")) continue
                    
                    // Parse SSE data lines
                    if (line.startsWith("data: ")) {
                        dataLineCount++
                        val data = line.removePrefix("data: ").trim()
                        
                        // Check for stream end
                        if (data == "[DONE]") {
                            Log.d(TAG, "Stream completed after $lineCount lines, $dataLineCount data lines")
                            break
                        }

                        // Parse the chunk
                        try {
                            val chunk = json.decodeFromString<ApiStreamChunk>(data)
                            val choice = chunk.choices?.firstOrNull()
                            val delta = choice?.delta
                            
                            // Debug log first few chunks
                            if (dataLineCount <= 3) {
                                Log.d(TAG, "Chunk $dataLineCount: delta.content=${delta?.content}, delta.role=${delta?.role}")
                            }
                            
                            // Handle content delta
                            delta?.content?.let { content ->
                                contentBuilder.append(content)
                                trySend(StreamEvent.ContentDelta(content, contentBuilder.toString()))
                            }
                            
                            // Handle tool call deltas
                            delta?.toolCalls?.forEach { toolCallDelta ->
                                val index = toolCallDelta.index
                                val accumulated = toolCallsAccumulator.getOrPut(index) {
                                    AccumulatedToolCall(
                                        id = toolCallDelta.id ?: "",
                                        name = "",
                                        arguments = StringBuilder()
                                    )
                                }
                                
                                // Update with new data
                                toolCallDelta.id?.let { accumulated.id = it }
                                toolCallDelta.function?.name?.let { accumulated.name = it }
                                toolCallDelta.function?.arguments?.let { accumulated.arguments.append(it) }
                            }
                            
                            // Capture finish reason
                            choice?.finishReason?.let { finishReason = it }
                            
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse chunk: $data", e)
                            // Continue processing - some chunks may be malformed
                        }
                    }
                }

                // Build final tool calls list
                val finalToolCalls = toolCallsAccumulator.values
                    .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                    .map { ApiToolCallInfo(
                        id = it.id,
                        type = "function",
                        function = ApiFunctionCallInfo(
                            name = it.name,
                            arguments = it.arguments.toString()
                        )
                    )}

                // Send completion event
                trySend(StreamEvent.Complete(
                    content = contentBuilder.toString().takeIf { it.isNotEmpty() },
                    toolCalls = finalToolCalls.takeIf { it.isNotEmpty() },
                    finishReason = finishReason
                ))
                
                Log.d(TAG, "Stream finished: content=${contentBuilder.length} chars, toolCalls=${finalToolCalls.size}, lines=$lineCount, dataLines=$dataLineCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            trySend(StreamEvent.Error("Stream error: ${e.message}"))
        }
        
        close()
        awaitClose { }
    }

    // ==================== HEALTH ENDPOINT ====================

    /**
     * Check if the server is healthy and ready
     */
    suspend fun checkHealth(): Result<Boolean> {
        return try {
            val response: HttpResponse = httpClient.get("$BASE_URL/health/ready")
            Result.success(response.status.isSuccess())
        } catch (e: Exception) {
            Log.e(TAG, "Health check error", e)
            Result.failure(ApiException("Server unavailable"))
        }
    }

    fun close() {
        httpClient.close()
    }
}

/**
 * Events emitted during streaming chat completion
 */
sealed class StreamEvent {
    /**
     * Content delta - emitted for each text chunk
     * @param delta The new text chunk
     * @param accumulated All content received so far
     */
    data class ContentDelta(
        val delta: String,
        val accumulated: String
    ) : StreamEvent()
    
    /**
     * Stream completed successfully
     * @param content Final accumulated content (null if only tool calls)
     * @param toolCalls List of tool calls (null if text response)
     * @param finishReason Why the stream finished (stop, tool_calls, length, etc.)
     */
    data class Complete(
        val content: String?,
        val toolCalls: List<ApiToolCallInfo>?,
        val finishReason: String?
    ) : StreamEvent()
    
    /**
     * Error occurred during streaming
     */
    data class Error(val message: String) : StreamEvent()
}

/**
 * Helper class to accumulate tool call data from streaming chunks
 */
private data class AccumulatedToolCall(
    var id: String,
    var name: String,
    val arguments: StringBuilder
)

open class ApiException(message: String) : Exception(message)

/**
 * Exception thrown when an API call requires authentication but user is not logged in.
 * User must sign in to use the app - all data is synced with the server.
 */
class AuthRequiredException : ApiException("Authentication required - please sign in to continue")

