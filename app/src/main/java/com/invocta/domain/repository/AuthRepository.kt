package com.invocta.domain.repository

import com.invocta.domain.model.AuthState
import com.invocta.domain.model.AuthUser
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for authentication operations
 */
interface AuthRepository {

    /**
     * Flow of the current authentication state
     */
    val authState: Flow<AuthState>

    /**
     * Get the current user synchronously (if authenticated)
     */
    suspend fun getCurrentUser(): AuthUser?

    /**
     * Get display name from Supabase user metadata
     */
    suspend fun getDisplayName(): String?

    /**
     * Sign up with email and password
     */
    suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser>

    /**
     * Sign in with email and password
     */
    suspend fun signInWithEmail(email: String, password: String): Result<AuthUser>

    /**
     * Sign in with Google using Supabase OAuth flow
     * This opens a browser for authentication
     */
    suspend fun signInWithGoogle(): Result<Unit>

    /**
     * Sign in with Google using ID Token (for Credential Manager)
     */
    suspend fun signInWithGoogleIdToken(idToken: String): Result<AuthUser>

    /**
     * Sign out the current user
     */
    suspend fun signOut(): Result<Unit>

    /**
     * Send password reset email
     */
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>

    /**
     * Verify email with OTP code
     */
    suspend fun verifyEmailOtp(email: String, otp: String): Result<AuthUser>

    /**
     * Resend OTP to email
     */
    suspend fun resendEmailOtp(email: String): Result<Unit>

    /**
     * Update user's display name
     */
    suspend fun updateDisplayName(name: String): Result<Unit>

    /**
     * Check if user is authenticated
     */
    suspend fun isAuthenticated(): Boolean

    /**
     * Refresh session if needed
     */
    suspend fun refreshSession(): Result<Unit>
}

