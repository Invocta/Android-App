package com.invocta.domain.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.invocta.assistant.bubble.FloatingBubbleService
import com.invocta.data.remote.AIClient
import com.invocta.data.remote.AIResponseWithTools
import com.invocta.data.remote.StreamingResponse
import com.invocta.data.remote.model.ChatMessage
import com.invocta.data.remote.model.FunctionCallInfo
import com.invocta.data.remote.model.ToolCallInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Executes tool calling loops with the AI agent.
 * Handles the cycle of: AI response -> tool calls -> tool results -> AI response
 */
class ToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val aiClient: AIClient,
    private val context: Context
) {
    companion object {
        private const val TAG = "ToolExecutor"
        private const val MAX_TOOL_ITERATIONS = 50 // Max iterations before forced stop
        private const val ACCESSIBILITY_FAILURE_THRESHOLD = 2 // Switch to vision after this many failures

        // Handler for main thread operations
        private val mainHandler = Handler(Looper.getMainLooper())

        // Screen access tools that should trigger bubble mode
        val SCREEN_ACCESS_TOOLS = setOf(
            "read_screen",
            "read_screen_vision",  // Vision-based screen reading
            "find_on_screen",
            "click_element",
            "toggle_switch",
            "set_seekbar",
            "input_text",
            "press_key",
            "scroll_screen",
            "swipe",
            "navigate",
            "open_app",  // Opening apps changes the screen, need to wait
            "web_search" // Opens browser which covers the overlay
        )

        // Callback for UI state changes
        private val _onScreenToolStart = MutableStateFlow<ToolEvent?>(null)
        val onScreenToolEvent: StateFlow<ToolEvent?> = _onScreenToolStart.asStateFlow()
        
        // Stop request state - checked before each tool execution
        private val _stopRequested = MutableStateFlow(false)
        val stopRequested: StateFlow<Boolean> = _stopRequested.asStateFlow()
        
        /**
         * Request graceful stop of tool execution.
         * The executor will stop before the next tool and let AI summarize what was done.
         */
        fun requestStop() {
            Log.i(TAG, "🛑 Stop requested by user")
            _stopRequested.value = true
        }
        
        /**
         * Clear the stop request (call when starting new execution)
         */
        fun clearStopRequest() {
            _stopRequested.value = false
        }
        
        /**
         * Check if stop has been requested
         */
        fun isStopRequested(): Boolean = _stopRequested.value
        
        // Friendly stop messages to show when user stops execution
        private val STOP_MESSAGES = listOf(
            "Okay, I've stopped. Let me know whenever you want to continue!",
            "Stopped! We can pick this up whenever you're ready.",
            "Got it, pausing here. Just say the word to continue.",
            "Alright, I've stopped. Feel free to continue when you're ready.",
            "Paused! Let me know if you'd like me to continue later.",
            "Okay, stopping here. We can continue this anytime.",
            "Stopped as requested. Ready to resume whenever you are!"
        )
        
        /**
         * Get a random friendly stop message
         */
        fun getRandomStopMessage(): String = STOP_MESSAGES.random()

        fun clearEvent() {
            _onScreenToolStart.value = null
        }
        
        // Emit event on main thread for immediate UI response
        private fun emitEvent(event: ToolEvent?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                _onScreenToolStart.value = event
            } else {
                mainHandler.post {
                    _onScreenToolStart.value = event
                }
            }
        }
    }
    
    // Notify service directly when all tools completed - works even when activity is in background
    private fun notifyAllToolsCompleted() {
        mainHandler.post {
            emitEvent(ToolEvent.AllToolsCompleted)
            FloatingBubbleService.onAllToolsCompleted(context)
        }
    }
    
    // Notify when stopped by user - bring overlay back after brief delay
    private fun notifyStoppedByUser() {
        mainHandler.post {
            emitEvent(ToolEvent.StoppedByUser)
            FloatingBubbleService.updateStatus("Stopped")
            // Bring overlay back after a moment so user can see the result
            mainHandler.postDelayed({
                FloatingBubbleService.onAllToolsCompleted(context)
            }, 500)
        }
    }

    sealed class ToolEvent {
        data class ScreenToolStarted(val toolName: String) : ToolEvent()
        data object ScreenToolCompleted : ToolEvent()
        data object AllToolsCompleted : ToolEvent()
        data object StoppedByUser : ToolEvent()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Execute a chat with tool calling support.
     * This will handle multiple rounds of tool calls until the AI produces a final response.
     *
     * @param systemPrompt System prompt for the AI
     * @param conversationHistory Previous messages in the conversation
     * @param userMessage The current user message
     * @param onStreamingContent Optional callback for streaming content updates (delta, accumulated)
     * @param useStreaming Whether to use streaming API (default true for faster response)
     * @return Result containing the final AI response or an error
     */
    suspend fun executeWithTools(
        systemPrompt: String,
        conversationHistory: List<ChatMessage>,
        userMessage: String,
        onStreamingContent: ((delta: String, accumulated: String) -> Unit)? = null,
        useStreaming: Boolean = true
    ): Result<ToolExecutionResult> {
        val tools = toolRegistry.toOpenAITools()
        val messages = conversationHistory.toMutableList()
        messages.add(ChatMessage.user(userMessage))

        var iterations = 0
        val toolCallsLog = mutableListOf<ToolCallLog>()
        var usedScreenTools = false

        // Track accessibility failures to switch to vision mode permanently
        var accessibilityFailureCount = 0
        var useVisionModePermanently = false

        Log.i(TAG, "🚀 Task: ${userMessage.take(80)}${if (userMessage.length > 80) "..." else ""}")
        
        // Clear any previous stop request
        clearStopRequest()

        // Wrap entire execution in NonCancellable to prevent cancellation when opening other apps
        return withContext(NonCancellable) {
            try {
                while (iterations < MAX_TOOL_ITERATIONS) {
                    iterations++
                    
                    // Check if user requested stop BEFORE making AI call
                    if (isStopRequested()) {
                        Log.i(TAG, "🛑 Stopped by user at iteration $iterations (before AI call)")
                        notifyStoppedByUser()
                        clearStopRequest()
                        return@withContext Result.success(ToolExecutionResult(
                            finalResponse = getRandomStopMessage(),
                            toolCallsLog = toolCallsLog,
                            wasCancelled = true
                        ))
                    }

                    // Call AI with tools - use streaming if enabled and callback provided
                    val aiResponse: AIResponseWithTools = if (useStreaming) {
                        // Use streaming API for faster perceived response
                        val streamResult = collectStreamingResponse(
                            systemPrompt = systemPrompt,
                            messages = messages,
                            tools = tools,
                            onContentDelta = onStreamingContent
                        )
                        
                        streamResult.getOrElse { error ->
                            Log.e(TAG, "❌ Streaming error, falling back to non-streaming: ${error.message}")
                            // Fall back to non-streaming on error
                            val fallbackResponse = aiClient.chatWithTools(
                                systemPrompt = systemPrompt,
                                messages = messages,
                                tools = tools
                            )
                            fallbackResponse.getOrElse {
                                if (usedScreenTools) notifyAllToolsCompleted()
                                return@withContext Result.failure(it)
                            }
                        }
                    } else {
                        // Use non-streaming API
                        val response = aiClient.chatWithTools(
                            systemPrompt = systemPrompt,
                            messages = messages,
                            tools = tools
                        )
                        response.getOrElse { error ->
                            Log.e(TAG, "❌ AI error: ${error.message}")
                            if (usedScreenTools) notifyAllToolsCompleted()
                            return@withContext Result.failure(error)
                        }
                    }

                    // Check if AI wants to call tools
                    if (aiResponse.toolCalls.isNotEmpty()) {
                        // Convert ToolCall to ToolCallInfo for ChatMessage
                        val toolCallInfos = aiResponse.toolCalls.map { tc ->
                            ToolCallInfo(
                                id = tc.id,
                                type = "function",
                                function = FunctionCallInfo(
                                    name = tc.name,
                                    arguments = tc.arguments
                                )
                            )
                        }

                        // Add assistant message with tool calls
                        messages.add(ChatMessage.assistantWithToolCalls(aiResponse.content, toolCallInfos))

                        // Execute each tool call
                        for (toolCall in aiResponse.toolCalls) {
                            var currentToolName = toolCall.name
                            var currentArguments = toolCall.arguments

                            // Intercept read_screen calls when vision mode is permanent
                            if (useVisionModePermanently && currentToolName == "read_screen") {
                                Log.i(TAG, "[$iterations] 🔄 Vision mode active - redirecting read_screen to read_screen_vision")
                                currentToolName = "read_screen_vision"
                                // Preserve any focus_area if specified, otherwise use empty args
                                currentArguments = "{}"
                            }

                            // Notify if this is a screen access tool
                            if (currentToolName in SCREEN_ACCESS_TOOLS) {
                                usedScreenTools = true
                                emitEvent(ToolEvent.ScreenToolStarted(currentToolName))
                                // Quick delay for UI to minimize (200ms instead of 500ms)
                                kotlinx.coroutines.delay(200)
                            }
                            
                            // Check for stop request BEFORE executing tool
                            if (isStopRequested()) {
                                Log.i(TAG, "🛑 Stopped by user before executing $currentToolName")
                                if (usedScreenTools) notifyStoppedByUser()
                                clearStopRequest()
                                return@withContext Result.success(ToolExecutionResult(
                                    finalResponse = getRandomStopMessage(),
                                    toolCallsLog = toolCallsLog,
                                    wasCancelled = true
                                ))
                            }

                            val arguments = parseArguments(currentArguments)
                            val result = toolRegistry.executeTool(currentToolName, arguments)

                            // Track accessibility failures for click_element (text-based clicks)
                            if (toolCall.name == "click_element" && result is ToolResult.Error) {
                                val originalArgs = parseArguments(toolCall.arguments)
                                val wasTextBasedClick = originalArgs.containsKey("text") || originalArgs.containsKey("description")
                                if (wasTextBasedClick) {
                                    accessibilityFailureCount++
                                    Log.w(TAG, "[$iterations] ⚠️ Accessibility click failure #$accessibilityFailureCount")

                                    if (accessibilityFailureCount >= ACCESSIBILITY_FAILURE_THRESHOLD && !useVisionModePermanently) {
                                        useVisionModePermanently = true
                                        Log.i(TAG, "[$iterations] 🔄 SWITCHING TO PERMANENT VISION MODE after $accessibilityFailureCount accessibility failures")
                                    }
                                }
                            }

                            // Short delay after opening apps - 1 second is enough for most apps
                            if (currentToolName == "open_app" && result is ToolResult.Success) {
                                Log.d(TAG, "Waiting for app to launch...")
                                kotlinx.coroutines.delay(1000)
                            }

                            // Notify completion of screen tool
                            if (currentToolName in SCREEN_ACCESS_TOOLS) {
                                emitEvent(ToolEvent.ScreenToolCompleted)
                            }

                            // Concise log: tool name, args preview, result preview
                            val argsPreview = currentArguments.replace(Regex("\\s+"), " ").take(60)
                            val resultStr = result.toOutputString()
                            val resultPreview = resultStr.replace(Regex("\\s+"), " ").take(100)
                            val status = if (result is ToolResult.Success) "✅" else if (result is ToolResult.Error) "❌" else "⚠️"
                            Log.i(TAG, "[$iterations] $status $currentToolName($argsPreview) → $resultPreview")

                            // Check if tool needs permission
                            if (result is ToolResult.NeedsPermission) {
                                Log.w(TAG, "⚠️ Needs permission: ${result.permissions.map { it.displayName }}")
                                if (usedScreenTools) {
                                    notifyAllToolsCompleted()
                                }
                                return@withContext Result.success(ToolExecutionResult(
                                    finalResponse = "I need permission to ${result.permissions.joinToString(" and ") { it.description.lowercase() }}. Please grant the permission and try again.",
                                    toolCallsLog = toolCallsLog,
                                    needsPermission = true,
                                    pendingPermissions = result.permissions
                                ))
                            }

                            // Log the tool call
                            toolCallsLog.add(ToolCallLog(
                                toolName = currentToolName,
                                arguments = arguments,
                                result = result.toOutputString()
                            ))

                            // Build the result message for AI - include vision mode status if relevant
                            var resultMessage = result.toOutputString()
                            if (useVisionModePermanently && result is ToolResult.Error && toolCall.name == "click_element") {
                                resultMessage += "\n\nNOTE: This app has poor accessibility support. Vision mode is now ACTIVE - use read_screen_vision and click with x,y coordinates only."
                            }

                            // Add tool result message - handle image results specially
                            if (result is ToolResult.SuccessWithImage) {
                                // First, add proper tool result to complete the tool call flow
                                messages.add(ChatMessage.toolResult(toolCall.id, "Screenshot captured. Analyze the following image."))
                                // Then, add user message with the image for vision analysis
                                messages.add(ChatMessage.userWithImage(
                                    text = result.message,
                                    base64Image = result.base64Image,
                                    mimeType = result.mimeType
                                ))
                            } else {
                                messages.add(ChatMessage.toolResult(toolCall.id, resultMessage))
                            }
                        }
                    } else {
                        // AI produced a final response without tool calls
                        val finalResponse = aiResponse.content ?: "I apologize, but I couldn't generate a response."
                        Log.i(TAG, "✅ Done in $iterations iterations. Response: ${finalResponse.take(100)}...")
                        
                        // Notify that all tools are complete - call service directly
                        if (usedScreenTools) {
                            notifyAllToolsCompleted()
                        }
                        
                        return@withContext Result.success(ToolExecutionResult(
                            finalResponse = finalResponse,
                            toolCallsLog = toolCallsLog
                        ))
                    }
                }

                Log.w(TAG, "⚠️ Max iterations ($MAX_TOOL_ITERATIONS) reached")
                if (usedScreenTools) {
                    notifyAllToolsCompleted()
                }
                Result.failure(Exception("Maximum tool calling iterations reached"))
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error", e)
                if (usedScreenTools) {
                    notifyAllToolsCompleted()
                }
                Result.failure(e)
            }
        }
    }

    private fun parseArguments(argumentsJson: String): Map<String, Any?> {
        return try {
            if (argumentsJson.isBlank() || argumentsJson == "{}") {
                emptyMap()
            } else {
                val jsonObject = json.decodeFromString<JsonObject>(argumentsJson)
                jsonObject.mapValues { (_, value) ->
                    when {
                        value is kotlinx.serialization.json.JsonNull -> null
                        value is kotlinx.serialization.json.JsonPrimitive -> {
                            when {
                                value.isString -> value.content
                                value.content.toBooleanStrictOrNull() != null -> value.content.toBoolean()
                                value.content.toIntOrNull() != null -> value.content.toInt()
                                value.content.toDoubleOrNull() != null -> value.content.toDouble()
                                else -> value.content
                            }
                        }
                        else -> value.toString()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse arguments: $argumentsJson", e)
            emptyMap()
        }
    }

    /**
     * Collect streaming response from the AI client
     * Handles content deltas and returns the final response
     */
    private suspend fun collectStreamingResponse(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        onContentDelta: ((delta: String, accumulated: String) -> Unit)?
    ): Result<AIResponseWithTools> {
        var finalResponse: AIResponseWithTools? = null
        var error: Throwable? = null

        aiClient.chatWithToolsStream(
            systemPrompt = systemPrompt,
            messages = messages,
            tools = tools,
            onContentDelta = onContentDelta
        ).catch { e ->
            Log.e(TAG, "Stream error", e)
            error = e
        }.collect { event ->
            when (event) {
                is StreamingResponse.ContentUpdate -> {
                    // Content updates are handled by onContentDelta callback
                    // Already invoked in the stream
                }
                is StreamingResponse.Complete -> {
                    finalResponse = event.response
                }
                is StreamingResponse.Error -> {
                    error = Exception(event.message)
                }
            }
        }

        return when {
            error != null -> Result.failure(error!!)
            finalResponse != null -> Result.success(finalResponse!!)
            else -> Result.failure(Exception("Stream completed without response"))
        }
    }
}

/**
 * Result of a tool execution cycle
 */
data class ToolExecutionResult(
    val finalResponse: String,
    val toolCallsLog: List<ToolCallLog>,
    val needsPermission: Boolean = false,
    val pendingPermissions: List<ToolPermission>? = null,
    val wasCancelled: Boolean = false
)

/**
 * Log entry for a tool call
 */
data class ToolCallLog(
    val toolName: String,
    val arguments: Map<String, Any?>,
    val result: String
)

