package com.invocta.ui.screens.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.invocta.R
import com.invocta.domain.model.ThemeMode
import com.invocta.domain.model.ChatStorageMode
import com.invocta.domain.model.AiProvider
import com.invocta.data.remote.GoogleAiStudioAIClient
import com.invocta.data.remote.OpenAiAIClient
import com.invocta.data.local.ai.LocalGemmaModelStatus
import com.invocta.data.local.ai.LocalGemmaModelManager
import com.invocta.domain.tools.PluginTrustStore
import com.invocta.pluginsdk.PluginApp
import com.invocta.pluginsdk.PluginClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onSignOut: () -> Unit = {},
    onSignInRequired: () -> Unit = {}
) {
    val preferences by viewModel.preferences.collectAsState()
    val signOutComplete by viewModel.signOutComplete.collectAsState()
    val signInRequired by viewModel.signInRequired.collectAsState()
    val storageModeError by viewModel.storageModeError.collectAsState()
    val gemmaStatus by viewModel.gemmaModelStatus.collectAsState()
    val googleKeyConfigured by viewModel.googleKeyConfigured.collectAsState()
    var googleKeyInput by remember { mutableStateOf("") }
    val openAiKeyConfigured by viewModel.openAiKeyConfigured.collectAsState()
    var openAiKeyInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val pluginClient = remember(context) { PluginClient(context) }
    val pluginTrust = remember(context) { PluginTrustStore(context) }
    var pluginApps by remember { mutableStateOf(emptyList<PluginApp>()) }
    var approvedProviders by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        pluginApps = pluginClient.discover()
        approvedProviders = pluginApps.filter(pluginTrust::isAllowed).map { it.id }.toSet()
    }

    // Navigate to onboarding when sign out is complete
    androidx.compose.runtime.LaunchedEffect(signOutComplete) {
        if (signOutComplete) {
            onSignOut()
        }
    }
    androidx.compose.runtime.LaunchedEffect(signInRequired) {
        if (signInRequired) {
            viewModel.consumeSignInRequired()
            onSignInRequired()
        }
    }

    // Mic permission launcher
    var micPermissionRequested by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        micPermissionRequested = !micPermissionRequested // Trigger recomposition
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {

            var showSignOutDialog by remember { mutableStateOf(false) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (preferences.chatStorageMode == ChatStorageMode.LOCAL) {
                        Text(
                            "Chats are stored on this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { viewModel.setChatStorageMode(ChatStorageMode.CLOUD) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign in & Sync your Chats across devices")
                        }
                    } else {
                        Text(
                            if (preferences.userName.isNotBlank()) "Signed in as ${preferences.userName}" else "Chats sync to your Supabase account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showSignOutDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign Out")
                        }
                    }

                    storageModeError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("On-device AI", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "${LocalGemmaModelManager.MODEL_NAME} · 2.6 GB download · ${LocalGemmaModelManager.MIN_DEVICE_MEMORY_GB} GB RAM recommended",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    when (val status = gemmaStatus) {
                        LocalGemmaModelStatus.Checking -> Text("Checking local model…")
                        LocalGemmaModelStatus.NotDownloaded -> Button(onClick = viewModel::downloadGemmaModel) { Text("Download Gemma") }
                        is LocalGemmaModelStatus.Downloading -> {
                            LinearProgressIndicator(
                                progress = (status.downloadedBytes.toFloat() / status.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                status.message ?: "${(status.downloadedBytes / (1024 * 1024))} MB of ${(status.totalBytes / (1024 * 1024))} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = viewModel::cancelGemmaDownload) { Text("Cancel download") }
                        }
                        is LocalGemmaModelStatus.Ready -> {
                            Text(if (preferences.aiProvider == AiProvider.LOCAL && status.selected) "Gemma is selected for chat." else "Model downloaded and ready to use.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!status.selected || preferences.aiProvider != AiProvider.LOCAL) Button(onClick = viewModel::selectGemmaModel) { Text("Use Gemma") }
                                TextButton(onClick = viewModel::deleteGemmaModel) { Text("Remove model") }
                            }
                        }
                        is LocalGemmaModelStatus.Failed -> {
                            Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = viewModel::downloadGemmaModel) { Text("Try again") }
                                TextButton(onClick = viewModel::deleteGemmaModel) { Text("Remove files") }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Google AI Studio (BYOK)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Use ${GoogleAiStudioAIClient.MODEL} with your own API key. When selected, messages, images, screen details, and device tool results are sent to Google for processing. Chat storage stays in your chosen local or cloud mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (googleKeyConfigured) "API key saved on this device." else "Add a Google AI Studio API key to use this model.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = googleKeyInput,
                        onValueChange = { googleKeyInput = it },
                        label = { Text(if (googleKeyConfigured) "Replace API key" else "API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.saveGoogleKey(googleKeyInput); googleKeyInput = "" },
                            enabled = googleKeyInput.isNotBlank()
                        ) { Text("Save key") }
                        if (googleKeyConfigured) TextButton(onClick = viewModel::removeGoogleKey) { Text("Remove key") }
                    }
                    if (googleKeyConfigured) {
                        OutlinedButton(
                            onClick = viewModel::selectGoogleAiStudio,
                            enabled = preferences.aiProvider != AiProvider.GOOGLE_AI_STUDIO,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (preferences.aiProvider == AiProvider.GOOGLE_AI_STUDIO) "Using Google AI Studio" else "Use Google AI Studio") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("OpenAI (BYOK)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Use ${OpenAiAIClient.MODEL} with your own API key. When selected, messages, images, screen details, and device tool results are sent to OpenAI. API usage may be billed. Chat storage stays in your chosen mode; API response storage is disabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (openAiKeyConfigured) "API key saved on this device." else "Add an OpenAI API key to use this model.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = openAiKeyInput,
                        onValueChange = { openAiKeyInput = it },
                        label = { Text(if (openAiKeyConfigured) "Replace API key" else "API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.saveOpenAiKey(openAiKeyInput); openAiKeyInput = "" },
                            enabled = openAiKeyInput.isNotBlank()
                        ) { Text("Save key") }
                        if (openAiKeyConfigured) TextButton(onClick = viewModel::removeOpenAiKey) { Text("Remove key") }
                    }
                    if (openAiKeyConfigured) {
                        OutlinedButton(
                            onClick = viewModel::selectOpenAi,
                            enabled = preferences.aiProvider != AiProvider.OPENAI,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (preferences.aiProvider == AiProvider.OPENAI) "Using OpenAI" else "Use OpenAI") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connected apps", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Allow Invocta to use actions offered by apps you trust. You may also need to enable access inside each app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pluginApps.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No compatible apps installed.", style = MaterialTheme.typography.bodyMedium)
                    }
                    pluginApps.forEach { app ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.component.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = app.id in approvedProviders,
                                onCheckedChange = { enabled ->
                                    if (pluginTrust.setAllowed(app, enabled)) {
                                        approvedProviders = if (enabled) approvedProviders + app.id
                                            else approvedProviders - app.id
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Permissions Section
            PermissionsSection(
                onRequestMicPermission = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // General Settings
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "General",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsToggleItem(
                        title = stringResource(R.string.settings_voice_enabled),
                        description = "Enable voice input for hands-free interaction",
                        checked = preferences.voiceInputEnabled,
                        onCheckedChange = viewModel::updateVoiceInputEnabled
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Theme Mode Selector
                    ThemeModeSelector(
                        selectedThemeMode = preferences.themeMode,
                        onThemeModeSelected = viewModel::updateThemeMode
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsToggleItem(
                        title = stringResource(R.string.settings_haptic_feedback),
                        description = "Vibrate on touch interactions",
                        checked = preferences.hapticFeedbackEnabled,
                        onCheckedChange = viewModel::updateHapticFeedbackEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Data Management Section
            var showDeleteAllDialog by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Data Management",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Manage your chat history and app data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showDeleteAllDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete All Chats")
                    }
                }
            }

            // Delete all confirmation dialog
            if (showDeleteAllDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteAllDialog = false },
                    title = { Text("Delete all chats?") },
                    text = { Text("This will permanently delete all your chat history. This action cannot be undone.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteAllChatHistory()
                                showDeleteAllDialog = false
                            }
                        ) {
                            Text("Delete All", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteAllDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Invocta Assistant",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Version 1.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your personal AI assistant powered by on-device AI, designed to replace the default assistant on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sign out confirmation dialog
            if (showSignOutDialog) {
                AlertDialog(
                    onDismissRequest = { showSignOutDialog = false },
                    title = { Text("Sign Out?") },
                    text = { Text("Your cloud chats will remain in your account. This device will switch to local storage.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.signOut()
                                showSignOutDialog = false
                            }
                        ) {
                            Text("Sign Out")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSignOutDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
fun ThemeModeSelector(
    selectedThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Choose your preferred app theme",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, themeMode ->
                SegmentedButton(
                    selected = themeMode == selectedThemeMode,
                    onClick = { onThemeModeSelected(themeMode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = when (themeMode) {
                                ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                ThemeMode.LIGHT -> Icons.Default.LightMode
                                ThemeMode.DARK -> Icons.Default.DarkMode
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            when (themeMode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
