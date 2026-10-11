package com.invocta

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.invocta.domain.model.AuthState
import com.invocta.domain.model.ThemeMode
import com.invocta.domain.model.UserPreferences
import com.invocta.domain.repository.AuthRepository
import com.invocta.domain.repository.PreferencesRepository
import com.invocta.ui.navigation.AppNavHost
import com.invocta.ui.navigation.Screen
import com.invocta.ui.theme.InvoctaTheme
import kotlinx.coroutines.flow.first
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val preferencesRepository: PreferencesRepository by inject()
    private val authRepository: AuthRepository by inject()

    private var hasMicPermission by mutableStateOf(false)

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge using same method as overlay
        WindowCompat.setDecorFitsSystemWindows(window, false)


        // Check initial permission state
        hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Get session ID from intent if coming from overlay
        val sessionIdFromIntent = intent.getStringExtra("session_id")
        val openAssistant = intent.getBooleanExtra("open_assistant", false)

        setContent {
            // Load preferences from DataStore - use produceState to wait for actual first value
            // instead of collectAsState which emits default value immediately
            val preferencesState by produceState<UserPreferences?>(initialValue = null) {
                value = preferencesRepository.getPreferences().first()
            }

            // Continue observing preferences after initial load
            val preferences by preferencesRepository.getPreferences().collectAsState(
                initial = preferencesState ?: UserPreferences()
            )

            // Observe auth state reactively - this will update when session is loaded
            val authState by authRepository.authState.collectAsState(initial = AuthState.Loading)

            val darkTheme = when (preferences.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            InvoctaTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Determine start destination based on auth state and preferences
                    // Wait for BOTH auth state and preferences to be loaded
                    val currentStartDestination = when {
                        authState is AuthState.Loading -> null // Auth still loading
                        preferencesState == null -> null // Preferences still loading
                        authState is AuthState.Authenticated -> {
                            if (preferences.onboardingCompleted) Screen.Home.route
                            else Screen.Onboarding.route
                        }
                        else -> Screen.Onboarding.route // Unauthenticated or error
                    }

                    if (currentStartDestination == null) {
                        // Show loading while determining start destination
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val navController = rememberNavController()

                        // Navigate to specific chat if session ID was provided
                        LaunchedEffect(sessionIdFromIntent) {
                            if (openAssistant && sessionIdFromIntent != null) {
                                navController.navigate(Screen.Chat.createRoute(sessionIdFromIntent)) {
                                    popUpTo(Screen.Home.route) { inclusive = false }
                                }
                            }
                        }

                        AppNavHost(
                            navController = navController,
                            startDestination = currentStartDestination,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        )
                    }
                }
            }
        }
    }
}