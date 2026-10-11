package com.invocta.data.remote.api

import android.util.Log
import com.invocta.BuildConfig
import com.invocta.data.remote.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * Speech API Client for TTS and STT
 * Uses server-proxied Azure Speech Services:
 * - TTS: Azure Neural voices and MAI-Voice-1 (US English only)
 * - STT: MAI-Transcribe-1 (proper punctuation, context-aware)
 */
class SpeechApiClient {

    companion object {
        private const val TAG = "SpeechApiClient"
        private val BASE_URL = BuildConfig.INVOCTA_API_URL
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient by lazy {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000  // TTS can take a while for long text
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    private suspend fun getAuthToken(): String? {
        return try {
            SupabaseClientProvider.getAuthToken()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get auth token", e)
            null
        }
    }

    // ==================== STATUS ====================

    /**
     * Check if speech endpoints are enabled on the server
     */
    suspend fun getSpeechStatus(): Result<ApiSpeechStatus> {
        return try {
            val token = getAuthToken()
            if (token == null) {
                return Result.failure(AuthRequiredException())
            }

            val response: HttpResponse = httpClient.get("$BASE_URL/speech/status") {
                header("Authorization", "Bearer $token")
            }

            if (response.status.isSuccess()) {
                val apiResponse = response.body<ApiResponse<ApiSpeechStatus>>()
                if (apiResponse.success && apiResponse.data != null) {
                    Result.success(apiResponse.data)
                } else {
                    Result.failure(ApiException(apiResponse.error?.message ?: "Unknown error"))
                }
            } else {
                Result.failure(ApiException("HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSpeechStatus failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get available TTS voices
     */
    suspend fun getVoices(): Result<List<ApiVoiceInfo>> {
        return try {
            val token = getAuthToken()
            if (token == null) {
                return Result.failure(AuthRequiredException())
            }

            val response: HttpResponse = httpClient.get("$BASE_URL/speech/voices") {
                header("Authorization", "Bearer $token")
            }

            if (response.status.isSuccess()) {
                val apiResponse = response.body<ApiResponse<ApiVoicesResponse>>()
                if (apiResponse.success && apiResponse.data != null) {
                    Result.success(apiResponse.data.voices)
                } else {
                    Result.failure(ApiException(apiResponse.error?.message ?: "Unknown error"))
                }
            } else {
                Result.failure(ApiException("HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getVoices failed", e)
            Result.failure(e)
        }
    }

    // ==================== TEXT TO SPEECH ====================

    /**
     * Convert text to speech audio
     * Returns audio data as ByteArray (MP3 by default)
     *
     * @param text The text to convert to speech
     * @param language Language hint for voice selection (e.g., "en-US", "hi-IN") - server auto-selects voice
     * @param format Audio output format (default: MP3 24kHz 48kbps for faster streaming)
     * @param rate Speech rate adjustment (e.g., "+10%", "-20%", "slow", "fast")
     */
    suspend fun textToSpeech(
        text: String,
        language: String? = null,
        format: TtsOutputFormat = TtsOutputFormat.MP3_24K_48K,
        rate: String? = null
    ): Result<ByteArray> {
        return try {
            val token = getAuthToken()
            if (token == null) {
                return Result.failure(AuthRequiredException())
            }

            Log.d(TAG, "TTS request: language=$language, format=${format.apiName}, length=${text.length}")

            val request = ApiTtsRequest(
                input = text,
                voice = null,  // Let server auto-select voice
                responseFormat = format.apiName,
                language = language,
                rate = rate
            )

            val response: HttpResponse = httpClient.post("$BASE_URL/speech/tts") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                // Read audio stream into ByteArray
                val channel = response.bodyAsChannel()
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(chunk)
                    if (bytesRead > 0) {
                        buffer.write(chunk, 0, bytesRead)
                    }
                }
                val audioData = buffer.toByteArray()
                Log.d(TAG, "TTS completed: ${audioData.size} bytes")
                Result.success(audioData)
            } else {
                // Read error as text (might be plain text or JSON depending on error source)
                val errorText = response.bodyAsText()
                Log.e(TAG, "TTS error ${response.status.value}: $errorText")
                Result.failure(ApiException("TTS failed: HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "textToSpeech failed", e)
            Result.failure(e)
        }
    }

    /**
     * Stream text to speech audio
     * Returns a Flow that emits audio chunks as they arrive
     *
     * @param text The text to convert to speech
     * @param language Language hint for voice selection (e.g., "en-US", "hi-IN")
     * @param format Audio output format (default: MP3 24kHz 48kbps for faster streaming)
     * @param rate Speech rate adjustment
     */
    fun textToSpeechStream(
        text: String,
        language: String? = null,
        format: TtsOutputFormat = TtsOutputFormat.MP3_24K_48K,
        rate: String? = null
    ): Flow<ByteArray> = flow {
        val token = getAuthToken()
            ?: throw AuthRequiredException()

        Log.d(TAG, "TTS stream request: language=$language, format=${format.apiName}")

        val request = ApiTtsRequest(
            input = text,
            voice = null,  // Let server auto-select voice
            responseFormat = format.apiName,
            language = language,
            rate = rate
        )

        val response: HttpResponse = httpClient.post("$BASE_URL/speech/tts") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            throw ApiException("TTS request failed: HTTP ${response.status.value}")
        }

        // Stream audio chunks
        val channel = response.bodyAsChannel()
        val chunk = ByteArray(8192)
        while (!channel.isClosedForRead) {
            val bytesRead = channel.readAvailable(chunk)
            if (bytesRead > 0) {
                emit(chunk.copyOf(bytesRead))
            }
        }

        Log.d(TAG, "TTS stream completed")
    }

    // ==================== SPEECH TO TEXT ====================

    /**
     * Transcribe audio to text
     *
     * @param audioData Raw audio data
     * @param mimeType MIME type of the audio (e.g., "audio/mp3", "audio/wav")
     * @param prompt Optional prompt to improve accuracy (proper nouns, context)
     * @param language Language hint (e.g., "en", "hi", "mr")
     */
    suspend fun speechToText(
        audioData: ByteArray,
        mimeType: String = "audio/mp3",
        prompt: String? = null,
        language: String? = null
    ): Result<ApiSttResponse> {
        return try {
            val token = getAuthToken()
            if (token == null) {
                return Result.failure(AuthRequiredException())
            }

            Log.d(TAG, "STT request: size=${audioData.size}, mimeType=$mimeType")

            val response: HttpResponse = httpClient.submitFormWithBinaryData(
                url = "$BASE_URL/speech/stt",
                formData = formData {
                    append("audio", audioData, Headers.build {
                        append(HttpHeaders.ContentType, mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"audio.${getExtension(mimeType)}\"")
                    })
                    prompt?.let { append("prompt", it) }
                    language?.let { append("language", it) }
                    append("response_format", "json")
                }
            ) {
                header("Authorization", "Bearer $token")
            }

            if (response.status.isSuccess()) {
                val apiResponse = response.body<ApiResponse<ApiSttResponse>>()
                if (apiResponse.success && apiResponse.data != null) {
                    Log.d(TAG, "STT completed: ${apiResponse.data.text.take(50)}...")
                    Result.success(apiResponse.data)
                } else {
                    Result.failure(ApiException(apiResponse.error?.message ?: "Unknown error"))
                }
            } else {
                Result.failure(ApiException("HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "speechToText failed", e)
            Result.failure(e)
        }
    }

    private fun getExtension(mimeType: String): String {
        return when (mimeType) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/webm" -> "webm"
            "audio/ogg" -> "ogg"
            "audio/flac" -> "flac"
            else -> "mp3"
        }
    }

    fun close() {
        httpClient.close()
    }
}

/**
 * Exception for speech API errors
 */
class SpeechApiException(message: String) : Exception(message)
