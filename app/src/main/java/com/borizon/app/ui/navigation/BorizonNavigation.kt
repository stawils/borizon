package com.borizon.app.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.borizon.app.R
import com.borizon.app.ai.inference.ModelManager
import com.borizon.app.ai.prompts.StarterTemplate
import com.borizon.app.ui.screens.BiometricScreen
import com.borizon.app.ui.theme.BorizonMotion
import com.borizon.app.ui.screens.ChatScreen
import com.borizon.app.ui.screens.ChatModelState
import com.borizon.app.ui.screens.ConversationState
import com.borizon.app.ui.screens.InteractiveDialogsState
import com.borizon.app.ui.screens.SpeechState
import com.borizon.app.ui.screens.StreamingState
import com.borizon.app.ui.screens.VoiceState
import com.borizon.app.ui.screens.MemoryScreen
import com.borizon.app.ui.screens.ModelDownloadScreen
import com.borizon.app.ui.screens.BorizonViewModel
import com.borizon.app.di.AppLifecycleProviderImpl
import com.borizon.app.ui.screens.OnboardingScreen
import com.borizon.app.ui.screens.SettingsScreen
import com.borizon.app.ui.screens.TosScreen
import com.borizon.app.ui.screens.WelcomeScreen

object Routes {
    const val WELCOME = "welcome"
    const val ONBOARDING = "onboarding"
    const val BIOMETRIC = "biometric"
    const val TOS = "tos"
    const val MODEL_SETUP = "model_setup"
    const val CHAT = "chat"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
}

private data class BottomNavItem(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.CHAT, R.string.nav_chat, Icons.Filled.Chat),
    BottomNavItem(Routes.MEMORY, R.string.nav_memory, Icons.Outlined.Psychology),
    BottomNavItem(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings)
)

private const val ANIM_DURATION = 300

