package com.invocta.domain.tools.impl

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.invocta.accessibility.InvoctaAccessibilityService
import com.invocta.accessibility.ScrollDirection
import com.invocta.domain.tools.Tool
import com.invocta.domain.tools.ToolParameters
import com.invocta.domain.tools.ToolProperty
import com.invocta.domain.tools.ToolResult
import kotlinx.coroutines.delay

/**
 * Tool to read the current screen content using accessibility services.
 * For apps/websites with poor accessibility support, use read_screen_vision instead.
 */
class ReadScreenTool : Tool {
    override val name = "read_screen"
    override val description = """Read the current screen content using accessibility services. Returns UI elements (buttons, text, input fields) visible on screen. This is the PRIMARY and FASTEST method.

CRITICAL RULES:
1. Use this FIRST to understand the screen
2. If click_element FAILS after using this tool, do NOT call read_screen again
3. After a failed click, IMMEDIATELY use read_screen_vision to get coordinates
4. If this returns few/unhelpful elements, switch to read_screen_vision

NOTE: If accessibility-based clicks fail 2+ times, the system automatically switches to VISION MODE permanently for this task. In vision mode, this tool is automatically redirected to read_screen_vision."""

    override val parameters = ToolParameters(
        properties = mapOf(
            "include_all" to ToolProperty(
                type = "boolean",
                description = "If true, includes all elements. If false (default), only shows interactive and text elements."
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen reading is not enabled. Please enable Invocta Accessibility Service in Settings > Accessibility.")

        // Small delay to allow screen to settle after overlay minimizes
        delay(200)
        
        // Try multiple times in case the screen is still transitioning
        var content = service.getScreenContent()
        var attempts = 0
        while (content.elements.isEmpty() && attempts < 3) {
            delay(300)
            content = service.getScreenContent()
            attempts++
        }
        
        return if (content.elements.isEmpty()) {
            ToolResult.Success("No screen content available. The screen may be locked or empty. Try read_screen_vision for visual analysis.")
        } else {
            ToolResult.Success(content.toSimpleText())
        }
    }
}

/**
 * Tool to capture and analyze the screen using AI vision.
 * Use this when accessibility services don't provide enough information (e.g., for poorly coded websites or canvas-based apps).
 */
class ReadScreenVisionTool : Tool {
    override val name = "read_screen_vision"
    override val description = """Capture a screenshot with coordinates when accessibility screen content is empty or clicks fail.
- Mostly empty or unhelpful data
- Web browser content that's hard to understand
- "No screen content available" message
- Canvas-based apps, games, or image content

The screenshot includes a coordinate grid overlay with labeled X and Y positions. Use these grid lines to accurately estimate coordinates for click_element(x, y). The screen dimensions are shown in the bottom-right corner."""

    override val parameters = ToolParameters(
        properties = mapOf(
            "focus_area" to ToolProperty(
                type = "string",
                description = "Optional: describe what to focus on (e.g., 'form fields', 'buttons at bottom', 'cookie popup')"
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service.")

        val focusArea = arguments["focus_area"] as? String

        // Temporarily hide the floating bubble so it doesn't appear in the screenshot
        val bubbleWasVisible = com.invocta.assistant.bubble.FloatingBubbleService.temporarilyHide()

        // Small delay to ensure bubble is hidden before capture
        if (bubbleWasVisible) {
            delay(100)
        }

        // Capture the screenshot with coordinate grid overlay
        val screenshotData = try {
            service.captureScreenWithGrid(quality = 85, maxWidth = 1080, gridSpacing = 200)
        } finally {
            // Always restore the bubble after capture
            if (bubbleWasVisible) {
                com.invocta.assistant.bubble.FloatingBubbleService.restoreAfterScreenshot()
            }
        }
        
        if (screenshotData == null) {
            return ToolResult.Error("Failed to capture screenshot. This device may not support screen capture via accessibility service (requires Android 11+).")
        }

        // Build informative message with coordinate guidance
        val focusPrompt = if (focusArea != null) "\n\nFocus on: $focusArea" else ""
        val message = """Screenshot captured with coordinate grid overlay.

SCREEN DIMENSIONS: ${screenshotData.originalWidth} x ${screenshotData.originalHeight} pixels

HOW TO READ COORDINATES:
- Red grid lines are drawn every 200 pixels
- X coordinates are labeled at the top (horizontal position)
- Y coordinates are labeled on the left (vertical position)
- Use the grid intersections to estimate element positions
- For a button between grid lines, interpolate the position

When you identify an element to click, provide its CENTER coordinates for click_element(x, y).$focusPrompt

Analyze the screenshot and describe the UI elements you see with their approximate coordinates."""

        return ToolResult.SuccessWithImage(
            message = message,
            base64Image = screenshotData.base64Image,
            mimeType = "image/jpeg"
        )
    }
}

/**
 * Tool to find elements on screen
 */
class FindOnScreenTool : Tool {
    override val name = "find_on_screen"
    override val description = """Find specific UI elements on the current screen. Search by text content, description, or element type. Returns matching elements with their positions."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "text" to ToolProperty(
                type = "string",
                description = "Text to search for in element labels"
            ),
            "type" to ToolProperty(
                type = "string",
                description = "Type of element to find: 'button', 'input', 'text', 'checkbox', or 'any'",
                enum = listOf("button", "input", "text", "checkbox", "any")
            )
        ),
        required = listOf("text")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val searchText = arguments["text"] as? String ?: return ToolResult.Error("Search text is required")
        val type = arguments["type"] as? String ?: "any"

        val clickableOnly = type == "button"
        val elements = service.findElements(
            text = searchText,
            clickableOnly = clickableOnly
        )

        val filtered = when (type) {
            "button" -> elements.filter { it.isClickable }
            "input" -> elements.filter { it.isEditable }
            "checkbox" -> elements.filter { it.isCheckable }
            "text" -> elements.filter { !it.isClickable && !it.isEditable && it.text.isNotEmpty() }
            else -> elements
        }

        return if (filtered.isEmpty()) {
            ToolResult.Success("No elements found matching '$searchText'")
        } else {
            val result = StringBuilder("Found ${filtered.size} element(s):\n")
            filtered.forEachIndexed { index, element ->
                val typeStr = when {
                    element.isEditable -> "Input"
                    element.isClickable -> "Button"
                    element.isCheckable -> if (element.isChecked) "Checked" else "Unchecked"
                    else -> "Text"
                }
                result.appendLine("${index + 1}. [$typeStr] ${element.text.ifEmpty { element.contentDescription }}")
            }
            ToolResult.Success(result.toString())
        }
    }
}

/**
 * Tool to click/tap on screen elements
 */
class ClickElementTool : Tool {
    override val name = "click_element"
    override val description = """Click/tap on a UI element on the screen. You can click by text content, description, or screen coordinates (x, y).

IMPORTANT: If clicking by text/description FAILS twice, the system automatically enables VISION MODE. In vision mode:
- Always use read_screen_vision to capture screenshots
- Always use x,y coordinates (not text) to click
- The app has poor accessibility support, so coordinates are required

If clicking by text/description FAILS, use read_screen_vision immediately to capture the screen with coordinate grid, then use the x,y coordinates from that image."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "text" to ToolProperty(
                type = "string",
                description = "Text of the element to click (button label, menu item, etc.)"
            ),
            "description" to ToolProperty(
                type = "string",
                description = "Content description of the element (for icon buttons like Send, Back, etc.)"
            ),
            "x" to ToolProperty(
                type = "integer",
                description = "X coordinate for direct tap (use with y). Get coordinates from read_screen_vision."
            ),
            "y" to ToolProperty(
                type = "integer",
                description = "Y coordinate for direct tap (use with x). Get coordinates from read_screen_vision."
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val text = arguments["text"] as? String
        val description = arguments["description"] as? String
        val x = (arguments["x"] as? Number)?.toInt()
        val y = (arguments["y"] as? Number)?.toInt()

        // For coordinate-based clicks
        if (x != null && y != null) {
            return if (service.clickAtPosition(x, y)) {
                ToolResult.Success("Clicked successfully")
            } else {
                ToolResult.Error("Could not click at ($x, $y). Coordinates may be wrong. Use read_screen_vision to get accurate coordinates.")
            }
        }

        // For text or description based clicks - TRY BOTH before failing
        if (text != null) {
            // Try text first
            if (service.clickByText(text)) {
                return ToolResult.Success("Clicked successfully")
            }
            // Fallback: try as description (icon buttons may have description matching the text)
            if (service.clickByDescription(text)) {
                return ToolResult.Success("Clicked successfully")
            }
        }
        
        if (description != null) {
            // Try description first
            if (service.clickByDescription(description)) {
                return ToolResult.Success("Clicked successfully")
            }
            // Fallback: try as text (some buttons have text matching description)
            if (service.clickByText(description)) {
                return ToolResult.Success("Clicked successfully")
            }
        }

        if (text == null && description == null) {
            return ToolResult.Error("Please provide either 'text', 'description', or both 'x' and 'y' coordinates")
        }

        // Both approaches failed - provide helpful error message
        val target = text ?: description!!
        return ToolResult.Error("Could not click '$target'. Element not found via accessibility. USE read_screen_vision NOW to capture screenshot with coordinate grid, then click using x,y coordinates.")
    }
}

/**
 * Tool to toggle a switch or checkbox
 */
class ToggleSwitchTool : Tool {
    override val name = "toggle_switch"
    override val description = """Toggle a switch, checkbox, or toggle button on the screen. Use this to turn settings on/off. Provide the label text near the switch."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "label" to ToolProperty(
                type = "string",
                description = "The label text near the switch/toggle (e.g., 'Use Dark theme', 'Wi-Fi', 'Bluetooth')"
            ),
            "x" to ToolProperty(
                type = "integer",
                description = "Alternative: X coordinate of the switch to tap"
            ),
            "y" to ToolProperty(
                type = "integer",
                description = "Alternative: Y coordinate of the switch to tap"
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val label = arguments["label"] as? String
        val x = (arguments["x"] as? Number)?.toInt()
        val y = (arguments["y"] as? Number)?.toInt()

        val success = when {
            label != null -> service.toggleSwitch(label)
            x != null && y != null -> service.clickAtPosition(x, y)
            else -> return ToolResult.Error("Please provide either 'label' or both 'x' and 'y' coordinates")
        }

        return if (success) {
            ToolResult.Success("Toggle switched successfully")
        } else {
            // Try clicking by text as fallback
            if (label != null && service.clickByText(label)) {
                ToolResult.Success("Toggle switched successfully (via text click)")
            } else {
                ToolResult.Error("Could not toggle the switch. Try using coordinates from read_screen.")
            }
        }
    }
}

/**
 * Tool to input text into fields
 */
class InputTextTool : Tool {
    override val name = "input_text"
    override val description = """Type text into an input field on the screen. Can target a specific field by hint/label or type into the currently focused field."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "text" to ToolProperty(
                type = "string",
                description = "The text to input"
            ),
            "field" to ToolProperty(
                type = "string",
                description = "Optional: hint or label of the target input field. If not provided, types into focused field."
            )
        ),
        required = listOf("text")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val text = arguments["text"] as? String ?: return ToolResult.Error("Text is required")
        val field = arguments["field"] as? String

        val success = service.inputText(text, field)

        return if (success) {
            ToolResult.Success("Text entered successfully")
        } else {
            ToolResult.Error("Could not enter text. Make sure an input field is focused or specify the field hint.")
        }
    }
}

