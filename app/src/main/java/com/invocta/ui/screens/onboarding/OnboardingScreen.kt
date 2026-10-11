package com.invocta.ui.screens.onboarding

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.invocta.R
import com.invocta.data.remote.SupabaseClientProvider
import com.invocta.domain.model.OnboardingStep
import io.github.jan.supabase.compose.auth.composeAuth
import io.github.jan.supabase.compose.auth.composable.NativeSignInResult
import io.github.jan.supabase.compose.auth.composable.rememberSignInWithGoogle
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onOnboardingComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Mic permission launcher
    var micPermissionRequested by remember { mutableStateOf(false) }
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        micPermissionRequested = !micPermissionRequested // Trigger recomposition
    }

    val onRequestMicPermission: () -> Unit = {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Notification permission launcher (for Android 13+)
    var notificationPermissionRequested by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        notificationPermissionRequested = !notificationPermissionRequested // Trigger recomposition
    }

    val onRequestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Navigate when onboarding is complete
    LaunchedEffect(uiState.isOnboardingComplete) {
        if (uiState.isOnboardingComplete) {
            onOnboardingComplete()
        }
    }

    // Show errors in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = uiState.currentStep,
                transitionSpec = {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                },
                label = "onboarding_step"
            ) { step ->
                when (step) {
                    OnboardingStep.AUTHENTICATION -> AuthenticationStep(
                        uiState = uiState,
                        onEmailChange = viewModel::updateEmail,
                        onPasswordChange = viewModel::updatePassword,
                        onContinueWithEmail = viewModel::continueWithEmail,
                        onSignInWithGoogle = viewModel::signInWithGoogle,
                        onGoogleSignInResult = viewModel::handleGoogleSignInResult,
                        onForgotPassword = viewModel::sendPasswordReset,
                        onContinueLocally = viewModel::continueLocally
                    )
                    OnboardingStep.EMAIL_VERIFICATION -> EmailVerificationStep(
                        uiState = uiState,
                        onOtpChange = viewModel::updateOtpCode,
                        onVerify = viewModel::verifyOtp,
                        onResendCode = viewModel::resendOtp
                    )
                    OnboardingStep.NAME_INPUT -> NameInputStep(
                        uiState = uiState,
                        onNameChange = viewModel::updateUserName,
                        onSubmit = viewModel::submitName
                    )
                    OnboardingStep.PERMISSIONS -> PermissionsStep(
                        uiState = uiState,
                        onRequestMicPermission = onRequestMicPermission,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onComplete = viewModel::completeOnboarding
                    )
                    OnboardingStep.COMPLETED -> {
                        // This shouldn't be visible as we navigate away
                    }
                }
            }

            // Loading overlay
            AnimatedVisibility(
                visible = uiState.isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthenticationStep(
    uiState: OnboardingUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onContinueWithEmail: () -> Unit,
    onSignInWithGoogle: (Activity) -> Unit,
    onGoogleSignInResult: (NativeSignInResult) -> Unit,
    onForgotPassword: () -> Unit,
    onContinueLocally: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

    // Supabase native Google Sign-In
    val googleSignInAction = SupabaseClientProvider.client.composeAuth.rememberSignInWithGoogle(
        onResult = { result ->
            onGoogleSignInResult(result)
        },
        fallback = {
            // Fallback for devices without Google Play Services
            // Could show a message or use alternative auth method
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // App Logo/Icon
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Invocta Logo",
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(25.dp))
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Welcome to Invocta",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Sign & Sync your Chat's across devices",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Google Sign-In Button - using Supabase native auth
        OutlinedButton(
            onClick = { googleSignInAction.startFlow() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_google),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Continue with Google",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider with "or"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "or",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Email field
        OutlinedTextField(
            value = uiState.email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            leadingIcon = {
                Icon(Icons.Default.Email, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password field
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = null)
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onContinueWithEmail()
                }
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading
        )

        TextButton(
            onClick = onForgotPassword,
            modifier = Modifier.align(Alignment.End),
            enabled = !uiState.isLoading
        ) {
            Text("Forgot Password?")
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Submit button
        Button(
            onClick = onContinueWithEmail,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading
        ) {
            Text(
                text = "Continue with Email",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Email sent message
        AnimatedVisibility(visible = uiState.emailSent) {
            Text(
                text = "Password reset email sent! Check your inbox.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onContinueLocally,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Text("Use locally with your own models")
        }
        Text(
            text = "Keep chats on this device and use your own AI models.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun EmailVerificationStep(
    uiState: OnboardingUiState,
    onOtpChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResendCode: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Email,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Verify your email",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "We sent an 8-digit code to",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        Text(
            text = uiState.email,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // OTP Input field
        OutlinedTextField(
            value = uiState.otpCode,
            onValueChange = onOtpChange,
            label = { Text("Verification Code") },
            placeholder = {
                Text(
                    text = "00000000",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onVerify()
                }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                textAlign = TextAlign.Center,
                letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onVerify,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading && uiState.otpCode.length == 8
        ) {
            Text(
                text = "Verify Email",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Resend code option
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Didn't receive the code?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onResendCode,
                enabled = !uiState.isLoading
            ) {
                Text(
                    text = "Resend",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // OTP resent confirmation
        AnimatedVisibility(visible = uiState.otpResent) {
            Text(
                text = "✓ New code sent!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun NameInputStep(
    uiState: OnboardingUiState,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hasPrefilledName = uiState.userName.isNotBlank()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (hasPrefilledName) "Confirm your name" else "What should we call you?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (hasPrefilledName) "You can edit your name if you'd like" else "This helps personalize your experience",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = uiState.userName,
            onValueChange = onNameChange,
            label = { Text("Your Name") },
            leadingIcon = {
                Icon(Icons.Default.Person, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onSubmit()
                }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading && uiState.userName.isNotBlank()
        ) {
            Text(
                text = "Continue",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PermissionsStep(
    uiState: OnboardingUiState,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val activity = context as? Activity

    // Permission states - refresh on resume
    var micPermissionGranted by remember { mutableStateOf(false) }
    var overlayPermissionGranted by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(false) }
    var defaultAssistantSet by remember { mutableStateOf(false) }

    // Launcher for default assistant role
    val assistantRoleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Refresh permission state after returning
        defaultAssistantSet = checkDefaultAssistant(context)
    }

    // Refresh permissions when screen resumes
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                micPermissionGranted = checkMicPermission(context)
                overlayPermissionGranted = checkOverlayPermission(context)
                accessibilityEnabled = checkAccessibilityEnabled(context)
                notificationPermissionGranted = checkNotificationPermission(context)
                defaultAssistantSet = checkDefaultAssistant(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Initial check
        micPermissionGranted = checkMicPermission(context)
        overlayPermissionGranted = checkOverlayPermission(context)
        accessibilityEnabled = checkAccessibilityEnabled(context)
        notificationPermissionGranted = checkNotificationPermission(context)
        defaultAssistantSet = checkDefaultAssistant(context)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Define permission items similar to PermissionsSection.kt
    // Only show "Display Over Other Apps" if default assistant is set but overlay permission is missing (edge case)
    val showOverlayPermission = defaultAssistantSet && !overlayPermissionGranted

    val permissions = remember(
        defaultAssistantSet,
        micPermissionGranted,
        accessibilityEnabled,
        overlayPermissionGranted,
        notificationPermissionGranted,
        showOverlayPermission
    ) {
        buildList {
            add(OnboardingPermission(
                name = "Default Assistant",
                description = "Set Invocta as your default voice assistant",
                icon = Icons.Default.PhoneAndroid,
                isGranted = defaultAssistantSet,
                isRequired = false,
                onRequest = {
                    val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            ))
            add(OnboardingPermission(
                name = "Microphone",
                description = "Required for voice input and commands",
                icon = Icons.Default.Mic,
                isGranted = micPermissionGranted,
                isRequired = true,
                onRequest = onRequestMicPermission
            ))
            add(OnboardingPermission(
                name = "Accessibility Service",
                description = "Required for screen control and automation",
                icon = Icons.Default.AccessibilityNew,
                isGranted = accessibilityEnabled,
                isRequired = true,
                onRequest = {
                    try {
                        // Try to open directly to our accessibility service settings
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val componentName = android.content.ComponentName(
                            context.packageName,
                            "com.invocta.accessibility.InvoctaAccessibilityService"
                        )
                        val extraFragmentArgKey = ":settings:fragment_args_key"
                        val extraShowFragmentArgs = ":settings:show_fragment_args"
                        val bundle = android.os.Bundle()
                        bundle.putString(extraFragmentArgKey, componentName.flattenToString())
                        intent.putExtra(extraShowFragmentArgs, bundle)
                        intent.putExtra(extraFragmentArgKey, componentName.flattenToString())
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback to general accessibility settings
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            ))
            // Only show Display Over Other Apps if default assistant is set but overlay permission is missing
            if (showOverlayPermission) {
                add(OnboardingPermission(
                    name = "Display Over Other Apps",
                    description = "Required for floating bubble assistant",
                    icon = Icons.Default.Layers,
                    isGranted = overlayPermissionGranted,
                    isRequired = false,
                    onRequest = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        // Add extras to help with scrolling to the app (API 26+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        intent.putExtra("app_package", context.packageName)
                        intent.putExtra(":settings:fragment_args_key", context.packageName)
                        context.startActivity(intent)
                    }
                ))
            }
            add(OnboardingPermission(
                name = "Notifications",
                description = "For assistant alerts and updates",
                icon = Icons.Default.Notifications,
                isGranted = notificationPermissionGranted,
                isRequired = false,
                onRequest = onRequestNotificationPermission
            ))
        }
    }

    val allRequiredGranted = micPermissionGranted && accessibilityEnabled
    val grantedCount = permissions.count { it.isGranted }
    val totalCount = permissions.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "🎉",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Almost there, ${uiState.userName}!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Grant permissions to unlock all features",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        // Progress indicator with status badge
        val allGranted = grantedCount == totalCount
        Box(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 24.dp)
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (allGranted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "$grantedCount/$totalCount permissions granted",
                style = MaterialTheme.typography.labelLarge,
                color = if (allGranted) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onErrorContainer
            )
        }

        // Permission items - one by one
        permissions.forEachIndexed { index, permission ->
            OnboardingPermissionItem(
                icon = permission.icon,
                title = permission.name,
                description = permission.description,
                isGranted = permission.isGranted,
                isRequired = permission.isRequired,
                onClick = permission.onRequest
            )
            if (index < permissions.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Info text
        if (!allRequiredGranted) {
            Text(
                text = "* Required permissions must be granted to continue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !uiState.isLoading && allRequiredGranted
        ) {
            Text(
                text = "Get Started",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Skip option (but warn about limited functionality)
        if (!allRequiredGranted) {
            TextButton(
                onClick = onComplete,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "Skip for now (limited features)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Data class for onboarding permission items
 */
private data class OnboardingPermission(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: Boolean,
    val isRequired: Boolean,
    val onRequest: () -> Unit
)

@Composable
private fun OnboardingPermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    isRequired: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isGranted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = !isGranted) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon with status background
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (isGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isGranted) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (isRequired && !isGranted) {
                    Text(
                        text = " *",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status indicator
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Grant permission",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Permission check helper functions
private fun checkMicPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun checkDefaultAssistant(context: Context): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        } else {
            val defaultAssistant = Settings.Secure.getString(
                context.contentResolver,
                "assistant"
            )
            defaultAssistant?.contains(context.packageName) == true
        }
    } catch (e: Exception) {
        false
    }
}

