package com.invocta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Input state for the chat input bar
 */
enum class ChatInputState {
    /** Normal idle state - user can type */
    Idle,
    /** User is speaking via STT */
    Listening,
    /** Waiting for transcription result after user stopped speaking */
    Transcribing,
    /** AI is processing/thinking */
    Processing,
    /** AI is responding (streaming) */
    Responding
}

/**
 * Unified chat input bar component used across HomeScreen, ChatScreen, and AssistantOverlay.
 * 
 * Clean design with:
 * - Rounded text field on the left
 * - Mic button always visible (appends to existing text)
 * - Send button when there's text or during listening
 * - Stop button replaces mic during listening
 * - Smooth animations between states
 */
@Composable
fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceInputStart: () -> Unit,
    onVoiceInputStop: () -> Unit,
    inputState: ChatInputState,
    modifier: Modifier = Modifier,
    partialSpeechResult: String = "",
    soundLevel: Float = 0f,
    placeholder: String = "Message...",
    compact: Boolean = false,
    /** Called when user wants to send immediately while listening */
    onSendWhileListening: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    
    // Determine display text
    val displayText = if ((inputState == ChatInputState.Listening || inputState == ChatInputState.Transcribing) 
        && partialSpeechResult.isNotEmpty()) {
        // Combine existing text with partial result
        if (inputText.isNotBlank()) {
            "$inputText $partialSpeechResult"
        } else {
            partialSpeechResult
        }
    } else {
        inputText
    }
    
    // Animation states
    val isListening = inputState == ChatInputState.Listening
    val isTranscribing = inputState == ChatInputState.Transcribing
    val isProcessing = inputState == ChatInputState.Processing
    val isResponding = inputState == ChatInputState.Responding
    val isDisabled = isProcessing || isResponding
    val hasText = displayText.isNotBlank()
    val isRecordingActive = isListening || isTranscribing
    
    val containerHeight = if (compact) 48.dp else 52.dp
    val buttonSize = if (compact) 40.dp else 44.dp
    
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Text input field
        InputField(
            text = displayText,
            onTextChange = onInputChange,
            onSend = {
                if (hasText && !isRecordingActive) {
                    onSend()
                    focusManager.clearFocus()
                }
            },
            placeholder = placeholder,
            enabled = !isDisabled && !isRecordingActive,
            inputState = inputState,
            soundLevel = soundLevel,
            modifier = Modifier
                .weight(1f)
                .height(containerHeight)
        )
        
        // Mic/Stop button (always visible)
        MicStopButton(
            isRecordingActive = isRecordingActive,
            isTranscribing = isTranscribing,
            isDisabled = isDisabled,
            soundLevel = soundLevel,
            buttonSize = buttonSize,
            onMicClick = onVoiceInputStart,
            onStopClick = {
                onVoiceInputStop()
                // Text will be appended when transcription completes
            }
        )
        
        // Send button (visible when there's text OR during listening/transcribing)
        if (hasText || isListening || isTranscribing) {
            SendButton(
                enabled = (hasText || isListening) && !isTranscribing,
                isListening = isListening,
                isTranscribing = isTranscribing,
                buttonSize = buttonSize,
                onClick = {
                    if (isListening) {
                        // Stop listening and send after transcription completes
                        onSendWhileListening?.invoke() ?: run {
                            onVoiceInputStop()
                        }
                    } else {
                        onSend()
                        focusManager.clearFocus()
                    }
                }
            )
        }
    }
}

@Composable
private fun InputField(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    enabled: Boolean,
    inputState: ChatInputState,
    soundLevel: Float,
    modifier: Modifier = Modifier
) {
    val isListening = inputState == ChatInputState.Listening
    val isTranscribing = inputState == ChatInputState.Transcribing
    val isProcessing = inputState == ChatInputState.Processing
    val isResponding = inputState == ChatInputState.Responding
    
    // Infinite transition for animations
    val infiniteTransition = rememberInfiniteTransition(label = "input_animations")
    
    // Pulsing for listening state
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // Animated border
    val borderWidth by animateFloatAsState(
        targetValue = when {
            isListening -> 2f
            isTranscribing -> 1.5f
            isProcessing || isResponding -> 1.5f
            else -> 1f
        },
        animationSpec = tween(200),
        label = "border_width"
    )
    
    val borderColor = when {
        isListening -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f + soundLevel * 0.4f)
        isTranscribing -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f + pulseAlpha * 0.3f)
        isProcessing -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f + pulseAlpha * 0.3f)
        isResponding -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = borderWidth.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Show different content based on state
            AnimatedContent(
                targetState = when {
                    isProcessing -> "processing"
                    isResponding -> "responding"
                    isTranscribing -> "transcribing"
                    isListening -> "listening" // Always show visualizer when listening
                    else -> "input"
                },
                transitionSpec = {
                    fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                },
                label = "input_content"
            ) { state ->
                when (state) {
                    "processing" -> ThinkingIndicator()
                    "responding" -> RespondingIndicator()
                    "transcribing" -> TranscribingIndicator()
                    "listening" -> ListeningIndicator(soundLevel)
                    else -> TextInput(
                        text = text,
                        onTextChange = onTextChange,
                        onSend = onSend,
                        placeholder = placeholder,
                        enabled = enabled
                    )
                }
            }
        }
    }
}

