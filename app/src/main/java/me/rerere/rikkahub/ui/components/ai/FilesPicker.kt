package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Modality
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Mail02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.AutoCompressOverride
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_TEMPLATES
import me.rerere.rikkahub.data.ai.prompts.mergeOverride
import me.rerere.rikkahub.data.ai.prompts.normalizedAgainst
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.model.ResolvedGraphBinding
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (templateId: Uuid, additionalPrompt: String, targetTokens: Int, keepRecent: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    /**
     * 对话级自动压缩覆盖写入口（2026-08-21）：不能复用 [onUpdateConversation]，
     * 后者是整条 Conversation 快照回写，生成中会吞掉新消息；这里只改 override 一项。
     */
    onAutoCompressOverrideChange: (AutoCompressOverride?) -> Unit = {},
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onAddUrl: () -> Unit,
    /** 打开扩展面板时定位到的 Tab（记忆图入口用 4） */
    initialExtensionTab: Int = 0,
    memoryGraphs: List<MemoryGraphMeta> = emptyList(),
    memoryGraphBindings: List<ResolvedGraphBinding> = emptyList(),
    onMemoryGraphBindingChange: (graphId: String, enabled: Boolean, writable: Boolean) -> Unit = { _, _, _ -> },
    /** 对话级 skill 开关（2026-08-18 重构） */
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    /** 对话级本地工具开关（子代理 / 信箱 等，2026-08-18 重构） */
    onToggleLocalTool: (LocalToolOption, Boolean) -> Unit = { _, _ -> },
    /** 对话级 MCP server 挂载开关（2026-08-21 下沉：不再写助手） */
    onToggleMcpServer: (Uuid, Boolean) -> Unit = { _, _ -> },
) {
    val settings = LocalSettings.current
    val currentModel = settings.getCurrentChatModel()
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val filesManager: FilesManager = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    var showRikkaHubFiles by remember { mutableStateOf(false) }
    var showAutoCompressSheet by remember { mutableStateOf(false) }
    // 本对话实际生效的本地工具集（对话级覆盖 ?? 助手默认）
    val effectiveLocalTools = conversation.effectiveLocalTools(assistant)
    val mcpMountsLockedBySupervision = settings.supervision.let { supervision ->
        supervision.isActiveNow() &&
            supervision.allowedAssistantIds.isNotEmpty() &&
            assistant.id in supervision.allowedAssistantIds &&
            (supervision.lockMcpServers || supervision.lockMcpToolToggles)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TakePicButton(onLaunchCamera = onTakePic)

            ImagePickButton(onClick = onPickImage)

            if (currentModel?.inputModalities?.contains(Modality.VIDEO) == true) {
                VideoPickButton(onClick = onPickVideo)
            }

            if (currentModel?.inputModalities?.contains(Modality.AUDIO) == true) {
                AudioPickButton(onClick = onPickAudio)
            }

            FilePickButton(onClick = onPickFile)

            RikkaHubFilePickButton(onClick = { showRikkaHubFiles = true })

            UrlPickButton(onClick = onAddUrl)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(24.dp))
                .clickable { state.compressImages = !state.compressImages }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.compressImages,
                onClick = { state.compressImages = !state.compressImages },
            )
            Text("压缩")
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth()
        )

        if (workspaces.isNotEmpty()) {
            WorkspacePickerListItem(
                assistant = assistant,
                conversation = conversation,
                workspaces = workspaces,
                onUpdateConversation = onUpdateConversation,
                onNavigateToDetail = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceDetail(id))
                },
                onNavigateToTerminal = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceTerminal(id))
                },
                onNavigateToManage = {
                    onDismiss()
                    navController.navigate(Screen.Workspaces)
                },
            )
        }

        SubagentPickerListItem(
            enabled = effectiveLocalTools.contains(LocalToolOption.Subagent),
            onToggle = { checked -> onToggleLocalTool(LocalToolOption.Subagent, checked) },
        )

        InboxPickerListItem(
            subagentOn = effectiveLocalTools.contains(LocalToolOption.Subagent),
            // Send 为合并前旧开关（2026-08-20 并入信箱工具），兼容旧数据
            enabled = effectiveLocalTools.contains(LocalToolOption.Inbox) ||
                effectiveLocalTools.contains(LocalToolOption.Send),
            onToggle = { checked -> onToggleLocalTool(LocalToolOption.Inbox, checked) },
        )

        if (settings.mcpServers.isNotEmpty()) {
            McpPickerListItem(
                // 挂载集合：对话级覆盖 ?? 助手默认（2026-08-21 下沉）
                mountedServers = conversation.effectiveMcpServers(assistant),
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                locked = mcpMountsLockedBySupervision,
                onToggleServer = onToggleMcpServer,
            )
        }

        // Extensions (Quick Messages + Prompt Injections + Skills)
        val modeAndLorebookCount =
            if (assistant.allowConversationPromptInjection) {
                conversation.modeInjectionIds.size + conversation.lorebookIds.size
            } else {
                assistant.modeInjectionIds.size + assistant.lorebookIds.size
            }
        val activeCount =
            assistant.quickMessageIds.size +
                modeAndLorebookCount +
                // 角标要反映**本对话**实际生效的 skill 数（对话级覆盖 ?? 助手默认）
                conversation.effectiveSkills(assistant).size +
                memoryGraphBindings.count { it.enabled }
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package,
                    contentDescription = stringResource(R.string.assistant_page_tab_extensions),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.assistant_page_tab_extensions))
            },
            trailingContent = {
                if (activeCount > 0) {
                    Text(
                        text = activeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowInjectionSheetChange(true)
                },
        )

        // Compress History Button
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = stringResource(R.string.chat_page_compress_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_compress_context))
            },
            trailingContent = {
                if (conversation.messageNodes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowCompressDialogChange(true)
                },
        )

        // 自动压缩（对话级）：助手设置只给默认值，这里按对话覆盖，聊天里随手能开关/调参
        val effectiveAuto = remember(assistant.autoCompress, conversation.autoCompressOverride) {
            assistant.autoCompress.mergeOverride(conversation.autoCompressOverride)
        }
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Codesandbox,
                    contentDescription = "自动压缩",
                )
            },
            headlineContent = { Text("自动压缩") },
            supportingContent = {
                Text(
                    text = buildString {
                        append(if (effectiveAuto.enabled) "已开" else "已关")
                        if (effectiveAuto.enabled) {
                            if (effectiveAuto.tokenLimitEnabled) {
                                append(" · ${effectiveAuto.tokenThreshold} token→留 ${effectiveAuto.tokenKeep}")
                            }
                            if (effectiveAuto.countLimitEnabled) {
                                append(" · ${effectiveAuto.countThreshold} 条→留 ${effectiveAuto.countKeep}")
                            }
                            if (!effectiveAuto.tokenLimitEnabled && !effectiveAuto.countLimitEnabled) {
                                append(" · 未设阈值，点此配置")
                            }
                        }
                        if (conversation.autoCompressOverride != null) append(" · 本对话自定义")
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Switch(
                    checked = effectiveAuto.enabled,
                    onCheckedChange = { checked ->
                        val base = conversation.autoCompressOverride ?: AutoCompressOverride()
                        // 拨回助手默认值时自动清掉覆盖（normalizedAgainst），不留死覆盖
                        val next = base.copy(enabled = checked)
                            .normalizedAgainst(assistant.autoCompress)
                        onAutoCompressOverrideChange(next)
                    },
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable { showAutoCompressSheet = true },
        )

        // Workspace CWD
        // 2026-08-22：workspace 挂载下沉到对话级，CWD 跟着「本对话生效的 workspace」走，
        // 不能再读 assistant.workspaceId —— 那只是新对话默认值。
        val boundWorkspace = remember(workspaces, conversation.workspaceId, assistant.workspaceId) {
            val effectiveId = conversation.workspaceId ?: assistant.workspaceId
            workspaces.find { it.id == effectiveId?.toString() }
        }
        if (boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name) {
            var showCwdSheet by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showCwdSheet = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Folder01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = conversation.workspaceCwd ?: "/workspace",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showCwdSheet) {
                WorkspaceCwdPickerSheet(
                    workspaceId = boundWorkspace.id,
                    currentCwd = conversation.workspaceCwd,
                    onSelectCwd = { newCwd ->
                        onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                    },
                    onDismiss = { showCwdSheet = false },
                )
            }
        }
    }

    if (showRikkaHubFiles) {
        RikkaHubFilesSheet(
            filesManager = filesManager,
            onSelect = { file ->
                state.addParts(listOf(file.toMessagePart(filesManager)))
                showRikkaHubFiles = false
                onDismiss()
            },
            onDismiss = { showRikkaHubFiles = false },
        )
    }

    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { onShowInjectionSheetChange(false) },
            onDismissAll = onDismiss,
            initialTab = initialExtensionTab,
            onToggleSkill = onToggleSkill,
            memoryGraphs = memoryGraphs,
            memoryGraphBindings = memoryGraphBindings,
            onMemoryGraphBindingChange = onMemoryGraphBindingChange,
        )
    }

    // Compress Context Dialog（方案 2026-08-08 重构：模板 + 目标字数 + 附加提示词，分界 = 最新消息）
    if (showCompressDialog) {
        CompressContextDialog(
            templates = settings.compressTemplates,
            defaultTemplateId = settings.defaultCompressTemplateId,
            boundaryHint = stringResource(R.string.chat_page_compress_history_hint),
            // 整段压缩入口：可指定保留最近多少条不进总结
            keepRecentDefault = 0,
            onDismiss = {
                onShowCompressDialogChange(false)
                onDismiss()
            },
            onConfirm = { templateId, additionalPrompt, targetTokens, keepRecent ->
                onCompressContext(templateId, additionalPrompt, targetTokens, keepRecent)
            }
        )
    }

    // 自动压缩（本对话覆盖；助手那份只是默认值）
    if (showAutoCompressSheet) {
        AutoCompressQuickSheet(
            assistantSetting = assistant.autoCompress,
            override = conversation.autoCompressOverride,
            templates = settings.compressTemplates.ifEmpty { DEFAULT_COMPRESS_TEMPLATES },
            onOverrideChange = { next ->
                onAutoCompressOverrideChange(next)
            },
            onDismiss = { showAutoCompressSheet = false },
        )
    }
}

