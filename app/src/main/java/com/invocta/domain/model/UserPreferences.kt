package com.invocta.domain.model

/**
 * User preferences for the assistant
 */
data class UserPreferences(
    val voiceInputEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hapticFeedbackEnabled: Boolean = true,
    val userName: String = "",
    val assistantVoiceSpeed: Float = 1.0f,
    // Wake word detection ("Hey Invocta")
    val wakeWordEnabled: Boolean = false,
    // Onboarding status
    val onboardingCompleted: Boolean = false
)

