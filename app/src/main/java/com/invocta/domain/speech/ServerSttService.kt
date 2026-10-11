package com.invocta.domain.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.invocta.data.remote.api.SpeechApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Server-Proxied STT using Azure OpenAI gpt-4o-transcribe
 * 
 * Features:
 * - Proper punctuation (context-aware)
 * - Automatic language detection
 * - High accuracy for conversational speech
 * - Support for Hindi, English, Marathi, and many more languages
 */
class ServerSttService(private val context: Context) {

    companion object {
        private const val TAG = "ServerSTT"
        
        // Audio recording configuration
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Silence detection thresholds
        private const val SILENCE_THRESHOLD = 500 // Amplitude threshold
        private const val MAX_SILENCE_MS = 2000L // Stop after 2 seconds of silence
        private const val MAX_RECORDING_MS = 30_000L // Max 30 seconds recording
    }

    private val apiClient = SpeechApiClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    
    // Flag to signal recording should stop (used instead of job cancellation)
    @Volatile
    private var stopRecordingRequested = false

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _finalResult = MutableStateFlow("")
    val finalResult: StateFlow<String> = _finalResult.asStateFlow()

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // Configuration
    private var promptContext: String? = null
    private var languageHint: String? = null
    private var onResultCallback: ((String) -> Unit)? = null

    /**
     * Initialize the service
     * Checks if server speech endpoints are available
     */
    suspend fun initialize(): Boolean {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Microphone permission not granted")
            _state.value = SpeechState.Error("Microphone permission required")
            return false
        }

