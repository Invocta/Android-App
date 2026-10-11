package com.invocta.ui.screens.onboarding

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invocta.domain.model.AuthState
import com.invocta.domain.model.AuthUser
import com.invocta.domain.model.OnboardingStep
import com.invocta.domain.repository.AuthRepository
import com.invocta.domain.repository.PreferencesRepository
import io.github.jan.supabase.compose.auth.composable.NativeSignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "OnboardingViewModel"

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.AUTHENTICATION,
    val isLoading: Boolean = false,
    val error: String? = null,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSignUp: Boolean = true,
    val userName: String = "",
    val emailSent: Boolean = false,
    val isOnboardingComplete: Boolean = false,
    val otpCode: String = "",
    val otpResent: Boolean = false
)

class OnboardingViewModel(
    private val authRepository: AuthRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    val authState = authRepository.authState

    init {
        checkOnboardingStatus()
        observeAuthState()
    }

    private fun checkOnboardingStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val preferences = preferencesRepository.getPreferences().first()
                val isAuthenticated = authRepository.isAuthenticated()

                // Get display name from Supabase (source of truth)
                val supabaseDisplayName = if (isAuthenticated) {
                    authRepository.getDisplayName()
                } else null

                // Sync to local preferences if Supabase has name but local doesn't
                if (supabaseDisplayName != null && preferences.userName.isBlank()) {
                    preferencesRepository.updateUserName(supabaseDisplayName)
                }

                // Check if user has a name (either from Supabase or local)
                val hasName = supabaseDisplayName?.isNotBlank() == true || preferences.userName.isNotBlank()
                val effectiveName = supabaseDisplayName ?: preferences.userName

                when {
                    // Fully onboarded
                    isAuthenticated && preferences.onboardingCompleted -> {
                        _uiState.value = _uiState.value.copy(
                            isOnboardingComplete = true,
                            userName = effectiveName,
                            isLoading = false
                        )
                    }
                    // Authenticated with name - skip to permissions
                    isAuthenticated && hasName && !preferences.onboardingCompleted -> {
                        _uiState.value = _uiState.value.copy(
                            currentStep = OnboardingStep.PERMISSIONS,
                            userName = effectiveName,
                            isLoading = false
                        )
                    }
                    // Authenticated but no name - show name input
                    isAuthenticated && !hasName && !preferences.onboardingCompleted -> {
                        _uiState.value = _uiState.value.copy(
                            currentStep = OnboardingStep.NAME_INPUT,
                            userName = "",
                            isLoading = false
                        )
                    }
                    // Not authenticated
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            currentStep = OnboardingStep.AUTHENTICATION,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking onboarding status", e)
                _uiState.value = _uiState.value.copy(
                    currentStep = OnboardingStep.AUTHENTICATION,
                    isLoading = false
                )
            }
        }
    }

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email, error = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun updateConfirmPassword(confirmPassword: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = confirmPassword, error = null)
    }

    fun updateUserName(name: String) {
        _uiState.value = _uiState.value.copy(userName = name, error = null)
    }

    fun toggleAuthMode() {
        _uiState.value = _uiState.value.copy(
            isSignUp = !_uiState.value.isSignUp,
            error = null,
            password = "",
            confirmPassword = ""
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun updateOtpCode(code: String) {
        // Only allow digits and limit to 8 characters
        val filteredCode = code.filter { it.isDigit() }.take(8)
        _uiState.value = _uiState.value.copy(otpCode = filteredCode, error = null)
    }

    fun verifyOtp() {
        val state = _uiState.value

        if (state.otpCode.length != 8) {
            _uiState.value = state.copy(error = "Please enter the 8-digit code")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            authRepository.verifyEmailOtp(state.email, state.otpCode)
                .onSuccess { user ->
                    Log.d(TAG, "Email verified successfully")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentStep = OnboardingStep.NAME_INPUT,
                        otpCode = ""
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "OTP verification failed", e)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Invalid verification code"
                    )
                }
        }
    }

    fun resendOtp() {
        val state = _uiState.value

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            authRepository.resendEmailOtp(state.email)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        otpResent = true,
                        error = null
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to resend code"
                    )
                }
        }
    }

    fun signUpWithEmail() {
        val state = _uiState.value

        // Validation
        if (state.email.isBlank()) {
            _uiState.value = state.copy(error = "Email is required")
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(state.email).matches()) {
            _uiState.value = state.copy(error = "Invalid email format")
            return
        }
        if (state.password.length < 6) {
            _uiState.value = state.copy(error = "Password must be at least 6 characters")
            return
        }
        if (state.password != state.confirmPassword) {
            _uiState.value = state.copy(error = "Passwords don't match")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            authRepository.signUpWithEmail(state.email, state.password)
                .onSuccess {
                    // Go to email verification step - OTP will be sent by Supabase
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        emailSent = true,
                        currentStep = OnboardingStep.EMAIL_VERIFICATION
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Sign up failed"
                    )
                }
        }
    }

    fun signInWithEmail() {
        val state = _uiState.value

        // Validation
        if (state.email.isBlank()) {
            _uiState.value = state.copy(error = "Email is required")
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = state.copy(error = "Password is required")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            authRepository.signInWithEmail(state.email, state.password)
                .onSuccess { user ->
                    // Get display name from Supabase (source of truth)
                    val supabaseDisplayName = authRepository.getDisplayName()
                    val preferences = preferencesRepository.getPreferences().first()

                    // Sync Supabase name to local preferences
                    if (supabaseDisplayName != null && preferences.userName != supabaseDisplayName) {
                        preferencesRepository.updateUserName(supabaseDisplayName)
                    }

                    // Check if user has a name
                    val hasName = supabaseDisplayName?.isNotBlank() == true || preferences.userName.isNotBlank()
                    val effectiveName = supabaseDisplayName ?: preferences.userName

                    // Skip name screen if user already has a name, go directly to permissions
                    // If onboarding is completed, skip everything
                    val nextStep = when {
                        preferences.onboardingCompleted -> OnboardingStep.COMPLETED
                        hasName -> OnboardingStep.PERMISSIONS
                        else -> OnboardingStep.NAME_INPUT
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentStep = nextStep,
                        userName = effectiveName,
                        isOnboardingComplete = nextStep == OnboardingStep.COMPLETED
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Sign in failed"
                    )
                }
        }
    }

    // Keep this for backward compatibility but it's no longer used
    fun signInWithGoogle(activity: Activity) {
        // Not used anymore - using native Supabase compose auth
    }

    // Handle result from Supabase's native Google Sign-In
    fun handleGoogleSignInResult(result: NativeSignInResult) {
        viewModelScope.launch {
            when (result) {
                is NativeSignInResult.Success -> {
                    Log.d(TAG, "Google Sign-In successful")
                    // Auth state will be updated automatically via authState flow
                }
                is NativeSignInResult.ClosedByUser -> {
                    Log.d(TAG, "Google Sign-In cancelled by user")
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                is NativeSignInResult.Error -> {
                    Log.e(TAG, "Google Sign-In error: ${result.message}")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                is NativeSignInResult.NetworkError -> {
                    Log.e(TAG, "Google Sign-In network error: ${result.message}")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Network error. Please check your connection."
                    )
                }
            }
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        // User just signed in (possibly from OAuth callback)
                        if (_uiState.value.currentStep == OnboardingStep.AUTHENTICATION) {
                            handleSuccessfulAuth(state.user)
                        }
                    }
                    else -> { /* handled elsewhere */ }
                }
            }
        }
    }

    private suspend fun handleSuccessfulAuth(user: AuthUser) {
        val preferences = preferencesRepository.getPreferences().first()

        // Get display name from Supabase metadata (our stored name, not OAuth provider name)
        val supabaseDisplayName = authRepository.getDisplayName()

        // Check if this is a NEW user (no name in our system yet)
        // vs a RETURNING user (has name saved in Supabase or local preferences)
        val hasNameInOurSystem = supabaseDisplayName?.isNotBlank() == true || preferences.userName.isNotBlank()

        // For new users, get name from OAuth provider to pre-fill
        // Extract only first name (before the first space)
        val oAuthName = user.displayName
        val firstName = oAuthName?.split(" ")?.firstOrNull()?.trim() ?: ""

        // If user has no name in our system but OAuth provider has one, use first name as pre-fill
        // but still show the name screen so they can edit
        val effectiveName = if (hasNameInOurSystem) {
            supabaseDisplayName ?: preferences.userName
        } else {
            // New user - pre-fill with first name from OAuth if available
            firstName
        }

        // Skip name screen only if user already has a name IN OUR SYSTEM
        // New Google users should still see name screen (with pre-filled name)
        val nextStep = when {
            preferences.onboardingCompleted -> OnboardingStep.COMPLETED
            hasNameInOurSystem -> OnboardingStep.PERMISSIONS
            else -> OnboardingStep.NAME_INPUT // New user (including Google signup)
        }

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            currentStep = nextStep,
            userName = effectiveName,
            isOnboardingComplete = nextStep == OnboardingStep.COMPLETED
        )
    }

    fun sendPasswordReset() {
        val email = _uiState.value.email
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter your email to reset password")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            authRepository.sendPasswordResetEmail(email)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        emailSent = true,
                        error = null
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to send reset email"
                    )
                }
        }
    }

    fun submitName() {
        val name = _uiState.value.userName.trim()

        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter your name")
            return
        }

        if (name.length < 2) {
            _uiState.value = _uiState.value.copy(error = "Name must be at least 2 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // Always save name to local preferences first (for immediate access)
                preferencesRepository.updateUserName(name)

                // Try to update Supabase user metadata if we have a valid session
                // This might fail for new email signups that haven't verified yet
                val supabaseResult = authRepository.updateDisplayName(name)

                if (supabaseResult.isFailure) {
                    // Log but don't fail - name is saved locally
                    // Will sync to Supabase when user verifies email and signs in
                    Log.d(TAG, "Could not update Supabase display name (user may not be verified yet): ${supabaseResult.exceptionOrNull()?.message}")
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentStep = OnboardingStep.PERMISSIONS
                )
            } catch (e: Exception) {
                // Even if Supabase fails, try to save locally and proceed
                try {
                    preferencesRepository.updateUserName(name)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentStep = OnboardingStep.PERMISSIONS
                    )
                } catch (localError: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = localError.message ?: "Failed to save name"
                    )
                }
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                preferencesRepository.updateOnboardingCompleted(true)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentStep = OnboardingStep.COMPLETED,
                    isOnboardingComplete = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to complete onboarding"
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _uiState.value = OnboardingUiState()
        }
    }
}

