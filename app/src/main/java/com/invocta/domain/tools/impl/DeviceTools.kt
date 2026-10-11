package com.invocta.domain.tools.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import com.invocta.domain.tools.Tool
import com.invocta.domain.tools.ToolParameters
import com.invocta.domain.tools.ToolPermission
import com.invocta.domain.tools.ToolProperty
import com.invocta.domain.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tool to get current date and time
 */
class GetCurrentTimeTool : Tool {
    override val name = "get_current_time"
    override val description = "Get the current date and time. Use this when the user asks about the current time, date, day of week, etc."
    override val parameters = ToolParameters(
        properties = mapOf(
            "format" to ToolProperty(
                type = "string",
                description = "Optional format: 'time_only', 'date_only', 'full', or 'day_of_week'. Default is 'full'",
                enum = listOf("time_only", "date_only", "full", "day_of_week")
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val format = arguments["format"] as? String ?: "full"
        val now = Date()
        val calendar = Calendar.getInstance()

        val result = when (format) {
            "time_only" -> SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
            "date_only" -> SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(now)
            "day_of_week" -> SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
            else -> SimpleDateFormat("EEEE, MMMM d, yyyy 'at' hh:mm a", Locale.getDefault()).format(now)
        }

        return ToolResult.Success(result)
    }
}

/**
 * Tool to perform mathematical calculations
 */
class CalculatorTool : Tool {
    override val name = "calculator"
    override val description = "Perform mathematical calculations. Supports basic arithmetic (+, -, *, /), percentages, and common operations."
    override val parameters = ToolParameters(
        properties = mapOf(
            "expression" to ToolProperty(
                type = "string",
                description = "The mathematical expression to evaluate. Examples: '2 + 2', '100 * 0.15', '50 / 2'"
            )
        ),
        required = listOf("expression")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val expression = arguments["expression"] as? String
            ?: return ToolResult.Error("Expression is required")

        return try {
            val result = evaluateExpression(expression)
            ToolResult.Success("$expression = $result")
        } catch (e: Exception) {
            ToolResult.Error("Could not calculate: ${e.message}")
        }
    }

    private fun evaluateExpression(expr: String): Double {
        // Simple expression parser for basic arithmetic
        val sanitized = expr.replace(" ", "")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("%", "/100")

        return object : Any() {
            var pos = -1
            var ch = 0

            fun nextChar() {
                ch = if (++pos < sanitized.length) sanitized[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) {
                    nextChar()
                    return true
                }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < sanitized.length) throw RuntimeException("Unexpected: ${ch.toChar()}")
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    when {
                        eat('+'.code) -> x += parseTerm()
                        eat('-'.code) -> x -= parseTerm()
                        else -> return x
                    }
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    when {
                        eat('*'.code) -> x *= parseFactor()
                        eat('/'.code) -> x /= parseFactor()
                        else -> return x
                    }
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor()
                if (eat('-'.code)) return -parseFactor()

                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) {
                    while ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) nextChar()
                    x = sanitized.substring(startPos, pos).toDouble()
                } else if (ch >= 'a'.code && ch <= 'z'.code) {
                    while (ch >= 'a'.code && ch <= 'z'.code) nextChar()
                    val func = sanitized.substring(startPos, pos)
                    x = parseFactor()
                    x = when (func) {
                        "sqrt" -> kotlin.math.sqrt(x)
                        "sin" -> kotlin.math.sin(Math.toRadians(x))
                        "cos" -> kotlin.math.cos(Math.toRadians(x))
                        "tan" -> kotlin.math.tan(Math.toRadians(x))
                        "abs" -> kotlin.math.abs(x)
                        else -> throw RuntimeException("Unknown function: $func")
                    }
                } else {
                    throw RuntimeException("Unexpected: ${ch.toChar()}")
                }

                if (eat('^'.code)) x = Math.pow(x, parseFactor())

                return x
            }
        }.parse()
    }
}

/**
 * Tool to set alarms and timers
 */
class SetAlarmTool(private val context: Context) : Tool {
    override val name = "set_alarm"
    override val description = "Set an alarm or timer on the device. Use this when the user asks to set an alarm, reminder, or timer."
    override val parameters = ToolParameters(
        properties = mapOf(
            "hour" to ToolProperty(
                type = "integer",
                description = "The hour for the alarm (0-23 in 24-hour format)"
            ),
            "minute" to ToolProperty(
                type = "integer",
                description = "The minute for the alarm (0-59)"
            ),
            "message" to ToolProperty(
                type = "string",
                description = "Optional message/label for the alarm"
            )
        ),
        required = listOf("hour", "minute")
    )