/**
 * Tool to scroll the screen
 */
class ScrollScreenTool : Tool {
    override val name = "scroll_screen"
    override val description = """Scroll the current screen in a direction. Use this to see more content that's not visible."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "direction" to ToolProperty(
                type = "string",
                description = "Direction to scroll: 'up', 'down', 'left', or 'right'",
                enum = listOf("up", "down", "left", "right")
            )
        ),
        required = listOf("direction")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val directionStr = arguments["direction"] as? String ?: return ToolResult.Error("Direction is required")
        
        val direction = when (directionStr.lowercase()) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> return ToolResult.Error("Invalid direction. Use 'up', 'down', 'left', or 'right'")
        }

        val success = service.scroll(direction)

        return if (success) {
            ToolResult.Success("Scrolled $directionStr")
        } else {
            ToolResult.Error("Could not scroll. The current view may not be scrollable.")
        }
    }
}

/**
 * Tool to perform swipe gestures
 */
class SwipeGestureTool : Tool {
    override val name = "swipe"
    override val description = """Perform a swipe gesture on the screen. Useful for dismissing items, navigating carousels, etc."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "direction" to ToolProperty(
                type = "string",
                description = "Quick swipe direction: 'up', 'down', 'left', 'right'",
                enum = listOf("up", "down", "left", "right")
            ),
            "start_x" to ToolProperty(
                type = "integer",
                description = "Custom swipe start X coordinate"
            ),
            "start_y" to ToolProperty(
                type = "integer",
                description = "Custom swipe start Y coordinate"
            ),
            "end_x" to ToolProperty(
                type = "integer",
                description = "Custom swipe end X coordinate"
            ),
            "end_y" to ToolProperty(
                type = "integer",
                description = "Custom swipe end Y coordinate"
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val direction = arguments["direction"] as? String
        val startX = (arguments["start_x"] as? Number)?.toInt()
        val startY = (arguments["start_y"] as? Number)?.toInt()
        val endX = (arguments["end_x"] as? Number)?.toInt()
        val endY = (arguments["end_y"] as? Number)?.toInt()

        // Get screen dimensions (approximate center)
        val centerX = 540 // Typical phone width / 2
        val centerY = 1200 // Typical phone height / 2
        val swipeDistance = 500

        val (sX, sY, eX, eY) = if (startX != null && startY != null && endX != null && endY != null) {
            listOf(startX, startY, endX, endY)
        } else when (direction?.lowercase()) {
            "up" -> listOf(centerX, centerY + swipeDistance/2, centerX, centerY - swipeDistance/2)
            "down" -> listOf(centerX, centerY - swipeDistance/2, centerX, centerY + swipeDistance/2)
            "left" -> listOf(centerX + swipeDistance/2, centerY, centerX - swipeDistance/2, centerY)
            "right" -> listOf(centerX - swipeDistance/2, centerY, centerX + swipeDistance/2, centerY)
            else -> return ToolResult.Error("Provide either 'direction' or all coordinate parameters")
        }

        val success = service.swipe(sX, sY, eX, eY)

        return if (success) {
            ToolResult.Success("Swipe performed successfully")
        } else {
            ToolResult.Error("Could not perform swipe gesture")
        }
    }
}

