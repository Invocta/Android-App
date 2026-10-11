package com.invocta.data.repository

import android.util.Log
import com.invocta.data.remote.SupabaseClientProvider
import com.invocta.domain.model.AuthProvider
import com.invocta.domain.model.AuthState
import com.invocta.domain.model.AuthUser
import com.invocta.domain.repository.AuthRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.compose.auth.composeAuth
import io.github.jan.supabase.compose.auth.composable.NativeSignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "AuthRepositoryImpl"

class AuthRepositoryImpl : AuthRepository {

    private val supabase = SupabaseClientProvider.client
    private val auth = supabase.auth
    private val composeAuth = supabase.composeAuth

    override val authState: Flow<AuthState> = auth.sessionStatus.map { status ->
        when (status) {
            is SessionStatus.Authenticated -> {
                val user = status.session.user
                if (user != null) {
                    AuthState.Authenticated(user.toAuthUser())
                } else {
                    AuthState.Unauthenticated
                }
            }
            is SessionStatus.NotAuthenticated -> AuthState.Unauthenticated
            SessionStatus.Initializing -> AuthState.Loading
            is SessionStatus.RefreshFailure -> {
                Log.e(TAG, "Session refresh failed: ${status.cause}")
                AuthState.Error("Session expired. Please sign in again.")
            }
        }
    }

    override suspend fun getCurrentUser(): AuthUser? {
        return try {
            auth.currentUserOrNull()?.toAuthUser()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current user", e)
            null
        }
    }

    override suspend fun getDisplayName(): String? {
        return try {
            val user = auth.currentUserOrNull()
            val metadata = user?.userMetadata
            metadata?.getStringOrNull("display_name")
                ?: metadata?.getStringOrNull("full_name")
                ?: metadata?.getStringOrNull("name")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting display name", e)
            null
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> {
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }

            // After signup, user might need to verify email
            val user = auth.currentUserOrNull()
            if (user != null) {
                Result.success(user.toAuthUser())
            } else {
                Result.success(AuthUser(
                    id = "",
                    email = email,
                    provider = AuthProvider.EMAIL
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }

            val user = auth.currentUserOrNull()
            if (user != null) {
                Result.success(user.toAuthUser())
            } else {
                Result.failure(Exception("Sign in failed - no user returned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    override suspend fun signInWithGoogle(): Result<Unit> {
        // This method is no longer used directly - use the Composable approach instead
        // The actual Google sign-in is handled by rememberSignInWithGoogle() in Compose
        return Result.failure(Exception("Use Compose Auth for Google Sign-In"))
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<AuthUser> {
        return try {
            auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
            }

            val user = auth.currentUserOrNull()
            if (user != null) {
                Result.success(user.toAuthUser(AuthProvider.GOOGLE))
            } else {
                Result.failure(Exception("Google sign in failed - no user returned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google sign in failed", e)
            Result.failure(e)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            // Invalidate token cache first to prevent stale tokens
            SupabaseClientProvider.invalidateTokenCache()
            auth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
            Result.failure(e)
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try {
            auth.resetPasswordForEmail(email)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            Result.failure(e)
        }
    }

    override suspend fun verifyEmailOtp(email: String, otp: String): Result<AuthUser> {
        return try {
            auth.verifyEmailOtp(
                type = OtpType.Email.SIGNUP,
                email = email,
                token = otp
            )

            val user = auth.currentUserOrNull()
            if (user != null) {
                Result.success(user.toAuthUser())
            } else {
                Result.failure(Exception("Verification failed - no user returned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTP verification failed", e)
            Result.failure(e)
        }
    }

    override suspend fun resendEmailOtp(email: String): Result<Unit> {
        return try {
            auth.resendEmail(OtpType.Email.SIGNUP, email)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Resend OTP failed", e)
            Result.failure(e)
        }
    }

    override suspend fun updateDisplayName(name: String): Result<Unit> {
        return try {
            auth.updateUser {
                data = buildJsonObject {
                    put("display_name", name)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Update display name failed", e)
            Result.failure(e)
        }
    }

    override suspend fun isAuthenticated(): Boolean {
        return auth.currentSessionOrNull() != null
    }

    override suspend fun refreshSession(): Result<Unit> {
        return try {
            auth.refreshCurrentSession()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Session refresh failed", e)
            Result.failure(e)
        }
    }

    private fun io.github.jan.supabase.auth.user.UserInfo.toAuthUser(
        provider: AuthProvider = AuthProvider.EMAIL
    ): AuthUser {
        val metadata = this.userMetadata
        return AuthUser(
            id = this.id,
            email = this.email,
            displayName = metadata?.getStringOrNull("display_name")
                ?: metadata?.getStringOrNull("full_name")
                ?: metadata?.getStringOrNull("name"),
            avatarUrl = metadata?.getStringOrNull("avatar_url")
                ?: metadata?.getStringOrNull("picture"),
            provider = when {
                this.appMetadata?.getStringOrNull("provider") == "google" -> AuthProvider.GOOGLE
                else -> provider
            }
        )
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        return this[key]?.jsonPrimitive?.content
    }
}





