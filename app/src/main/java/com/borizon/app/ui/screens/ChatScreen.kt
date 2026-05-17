package com.borizon.app.ui.screens

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.foundation.Image as FoundationImage
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.borizon.app.R
import com.borizon.app.ai.inference.ModelManager
import com.borizon.app.ai.prompts.StarterTemplate
import com.borizon.app.ai.tools.ToolEvent
import com.borizon.app.ai.tools.ToolNavigationTarget
import com.borizon.app.data.models.ChatMessage
import com.borizon.app.data.models.MessageType
import com.borizon.app.ui.components.*
import com.borizon.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val MESSAGE_GAP_MS = 5 * 60 * 1000L
private const val MAX_IMAGE_COUNT = 10

enum class NotificationSeverity { ERROR, WARNING, INFO }

private data class InlineNotification(
    val message: String,
    val severity: NotificationSeverity = NotificationSeverity.ERROR,
)

// ── ChatScreen Orchestrator ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionMessages: List<ChatMessage> = emptyList(),
    toolEvents: List<ToolEvent> = emptyList(),
    frozenToolEvents: List<ToolEvent> = emptyList(),
    inputHistory: List<String> = emptyList(),
    lastError: String? = null,
    lastResponseDurationMs: Long = 0L,
    // State groups
    voiceState: VoiceState = VoiceState(),
    streamingState: StreamingState = StreamingState(),
    chatModelState: ChatModelState = ChatModelState(),
    speechState: SpeechState = SpeechState(),
    conversationState: ConversationState = ConversationState(),
    dialogsState: InteractiveDialogsState = InteractiveDialogsState(),
    // Callbacks: send & media
    onSendMessageWithImages: (String, List<Bitmap>) -> Unit = { _, _ -> },
    onDocumentSelected: (String, String) -> Unit = { _, _ -> },
    // Callbacks: voice & audio
    onVoiceInput: () -> Unit = {},
    onStartAudioRecording: () -> Unit = {},
    onStopAudioRecording: () -> java.io.File? = { null },
    onCancelAudioRecording: () -> Unit = {},
    onSendMessageWithAudio: (java.io.File) -> Unit = {},
    // Callbacks: generation control
    onNewChat: (StarterTemplate) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onStopGenerating: () -> Unit = {},
    // Callbacks: speech
    onSpeakMessage: (String, Int) -> Unit = { _, _ -> },
    onStopSpeaking: () -> Unit = {},
    // Callbacks: conversation
    onSelectConversation: (Long) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
    onSearchConversations: (String, (List<com.borizon.app.ui.screens.BorizonViewModel.ConversationSearchResult>) -> Unit) -> Unit = { _, cb -> cb(emptyList()) },
    // Callbacks: input history
    onInputHistorySelect: (String) -> Unit = {},
    onClearInputHistory: () -> Unit = {},
    // Callbacks: model
    onRetryModelLoad: () -> Unit = {},
    onModelConfigChanged: (ModelConfig) -> Unit = {},
    // Callbacks: interactive dialogs
    onCompleteAskAction: (String) -> Unit = {},
    onDismissAskAction: () -> Unit = {},
    onCompleteConfirmAction: (Boolean) -> Unit = {},
    // Callbacks: pagination
    onLoadOlderMessages: () -> Unit = {},
) {
    val semanticColors = LocalBorizonSemanticColors.current
    val context = LocalContext.current
    var inputText by rememberSaveable { mutableStateOf("") }
    var pendingImages by rememberSaveable(
        stateSaver = listSaver(
            save = { bitmaps -> bitmaps.mapIndexed { i, bmp -> val f = java.io.File(context.cacheDir, "pending_img_$i.webp"); bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, f.outputStream()); f.absolutePath } },
            restore = { paths -> paths.map { android.graphics.BitmapFactory.decodeFile(it) } },
        )
    ) { mutableStateOf(emptyList()) }
    var pendingAudioFile by remember { mutableStateOf<java.io.File?>(null) }
    var isClipRecording by remember { mutableStateOf(false) }
    var clipRecordingStart by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            pendingImages.forEach { it.recycle() }
            pendingAudioFile?.delete()
        }
    }
    val listState = rememberLazyListState()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val populateInput: (String) -> Unit = { text ->
        inputText = text
        focusRequester.requestFocus()
    }
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val view = LocalView.current

    var fullscreenImage by remember { mutableStateOf<Bitmap?>(null) }
    var askInputValue by remember { mutableStateOf("") }

    // ── Tool Dialogs ──────────────────────────────────────────────────
    if (dialogsState.pendingAskAction != null) {
        AlertDialog(
            onDismissRequest = onDismissAskAction,
            title = { Text(dialogsState.pendingAskAction.dialogTitle) },
            text = {
                OutlinedTextField(
                    value = askInputValue,
                    onValueChange = { askInputValue = it },
                    label = { Text(dialogsState.pendingAskAction.fieldLabel) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCompleteAskAction(askInputValue)
                    askInputValue = ""
                }) { Text(stringResource(R.string.chat_send)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDismissAskAction()
                    askInputValue = ""
                }) { Text(stringResource(R.string.chat_skip)) }
            },
        )
    }

    if (dialogsState.pendingConfirmAction != null) {
        AlertDialog(
            onDismissRequest = { onCompleteConfirmAction(false) },
            title = { Text(stringResource(R.string.chat_confirm)) },
            text = { Text(dialogsState.pendingConfirmAction.message) },
            confirmButton = {
                TextButton(onClick = { onCompleteConfirmAction(true) }) { Text(stringResource(R.string.chat_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { onCompleteConfirmAction(false) }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ── Launchers ─────────────────────────────────────────────────────
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            if (pendingImages.size >= MAX_IMAGE_COUNT) return@let
            val source = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            val resized = com.borizon.app.util.BitmapUtils.scaleToFit(source, 1024)
            pendingImages = pendingImages + resized
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            context.contentResolver.takeIf { cr -> it.scheme == "content" }?.openInputStream(it)?.use { stream ->
                val size = stream.available().coerceAtMost(1_000_000)
                if (size >= 1_000_000) {
                    android.widget.Toast.makeText(context, context.getString(R.string.chat_file_too_large), android.widget.Toast.LENGTH_SHORT).show()
                    return@let
                }
                val text = stream.bufferedReader().readText()
                val fileName = android.provider.OpenableColumns.DISPLAY_NAME.let { col ->
                    context.contentResolver.query(it, arrayOf(col), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(col)) else context.getString(R.string.chat_document)
                    }
                }
                onDocumentSelected(text, fileName ?: context.getString(R.string.chat_document))
            }
        }
    }
    var showCameraSheet by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCameraSheet = true
    }
    val isKeyboardOpen by remember {
        derivedStateOf {
            val insets = ViewCompat.getRootWindowInsets(view) ?: return@derivedStateOf false
            insets.isVisible(WindowInsetsCompat.Type.ime())
        }
    }

    val shareText: (String) -> Unit = { text ->
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(shareIntent, context.getString(R.string.chat_share)))
    }

    var showInputHistory by remember { mutableStateOf(false) }
    var showConfigSheet by remember { mutableStateOf(false) }

    // ── Inline notification system ────────────────────────────────────
    var inlineNotification by remember { mutableStateOf<InlineNotification?>(null) }

    LaunchedEffect(lastError) {
        if (lastError != null) {
            inlineNotification = InlineNotification(lastError, NotificationSeverity.ERROR)
            delay(4000)
            inlineNotification = null
        }
    }
    LaunchedEffect(chatModelState.backgroundError) {
        if (chatModelState.backgroundError != null) {
            inlineNotification = InlineNotification(chatModelState.backgroundError, NotificationSeverity.WARNING)
            delay(4000)
            inlineNotification = null
        }
    }

    var showErrorDialog by remember { mutableStateOf<String?>(null) }
    var lastShownError by remember { mutableStateOf("") }
    LaunchedEffect(chatModelState.modelState) {
        if (chatModelState.modelState is ModelManager.ModelState.Error) {
            val msg = (chatModelState.modelState as ModelManager.ModelState.Error).message
            if (msg != lastShownError) { lastShownError = msg; showErrorDialog = msg }
        } else { lastShownError = "" }
    }

    // ── Auto-scroll ───────────────────────────────────────────────────
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(sessionMessages.size) {
        if (sessionMessages.isNotEmpty()) {
            val itemCount = listState.layoutInfo.totalItemsCount
            if (itemCount > 0) listState.animateScrollToItem(itemCount - 1, scrollOffset = 1000000)
        }
    }
    LaunchedEffect(streamingState.isGenerating) {
        if (streamingState.isGenerating) {
            val itemCount = listState.layoutInfo.totalItemsCount
            if (itemCount > 0) listState.animateScrollToItem(itemCount - 1, scrollOffset = 1000000)
        }
    }
    LaunchedEffect(Unit) {
        var lastScrollMs = 0L
        snapshotFlow { streamingState.streamingText to streamingState.streamingThinkingText }.collect { _ ->
            if (sessionMessages.isEmpty()) return@collect
            val now = System.currentTimeMillis()
            if (now - lastScrollMs < 100) return@collect
            lastScrollMs = now
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
            val itemCount = listState.layoutInfo.totalItemsCount
            val canAutoScroll =
                lastVisible.index == itemCount - 1 &&
                lastVisible.offset + lastVisible.size - listState.layoutInfo.viewportEndOffset < 90
            if (canAutoScroll && itemCount > 0) listState.animateScrollToItem(itemCount - 1, scrollOffset = 1000000)
        }
    }

    var isLoadingOlder by remember { mutableStateOf(false) }
    LaunchedEffect(listState, conversationState.hasOlderMessages) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstVisible ->
                if (firstVisible <= 1 && conversationState.hasOlderMessages && !isLoadingOlder && sessionMessages.isNotEmpty()) {
                    isLoadingOlder = true
                    val oldSize = sessionMessages.size
                    onLoadOlderMessages()
                    snapshotFlow { sessionMessages.size }.first { it != oldSize || !conversationState.hasOlderMessages }
                    val newSize = sessionMessages.size
                    if (newSize > oldSize) listState.scrollToItem(newSize - oldSize + listState.firstVisibleItemIndex)
                    isLoadingOlder = false
                }
            }
    }

    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(streamingState.isGenerating, streamingState.generationStartTime) {
        if (streamingState.isGenerating && streamingState.generationStartTime > 0) {
            elapsedSeconds = 0
            while (true) { kotlinx.coroutines.delay(1000); elapsedSeconds++ }
        }
    }

    // ── Main Layout ───────────────────────────────────────────────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                modifier = Modifier.width(320.dp)
            ) {
                ConversationDrawer(
                    conversations = conversationState.conversations,
                    activeConversationId = conversationState.activeConversationId,
                    onSelectConversation = onSelectConversation,
                    onDeleteConversation = onDeleteConversation,
                    onNewChat = { onNewChat(StarterTemplate.DEFAULT) },
                    onClose = { coroutineScope.launch { drawerState.close() } },
                    onSearch = onSearchConversations,
                )
            }
        }
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                ModelStatusBar(
                    modelState = chatModelState.modelState,
                    isGenerating = streamingState.isGenerating,
                    tokensPerSecond = streamingState.streamingTokensPerSecond,
                    wallClockTps = streamingState.streamingWallClockTps
                )
            }
            if (chatModelState.isBackgroundProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.chat_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { onNewChat(StarterTemplate.DEFAULT) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_new_chat),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { showConfigSheet = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.chat_model_config),
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
        HorizontalDivider(thickness = 1.dp, color = semanticColors.ui.dividerColor)

        // Message list
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isLoadingOlder) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                if (!streamingState.isGenerating && sessionMessages.isEmpty() && streamingState.streamingText.isBlank()) {
                    item {
                        EmptyChatState(onNewChat = onNewChat, onPopulateInput = populateInput)
                    }
                }

                itemsIndexed(sessionMessages, key = { index, msg -> msg.timestamp * 1000 + index }) { index, message ->
                    val prevMessage = sessionMessages.getOrNull(index - 1)
                    val showDateSeparator = prevMessage == null || !isSameDay(message.timestamp, prevMessage.timestamp)
                    if (showDateSeparator) DateSeparator(timestamp = message.timestamp)

                    if (message.type == MessageType.CONFIG_CHANGE) {
                        ConfigChangeBubble(changes = message.configChanges ?: emptyMap())
                        return@itemsIndexed
                    }
                    if (message.type == MessageType.WEBVIEW) {
                        WebViewCard(title = message.webViewTitle ?: "Web view", url = message.webViewUrl, aspectRatio = message.webViewAspectRatio)
                        return@itemsIndexed
                    }
                    if (index == sessionMessages.lastIndex && message.type == MessageType.CONFIG_CHANGE && chatModelState.isReinitializing) {
                        ReinitLoadingIndicator()
                    }

                    val showTimestamp = shouldShowTimestamp(index, sessionMessages)
                    val isLastAssistant = !message.isUser() && index == sessionMessages.lastIndex && !streamingState.isGenerating

                    val toolEventsToShow = when {
                        isLastAssistant && frozenToolEvents.isNotEmpty() -> frozenToolEvents
                        !message.isUser() && !streamingState.isGenerating && message.toolEvents.isNotEmpty() -> message.toolEvents
                        else -> null
                    }
                    if (toolEventsToShow != null) {
                        ToolTimelinePanel(events = toolEventsToShow, isLive = false, onToolClick = { _ -> })
                    }

                    if (!message.isUser() && !streamingState.isGenerating) {
                        message.thinkingContent?.let { thinking ->
                            if (thinking.isNotBlank()) {
                                ThinkingBubble(
                                    thinkingText = thinking,
                                    isStillThinking = false,
                                    onExpand = { coroutineScope.launch { listState.animateScrollToItem(index) } },
                                )
                            }
                        }
                    }

                    ChatMessageBubble(
                        message = message,
                        showTimestamp = showTimestamp,
                        screenWidth = screenWidth,
                        isLastAssistant = isLastAssistant,
                        isSpeakingThis = speechState.speakingMessageIndex == index,
                        responseDurationMs = if (isLastAssistant) lastResponseDurationMs else 0L,
                        onCopy = {},
                        onRegenerate = onRegenerate,
                        onSpeak = { onSpeakMessage(message.content, index) },
                        onStopSpeaking = onStopSpeaking,
                        onShare = shareText,
                        onImageClick = { fullscreenImage = it },
                    )
                }

                if (streamingState.isGenerating) {
                    item(key = "tool_timeline") {
                        ToolTimelinePanel(events = toolEvents, isLive = true, onToolClick = { _ -> })
                    }
                    item(key = "live_response") {
                        if (streamingState.streamingThinkingText.isNotBlank()) {
                            ThinkingBubble(
                                thinkingText = streamingState.streamingThinkingText,
                                isStillThinking = streamingState.streamingText.isBlank()
                            )
                        }
                        if (streamingState.streamingText.isNotBlank()) {
                            StreamingBubble(text = streamingState.streamingText, screenWidth = screenWidth, elapsedSeconds = elapsedSeconds, tokensPerSecond = streamingState.streamingTokensPerSecond, wallClockTps = streamingState.streamingWallClockTps)
                        } else if (streamingState.showTypingDots) {
                            val latestTool = toolEvents.lastOrNull { it.isInProgress }?.label
                                ?: toolEvents.lastOrNull()?.label
                            TypingIndicator(elapsedSeconds = elapsedSeconds, activityLabel = latestTool)
                        }
                    }
                }
            }

            fullscreenImage?.let { bitmap ->
                Dialog(onDismissRequest = { fullscreenImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable { fullscreenImage = null }) {
                        FoundationImage(bitmap = bitmap.asImageBitmap(), contentDescription = stringResource(R.string.chat_full_image),
                            modifier = Modifier.align(Alignment.Center).fillMaxSize().padding(24.dp), contentScale = ContentScale.Fit)
                        IconButton(onClick = { fullscreenImage = null },
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(40.dp).background(Color.White.copy(alpha = 0.15f), CircleShape)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        // Pending images strip
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pendingImages.forEachIndexed { index, bitmap ->
                    Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        FoundationImage(bitmap = bitmap.asImageBitmap(), contentDescription = stringResource(R.string.chat_pending_image, index + 1),
                            modifier = Modifier.fillMaxSize().clickable { fullscreenImage = bitmap }, contentScale = ContentScale.Crop)
                        IconButton(onClick = {
                            val bitmapToRecycle = pendingImages[index]
                            pendingImages = pendingImages.toMutableList().also { it.removeAt(index) }
                            bitmapToRecycle.recycle()
                        }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp).padding(2.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_remove_image),
                                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // Audio recording overlay
        if (isClipRecording) {
            AudioRecordingOverlay(
                amplitude = voiceState.audioClipAmplitude,
                elapsedMs = if (clipRecordingStart > 0) System.currentTimeMillis() - clipRecordingStart else 0L,
                onStop = {
                    isClipRecording = false
                    val file = onStopAudioRecording()
                    if (file != null && file.exists()) pendingAudioFile = file
                },
                onCancel = { isClipRecording = false; onCancelAudioRecording() }
            )
        }

        // Audio clip preview
        pendingAudioFile?.let { audioFile ->
            if (audioFile.exists()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AudioPlaybackPanel(wavFile = audioFile, accentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { pendingAudioFile = null; audioFile.delete() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_remove_audio),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = {
                            val file = pendingAudioFile; pendingAudioFile = null
                            if (file != null) onSendMessageWithAudio(file)
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Send, contentDescription = stringResource(R.string.chat_send_audio),
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // Inline notification banner (above input bar, never covers it)
        AnimatedVisibility(
            visible = inlineNotification != null,
            enter = expandVertically(expandFrom = Alignment.Bottom),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            inlineNotification?.let { notif ->
                val semanticColors = LocalBorizonSemanticColors.current
                val (containerColor, contentColor, icon) = when (notif.severity) {
                    NotificationSeverity.ERROR -> Triple(
                        semanticColors.status.error.copy(alpha = 0.15f),
                        semanticColors.status.error,
                        Icons.Filled.Error,
                    )
                    NotificationSeverity.WARNING -> Triple(
                        semanticColors.status.warning.copy(alpha = 0.15f),
                        semanticColors.status.warning,
                        Icons.Filled.Warning,
                    )
                    NotificationSeverity.INFO -> Triple(
                        semanticColors.status.info.copy(alpha = 0.15f),
                        semanticColors.status.info,
                        Icons.Filled.Info,
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = containerColor,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(notif.message, style = MaterialTheme.typography.bodySmall, color = contentColor, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { inlineNotification = null },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // Input bar
        InputBar(
            inputText = inputText,
            isGenerating = streamingState.isGenerating,
            isRecording = voiceState.isRecording,
            voiceAmplitude = voiceState.voiceAmplitude,
            transcriptionPartial = voiceState.transcriptionPartial,
            isKeyboardOpen = isKeyboardOpen,
            hasHistory = inputHistory.isNotEmpty(),
            onInputTextChange = { inputText = it },
            onSend = {
                val text = inputText.trim()
                val images = pendingImages.toList()
                if (text.isNotBlank() || images.isNotEmpty()) {
                    inputText = ""; pendingImages = emptyList()
                    keyboardController?.hide(); focusManager.clearFocus()
                    onSendMessageWithImages(text, images)
                }
            },
            onStopGenerating = onStopGenerating,
            onVoiceInput = onVoiceInput,
            onPickImage = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPickDocument = { documentPickerLauncher.launch("text/*") },
            onShowHistory = { showInputHistory = true },
            hasPendingImages = pendingImages.isNotEmpty(),
            onPickFromCamera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) showCameraSheet = true
                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            isClipRecording = isClipRecording,
            audioClipAmplitude = voiceState.audioClipAmplitude,
            onStartClipRecording = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    onStartAudioRecording(); isClipRecording = true; clipRecordingStart = System.currentTimeMillis()
                }
            },
            onStopClipRecording = {
                val file = onStopAudioRecording(); isClipRecording = false
                if (file != null && file.exists()) pendingAudioFile = file
            },
            onCancelClipRecording = { isClipRecording = false; onCancelAudioRecording() },
            isReinitializing = chatModelState.isReinitializing,
            focusRequester = focusRequester,
        )

        if (showCameraSheet) {
            CameraCaptureSheet(
                onImageCaptured = { bitmap -> if (pendingImages.size < MAX_IMAGE_COUNT) pendingImages = pendingImages + bitmap; showCameraSheet = false },
                onDismiss = { showCameraSheet = false },
            )
        }
    }

    if (showInputHistory) {
        ModalBottomSheet(onDismissRequest = { showInputHistory = false }, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            InputHistorySheet(
                history = inputHistory,
                onSelect = { text -> inputText = ""; showInputHistory = false; onInputHistorySelect(text) },
                onClear = onClearInputHistory,
            )
        }
    }

    showErrorDialog?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            title = { Text(stringResource(R.string.chat_error_title)) },
            text = { Text(errorMsg, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { showErrorDialog = null; onRetryModelLoad() }) { Text(stringResource(R.string.retry)) } },
            dismissButton = { TextButton(onClick = { showErrorDialog = null }) { Text(stringResource(R.string.dismiss)) } }
        )
    }

    if (showConfigSheet) {
        ModelConfigSheet(
            initialConfig = chatModelState.modelConfig,
            onDismiss = { showConfigSheet = false },
            onApply = { config -> onModelConfigChanged(config); showConfigSheet = false }
        )
    }
    } // Box
    } // ModalNavigationDrawer
}

// ── Input Bar ────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    inputText: String,
    isGenerating: Boolean,
    isRecording: Boolean,
    voiceAmplitude: Int,
    transcriptionPartial: String,
    isKeyboardOpen: Boolean,
    hasHistory: Boolean,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGenerating: () -> Unit,
    onVoiceInput: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onShowHistory: () -> Unit,
    hasPendingImages: Boolean = false,
    onPickFromCamera: () -> Unit = {},
    isClipRecording: Boolean = false,
    audioClipAmplitude: Int = 0,
    onStartClipRecording: () -> Unit = {},
    onStopClipRecording: () -> Unit = {},
    onCancelClipRecording: () -> Unit = {},
    isReinitializing: Boolean = false,
    focusRequester: FocusRequester
) {
    val semanticColors = LocalBorizonSemanticColors.current
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                color = semanticColors.chat.inputBarBg, border = BorderStroke(1.dp, semanticColors.chat.inputBarBorder)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    if (hasHistory) {
                        IconButton(onClick = onShowHistory, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.History, contentDescription = stringResource(R.string.chat_input_history),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }

                    val currentOnVoiceInput by rememberUpdatedState(onVoiceInput)
                    val currentOnStartClipRecording by rememberUpdatedState(onStartClipRecording)
                    Box(modifier = Modifier.size(40.dp).pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { currentOnVoiceInput() },
                            onLongPress = { currentOnStartClipRecording() },
                        )
                    }, contentAlignment = Alignment.Center) {
                        when {
                            isClipRecording -> VoiceAmplitudeVisualizer(amplitude = audioClipAmplitude)
                            isRecording -> VoiceAmplitudeVisualizer(amplitude = voiceAmplitude)
                            else -> Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.chat_voice_input),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }

                    Box {
                        var showAttachMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showAttachMenu = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.chat_attach),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_photo_gallery)) },
                                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = { showAttachMenu = false; onPickImage() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_document)) },
                                leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = { showAttachMenu = false; onPickDocument() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_take_photo)) },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = { showAttachMenu = false; onPickFromCamera() },
                            )
                        }
                    }

                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp).focusRequester(focusRequester),
                        minLines = if (isKeyboardOpen) 3 else 1,
                        maxLines = 5,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 24.dp)) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = when {
                                            isRecording && transcriptionPartial.isNotBlank() -> transcriptionPartial
                                            isRecording -> stringResource(R.string.chat_listening)
                                            else -> stringResource(R.string.chat_placeholder)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = semanticColors.ui.emptyStateDescColor
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (isGenerating || isReinitializing) {
                        IconButton(onClick = onStopGenerating, modifier = Modifier.size(40.dp)) {
                            if (isReinitializing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = semanticColors.status.error)
                            } else {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop),
                                    tint = semanticColors.status.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    } else {
                        FilledIconButton(onClick = onSend, enabled = inputText.isNotBlank() || hasPendingImages,
                            modifier = Modifier.size(36.dp), shape = CircleShape) {
                            Icon(Icons.Default.Send, contentDescription = stringResource(R.string.chat_send), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Generation Indicators ────────────────────────────────────────────

@Composable
private fun ModelStatusBar(
    modelState: ModelManager.ModelState,
    isGenerating: Boolean,
    tokensPerSecond: Float,
    wallClockTps: Float = 0f
) {
    var showDialog by remember { mutableStateOf(false) }
    val semanticColors = LocalBorizonSemanticColors.current
    val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val separator = " · "

    Row(modifier = Modifier.clickable { showDialog = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (modelState) {
            is ModelManager.ModelState.Ready -> {
                Text(modelState.modelName, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                Text(separator, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                val dotColor = when (modelState.backend) {
                    "NPU" -> semanticColors.status.success
                    "GPU" -> semanticColors.status.warning
                    else -> semanticColors.status.info
                }
                Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape).align(Alignment.CenterVertically))
                Spacer(modifier = Modifier.width(3.dp))
                Text(modelState.backend, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                // Show wall-clock TPS (includes tool execution) when available,
                // fall back to raw inference TPS during initial streaming.
                val displayTps = if (wallClockTps > 0) wallClockTps else tokensPerSecond
                if (isGenerating && displayTps > 0) {
                    Text(separator, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                    Text("${displayTps.toInt()} tok/s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            is ModelManager.ModelState.Loading -> {
                val breathe by rememberInfiniteTransition(label = "statusBreathe").animateFloat(
                    0.4f, 1f, infiniteRepeatable(tween(800, easing = EaseInOutSine), RepeatMode.Reverse), "breathe"
                )
                CircularProgressIndicator(modifier = Modifier.size(10.dp).align(Alignment.CenterVertically), strokeWidth = 1.5.dp,
                    color = subtitleColor.copy(alpha = breathe), trackColor = Color.Transparent)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.chat_loading_model), style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
            is ModelManager.ModelState.Error -> {
                Box(modifier = Modifier.size(6.dp).background(semanticColors.status.error, CircleShape).align(Alignment.CenterVertically))
                Spacer(modifier = Modifier.width(3.dp))
                Text(stringResource(R.string.chat_model_error), style = MaterialTheme.typography.bodySmall, color = semanticColors.status.error)
            }
            else -> Text(stringResource(R.string.chat_no_model), style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.chat_model_status)) },
            text = {
                when (modelState) {
                    is ModelManager.ModelState.Ready -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(modelState.modelName, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.chat_model_backend, modelState.backend), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.chat_model_on_device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is ModelManager.ModelState.Loading -> Text(stringResource(R.string.chat_model_loading))
                    is ModelManager.ModelState.Error -> Text("Model failed: ${modelState.message}", color = MaterialTheme.colorScheme.error)
                    else -> Text(stringResource(R.string.chat_model_not_loaded))
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.ok)) } }
        )
    }
}

@Composable
private fun ThinkingBubble(
    thinkingText: String,
    isStillThinking: Boolean = true,
    onExpand: () -> Unit = {},
) {
    var isCollapsed by remember(isStillThinking) { mutableStateOf(!isStillThinking) }
    val semanticColors = LocalBorizonSemanticColors.current

    if (isStillThinking) {
        LaunchedEffect(thinkingText) { if (thinkingText.isNotBlank()) isCollapsed = false }
    }

    Column(modifier = Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.Start) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(if (isStillThinking) Modifier.background(semanticColors.chat.thinkingPanelBg) else Modifier)
                .clickable { isCollapsed = !isCollapsed; if (!isCollapsed) onExpand() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isStillThinking) {
                val infiniteTransition = rememberInfiniteTransition(label = "thinkingPulse")
                val alpha by infiniteTransition.animateFloat(0.4f, 1f,
                    infiniteRepeatable(tween(800, easing = EaseInOutSine), RepeatMode.Reverse), "thinkingAlpha")
                Surface(modifier = Modifier.size(6.dp), shape = CircleShape, color = semanticColors.chat.thinkingPanelAccent.copy(alpha = alpha)) {}
            } else {
                Surface(modifier = Modifier.size(6.dp), shape = CircleShape, color = semanticColors.chat.thinkingPanelAccent) {}
            }
            Text(
                text = if (isStillThinking) stringResource(R.string.chat_thinking_active) else stringResource(R.string.chat_thought_process),
                style = MaterialTheme.typography.labelSmall, color = semanticColors.chat.thinkingPanelAccent
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isCollapsed) stringResource(R.string.expand) else stringResource(R.string.collapse),
                tint = semanticColors.chat.senderLabelText,
                modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = if (isCollapsed) 0f else 180f }
            )
        }

        AnimatedVisibility(visible = !isCollapsed, enter = expandVertically(), exit = shrinkVertically()) {
            if (thinkingText.isNotBlank()) {
                Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
                    Surface(modifier = Modifier.width(2.dp).heightIn(min = 20.dp), shape = RoundedCornerShape(1.dp),
                        color = semanticColors.chat.thinkingPanelAccent.copy(alpha = 0.4f)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = thinkingText, style = EvidenceQuote, modifier = Modifier.padding(end = 32.dp))
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    screenWidth: androidx.compose.ui.unit.Dp,
    elapsedSeconds: Int = 0,
    tokensPerSecond: Float = 0f,
    wallClockTps: Float = 0f,
) {
    val maxBubbleWidth = screenWidth * 0.82f
    val semanticColors = LocalBorizonSemanticColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), "cursorAlpha")

    Column(modifier = Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.Start) {
        Row(horizontalArrangement = Arrangement.Start) {
            Surface(shape = MessageBubbleShape(radius = 18.dp, sharpCornerLeft = true), color = semanticColors.chat.agentBubbleBg,
                modifier = Modifier.widthIn(max = maxBubbleWidth)) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarkdownRenderer(markdown = text, modifier = Modifier.weight(1f, fill = false))
                    Box(modifier = Modifier
                        .width(2.dp)
                        .height(MaterialTheme.typography.bodyLarge.fontSize.value.dp)
                        .graphicsLayer { alpha = cursorAlpha }
                        .background(semanticColors.chat.streamingCursorColor, RoundedCornerShape(1.dp))
                    )
                }
            }
        }
        Text(text = "${elapsedSeconds}s", style = Timestamp, color = semanticColors.chat.dateSeparatorText, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        // Show wall-clock TPS when available (more accurate), else raw inference TPS
        val displayTps = if (wallClockTps > 0) wallClockTps else tokensPerSecond
        if (displayTps > 0) {
            Text(text = String.format("%.1f tok/s", displayTps), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
        }
    }
}

// ── Message List Helpers ─────────────────────────────────────────────

@Composable
private fun DateSeparator(timestamp: Long) {
    val semanticColors = LocalBorizonSemanticColors.current
    val todayLabel = stringResource(R.string.chat_today)
    val yesterdayLabel = stringResource(R.string.chat_yesterday)
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text = formatDateSeparator(timestamp, todayLabel, yesterdayLabel), style = Timestamp, color = semanticColors.chat.dateSeparatorText)
    }
}