@Composable
private fun SubagentPickerListItem(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Package01,
                contentDescription = "子代理",
            )
        },
        headlineContent = { Text("子代理") },
        supportingContent = {
            Text(
                text = if (enabled) "已启用：允许 AI 派生独立上下文执行子任务" else "未启用：点击开启子代理",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Switch(
                checked = enabled,
                // 子代理依赖收件箱收任务/指令/回报，开启时隐含拉起信箱 —— 由 ChatVM.toggleLocalTool 统一处理
                onCheckedChange = onToggle,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.clip(MaterialTheme.shapes.large),
    )
}

@Composable
private fun InboxPickerListItem(
    subagentOn: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    // 子代理开启时信箱工具必须保持开启（任务/指令/回报全走 inbox），开关锁定
    val checked = enabled || subagentOn
    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Mail02,
                contentDescription = "信箱工具",
            )
        },
        headlineContent = { Text("信箱工具") },
        supportingContent = {
            Text(
                text = when {
                    subagentOn -> "已启用：子代理开启时信箱工具必须保持开启"
                    checked -> "已启用：AI 可查收收件箱并向其他对话发信"
                    else -> "未启用：点击开启信箱工具"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = !subagentOn,
                onCheckedChange = onToggle,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.clip(MaterialTheme.shapes.large),
    )
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    conversation: Conversation,
    workspaces: List<WorkspaceEntity>,
    onUpdateConversation: (Conversation) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    // 2026-08-22：workspace 挂载从 assistant 下沉到 conversation。
    // 2026-08-23：补「明确不挂」第三态（WORKSPACE_ID_UNBOUND 哨兵），助手绑了默认工作区时
    // 对话也能单独解绑。effectiveWorkspaceId() 已把哨兵规整成 null，UI 展示/工具装配直接用它。
    val effectiveWorkspaceId = conversation.effectiveWorkspaceId(assistant)
    val boundWorkspace = remember(workspaces, conversation.workspaceId, assistant.workspaceId) {
        workspaces.find { it.id == effectiveWorkspaceId?.toString() }
    }
    // sheet 勾选项要区分三态：null=继承助手（助手没绑时视觉等同未绑定，但勾「未绑定」要写哨兵），
    // 哨兵=明确不挂，其他 Uuid=显式绑定。selectedWorkspaceId 只在「明确不挂」和「显式绑定」时非继承。
    val sheetSelection: String? = when (conversation.workspaceId) {
        null -> null // 未设置/继承
        Conversation.WORKSPACE_ID_UNBOUND -> Conversation.WORKSPACE_ID_UNBOUND_STR // 明确不挂
        else -> conversation.workspaceId.toString()
    }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = stringResource(R.string.assistant_page_workspace),
            )
        },
        headlineContent = {
            Text(stringResource(R.string.assistant_page_workspace))
        },
        supportingContent = {
            Text(
                // 未设置（继承助手默认）但助手也没绑 → 显示「未绑定」；
                // 对话显式选了「不绑定」也是 null，UI 上与继承到 null 视觉一致
                // （语义差异靠下面 onSelect 的写库策略保证，不污染助手默认）。
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { showSheet = true },
    )

    if (showSheet) {
        WorkspaceSelectSheet(
            selectedWorkspaceId = sheetSelection,
            workspaces = workspaces,
            onSelect = { selectedId ->
                // 「不绑定」回调 null → 写哨兵（明确不挂，阻断继承助手默认）；
                // 选具体 workspace 回调其 id；切走时一并清掉对话级 cwd。
                val newId = selectedId?.let { Uuid.parse(it) }
                    ?: Conversation.WORKSPACE_ID_UNBOUND
                if (newId != conversation.workspaceId) {
                    onUpdateConversation(
                        conversation.copy(
                            workspaceId = newId,
                            workspaceCwd = null,
                        )
                    )
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun InjectionQuickConfigSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
    initialTab: Int = 0,
    memoryGraphs: List<MemoryGraphMeta> = emptyList(),
    memoryGraphBindings: List<ResolvedGraphBinding> = emptyList(),
    onMemoryGraphBindingChange: (graphId: String, enabled: Boolean, writable: Boolean) -> Unit = { _, _, _ -> },
    /** 对话级 skill 开关（2026-08-18 重构） */
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val navController = LocalNavController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                conversation = conversation,
                onUpdateConversation = onUpdateConversation,
                modifier = Modifier.weight(1f),
                initialTab = initialTab,
                memoryGraphs = memoryGraphs,
                memoryGraphBindings = memoryGraphBindings,
                onMemoryGraphBindingChange = onMemoryGraphBindingChange,
                // Skills 走对话级持久覆盖（2026-08-18 重构）
                onToggleConversationSkill = onToggleSkill,
                effectiveSkills = conversation.effectiveSkills(assistant),
                onNavigateToQuickMessages = {
                    onDismissAll()
                    navController.navigate(Screen.QuickMessages)
                },
                onNavigateToPrompts = {
                    onDismissAll()
                    navController.navigate(Screen.Prompts)
                },
                onNavigateToSkills = {
                    onDismissAll()
                    navController.navigate(Screen.Skills)
                },
                onNavigateToMemoryGraphs = {
                    onDismissAll()
                    navController.navigate(Screen.MemoryGraphList)
                })

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RikkaHubFilesSheet(
    filesManager: FilesManager,
    onSelect: (ManagedFileEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    // 聊天上传与 AI 工具读取（ai_read_image）都可复用，顶部分类切换
    val pickerFolders = remember { listOf(FileFolders.UPLOAD, FileFolders.AI_READ_IMAGES) }
    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    val files by remember(selectedFolder) { filesManager.observe(selectedFolder) }
        .collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("RikkaHub 文件", style = MaterialTheme.typography.titleLarge)
            Text(
                "复用已在 RikkaHub 登记的文件（聊天上传 / AI 读取）；不会重新导入，也不会因当前“压缩”开关再次压缩。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pickerFolders.forEach { folder ->
                    FilterChip(
                        selected = selectedFolder == folder,
                        onClick = { selectedFolder = folder },
                        label = {
                            Text(
                                when (folder) {
                                    FileFolders.AI_READ_IMAGES -> stringResource(R.string.setting_files_page_folder_ai_read_images)
                                    else -> stringResource(R.string.setting_files_page_folder_upload)
                                }
                            )
                        },
                    )
                }
            }
            if (files.isEmpty()) {
                Text("暂无文件", modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(files, key = { it.id }) { file ->
                        val local = filesManager.getFile(file).isFile
                        val cloud = file.hasCloudCopy()
                        ListItem(
                            leadingContent = { RikkaHubFileThumbnail(file = file, filesManager = filesManager) },
                            headlineContent = { Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        file.mimeType,
                                        if (local && cloud) "本地 + 云端" else if (cloud) "云端" else if (local) "本地" else "不可用"
                                    ).joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.large)
                                .clickable(enabled = local || cloud) { onSelect(file) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun ManagedFileEntity.toMessagePart(filesManager: FilesManager): UIMessagePart {
    val url = AssetUri.fromId(id)
    return when {
        mimeType.startsWith("image/") || mimeType == "image/url" -> UIMessagePart.Image(url)
        mimeType.startsWith("video/") -> UIMessagePart.Video(url)
        mimeType.startsWith("audio/") -> UIMessagePart.Audio(url)
        else -> UIMessagePart.Document(url = url, fileName = displayName, mime = mimeType)
    }
}

@Composable
private fun RikkaHubFileThumbnail(file: ManagedFileEntity, filesManager: FilesManager) {
    val local = filesManager.getFile(file)
    val model = when {
        local.isFile -> local
        file.hasCloudCopy() -> "r2://${file.r2Acct}/${file.r2Key}"
        file.relativePath.startsWith("http://", true) || file.relativePath.startsWith("https://", true) -> file.relativePath
        else -> null
    }
    if (file.mimeType.startsWith("image/") && model != null) {
        AsyncImage(
            model = model,
            contentDescription = file.displayName,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(file.icon(), null, modifier = Modifier.size(28.dp))
    }
}

private fun ManagedFileEntity.hasCloudCopy(): Boolean = !r2Key.isNullOrBlank() && !r2Acct.isNullOrBlank()

private fun ManagedFileEntity.icon() = when {
    mimeType.startsWith("image/") -> HugeIcons.Image02
    mimeType.startsWith("video/") -> HugeIcons.Video01
    mimeType.startsWith("audio/") -> HugeIcons.MusicNote03
    else -> HugeIcons.Files02
}

@Composable
private fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Camera01, null)
    }, text = {
        Text(stringResource(R.string.take_picture))
    }) {
        onLaunchCamera()
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
private fun RikkaHubFilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Folder01, null)
    }, text = {
        Text("RikkaHub")
    }) {
        onClick()
    }
}

@Composable
fun UrlPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.GlobalSearch, null)
    }, text = {
        Text("URL")
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}

@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
