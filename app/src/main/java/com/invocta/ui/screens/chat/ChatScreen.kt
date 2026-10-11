package com.invocta.ui.screens.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.invocta.R
import com.invocta.domain.model.AssistantState
import com.invocta.domain.model.Message
import com.invocta.ui.components.ChatInputBar
import com.invocta.ui.components.ChatInputState
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    sessionId: String,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val messages by viewModel.messages.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val partialSpeechResult by viewModel.partialSpeechResult.collectAsState()
    val soundLevel by viewModel.soundLevel.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val allSessions by viewModel.allSessions.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val listState = rememberLazyListState()
    var showMenu by remember { mutableStateOf(false) }

    // Map AssistantState to ChatInputState
    val inputState = when {
        assistantState == AssistantState.Listening -> ChatInputState.Listening
        assistantState == AssistantState.Transcribing -> ChatInputState.Transcribing
        assistantState == AssistantState.Processing && streamingContent?.isNotEmpty() == true -> ChatInputState.Responding
        assistantState == AssistantState.Processing -> ChatInputState.Processing
        assistantState == AssistantState.Speaking -> ChatInputState.Responding
        else -> ChatInputState.Idle
    }

    // Initialize speech recognition lazily (not blocking UI)
    LaunchedEffect(Unit) {
        viewModel.initializeSpeechRecognition(context)
    }

    // Load the session only if it's different from the current one
    LaunchedEffect(sessionId) {
        if (viewModel.currentSessionId.value != sessionId) {
            viewModel.loadSession(sessionId)
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Auto-scroll when keyboard opens - use IME bottom inset as proxy for keyboard visibility
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
                        if (session.id != sessionId) {
                            onNavigateToChat(session.id)
                        }
                    }
                },
                onDeleteSession = { session ->
                    viewModel.deleteSession(session.id)
                    if (session.id == sessionId) {
                        onNavigateToNewChat()
                    }
                },
                onRenameSession = { session, newTitle ->
                    viewModel.renameSession(session.id, newTitle)
                },
                onNewChat = {
                    scope.launch {
                        drawerState.close()
                        viewModel.resetForNewChat()
                        onNavigateToNewChat()
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
                        Column {
                            Text(
                                text = currentSession?.title ?: "Chat",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Text(
                                text = when (assistantState) {
                                    is AssistantState.Listening -> stringResource(R.string.hint_speak_now)
                                    is AssistantState.Processing -> "Thinking..."
                                    else -> stringResource(R.string.assistant_ready)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open menu"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    // Apply status bar insets to TopAppBar
                    windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Clear conversation") },
                                    onClick = {
                                        viewModel.clearConversation()
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New chat") },
                                    onClick = {
                                        viewModel.resetForNewChat()
                                        onNavigateToNewChat()
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
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
                // Messages list - fills remaining space above input
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }

                    // Show streaming content or processing indicator
                    if (inputState == ChatInputState.Processing || inputState == ChatInputState.Responding) {
                        item {
                            if (streamingContent != null && streamingContent!!.isNotEmpty()) {
                                // Show streaming content as a temporary message bubble
                                StreamingMessageBubble(content = streamingContent!!)
                            } else {
                                ProcessingIndicator()
                            }
                        }
                    }
                }

                // Input area - at bottom, moves up with keyboard
                ChatInputBar(
                    inputText = inputText,
                    onInputChange = viewModel::updateInputText,
                    onSend = viewModel::sendMessage,
                    onVoiceInputStart = { startVoiceRecognition() },
                    onVoiceInputStop = { viewModel.stopVoiceInput() },
                    inputState = inputState,
                    partialSpeechResult = partialSpeechResult,
                    soundLevel = soundLevel,
                    placeholder = "Message...",
                    onSendWhileListening = { viewModel.sendWhileListening() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.isFromUser
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            modifier = Modifier.widthIn(max = 300.dp)  // Max width, but shrinks to content
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                if (isUser) {
                    Text(
                        text = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    MarkdownText(
                        markdown = message.content,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = textColor,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ProcessingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

/**
 * Shows streaming AI response content in real-time
 */
@Composable
private fun StreamingMessageBubble(content: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                // Blinking cursor effect
                val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "cursor_blink"
                )
                Text(
                    text = "▌",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
