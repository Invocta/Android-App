package com.invocta.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Accessibility Service that provides screen control capabilities to the AI agent.
 * 
 * This service can:
 * - Read screen content (UI hierarchy)
 * - Perform click actions on UI elements
 * - Scroll through content
 * - Input text into fields
 * - Perform gestures (swipe, tap, etc.)
 * - Take screenshots for AI vision analysis
 */
class InvoctaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "InvoctaAccessibility"
        private var instance: InvoctaAccessibilityService? = null

        private val _isServiceEnabled = MutableStateFlow(false)
        val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()
        
        private val _currentScreenContent = MutableStateFlow<ScreenContent?>(null)
        val currentScreenContent: StateFlow<ScreenContent?> = _currentScreenContent.asStateFlow()

        /**
         * Get the service instance if available
         */
        fun getInstance(): InvoctaAccessibilityService? = instance

        /**
         * Check if the service is currently running
         */
        fun isRunning(): Boolean = instance != null
    }

    // Handler for debouncing screen content updates
    private val handler = Handler(Looper.getMainLooper())
    private var pendingUpdateRunnable: Runnable? = null

    // Debounce delay for screen content updates (ms)
    private val UPDATE_DEBOUNCE_MS = 300L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceEnabled.value = true

        // Configure the service
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or 
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        // Skip events from our own app to avoid blocking assistant overlay launch
        val packageName = event.packageName?.toString() ?: ""
        if (packageName == "com.invocta") {
            return
        }

        // Update screen content on window changes, but debounce to avoid blocking
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleScreenContentUpdate()
            }
        }
    }

    /**
     * Schedule a debounced screen content update to avoid blocking main thread
     */
    private fun scheduleScreenContentUpdate() {
        // Cancel any pending update
        pendingUpdateRunnable?.let { handler.removeCallbacks(it) }

        // Schedule new update with debounce
        pendingUpdateRunnable = Runnable {
            // Only update if we're not currently being used by our overlay
            // The tools will fetch fresh content when needed
            updateScreenContent()
        }
        handler.postDelayed(pendingUpdateRunnable!!, UPDATE_DEBOUNCE_MS)
    }

    override fun onInterrupt() {
        // Handle interrupt
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceEnabled.value = false
        // Cancel any pending updates
        pendingUpdateRunnable?.let { handler.removeCallbacks(it) }
        pendingUpdateRunnable = null
        // Remove any overlay views
        overlayView?.let {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                wm.removeView(it)
            } catch (e: Exception) {
                // View might not be attached
            }
        }
        overlayView = null
    }
    
    // Overlay view management - uses accessibility service's window token
    private var overlayView: android.view.View? = null
    private var overlayParams: android.view.WindowManager.LayoutParams? = null
    
    /**
     * Add an overlay view using the accessibility service's window token.
     * This allows showing views over ALL apps including system apps.
     */
    fun addOverlayView(view: android.view.View, params: android.view.WindowManager.LayoutParams): Boolean {
        return try {
            // Make sure we're using TYPE_ACCESSIBILITY_OVERLAY
            params.type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.addView(view, params)
            overlayView = view
            overlayParams = params
            true
        } catch (e: Exception) {
            android.util.Log.e("Accessibility", "Failed to add overlay view", e)
            false
        }
    }
    
    /**
     * Update the overlay view params
     */
    fun updateOverlayView(view: android.view.View, params: android.view.WindowManager.LayoutParams) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            android.util.Log.e("Accessibility", "Failed to update overlay view", e)
        }
    }
    
    /**
     * Remove the overlay view
     */
    fun removeOverlayView(view: android.view.View) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.removeView(view)
            if (overlayView == view) {
                overlayView = null
                overlayParams = null
            }
        } catch (e: Exception) {
            android.util.Log.e("Accessibility", "Failed to remove overlay view", e)
        }
    }

    /**
     * Get the current screen content as a structured hierarchy.
     * Tries multiple times if the first attempt returns empty content.
     */
    fun getScreenContent(): ScreenContent {
        // Try to get root node, with retries
        var rootNode: AccessibilityNodeInfo? = null
        var attempts = 0
        val maxAttempts = 3
        
        while (rootNode == null && attempts < maxAttempts) {
            rootNode = rootInActiveWindow
            if (rootNode == null) {
                attempts++
                try {
                    Thread.sleep(100) // Wait a bit before retry
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        
        if (rootNode == null) {
            return ScreenContent(
                packageName = "",
                activityName = "",
                elements = emptyList()
            )
        }

        val packageName = rootNode.packageName?.toString() ?: ""
        val elements = mutableListOf<ScreenElement>()
        traverseNode(rootNode, elements, 0)
        
        // Don't recycle rootNode before extracting packageName
        try {
            rootNode.recycle()
        } catch (e: Exception) {
            // Node might already be recycled
        }

        return ScreenContent(
            packageName = packageName,
            activityName = "",
            elements = elements
        )
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, elements: MutableList<ScreenElement>, depth: Int) {
        node ?: return

        // Extract element info
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        // Check if it's a switch/toggle element
        val className = node.className?.toString() ?: ""
        val isSwitch = className.contains("Switch") || 
                       className.contains("Toggle") ||
                       className.contains("CheckBox") ||
                       className.contains("CompoundButton")
        
        // Check if it's a seekbar/slider
        val isSeekbar = className.contains("SeekBar", ignoreCase = true) ||
                       className.contains("Slider", ignoreCase = true) ||
                       (className.contains("ProgressBar", ignoreCase = true) && node.rangeInfo != null) ||
                       node.rangeInfo != null

        // Get range info for seekbars
        val rangeInfo = node.rangeInfo
        val seekbarPercent = if (isSeekbar && rangeInfo != null) {
            val currentValue = rangeInfo.current
            val minValue = rangeInfo.min
            val maxValue = rangeInfo.max
            if (maxValue > minValue) {
                ((currentValue - minValue) / (maxValue - minValue) * 100).toInt()
            } else null
        } else null

        val element = ScreenElement(
            id = node.viewIdResourceName ?: "",
            className = className,
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable || isSwitch,
            isChecked = node.isChecked,
            isFocusable = node.isFocusable,
            isSeekbar = isSeekbar,
            seekbarPercent = seekbarPercent,
            bounds = ElementBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            depth = depth
        )

        // Add meaningful elements - include switches and seekbars even without text
        if (element.text.isNotEmpty() ||
            element.contentDescription.isNotEmpty() || 
            element.isClickable || 
            element.isEditable ||
            element.isCheckable ||
            element.isSeekbar) {
            elements.add(element)
        }

        // Traverse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseNode(child, elements, depth + 1)
            child?.recycle()
        }
    }

    private fun updateScreenContent() {
        _currentScreenContent.value = getScreenContent()
    }

    /**
     * Find elements matching the given criteria
     */
    fun findElements(
        text: String? = null,
        contentDescription: String? = null,
        className: String? = null,
        id: String? = null,
        clickableOnly: Boolean = false
    ): List<ScreenElement> {
        val content = getScreenContent()
        return content.elements.filter { element ->
            val matchesText = text == null || element.text.contains(text, ignoreCase = true)
            val matchesDesc = contentDescription == null || 
                element.contentDescription.contains(contentDescription, ignoreCase = true)
            val matchesClass = className == null || element.className.contains(className, ignoreCase = true)
            val matchesId = id == null || element.id.contains(id, ignoreCase = true)
            val matchesClickable = !clickableOnly || element.isClickable

            matchesText && matchesDesc && matchesClass && matchesId && matchesClickable
        }
    }

    /**
     * Click on an element by its text content
     */
    fun clickByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = findAndClickNode(rootNode, text, MatchType.TEXT)
        rootNode.recycle()
        return result
    }

    /**
     * Click on an element by its content description
     */
    fun clickByDescription(description: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = findAndClickNode(rootNode, description, MatchType.CONTENT_DESCRIPTION)
        rootNode.recycle()
        return result
    }

    /**
     * Click on an element by its resource ID
     */
    fun clickById(id: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = findAndClickNode(rootNode, id, MatchType.ID)
        rootNode.recycle()
        return result
    }

    /**
     * Click at specific screen coordinates
     */
    fun clickAtPosition(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Toggle a switch or checkbox near the given text label
     */
    fun toggleSwitch(nearText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = findAndToggleSwitch(rootNode, nearText)
        try { rootNode.recycle() } catch (e: Exception) {}
        return result
    }

    private fun findAndToggleSwitch(node: AccessibilityNodeInfo?, nearText: String): Boolean {
        node ?: return false

        // First, find the text label
        val textNode = findNodeWithText(node, nearText)
        if (textNode != null) {
            // Look for a checkable element (switch/toggle) near this text
            // Check siblings and parent's children
            val parent = textNode.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i)
                    if (sibling != null) {
                        val toggleNode = findCheckableNode(sibling)
                        if (toggleNode != null) {
                            val result = toggleNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            toggleNode.recycle()
                            sibling.recycle()
                            parent.recycle()
                            textNode.recycle()
                            return result
                        }
                        sibling.recycle()
                    }
                }
                parent.recycle()
            }
            textNode.recycle()
        }

        return false
    }

    private fun findNodeWithText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        node ?: return null
        
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findNodeWithText(child, text)
            child?.recycle()
            if (result != null) return result
        }
        
        return null
    }

    private fun findCheckableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        
        val className = node.className?.toString() ?: ""
        if (node.isCheckable || 
            className.contains("Switch") || 
            className.contains("Toggle") ||
            className.contains("CheckBox")) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findCheckableNode(child)
            child?.recycle()
            if (result != null) return result
        }
        
        return null
    }

    private enum class MatchType { TEXT, CONTENT_DESCRIPTION, ID }

    private fun findAndClickNode(node: AccessibilityNodeInfo?, target: String, matchType: MatchType): Boolean {
        node ?: return false

        val matches = when (matchType) {
            MatchType.TEXT -> node.text?.toString()?.contains(target, ignoreCase = true) == true
            MatchType.CONTENT_DESCRIPTION -> 
                node.contentDescription?.toString()?.contains(target, ignoreCase = true) == true
            MatchType.ID -> node.viewIdResourceName?.contains(target, ignoreCase = true) == true
        }

        if (matches) {
            // If this node is checkable (switch/toggle), click it directly
            if (node.isCheckable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // Try clicking on parent if this node isn't clickable
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    return result
                }
                val oldParent = parent
                parent = parent.parent
                oldParent.recycle()
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (findAndClickNode(child, target, matchType)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }

        return false
    }
    /**
     * Input text into a focused field or find and fill a field
     */
    fun inputText(text: String, fieldHint: String? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        
        val targetNode = if (fieldHint != null) {
            findEditableNode(rootNode, fieldHint)
        } else {
            findFocusedEditableNode(rootNode)
        }

        val result = if (targetNode != null) {
            // Focus the node first
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            
            // Set the text
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            targetNode.recycle()
            success
        } else {
            false
        }

        rootNode.recycle()
        return result
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?, hint: String): AccessibilityNodeInfo? {
        node ?: return null

        if (node.isEditable) {
            val matchesHint = node.text?.toString()?.contains(hint, ignoreCase = true) == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
                    node.hintText?.toString()?.contains(hint, ignoreCase = true) == true) ||
                node.contentDescription?.toString()?.contains(hint, ignoreCase = true) == true
            if (matchesHint || hint.isEmpty()) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findEditableNode(child, hint)
            child?.recycle()
            if (result != null) return result
        }

        return null
    }

    private fun findFocusedEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null

        if (node.isEditable && node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findFocusedEditableNode(child)
            child?.recycle()
            if (result != null) return result
        }

        return null
    }

    /**
     * Scroll in a direction
     */
    fun scroll(direction: ScrollDirection): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = findAndScrollNode(rootNode, direction)
        rootNode.recycle()
        return result
    }

    private fun findAndScrollNode(node: AccessibilityNodeInfo?, direction: ScrollDirection): Boolean {
        node ?: return false

        if (node.isScrollable) {
            val action = when (direction) {
                ScrollDirection.UP, ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScrollDirection.DOWN, ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            return node.performAction(action)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (findAndScrollNode(child, direction)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }

        return false
    }

    /**
     * Perform a swipe gesture
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Go back (press back button)
     */
    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Go home (press home button)
     */
    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Open recent apps
     */
    fun openRecentApps(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Open notifications panel
     */
    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * Open quick settings
     */
    fun openQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /**
     * Take a screenshot (API 28+) - saves to device
     */
    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }
    
    /**
     * Capture the current screen as a Bitmap (API 30+).
     * Returns null if screenshot capture fails or is not supported.
     */
    suspend fun captureScreenBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot capture requires API 30+")
            return null
        }
        
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val executor = Executor { command -> handler.post(command) }
                    
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        executor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                try {
                                    val bitmap = Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer,
                                        screenshot.colorSpace
                                    )
                                    screenshot.hardwareBuffer.close()
                                    
                                    // Convert hardware bitmap to software bitmap for processing
                                    val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    bitmap?.recycle()
                                    
                                    if (continuation.isActive) {
                                        continuation.resume(softwareBitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing screenshot", e)
                                    if (continuation.isActive) {
                                        continuation.resume(null)
                                    }
                                }
                            }
                            
                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "Screenshot failed with error code: $errorCode")
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error initiating screenshot", e)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
    
    /**
     * Capture the current screen and return as Base64 encoded PNG string.
     * Returns null if screenshot capture fails.
     * 
     * @param quality JPEG quality (0-100), only used if useJpeg is true
     * @param maxWidth Maximum width to resize to (maintains aspect ratio)
     * @param useJpeg If true, uses JPEG compression (smaller size), else PNG
     */
    suspend fun captureScreenBase64(
        quality: Int = 85,
        maxWidth: Int = 1080,
        useJpeg: Boolean = true
    ): String? {
        val bitmap = captureScreenBitmap() ?: return null
        
        return try {
            // Resize if needed to reduce token usage
            val scaledBitmap = if (bitmap.width > maxWidth) {
                val scale = maxWidth.toFloat() / bitmap.width
                val newHeight = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
            
            val outputStream = ByteArrayOutputStream()
            if (useJpeg) {
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            } else {
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            scaledBitmap.recycle()
            
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding screenshot to base64", e)
            bitmap.recycle()
            null
        }
    }

    /**
     * Data class for screenshot with coordinate info
     */
    data class ScreenshotWithDimensions(
        val base64Image: String,
        val originalWidth: Int,
        val originalHeight: Int,
        val imageWidth: Int,
        val imageHeight: Int
    )

    /**
     * Capture screenshot with a coordinate grid overlay for accurate AI vision analysis.
     * The grid helps the AI estimate click positions more accurately.
     *
     * @param quality JPEG quality (0-100)
     * @param maxWidth Maximum width to resize to
     * @param gridSpacing Spacing between grid lines in pixels (on original resolution)
     * @return Screenshot with grid overlay and dimension info, or null if capture fails
     */
    suspend fun captureScreenWithGrid(
        quality: Int = 85,
        maxWidth: Int = 1080,
        gridSpacing: Int = 200
    ): ScreenshotWithDimensions? {
        val bitmap = captureScreenBitmap() ?: return null
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        return try {
            // Create a mutable copy to draw on
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            bitmap.recycle()

            val canvas = Canvas(mutableBitmap)

            // Paint for grid lines - semi-transparent
            val gridPaint = Paint().apply {
                color = Color.argb(80, 255, 0, 0)  // Semi-transparent red
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }

            // Paint for coordinate labels
            val textPaint = Paint().apply {
                color = Color.argb(200, 255, 255, 255)  // White with background
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }

            // Paint for text background
            val bgPaint = Paint().apply {
                color = Color.argb(180, 0, 0, 0)  // Semi-transparent black
                style = Paint.Style.FILL
            }

            // Draw vertical grid lines with x-coordinate labels
            var x = gridSpacing
            while (x < originalWidth) {
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), originalHeight.toFloat(), gridPaint)
                // Draw coordinate label at top
                val label = "$x"
                val textWidth = textPaint.measureText(label)
                canvas.drawRect(x - textWidth/2 - 4, 0f, x + textWidth/2 + 4, 36f, bgPaint)
                canvas.drawText(label, x - textWidth/2, 28f, textPaint)
                x += gridSpacing
            }

            // Draw horizontal grid lines with y-coordinate labels
            var y = gridSpacing
            while (y < originalHeight) {
                canvas.drawLine(0f, y.toFloat(), originalWidth.toFloat(), y.toFloat(), gridPaint)
                // Draw coordinate label at left
                val label = "$y"
                val textWidth = textPaint.measureText(label)
                canvas.drawRect(0f, y - 18f, textWidth + 8, y + 10f, bgPaint)
                canvas.drawText(label, 4f, y + 8f, textPaint)
                y += gridSpacing
            }

            // Draw screen dimensions at bottom-right corner
            val dimLabel = "${originalWidth}x${originalHeight}"
            val dimTextWidth = textPaint.measureText(dimLabel)
            canvas.drawRect(
                originalWidth - dimTextWidth - 12f,
                originalHeight - 40f,
                originalWidth.toFloat(),
                originalHeight.toFloat(),
                bgPaint
            )
            canvas.drawText(dimLabel, originalWidth - dimTextWidth - 6f, originalHeight - 12f, textPaint)

            // Resize if needed
            val scaledBitmap = if (mutableBitmap.width > maxWidth) {
                val scale = maxWidth.toFloat() / mutableBitmap.width
                val newHeight = (mutableBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(mutableBitmap, maxWidth, newHeight, true).also {
                    if (it != mutableBitmap) mutableBitmap.recycle()
                }
            } else {
                mutableBitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            val result = ScreenshotWithDimensions(
                base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP),
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                imageWidth = scaledBitmap.width,
                imageHeight = scaledBitmap.height
            )

            scaledBitmap.recycle()
            result

        } catch (e: Exception) {
            Log.e(TAG, "Error creating screenshot with grid", e)
            bitmap.recycle()
            null
        }
    }

    /**
     * Get the current screen dimensions.
     */
    fun getScreenDimensions(): Pair<Int, Int>? {
        return try {
            val displayMetrics = resources.displayMetrics
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen dimensions", e)
            null
        }
    }

    /**
     * Lock the screen (API 28+)
     */
    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    /**
     * Press Enter/Search key on the keyboard.
     * This is useful for submitting search queries or forms.
     */
    fun pressEnter(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        
        // Try to find focused editable node and perform IME action
        val focusedNode = findFocusedEditableNode(rootNode)
        val result = if (focusedNode != null) {
            // Try ACTION_IME_ENTER first (API 30+)
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                // Fallback: try to click the IME action button via input connection
                // or perform a generic "next" action
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
            }
            focusedNode.recycle()
            success
        } else {
            false
        }
        
        rootNode.recycle()
        return result
    }

    /**
     * Press a specific key action on the focused input.
     * @param action The IME action to perform: "search", "go", "send", "next", "done"
     */
    fun pressImeAction(action: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        
        val focusedNode = findFocusedEditableNode(rootNode)
        val result = if (focusedNode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val success = when (action.lowercase()) {
                "enter", "search", "go", "send", "done" -> {
                    focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                else -> false
            }
            focusedNode.recycle()
            success
        } else {
            focusedNode?.recycle()
            false
        }
        
        rootNode.recycle()
        return result
    }

    /**
     * Find a seekbar/slider element on screen, optionally near a label
     */
    fun findSeekbar(nearLabel: String? = null): SeekbarInfo? {
        val rootNode = rootInActiveWindow ?: return null
        
        val result = if (nearLabel != null) {
            findSeekbarNearLabel(rootNode, nearLabel)
        } else {
            findFirstSeekbar(rootNode)
        }
        
        try { rootNode.recycle() } catch (e: Exception) {}
        return result
    }

    private fun findSeekbarNearLabel(node: AccessibilityNodeInfo?, label: String): SeekbarInfo? {
        node ?: return null
        
        // First find the label
        val labelNode = findNodeWithText(node, label)
        if (labelNode != null) {
            // Look for a seekbar in the parent hierarchy or siblings
            val parent = labelNode.parent
            if (parent != null) {
                val seekbar = findSeekbarInTree(parent)
                if (seekbar != null) {
                    try { labelNode.recycle() } catch (e: Exception) {}
                    try { parent.recycle() } catch (e: Exception) {}
                    return seekbar
                }
                try { parent.recycle() } catch (e: Exception) {}
            }
            try { labelNode.recycle() } catch (e: Exception) {}
        }
        
        // Fallback: find first seekbar if no label match
        return findFirstSeekbar(node)
    }

    private fun findFirstSeekbar(node: AccessibilityNodeInfo?): SeekbarInfo? {
        return findSeekbarInTree(node)
    }

    private fun findSeekbarInTree(node: AccessibilityNodeInfo?): SeekbarInfo? {
        node ?: return null
        
        val className = node.className?.toString() ?: ""
        val isSeekbar = className.contains("SeekBar", ignoreCase = true) ||
                       className.contains("Slider", ignoreCase = true) ||
                       className.contains("ProgressBar", ignoreCase = true) ||
                       node.rangeInfo != null
        
        if (isSeekbar) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            val rangeInfo = node.rangeInfo
            val currentValue = rangeInfo?.current ?: 0f
            val minValue = rangeInfo?.min ?: 0f
            val maxValue = rangeInfo?.max ?: 100f
            val currentPercent = if (maxValue > minValue) {
                ((currentValue - minValue) / (maxValue - minValue) * 100).toInt()
            } else {
                50
            }
            
            return SeekbarInfo(
                bounds = ElementBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                currentPercent = currentPercent,
                minValue = minValue,
                maxValue = maxValue,
                currentValue = currentValue,
                contentDescription = node.contentDescription?.toString() ?: ""
            )
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findSeekbarInTree(child)
            try { child?.recycle() } catch (e: Exception) {}
            if (result != null) return result
        }
        
        return null
    }

    /**
     * Set a seekbar to a specific percentage (0-100)
     */
    fun setSeekbarValue(targetPercent: Int, nearLabel: String? = null): Boolean {
        val seekbarInfo = findSeekbar(nearLabel) ?: return false
        
        val bounds = seekbarInfo.bounds
        val targetX = bounds.left + (bounds.width * targetPercent / 100)
        val centerY = bounds.centerY
        
        // Click at the target position to set the value
        return clickAtPosition(targetX, centerY)
    }

    /**
     * Adjust a seekbar by a relative amount (-100 to +100)
     */
    fun adjustSeekbar(changePercent: Int, nearLabel: String? = null): Boolean {
        val seekbarInfo = findSeekbar(nearLabel) ?: return false
        
        val newPercent = (seekbarInfo.currentPercent + changePercent).coerceIn(0, 100)
        return setSeekbarValue(newPercent, nearLabel)
    }

    /**
     * Drag on a seekbar from current position to target
     */
    fun dragSeekbar(targetPercent: Int, nearLabel: String? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        
        val seekbarInfo = findSeekbar(nearLabel) ?: return false
        val bounds = seekbarInfo.bounds
        
        // Calculate start and end X positions
        val startX = bounds.left + (bounds.width * seekbarInfo.currentPercent / 100)
        val endX = bounds.left + (bounds.width * targetPercent.coerceIn(0, 100) / 100)
        val centerY = bounds.centerY
        
        // Perform a drag gesture
        return swipe(startX, centerY, endX, centerY, 500)
    }
}