    override val requiredPermissions = listOf(ToolPermission.SET_ALARM)

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val hour = (arguments["hour"] as? Number)?.toInt()
            ?: return ToolResult.Error("Hour is required")
        val minute = (arguments["minute"] as? Number)?.toInt()
            ?: return ToolResult.Error("Minute is required")
        val message = arguments["message"] as? String ?: "Invocta Alarm"

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val timeStr = String.format("%02d:%02d", hour, minute)
            ToolResult.Success("Setting alarm for $timeStr with message: $message")
        } catch (e: Exception) {
            ToolResult.Error("Could not set alarm: ${e.message}")
        }
    }
}

/**
 * Tool to set a timer
 */
class SetTimerTool(private val context: Context) : Tool {
    override val name = "set_timer"
    override val description = "Set a countdown timer. Use this when the user asks to set a timer for a specific duration."
    override val parameters = ToolParameters(
        properties = mapOf(
            "seconds" to ToolProperty(
                type = "integer",
                description = "Total duration in seconds"
            ),
            "message" to ToolProperty(
                type = "string",
                description = "Optional message/label for the timer"
            )
        ),
        required = listOf("seconds")
    )

    override val requiredPermissions = listOf(ToolPermission.SET_ALARM)

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val seconds = (arguments["seconds"] as? Number)?.toInt()
            ?: return ToolResult.Error("Duration in seconds is required")
        val message = arguments["message"] as? String ?: "Invocta Timer"

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val minutes = seconds / 60
            val secs = seconds % 60
            val timeStr = if (minutes > 0) "${minutes}m ${secs}s" else "${secs}s"
            ToolResult.Success("Setting timer for $timeStr")
        } catch (e: Exception) {
            ToolResult.Error("Could not set timer: ${e.message}")
        }
    }
}

/**
 * Tool to open apps
 */
class OpenAppTool(private val context: Context) : Tool {
    override val name = "open_app"
    override val description = "Open an app on the device by name."
    override val parameters = ToolParameters(
        properties = mapOf(
            "app_name" to ToolProperty(
                type = "string",
                description = "The name of the app to open (e.g., 'Chrome', 'YouTube', 'Settings', 'Camera', 'Calculator')"
            )
        ),
        required = listOf("app_name")
    )