        return try {
            val result = apiClient.getSpeechStatus()
            if (result.isSuccess) {
                val status = result.getOrNull()
                if (status?.enabled == true) {
                    Log.d(TAG, "Server STT ready: model=${status.sttModel}")
                    _isReady.value = true
                    return true
                }
            }
            Log.w(TAG, "Server STT not available")
            _isReady.value = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize server STT", e)
            _isReady.value = false
            false
        }
    }

    /**
     * Set a prompt to improve transcription accuracy
     * Use this to specify proper nouns, acronyms, or context
     * 
     * @param prompt Context hint for the transcription model
     */
    fun setPrompt(prompt: String?) {
        promptContext = prompt
    }

    /**
     * Set language hint for transcription
     * 
     * @param language ISO 639-1 code (e.g., "en", "hi", "mr")
     */
    fun setLanguage(language: String?) {
        languageHint = language
    }

    /**
     * Start listening and recording audio
     * Recording stops automatically after detecting silence
     * 
     * @param onResult Callback when transcription is complete
     */
    fun startListening(onResult: (String) -> Unit) {
        if (_state.value == SpeechState.Listening) {
            Log.w(TAG, "Already listening")
            return
        }

        onResultCallback = onResult
        _partialResult.value = ""
        _finalResult.value = ""
        _state.value = SpeechState.Listening
        stopRecordingRequested = false

        recordingJob = scope.launch {
            try {
                val audioData = recordAudio()
                
                if (audioData.isNotEmpty()) {
                    _state.value = SpeechState.Processing
                    transcribeAudio(audioData)
                } else {
                    Log.d(TAG, "No audio recorded")
                    _state.value = SpeechState.Idle
                }
            } catch (e: CancellationException) {
                // Job was cancelled (e.g., by cancelListening) - don't report as error
                Log.d(TAG, "Recording job cancelled")
                _state.value = SpeechState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Recording/transcription failed", e)
                _state.value = SpeechState.Error(e.message ?: "Unknown error")
            } finally {
                recordingJob = null
            }
        }
    }

    /**
     * Record audio from microphone until silence is detected
     * Returns raw PCM audio data
     */
    private suspend fun recordAudio(): ByteArray = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize * 2, SAMPLE_RATE * 2) // At least 1 second buffer
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            throw e
        }

        val record = audioRecord ?: throw IllegalStateException("Failed to create AudioRecord")
        
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            throw IllegalStateException("AudioRecord initialization failed")
        }

        val audioBuffer = ByteArrayOutputStream()
        val buffer = ShortArray(bufferSize / 2)
        var silenceStartTime = 0L
        val recordingStartTime = System.currentTimeMillis()

        record.startRecording()
        Log.d(TAG, "Recording started")

        try {
            while (isActive && !stopRecordingRequested) {
                val shortsRead = record.read(buffer, 0, buffer.size)
                if (shortsRead <= 0) continue

                // Convert shorts to bytes and write to buffer
                val byteBuffer = ByteBuffer.allocate(shortsRead * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until shortsRead) {
                    byteBuffer.putShort(buffer[i])
                }
                audioBuffer.write(byteBuffer.array())

                // Calculate sound level
                val maxAmplitude = buffer.take(shortsRead).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                val normalizedLevel = (maxAmplitude / 32768f).coerceIn(0f, 1f)
                _soundLevel.value = normalizedLevel

                // Check for silence
                if (maxAmplitude < SILENCE_THRESHOLD) {
                    if (silenceStartTime == 0L) {
                        silenceStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - silenceStartTime > MAX_SILENCE_MS) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        break
                    }
                } else {
                    silenceStartTime = 0L
                }

                // Check max recording time
                if (System.currentTimeMillis() - recordingStartTime > MAX_RECORDING_MS) {
                    Log.d(TAG, "Max recording time reached")
                    break
                }
            }
            
            // Log why we stopped
            if (stopRecordingRequested) {
                Log.d(TAG, "Recording stopped by user request")
            }
        } finally {
            // Only stop if still recording (avoid IllegalStateException from race condition)
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord already stopped")
                }
            }
            try {
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord", e)
            }
            audioRecord = null
            _soundLevel.value = 0f
        }

        val rawData = audioBuffer.toByteArray()
        Log.d(TAG, "Recording complete: ${rawData.size} bytes")
        
        // Convert to WAV format for better API compatibility
        return@withContext createWavFile(rawData)
    }

    /**
     * Convert raw PCM audio to WAV format
     */
    private fun createWavFile(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val totalAudioLen = pcmData.size
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2 // 16-bit

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF header
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            
            // fmt chunk
            put("fmt ".toByteArray())
            putInt(16) // Chunk size
            putShort(1) // Audio format (PCM)
            putShort(channels.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((channels * 2).toShort()) // Block align
            putShort(16) // Bits per sample
            
            // data chunk
            put("data".toByteArray())
            putInt(totalAudioLen)
        }

        return header.array() + pcmData
    }

    /**
     * Send audio to server for transcription
     */
    private suspend fun transcribeAudio(audioData: ByteArray) {
        Log.d(TAG, "Transcribing ${audioData.size} bytes of audio")

        val result = apiClient.speechToText(
            audioData = audioData,
            mimeType = "audio/wav",
            prompt = promptContext,
            language = languageHint
        )

        if (result.isSuccess) {
            val response = result.getOrNull()
            val text = response?.text ?: ""
            
            Log.d(TAG, "Transcription complete: $text")
            _finalResult.value = text
            _state.value = SpeechState.Idle
            
            if (text.isNotBlank()) {
                onResultCallback?.invoke(text)
            }
        } else {
            val error = result.exceptionOrNull()?.message ?: "Transcription failed"
            Log.e(TAG, "Transcription error: $error")
            _state.value = SpeechState.Error(error)
        }
    }

    /**
     * Stop listening immediately and trigger transcription
     * The recording stops but transcription continues with recorded audio
     */
    fun stopListening() {
        Log.d(TAG, "Stopping listening")
        
        // Signal recording loop to stop - transcription will proceed with recorded audio
        stopRecordingRequested = true
        _soundLevel.value = 0f
        
        // Note: Don't cancel the job or stop AudioRecord here
        // The recording loop will exit, cleanup in finally block, and transcription will continue
    }

    /**
     * Cancel listening and discard any audio (no transcription)
     */
    fun cancelListening() {
        Log.d(TAG, "Canceling listening")
        
        // Signal stop first
        stopRecordingRequested = true
        
        // Stop recording
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord", e)
            }
        }
        audioRecord = null
        
        // Cancel the job to prevent transcription
        recordingJob?.cancel()
        recordingJob = null
        
        _state.value = SpeechState.Idle
        _partialResult.value = ""
        _soundLevel.value = 0f
        onResultCallback = null
    }

    /**
     * Check if currently listening
     */
    fun isCurrentlyListening(): Boolean = _state.value == SpeechState.Listening

    /**
     * Release all resources
     */
    fun destroy() {
        cancelListening()
        scope.cancel()
        apiClient.close()
        _isReady.value = false
        Log.d(TAG, "Server STT destroyed")
    }
}