/**
 * Tool for navigation actions (back, home, etc.)
 */
class NavigationTool : Tool {
    override val name = "navigate"
    override val description = """Perform system navigation actions like going back, going home, opening recent apps, etc."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ToolProperty(
                type = "string",
                description = "Navigation action to perform",
                enum = listOf("back", "home", "recents", "notifications", "quick_settings")
            )
        ),
        required = listOf("action")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val action = arguments["action"] as? String ?: return ToolResult.Error("Action is required")

        val success = when (action.lowercase()) {
            "back" -> service.goBack()
            "home" -> service.goHome()
            "recents" -> service.openRecentApps()
            "notifications" -> service.openNotifications()
            "quick_settings" -> service.openQuickSettings()
            else -> return ToolResult.Error("Unknown action: $action")
        }

        return if (success) {
            ToolResult.Success("Navigation action '$action' performed")
        } else {
            ToolResult.Error("Could not perform navigation action")
        }
    }
}

/**
 * Tool to press Enter/Search key on keyboard for submitting searches and forms
 */
class PressKeyTool : Tool {
    override val name = "press_key"
    override val description = """Press a key on the keyboard or find and click submit/send buttons. Use this to submit search queries, forms, send messages, etc. Most commonly used to press Enter/Search after typing in a search box or to send messages in chat apps."""

    override val parameters = ToolParameters(
        properties = mapOf(
            "key" to ToolProperty(
                type = "string",
                description = "The key to press: 'enter', 'search', 'go', 'send', 'done', or 'next'",
                enum = listOf("enter", "search", "go", "send", "done", "next")
            )
        ),
        required = listOf("key")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val key = arguments["key"] as? String ?: return ToolResult.Error("Key is required")

        val success = when (key.lowercase()) {
            "enter", "search", "go", "send", "done", "next" -> {
                service.pressEnter() || service.pressImeAction(key)
            }
            else -> return ToolResult.Error("Unknown key: $key. Use 'enter', 'search', 'go', 'send', 'done', or 'next'")
        }

        if (success) {
            return ToolResult.Success("Pressed '$key' key successfully")
        }

        // Fallback: try to click on various send/search/submit buttons
        val buttonVariants = when (key.lowercase()) {
            "send" -> listOf(
                // Content descriptions
                Pair(true, "Send"),
                Pair(true, "send"),
                Pair(true, "Send message"),
                Pair(true, "Send Message"),
                // Text variants
                Pair(false, "Send"),
                Pair(false, "SEND"),
                Pair(false, "➤"),
                Pair(false, "➜"),
                Pair(false, "→")
            )
            "search" -> listOf(
                Pair(true, "Search"),
                Pair(true, "search"),
                Pair(false, "Search"),
                Pair(false, "SEARCH"),
                Pair(true, "Go"),
                Pair(false, "Go")
            )
            else -> listOf(
                Pair(true, "Go"),
                Pair(true, "Search"),
                Pair(true, "Send"),
                Pair(true, "Submit"),
                Pair(false, "Go"),
                Pair(false, "Search"),
                Pair(false, "Send"),
                Pair(false, "Submit"),
                Pair(false, "OK"),
                Pair(false, "Done")
            )
        }

        for ((isDescription, text) in buttonVariants) {
            val clicked = if (isDescription) {
                service.clickByDescription(text)
            } else {
                service.clickByText(text)
            }
            if (clicked) {
                return ToolResult.Success("Pressed '$key' successfully (via button click)")
            }
        }

        return ToolResult.Error("Could not press '$key' key. Try using click_element to tap the send/submit button directly.")
    }
}

/**
 * Tool to adjust seekbar/slider elements (brightness, volume, etc.)
 */
class SetSeekbarTool : Tool {
    override val name = "set_seekbar"
    override val description = """Adjust a seekbar or slider on the screen (like brightness, volume sliders). Can set to a specific percentage or adjust relatively. Use this for brightness sliders, volume controls, and other slider UI elements."""
    
    override val parameters = ToolParameters(
        properties = mapOf(
            "value" to ToolProperty(
                type = "integer",
                description = "Target percentage value (0-100). For example: 0=minimum, 50=middle, 100=maximum"
            ),
            "adjust" to ToolProperty(
                type = "integer",
                description = "Relative adjustment (-100 to +100). Use negative to decrease, positive to increase. E.g., -20 to lower by 20%"
            ),
            "label" to ToolProperty(
                type = "string",
                description = "Optional label near the seekbar to identify it (e.g., 'Brightness', 'Volume')"
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val service = InvoctaAccessibilityService.getInstance()
            ?: return ToolResult.Error("Screen control is not enabled. Please enable Invocta Accessibility Service in Settings.")

        val value = (arguments["value"] as? Number)?.toInt()
        val adjust = (arguments["adjust"] as? Number)?.toInt()
        val label = arguments["label"] as? String

        // First, check if we can find a seekbar
        val seekbarInfo = service.findSeekbar(label)
            ?: return ToolResult.Error("No seekbar/slider found on screen${if (label != null) " near '$label'" else ""}. Make sure the brightness/volume slider is visible.")

        val success = when {
            value != null -> {
                // Set to absolute value
                val clampedValue = value.coerceIn(0, 100)
                service.dragSeekbar(clampedValue, label) || service.setSeekbarValue(clampedValue, label)
            }
            adjust != null -> {
                // Adjust relatively
                service.adjustSeekbar(adjust, label)
            }
            else -> return ToolResult.Error("Please provide either 'value' (0-100) for absolute setting or 'adjust' for relative change")
        }

        return if (success) {
            val currentInfo = service.findSeekbar(label)
            val newPercent = currentInfo?.currentPercent ?: "unknown"
            when {
                value != null -> ToolResult.Success("Seekbar set to $value%. Current value: $newPercent%")
                adjust != null && adjust < 0 -> ToolResult.Success("Seekbar decreased. Current value: $newPercent%")
                adjust != null -> ToolResult.Success("Seekbar increased. Current value: $newPercent%")
                else -> ToolResult.Success("Seekbar adjusted. Current value: $newPercent%")
            }
        } else {
            ToolResult.Error("Could not adjust the seekbar. Try dragging with swipe gesture or tapping on the slider track.")
        }
    }
}

/**
 * Tool to check if screen control is enabled
 */
class ScreenControlStatusTool(private val context: Context) : Tool {
    override val name = "screen_control_status"
    override val description = """Check if screen control (accessibility service) is enabled. If not enabled, provides instructions to enable it."""
    
    override val parameters = ToolParameters(
        properties = emptyMap(),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val isEnabled = InvoctaAccessibilityService.isRunning()
        
        return if (isEnabled) {
            ToolResult.Success("Screen control is enabled and ready to use.")
        } else {
            ToolResult.Success(
                "Screen control is NOT enabled. To enable it:\n" +
                "1. Go to Settings > Accessibility\n" +
                "2. Find 'Invocta' in the list\n" +
                "3. Enable the Invocta accessibility service\n" +
                "4. Grant the required permissions\n\n" +
                "Would you like me to open the Accessibility Settings for you?"
            )
        }
    }
}

/**
 * Tool to open accessibility settings
 */
class OpenAccessibilitySettingsTool(private val context: Context) : Tool {
    override val name = "open_accessibility_settings"
    override val description = """Open the system Accessibility Settings so the user can enable screen control."""
    
    override val parameters = ToolParameters(
        properties = emptyMap(),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Opened Accessibility Settings. Please find and enable 'Invocta' in the list.")
        } catch (e: Exception) {
            ToolResult.Error("Could not open settings: ${e.message}")
        }
    }
}

