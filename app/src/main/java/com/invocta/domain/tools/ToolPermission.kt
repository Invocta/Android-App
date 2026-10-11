package com.invocta.domain.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Defines permissions that a tool may require
 */
enum class ToolPermission(
    val androidPermission: String,
    val displayName: String,
    val description: String
) {
    RECORD_AUDIO(
        Manifest.permission.RECORD_AUDIO,
        "Microphone",
        "Required for voice input"
    ),
    CALL_PHONE(
        Manifest.permission.CALL_PHONE,
        "Phone Calls",
        "Required to make phone calls directly"
    ),
    SEND_SMS(
        Manifest.permission.SEND_SMS,
        "SMS",
        "Required to send text messages"
    ),
    READ_CONTACTS(
        Manifest.permission.READ_CONTACTS,
        "Contacts",
        "Required to access your contacts"
    ),
    CAMERA(
        Manifest.permission.CAMERA,
        "Camera",
        "Required for camera and flashlight features"
    ),
    SET_ALARM(
        "com.android.alarm.permission.SET_ALARM",
        "Alarms",
        "Required to set alarms and timers"
    ),
    READ_CALENDAR(
        Manifest.permission.READ_CALENDAR,
        "Calendar",
        "Required to read calendar events"
    ),
    WRITE_CALENDAR(
        Manifest.permission.WRITE_CALENDAR,
        "Calendar",
        "Required to create calendar events"
    );

    companion object {
        fun fromAndroidPermission(permission: String): ToolPermission? {
            return entries.find { it.androidPermission == permission }
        }
    }
}

/**
 * Result of a permission check
 */
sealed class PermissionCheckResult {
    data object Granted : PermissionCheckResult()
    data class NeedsPermission(val permissions: List<ToolPermission>) : PermissionCheckResult()
}

/**
 * Helper class to check and manage tool permissions
 */
class ToolPermissionManager(private val context: Context) {

    /**
     * Check if all required permissions for a tool are granted
     */
    fun checkPermissions(permissions: List<ToolPermission>): PermissionCheckResult {
        val missingPermissions = permissions.filter { permission ->
            !isPermissionGranted(permission)
        }

        return if (missingPermissions.isEmpty()) {
            PermissionCheckResult.Granted
        } else {
            PermissionCheckResult.NeedsPermission(missingPermissions)
        }
    }

    /**
     * Check if a single permission is granted
     */
    fun isPermissionGranted(permission: ToolPermission): Boolean {
        // Some permissions like SET_ALARM don't need runtime permission on most devices
        if (permission == ToolPermission.SET_ALARM) {
            return true // This is typically granted via manifest
        }

        return ContextCompat.checkSelfPermission(
            context,
            permission.androidPermission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get the Android permission strings for requesting
     */
    fun getAndroidPermissions(permissions: List<ToolPermission>): Array<String> {
        return permissions
            .filter { it != ToolPermission.SET_ALARM } // Filter out manifest-only permissions
            .map { it.androidPermission }
            .toTypedArray()
    }
}