@Composable
private fun TextInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    enabled: Boolean
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
    }
}

@Composable
private fun MicStopButton(
    isRecordingActive: Boolean,
    isTranscribing: Boolean,
    isDisabled: Boolean,
    soundLevel: Float,
    buttonSize: androidx.compose.ui.unit.Dp,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit
) {
    // Animated scale for mic when listening
    val scale by animateFloatAsState(
        targetValue = if (isRecordingActive && !isTranscribing) 1f + soundLevel * 0.1f else 1f,
        animationSpec = tween(100),
        label = "mic_scale"
    )
    
    // Determine button appearance
    val backgroundColor = when {
        isTranscribing || isDisabled -> MaterialTheme.colorScheme.surfaceContainerHighest
        isRecordingActive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    
    val iconColor = when {
        isTranscribing || isDisabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        isRecordingActive -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    
    val icon = when {
        isRecordingActive -> Icons.Default.Stop
        else -> Icons.Default.Mic
    }
    
    val contentDescription = when {
        isTranscribing -> "Transcribing"
        isRecordingActive -> "Stop listening"
        else -> "Start voice input"
    }
    
    // Animated content for smooth icon transitions
    AnimatedContent(
        targetState = Pair(icon, backgroundColor),
        transitionSpec = {
            (scaleIn(tween(150)) + fadeIn(tween(150))) togetherWith
            (scaleOut(tween(150)) + fadeOut(tween(150)))
        },
        label = "mic_stop_button"
    ) { (currentIcon, currentBg) ->
        Box(
            modifier = Modifier
                .size(buttonSize)
                .scale(scale)
                .clip(CircleShape)
                .background(currentBg),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = if (isRecordingActive) onStopClick else onMicClick,
                enabled = !isDisabled && !isTranscribing,
                modifier = Modifier.size(buttonSize),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = iconColor,
                    disabledContentColor = iconColor
                )
            ) {
                Icon(
                    imageVector = currentIcon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    isListening: Boolean,
    isTranscribing: Boolean,
    buttonSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val isActuallyEnabled = enabled && !isTranscribing
    
    val backgroundColor = when {
        !isActuallyEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest
        isListening -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    
    val iconColor = when {
        !isActuallyEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onPrimary
    }
    
    AnimatedContent(
        targetState = Pair(backgroundColor, iconColor),
        transitionSpec = {
            (scaleIn(tween(150)) + fadeIn(tween(150))) togetherWith
            (scaleOut(tween(150)) + fadeOut(tween(150)))
        },
        label = "send_button"
    ) { (currentBg, currentIconColor) ->
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .background(currentBg),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onClick,
                enabled = isActuallyEnabled,
                modifier = Modifier.size(buttonSize),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = currentIconColor,
                    disabledContentColor = currentIconColor
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isListening) "Send after transcription" else "Send message",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun TranscribingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.tertiary
        )
        Text(
            text = "Transcribing...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThinkingDots()
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun ThinkingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val delay = index * 120
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )
            
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }
    }
}

@Composable
private fun RespondingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = "Responding...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun ListeningIndicator(soundLevel: Float) {
    // Full-width waveform visualizer without text
    FullWidthWaveform(soundLevel = soundLevel)
}

@Composable
private fun FullWidthWaveform(soundLevel: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    
    // More bars for full width effect
    val barCount = 24
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            // Create varied delays and speeds for organic look
            val delay = (index * 40) % 300
            val speed = 200 + (index % 5) * 50
            
            val animatedHeight by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(speed, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            )
            
            // Create wave pattern - center bars are taller
            val centerEffect = 1f - kotlin.math.abs(index - barCount / 2f) / (barCount / 2f) * 0.4f
            val baseHeight = 4 + (animatedHeight * soundLevel * 16 * centerEffect)
            
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(baseHeight.dp.coerceIn(3.dp, 20.dp))
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
