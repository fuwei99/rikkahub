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
import me.rerere.hugeicons.stroke.ArrowRight01
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
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findModelById
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
    // ---- 对话级能力覆盖回调（2026-08-18 重构；写入 Conversation 而非 Assistant）----
    /** 思维链档位；null = 恢复继承助手默认 */
    onUpdateReasoningLevel: (me.rerere.ai.core.ReasoningLevel?) -> Unit = {},
    /** 单个本地工具开关（含生图 / 子代理 / 信箱 / 发信） */
    onToggleLocalTool: (LocalToolOption, Boolean) -> Unit = { _, _ -> },
    /** 单个工作区工具开关；第三参为 workspace 配置的「默认开启」集合，供首次种子物化 */
    onToggleWorkspaceTool: (String, Boolean, Set<String>) -> Unit = { _, _, _ -> },
    /** 单个 MCP 工具开关（key = "serverId/toolName"）；第三参为默认启用集合 */
    onToggleMcpTool: (String, Boolean, Set<String>) -> Unit = { _, _, _ -> },
    /** 记忆选项（对话级持久） */
    onUpdateMemoryOptions: (MemoryOptions) -> Unit = {},
    onMoreClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    /**
     * 专注监督拦截原因（null = 不拦）。非空时发送键置灰并提示，
     * 避免用户敲完一大段才发现发不出去（2026-08-18 非白名单助手后门修复）。
     */
    supervisionBlockReason: String? = null,
    /** 记忆弹窗「记忆图」入口跳扩展面板（阶段二 §2.4） */
    graphEnabledCount: Int = 0,
    graphWritableCount: Int = 0,
    onOpenMemoryGraphs: () -> Unit = {},
) {
    val toaster = LocalToaster.current
    // 必须按**本对话**的 assistantId 解析，不能用「全局当前助手」：
    // 用户点进 agent 子对话插话时全局助手往往是别人，会拿错默认值（同 ChatVM 的处理）。
    val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
    // 本对话实际生效的本地工具集（对话级覆盖 ?? 助手默认），所有工具开关 UI 共用
    val effectiveLocalTools = conversation.effectiveLocalTools(assistant)
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
        if (supervisionBlockReason != null) {
            toaster.show(supervisionBlockReason, type = ToastType.Warning)
            return
        }
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        if (supervisionBlockReason != null) {
            toaster.show(supervisionBlockReason, type = ToastType.Warning)
            return
        }
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
                            // 与下方 Reasoning 按钮同口径：对话绑定 > 助手 > 全局。
                            // 原先用 getCurrentChatModel()（只看助手/全局，无视 conversation.modelId），
                            // 对话单独绑模型时这里会拿错模型，搜索能力判定跟着错（2026-08-18 修复）。
                            val chatModel = settings.findModelById(effectiveModelId)
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
                                // 生图工具开关走对话级；模型选择仍是全局设置（模型池是全局资源）
                                enabled = effectiveLocalTools.contains(LocalToolOption.ImageGeneration),
                                onToggleEnabled = { checked ->
                                    onToggleLocalTool(LocalToolOption.ImageGeneration, checked)
                                },
                                onSelectModels = onUpdateImageGenerationModels,
                            )

                            if (assistant.enableMemory) {
                                MemoryPickerButton(
                                    assistant = assistant,
                                    options = conversation.effectiveMemoryOptions(),
                                    onUpdate = onUpdateMemoryOptions,
                                    graphEnabledCount = graphEnabledCount,
                                    graphWritableCount = graphWritableCount,
                                    onOpenMemoryGraphs = onOpenMemoryGraphs,
                                )
                            }

                            LocalToolPickerButton(
                                availableTools = ChatInputState.CHAT_TOGGLEABLE_LOCAL_TOOLS,
                                enabledTools = effectiveLocalTools,
                                onToggle = onToggleLocalTool,
                            )

                            if (assistant.workspaceId != null) {
                                WorkspaceToolPickerButton(
                                    workspaceId = assistant.workspaceId.toString(),
                                    conversation = conversation,
                                    onToggle = onToggleWorkspaceTool,
                                )
                            }

                            McpToolPickerButton(
                                settings = settings,
                                assistant = assistant,
                                conversation = conversation,
                                onToggle = onToggleMcpTool,
                            )

                            // Reasoning：模型解析必须与 ChatVM.currentChatModel 同口径
                            // （对话绑定 > 助手 > 全局），否则对话单独绑了推理模型时按钮不显示，
                            // 或显示了却改到别的模型的档位上（2026-08-18 修复）。
                            val model = settings.findModelById(effectiveModelId)
                            if (model?.isReasoningEnabled == true) {
                                ReasoningButton(
                                    reasoningLevel = conversation.effectiveReasoningLevel(assistant),
                                    onUpdateReasoningLevel = { onUpdateReasoningLevel(it) },
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
                                        // 监督拦截时连「取消生成」也不需要（本就不该在生成）
                                        enabled = supervisionBlockReason == null &&
                                            (loading || !state.isEmpty()),
                                        onClick = {
                                            sendMessage()
                                        }, onLongClick = {
                                            sendMessageWithoutAnswer()
                                        }
                                    )
                            ) {
                                val containerColor = when {
                                    supervisionBlockReason != null -> MaterialTheme.colorScheme.surfaceContainerHigh
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
    enabledTools: List<LocalToolOption>,
    onToggle: (LocalToolOption, Boolean) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val enabledCount = availableTools.count { it in enabledTools }
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
                        "开关只作用于**当前对话**并永久保存（含跨端同步）；助手设置里的工具只是新对话的默认值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    availableTools.forEach { option ->
                        // 子代理开启时信箱必须保持开启（任务/指令/回报全走 inbox），锁定该行
                        val lockedBySubagent = option == LocalToolOption.Inbox &&
                            enabledTools.contains(LocalToolOption.Subagent)
                        MemorySwitchRow(
                            title = option.label(),
                            checked = option in enabledTools,
                            enabled = !lockedBySubagent,
                            onCheckedChange = { enabled -> onToggle(option, enabled) },
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
    LocalToolOption.Inbox -> "信箱工具"
    LocalToolOption.Send -> "发信工具"
}

@Composable
private fun WorkspaceToolPickerButton(
    workspaceId: String,
    conversation: Conversation,
    onToggle: (String, Boolean, Set<String>) -> Unit,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    var showDialog by remember { mutableStateOf(false) }
    var defaultEnabled by remember(workspaceId) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(workspaceId) {
        val overrides = workspaceRepository.getById(workspaceId)?.toolDefaultEnabledOverrides().orEmpty()
        defaultEnabled = WorkspaceToolNames
            .filter { resolveWorkspaceToolDefaultEnabled(it, overrides) }
            .toSet()
    }
    // 对话级覆盖 ?? workspace 配置的默认开启集合
    val enabled = conversation.workspaceTools ?: defaultEnabled
    val enabledCount = WorkspaceToolNames.count { it in enabled }
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
                    Text("工作区设置里的“默认开启”只决定新对话的初始勾选；这里的改动只作用于当前对话并永久保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WorkspaceToolNames.forEach { toolName ->
                        MemorySwitchRow(
                            title = workspaceToolLabel(toolName),
                            checked = toolName in enabled,
                            enabled = true,
                            onCheckedChange = { onToggle(toolName, it, defaultEnabled) },
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
    conversation: Conversation,
    onToggle: (String, Boolean, Set<String>) -> Unit,
) {
    val servers = settings.mcpServers.filter { it.commonOptions.enable && it.id in assistant.mcpServers }
    if (servers.isEmpty()) return
    val tools = servers.flatMap { server ->
        server.commonOptions.tools.map { tool ->
            Triple(mcpToolKey(server.id, tool.name), server.commonOptions.name, tool)
        }
    }
    if (tools.isEmpty()) return
    val defaultEnabled = tools.filter { it.third.enable }.map { it.first }.toSet()
    // 对话级覆盖 ?? MCP 设置里的 enable 集合
    val enabled = conversation.mcpTools ?: defaultEnabled
    var showDialog by remember { mutableStateOf(false) }
    val enabledCount = tools.count { it.first in enabled }
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
                            checked = key in enabled,
                            enabled = true,
                            onCheckedChange = { onToggle(key, it, defaultEnabled) },
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
    "workspace_codex_patch" -> "应用 Codex 补丁"
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
    graphEnabledCount: Int,
    graphWritableCount: Int,
    onOpenMemoryGraphs: () -> Unit,
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
                        title = "参考用户记忆",
                        checked = effective.referenceAssistantMemory,
                        enabled = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdate(options.copy(referenceAssistantMemory = it))
                        },
                    )
                    MemorySwitchRow(
                        title = "参考全局记忆",
                        checked = effective.referenceGlobalMemory,
                        enabled = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdate(options.copy(referenceGlobalMemory = it))
                        },
                    )
                    MemorySwitchRow(
                        title = "允许编辑用户记忆",
                        checked = effective.allowEditAssistantMemory,
                        // 编辑与参考解耦：允许编辑只依赖总闸 enableMemory，关掉参考(自动注入)后仍可手动编辑
                        enabled = assistant.enableMemory,
                        onCheckedChange = { onUpdate(options.copy(allowEditAssistantMemory = it)) },
                    )
                    MemorySwitchRow(
                        title = "允许编辑全局记忆",
                        checked = effective.allowEditGlobalMemory,
                        enabled = assistant.enableMemory,
                        onCheckedChange = { onUpdate(options.copy(allowEditGlobalMemory = it)) },
                    )
                    MemorySwitchRow(
                        title = stringResource(R.string.assistant_page_recent_chats),
                        checked = effective.referenceRecentChats == true,
                        enabled = true,
                        onCheckedChange = {
                            onUpdate(options.copy(referenceRecentChats = it))
                        },
                    )
                    // 多图体系：四行图开关收敛成一行入口 + 运行时总闸（阶段二 §2.4）。
                    // 持久化的 enabled/writable 绑定在扩展面板第 5 个 Tab 里改；
                    // graphMuted 是「本轮不使用记忆图」的临时意图，两者语义不同不能互相替代。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showDialog = false
                                onOpenMemoryGraphs()
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.memory_graph_binding_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(
                                    R.string.memory_graph_binding_summary,
                                    graphEnabledCount,
                                    graphWritableCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = stringResource(R.string.memory_graph_manage_title),
                        )
                    }
                    // 记忆图编辑总闸 + 管理开关（2026-08-12 用户需求）：
                    // - 允许编辑记忆图：总闸，关掉则 graph 侧 memory_tool 完全不暴露；
                    //   扩展面板里每张图的「可编辑」只决定具体哪张图可写（AND 关系）。
                    // - 允许 AI 管理记忆图：会话级覆盖，未设置时继承助手默认（原设置只决定默认值）。
                    MemorySwitchRow(
                        title = "允许编辑记忆图",
                        checked = effective.allowEditMemoryGraph,
                        enabled = true,
                        onCheckedChange = {
                            onUpdate(options.copy(allowEditMemoryGraph = it))
                        },
                    )
                    MemorySwitchRow(
                        title = "允许 AI 管理记忆图",
                        checked = effective.allowManageMemoryGraphs == true,
                        enabled = true,
                        onCheckedChange = {
                            onUpdate(options.copy(allowManageMemoryGraphs = it))
                        },
                    )
                    MemorySwitchRow(
                        title = stringResource(R.string.memory_graph_mute),
                        checked = options.graphMuted,
                        enabled = true,
                        onCheckedChange = {
                            onUpdate(options.copy(graphMuted = it))
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
