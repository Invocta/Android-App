package com.invocta.assistant.bubble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the UI mode of the assistant - whether it's showing as
 * full overlay or minimized to a floating bubble.
 */
object AssistantUIManager {

    /**
     * Current UI mode of the assistant
     */
    enum class UIMode {
        HIDDEN,      // Not visible at all
        OVERLAY,     // Full overlay panel visible
        BUBBLE       // Minimized to floating bubble
    }

    private val _uiMode = MutableStateFlow(UIMode.HIDDEN)
    val uiMode: StateFlow<UIMode> = _uiMode.asStateFlow()

    private val _currentAction = MutableStateFlow<String?>(null)
    val currentAction: StateFlow<String?> = _currentAction.asStateFlow()

    // List of tools that require screen access (should minimize to bubble)
    private val screenAccessTools = setOf(
        "read_screen",
        "find_on_screen",
        "click_element",
        "input_text",
        "scroll_screen",
        "swipe",
        "navigate"
    )

    /**
     * Check if a tool requires screen access
     */
    fun requiresScreenAccess(toolName: String): Boolean {
        return toolName in screenAccessTools
    }

    /**
     * Called when tool execution starts
     */
    fun onToolExecutionStart(context: Context, toolName: String) {
        if (requiresScreenAccess(toolName)) {
            _currentAction.value = getToolDescription(toolName)
            minimizeToBubble(context, getToolDescription(toolName))
        }
    }

    /**
     * Called when tool execution completes
     */
    fun onToolExecutionComplete(context: Context) {
        _currentAction.value = null
        // Don't automatically expand - let user tap bubble to return
    }

    /**
     * Called when all tool iterations are done
     */
    fun onAllToolsComplete(context: Context) {
        _currentAction.value = null
        // Could optionally expand back to overlay here
        // For now, update bubble status to show completion
        FloatingBubbleService.updateStatus("Done! Tap to see result")
    }

    /**
     * Minimize to floating bubble
     */
    fun minimizeToBubble(context: Context, status: String = "Working...") {
        _uiMode.value = UIMode.BUBBLE
        FloatingBubbleService.show(context, status)
    }

    /**
     * Expand from bubble to full overlay
     */
    fun expandToOverlay() {
        _uiMode.value = UIMode.OVERLAY
    }

    /**
     * Show full overlay
     */
    fun showOverlay() {
        _uiMode.value = UIMode.OVERLAY
    }

    /**
     * Hide everything
     */
    fun hide(context: Context) {
        _uiMode.value = UIMode.HIDDEN
        FloatingBubbleService.hide(context)
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
            else -> "Working..."
        }
    }
}

