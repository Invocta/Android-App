package com.invocta.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.invocta.domain.model.UserPreferences
import com.invocta.domain.repository.PreferencesRepository
import com.invocta.ui.screens.chat.ChatScreen
import com.invocta.ui.screens.chat.ChatViewModel
import com.invocta.ui.screens.chat.HomeScreen
import com.invocta.ui.screens.onboarding.OnboardingScreen
import com.invocta.ui.screens.onboarding.OnboardingViewModel
import com.invocta.ui.screens.settings.SettingsScreen
import com.invocta.ui.screens.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Onboarding.route,
    hasMicPermission: Boolean = false,
    onRequestMicPermission: () -> Unit = {}
) {
    // Get singleton ChatViewModel - shared across all screens and with AssistantOverlayActivity
    val chatViewModel: ChatViewModel = koinInject()
    val preferencesRepository: PreferencesRepository = koinInject()
    val preferences by preferencesRepository.getPreferences().collectAsState(initial = UserPreferences())

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Onboarding - Authentication and setup flow
        composable(Screen.Onboarding.route) {
            val viewModel: OnboardingViewModel = koinViewModel()
            OnboardingScreen(
                viewModel = viewModel,
                onOnboardingComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        // Home - Main landing page for new chats
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = chatViewModel,
                hasMicPermission = hasMicPermission,
                onRequestPermission = onRequestMicPermission,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToChat = { sessionId ->
                    navController.navigate(Screen.Chat.createRoute(sessionId)) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                userName = preferences.userName
            )
        }

        // Chat - Existing conversation
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            ChatScreen(
                viewModel = chatViewModel,
                sessionId = sessionId,
                hasMicPermission = hasMicPermission,
                onRequestPermission = onRequestMicPermission,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToNewChat = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onNavigateToChat = { newSessionId ->
                    navController.navigate(Screen.Chat.createRoute(newSessionId)) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                }
            )
        }

        // Settings
        composable(Screen.Settings.route) {
            val viewModel: SettingsViewModel = koinViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSignOut = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}


