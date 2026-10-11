package com.invocta.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.invocta.domain.model.AssistantState
import java.util.Calendar
import com.invocta.domain.model.Message
import com.invocta.ui.components.ChatInputBar
import com.invocta.ui.components.ChatInputState
import com.invocta.ui.screens.chat.ChatViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun AssistantOverlayScreen(
    viewModel: ChatViewModel,
    userName: String = "",
    onDismiss: () -> Unit,
    onExpandToFullApp: () -> Unit
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val partialSpeechResult by viewModel.partialSpeechResult.collectAsState()
    val soundLevel by viewModel.soundLevel.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    // Initialize speech recognition and TTS after first frame
    LaunchedEffect(Unit) {
        // Defer initialization to avoid blocking first render
        kotlinx.coroutines.delay(100)
        viewModel.initializeSpeechRecognition(context)
        viewModel.initializeTextToSpeech(context)
        viewModel.setTtsEnabled(true) // Enable TTS in overlay mode
    }
    
    // Auto-speak new agent responses (only once per message)
    LaunchedEffect(messages) {
        val lastMessage = messages.lastOrNull()
        if (lastMessage != null && !lastMessage.isFromUser) {
            // Use speakTextOnce which tracks spoken messages in ViewModel
            viewModel.speakTextOnce(lastMessage.id, lastMessage.content)
        }
    }

    // Create new session if needed, but only if we're not restoring a session
    // Check FloatingBubbleService.getActiveSession() to avoid race condition
    LaunchedEffect(currentSessionId) {
        if (currentSessionId == null) {
            // Wait a short moment to see if a session is being loaded
            kotlinx.coroutines.delay(50)
            // Double-check - if still null and no active session being restored, create new
            val activeSession = com.invocta.assistant.bubble.FloatingBubbleService.getActiveSession()
            if (viewModel.currentSessionId.value == null && activeSession == null) {
                viewModel.createNewSession()
            }
        }
    }

    // Cleanup when leaving
    DisposableEffect(Unit) {
        onDispose {
            if (assistantState == AssistantState.Listening) {
                viewModel.cancelVoiceInput()
            }
            viewModel.stopSpeaking()
            viewModel.setTtsEnabled(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // Main assistant panel at bottom
        AssistantPanel(
            messages = messages,
            currentSessionId = currentSessionId,
            assistantState = assistantState,
            inputText = inputText,
            partialSpeechResult = partialSpeechResult,
            soundLevel = soundLevel,
            streamingContent = streamingContent,
            userName = userName,
            onInputTextChange = { viewModel.updateInputText(it) },
            onSendMessage = {
                if (currentSessionId != null) {
                    viewModel.sendMessage()
                } else {
                    viewModel.createNewSessionAndSend()
                }
            },
            onVoiceInput = {
                if (hasMicPermission) {
                    // Stop TTS before starting voice input
                    viewModel.stopSpeaking()
                    viewModel.startVoiceInput()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopVoiceInput = {
                viewModel.stopVoiceInput()
            },
            onSendWhileListening = {
                if (currentSessionId != null) {
                    viewModel.sendWhileListening()
                } else {
                    viewModel.sendWhileListeningNewSession()
                }
            },
            onNewChat = {
                // Clear active session and persisted overlay session
                com.invocta.assistant.bubble.FloatingBubbleService.setActiveSession(null)
                com.invocta.assistant.bubble.FloatingBubbleService.clearOverlaySession(context)
                viewModel.resetForNewChat()
                viewModel.createNewSession()
            },
            onDismiss = onDismiss,
            onExpandToFullApp = onExpandToFullApp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun AssistantPanel(
    messages: List<Message>,
    currentSessionId: String?,
    assistantState: AssistantState,
    inputText: String,
    partialSpeechResult: String,
    soundLevel: Float,
    streamingContent: String?,
    userName: String,
    onInputTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onVoiceInput: () -> Unit,
    onStopVoiceInput: () -> Unit,
    onSendWhileListening: () -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
    onExpandToFullApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Map AssistantState to ChatInputState
    val inputState = when {
        assistantState == AssistantState.Listening -> ChatInputState.Listening
        assistantState == AssistantState.Transcribing -> ChatInputState.Transcribing
        assistantState == AssistantState.Processing && streamingContent?.isNotEmpty() == true -> ChatInputState.Responding
        assistantState == AssistantState.Processing -> ChatInputState.Processing
        assistantState == AssistantState.Speaking -> ChatInputState.Responding
        else -> ChatInputState.Idle
    }

    // Determine if we should show active animation (listening or processing)
    val isActive = assistantState == AssistantState.Listening || 
                   assistantState == AssistantState.Transcribing ||
                   assistantState == AssistantState.Processing

    // Animated gradient for border glow effect
    val infiniteTransition = rememberInfiniteTransition(label = "border_glow")
    
    // Faster animation when active
    val animationDuration = if (isActive) 1000 else 3000
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradient_offset"
    )

    // Animated border width - thicker when active
    val borderWidth by animateFloatAsState(
        targetValue = if (isActive) 4f else 2f,
        animationSpec = tween(300),
        label = "border_width"
    )

    // Define gradient colors - more vibrant when active
    val gradientColors = if (isActive) {
        listOf(
            Color(0xFF6366F1), // Indigo
            Color(0xFF8B5CF6), // Purple
            Color(0xFFEC4899), // Pink
            Color(0xFFF43F5E), // Rose
            Color(0xFF6366F1)  // Back to Indigo
        )
    } else {
        listOf(
            Color(0xFF6366F1).copy(alpha = 0.6f),
            Color(0xFF8B5CF6).copy(alpha = 0.6f),
            Color(0xFFEC4899).copy(alpha = 0.6f),
            Color(0xFF6366F1).copy(alpha = 0.6f)
        )
    }

    // Auto-scroll to bottom when overlay first opens
    LaunchedEffect(Unit) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    // Auto-scroll when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Outer box for the glowing border effect
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 24.dp)  // Lift overlay above screen bottom
    ) {
        // Animated glowing border
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val strokeWidth = borderWidth.dp.toPx()
                    val cornerRadius = 28.dp.toPx()
                    
                    // Create animated gradient brush
                    val brush = Brush.sweepGradient(
                        colors = gradientColors,
                        center = Offset(
                            x = size.width / 2 + (size.width * 0.5f * kotlin.math.cos(animatedOffset * 2 * Math.PI)).toFloat(),
                            y = size.height / 2 + (size.height * 0.5f * kotlin.math.sin(animatedOffset * 2 * Math.PI)).toFloat()
                        )
                    )
                    
                    // Draw rounded rectangle border
                    drawRoundRect(
                        brush = brush,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
                        style = Stroke(width = strokeWidth)
                    )
                }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* Consume clicks to prevent dismissing */ },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Header with drag handle and controls
            AssistantHeader(
                hasMessages = messages.isNotEmpty(),
                onNewChat = onNewChat,
                onDismiss = onDismiss,
                onExpandToFullApp = onExpandToFullApp
            )

            // Messages area (limited height for overlay)
            if (messages.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .height(380.dp),  // Increased height for better visibility
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        OverlayMessageBubble(message = message)
                    }
                }
            } else {
                // Empty state with greeting (for new chats or while loading)
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

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "$greeting$displayName",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "How can I help you today?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Input area
            ChatInputBar(
                inputText = inputText,
                onInputChange = onInputTextChange,
                onSend = onSendMessage,
                onVoiceInputStart = onVoiceInput,
                onVoiceInputStop = onStopVoiceInput,
                inputState = inputState,
                partialSpeechResult = partialSpeechResult,
                soundLevel = soundLevel,
                placeholder = "Ask anything...",
                compact = true,
                onSendWhileListening = onSendWhileListening,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun AssistantHeader(
    hasMessages: Boolean,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
    onExpandToFullApp: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )

        // Controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side - Dismiss button
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Center - Title
            Text(
                text = "Invocta",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Right side - Action buttons
            Row {
                // New chat button (only show when there are messages)
                if (hasMessages) {
                    IconButton(onClick = onNewChat) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New chat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                IconButton(onClick = onExpandToFullApp) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Open full app",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayMessageBubble(message: Message) {
    val isUser = message.isFromUser
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bubbleColor)
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                // Use simple Text for short messages without markdown, MarkdownText for longer ones
                val hasMarkdown = message.content.contains("**") || 
                                  message.content.contains("*") || 
                                  message.content.contains("#") ||
                                  message.content.contains("`") ||
                                  message.content.contains("-")
                
                if (hasMarkdown) {
                    MarkdownText(
                        markdown = message.content,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4
                        )
                    )
                } else {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

