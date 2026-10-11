package com.invocta.assistant.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.invocta.MainActivity
import com.invocta.assistant.bubble.FloatingBubbleService
import com.invocta.data.remote.SupabaseClientProvider
import com.invocta.domain.model.ThemeMode
import com.invocta.domain.model.UserPreferences
import com.invocta.domain.repository.PreferencesRepository
import com.invocta.domain.tools.ToolExecutor
import com.invocta.ui.screens.chat.ChatViewModel
import com.invocta.ui.theme.InvoctaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Overlay Activity that shows a Google Assistant-like compact UI
 * when triggered via long-press home or voice command.
 */
class AssistantOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AssistantOverlay"
    }

    // Use lazy injection to avoid blocking onCreate
    private val chatViewModel: ChatViewModel by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val handler = Handler(Looper.getMainLooper())

    private var isMinimizedToBubble = false

    // Receiver to handle expand from bubble
    private val expandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.invocta.EXPAND_OVERLAY") {
                Log.d(TAG, "Received EXPAND_OVERLAY broadcast")
                bringOverlayToFront()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup window FIRST for fastest visual feedback
        setupWindow()
        
        Log.d(TAG, "onCreate called, savedInstanceState=${savedInstanceState != null}")
        
        // Handle back press immediately (doesn't need ViewModel)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        // Register broadcast receiver immediately (doesn't need ViewModel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(expandReceiver, IntentFilter("com.invocta.EXPAND_OVERLAY"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(expandReceiver, IntentFilter("com.invocta.EXPAND_OVERLAY"))
        }

        // Set content IMMEDIATELY - session loading happens in background
        setContent {
            val preferences by preferencesRepository.getPreferences().collectAsState(
                initial = UserPreferences()
            )

            val isDarkTheme = when (preferences.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Track if we should show the overlay based on tool execution state
            val toolEvent by ToolExecutor.onScreenToolEvent.collectAsState()
            val showOverlay = toolEvent !is ToolExecutor.ToolEvent.ScreenToolStarted && 
                              toolEvent !is ToolExecutor.ToolEvent.ScreenToolCompleted

            InvoctaTheme(darkTheme = isDarkTheme) {
                // Show UI immediately - AssistantOverlayScreen handles null session gracefully
                // (shows "Hi, how can I help?" greeting)
                if (showOverlay) {
                    AssistantOverlayScreen(
                        viewModel = chatViewModel,
                        userName = preferences.userName,
                        onDismiss = { 
                            FloatingBubbleService.hide(this@AssistantOverlayActivity)
                            finish() 
                        },
                        onExpandToFullApp = {
                            // Navigate to full app with current session
                            val intent = Intent(this@AssistantOverlayActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("session_id", chatViewModel.currentSessionId.value)
                                putExtra("open_assistant", true)  // Required for MainActivity to navigate to chat
                            }
                            startActivity(intent)
                            FloatingBubbleService.hide(this@AssistantOverlayActivity)
                            finish()
                        }
                    )
                }
            }
        }

        // Initialize session in background - UI shows immediately
        lifecycleScope.launch {
            initializeSessionAsync(savedInstanceState)
        }
    }

    /**
     * Initialize session in background - UI already showing
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun initializeSessionAsync(savedInstanceState: Bundle?) {
        // IMMEDIATELY stop any TTS from main app
        chatViewModel.stopSpeaking()

        // Wait for auth to be ready (handles async loading from storage)
        val isAuthenticated = SupabaseClientProvider.waitForAuthReady()
        if (!isAuthenticated) {
            Log.w(TAG, "Not authenticated - redirecting to main app for login")
            // Redirect to main app for login
            val intent = Intent(this@AssistantOverlayActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("show_login", true)
            }
            startActivity(intent)
            finish()
            return
        }

        // Check if we're restoring from a minimized bubble state
        val isRestoringFromBubble = intent?.getBooleanExtra("restore_from_bubble", false) ?: false
        val sessionIdFromIntent = intent?.getStringExtra("session_id")

        // Get persisted overlay session (separate from main app) - this is fast (SharedPrefs)
        val savedOverlaySession = FloatingBubbleService.getOverlaySession(this@AssistantOverlayActivity)
        val currentVMSession = chatViewModel.currentSessionId.value

        Log.d(TAG, "Session check: fromIntent=$sessionIdFromIntent, restoringFromBubble=$isRestoringFromBubble, savedOverlay=$savedOverlaySession, currentVM=$currentVMSession")

        // Determine which session to use
        val targetSessionId = when {
            isRestoringFromBubble && sessionIdFromIntent != null -> sessionIdFromIntent
            sessionIdFromIntent != null -> sessionIdFromIntent
            savedOverlaySession != null -> savedOverlaySession
            else -> null
        }

        // If already on correct session, nothing to do
        if (targetSessionId != null && currentVMSession == targetSessionId) {
            Log.d(TAG, "Already on correct session: $targetSessionId")
            setupObservers()
            return
        }

        // Need to load or create session
        if (targetSessionId != null) {
            Log.d(TAG, "Loading session: $targetSessionId")
            chatViewModel.resetForNewChat()
            
            // Try to load - this may go to network, but UI is already showing
            val loaded = chatViewModel.loadSessionAsync(targetSessionId)
            if (!loaded) {
                Log.w(TAG, "Saved overlay session not found, creating new session")
                FloatingBubbleService.clearOverlaySession(this)
                chatViewModel.createNewSession()
            }
        } else {
            // No saved session - create new one
            Log.d(TAG, "Starting fresh overlay chat")
            FloatingBubbleService.setActiveSession(null)
            chatViewModel.resetForNewChat()
            chatViewModel.createNewSession()
        }

        // Setup observers after initialization
        setupObservers()
    }

    /**
     * Setup flow observers after initialization
     */
    private fun setupObservers() {
        // Observe tool events to minimize when screen tools are used
        lifecycleScope.launch {
            ToolExecutor.onScreenToolEvent.collectLatest { event ->
                Log.d(TAG, "Tool event received: $event, isMinimized=$isMinimizedToBubble")
                when (event) {
                    is ToolExecutor.ToolEvent.ScreenToolStarted -> {
                        minimizeToBubble(getToolDescription(event.toolName))
                    }
                    else -> {}
                }
            }
        }

        // Track current session ID for restoration
        lifecycleScope.launch {
            chatViewModel.currentSessionId.collectLatest { sessionId ->
                Log.d(TAG, "Current session ID changed: $sessionId")
                if (sessionId != null) {
                    FloatingBubbleService.setActiveSession(sessionId)
                    FloatingBubbleService.saveOverlaySession(this@AssistantOverlayActivity, sessionId)
                }
            }
        }
    }

    private fun minimizeToBubble(status: String) {
        if (isMinimizedToBubble) return

        Log.d(TAG, "Minimizing to bubble: $status")

        // Check if we have overlay permission
        if (!Settings.canDrawOverlays(this)) {
            // Request overlay permission
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        isMinimizedToBubble = true
        
        // Use a handler to avoid blocking
        handler.post {
            // Show bubble first
            FloatingBubbleService.show(this, status)
            
            // Then minimize after a short delay to ensure bubble is visible
            handler.postDelayed({
                // Move activity to back so bubble is visible
                moveTaskToBack(true)
            }, 50)
        }
    }

    private fun bringOverlayToFront() {
        Log.d(TAG, "Bringing overlay to front, isMinimized=$isMinimizedToBubble")
        
        // Hide the bubble first
        FloatingBubbleService.hide(this)
        
        // Reset minimized state
        isMinimizedToBubble = false
        
        // Bring activity back to front
        handler.post {
            try {
                val bringToFrontIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(bringToFrontIntent)
                Log.d(TAG, "Started activity to bring to front")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring overlay to front", e)
            }
        }
        
        // Clear the event after handling
        handler.postDelayed({
            ToolExecutor.clearEvent()
        }, 200)
    }

    private fun getToolDescription(toolName: String): String {
        return when (toolName) {
            "read_screen" -> "Reading screen..."
            "find_on_screen" -> "Finding element..."
            "click_element" -> "Tapping..."
            "input_text" -> "Typing..."
            "scroll_screen" -> "Scrolling..."
            "swipe" -> "Swiping..."
            "navigate" -> "Navigating..."
            "open_app" -> "Opening app..."
            "toggle_switch" -> "Toggling..."
            "set_seekbar" -> "Adjusting..."
            "press_key" -> "Pressing key..."
            "web_search" -> "Searching web..."
            else -> "Working..."
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called")
        setIntent(intent) // Update the intent
        
        // Coming back from bubble
        isMinimizedToBubble = false
        
        // Check if we're restoring from bubble
        val isRestoringFromBubble = intent.getBooleanExtra("restore_from_bubble", false)
        val sessionIdFromIntent = intent.getStringExtra("session_id")
        
        Log.d(TAG, "onNewIntent: fromIntent=$sessionIdFromIntent, restoringFromBubble=$isRestoringFromBubble, current=${chatViewModel.currentSessionId.value}")
        
        if (isRestoringFromBubble && sessionIdFromIntent != null) {
            // Restoring from bubble - load the session
            if (chatViewModel.currentSessionId.value != sessionIdFromIntent) {
                Log.d(TAG, "Loading session in onNewIntent: $sessionIdFromIntent")
                chatViewModel.loadSession(sessionIdFromIntent)
            }
        }
        // If not restoring from bubble, keep the current state
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - isMinimized=$isMinimizedToBubble, currentSession=${chatViewModel.currentSessionId.value}")
        
        // Only restore session if we were minimized to bubble and returning
        // Don't restore if this is a fresh open (that's handled in onCreate)
        if (isMinimizedToBubble) {
            val activeSession = FloatingBubbleService.getActiveSession()
            if (activeSession != null && chatViewModel.currentSessionId.value != activeSession) {
                Log.d(TAG, "onResume: Restoring session from minimized state: $activeSession")
                chatViewModel.loadSession(activeSession)
            }
        }
        
        // Reset minimized state since we're now visible
        isMinimizedToBubble = false
        
        // Hide bubble immediately since overlay is now showing
        FloatingBubbleService.hide(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(expandReceiver)
        } catch (_: Exception) {
            // Receiver might not be registered
        }
        // Only hide bubble if we're not minimized (user dismissed the overlay)
        // If minimized, let the service keep running for tool execution
        if (!isMinimizedToBubble) {
            FloatingBubbleService.hide(this)
        }
        ToolExecutor.clearEvent()
        super.onDestroy()
    }

    private fun setupWindow() {
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Make status bar and navigation bar transparent
        @Suppress("DEPRECATION")
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
        }

        // Show on lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}
