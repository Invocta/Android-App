package com.invocta.util

import android.os.Build

/**
 * Utility object to get device information for the AI agent context
 */
object DeviceInfo {

    /**
     * Get a summary of device information for the AI agent
     */
    fun getDeviceSummary(): String {
        return buildString {
            appendLine("Device Information:")
            appendLine("- Manufacturer: ${Build.MANUFACTURER}")
            appendLine("- Model: ${Build.MODEL}")
            appendLine("- Device: ${Build.DEVICE}")
            appendLine("- Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- Brand: ${Build.BRAND}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine("- SOC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            }
        }
    }

    /**
     * Get a one-line device summary
     */
    fun getShortDeviceSummary(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    }

    /**
     * Check if device is likely a Samsung device
     */
    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * Check if device is likely a Pixel device
     */
    fun isPixel(): Boolean = Build.MANUFACTURER.equals("Google", ignoreCase = true)

    /**
     * Check if device is likely a OnePlus device
     */
    fun isOnePlus(): Boolean = Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)

    /**
     * Check if device is likely a Xiaomi device
     */
    fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true)
}

