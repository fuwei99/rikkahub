package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.schedule.ScheduleAction
import me.rerere.rikkahub.data.model.MemoryGraphBinding
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.model.ResolvedGraphBinding
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.ai.memory.MemoryGraphBindingResolver
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.chat.memory.MemoryGraphDrawer
import me.rerere.rikkahub.ui.pages.chat.memory.parseMemoryInjectionNodeIds
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null, folderId: Uuid? = null, temporary: Boolean = false) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString(), folderId?.toString().orEmpty(), temporary)
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val conversationLockedNow by vm.conversationLockedNow.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val summaryStatus by vm.summaryStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(conversationLockedNow) {
        if (conversationLockedNow) {
            // 锁落地后不留在当前会话，直接回到默认新对话界面，避免从页面分支/继续发送。
            navigateToChatPage(navController, folderId = conversation.folderId)
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val parts = buildList {
                files.forEach { file ->
                    val type = filesManager.getFileMimeType(file)
                    val localFiles = if (type?.startsWith("image/") == true) {
                        filesManager.createChatImageFilesByContents(listOf(file), inputState.compressImages)
                    } else {
                        filesManager.createChatFilesByContents(listOf(file))
                    }
                    localFiles.forEach { localFile ->
                        if (type?.startsWith("image/") == true) {
                            add(UIMessagePart.Image(url = localFile.toString()))
                        } else if (type?.startsWith("video/") == true) {
                            add(UIMessagePart.Video(url = localFile.toString()))
                        } else if (type?.startsWith("audio/") == true) {
                            add(UIMessagePart.Audio(url = localFile.toString()))
                        }
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    summaryStatus = summaryStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    summaryStatus = summaryStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    summaryStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    // 受保护的定时任务会话（监督查岗）：停止 / 分支 / 重 roll / 删改 一律不给点
    val scheduleProtection by vm.scheduleProtection.collectAsStateWithLifecycle()
    val compressErrorTitle = stringResource(R.string.error_title_compress_conversation)
    val summaryError = remember(errors, conversation.id, compressErrorTitle) {
        errors.lastOrNull {
            it.title == compressErrorTitle &&
                (it.conversationId == null || it.conversationId == conversation.id)
        }
    }
    if (summaryError != null) {
        AlertDialog(
            onDismissRequest = { onDismissError(summaryError.id) },
            title = { Text(compressErrorTitle) },
            text = {
                Text(summaryError.error.message ?: summaryError.error.toString())
            },
            confirmButton = {
                TextButton(onClick = { onDismissError(summaryError.id) }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    var showFilesSheet by remember { mutableStateOf(false) }
    // 打开扩展面板时定位的 Tab：从 ChatInput 记忆弹窗的「记忆图」入口进来时定位到第 5 个 Tab
    var extensionInitialTab by remember { mutableStateOf(0) }
    // 记忆图抽屉：null = 关闭；非 null = 展示该条消息触发的节点（顶部按钮取最近一条有注入的消息）
    var memoryGraphTrace by remember { mutableStateOf<Map<String, Set<Long>>?>(null) }
    // 记忆选项：对话级持久覆盖 ?? 默认值，再按助手能力 effective（2026-08-18 重构）
    val memoryOptions = conversation.effectiveMemoryOptions().effective(assistant)
    val memoryGraphRegistry: MemoryGraphRegistry = koinInject()
    val memoryGraphBindingResolver: MemoryGraphBindingResolver = koinInject()

    // 多图体系（阶段二）：Resolver 是唯一真源，UI 只读它的输出。
    // - enabledGraphs：本轮实际参与注入的图（抽屉 Tab，受 graphMuted 影响）；
    // - panelGraphBindings：持久绑定（不受本轮 graphMuted 影响，扩展面板开关显示用）；
    // - allMemoryGraphs：注册表全量图（扩展面板列表）。
    var enabledGraphs by remember { mutableStateOf<List<MemoryGraphMeta>>(emptyList()) }
    var panelGraphBindings by remember { mutableStateOf<List<ResolvedGraphBinding>>(emptyList()) }
    var allMemoryGraphs by remember { mutableStateOf<List<MemoryGraphMeta>>(emptyList()) }
    // key 只收窄到 resolve 真正读的字段：conversation 整体在流式回复期间每个 token 都是新实例，
    // 以它为 key 会让本 effect 每 token 取消重启一次，进而把下面三个 state 打回空 —— 记忆图按钮闪、
    // 抽屉整棵子树被拆掉重建、GraphVisualizer 位置全丢后按冷启动高温重新散布 = 大范围弹跳。
    LaunchedEffect(
        assistant.id,
        assistant.name,
        assistant.memoryGraphBindings,
        assistant.allowConversationPromptInjection,
        conversation.memoryGraphBindings,
        memoryOptions,
    ) {
        // 先解析绑定再读图列表：resolve 会顺手把内置助手图名字同步成助手名，
        // 列表先读会拿到改名前的快照（一排「助手记忆图」分不清谁是谁）
        // 三个结果先算到局部变量、全部成功后再一次性提交：
        // 失败或协程取消（CancellationException 会被 runCatching 一并吞掉）时保留旧值，绝不清空 UI。
        val panel = try {
            memoryGraphBindingResolver.resolve(
                assistant = assistant,
                conversation = conversation,
                options = MemoryOptions(graphMuted = false),
                maxEnabledGraphs = Int.MAX_VALUE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ChatPage", "resolve graph bindings failed", e)
            return@LaunchedEffect
        }
        val enabled = try {
            memoryGraphBindingResolver.enabledGraphs(assistant, conversation, memoryOptions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ChatPage", "resolve enabled graphs failed", e)
            return@LaunchedEffect
        }
        val all = try {
            memoryGraphRegistry.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ChatPage", "list memory graphs failed", e)
            return@LaunchedEffect
        }
        panelGraphBindings = panel
        enabledGraphs = enabled
        allMemoryGraphs = all
    }
    val memoryGraphEnabled = enabledGraphs.isNotEmpty()
    val graphEnabledCount = panelGraphBindings.count { it.enabled }
    val graphWritableCount = panelGraphBindings.count { it.writable }

    // 2026-08-23：workspace 是纯对话级两态字段，路径补全直接读 conversation.workspaceId。
    val mountedWorkspaceId = conversation.workspaceId
    val completionProviders = remember(mountedWorkspaceId, conversation.workspaceCwd, workspaceRepository) {
        mountedWorkspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController, folderId = conversation.folderId)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    enableMemoryGraph = memoryGraphEnabled,
                    onOpenMemoryGraph = {
                        // 顶部按钮展示整个当前会话中注入过的记忆节点，按 scope 合并并去重。
                        memoryGraphTrace = conversation.currentMessages
                            .asSequence()
                            .map { parseMemoryInjectionNodeIds(it.memoryInjection) }
                            .flatMap { it.entries }
                            .groupBy({ it.key }, { it.value })
                            .mapValues { (_, nodeSets) -> nodeSets.flatten().toSet() }
                    },
                )
            },
            bottomBar = {
                Column {
                    // 同步合并提示：仅真分叉另存分支时出现，不再有锁拦截
                    val mergeNotice by vm.mergeNotice.collectAsStateWithLifecycle()
                    SyncMergeBanner(
                        notice = mergeNotice,
                        onDismiss = { vm.dismissMergeNotice() },
                    )
                    // 本对话是某个 agent 的工作对话时提示观察态 + 回主对话入口（非 agent 对话不渲染）
                    AgentObserveBanner(conversationId = conversation.id)
                    val supervisionBlockReason by vm.supervisionBlockReason.collectAsStateWithLifecycle()
                    ChatInput(
                        state = inputState,
                        loading = loadingJob != null,
                        supervisionBlockReason = supervisionBlockReason,
                    settings = setting,
                    conversation = conversation,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        // 受保护的定时任务不许手动掐断（硬拦截在 ChatService，这里只是提前告知）
                        val protection = scheduleProtection
                        val reason = protection?.reasonFor(ScheduleAction.CANCEL)
                        if (reason != null) {
                            toaster.show(reason, type = ToastType.Warning)
                        } else {
                            vm.stopGeneration()
                        }
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        // 2026-08-18 重构：写对话而不是助手，避免改一处影响该助手所有对话
                        vm.setWebSearchEnabled(!enableWebSearch)
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(model = it)
                    },
                    onUpdateImageGenerationModels = { modelIds ->
                        val primaryImageModelId = setting.imageGenerationModelId
                            .takeIf { it in modelIds }
                            ?: modelIds.firstOrNull()
                            ?: setting.imageGenerationModelId
                        vm.updateSettings(
                            setting.copy(
                                imageGenerationModelId = primaryImageModelId,
                                imageGenerationModelIds = modelIds,
                            )
                        )
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    // ---- 对话级能力覆盖（2026-08-18 重构）----
                    onUpdateReasoningLevel = { vm.setReasoningLevel(it) },
                    onToggleLocalTool = { option, checked -> vm.toggleLocalTool(option, checked) },
                    onToggleWorkspaceTool = { name, checked, defaults ->
                        vm.toggleWorkspaceTool(name, checked, defaults)
                    },
                    onToggleMcpTool = { key, checked, defaults ->
                        vm.toggleMcpTool(key, checked, defaults)
                    },
                    onUpdateMemoryOptions = { vm.setMemoryOptions(it) },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                    graphEnabledCount = graphEnabledCount,
                    graphWritableCount = graphWritableCount,
                    onOpenMemoryGraphs = {
                        // 记忆弹窗「记忆图」入口：关闭弹窗并打开扩展面板第 5 个 Tab
                        extensionInitialTab = 4
                        showFilesSheet = true
                    },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                summaryStatus = summaryStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    val reason = scheduleProtection?.reasonFor(ScheduleAction.REGENERATE)
                    if (reason != null) toaster.show(reason, type = ToastType.Warning)
                    else vm.regenerateAtMessage(it)
                },
                onEdit = {
                    val reason = scheduleProtection?.reasonFor(ScheduleAction.EDIT_MESSAGE)
                    if (reason == null) {
                        inputState.editingMessage = it.id
                        inputState.setContents(it.parts)
                    } else {
                        toaster.show(reason, type = ToastType.Warning)
                    }
                },
                onForkMessage = {
                    val reason = scheduleProtection?.reasonFor(ScheduleAction.FORK)
                    if (reason == null) {
                        scope.launch {
                            vm.forkMessage(message = it)?.let { fork ->
                                navigateToChatPage(navController, chatId = fork.id)
                            }
                        }
                    } else {
                        toaster.show(reason, type = ToastType.Warning)
                    }
                },
                onDelete = {
                    val reason = scheduleProtection?.reasonFor(ScheduleAction.DELETE_MESSAGE)
                    if (reason != null) {
                        toaster.show(reason, type = ToastType.Warning)
                    } else if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
                onOpenMemoryGraph = if (memoryGraphEnabled) {
                    { message -> memoryGraphTrace = parseMemoryInjectionNodeIds(message.memoryInjection) }
                } else {
                    null
                },
                onInsertSummary = { message, templateId, prompt, tokens ->
                    vm.summarizeAtMessage(message, templateId, prompt, tokens)
                    Unit
                },
                onEditSummary = { message, title, content ->
                    vm.updateSummaryMessage(message, title, content)
                },
                onRegenerateSummary = { boundaryId, templateId, prompt, tokens ->
                    vm.summarizeAtBoundary(boundaryId, templateId, prompt, tokens)
                },
                onSelectSummaryVersion = { nodeId, index ->
                    vm.selectSummaryVersion(nodeId, index)
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
                extensionInitialTab = extensionInitialTab,
                memoryGraphs = allMemoryGraphs,
                memoryGraphBindings = panelGraphBindings,
            )
        }

        // 抽屉常驻 composition，只用 visible 控制显隐：
        // 若用 if 包裹，enabledGraphs 任何一次瞬时抖动都会把抽屉子树整棵移除，
        // GraphVisualizer 的 nodePositions/scale/offset 随之丢失并冷启动重排。
        MemoryGraphDrawer(
            visible = memoryGraphEnabled && memoryGraphTrace != null,
            graphs = enabledGraphs,
            trace = memoryGraphTrace.orEmpty(),
            conversationHasNoTrace = memoryGraphTrace?.isEmpty() == true,
            onDismissRequest = { memoryGraphTrace = null },
        )
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
    /** 打开扩展面板时定位到的 Tab（记忆图入口用 4） */
    extensionInitialTab: Int = 0,
    memoryGraphs: List<MemoryGraphMeta> = emptyList(),
    memoryGraphBindings: List<ResolvedGraphBinding> = emptyList(),
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    /**
     * 记忆图开关写回（阶段二 §2.1，2026-08-11 修「全关又复活」）：
     * - `allowConversationPromptInjection` 打开 → 写会话绑定，首次以当前生效绑定做种子物化（review2 §二.B），
     *   避免首开开关瞬间把其它已启用图清空；
     * - 否则 → 写助手绑定。
     *
     * **不再丢弃全 false 的 binding**：助手侧 `memoryGraphBindings` 的 `emptyList()` 语义是
     * 「未设置 → 走老字段推导」，一旦最后一张图关掉导致列表变空，legacy 分支会把
     * `enableMemoryGraph` 这些老字段接管，两张图当场复活。所以显式保留
     * `MemoryGraphBinding(id, false, false)` 让列表永远非空，同时把老字段一次性置 false 兜底
     * （PreferencesStore 会过滤掉指向已删图的 binding，过滤后变空同样会退回 legacy）。
     */
    fun updateGraphBinding(graphId: String, enabled: Boolean, writable: Boolean) {
        val useConversation = assistant.allowConversationPromptInjection
        // 种子 = 当前生效的持久绑定（resolver 输出，含老字段推导），未显式绑定过时用它物化
        val seed = memoryGraphBindings.map { MemoryGraphBinding(it.meta.id, it.enabled, it.writable) }
        val base = if (useConversation) {
            conversation.memoryGraphBindings ?: seed
        } else {
            assistant.memoryGraphBindings.ifEmpty { seed }
        }
        val next = base.filter { it.graphId != graphId } +
            MemoryGraphBinding(graphId, enabled, writable)
        if (useConversation) {
            vm.updateConversation(conversation.copy(memoryGraphBindings = next))
            vm.saveConversationAsync()
        } else {
            vm.updateSettings(
                setting.copy(
                    assistants = setting.assistants.map { a ->
                        if (a.id == assistant.id) {
                            a.copy(
                                memoryGraphBindings = next,
                                // 收敛老字段，之后 legacy 推导分支永不再触发
                                enableMemoryGraph = false,
                                enableAssistantMemoryGraph = false,
                                enableGlobalMemoryGraph = false,
                            )
                        } else a
                    }
                )
            )
        }
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatImageFilesByContents(listOf(croppedUri), inputState.compressImages))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatImageFilesByContents(listOf(cameraOutputUri!!), inputState.compressImages))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatImageFilesByContents(listOf(croppedUri), inputState.compressImages))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatImageFilesByContents(selectedUris, inputState.compressImages))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatImageFilesByContents(selectedUris, inputState.compressImages))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    context.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            context.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("添加 URL") },
            text = {
                Column {
                    Text("图片 URL 会作为图片附件发送；其他 URL 会插入为文本。")
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = urlInput.trim()
                        if (!url.isHttpUrl()) {
                            toaster.show("请输入 http(s) URL", type = ToastType.Error)
                            return@TextButton
                        }
                        if (url.isImageUrl()) {
                            inputState.addImageUrl(url)
                            filesManager.trackRemoteUrl(FileFolders.UPLOAD, url, mimeType = "image/url")
                        } else {
                            inputState.appendText(if (inputState.textContent.text.isBlank()) url else "\n$url")
                        }
                        urlInput = ""
                        showUrlDialog = false
                        dismissAll()
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { templateId, additionalPrompt, targetTokens, keepRecent ->
                vm.summarizeToEnd(templateId, additionalPrompt, targetTokens, keepRecent)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            onAutoCompressOverrideChange = { vm.updateAutoCompressOverride(it) },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            // ---- 对话级能力覆盖（2026-08-18 重构）----
            onToggleSkill = { name, checked -> vm.toggleSkill(name, checked) },
            onToggleLocalTool = { option, checked -> vm.toggleLocalTool(option, checked) },
            onToggleMcpServer = { serverId, enabled ->
                vm.toggleMcpServer(serverId, enabled)
            },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            onAddUrl = { showUrlDialog = true },
            initialExtensionTab = extensionInitialTab,
            memoryGraphs = memoryGraphs,
            memoryGraphBindings = memoryGraphBindings,
            onMemoryGraphBindingChange = { graphId, enabled, writable ->
                updateGraphBinding(graphId, enabled, writable)
            },
        )
    }
}

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun String.isImageUrl(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".png") ||
        clean.endsWith(".jpg") ||
        clean.endsWith(".jpeg") ||
        clean.endsWith(".webp") ||
        clean.endsWith(".gif") ||
        clean.endsWith(".bmp") ||
        clean.endsWith(".svg")
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    enableMemoryGraph: Boolean = false,
    onOpenMemoryGraph: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = conversation.modelId?.let { settings.findModelById(it) }
                        ?: assistant.chatModelId?.let { settings.findModelById(it) }
                        ?: settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = if (conversation.isTemporary) {
                            stringResource(R.string.chat_page_temporary_badge)
                        } else {
                            conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) }
                        },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (conversation.isTemporary) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            LocalContentColor.current
                        },
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            if (enableMemoryGraph) {
                IconButton(onClick = onOpenMemoryGraph) {
                    Icon(
                        painter = painterResource(R.drawable.ic_memory_network),
                        contentDescription = stringResource(R.string.memory_graph_trace_open),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

/**
 * 同步合并提示横幅。
 *
 * 取代原来的会话互斥锁三态横幅：不再有"对话被占用"的拦截与强制接管，
 * 只在云端真分叉、本地另存了一份分支后做一次事后告知。
 */
@Composable
private fun SyncMergeBanner(
    notice: ChatService.MergeNotice?,
    onDismiss: () -> Unit,
) {
    if (notice == null) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_sync_merge_branch, notice.branchTitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_lock_dismiss))
            }
        }
    }
}
