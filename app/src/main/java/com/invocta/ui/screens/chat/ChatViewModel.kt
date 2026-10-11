package com.invocta.ui.screens.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invocta.data.remote.api.AuthRequiredException
import com.invocta.data.local.ai.LocalGemmaModelManager
import com.invocta.domain.model.AssistantState
import com.invocta.domain.model.AiProvider
import com.invocta.domain.model.ChatSession
import com.invocta.domain.model.ChatStorageMode
import com.invocta.domain.model.Message
import com.invocta.domain.repository.ChatSessionRepository
import com.invocta.domain.repository.PreferencesRepository
import com.invocta.domain.speech.SpeechState
import com.invocta.domain.speech.SpeechToTextService
import com.invocta.domain.speech.TextToSpeechService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ChatViewModel(
    private val chatSessionRepository: ChatSessionRepository,
    private val preferencesRepository: PreferencesRepository,
    private val gemmaModelManager: LocalGemmaModelManager
) : ViewModel() {

    val gemmaModelStatus = gemmaModelManager.status
    val aiProvider: StateFlow<AiProvider> = preferencesRepository.getPreferences()
        .map { it.aiProvider }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiProvider.LOCAL)

    init {
        viewModelScope.launch {
            preferencesRepository.getPreferences()
                .map { it.chatStorageMode ?: ChatStorageMode.LOCAL }
                .distinctUntilChanged()
                .collect {
                    textToSpeechService?.shutdown()
                    textToSpeechService = null
                    speechToTextService?.destroy()
                    speechToTextService = null
                }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // Auth required state - set when operations fail due to missing auth
    private val _authRequired = MutableStateFlow(false)
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    // Unified Speech-to-Text service (Server STT → Native STT fallback)
    private var speechToTextService: SpeechToTextService? = null
    
    private var textToSpeechService: TextToSpeechService? = null
    
    // TTS enabled state (for overlay mode)
    private val _ttsEnabled = MutableStateFlow(false)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()
    
    // TTS speaking state
    val isSpeaking: StateFlow<Boolean> 
        get() = textToSpeechService?.isSpeaking ?: MutableStateFlow(false)

    // Track message IDs that have already been spoken (prevents re-speaking on recomposition)
    private val spokenMessageIds = mutableSetOf<String>()

    // Speech recognition state flows
    private val _partialSpeechResult = MutableStateFlow("")
    val partialSpeechResult: StateFlow<String> = _partialSpeechResult.asStateFlow()

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()
    
    // Flag to auto-send message after transcription completes
    private var sendAfterTranscription = false
    // Flag to create new session before sending (for HomeScreen)
    private var createSessionBeforeSend = false

    // Current session ID
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // Current session
    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    // All sessions for history view
    val allSessions: StateFlow<List<ChatSession>> = chatSessionRepository.getAllSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Messages for current session
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                chatSessionRepository.getMessages(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _assistantState = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // Streaming content - shows partial response while AI is generating
    private val _streamingContent = MutableStateFlow<String?>(null)
    val streamingContent: StateFlow<String?> = _streamingContent.asStateFlow()

    /**
     * Initialize Text-to-Speech service
     */
    fun initializeTextToSpeech(context: Context) {
        if (textToSpeechService == null) {
            viewModelScope.launch {
                val localOnly = preferencesRepository.getPreferences().first().chatStorageMode != ChatStorageMode.CLOUD
                textToSpeechService = TextToSpeechService(context, localOnly).also { it.initialize() }
            }
        }
    }
    
    /**
     * Enable/disable TTS for agent responses (used in overlay mode)
     */
    fun setTtsEnabled(enabled: Boolean) {
        _ttsEnabled.value = enabled
    }
    
    /**
     * Speak text using TTS
     */
    fun speakText(text: String) {
        textToSpeechService?.speak(text)
    }
    
    /**
     * Speak text only if the message hasn't been spoken before.
     * Returns true if the message will be spoken, false if already spoken.
     */
    fun speakTextOnce(messageId: String, text: String): Boolean {
        if (spokenMessageIds.contains(messageId)) {
            return false
        }
        spokenMessageIds.add(messageId)
        textToSpeechService?.speak(text)
        return true
    }
    
    /**
     * Stop TTS playback
     */
    fun stopSpeaking() {
        textToSpeechService?.stop()
    }
    
    /**
     * Shutdown TTS service
     */
    fun shutdownTts() {
        textToSpeechService?.shutdown()
        textToSpeechService = null
    }

    /**
     * Create a new chat session and set it as current
     */
    fun createNewSession() {
        viewModelScope.launch {
            try {
                val session = chatSessionRepository.createSession()
                _currentSessionId.value = session.id
                _currentSession.value = session
                _authRequired.value = false
            } catch (e: AuthRequiredException) {
                Log.w(TAG, "Cannot create session - authentication required")
                _authRequired.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
            }
        }
    }

    /**
     * Load an existing session
     */
    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            loadSessionAsync(sessionId)
        }
    }

    /**
     * Load an existing session (suspending version)
     * Returns true if session was loaded successfully, false if not found
     */
    suspend fun loadSessionAsync(sessionId: String): Boolean {
        val session = chatSessionRepository.getSessionById(sessionId)
        return if (session != null) {
            _currentSessionId.value = session.id
            _currentSession.value = session
            true
        } else {
            android.util.Log.w("ChatViewModel", "Session not found: $sessionId")
            false
        }
    }

    /**
     * Reset the view model state for a new chat
     */
    fun resetForNewChat() {
        _currentSessionId.value = null
        _currentSession.value = null
        _inputText.value = ""
        _assistantState.value = AssistantState.Idle
        _partialSpeechResult.value = ""
    }

    /**
     * Clear just the input text without resetting the session
     */
    fun clearInputOnly() {
        _inputText.value = ""
        _partialSpeechResult.value = ""
    }

    /**
     * Create a new session and immediately send the current input
     */
    fun createNewSessionAndSend() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            _assistantState.value = AssistantState.Processing
            val messageText = _inputText.value
            _inputText.value = ""
            _streamingContent.value = null

            // Create new session
            val session = chatSessionRepository.createSession()
            _currentSessionId.value = session.id
            _currentSession.value = session

            // Send the message with streaming
            val result = chatSessionRepository.processUserInput(
                sessionId = session.id,
                input = text,
                onStreamingContent = { _, accumulated ->
                    _streamingContent.value = accumulated
                }
            )

            // Clear streaming content when done
            _streamingContent.value = null

            result.fold(
                onSuccess = {
                    _assistantState.value = AssistantState.Idle
                    // Refresh session to get updated title
                    _currentSession.value = chatSessionRepository.getSessionById(session.id)
                },
                onFailure = { error ->
                    _assistantState.value = AssistantState.Error(error.message ?: "An error occurred")
                    kotlinx.coroutines.delay(100)
                    _assistantState.value = AssistantState.Idle
                }
            )
        }
    }

    /**
     * Send while listening on home screen - creates new session after transcription
     */
    fun sendWhileListeningNewSession() {
        // Set flags to auto-send and create session once transcription completes
        sendAfterTranscription = true
        createSessionBeforeSend = true
        
        // Stop recording and wait for transcription
        stopVoiceInput()
    }

    /**
     * Delete a session
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatSessionRepository.deleteSession(sessionId)
            // If we deleted the current session, clear it
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _currentSession.value = null
            }
        }
    }

    /**
     * Rename a session
     */
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            chatSessionRepository.updateSessionTitle(sessionId, newTitle)
        }
    }

    /**
     * Delete all sessions
     */
    fun deleteAllSessions() {
        viewModelScope.launch {
            chatSessionRepository.deleteAllSessions()
            _currentSessionId.value = null
            _currentSession.value = null
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        val sessionId = _currentSessionId.value ?: return

        viewModelScope.launch {
            _assistantState.value = AssistantState.Processing
            _inputText.value = ""
            _streamingContent.value = null

            val result = chatSessionRepository.processUserInput(
                sessionId = sessionId,
                input = text,
                onStreamingContent = { _, accumulated ->
                    // Update streaming content on main thread
                    _streamingContent.value = accumulated
                }
            )

            // Clear streaming content when done
            _streamingContent.value = null

            result.fold(
                onSuccess = {
                    _assistantState.value = AssistantState.Idle
                    // Refresh session to get updated title
                    _currentSession.value = chatSessionRepository.getSessionById(sessionId)
                },
                onFailure = { error ->
                    _assistantState.value = AssistantState.Error(error.message ?: "An error occurred")
                    // Reset to idle after showing error
                    delay(100)
                    _assistantState.value = AssistantState.Idle
                }
            )
        }
    }

    fun processVoiceInput(text: String) {
        // Append to existing input text (user may have typed something before using mic)
        val existingText = _inputText.value.trim()
        val combinedText = if (existingText.isNotEmpty()) {
            "$existingText $text"
        } else {
            text
        }
        
        _inputText.value = combinedText
        _partialSpeechResult.value = ""
        
        // Check if user requested to send after transcription
        if (sendAfterTranscription) {
            sendAfterTranscription = false
            if (createSessionBeforeSend) {
                createSessionBeforeSend = false
                createNewSessionAndSend()
            } else {
                sendMessage()
            }
        } else {
            // Just update the text, don't send automatically
            // Reset to idle so user can edit or send
            _assistantState.value = AssistantState.Idle
        }
    }
    
    /**
     * Process voice input and send immediately.
     * Used when user clicks send while listening.
     */
    fun processVoiceInputAndSend(text: String) {
        // Stop speech service first
        speechToTextService?.stopListening()
        
        // Append to existing input text
        val existingText = _inputText.value.trim()
        val combinedText = if (existingText.isNotEmpty()) {
            "$existingText $text"
        } else {
            text
        }
        
        _inputText.value = combinedText
        _partialSpeechResult.value = ""
        sendMessage()
    }

    fun clearConversation() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            chatSessionRepository.clearSessionMessages(sessionId)
        }
    }

    /**
     * Initialize the speech recognition service.
     * Uses unified SpeechToTextService which handles fallback internally:
     * Server STT (gpt-4o-transcribe) → Native Android STT
     * Must be called with a valid context before using voice input.
     */
    fun initializeSpeechRecognition(context: Context) {
        // Check if already initialized
        if (speechToTextService != null) return

        android.util.Log.d("ChatViewModel", "Initializing unified SpeechToTextService")
        viewModelScope.launch {
            val localOnly = preferencesRepository.getPreferences().first().chatStorageMode != ChatStorageMode.CLOUD
            speechToTextService = SpeechToTextService(context, localOnly).apply {
                initialize()
            }
            setupSpeechObservers()
        }
    }
    
    /**
     * Setup observers for SpeechToTextService state flows
     */
    private fun setupSpeechObservers() {
        viewModelScope.launch {
            // Observe speech state changes
            launch {
                speechToTextService?.state?.collect { speechState ->
                    when (speechState) {
                        is SpeechState.Listening -> {
                            _assistantState.value = AssistantState.Listening
                        }
                        is SpeechState.Processing -> {
                            // When we stop listening and start transcribing
                            if (_assistantState.value == AssistantState.Listening) {
                                _assistantState.value = AssistantState.Transcribing
                            }
                        }
                        is SpeechState.Idle -> {
                            // If we were transcribing or listening, transition to Idle
                            // Final result will be handled by processVoiceInput callback
                            when (_assistantState.value) {
                                is AssistantState.Listening,
                                is AssistantState.Transcribing -> {
                                    _assistantState.value = AssistantState.Idle
                                }
                                else -> {
                                    // Don't change if we're Processing a message (sendMessage handles that)
                                }
                            }
                        }
                        is SpeechState.Error -> {
                            _assistantState.value = AssistantState.Error(speechState.message)
                            delay(2000)
                            _assistantState.value = AssistantState.Idle
                        }
                    }
                }
            }

            // Observe partial results
            launch {
                speechToTextService?.partialResult?.collect { partial ->
                    _partialSpeechResult.value = partial
                }
            }

            // Observe sound level
            launch {
                speechToTextService?.soundLevel?.collect { level ->
                    _soundLevel.value = level
                }
            }
        }
    }

    /**
     * Start listening for voice input.
     * Uses unified SpeechToTextService (Server STT with Native fallback).
     */
    fun startVoiceInput() {
        // Reset send flags at the start of new voice input
        sendAfterTranscription = false
        createSessionBeforeSend = false
        
        speechToTextService?.startListening { result ->
            processVoiceInput(result)
        }
    }

    /**
     * Stop listening for voice input.
     * Sets to Transcribing state while waiting for transcription result.
     */
    fun stopVoiceInput() {
        // If we're listening, set to transcribing to show loading state
        if (_assistantState.value == AssistantState.Listening) {
            _assistantState.value = AssistantState.Transcribing
        }
        
        speechToTextService?.stopListening()
        // Don't clear partial result here - it will be processed when transcription completes
    }

    /**
     * Cancel voice input without processing.
     */
    fun cancelVoiceInput() {
        speechToTextService?.cancelListening()
        _partialSpeechResult.value = ""
        
        // Reset send flags
        sendAfterTranscription = false
        createSessionBeforeSend = false
        
        // Always reset to Idle when cancelled
        when (_assistantState.value) {
            is AssistantState.Listening,
            is AssistantState.Transcribing -> {
                _assistantState.value = AssistantState.Idle
            }
            else -> {}
        }
    }
    
    /**
     * Stop recording and send after transcription completes.
     * User clicks send while listening - waits for actual transcription result.
     */
    fun sendWhileListening() {
        // Set flag to auto-send once transcription completes
        sendAfterTranscription = true
        
        // Stop recording and wait for transcription
        // The result will trigger processVoiceInput which will check sendAfterTranscription flag
        stopVoiceInput()
    }

    override fun onCleared() {
        super.onCleared()
        speechToTextService?.destroy()
        speechToTextService = null
        textToSpeechService?.shutdown()
        textToSpeechService = null
    }
}