@Composable
fun BorizonNavHost(
    viewModel: BorizonViewModel,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val isOnboardingComplete by viewModel.isOnboardingComplete.collectAsStateWithLifecycle()
    val prefsLoaded by viewModel.prefsLoaded.collectAsStateWithLifecycle()

    // Don't build the nav graph until preferences are loaded.
    // The splash screen stays visible until prefsLoaded=true, so the user
    // never sees a flash of wrong content.
    if (!prefsLoaded) return

    val isFirstLaunch by viewModel.isFirstLaunch.collectAsStateWithLifecycle()
    val isTosAccepted by viewModel.isTosAccepted.collectAsStateWithLifecycle()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()

    // Track whether the returning user has passed the biometric gate this session.
    var biometricGatePassed by remember { mutableStateOf(false) }

    val startDestination = when {
        !isOnboardingComplete -> {
            if (isFirstLaunch) Routes.WELCOME else Routes.ONBOARDING
        }
        !isTosAccepted -> Routes.TOS
        isBiometricEnabled && !biometricGatePassed -> Routes.BIOMETRIC
        else -> Routes.CHAT
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val impl = viewModel.lifecycleProvider as? DefaultLifecycleObserver
        if (impl != null) {
            lifecycleOwner.lifecycle.addObserver(impl)
        }
        onDispose {
            val impl = viewModel.lifecycleProvider as? DefaultLifecycleObserver
            if (impl != null) {
                lifecycleOwner.lifecycle.removeObserver(impl)
            }
        }
    }

    // Re-gate with biometric when the app returns from background.
    // On resume: if biometric is enabled and the gate was previously passed,
    // reset the flag and navigate to the biometric screen.
    val isInForeground by viewModel.lifecycleProvider.isInForeground.collectAsStateWithLifecycle()
    LaunchedEffect(isInForeground) {
        if (isInForeground && biometricGatePassed && isBiometricEnabled) {
            biometricGatePassed = false
            navController.navigate(Routes.BIOMETRIC) {
                popUpTo(0) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    val view = LocalView.current
    val isKeyboardOpen by remember(view) {
        derivedStateOf {
            val insets = ViewCompat.getRootWindowInsets(view)
                ?: return@derivedStateOf false
            insets.isVisible(WindowInsetsCompat.Type.ime())
        }
    }
    val showBottomBar = !isKeyboardOpen && bottomNavItems.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        val label = stringResource(item.labelResId)
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    item.icon,
                                    contentDescription = label,
                                    tint = if (selected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            label = {
                                Text(
                                    label,
                                    color = if (selected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { BorizonMotion.NavEnterTransition },
            exitTransition = { BorizonMotion.NavExitTransition },
            popEnterTransition = { BorizonMotion.NavPopEnterTransition },
            popExitTransition = { BorizonMotion.NavPopExitTransition }
        ) {
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onStart = {
                        navController.navigate(Routes.ONBOARDING) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = { name ->
                        viewModel.completeOnboarding(name)
                        navController.navigate(Routes.TOS) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.BIOMETRIC) {
                BiometricScreen(
                    onAuthenticated = {
                        // Onboarding flow: first-time setup
                        if (!isOnboardingComplete) {
                            navController.navigate(Routes.MODEL_SETUP) {
                                popUpTo(Routes.BIOMETRIC) { inclusive = true }
                            }
                        } else {
                            // Returning user: biometric gate passed, go to chat
                            biometricGatePassed = true
                            navController.navigate(Routes.CHAT) {
                                popUpTo(Routes.BIOMETRIC) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(Routes.TOS) {
                TosScreen(
                    onAccept = {
                        viewModel.acceptTos()
                        navController.navigate(Routes.BIOMETRIC) {
                            popUpTo(Routes.TOS) { inclusive = true }
                        }
                    },
                    onDecline = {
                        navController.navigate(Routes.ONBOARDING) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.MODEL_SETUP) {
                val dlState by viewModel.downloadState.collectAsStateWithLifecycle()
                val info by viewModel.modelInfo.collectAsStateWithLifecycle()
                var hasNavigated by remember { mutableStateOf(false) }

                // Navigate to chat only after model is fully loaded and ready
                LaunchedEffect(info.state) {
                    if (info.state is com.borizon.app.ai.inference.ModelManager.ModelState.Ready && !hasNavigated) {
                        hasNavigated = true
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.MODEL_SETUP) { inclusive = true }
                        }
                    }
                }

                ModelDownloadScreen(
                    downloadState = dlState,
                    isModelDownloaded = info.isDownloaded,
                    modelState = info.state,
                    onDownload = { viewModel.downloadModel() },
                    onCancel = { viewModel.cancelDownload() },
                    onModelReady = { viewModel.loadDownloadedModel() }
                )
            }

            composable(Routes.CHAT) {
                val messages by viewModel.sessionMessages.collectAsStateWithLifecycle()
                val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
                val streaming by viewModel.streamingText.collectAsStateWithLifecycle()
                val streamingThinking by viewModel.streamingThinkingText.collectAsStateWithLifecycle()
                val lastError by viewModel.lastError.collectAsStateWithLifecycle()
                val modelState by viewModel.modelState.collectAsStateWithLifecycle()
                val genStartTime by viewModel.generationStartTime.collectAsStateWithLifecycle()
                val lastDurationMs by viewModel.lastResponseDurationMs.collectAsStateWithLifecycle()

                val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
                val voiceAmplitude by viewModel.voiceAmplitude.collectAsStateWithLifecycle()
                val transcriptionPartial by viewModel.transcriptionPartial.collectAsStateWithLifecycle()
                val isAudioClipRecording by viewModel.isAudioRecording.collectAsStateWithLifecycle()
                val audioClipAmplitude by viewModel.audioAmplitude.collectAsStateWithLifecycle()
                val micPermissionNeeded by viewModel.micPermissionNeeded.collectAsStateWithLifecycle()
                val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
                val speakingIndex by viewModel.speakingMessageIndex.collectAsStateWithLifecycle()
                val conversations by viewModel.conversations.collectAsStateWithLifecycle()
                val activeConvId by viewModel.activeConversationId.collectAsStateWithLifecycle()
                val inputHistory by viewModel.inputHistory.collectAsStateWithLifecycle()
                val toolEvents by viewModel.toolEvents.collectAsStateWithLifecycle()
                val frozenToolEvents by viewModel.frozenToolEvents.collectAsStateWithLifecycle()
                val config by viewModel.modelConfig.collectAsStateWithLifecycle()
                val streamingTps by viewModel.streamingTokensPerSecond.collectAsStateWithLifecycle()
                val wallClockTps by viewModel.wallClockTps.collectAsStateWithLifecycle()
                val isReiniting: Boolean by viewModel.isReinitializing.collectAsStateWithLifecycle()
                val isConvReady: Boolean by viewModel.isConversationReady.collectAsStateWithLifecycle()
                val isBgProcessing: Boolean by viewModel.isBackgroundProcessing.collectAsStateWithLifecycle()
                val bgError: String? by viewModel.backgroundError.collectAsStateWithLifecycle()

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.onMicPermissionResult(granted)
                }

                DisposableEffect(micPermissionNeeded) {
                    if (micPermissionNeeded) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    onDispose { }
                }

                ChatScreen(
                    sessionMessages = messages,
                    toolEvents = toolEvents,
                    frozenToolEvents = frozenToolEvents,
                    inputHistory = inputHistory,
                    lastError = lastError,
                    lastResponseDurationMs = lastDurationMs,
                    // State groups
                    voiceState = VoiceState(
                        isRecording = isRecording,
                        voiceAmplitude = voiceAmplitude,
                        transcriptionPartial = transcriptionPartial,
                        isAudioClipRecording = isAudioClipRecording,
                        audioClipAmplitude = audioClipAmplitude,
                    ),
                    streamingState = StreamingState(
                        isGenerating = isGenerating,
                        streamingText = streaming,
                        streamingThinkingText = streamingThinking,
                        generationStartTime = genStartTime,
                        streamingTokensPerSecond = streamingTps,
                        streamingWallClockTps = wallClockTps,
                    ),
                    chatModelState = ChatModelState(
                        modelState = modelState,
                        isReinitializing = isReiniting,
                        isBackgroundProcessing = isBgProcessing,
                        backgroundError = bgError,
                        modelConfig = config,
                    ),
                    speechState = SpeechState(
                        isSpeaking = isSpeaking,
                        speakingMessageIndex = speakingIndex,
                    ),
                    conversationState = ConversationState(
                        conversations = conversations,
                        activeConversationId = activeConvId,
                        hasOlderMessages = viewModel.hasOlderMessages.collectAsStateWithLifecycle().value,
                    ),
                    dialogsState = InteractiveDialogsState(
                        pendingAskAction = viewModel.pendingAskAction.collectAsStateWithLifecycle().value,
                        pendingConfirmAction = viewModel.pendingConfirmAction.collectAsStateWithLifecycle().value,
                    ),
                    // Callbacks
                    onSendMessageWithImages = { text, images -> viewModel.sendMessageWithImages(text, images) },
                    onDocumentSelected = { text, name -> viewModel.processDocument(text, name) },
                    onVoiceInput = {
                        if (isRecording) viewModel.stopListening()
                        else viewModel.startVoiceInput()
                    },
                    onStartAudioRecording = { viewModel.startAudioRecording() },
                    onStopAudioRecording = { viewModel.stopAudioRecording() },
                    onCancelAudioRecording = { viewModel.cancelAudioRecording() },
                    onSendMessageWithAudio = { audioFile -> viewModel.sendMessageWithAudio(audioFile) },
                    onNewChat = { template -> viewModel.newChat(template) },
                    onRegenerate = { viewModel.regenerate() },
                    onStopGenerating = { viewModel.stopGeneration() },
                    onSpeakMessage = { text, idx -> viewModel.speakMessage(text, idx) },
                    onStopSpeaking = { viewModel.stopSpeaking() },
                    onSelectConversation = { viewModel.loadConversation(it) },
                    onDeleteConversation = { viewModel.deleteConversation(it) },
                    onSearchConversations = { query, cb -> viewModel.searchConversations(query, cb) },
                    onInputHistorySelect = { viewModel.sendMessage(it) },
                    onClearInputHistory = { viewModel.clearInputHistory() },
                    onRetryModelLoad = { viewModel.retryModelLoad() },
                    onModelConfigChanged = { viewModel.updateModelConfig(it) },
                    onCompleteAskAction = { viewModel.completeAskAction(it) },
                    onDismissAskAction = { viewModel.dismissAskAction() },
                    onCompleteConfirmAction = { viewModel.completeConfirmAction(it) },
                    onLoadOlderMessages = { viewModel.loadOlderMessages() },
                )
            }

            composable(Routes.MEMORY) {
                val memories by viewModel.memories.collectAsStateWithLifecycle()
                MemoryScreen(
                    memories = memories,
                    onSearch = { query -> viewModel.searchMemories(query) },
                    onDelete = { id -> viewModel.deleteMemory(id) },
                )
            }

            composable(Routes.SETTINGS) {
                val info by viewModel.modelInfo.collectAsStateWithLifecycle()
                val dlState by viewModel.downloadState.collectAsStateWithLifecycle()
                val config by viewModel.modelConfig.collectAsStateWithLifecycle()
                val skillList by viewModel.skills.collectAsStateWithLifecycle()
                val apiKey by viewModel.braveApiKey.collectAsState(initial = "")
                val selModel by viewModel.selectedModel.collectAsStateWithLifecycle()

                SettingsScreen(
                    modelInfo = info,
                    downloadState = dlState,
                    modelConfig = config,
                    selectedModel = selModel,
                    skills = skillList,
                    braveApiKey = apiKey,
                    biometricEnabled = isBiometricEnabled,
                    onClearData = { viewModel.clearAllDataAndReset() },
                    onDownloadModel = { viewModel.downloadModel() },
                    onDeleteModel = { viewModel.deleteModel() },
                    onModelConfigChanged = { viewModel.updateModelConfig(it) },
                    onToggleSkill = { name, selected -> viewModel.toggleSkill(name, selected) },
                    onImportSkillFromDirectory = { uri -> viewModel.importSkillFromDirectory(uri) },
                    onImportSkillFromFile = { uri -> viewModel.importSkillFromFile(uri) },
                    onDeleteSkill = { name -> viewModel.deleteSkill(name) },
                    onBraveApiKeyChange = { viewModel.setBraveApiKey(it) },
                    onBiometricToggle = { viewModel.setBiometricEnabled(it) },
                    onSelectModel = { viewModel.setSelectedModel(it) },
                )
            }
        }
    }
}
