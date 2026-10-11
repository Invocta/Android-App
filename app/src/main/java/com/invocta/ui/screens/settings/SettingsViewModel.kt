package com.invocta.ui.screens.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invocta.domain.model.ThemeMode
import com.invocta.domain.model.UserPreferences
import com.invocta.domain.repository.AuthRepository
import com.invocta.domain.repository.ChatSessionRepository
import com.invocta.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val chatSessionRepository: ChatSessionRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.getPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPreferences()
        )

    private val _signOutComplete = MutableStateFlow(false)
    val signOutComplete: StateFlow<Boolean> = _signOutComplete.asStateFlow()

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

    fun isAIConfigured(): Boolean {
        // AI is now configured on the server side, so we check server connectivity
        return chatSessionRepository.isAIConfigured()
    }

    fun deleteAllChatHistory() {
        viewModelScope.launch {
            chatSessionRepository.deleteAllSessions()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            // Clear onboarding status so user goes through flow again
            preferencesRepository.updateOnboardingCompleted(false)
            _signOutComplete.value = true
        }
    }
}
