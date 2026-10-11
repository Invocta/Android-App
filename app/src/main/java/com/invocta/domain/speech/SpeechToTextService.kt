package com.invocta.domain.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified Speech-to-Text service with priority fallback:
 * 
 * 1. Server STT (gpt-4o-transcribe) - Best quality, proper punctuation
 * 2. Native Android STT - Fallback when server is unavailable
 * 
 * Server STT provides:
 * - ChatGPT-like transcription with proper punctuation
 * - Context-aware capitalization
 * - Automatic language detection
 * - Support for Hindi, English, Marathi, and 50+ languages
 */
class SpeechToTextService(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToText"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverStt: ServerSttService? = null
    private var nativeStt: NativeSpeechToTextService? = null

    // Current STT provider in use
    private enum class SttProvider { SERVER, NATIVE }
    private var currentProvider = SttProvider.NATIVE

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

    /**
     * Initialize the STT service.
     * Tries Server STT first, falls back to Native STT.
     */
    fun initialize() {
        scope.launch {
            // Try Server STT first (best quality)
            Log.d(TAG, "Trying Server STT (gpt-4o-transcribe)...")
            serverStt = ServerSttService(context)
            if (serverStt?.initialize() == true) {
                currentProvider = SttProvider.SERVER
                _isReady.value = true
                Log.d(TAG, "✅ Using Server STT (ChatGPT-quality, proper punctuation)")
                observeServerState()
                return@launch
            } else {
                Log.w(TAG, "Server STT not available, falling back to Native STT")
                serverStt?.destroy()
                serverStt = null
            }

            // Fall back to Native STT
            initializeNativeStt()
        }
    }

    private fun initializeNativeStt() {
        currentProvider = SttProvider.NATIVE
        nativeStt = NativeSpeechToTextService(context).apply {
            initialize()
        }
        observeNativeState()
        _isReady.value = true
        Log.d(TAG, "✅ Using Native STT (fallback)")
    }

    private fun observeServerState() {
        scope.launch {
            serverStt?.state?.collect { _state.value = it }
        }
        scope.launch {
            serverStt?.partialResult?.collect { _partialResult.value = it }
        }
        scope.launch {
            serverStt?.finalResult?.collect { _finalResult.value = it }
        }
        scope.launch {
            serverStt?.soundLevel?.collect { _soundLevel.value = it }
        }
    }

    private fun observeNativeState() {
        scope.launch {
            nativeStt?.state?.collect { _state.value = it }
        }
        scope.launch {
            nativeStt?.partialResult?.collect { _partialResult.value = it }
        }
        scope.launch {
            nativeStt?.finalResult?.collect { _finalResult.value = it }
        }
        scope.launch {
            nativeStt?.soundLevel?.collect { _soundLevel.value = it }
        }
    }

    /**
     * Start listening for speech input.
     * 
     * @param onResult Callback when transcription is complete
     */
    fun startListening(onResult: (String) -> Unit) {
        Log.d(TAG, "Starting listening with ${currentProvider.name} STT")
        
        when (currentProvider) {
            SttProvider.SERVER -> serverStt?.startListening(onResult)
            SttProvider.NATIVE -> nativeStt?.startListening(onResult)
        }
    }

    /**
     * Stop listening and finalize transcription.
     */
    fun stopListening() {
        when (currentProvider) {
            SttProvider.SERVER -> serverStt?.stopListening()
            SttProvider.NATIVE -> nativeStt?.stopListening()
        }
    }

    /**
     * Cancel listening and discard any partial results.
     */
    fun cancelListening() {
        when (currentProvider) {
            SttProvider.SERVER -> serverStt?.cancelListening()
            SttProvider.NATIVE -> nativeStt?.cancelListening()
        }
    }

    /**
     * Set languages for auto-detection.
     * Server STT handles language detection automatically.
     * 
     * @param languages List of language codes (e.g., "en-US", "hi-IN")
     */
    fun setDetectionLanguages(languages: List<String>) {
        when (currentProvider) {
            SttProvider.SERVER -> {
                // Server STT handles language detection automatically
                // Set a language hint if needed
                if (languages.isNotEmpty()) {
                    serverStt?.setLanguage(languages.first().split("-").first())
                }
            }
            SttProvider.NATIVE -> {
                // Native STT uses system locale
            }
        }
    }

    /**
     * Set a prompt to improve transcription accuracy.
     * Use this to specify proper nouns, acronyms, or context.
     * 
     * @param prompt Context hint for the transcription model
     */
    fun setPrompt(prompt: String?) {
        when (currentProvider) {
            SttProvider.SERVER -> serverStt?.setPrompt(prompt)
            SttProvider.NATIVE -> {
                // Native STT doesn't support prompts
            }
        }
    }

    /**
     * Check if currently listening.
     */
    fun isCurrentlyListening(): Boolean {
        return when (currentProvider) {
            SttProvider.SERVER -> serverStt?.isCurrentlyListening() ?: false
            SttProvider.NATIVE -> _state.value == SpeechState.Listening
        }
    }

    /**
     * Release all resources.
     */
    fun destroy() {
        serverStt?.destroy()
        serverStt = null
        nativeStt?.destroy()
        nativeStt = null
        _isReady.value = false
        Log.d(TAG, "STT destroyed")
    }

    /**
     * Check which STT provider is being used (for debugging/logging).
     */
    fun getCurrentProvider(): String = currentProvider.name

    /**
     * Check if using Server STT (best quality).
     */
    fun isUsingServerStt(): Boolean = currentProvider == SttProvider.SERVER
}