/**
 * Information about a seekbar/slider element
 */
data class SeekbarInfo(
    val bounds: ElementBounds,
    val currentPercent: Int,
    val minValue: Float,
    val maxValue: Float,
    val currentValue: Float,
    val contentDescription: String
)

/**
 * Represents the current screen content
 */
data class ScreenContent(
    val packageName: String,
    val activityName: String,
    val elements: List<ScreenElement>
) {
    /**
     * Get a simplified text representation for the AI
     */
    fun toSimpleText(): String {
        val sb = StringBuilder()
        sb.appendLine("Current App: $packageName")
        sb.appendLine("Screen Elements:")
        
        elements.forEachIndexed { index, element ->
            val indent = "  ".repeat(minOf(element.depth, 3))
            val type = when {
                element.isSeekbar -> {
                    val percent = element.seekbarPercent?.let { "$it%" } ?: "?"
                    "[Slider:$percent]"
                }
                element.isEditable -> "[Input]"
                element.isCheckable -> {
                    val state = if (element.isChecked) "ON" else "OFF"
                    "[Toggle:$state]"
                }
                element.isClickable -> "[Button]"
                element.isScrollable -> "[Scrollable]"
                else -> "[Text]"
            }
            
            val content = element.text.ifEmpty { element.contentDescription }
            
            // Include bounds for checkable elements and seekbars so AI can interact with them
            val boundsInfo = if (element.isCheckable || element.isSeekbar) {
                " (tap at x:${element.bounds.centerX}, y:${element.bounds.centerY})"
            } else ""
            
            if (content.isNotEmpty() || element.isCheckable || element.isSeekbar) {
                val displayContent = when {
                    element.isSeekbar && content.isEmpty() -> "Brightness/Volume Slider"
                    content.isEmpty() -> "Toggle"
                    else -> content
                }
                sb.appendLine("$indent$index. $type $displayContent$boundsInfo")
            }
        }
        
        return sb.toString()
    }
}

/**
 * Represents a UI element on screen
 */
data class ScreenElement(
    val id: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isFocusable: Boolean,
    val isSeekbar: Boolean = false,
    val seekbarPercent: Int? = null,
    val bounds: ElementBounds,
    val depth: Int
)

/**
 * Bounds of an element on screen
 */
data class ElementBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Scroll direction
 */
enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

