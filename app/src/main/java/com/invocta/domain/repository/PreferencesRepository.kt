package com.invocta.domain.repository

import com.invocta.domain.model.ThemeMode
import com.invocta.domain.model.ChatStorageMode
import com.invocta.domain.model.AiProvider
import com.invocta.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user preferences
 */
interface PreferencesRepository {
    fun getPreferences(): Flow<UserPreferences>
    suspend fun updateVoiceInputEnabled(enabled: Boolean)
    suspend fun updateThemeMode(themeMode: ThemeMode)
    suspend fun updateHapticFeedbackEnabled(enabled: Boolean)
    suspend fun updateUserName(name: String)
    suspend fun updateAssistantVoiceSpeed(speed: Float)

    // Wake word detection
    suspend fun updateWakeWordEnabled(enabled: Boolean)

    // Onboarding
    suspend fun updateOnboardingCompleted(completed: Boolean)
    suspend fun updateChatStorageMode(mode: ChatStorageMode)
    suspend fun updateAiProvider(provider: AiProvider)
}

