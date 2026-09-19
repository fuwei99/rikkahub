package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 工具调用的渲染上下文, 预解析好工具入参与输出, 避免各渲染器重复解析
 */
data class ToolUIContext(
    val tool: UIMessagePart.Tool,
    /** 工具入参 ([UIMessagePart.Tool.input] 的 JSON 解析结果) */
    val arguments: JsonElement,
    /** 输出文本部件解析出的 JSON, 工具未执行时为 null */
    val content: JsonElement?,
    /** 该工具调用是否在生成中 */
    val loading: Boolean,
)

/**
 * 单个工具的 UI 渲染器
 *
 * 在 [ToolUIRegistry] 注册后, 聊天消息中对应的工具调用将使用该渲染器展示;
 * 未注册的工具 fallback 到接口的默认实现 (通用标题/图标 + JSON 详情)
 */
interface ToolUIRenderer {
    /** 渲染器对应的工具名 */
    val toolName: String

    /** 折叠步骤的图标 */
    fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    /** 折叠步骤的标题 */
    @Composable
    fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)

    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /**
     * 摘要区是否接受统一的最大高度约束（超出则内部滚动）。
     *
     * 默认 true：文本类摘要（文件内容、stdout、diff、列表）没有自然高度上限，
     * 不封顶就会把整屏铺满还显示不全。图片类摘要自己有固定尺寸，可以返回 false 免于滚动。
     */
    fun summaryHeightCapped(context: ToolUIContext): Boolean = true

    /**
     * Summary 是否已自行渲染输出里的图片。
     *
     * 为 true 时 ChatMessageTools 不再叠加通用图片横滑条（LazyRow），
     * 避免同一张图在摘要卡和通用条里各出现一次（生图多图格式踩过这个坑）。
     */
    fun rendersImagesInSummary(context: ToolUIContext): Boolean = false

    /**
     * 摘要下方是否再挂一块「原始内容」出口（工具入参 + 原始输出）。
     *
     * 默认 false：JSON 类工具本就没有富渲染，摘要即原文，再挂一遍纯属重复。
     * 文件读写 / 补丁 / shell 这类做了富渲染的工具返回 true —— 渲染把原始结构盖住了，
     * 需要留一个「看原文」的口子：想知道工具到底收到、返回了哪些字段时只能靠它。
     */
    fun showsRawDetails(context: ToolUIContext): Boolean = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }

    /** 点击步骤后的详情, 渲染在 BottomSheet 内 */
    @Composable
    fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context = context)
    }
}

/** 未注册工具使用的默认渲染器, 全部行为来自 [ToolUIRenderer] 的默认实现 */
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""

    /**
     * MCP 工具标题去掉 `mcp__<server>__` 前缀。
     *
     * server 名已由气泡上的来源徽章单独展示，标题里再带一遍前缀纯属噪音，
     * 而且长名字会把标题挤到省略号。
     */
    @Composable
    override fun title(context: ToolUIContext): String {
        val raw = context.tool.toolName
        val display = if (raw.startsWith("mcp__")) {
            val rest = raw.removePrefix("mcp__")
            val idx = rest.indexOf("__")
            if (idx >= 0) rest.substring(idx + 2) else rest
        } else {
            raw
        }
        return stringResource(R.string.chat_message_tool_call_generic, display)
    }
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 */
object ToolUIRegistry {
    private val renderers: Map<String, ToolUIRenderer> = listOf(
        MemoryToolUI,
        SearchWebToolUI,
        ScrapeWebToolUI,
        GetTimeInfoToolUI,
        ClipboardToolUI,
        TextToSpeechToolUI,
        GetScreenTimeToolUI,
        CalendarQueryToolUI,
        CalendarCreateToolUI,
        UseSkillToolUI,
        SubagentToolUI,
        AgentToolUI,
        AgentReportToolUI,
        AgentAskToolUI,
        AgentSendToolUI,
        AgentMailToolUI,
        ChatHistoryToolUI,
        // 旧工具名（recent_chats / conversation_search）保留注册：
        // 历史消息里的气泡还引用它们，删掉就掉回默认 JSON 渲染。
        RecentChatsToolUI,
        ConversationSearchToolUI,
        EditFileToolUI,
        ReadFileToolUI,
        WriteFileToolUI,
        ShellToolUI,
        PatchToolUI,
        CodexPatchToolUI,
        ImageGenerationToolUI,
    ).associateBy { it.toolName }

