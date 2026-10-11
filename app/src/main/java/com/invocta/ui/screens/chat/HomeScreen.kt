package com.invocta.ui.screens.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.invocta.R
import com.invocta.domain.model.AssistantState
import com.invocta.ui.components.ChatInputBar
import com.invocta.ui.components.ChatInputState
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ChatViewModel,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    userName: String = ""
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val inputText by viewModel.inputText.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val partialSpeechResult by viewModel.partialSpeechResult.collectAsState()
    val soundLevel by viewModel.soundLevel.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val allSessions by viewModel.allSessions.collectAsState()

    // Track if we initiated a new chat from this screen
    var hasSentMessage by remember { mutableStateOf(false) }

    // Map AssistantState to ChatInputState
    val inputState = when (assistantState) {
        AssistantState.Listening -> ChatInputState.Listening
        AssistantState.Transcribing -> ChatInputState.Transcribing
        AssistantState.Processing -> ChatInputState.Processing
        AssistantState.Speaking -> ChatInputState.Responding
        else -> ChatInputState.Idle
    }

    // Initialize speech recognition lazily (not blocking UI)
    LaunchedEffect(Unit) {
        viewModel.initializeSpeechRecognition(context)
    }

    // If we sent a message and session was created, navigate to chat
    LaunchedEffect(currentSessionId, messages.size, hasSentMessage) {
        val sessionId = currentSessionId
        if (hasSentMessage && sessionId != null && messages.any { it.isFromUser }) {
            hasSentMessage = false
            viewModel.clearInputOnly()
            onNavigateToChat(sessionId)
        }
    }

    fun startVoiceRecognition() {
        if (!hasMicPermission) {
            onRequestPermission()
            return
        }
        // Stop TTS before starting voice input
        viewModel.stopSpeaking()
        viewModel.startVoiceInput()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                sessions = allSessions,
                onSessionClick = { session ->
                    scope.launch {
                        drawerState.close()
                        onNavigateToChat(session.id)
                    }
                },
                onDeleteSession = { session ->
                    viewModel.deleteSession(session.id)
                },
                onRenameSession = { session, newTitle ->
                    viewModel.renameSession(session.id, newTitle)
                },
                onNewChat = {
                    scope.launch {
                        drawerState.close()
                        viewModel.resetForNewChat()
                    }
                },
                onNavigateToSettings = {
                    scope.launch {
                        drawerState.close()
                        onNavigateToSettings()
                    }
                }
            )
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // Remove all insets handling from Scaffold - we handle manually
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open menu"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.action_settings)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    // Apply status bar insets to TopAppBar
                    windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
            }
        ) { paddingValues ->
            // Use Column to stack content vertically - input stays at bottom with IME
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            ) {
                // Main content - centered welcome message, fills remaining space
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    WelcomeContent(userName = userName)
                }

                // Input area - at bottom, moves up with keyboard
                ChatInputBar(
                    inputText = inputText,
                    onInputChange = viewModel::updateInputText,
                    onSend = {
                        hasSentMessage = true
                        viewModel.createNewSessionAndSend()
                    },
                    onVoiceInputStart = { startVoiceRecognition() },
                    onVoiceInputStop = { viewModel.stopVoiceInput() },
                    inputState = inputState,
                    partialSpeechResult = partialSpeechResult,
                    soundLevel = soundLevel,
                    placeholder = "Message Invocta...",
                    onSendWhileListening = {
                        hasSentMessage = true
                        viewModel.sendWhileListeningNewSession()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun WelcomeContent(userName: String = "") {
    // Get time-based greeting
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour in 0..4 -> "Sweet Dreams"
            hour in 5..11 -> "Good Morning"
            hour in 12..16 -> "Good Afternoon"
            else -> "Good Evening"
        }
    }

    val displayName = if (userName.isNotBlank()) ", $userName" else ""

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        // Logo
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Invocta Logo",
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "$greeting$displayName",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color(0xFF666666)
        )
    }
}
