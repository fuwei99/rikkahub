package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolNames
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolDefaultEnabled
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    settings: Settings,
    conversation: Conversation,
    hazeState: HazeState,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    onUpdateChatModel: (Model) -> Unit,
    onUpdateImageGenerationModels: (List<Uuid>) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow
    val inputHazeStyle = HazeMaterials.thin(containerColor = hazeTintColor)

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 键盘弹出时让底部两角变直角，贴合 IME
    val imeVisible = WindowInsets.isImeVisible
    val containerShape = if (imeVisible) {
        MaterialTheme.shapes.largeIncreased.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
    } else {
        MaterialTheme.shapes.largeIncreased
    }

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = if (imeVisible) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape)
                    .then(
                        if (settings.displaySetting.enableBlurEffect) Modifier.hazeEffect(
                            state = hazeState
                        ) {
                            blurEffect {
                                style = inputHazeStyle
                            }
                        }
                        else Modifier
                    ),
                shape = containerShape,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    TextInputRow(
                        state = state,
                        completionProviders = completionProviders,
                        onSendMessage = { sendMessage() }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Model Picker
                            val effectiveModelId = conversation.modelId
                                ?: assistant.chatModelId
                                ?: settings.chatModelId
                            ModelSelector(
                                modelId = effectiveModelId,
                                providers = settings.providers,
                                onSelect = {
                                    onUpdateChatModel(it)
                                },
                                type = ModelType.CHAT,
                                onlyIcon = true,
                                modifier = Modifier,
                            )

                            // Search
                            val enableSearchMsg = stringResource(R.string.web_search_enabled)
                            val disableSearchMsg = stringResource(R.string.web_search_disabled)
                            val chatModel = settings.getCurrentChatModel()
                            SearchPickerButton(
                                enableSearch = enableSearch,
                                settings = settings,
                                onToggleSearch = { enabled ->
                                    onToggleSearch(enabled)
                                    toaster.show(
                                        message = if (enabled) enableSearchMsg else disableSearchMsg,
                                        duration = 1.seconds,
                                        type = if (enabled) {
                                            ToastType.Success
                                        } else {
                                            ToastType.Normal
                                        }
                                    )
                                },
                                onUpdateSearchService = onUpdateSearchService,
                                model = chatModel,
                            )

                            // Image generation: enable the tool and choose its default image model.
                            ImageGenerationPickerButton(
                                settings = settings,
                                assistant = assistant,
                                onUpdateAssistant = onUpdateAssistant,
                                onSelectModels = onUpdateImageGenerationModels,
                            )

                            if (assistant.enableMemory) {
                                MemoryPickerButton(
                                    assistant = assistant,
                                    options = state.memoryOptions,
                                    onUpdate = { state.memoryOptions = it },
                                )
                            }

                            LocalToolPickerButton(
                                availableTools = ChatInputState.CHAT_TOGGLEABLE_LOCAL_TOOLS,
                                defaultEnabledTools = assistant.localTools,
                                state = state,
                            )

                            if (assistant.workspaceId != null) {
                                WorkspaceToolPickerButton(
                                    workspaceId = assistant.workspaceId.toString(),
                                    state = state,
                                )
                            }

                            McpToolPickerButton(
                                settings = settings,
                                assistant = assistant,
                                state = state,
                            )

                            // Reasoning
                            val model = settings.getCurrentChatModel()
                            if (model?.isReasoningEnabled == true) {
                                ReasoningButton(
                                    reasoningLevel = assistant.reasoningLevel,
                                    onUpdateReasoningLevel = {
                                        onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                    },
                                    onlyIcon = true,
                                )
                            }

                        }

                        ActionIconButton(
                            onClick = onMoreClick
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.more_options)
                            )
                        }

                        if (asrState.isAvailable || asrState.isRecording) {
                            AsrButton(
                                state = asrState,
                                onClick = {
                                    when (asrState.status) {
                                        ASRStatus.Listening -> asr.stop()
                                        ASRStatus.Idle, ASRStatus.Error -> {
                                            if (!asrPermission.allRequiredPermissionsGranted) {
                                                asrPermission.requestPermissions()
                                            } else {
                                                asrBaseText = state.textContent.text.toString()
                                                asr.start { transcript ->
                                                    val spacer =
                                                        if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                    state.setMessageText(asrBaseText + spacer + transcript)
                                                }
                                            }
                                        }

                                        ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = !asrState.isRecording,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("chat_send_button")
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        enabled = loading || !state.isEmpty(),
                                        onClick = {
                                            sendMessage()
                                        }, onLongClick = {
                                            sendMessageWithoutAnswer()
                                        }
                                    )
                            ) {
                                val containerColor = when {
                                    loading -> MaterialTheme.colorScheme.errorContainer
                                    state.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                val contentColor = when {
                                    loading -> MaterialTheme.colorScheme.onErrorContainer
                                    state.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else -> MaterialTheme.colorScheme.onPrimary
                                }
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = CircleShape,
                                    color = containerColor,
                                    content = {})
                                if (loading) {
                                    KeepScreenOn()
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.stop),
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = HugeIcons.ArrowUp02,
                                        contentDescription = stringResource(R.string.send),
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    completionProviders: List<ChatCompletionProvider>,
    onSendMessage: () -> Unit,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatImageFilesByContents(
                                        listOf(uri),
                                        state.compressImages,
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists = completionProviders.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val primary = lists.firstOrNull()
                completionList = primary?.let { list ->
                    val mergedItems = lists
                        .filter { it.replacementRange == list.replacementRange }
                        .flatMap { it.items }
                        .distinctBy { it.label to it.insertText }
                        .sortedWith(
                            compareByDescending<ChatCompletionItem> { it.sortScore }
                                .thenBy { it.label.length }
                                .thenBy { it.label.lowercase() }
                        )
                        .take(8)
                    list.copy(items = mergedItems)
                }
            }
        }

        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    state.applyCompletion(list.replacementRange, item)
                    completionList = null
                },
            )
        }

        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                Text(stringResource(R.string.chat_input_placeholder))
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            trailingIcon = {
                if (isFocused) {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        }) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun LocalToolPickerButton(
    availableTools: List<LocalToolOption>,
    defaultEnabledTools: List<LocalToolOption>,
    state: ChatInputState,
) {
    var showDialog by remember { mutableStateOf(false) }
    val enabledCount = availableTools.count { state.isLocalToolEnabled(it, defaultEnabledTools) }
    ToggleSurface(
        checked = enabledCount > 0,
        onClick = { showDialog = true },
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp)
                .size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HugeIcons.Tools, contentDescription = "工具")
        }
    }

    if (showDialog) {
        BasicAlertDialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("工具", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "这里显示全部本地工具；助手设置里的工具只代表默认启用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    availableTools.forEach { option ->
                        MemorySwitchRow(
                            title = option.label(),
                            checked = state.isLocalToolEnabled(option, defaultEnabledTools),
                            enabled = true,
                            onCheckedChange = { enabled -> state.setLocalToolEnabled(option, enabled) },
                        )
                    }
                    Text(
                        "点击弹窗外即可关闭。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun LocalToolOption.label(): String = when (this) {
    LocalToolOption.JavascriptEngine -> "JavaScript"
    LocalToolOption.TimeInfo -> "时间信息"
    LocalToolOption.Clipboard -> "剪贴板"
    LocalToolOption.Tts -> "文字转语音"
    LocalToolOption.AskUser -> "询问用户"
    LocalToolOption.ScreenTime -> "屏幕使用时间"
    LocalToolOption.Calendar -> "日历"
    LocalToolOption.Alarm -> "系统闹钟"
    LocalToolOption.ImageGeneration -> "图片生成"
    LocalToolOption.Subagent -> "子代理"
    LocalToolOption.Notification -> "系统通知"
}

@Composable
private fun WorkspaceToolPickerButton(
    workspaceId: String,
    state: ChatInputState,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    var showDialog by remember { mutableStateOf(false) }
    var defaultEnabled by remember(workspaceId) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(workspaceId) {
        val overrides = workspaceRepository.getById(workspaceId)?.toolDefaultEnabledOverrides().orEmpty()
        defaultEnabled = WorkspaceToolNames
            .filter { resolveWorkspaceToolDefaultEnabled(it, overrides) }
            .toSet()
        state.updateWorkspaceToolDefaults(defaultEnabled)
    }
    val enabledCount = WorkspaceToolNames.count { state.isWorkspaceToolEnabled(it, defaultEnabled) }
    ToggleSurface(checked = enabledCount > 0, onClick = { showDialog = true }) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp).size(24.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(HugeIcons.Folder01, contentDescription = "工作区工具") }
    }
    if (showDialog) {
        BasicAlertDialog(onDismissRequest = { showDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("工作区工具", style = MaterialTheme.typography.titleLarge)
                    Text("工作区设置里的“默认开启”只决定初始勾选；这里可为当前聊天临时打开/关闭。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WorkspaceToolNames.forEach { toolName ->
                        MemorySwitchRow(
                            title = workspaceToolLabel(toolName),
                            checked = state.isWorkspaceToolEnabled(toolName, defaultEnabled),
                            enabled = true,
                            onCheckedChange = { state.setWorkspaceToolEnabled(toolName, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolPickerButton(
    settings: Settings,
    assistant: Assistant,
    state: ChatInputState,
) {
    val servers = settings.mcpServers.filter { it.commonOptions.enable && it.id in assistant.mcpServers }
    if (servers.isEmpty()) return
    val tools = servers.flatMap { server ->
        server.commonOptions.tools.map { tool ->
            Triple(mcpToolKey(server.id, tool.name), server.commonOptions.name, tool)
        }
    }
    if (tools.isEmpty()) return
    val availableKeys = tools.map { it.first }.toSet()
    val defaultEnabled = tools.filter { it.third.enable }.map { it.first }.toSet()
    var showDialog by remember { mutableStateOf(false) }
    val enabledCount = tools.count { state.isMcpToolEnabled(it.first, defaultEnabled) }
    ToggleSurface(checked = enabledCount > 0, onClick = { showDialog = true }) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp).size(24.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(HugeIcons.McpServer, contentDescription = "MCP 工具") }
    }
    if (showDialog) {
        BasicAlertDialog(onDismissRequest = { showDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("MCP 工具", style = MaterialTheme.typography.titleLarge)
                    Text("只显示当前助手绑定且服务器启用的 MCP。MCP 设置里的启用开关作为默认勾选，不是永久禁止。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tools.forEach { (key, serverName, tool) ->
                        MemorySwitchRow(
                            title = "${serverName.ifBlank { "MCP" }} / ${tool.name}",
                            checked = state.isMcpToolEnabled(key, defaultEnabled),
                            enabled = key in availableKeys,
                            onCheckedChange = { state.setMcpToolEnabled(key, it) },
                        )
                    }
                }
            }
        }
    }
}

private fun mcpToolKey(serverId: Uuid, toolName: String): String = "$serverId/$toolName"

private fun workspaceToolLabel(toolName: String): String = when (toolName) {
    "workspace_read_file" -> "读取文件"
    "workspace_write_file" -> "写入文件"
    "workspace_edit_file" -> "编辑文件"
    "workspace_apply_patch" -> "应用补丁"
    "workspace_list_backups" -> "查看备份"
    "workspace_restore_backup" -> "恢复备份"
    "workspace_backup" -> "文件备份"
    "workspace_shell" -> "Shell 命令"
    "workspace_grep" -> "搜索文件"
    "workspace_shell_background" -> "后台 Shell"
    "workspace_shell_session" -> "Shell 会话"
    else -> toolName
}

@Composable
private fun MemoryPickerButton(
    assistant: Assistant,
    options: MemoryOptions,
    onUpdate: (MemoryOptions) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val effective = options.effective(assistant)
    val checked = effective.referencesAny() || effective.editsAny()
    ToggleSurface(
        checked = checked,
        onClick = { showDialog = true },
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp)
                .size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HugeIcons.Brain02, contentDescription = "记忆")
        }
    }

    if (showDialog) {
        BasicAlertDialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("记忆", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "参考记忆只会把记忆提供给 AI；允许编辑记忆才会暴露记忆编辑工具。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MemorySwitchRow(
                        title = "参考助手记忆",
                        checked = effective.referenceAssistantMemory,
                        enabled = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdate(options.copy(referenceAssistantMemory = it))
                        },
                    )
                    MemorySwitchRow(
                        title = "允许编辑助手记忆",
                        checked = effective.allowEditAssistantMemory,
                        // 编辑与参考解耦：允许编辑只依赖总闸 enableMemory，关掉参考(自动注入)后仍可手动编辑
                        enabled = assistant.enableMemory,
                        onCheckedChange = { onUpdate(options.copy(allowEditAssistantMemory = it)) },
                    )
                    if (assistant.useGlobalMemory) {
                        MemorySwitchRow(
                            title = "参考全局记忆",
                            checked = effective.referenceGlobalMemory,
                            enabled = assistant.enableMemory,
                            onCheckedChange = {
                                onUpdate(options.copy(referenceGlobalMemory = it))
                            },
                        )
                        MemorySwitchRow(
                            title = "允许助手编辑全局记忆",
                            checked = effective.allowEditGlobalMemory,
                            // 编辑与参考解耦：允许编辑只依赖 enableMemory + useGlobalMemory
                            enabled = assistant.enableMemory,
                            onCheckedChange = { onUpdate(options.copy(allowEditGlobalMemory = it)) },
                        )
                    }
                    // 记忆图编辑权限（独立于 legacy 记忆，方案 2026-08-05）：开启记忆图后才暴露
                    if (assistant.enableMemoryGraph) {
                        MemorySwitchRow(
                            title = "允许 AI 编辑助手记忆图",
                            checked = effective.allowEditAssistantGraph,
                            enabled = true,
                            onCheckedChange = { onUpdate(options.copy(allowEditAssistantGraph = it)) },
                        )
                        if (assistant.useGlobalMemory) {
                            MemorySwitchRow(
                                title = "允许 AI 编辑全局记忆图",
                                checked = effective.allowEditGlobalGraph,
                                enabled = true,
                                onCheckedChange = { onUpdate(options.copy(allowEditGlobalGraph = it)) },
                            )
                        }
                    }
                    MemorySwitchRow(
                        title = stringResource(R.string.assistant_page_recent_chats),
                        checked = effective.referenceRecentChats == true,
                        enabled = true,
                        onCheckedChange = {
                            onUpdate(options.copy(referenceRecentChats = it))
                        },
                    )
                    Text(
                        "点击弹窗外即可关闭。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemorySwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.5f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(
                items = completionList.items,
                key = { item -> "${item.label}:${item.insertText}" },
            ) { item ->
                Surface(
                    onClick = { onItemClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = textContent.text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    textContent.edit {
        replace(start, end, item.insertText)
        selection = TextRange(start + item.insertText.length)
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 360.dp)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