@Composable
private fun ConfigChangeBubble(changes: Map<String, Pair<String, String>>) {
    if (changes.isEmpty()) return
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = stringResource(R.string.chat_configs_updated), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                changes.forEach { (param, oldValueToNew) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = param, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(text = oldValueToNew.first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f))
                        Text(text = stringResource(R.string.chat_arrow), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(text = oldValueToNew.second, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioRecordingOverlay(
    amplitude: Int,
    elapsedMs: Long,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val seconds = (elapsedMs / 1000).toInt().coerceAtMost(30)
    val semanticColors = LocalBorizonSemanticColors.current

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(8.dp).background(semanticColors.chat.recordingIndicatorColor, CircleShape))
            Text(text = stringResource(R.string.chat_recording_timer, seconds), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface)
            VoiceAmplitudeVisualizer(amplitude = amplitude, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_cancel_recording),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            FilledIconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.chat_stop_send), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ReinitLoadingIndicator() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Text(text = stringResource(R.string.chat_reinitializing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Utility Functions ────────────────────────────────────────────────

private fun ChatMessage.isUser(): Boolean = role == "user"

private val dateFormatSeparator = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
private val timeFormatShort = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = ts1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = ts2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun formatDateSeparator(timestamp: Long, todayLabel: String, yesterdayLabel: String): String {
    return when {
        isSameDay(System.currentTimeMillis(), timestamp) -> todayLabel
        isSameDay(System.currentTimeMillis() - 86400000L, timestamp) -> yesterdayLabel
        else -> dateFormatSeparator.format(Date(timestamp))
    }
}

private fun shouldShowTimestamp(index: Int, allMessages: List<ChatMessage>): Boolean {
    if (index <= 0) return true
    return allMessages[index].timestamp - allMessages[index - 1].timestamp > MESSAGE_GAP_MS
}