    // Map of common app names to their package names (with aliases)
    private val commonApps = mapOf(
        // Google apps
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "mail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "navigation" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "google photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "calendar" to "com.google.android.calendar",
        "google calendar" to "com.google.android.calendar",
        "drive" to "com.google.android.apps.docs",
        "google drive" to "com.google.android.apps.docs",
        "docs" to "com.google.android.apps.docs.editors.docs",
        "sheets" to "com.google.android.apps.docs.editors.sheets",
        "slides" to "com.google.android.apps.docs.editors.slides",
        "keep" to "com.google.android.keep",
        "google keep" to "com.google.android.keep",
        "notes" to "com.google.android.keep",
        "meet" to "com.google.android.apps.meetings",
        "google meet" to "com.google.android.apps.meetings",
        "duo" to "com.google.android.apps.tachyon",
        "google" to "com.google.android.googlequicksearchbox",
        "google app" to "com.google.android.googlequicksearchbox",
        "assistant" to "com.google.android.apps.googleassistant",
        "google assistant" to "com.google.android.apps.googleassistant",
        "translate" to "com.google.android.apps.translate",
        "google translate" to "com.google.android.apps.translate",
        "news" to "com.google.android.apps.magazines",
        "google news" to "com.google.android.apps.magazines",
        "podcasts" to "com.google.android.apps.podcasts",
        "google podcasts" to "com.google.android.apps.podcasts",
        "files" to "com.google.android.apps.nbu.files",
        "google files" to "com.google.android.apps.nbu.files",
        "file manager" to "com.google.android.apps.nbu.files",
        
        // System apps
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "camera" to "com.google.android.GoogleCamera",
        "calculator" to "com.google.android.calculator",
        "calc" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "alarm" to "com.google.android.deskclock",
        "timer" to "com.google.android.deskclock",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "texting" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "calls" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts",
        "play store" to "com.android.vending",
        "google play" to "com.android.vending",
        "store" to "com.android.vending",
        "play music" to "com.google.android.music",
        "music" to "com.google.android.music",
        
        // Social media
        "spotify" to "com.spotify.music",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "fb messenger" to "com.facebook.orca",
        "snapchat" to "com.snapchat.android",
        "snap" to "com.snapchat.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "linkedin" to "com.linkedin.android",
        "reddit" to "com.reddit.frontpage",
        "discord" to "com.discord",
        "telegram" to "org.telegram.messenger",
        "signal" to "org.thoughtcrime.securesms",
        "slack" to "com.Slack",
        "pinterest" to "com.pinterest",
        "tumblr" to "com.tumblr",
        
        // Entertainment
        "netflix" to "com.netflix.mediaclient",
        "prime video" to "com.amazon.avod.thirdpartyclient",
        "amazon prime" to "com.amazon.avod.thirdpartyclient",
        "disney" to "com.disney.disneyplus",
        "disney+" to "com.disney.disneyplus",
        "disney plus" to "com.disney.disneyplus",
        "hulu" to "com.hulu.plus",
        "twitch" to "tv.twitch.android.app",
        "hbo" to "com.hbo.hbonow",
        "hbo max" to "com.hbo.hbonow",
        
        // Utilities
        "uber" to "com.ubercab",
        "lyft" to "me.lyft.android",
        "amazon" to "com.amazon.mShop.android.shopping",
        "amazon shopping" to "com.amazon.mShop.android.shopping",
        "ebay" to "com.ebay.mobile",
        "zoom" to "us.zoom.videomeetings",
        "teams" to "com.microsoft.teams",
        "microsoft teams" to "com.microsoft.teams",
        "outlook" to "com.microsoft.office.outlook",
        "word" to "com.microsoft.office.word",
        "excel" to "com.microsoft.office.excel",
        "powerpoint" to "com.microsoft.office.powerpoint",
        "onenote" to "com.microsoft.office.onenote",
        "onedrive" to "com.microsoft.skydrive",
        "dropbox" to "com.dropbox.android",
        "evernote" to "com.evernote",
        "notion" to "notion.id",
        "todoist" to "com.todoist",
        "trello" to "com.trello"
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val appName = (arguments["app_name"] as? String)?.lowercase()?.trim()
            ?: return ToolResult.Error("App name is required")

        android.util.Log.d("OpenAppTool", "Searching for app: $appName")

        // Try to find package name using multiple strategies
        val packageName = findPackageName(appName)

        return if (packageName != null) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    android.util.Log.d("OpenAppTool", "Successfully opened: $packageName")
                    ToolResult.Success("Opening $appName")
                } else {
                    android.util.Log.w("OpenAppTool", "No launch intent for: $packageName")
                    ToolResult.Error("$appName cannot be launched")
                }
            } catch (e: Exception) {
                android.util.Log.e("OpenAppTool", "Error opening app", e)
                ToolResult.Error("Could not open $appName: ${e.message}")
            }
        } else {
            android.util.Log.w("OpenAppTool", "App not found: $appName")
            // List available apps that might match
            val suggestions = findSimilarApps(appName)
            if (suggestions.isNotEmpty()) {
                ToolResult.Error("Could not find '$appName'. Did you mean: ${suggestions.joinToString(", ")}?")
            } else {
                ToolResult.Error("Could not find app: $appName")
            }
        }
    }

    private fun findPackageName(appName: String): String? {
        // Strategy 1: Search installed apps by label FIRST (most reliable)
        findPackageByLabel(appName, exactMatch = true)?.let { 
            android.util.Log.d("OpenAppTool", "Found exact label match: $it")
            return it 
        }

        // Strategy 2: Search installed apps by label (contains match)
        findPackageByLabel(appName, exactMatch = false)?.let { 
            android.util.Log.d("OpenAppTool", "Found partial label match: $it")
            return it 
        }
        
        // Strategy 3: Direct lookup in common apps map
        commonApps[appName]?.let { packageName ->
            if (isPackageInstalled(packageName)) {
                android.util.Log.d("OpenAppTool", "Found in common apps: $packageName")
                return packageName
            } else {
                android.util.Log.d("OpenAppTool", "Common app not installed: $packageName")
            }
        }
        
        // Strategy 4: Partial match in common apps map
        commonApps.entries.find { (key, _) -> 
            key.contains(appName) || appName.contains(key) 
        }?.let { (key, packageName) ->
            if (isPackageInstalled(packageName)) {
                android.util.Log.d("OpenAppTool", "Found partial match in common apps: $key -> $packageName")
                return packageName
            }
        }

        // Strategy 5: Search by package name
        findPackageByPackageName(appName)?.let {
            android.util.Log.d("OpenAppTool", "Found by package name: $it")
            return it
        }

        return null
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            android.util.Log.d("OpenAppTool", "Package not found: $packageName")
            false
        }
    }

    private fun findPackageByLabel(appName: String, exactMatch: Boolean): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        
        val normalizedAppName = appName.lowercase().trim()
        android.util.Log.d("OpenAppTool", "Searching for label: '$normalizedAppName' (exact=$exactMatch) among ${activities.size} apps")
        
        val match = activities.find { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            val matches = if (exactMatch) {
                label == normalizedAppName
            } else {
                label.contains(normalizedAppName) || normalizedAppName.contains(label)
            }
            if (matches) {
                android.util.Log.d("OpenAppTool", "Label match found: '$label' -> ${resolveInfo.activityInfo.packageName}")
            }
            matches
        }
        
        return match?.activityInfo?.packageName
    }

    private fun findPackageByPackageName(appName: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        
        return activities.find { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName.lowercase()
            packageName.contains(appName)
        }?.activityInfo?.packageName
    }

    private fun findSimilarApps(appName: String): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        
        return activities
            .map { it.loadLabel(pm).toString() }
            .filter { label ->
                val normalizedLabel = label.lowercase()
                val normalizedName = appName.lowercase()
                // Check if any word matches
                normalizedLabel.split(" ").any { word -> 
                    word.startsWith(normalizedName.take(3)) 
                } || normalizedName.split(" ").any { word ->
                    normalizedLabel.contains(word)
                }
            }
            .take(3)
    }
}