    /**
     * MCP 工具名 → 内置工作区渲染器。
     *
     * 外部 MCP server(termux / win-pc-agent 等)提供的是同一套文件工具语义, 只是名字裸着,
     * 且被 ChatService 前缀成 `mcp__<server>__<tool>`。这里按裸名复用工作区渲染器,
     * 免得同样的读写补丁在气泡里退化成一坨 JSON。输出字段形状差异由各渲染器自己兼容。
     */
    private val mcpAliases: Map<String, ToolUIRenderer> = mapOf(
        "read_file" to ReadFileToolUI,
        "write_file" to WriteFileToolUI,
        "edit_file" to EditFileToolUI,
        "apply_patch" to PatchToolUI,
        "codex_patch" to CodexPatchToolUI,
        "apply_codex_patch" to CodexPatchToolUI,
        "shell" to ShellToolUI,
        "shell_session" to ShellToolUI,
    )

    /**
     * `mcp__<server>__<tool>` → `<server>`; 本地内置工具返回 null。
     *
     * 用于气泡上标出「这活是哪台机器/哪个 MCP 干的」：MCP 工具被 alias 成内置工作区渲染器后，
     * 卡片长得和本地 workspace_* 完全一样，不标来源根本分不清是本机还是 pc / termux。
     */
    fun mcpServerName(toolName: String): String? {
        if (!toolName.startsWith("mcp__")) return null
        val rest = toolName.removePrefix("mcp__")
        val idx = rest.indexOf("__")
        return if (idx > 0) rest.substring(0, idx) else null
    }

    /** `mcp__<server>__<tool>` → `<tool>`; 非 MCP 名返回 null */
    private fun stripMcpPrefix(toolName: String): String? {
        if (!toolName.startsWith("mcp__")) return null
        val rest = toolName.removePrefix("mcp__")
        val idx = rest.indexOf("__")
        return if (idx >= 0) rest.substring(idx + 2) else rest
    }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer = when (toolName) {
        // 历史会话里的分裂工具名, 统一由记忆渲染器接管
        "assistant_memory_tool", "global_memory_tool" -> MemoryToolUI
        else -> renderers[toolName]
            ?: stripMcpPrefix(toolName)?.let { bare ->
                renderers["workspace_$bare"] ?: renderers[bare] ?: mcpAliases[bare]
            }
            ?: DefaultToolUIRenderer
    }
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

/**
 * 工具调用的原始内容: 入参与输出的 JSON 高亮展示。
 *
 * 抽成独立组件给两处复用: BottomSheet 里的 [DefaultToolPreview], 以及摘要下方的
 * 「原始内容」折叠区 —— 富渲染工具在上面已经把结构盖住了, 这里保证任何时候
 * 都能看到工具实际收发的是什么。
 */
@Composable
fun RawToolContent(
    context: ToolUIContext,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, context.tool.toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(context.arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
        if (context.tool.output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tool.output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = runCatching {
                                    JsonInstantPretty.encodeToString(
                                        JsonInstant.parseToJsonElement(part.text)
                                    )
                                }.getOrElse { part.text },
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

/**
 * 摘要下方的「原始内容」折叠区。
 *
 * 默认折叠: 它是出口, 不是主视图。展开后按 [RawToolContent] 渲染入参与输出原文。
 */
@Composable
fun RawToolDetails(
    context: ToolUIContext,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(context) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.chat_message_tool_raw_details),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            RawToolContent(
                context = context,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
        }
    }
}

/**
 * 默认工具详情: 入参与输出的 JSON 高亮展示
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            headerActions?.invoke()
        }
        RawToolContent(context = context)
    }
}
