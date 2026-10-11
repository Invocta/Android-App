package com.invocta.domain.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Native Android speech-to-text service that handles voice input without showing
 * the native Android voice recognition modal.
 * 
 * This is used as a fallback when Azure Speech Service is not configured.
 */
class NativeSpeechToTextService(private val context: Context) {

    companion object {
        private const val TAG = "NativeSpeechToText"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _finalResult = MutableStateFlow("")
    val finalResult: StateFlow<String> = _finalResult.asStateFlow()

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

    private var onResultCallback: ((String) -> Unit)? = null

    fun initialize() {
        Log.d(TAG, "Initializing speech recognition, available: ${SpeechRecognizer.isRecognitionAvailable(context)}")
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                Log.d(TAG, "SpeechRecognizer created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer", e)
                _state.value = SpeechState.Error("Failed to initialize: ${e.message}")
            }
        } else {
            Log.e(TAG, "Speech recognition not available")
            _state.value = SpeechState.Error("Speech recognition not available on this device")
        }
    }

    fun startListening(onResult: (String) -> Unit) {
        Log.d(TAG, "startListening called, speechRecognizer: ${speechRecognizer != null}")

        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer is null, trying to reinitialize")
            initialize()
            if (speechRecognizer == null) {
                _state.value = SpeechState.Error("Speech recognizer not available")
                return
            }
        }

        onResultCallback = onResult
        _partialResult.value = ""
        _finalResult.value = ""
        _state.value = SpeechState.Listening

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Don't show any UI
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "startListening intent sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            _state.value = SpeechState.Error("Failed to start speech recognition: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            // Ignore
        }
        _state.value = SpeechState.Idle
    }

    fun cancelListening() {
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            // Ignore
        }
        _state.value = SpeechState.Idle
        _partialResult.value = ""
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        speechRecognizer = null
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                _state.value = SpeechState.Listening
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                _state.value = SpeechState.Listening
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Normalize RMS dB to 0-1 range (typical range is -2 to 10)
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _soundLevel.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Not used
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                _state.value = SpeechState.Processing
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error ($error)"
                }

                Log.e(TAG, "onError: $error - $errorMessage")

                // For no match or timeout, just go back to idle without error
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    _state.value = SpeechState.Idle
                } else {
                    _state.value = SpeechState.Error(errorMessage)
                }
                _soundLevel.value = 0f
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val result = matches?.firstOrNull() ?: ""

                Log.d(TAG, "onResults: $result")

                _finalResult.value = result
                _partialResult.value = ""
                _state.value = SpeechState.Idle
                _soundLevel.value = 0f

                if (result.isNotBlank()) {
                    onResultCallback?.invoke(result)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull() ?: ""
                Log.d(TAG, "onPartialResults: $partial")
                _partialResult.value = partial
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "onEvent: $eventType")
            }
        }
    }
}

sealed class SpeechState {
    data object Idle : SpeechState()
    data object Listening : SpeechState()
    data object Processing : SpeechState()
    data class Error(val message: String) : SpeechState()
}

