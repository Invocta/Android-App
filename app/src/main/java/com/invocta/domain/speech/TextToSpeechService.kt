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
 * Unified Text-to-Speech service with priority fallback:
 * 
 * 1. Server TTS (Azure Speech Service) - High quality Neural voices
 * 2. Native Android TTS - Fallback when server is unavailable
 * 
 * Server TTS provides:
 * - Azure Neural voices for natural speech
 * - MAI-Voice-1 voices for enhanced US English
 * - Multi-language support (English, Hindi, Marathi)
 */
class TextToSpeechService(private val context: Context) {

    companion object {
        private const val TAG = "TextToSpeech"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var serverTts: ServerTtsService? = null
    private var nativeTts: NativeTextToSpeechService? = null
    
    // Current TTS provider in use
    private enum class TtsProvider { SERVER, NATIVE }
    private var currentProvider = TtsProvider.NATIVE

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Initialize the TTS service.
     * Tries Server TTS first, falls back to Native TTS.
     */
    fun initialize() {
        scope.launch {
            // Try Server TTS first (best quality)
            Log.d(TAG, "Trying Server TTS (Azure Neural)...")
            serverTts = ServerTtsService(context)
            if (serverTts?.initialize() == true) {
                currentProvider = TtsProvider.SERVER
                _isReady.value = true
                Log.d(TAG, "✅ Using Server TTS (Azure Neural voices)")
                observeServerSpeakingState()
                return@launch
            } else {
                Log.w(TAG, "Server TTS not available, falling back to Native TTS")
                serverTts = null
            }

            // Fall back to Native TTS
            initializeNativeTts()
        }
    }
    
    private fun initializeNativeTts() {
        currentProvider = TtsProvider.NATIVE
        nativeTts = NativeTextToSpeechService(context).apply {
            initialize()
        }
        _isReady.value = true
        Log.d(TAG, "✅ Using Native TTS (fallback)")
    }
    
    private fun observeServerSpeakingState() {
        scope.launch {
            serverTts?.isSpeaking?.collect { speaking ->
                _isSpeaking.value = speaking
            }
        }
    }

    /**
     * Speak the given text using the best available TTS.
     */
    fun speak(text: String, utteranceId: String = "agent_response") {
        Log.d(TAG, "Speaking with ${currentProvider.name} TTS")
        
        when (currentProvider) {
            TtsProvider.SERVER -> {
                _isSpeaking.value = true
                serverTts?.speak(text, utteranceId)
            }
            TtsProvider.NATIVE -> {
                nativeTts?.speak(text, utteranceId)
            }
        }
    }

    /**
     * Set the voice language.
     * For Server TTS, this affects voice selection.
     * No-op for Native TTS (uses system default).
     */
    fun setLanguage(languageCode: String) {
        // Server TTS handles language automatically based on text
        // Native TTS uses system locale
    }

    /**
     * Stop any ongoing speech.
     */
    fun stop() {
        when (currentProvider) {
            TtsProvider.SERVER -> serverTts?.stop()
            TtsProvider.NATIVE -> nativeTts?.stop()
        }
        _isSpeaking.value = false
    }

    /**
     * Release all resources.
     */
    fun shutdown() {
        serverTts?.shutdown()
        serverTts = null
        nativeTts?.shutdown()
        nativeTts = null
        currentProvider = TtsProvider.NATIVE
        _isReady.value = false
        _isSpeaking.value = false
        Log.d(TAG, "TTS shutdown")
    }

    /**
     * Check if currently speaking.
     */
    fun isSpeakingNow(): Boolean {
        return when (currentProvider) {
            TtsProvider.SERVER -> serverTts?.isSpeakingNow() ?: false
            TtsProvider.NATIVE -> nativeTts?.isSpeakingNow() ?: false
        }
    }
    
    /**
     * Check which TTS provider is being used (for debugging/logging).
     */
    fun getCurrentProvider(): String = currentProvider.name
    
    /**
     * Check if using Server TTS (best quality).
     */
    fun isUsingServerTts(): Boolean = currentProvider == TtsProvider.SERVER
}

