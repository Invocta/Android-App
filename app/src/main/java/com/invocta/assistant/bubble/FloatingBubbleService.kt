package com.invocta.assistant.bubble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.invocta.R
import com.invocta.assistant.ui.AssistantOverlayActivity
import com.invocta.domain.tools.ToolExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Floating bubble service that shows when the assistant is working in the background
 * (e.g., using accessibility tools to interact with other apps)
 *
 * Uses simple Android Views instead of Compose for better performance in overlay context.
 */
class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var addedViaAccessibilityService = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private var instance: FloatingBubbleService? = null
        
        // Track the active session ID for bubble restoration (during tool execution)
        private var activeSessionId: String? = null
        
        // Overlay session storage key
        private const val OVERLAY_PREFS = "overlay_session_prefs"
        private const val OVERLAY_SESSION_KEY = "overlay_session_id"
        
        fun setActiveSession(sessionId: String?) {
            activeSessionId = sessionId
            android.util.Log.d("FloatingBubble", "Active session set to: $sessionId")
        }
        
        fun getActiveSession(): String? = activeSessionId
        
        /**
         * Save the overlay session ID to persist across overlay open/close.
         * This is separate from the main app's session.
         */
        fun saveOverlaySession(context: Context, sessionId: String?) {
            val prefs = context.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
            if (sessionId != null) {
                prefs.edit().putString(OVERLAY_SESSION_KEY, sessionId).apply()
            } else {
                prefs.edit().remove(OVERLAY_SESSION_KEY).apply()
            }
            android.util.Log.d("FloatingBubble", "Overlay session saved: $sessionId")
        }
        
        /**
         * Get the persisted overlay session ID.
         */
        fun getOverlaySession(context: Context): String? {
            val prefs = context.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
            return prefs.getString(OVERLAY_SESSION_KEY, null)
        }
        
        /**
         * Clear the overlay session (for starting fresh).
         */
        fun clearOverlaySession(context: Context) {
            val prefs = context.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove(OVERLAY_SESSION_KEY).apply()
            android.util.Log.d("FloatingBubble", "Overlay session cleared")
        }

        private val _isShowing = MutableStateFlow(false)
        val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

        private val _statusText = MutableStateFlow("Working...")
        val statusText: StateFlow<String> = _statusText.asStateFlow()

        private val _isProcessing = MutableStateFlow(true)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        fun show(context: Context, status: String = "Working...") {
            // Check for overlay permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                android.util.Log.w("FloatingBubble", "Cannot show bubble - overlay permission not granted")
                return
            }
            
            _statusText.value = status
            _isProcessing.value = true
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_STATUS, status)
            }
            context.startService(intent)
        }

        fun updateStatus(status: String) {
            _statusText.value = status
            instance?.let { service ->
                service.updateStatusText(status)
                // Always update the notification even if bubble isn't visible
                service.updateNotificationStatus(status)
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
        
        /**
         * Called directly by ToolExecutor when all tools are completed.
         * This avoids flow collection issues when activity is in background.
         */
        fun onAllToolsCompleted(context: Context) {
            android.util.Log.d("FloatingBubble", "onAllToolsCompleted called, activeSession=$activeSessionId")
            instance?.let { service ->
                service.handler.post {
                    service.expandToOverlay()
                }
            } ?: run {
                // Service might not be running, just bring overlay to front directly
                android.util.Log.d("FloatingBubble", "Service not running, starting overlay directly")
                val intent = Intent(context, AssistantOverlayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    activeSessionId?.let { putExtra("session_id", it) }
                }
                context.startActivity(intent)
            }
        }
        
        /**
         * Called directly by ToolExecutor when a screen tool starts.
         */
        fun onScreenToolStarted(context: Context, toolName: String) {
            val statusText = when (toolName) {
                "read_screen" -> "Reading screen..."
                "read_screen_vision" -> "Capturing screen..."
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
            updateStatus(statusText)
        }

        /**
         * Temporarily hide the bubble for screenshot capture.
         * Returns true if bubble was visible and is now hidden.
         */
        fun temporarilyHide(): Boolean {
            val service = instance ?: return false
            if (!_isShowing.value || service.bubbleView == null) return false

            android.util.Log.d("FloatingBubble", "Temporarily hiding bubble for screenshot")
            service.bubbleView?.visibility = View.INVISIBLE
            return true
        }

        /**
         * Restore the bubble after screenshot capture.
         */
        fun restoreAfterScreenshot() {
            val service = instance ?: return
            android.util.Log.d("FloatingBubble", "Restoring bubble after screenshot")
            service.bubbleView?.visibility = View.VISIBLE
        }

        fun getInstance(): FloatingBubbleService? = instance
        
        /**
         * Check if Live Update promotions are enabled (Android 15+)
         */
        fun isLiveUpdatePromotionEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    val notificationManager = context.getSystemService(NotificationManager::class.java)
                    val channel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
                    if (channel != null) {
                        val method = NotificationChannel::class.java.getMethod("canPostPromotions")
                        return method.invoke(channel) as Boolean
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FloatingBubble", "Could not check post promotions: ${e.message}")
                }
            }
            return false
        }
        
        /**
         * Open notification promotion settings for Android 15+
         */
        fun openLiveUpdateSettings(context: Context) {
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    val intent = Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").apply {
                        putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.w("FloatingBubble", "Could not open promotion settings: ${e.message}")
                    // Fallback to regular notification settings
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }

        const val ACTION_SHOW = "com.invocta.SHOW_BUBBLE"
        const val ACTION_HIDE = "com.invocta.HIDE_BUBBLE"
        const val ACTION_EXPAND = "com.invocta.EXPAND_BUBBLE"
        const val EXTRA_STATUS = "extra_status"
        
        private const val NOTIFICATION_CHANNEL_ID = "screen_control_channel"
        private const val NOTIFICATION_ID = 2001
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Use application context to get WindowManager to avoid activity lifecycle issues
        windowManager = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Create notification channel
        createNotificationChannel()
        
        // Note: Tool events are now handled via direct calls from ToolExecutor
        // (onAllToolsCompleted, onScreenToolStarted) to avoid lifecycle issues
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Delete old channel if exists to apply new settings
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)
            
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Control",
                NotificationManager.IMPORTANCE_LOW  // LOW importance for silent notification
            ).apply {
                description = "Shows when AI is controlling the screen"
                setShowBadge(false)  // Don't show badge
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(false)  // No lights
                enableVibration(false)  // No vibration
                setSound(null, null)  // No sound
                setBypassDnd(false)
            }
            
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun createNotification(status: String, isInitial: Boolean = false): Notification {
        // Intent to expand the overlay when notification is tapped
        val expandIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, expandIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Stop action
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_HIDE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Use native Notification.Builder for Android 15+ to access new APIs
        if (Build.VERSION.SDK_INT >= 35) {
            return createAndroid15Notification(status, isInitial, pendingIntent, stopPendingIntent)
        }
        
        // Fallback for older Android versions
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Invocta")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Low priority - silent
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColorized(true)
            .setColor(0xFF6366F1.toInt())
            .setSilent(true)  // Ensure silent
            .setOnlyAlertOnce(true)  // Never alert more than once
        
        // Add chronometer for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setUsesChronometer(true)
                .setWhen(startTime)
                .setShowWhen(true)
        }
        
        return builder.build()
    }
    
    @SuppressLint("NewApi")
    private fun createAndroid15Notification(
        status: String,
        isInitial: Boolean,
        contentIntent: PendingIntent,
        stopIntent: PendingIntent
    ): Notification {
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Invocta")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopIntent
                ).build()
            )
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setColorized(true)
            .setColor(0xFF6366F1.toInt())
            .setUsesChronometer(true)
            .setWhen(startTime)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)  // Silent updates
        
        return builder.build()
    }
    
    // Track when the service started for chronometer
    private var startTime: Long = System.currentTimeMillis()
    
    private fun startForegroundNotification(status: String) {
        startTime = System.currentTimeMillis() // Reset timer when starting
        val notification = createNotification(status, isInitial = false)  // Always silent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        android.util.Log.d("FloatingBubble", "Started foreground notification (silent)")
    }
    
    private fun updateNotification(status: String) {
        val notification = createNotification(status, isInitial = false)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("FloatingBubble", "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Working..."
                _statusText.value = status
                
                // Start foreground notification immediately (always works)
                startForegroundNotification(status)
                
                // Delay bubble UI slightly to ensure any activity transitions are complete
                handler.postDelayed({
                    showBubble(status)
                }, 100)
            }
            ACTION_HIDE -> {
                android.util.Log.d("FloatingBubble", "ACTION_HIDE received - hiding bubble and stopping service")
                hideBubble()
                stopSelf()
            }
            ACTION_EXPAND -> {
                expandToOverlay()
            }
        }
        return START_STICKY // Keep service running
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showBubble(status: String) {

        if (bubbleView != null) {
            updateStatusText(status)
            return
        }

        _isShowing.value = true

        // Create a simple view programmatically
        bubbleView = createBubbleView(status)

        // Determine the best window type for maximum compatibility
        // TYPE_ACCESSIBILITY_OVERLAY works over ALL apps but requires accessibility service
        // TYPE_APPLICATION_OVERLAY is fallback but doesn't work over some system apps
        val accessibilityService = com.invocta.accessibility.InvoctaAccessibilityService.getInstance()
        val useAccessibilityOverlay = accessibilityService != null
        
        val layoutFlag = when {
            useAccessibilityOverlay -> {
                android.util.Log.d("FloatingBubble", "Using TYPE_ACCESSIBILITY_OVERLAY via AccessibilityService")
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                android.util.Log.d("FloatingBubble", "Using TYPE_APPLICATION_OVERLAY")
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            else -> {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        try {
            if (useAccessibilityOverlay && accessibilityService != null) {
                // Use AccessibilityService to add the view - this works over ALL apps
                val success = accessibilityService.addOverlayView(bubbleView!!, bubbleParams!!)
                if (success) {
                    addedViaAccessibilityService = true
                    android.util.Log.d("FloatingBubble", "Bubble view added via AccessibilityService")
                } else {
                    throw Exception("AccessibilityService failed to add view")
                }
            } else {
                addedViaAccessibilityService = false
                windowManager.addView(bubbleView, bubbleParams)
                android.util.Log.d("FloatingBubble", "Bubble view added successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "Failed to add bubble view", e)
            
            // Fallback: try with TYPE_APPLICATION_OVERLAY
            android.util.Log.d("FloatingBubble", "Retrying with TYPE_APPLICATION_OVERLAY")
            try {
                addedViaAccessibilityService = false
                bubbleParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                windowManager.addView(bubbleView, bubbleParams)
                android.util.Log.d("FloatingBubble", "Fallback bubble view added successfully")
            } catch (e2: Exception) {
                android.util.Log.e("FloatingBubble", "Fallback also failed", e2)
                _isShowing.value = false
                bubbleView = null
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView(status: String): View {
        val density = resources.displayMetrics.density
        
        // Determine if dark mode based on system setting
        val isDarkMode = (resources.configuration.uiMode and 
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        // Theme colors
        val primaryColor = if (isDarkMode) 0xFF818CF8.toInt() else 0xFF6366F1.toInt()  // Indigo
        val backgroundColor = if (isDarkMode) 0xFF1E1E2E.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (isDarkMode) 0xFFE2E8F0.toInt() else 0xFF1E293B.toInt()
        val secondaryTextColor = if (isDarkMode) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
        val cancelBgColor = if (isDarkMode) 0xFF3B3B4F.toInt() else 0xFFF1F5F9.toInt()
        
        // Wrapper for touch handling and positioning - extra padding for shadow and animations
        val wrapper = FrameLayout(this).apply {
            // Large padding to accommodate shadow and pulse animation
            val padding = (24 * density).toInt()
            setPadding(padding, padding, padding, padding)
            clipChildren = false
            clipToPadding = false
        }
        
        // Main container - horizontal pill
        val pillContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt()
            )
            
            // Pill background with shadow
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = 28 * density
                setStroke((1 * density).toInt(), if (isDarkMode) 0xFF2D2D3F.toInt() else 0xFFE2E8F0.toInt())
            }
            elevation = 16 * density
            
            // Disable clipping to allow animations to extend beyond bounds
            clipChildren = false
            clipToPadding = false
            
            // Min width for good appearance
            minimumWidth = (220 * density).toInt()
        }
        
        // Animated indicator container - needs extra space for pulse
        val indicatorContainer = FrameLayout(this).apply {
            val size = (40 * density).toInt()  // Larger to accommodate pulse
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (10 * density).toInt()
            }
            clipChildren = false
            clipToPadding = false
        }
        
        // Outer pulsing ring
        val pulseRing = View(this).apply {
            val size = (28 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((2 * density).toInt(), primaryColor)
            }
            alpha = 0.6f
        }
        indicatorContainer.addView(pulseRing)
        
        // Start pulse animation
        val pulseAnimator = android.animation.ObjectAnimator.ofFloat(pulseRing, "alpha", 0.6f, 0.0f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.AccelerateInterpolator()
        }
        pulseAnimator.start()
        
        val scaleAnimator = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(pulseRing, "scaleX", 1f, 1.5f).apply {
                    duration = 1200
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.RESTART
                },
                android.animation.ObjectAnimator.ofFloat(pulseRing, "scaleY", 1f, 1.5f).apply {
                    duration = 1200
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.RESTART
                }
            )
        }
        scaleAnimator.start()
        
        // Inner dot with gradient
        val innerDot = View(this).apply {
            val size = (16 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                colors = intArrayOf(primaryColor, 0xFF8B5CF6.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            }
            elevation = 4 * density
        }
        indicatorContainer.addView(innerDot)
        
        // Inner dot subtle pulse
        val dotPulse = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(innerDot, "scaleX", 1f, 1.1f, 1f).apply {
                    duration = 1500
                    repeatCount = android.animation.ValueAnimator.INFINITE
                },
                android.animation.ObjectAnimator.ofFloat(innerDot, "scaleY", 1f, 1.1f, 1f).apply {
                    duration = 1500
                    repeatCount = android.animation.ValueAnimator.INFINITE
                }
            )
        }
        dotPulse.start()
        
        pillContainer.addView(indicatorContainer)
        
        // Text container (title + status)
        val textContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        
        // Title
        val titleText = TextView(this).apply {
            text = "Invocta"
            setTextColor(textColor)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        textContainer.addView(titleText)
        
        // Status text with tag for updates
        val statusTextView = TextView(this).apply {
            tag = "status_text"
            text = status
            setTextColor(secondaryTextColor)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textContainer.addView(statusTextView)
        
        pillContainer.addView(textContainer)
        
        // Stop button - requests graceful stop of tool execution
        val stopButton = FrameLayout(this).apply {
            val size = (32 * density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginStart = (8 * density).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(cancelBgColor)
            }
            
            setOnClickListener {
                // Request graceful stop - ToolExecutor will stop before next tool
                ToolExecutor.requestStop()
                updateStatusText("Stopping...")
                updateNotificationStatus("Stopping...")
            }
        }
        
        // Stop icon (square/stop symbol)
        val stopIcon = TextView(this).apply {
            text = "■"
            setTextColor(secondaryTextColor)
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        stopButton.addView(stopIcon)
        
        pillContainer.addView(stopButton)
        
        wrapper.addView(pillContainer)
        
        // Touch handling for drag and tap
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        pillContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams?.x ?: 0
                    initialY = bubbleParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        bubbleParams?.x = initialX + deltaX
                        bubbleParams?.y = initialY + deltaY
                        try {
                            if (addedViaAccessibilityService) {
                                val accessibilityService = com.invocta.accessibility.InvoctaAccessibilityService.getInstance()
                                accessibilityService?.updateOverlayView(wrapper, bubbleParams!!)
                            } else {
                                windowManager.updateViewLayout(wrapper, bubbleParams)
                            }
                        } catch (e: Exception) {
                            // View might not be attached
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // It was a tap, expand to overlay
                        expandToOverlay()
                    }
                    true
                }
                else -> false
            }
        }

        return wrapper
    }

    private fun updateStatusText(status: String) {
        handler.post {
            // Update bubble view if visible
            bubbleView?.findViewWithTag<TextView>("status_text")?.text = status
        }
    }
    
    // Make updateNotification accessible for companion object
    internal fun updateNotificationStatus(status: String) {
        updateNotification(status)
    }

    private fun hideBubble() {
        _isShowing.value = false
        bubbleView?.let { view ->
            try {
                if (addedViaAccessibilityService) {
                    // Remove via AccessibilityService
                    val accessibilityService = com.invocta.accessibility.InvoctaAccessibilityService.getInstance()
                    accessibilityService?.removeOverlayView(view) ?: windowManager.removeView(view)
                } else {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                // Try regular removal as fallback
                try {
                    windowManager.removeView(view)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }
        bubbleView = null
        addedViaAccessibilityService = false
    }

    private fun expandToOverlay() {
        android.util.Log.d("FloatingBubble", "expandToOverlay called, activeSession=$activeSessionId")
        
        // Start overlay activity with the active session ID
        val intent = Intent(this, AssistantOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            activeSessionId?.let { putExtra("session_id", it) }
        }
        startActivity(intent)

        hideBubble()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        android.util.Log.d("FloatingBubble", "onDestroy called - service being destroyed")
        hideBubble()
        instance = null
        super.onDestroy()
    }
}
