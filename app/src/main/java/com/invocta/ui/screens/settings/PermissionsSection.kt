package com.invocta.ui.screens.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Data class representing a permission item
 */
data class PermissionItem(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: Boolean,
    val isRequired: Boolean,
    val onRequestPermission: () -> Unit
)

/**
 * Composable that displays permission status and shortcuts to grant them
 */
@Composable
fun PermissionsSection(
    onRequestMicPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission states - refresh on resume
    var micPermissionGranted by remember { mutableStateOf(false) }
    var overlayPermissionGranted by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(false) }
    var defaultAssistantSet by remember { mutableStateOf(false) }

    // Refresh permissions when screen resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micPermissionGranted = checkMicPermission(context)
                overlayPermissionGranted = checkOverlayPermission(context)
                accessibilityEnabled = checkAccessibilityEnabled(context)
                notificationPermissionGranted = checkNotificationPermission(context)
                defaultAssistantSet = checkDefaultAssistant(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        // Initial check
        micPermissionGranted = checkMicPermission(context)
        overlayPermissionGranted = checkOverlayPermission(context)
        accessibilityEnabled = checkAccessibilityEnabled(context)
        notificationPermissionGranted = checkNotificationPermission(context)
        defaultAssistantSet = checkDefaultAssistant(context)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Only show "Display Over Other Apps" if default assistant is set but overlay permission is missing (edge case)
    val showOverlayPermission = defaultAssistantSet && !overlayPermissionGranted

    val permissions = buildList {
        add(PermissionItem(
            name = "Default Assistant",
            description = "Set Invocta as your default voice assistant",
            icon = Icons.Default.PhoneAndroid,
            isGranted = defaultAssistantSet,
            isRequired = false,
            onRequestPermission = {
                val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        ))
        add(PermissionItem(
            name = "Microphone",
            description = "Required for voice input and commands",
            icon = Icons.Default.Mic,
            isGranted = micPermissionGranted,
            isRequired = true,
            onRequestPermission = onRequestMicPermission
        ))
        add(PermissionItem(
            name = "Accessibility Service",
            description = "Required for screen control and automation",
            icon = Icons.Default.AccessibilityNew,
            isGranted = accessibilityEnabled,
            isRequired = false,
            onRequestPermission = {
                try {
                    // Try to open directly to our accessibility service settings
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val componentName = android.content.ComponentName(
                        context.packageName,
                        "com.invocta.accessibility.InvoctaAccessibilityService"
                    )
                    val extraFragmentArgKey = ":settings:fragment_args_key"
                    val extraShowFragmentArgs = ":settings:show_fragment_args"
                    val bundle = android.os.Bundle()
                    bundle.putString(extraFragmentArgKey, componentName.flattenToString())
                    intent.putExtra(extraShowFragmentArgs, bundle)
                    intent.putExtra(extraFragmentArgKey, componentName.flattenToString())
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general accessibility settings
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        ))
        // Only show Display Over Other Apps if default assistant is set but overlay permission is missing
        if (showOverlayPermission) {
            add(PermissionItem(
                name = "Display Over Other Apps",
                description = "Required for floating bubble assistant",
                icon = Icons.Default.Layers,
                isGranted = overlayPermissionGranted,
                isRequired = false,
                onRequestPermission = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // Add extras to help with scrolling to the app (API 26+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    intent.putExtra("app_package", context.packageName)
                    intent.putExtra(":settings:fragment_args_key", context.packageName)
                    context.startActivity(intent)
                }
            ))
        }
        add(PermissionItem(
            name = "Notifications",
            description = "For assistant alerts and updates",
            icon = Icons.Default.Notifications,
            isGranted = notificationPermissionGranted,
            isRequired = false,
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        ))
    }


    val grantedCount = permissions.count { it.isGranted }
    val totalCount = permissions.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Permissions",
                    style = MaterialTheme.typography.titleMedium
                )
                
                // Status badge
                val allGranted = grantedCount == totalCount
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (allGranted) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$grantedCount/$totalCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (allGranted) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Grant permissions to unlock all features",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Permission items
            permissions.forEachIndexed { index, permission ->
                PermissionItemRow(permission = permission)
                if (index < permissions.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(permission: PermissionItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = !permission.isGranted) { permission.onRequestPermission() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon with status
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (permission.isGranted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = permission.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (permission.isGranted) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = permission.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (permission.isRequired) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "*",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = permission.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status icon or action
        if (permission.isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open settings",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// Helper functions to check permission status
private fun checkMicPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any { 
        it.resolveInfo.serviceInfo.packageName == context.packageName 
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true // Notifications are enabled by default on older versions
    }
}

/**
 * Check if Invocta is set as the default assistant
 */
private fun checkDefaultAssistant(context: Context): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        } else {
            // Fallback: check if our VoiceInteractionService is the default
            val defaultAssistant = Settings.Secure.getString(
                context.contentResolver,
                "assistant"
            )
            defaultAssistant?.contains(context.packageName) == true
        }
    } catch (e: Exception) {
        false
    }
}

