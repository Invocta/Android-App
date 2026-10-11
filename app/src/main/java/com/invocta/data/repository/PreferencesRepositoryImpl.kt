package com.invocta.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.invocta.domain.model.ThemeMode
import com.invocta.domain.model.ChatStorageMode
import com.invocta.domain.model.AiProvider
import com.invocta.domain.model.UserPreferences
import com.invocta.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "invocta_preferences")

class PreferencesRepositoryImpl(
    private val context: Context
) : PreferencesRepository {

    private object PreferencesKeys {
        val VOICE_INPUT_ENABLED = booleanPreferencesKey("voice_input_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("haptic_feedback_enabled")
        val USER_NAME = stringPreferencesKey("user_name")
        val ASSISTANT_VOICE_SPEED = floatPreferencesKey("assistant_voice_speed")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")

        // Onboarding
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val CHAT_STORAGE_MODE = stringPreferencesKey("chat_storage_mode")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
    }

    override fun getPreferences(): Flow<UserPreferences> {
        return context.dataStore.data.map { preferences ->
            UserPreferences(
                voiceInputEnabled = preferences[PreferencesKeys.VOICE_INPUT_ENABLED] ?: true,
                themeMode = preferences[PreferencesKeys.THEME_MODE]?.let {
                    try { ThemeMode.valueOf(it) } catch (e: Exception) { ThemeMode.SYSTEM }
                } ?: ThemeMode.SYSTEM,
                hapticFeedbackEnabled = preferences[PreferencesKeys.HAPTIC_FEEDBACK_ENABLED] ?: true,
                userName = preferences[PreferencesKeys.USER_NAME] ?: "",
                assistantVoiceSpeed = preferences[PreferencesKeys.ASSISTANT_VOICE_SPEED] ?: 1.0f,
                wakeWordEnabled = preferences[PreferencesKeys.WAKE_WORD_ENABLED] ?: false,
                onboardingCompleted = preferences[PreferencesKeys.ONBOARDING_COMPLETED] ?: false,
                aiProvider = preferences[PreferencesKeys.AI_PROVIDER]?.let {
                    runCatching { AiProvider.valueOf(it) }.getOrNull()
                } ?: AiProvider.LOCAL,
                chatStorageMode = preferences[PreferencesKeys.CHAT_STORAGE_MODE]?.let {
                    runCatching { ChatStorageMode.valueOf(it) }.getOrNull()
                }
            )
        }
    }

    override suspend fun updateVoiceInputEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOICE_INPUT_ENABLED] = enabled
        }
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = themeMode.name
        }
    }

    override suspend fun updateHapticFeedbackEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAPTIC_FEEDBACK_ENABLED] = enabled
        }
    }

    override suspend fun updateUserName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USER_NAME] = name
        }
    }

    override suspend fun updateAssistantVoiceSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ASSISTANT_VOICE_SPEED] = speed
        }
    }

    override suspend fun updateOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] = completed
        }
    }

    override suspend fun updateChatStorageMode(mode: ChatStorageMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CHAT_STORAGE_MODE] = mode.name
        }
    }

    override suspend fun updateAiProvider(provider: AiProvider) {
        context.dataStore.edit { it[PreferencesKeys.AI_PROVIDER] = provider.name }
    }

    override suspend fun updateWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKE_WORD_ENABLED] = enabled
        }
    }
}


