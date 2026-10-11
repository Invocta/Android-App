package com.invocta.ui.navigation

/**
 * Navigation routes for the app
 */
sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Home : Screen("home")
    data object Chat : Screen("chat/{sessionId}") {
        fun createRoute(sessionId: String) = "chat/$sessionId"
    }
    data object Settings : Screen("settings")
}

