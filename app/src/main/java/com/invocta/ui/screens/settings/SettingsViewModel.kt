package com.invocta.ui.screens.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invocta.domain.model.ThemeMode
import com.invocta.domain.model.ChatStorageMode
import com.invocta.domain.model.UserPreferences
import com.invocta.domain.repository.AuthRepository
import com.invocta.domain.repository.ChatSessionRepository
import com.invocta.domain.repository.PreferencesRepository
import com.invocta.data.local.ai.LocalGemmaModelManager
import com.invocta.data.remote.GoogleAiStudioKeyStore
import com.invocta.data.remote.OpenAiKeyStore
import com.invocta.domain.model.AiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val chatSessionRepository: ChatSessionRepository,
    private val authRepository: AuthRepository,
    private val gemmaModelManager: LocalGemmaModelManager,
    private val googleKeyStore: GoogleAiStudioKeyStore,
    private val openAiKeyStore: OpenAiKeyStore
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.getPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPreferences()
        )

    private val _signOutComplete = MutableStateFlow(false)
    val signOutComplete: StateFlow<Boolean> = _signOutComplete.asStateFlow()
    private val _signInRequired = MutableStateFlow(false)
    val signInRequired: StateFlow<Boolean> = _signInRequired.asStateFlow()
    private val _storageModeError = MutableStateFlow<String?>(null)
    val storageModeError: StateFlow<String?> = _storageModeError.asStateFlow()

    fun consumeSignInRequired() {
        _signInRequired.value = false
    }

    fun updateVoiceInputEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateVoiceInputEnabled(enabled)
        }
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.updateThemeMode(themeMode)
        }
    }

    fun updateHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateHapticFeedbackEnabled(enabled)
        }
    }

    fun updateWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateWakeWordEnabled(enabled)
        }
    }

    val gemmaModelStatus = gemmaModelManager.status

    private val _googleKeyConfigured = MutableStateFlow(!googleKeyStore.get().isNullOrBlank())
    val googleKeyConfigured: StateFlow<Boolean> = _googleKeyConfigured.asStateFlow()

    fun saveGoogleKey(key: String) {
        if (key.isBlank()) return
        googleKeyStore.save(key)
        _googleKeyConfigured.value = true
    }

    fun removeGoogleKey() {
        googleKeyStore.clear()
        _googleKeyConfigured.value = false
        viewModelScope.launch {
            if (preferencesRepository.getPreferences().first().aiProvider == AiProvider.GOOGLE_AI_STUDIO)
                preferencesRepository.updateAiProvider(AiProvider.LOCAL)
        }
    }

    fun selectGoogleAiStudio() {
        if (!_googleKeyConfigured.value) return
        viewModelScope.launch { preferencesRepository.updateAiProvider(AiProvider.GOOGLE_AI_STUDIO) }
    }

    private val _openAiKeyConfigured = MutableStateFlow(!openAiKeyStore.get().isNullOrBlank())
    val openAiKeyConfigured: StateFlow<Boolean> = _openAiKeyConfigured.asStateFlow()

    fun saveOpenAiKey(key: String) {
        if (key.isBlank()) return
        openAiKeyStore.save(key)
        _openAiKeyConfigured.value = true
    }

    fun removeOpenAiKey() {
        openAiKeyStore.clear()
        _openAiKeyConfigured.value = false
        viewModelScope.launch {
            if (preferencesRepository.getPreferences().first().aiProvider == AiProvider.OPENAI)
                preferencesRepository.updateAiProvider(AiProvider.LOCAL)
        }
    }

    fun selectOpenAi() {
        if (!_openAiKeyConfigured.value) return
        viewModelScope.launch { preferencesRepository.updateAiProvider(AiProvider.OPENAI) }
    }

    fun downloadGemmaModel() { viewModelScope.launch { gemmaModelManager.startDownload() } }
    fun cancelGemmaDownload() { viewModelScope.launch { gemmaModelManager.cancelDownload() } }
    fun selectGemmaModel() { viewModelScope.launch {
        gemmaModelManager.useModel()
        preferencesRepository.updateAiProvider(AiProvider.LOCAL)
    } }
    fun deleteGemmaModel() { viewModelScope.launch { gemmaModelManager.deleteModel() } }

    fun deleteAllChatHistory() {
        viewModelScope.launch {
            chatSessionRepository.deleteAllSessions()
        }
    }

    fun setChatStorageMode(mode: ChatStorageMode) {
        viewModelScope.launch {
            val needsSignIn = mode == ChatStorageMode.CLOUD && !authRepository.isAuthenticated()
            _storageModeError.value = null
            if (needsSignIn) {
                preferencesRepository.updateChatStorageMode(mode)
                _signInRequired.value = true
            } else {
                try {
                    val currentMode = preferencesRepository.getPreferences().first().chatStorageMode
                    if (mode == ChatStorageMode.CLOUD && currentMode != ChatStorageMode.CLOUD) {
                        chatSessionRepository.syncLocalChatsToCloud()
                    }
                    preferencesRepository.updateChatStorageMode(mode)
                    _signInRequired.value = false
                } catch (e: Exception) {
                    _storageModeError.value = "Could not sync local chats. They are still saved on this device."
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            preferencesRepository.updateChatStorageMode(ChatStorageMode.LOCAL)
            authRepository.signOut()
            _signOutComplete.value = true
        }
    }
}
