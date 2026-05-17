package com.borizon.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.borizon.app.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.borizon.app.ai.inference.ModelDownloader
import com.borizon.app.ai.inference.ModelManager.ModelState
import com.borizon.app.ui.components.AccentStripe
import com.borizon.app.ui.components.BorizonSwitch
import com.borizon.app.ui.components.StripePosition
import com.borizon.app.ui.components.BorizonCard
import com.borizon.app.ui.components.ModelConfig
import com.borizon.app.ui.components.ModelConfigSheet
import com.borizon.app.ui.components.SkillManagerSheet
import com.borizon.app.ui.theme.*
import com.borizon.app.ui.screens.BorizonViewModel.ModelInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelInfo: ModelInfo,
    downloadState: ModelDownloader.DownloadState,
    modelConfig: ModelConfig = ModelConfig(),
    selectedModel: String = "E4B",
    skills: List<com.borizon.app.proto.Skill> = emptyList(),
    braveApiKey: String = "",
    biometricEnabled: Boolean = false,
    onClearData: () -> Unit = {},
    onDownloadModel: () -> Unit = {},
    onDeleteModel: () -> Unit = {},
    onModelConfigChanged: (ModelConfig) -> Unit = {},
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    onImportSkillFromDirectory: (android.net.Uri) -> Unit = {},
    onImportSkillFromFile: (android.net.Uri) -> Unit = {},
    onDeleteSkill: (String) -> Unit = {},
    onBraveApiKeyChange: (String) -> Unit = {},
    onBiometricToggle: (Boolean) -> Unit = {},
    onSelectModel: (String) -> Unit = {},
) {
    val semanticColors = LocalBorizonSemanticColors.current
    var showConfigSheet by remember { mutableStateOf(false) }
    var showSkillSheet by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf(braveApiKey) }

    // SAF launchers for skill import
    val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onImportSkillFromDirectory(uri)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportSkillFromFile(uri)
    }

    if (showConfigSheet) {
        ModelConfigSheet(
            initialConfig = modelConfig,
            onDismiss = { showConfigSheet = false },
            onApply = { config -> onModelConfigChanged(config); showConfigSheet = false }
        )
    }

    if (showSkillSheet) {
        SkillManagerSheet(
            skills = skills,
            onToggleSkill = onToggleSkill,
            onDeleteSkill = onDeleteSkill,
            onImportFromDirectory = { dirLauncher.launch(null) },
            onImportFromFile = {
                fileLauncher.launch(arrayOf("text/markdown", "text/*"))
            },
            onDismiss = { showSkillSheet = false },
        )
    }

    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text(stringResource(R.string.settings_brave_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_brave_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        placeholder = { Text(stringResource(R.string.settings_brave_placeholder)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onBraveApiKeyChange(apiKeyInput)
                    showApiKeyDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text(stringResource(R.string.settings_clear_dialog_title)) },
            text = { Text(stringResource(R.string.settings_clear_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDataDialog = false
                    onClearData()
                }) {
                    Text(stringResource(R.string.settings_clear_everything), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        }

        HorizontalDivider(thickness = 1.dp, color = semanticColors.ui.dividerColor)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Privacy section
            SectionHeader(stringResource(R.string.settings_section_privacy))
            SettingsItem(
                icon = Icons.Default.Fingerprint,
                title = stringResource(R.string.settings_biometric_lock),
                subtitle = stringResource(R.string.settings_biometric_desc),
                trailing = { BorizonSwitch(checked = biometricEnabled, onCheckedChange = onBiometricToggle) }
            )
            SettingsItem(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.settings_on_device_ai),
                subtitle = stringResource(R.string.settings_on_device_desc),
                trailing = {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(stringResource(R.string.settings_active), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Permissions section
            SectionHeader(stringResource(R.string.settings_section_permissions))

            val context = LocalContext.current

            // Bump to re-check all permissions after grant or returning from system settings
            var permVersion by remember { mutableIntStateOf(0) }

            // Also refresh when returning from system settings (notification listener)
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) permVersion++
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // Runtime permission states — re-read on every permVersion change
            val micGranted = permVersion.run {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val calendarGranted = permVersion.run {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val contactsGranted = permVersion.run {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val notifGranted = permVersion.run {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val notifListenerGranted = permVersion.run {
                Settings.Secure.getString(
                    context.contentResolver, "enabled_notification_listeners"
                )?.contains(context.packageName) == true
            }
            val smsGranted = permVersion.run {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val callLogGranted = permVersion.run {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            // Permission request launchers — bump version on result to trigger refresh
            val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }
            val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }
            val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }
            val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }
            val callLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }

            SettingsItem(
                icon = Icons.Default.Mic,
                title = stringResource(R.string.settings_microphone),
                subtitle = if (micGranted) stringResource(R.string.settings_mic_granted) else stringResource(R.string.settings_mic_needed),
                onClick = { if (!micGranted) micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                trailing = { PermissionBadge(granted = micGranted) }
            )
            SettingsItem(
                icon = Icons.Default.CalendarMonth,
                title = stringResource(R.string.settings_calendar),
                subtitle = if (calendarGranted) stringResource(R.string.settings_calendar_granted) else stringResource(R.string.settings_calendar_needed),
                onClick = { if (!calendarGranted) calendarLauncher.launch(android.Manifest.permission.READ_CALENDAR) },
                trailing = { PermissionBadge(granted = calendarGranted) }
            )
            SettingsItem(
                icon = Icons.Default.Contacts,
                title = stringResource(R.string.settings_contacts),
                subtitle = if (contactsGranted) stringResource(R.string.settings_contacts_granted) else stringResource(R.string.settings_contacts_needed),
                onClick = { if (!contactsGranted) contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS) },
                trailing = { PermissionBadge(granted = contactsGranted) }
            )
            SettingsItem(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.settings_notifications),
                subtitle = if (notifGranted) stringResource(R.string.settings_notif_granted) else stringResource(R.string.settings_notif_needed),
                onClick = { if (!notifGranted) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                trailing = { PermissionBadge(granted = notifGranted) }
            )
            SettingsItem(
                icon = Icons.Default.NotificationsActive,
                title = stringResource(R.string.settings_read_notifications),
                subtitle = if (notifListenerGranted) stringResource(R.string.settings_read_notif_granted) else stringResource(R.string.settings_read_notif_needed),
                onClick = {
                    if (!notifListenerGranted) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                },
                trailing = { PermissionBadge(granted = notifListenerGranted) }
            )
            SettingsItem(
                icon = Icons.Default.Sms,
                title = stringResource(R.string.settings_sms),
                subtitle = if (smsGranted) stringResource(R.string.settings_sms_granted) else stringResource(R.string.settings_sms_needed),
                onClick = { if (!smsGranted) smsLauncher.launch(android.Manifest.permission.READ_SMS) },
                trailing = { PermissionBadge(granted = smsGranted) }
            )
            SettingsItem(
                icon = Icons.Default.Call,
                title = stringResource(R.string.settings_call_log),
                subtitle = if (callLogGranted) stringResource(R.string.settings_call_log_granted) else stringResource(R.string.settings_call_log_needed),
                onClick = { if (!callLogGranted) callLogLauncher.launch(android.Manifest.permission.READ_CALL_LOG) },
                trailing = { PermissionBadge(granted = callLogGranted) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Web search section
            SectionHeader(stringResource(R.string.settings_section_web_search))
            SettingsItem(
                icon = Icons.Default.Language,
                title = stringResource(R.string.settings_brave_api_key),
                subtitle = if (braveApiKey.isBlank()) stringResource(R.string.settings_brave_not_configured)
                           else stringResource(R.string.settings_brave_configured),
                onClick = {
                    apiKeyInput = ""
                    showApiKeyDialog = true
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Data section
            SectionHeader(stringResource(R.string.settings_section_data))
            SettingsItem(icon = Icons.Default.DeleteForever, title = stringResource(R.string.settings_clear_all_data),
                subtitle = stringResource(R.string.settings_clear_desc),
                onClick = { showClearDataDialog = true }, isDestructive = true)

            Spacer(modifier = Modifier.height(12.dp))

            // Skills section
            SectionHeader(stringResource(R.string.settings_section_skills))
            val activeSkills = skills.count { it.selected }
            SettingsItem(
                icon = Icons.Default.AutoAwesome,
                title = stringResource(R.string.settings_manage_skills),
                subtitle = "$activeSkills of ${skills.size} active",
                onClick = { showSkillSheet = true },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Model section
            SectionHeader(stringResource(R.string.settings_section_ai_model))

            ModelStatusCard(
                modelInfo = modelInfo, downloadState = downloadState,
                selectedModel = selectedModel,
                onDownloadModel = onDownloadModel, onDeleteModel = onDeleteModel,
                onSelectModel = onSelectModel
            )

            SettingsItem(icon = Icons.Default.Memory, title = stringResource(R.string.settings_inference_engine),
                subtitle = when (modelInfo.state) {
                    is ModelState.Ready -> stringResource(R.string.settings_model_backend, "LiteRT ${modelInfo.state.backend}")
                    is ModelState.Loading -> "${stringResource(R.string.settings_state_loading)}..."
                    is ModelState.Error -> stringResource(R.string.settings_state_error)
                    else -> stringResource(R.string.settings_waiting_for_model)
                }
            )
            SettingsItem(icon = Icons.Default.Tune, title = stringResource(R.string.settings_model_config),
                subtitle = stringResource(R.string.settings_config_summary, String.format("%.1f", modelConfig.temperature), modelConfig.topK.toString(), String.format("%.2f", modelConfig.topP)),
                onClick = { showConfigSheet = true }
            )
            SettingsItem(icon = Icons.Default.Info, title = stringResource(R.string.settings_version),
                subtitle = stringResource(R.string.settings_version_detail))

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModelStatusCard(
    modelInfo: ModelInfo,
    downloadState: ModelDownloader.DownloadState,
    selectedModel: String,
    onDownloadModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    val variant = ModelDownloader.variant(selectedModel)
    val isModelBusy = modelInfo.state is ModelState.Loading ||
        downloadState is ModelDownloader.DownloadState.Downloading

    BorizonCard(
        surfaceLevel = SurfaceLevel.Default,
        accentStripe = AccentStripe(color = MaterialTheme.colorScheme.primary, position = StripePosition.TOP, width = 4.dp),
        cornerSize = 12.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(variant.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            modelInfo.isDownloaded && modelInfo.state is ModelState.Ready ->
                                "${stringResource(R.string.settings_model_loaded)} • ${"%.1f".format(modelInfo.fileSizeMb / 1024.0)} GB"
                            modelInfo.isDownloaded -> "${stringResource(R.string.settings_model_downloaded)} • ${"%.1f".format(modelInfo.fileSizeMb / 1024.0)} GB"
                            else -> "${stringResource(R.string.settings_model_not_downloaded)} • ~${"%.1f".format(variant.expectedSize / 1_000_000_000.0)} GB"
                        },
                        style = Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val (badgeText, badgeColor) = when {
                    modelInfo.state is ModelState.Ready -> stringResource(R.string.settings_state_ready) to MaterialTheme.colorScheme.primary
                    modelInfo.state is ModelState.Loading -> stringResource(R.string.settings_state_loading) to MaterialTheme.colorScheme.tertiary
                    modelInfo.state is ModelState.Error -> stringResource(R.string.settings_state_error) to MaterialTheme.colorScheme.error
                    modelInfo.isDownloaded -> stringResource(R.string.settings_state_idle) to MaterialTheme.colorScheme.outline
                    else -> stringResource(R.string.settings_state_needed) to MaterialTheme.colorScheme.error
                }
                Surface(shape = RoundedCornerShape(6.dp), color = badgeColor.copy(alpha = 0.2f)) {
                    Text(badgeText, style = Metadata, color = badgeColor, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }

            // Model variant picker — hidden during active download/load
            if (!isModelBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModelDownloader.VARIANTS.forEach { v ->
                        val isSelected = v.key == selectedModel
                        val colors = if (isSelected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                        OutlinedButton(
                            onClick = { onSelectModel(v.key) },
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(v.key, style = MaterialTheme.typography.labelLarge)
                                Text("~${"%.1f".format(v.expectedSize / 1_000_000_000.0)} GB", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = downloadState is ModelDownloader.DownloadState.Downloading,
                enter = fadeIn(), exit = fadeOut()
            ) {
                val progress = (downloadState as? ModelDownloader.DownloadState.Downloading)?.progress ?: 0f
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Text(stringResource(R.string.settings_downloading, (progress * 100).toInt()), style = Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }

            (downloadState as? ModelDownloader.DownloadState.Error)?.let { error ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                ) {
                    Text(error.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!modelInfo.isDownloaded && downloadState !is ModelDownloader.DownloadState.Downloading) {
                    OutlinedButton(onClick = onDownloadModel, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_download_model))
                    }
                }
                if (modelInfo.isDownloaded && modelInfo.needsUpdate) {
                    Button(onClick = onDownloadModel, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        )
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update to ${modelInfo.currentVersion}")
                    }
                }
                if (modelInfo.isDownloaded) {
                    OutlinedButton(onClick = onDeleteModel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_delete_model))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBadge(granted: Boolean) {
    val (text, color) = if (granted) stringResource(R.string.granted) to MaterialTheme.colorScheme.primary
    else stringResource(R.string.grant) to MaterialTheme.colorScheme.outline
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = SectionHeader,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    isDestructive: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    val semanticColors = LocalBorizonSemanticColors.current
    val contentColor = if (isDestructive) semanticColors.status.error else MaterialTheme.colorScheme.onSurface

    BorizonCard(
        surfaceLevel = SurfaceLevel.Low,
        onClick = onClick,
        accentStripe = if (isDestructive) AccentStripe(
            color = semanticColors.status.error, position = StripePosition.LEFT, width = 3.dp
        ) else null,
        cornerSize = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null,
                tint = if (isDestructive) semanticColors.status.error
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, color = contentColor)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (isDestructive) semanticColors.status.error.copy(alpha = 0.7f)
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}