/**
 * Tool to make phone calls
 */
class MakeCallTool(private val context: Context) : Tool {
    override val name = "make_call"
    override val description = "Initiate a phone call. Use this when the user wants to call someone."
    override val parameters = ToolParameters(
        properties = mapOf(
            "phone_number" to ToolProperty(
                type = "string",
                description = "The phone number to call"
            )
        ),
        required = listOf("phone_number")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val phoneNumber = arguments["phone_number"] as? String
            ?: return ToolResult.Error("Phone number is required")

        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Opening dialer with number: $phoneNumber")
        } catch (e: Exception) {
            ToolResult.Error("Could not open dialer: ${e.message}")
        }
    }
}

/**
 * Tool to send SMS
 */
class SendSmsTool(private val context: Context) : Tool {
    override val name = "send_sms"
    override val description = "Compose an SMS message. Use this when the user wants to send a text message."
    override val parameters = ToolParameters(
        properties = mapOf(
            "phone_number" to ToolProperty(
                type = "string",
                description = "The phone number to send the message to"
            ),
            "message" to ToolProperty(
                type = "string",
                description = "The message content"
            )
        ),
        required = listOf("phone_number", "message")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val phoneNumber = arguments["phone_number"] as? String
            ?: return ToolResult.Error("Phone number is required")
        val message = arguments["message"] as? String
            ?: return ToolResult.Error("Message is required")

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Opening messaging app to send SMS to $phoneNumber")
        } catch (e: Exception) {
            ToolResult.Error("Could not open messaging app: ${e.message}")
        }
    }
}

/**
 * Tool to search the web
 */
class WebSearchTool(private val context: Context) : Tool {
    override val name = "web_search"
    override val description = "Search the web for information. Use this when the user asks to search for something online."
    override val parameters = ToolParameters(
        properties = mapOf(
            "query" to ToolProperty(
                type = "string",
                description = "The search query"
            )
        ),
        required = listOf("query")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val query = arguments["query"] as? String
            ?: return ToolResult.Error("Search query is required")

        return try {
            val encodedQuery = Uri.encode(query)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=$encodedQuery")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Searching the web for: $query")
        } catch (e: Exception) {
            ToolResult.Error("Could not perform web search: ${e.message}")
        }
    }
}

/**
 * Tool to get device battery info
 */
class BatteryInfoTool(private val context: Context) : Tool {
    override val name = "get_battery_info"
    override val description = "Get the current battery level and charging status. Use this when the user asks about battery."
    override val parameters = ToolParameters(
        properties = emptyMap(),
        required = emptyList()
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = batteryManager.isCharging

            val status = if (isCharging) "charging" else "not charging"
            ToolResult.Success("Battery level: $level%, Status: $status")
        } catch (e: Exception) {
            ToolResult.Error("Could not get battery info: ${e.message}")
        }
    }
}

/**
 * Tool to control device flashlight
 */
class FlashlightTool(private val context: Context) : Tool {
    override val name = "flashlight"
    override val description = "Turn the device flashlight on or off. Use this when the user asks to control the flashlight/torch."
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ToolProperty(
                type = "string",
                description = "Whether to turn the flashlight 'on' or 'off'",
                enum = listOf("on", "off")
            )
        ),
        required = listOf("action")
    )

    override val requiredPermissions = listOf(ToolPermission.CAMERA)

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val action = arguments["action"] as? String
            ?: return ToolResult.Error("Action (on/off) is required")

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolResult.Error("No camera available")

            val turnOn = action.lowercase() == "on"
            cameraManager.setTorchMode(cameraId, turnOn)

            ToolResult.Success("Flashlight turned ${if (turnOn) "on" else "off"}")
        } catch (e: Exception) {
            ToolResult.Error("Could not control flashlight: ${e.message}")
        }
    }
}


