package com.invocta.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents the authenticated user information
 */
@Serializable
data class AuthUser(
    val id: String,
    val email: String?,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val provider: AuthProvider = AuthProvider.EMAIL
)

/**
 * Authentication provider types
 */
enum class AuthProvider {
    EMAIL,
    GOOGLE
}

/**
 * Authentication state
 */
sealed class AuthState {
    data object Loading : AuthState()
    data object Unauthenticated : AuthState()
    data class Authenticated(val user: AuthUser) : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * Onboarding state to track user's progress
 */
enum class OnboardingStep {
    AUTHENTICATION,
    EMAIL_VERIFICATION,
    NAME_INPUT,
    PERMISSIONS,
    COMPLETED
}

