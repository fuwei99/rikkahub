package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.CalendarAdd01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.subagent.SubagentJobManager
import me.rerere.rikkahub.data.ai.tools.MEMORY_TOOL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentJobStatus
import me.rerere.rikkahub.data.ai.subagent.SubagentTraceState
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 记忆工具: 按 action 区分标题/图标, 摘要显示记忆内容, 详情附带删除按钮
 */
object MemoryToolUI : ToolUIRenderer {
    private const val ACTION_CREATE = "create"
    private const val ACTION_EDIT = "edit"
    private const val ACTION_DELETE = "delete"
    private const val ACTION_LINK = "link"
    private const val ACTION_QUERY_LINKS = "query_links"
    private const val ACTION_UNLINK = "unlink"

    override val toolName: String = MEMORY_TOOL_NAME

    private fun action(context: ToolUIContext): String? =
        context.arguments.getStringContent("action")

    override fun icon(context: ToolUIContext): ImageVector = when (action(context)) {
        ACTION_DELETE, ACTION_UNLINK -> HugeIcons.Eraser
        ACTION_LINK -> HugeIcons.Link01
        ACTION_QUERY_LINKS -> HugeIcons.GlobalSearch
        else -> HugeIcons.QuillWrite01
    }

    private fun scopeLabel(context: ToolUIContext): String? =
        when (context.arguments.getStringContent("scope") ?: context.content.getStringContent("scope")) {
            "global" -> "全局"
            "assistant" -> "助手"
            else -> null
        }

    @Composable
    override fun title(context: ToolUIContext): String {
        val base = when (action(context)) {
            ACTION_CREATE -> stringResource(R.string.chat_message_tool_create_memory)
            ACTION_EDIT -> stringResource(R.string.chat_message_tool_edit_memory)
            ACTION_DELETE -> stringResource(R.string.chat_message_tool_delete_memory)
            ACTION_LINK -> stringResource(R.string.chat_message_tool_link_memory)
            ACTION_QUERY_LINKS -> stringResource(R.string.chat_message_tool_query_memory_links)
            ACTION_UNLINK -> stringResource(R.string.chat_message_tool_unlink_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }
        return scopeLabel(context)?.let { "$base · $it" } ?: base
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        (action(context) in listOf(ACTION_CREATE, ACTION_EDIT) &&
            context.content.getStringContent("content") != null) ||
            (action(context) == ACTION_QUERY_LINKS &&
                (context.content?.jsonObjectOrNull?.get("links")?.jsonArray?.size ?: 0) > 0)

    @Composable
    override fun Summary(context: ToolUIContext) {
        when (action(context)) {
            ACTION_QUERY_LINKS -> {
                val links = context.content?.jsonObjectOrNull?.get("links")?.jsonArray.orEmpty()
                val defaultLabel = stringResource(R.string.memory_graph_edge_default_label)
                Text(
                    text = links.joinToString("\n") { element ->
                        val obj = element.jsonObjectOrNull
                        "${obj?.get("type")?.jsonPrimitiveOrNull?.contentOrNull ?: defaultLabel}: " +
                            "${obj?.get("source_content")?.jsonPrimitiveOrNull?.contentOrNull ?: "#${obj?.get("source_id")?.jsonPrimitiveOrNull?.intOrNull}"} → " +
                            "${obj?.get("target_content")?.jsonPrimitiveOrNull?.contentOrNull ?: "#${obj?.get("target_id")?.jsonPrimitiveOrNull?.intOrNull}"}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            else -> context.content.getStringContent("content")?.let { memoryContent ->
                Text(
                    text = memoryContent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.shimmer(isLoading = context.loading),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val memoryRepo: MemoryRepository = koinInject()
        val scope = rememberCoroutineScope()
        val memoryId = (context.content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull
        DefaultToolPreview(
            context = context,
            headerActions = if (action(context) in listOf(ACTION_CREATE, ACTION_EDIT) && memoryId != null) {
                {
                    IconButton(
                        onClick = {
                            scope.launch {
                                memoryRepo.deleteMemory(memoryId)
                                onDismissRequest()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.tool_ui_delete_memory)
                        )
                    }
                }
            } else {
                null
            },
        )
    }
}

/**
 * 网络搜索: 标题带查询词, 摘要显示 answer 与结果数, 详情为结果列表
 */
object SearchWebToolUI : ToolUIRenderer {
    override val toolName: String = "search_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_search_web,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun items(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("items")?.jsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("answer") != null || items(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("answer")?.let { answer ->
            Text(
                text = answer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val items = items(context)
        if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FaviconRow(
                    urls = items.mapNotNull { it.getStringContent("url") },
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        SearchWebPreview(arguments = context.arguments, content = content)
    }
}

/**
 * 网页抓取: 摘要显示 URL, 详情为各网页的 Markdown 内容
 */
object ScrapeWebToolUI : ToolUIRenderer {
    override val toolName: String = "scrape_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_scrape_web)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("url") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Text(
            text = context.arguments.getStringContent("url") ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        ScrapeWebPreview(content = content)
    }
}

/**
 * 获取时间信息
 */
object GetTimeInfoToolUI : ToolUIRenderer {
    override val toolName: String = "get_time_info"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Time02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_get_time)
}

/**
 * 剪贴板: 按 action 区分读/写标题
 */
object ClipboardToolUI : ToolUIRenderer {
    private const val ACTION_READ = "read"
    private const val ACTION_WRITE = "write"

    override val toolName: String = "clipboard_tool"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clipboard

    @Composable
    override fun title(context: ToolUIContext): String =
        when (context.arguments.getStringContent("action")) {
            ACTION_READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ACTION_WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }
}

/**
 * 文本转语音: 摘要显示朗读文本与重播按钮
 */
object TextToSpeechToolUI : ToolUIRenderer {
    override val toolName: String = "text_to_speech"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.VolumeHigh

    @Composable
    override fun title(context: ToolUIContext): String {
        val preview = context.arguments.getStringContent("text")?.let { text ->
            if (text.length > 24) text.take(24) + "…" else text
        } ?: ""
        return stringResource(R.string.tool_ui_speaking, preview)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("text") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = context.arguments.getStringContent("text") ?: ""
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.tool_ui_replay),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 技能调用: 标题显示技能名与路径
 */
object UseSkillToolUI : ToolUIRenderer {
    override val toolName: String = "use_skill"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("name") ?: ""
        val path = context.arguments.getStringContent("path")
        return if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
    }
}

/**
 * 最近聊天: 标题固定, 摘要列出最近对话的标题
 */
object RecentChatsToolUI : ToolUIRenderer {
    override val toolName: String = "recent_chats"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_recent_chats)

    private fun chats(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = chats(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val titles = chats(context).mapNotNull { it.getStringContent("title") }
        if (titles.isEmpty()) return
        Text(
            text = titles.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 对话历史搜索: 标题带查询词, 摘要显示命中数
 */
object ConversationSearchToolUI : ToolUIRenderer {
    override val toolName: String = "conversation_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_conversation_search,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun results(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = results(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val results = results(context)
        if (results.isEmpty()) return
        Text(
            text = stringResource(R.string.chat_message_tool_search_results_count, results.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }
}

/**
 * 对话历史 (chat_history, 2026-08-11 三合一): 标题按 action 区分,
 * 摘要分别显示会话标题列表 / 命中数 / 取到的消息条数。
 *
 * 旧的 RecentChatsToolUI / ConversationSearchToolUI 保留注册, 否则历史消息里的
 * recent_chats / conversation_search 气泡会掉回默认 JSON 渲染。
 */
object ChatHistoryToolUI : ToolUIRenderer {
    override val toolName: String = "chat_history"

    override fun icon(context: ToolUIContext): ImageVector = when (action(context)) {
        "search" -> HugeIcons.Search01
        else -> HugeIcons.Message02
    }

    private fun action(context: ToolUIContext): String =
        context.arguments.getStringContent("action")
            ?: context.content.getStringContent("action")
            ?: "recent"

    private fun results(context: ToolUIContext): List<JsonElement> =
        (context.content?.jsonObjectOrNull?.get("results") as? JsonArray) ?: emptyList()

    private fun messages(context: ToolUIContext): List<JsonElement> =
        (context.content?.jsonObjectOrNull?.get("messages") as? JsonArray) ?: emptyList()

    @Composable
    override fun title(context: ToolUIContext): String = when (action(context)) {
        "search" -> stringResource(
            R.string.chat_message_tool_conversation_search,
            context.arguments.getStringContent("query") ?: ""
        )

        "fetch" -> stringResource(
            R.string.chat_message_tool_chat_history_fetch,
            context.content.getStringContent("title") ?: ""
        )

        else -> stringResource(R.string.chat_message_tool_recent_chats)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        results(context).isNotEmpty() || messages(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = when (action(context)) {
            "fetch" -> {
                val count = messages(context).size
                if (count == 0) return
                stringResource(R.string.chat_message_tool_search_results_count, count)
            }

            "search" -> {
                val count = results(context).size
                if (count == 0) return
                stringResource(R.string.chat_message_tool_search_results_count, count)
            }

            else -> {
                val titles = results(context).mapNotNull { it.getStringContent("title") }
                if (titles.isEmpty()) return
                titles.joinToString(", ")
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 屏幕使用时间: 摘要显示总时长与用时最多的应用, 详情为按时长排序的应用列表 (带占比条);
 * 无权限时回退到默认 JSON 详情
 */
object GetScreenTimeToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_APPS = 3
    private const val SUMMARY_MAX_DEVICES = 4

    override val toolName: String = "get_screen_time"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_screen_time)

    private fun apps(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("apps")?.let { it as? JsonArray } ?: emptyList()

    /** devices[]: 2026-08-11 起工具按设备拆分明细; 旧消息没有该字段, 回退到顶层 apps */
    private fun devices(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("devices")?.let { it as? JsonArray } ?: emptyList()

    private fun isNoPermission(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") == "NO_PERMISSION" ||
            context.content.getStringContent("local_error") == "NO_PERMISSION"

    override fun hasSummary(context: ToolUIContext): Boolean =
        isNoPermission(context) || devices(context).isNotEmpty() || apps(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val devices = devices(context)
        val noPermission = isNoPermission(context)
        if (devices.isEmpty() && noPermission) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        if (devices.isNotEmpty()) {
            val allMs = context.content?.jsonObjectOrNull?.get("total_all_devices_ms")
                ?.jsonPrimitiveOrNull?.longOrNull ?: devices.sumOf { it.deviceMs() }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.shimmer(isLoading = context.loading),
            ) {
                ScreenTimeSummaryRow(
                    label = stringResource(R.string.tool_ui_screen_time_all_devices),
                    value = formatMinutes(allMs / 60000),
                    emphasizeLabel = true,
                )
                devices.take(SUMMARY_MAX_DEVICES).forEach { device ->
                    ScreenTimeSummaryRow(
                        label = device.deviceLabel(),
                        value = formatMinutes(device.deviceMs() / 60000),
                    )
                }
                if (noPermission) {
                    Text(
                        text = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            return
        }
        val apps = apps(context)
        if (apps.isEmpty()) return
        val totalMinutes = context.content?.jsonObjectOrNull?.get("total_minutes")
            ?.jsonPrimitiveOrNull?.longOrNull ?: 0
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            ScreenTimeSummaryRow(
                label = stringResource(R.string.tool_ui_screen_time_total),
                value = formatMinutes(totalMinutes),
                emphasizeLabel = true,
            )
            apps.take(SUMMARY_MAX_APPS).forEach { app ->
                ScreenTimeSummaryRow(
                    label = app.getStringContent("app_name") ?: app.getStringContent("package") ?: "",
                    value = formatMinutes(app.appMinutes()),
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val devices = devices(context)
        val apps = apps(context)
        if (devices.isEmpty() && apps.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }
        ScreenTimePreview(content = context.content!!, devices = devices, legacyApps = apps)
    }
}

object CalendarQueryToolUI : ToolUIRenderer {
    override val toolName: String = "calendar_query"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Calendar03

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_calendar_query)

    private fun events(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("events")?.let { it as? JsonArray } ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = events(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val events = events(context)
        if (events.isEmpty()) return
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_search_results_count, events.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            events.take(3).forEach { event ->
                val title = event.getStringContent("title") ?: return@forEach
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

object CalendarCreateToolUI : ToolUIRenderer {
    override val toolName: String = "calendar_create"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.CalendarAdd01

    @Composable
    override fun title(context: ToolUIContext): String {
        val eventTitle = context.arguments.getStringContent("title") ?: ""
        return stringResource(R.string.chat_message_tool_calendar_create, eventTitle)
    }
}

@Composable
private fun ScreenTimePreview(
    content: JsonElement,
    devices: List<JsonElement>,
    legacyApps: List<JsonElement>,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            val allMs = content.jsonObjectOrNull?.get("total_all_devices_ms")
                ?.jsonPrimitiveOrNull?.longOrNull
                ?: (content.jsonObjectOrNull?.get("total_ms")?.jsonPrimitiveOrNull?.longOrNull ?: 0L)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (devices.size > 1) {
                            stringResource(R.string.tool_ui_screen_time_all_devices)
                        } else {
                            stringResource(R.string.tool_ui_screen_time_total)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(allMs / 60000),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val begin = content.getStringContent("start")
                val finish = content.getStringContent("end")
                if (begin != null && finish != null) {
                    Text(
                        text = "${formatRangeTime(begin)} → ${formatRangeTime(finish)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        if (devices.isEmpty()) {
            // 旧消息: 只有顶层 apps
            val maxAppMs = legacyApps.maxOfOrNull { it.appMs() }?.takeIf { it > 0 } ?: 1L
            items(legacyApps) { app ->
                ScreenTimeAppRow(app = app, maxAppMs = maxAppMs)
            }
            return@LazyColumn
        }
        devices.forEach { device ->
            item {
                ScreenTimeDeviceHeader(device = device)
            }
            val deviceApps = device.jsonObjectOrNull?.get("apps")?.let { it as? JsonArray } ?: emptyList()
            val maxAppMs = deviceApps.maxOfOrNull { it.appMs() }?.takeIf { it > 0 } ?: 1L
            items(deviceApps) { app ->
                ScreenTimeAppRow(
                    app = app,
                    maxAppMs = maxAppMs,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** 设备分组头: 名称 + 总时长 + 粒度角标 + 小时分布柱状图 + 数据完整性提示 */
@Composable
private fun ScreenTimeDeviceHeader(device: JsonElement) {
    val granularity = device.getStringContent("granularity") ?: "daily"
    val isCurrent = device.jsonObjectOrNull?.get("is_current_device")
        ?.jsonPrimitiveOrNull?.contentOrNull == "true"
    val hourly = device.jsonObjectOrNull?.get("hourly_ms")?.let { it as? JsonArray }
        ?.map { it.jsonPrimitiveOrNull?.longOrNull ?: 0L }
        ?: emptyList()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = device.deviceLabel() + if (isCurrent) " ·" else "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = granularity,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                text = formatMinutes(device.deviceMs() / 60000),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
        if (hourly.size == 24) {
            ScreenTimeHourlyBars(hourly = hourly)
            val lateNight = device.jsonObjectOrNull?.get("late_night_ms")
                ?.jsonPrimitiveOrNull?.longOrNull ?: hourly.take(6).sum()
            if (lateNight > 0) {
                Text(
                    text = stringResource(
                        R.string.tool_ui_screen_time_late_night,
                        formatMinutes(lateNight / 60000),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                )
            }
        }
        if (!isCurrent) {
            val days = device.jsonObjectOrNull?.get("days_with_data")?.jsonPrimitiveOrNull?.longOrNull
            val from = device.getStringContent("data_start_date")
            val to = device.getStringContent("data_end_date")
            if (days != null && from != null && to != null) {
                Text(
                    text = stringResource(
                        R.string.tool_ui_screen_time_remote_note,
                        days.toString(),
                        from,
                        to,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

/** 24 小时分布迷你柱状图, 深夜 0-6 点用 error 色标出 */
@Composable
private fun ScreenTimeHourlyBars(hourly: List<Long>) {
    val maxMs = hourly.maxOrNull()?.takeIf { it > 0 } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        hourly.forEachIndexed { hour, ms ->
            val fraction = (ms.toFloat() / maxMs).coerceIn(0f, 1f)
            val color = if (hour < 6) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(if (fraction > 0f) maxOf(fraction, 0.04f) else 0.02f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (ms > 0) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        ),
                )
            }
        }
    }
}

@Composable
private fun ScreenTimeAppRow(
    app: JsonElement,
    maxAppMs: Long,
    modifier: Modifier = Modifier,
) {
    val name = app.getStringContent("app_name") ?: app.getStringContent("package") ?: return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatMinutes(app.appMinutes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        LinearProgressIndicator(
            progress = { (app.appMs().toFloat() / maxAppMs).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScreenTimeSummaryRow(
    label: String,
    value: String,
    emphasizeLabel: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                alpha = if (emphasizeLabel) 0.8f else 1f
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                alpha = if (emphasizeLabel) 1f else 0.8f
            ),
        )
    }
}

/** 设备条目的总时长 (毫秒) 与展示名 */
private fun JsonElement.deviceMs(): Long =
    jsonObjectOrNull?.get("total_ms")?.jsonPrimitiveOrNull?.longOrNull ?: 0

private fun JsonElement.deviceLabel(): String =
    getStringContent("device_label") ?: getStringContent("device_id") ?: ""

/** 读取单个应用条目的前台时长 (毫秒) */
private fun JsonElement.appMs(): Long =
    jsonObjectOrNull?.get("total_ms")?.jsonPrimitiveOrNull?.longOrNull ?: 0

/** 读取单个应用条目的前台时长 (分钟) */
private fun JsonElement.appMinutes(): Long =
    jsonObjectOrNull?.get("total_minutes")?.jsonPrimitiveOrNull?.longOrNull ?: (appMs() / 60000)

private val SCREEN_TIME_RANGE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 将工具返回的 ISO 时间字符串格式化为 "MM-dd HH:mm", 解析失败时原样返回.
 *
 * 工具用 ZonedDateTime.toString() 输出, 区域 ID 时会带 "[Asia/Shanghai]" 后缀,
 * 故优先用 ZonedDateTime.parse, 再回退到 offset / 本地日期时间.
 */
private fun formatRangeTime(iso: String): String = runCatching {
    ZonedDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    OffsetDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    LocalDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.getOrDefault(iso)

/** 将分钟数格式化为 "Xh Ym" / "Xh" / "Ym" */
private fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""
    val images = content.jsonObject["images"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (answer != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (images.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(images) { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .height(120.dp)
                                .width(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { context.openUrl(imageUrl) },
                        )
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(text = title, maxLines = 1)
                            Text(
                                text = text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = url,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                HighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.chat_message_tool_scrape_prefix,
                    urls.joinToString(", ") { it.getStringContent("url") ?: "" }
                )
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

object SubagentToolUI : ToolUIRenderer {
    override val toolName: String = "subagent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val trace = context.subagentTrace()
        val status = trace?.status?.name?.lowercase()
            ?: context.content.getStringContent("status")
            ?: if (context.loading) "running" else "subagent"
        val task = trace?.taskBrief
            ?: context.arguments.jsonObjectOrNull?.get("task")?.jsonPrimitiveOrNull?.contentOrNull
            ?: "Subagent"
        return "Subagent $status · ${task.take(48)}"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val trace = context.subagentTrace()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (trace != null) {
                Text("Status: ${trace.status.name.lowercase()} · step ${trace.steps}/${trace.maxSteps} · tools ${trace.toolCalls.size}")
                Text("Tokens: ${trace.tokenUsage.totalTokens}/${trace.maxTotalTokens.takeIf { it > 0 } ?: "?"}")
                trace.currentTool?.takeIf { it.isNotBlank() }?.let { Text("Current tool: $it") }
                trace.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                trace.summary?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            } else {
                Text(context.content.getStringContent("summary") ?: context.content.getStringContent("error") ?: "Subagent job submitted")
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val trace = context.subagentTrace()
        if (trace == null) {
            DefaultToolPreview(context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Subagent · ${trace.status.name.lowercase()}", style = MaterialTheme.typography.headlineSmall)
            Text(trace.taskBrief, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Steps: ${trace.steps}/${trace.maxSteps} · Tools: ${trace.toolCalls.size} · Tokens: ${trace.tokenUsage.totalTokens}/${trace.maxTotalTokens.takeIf { it > 0 } ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
            )
            trace.currentTool?.takeIf { it.isNotBlank() }?.let {
                Text("Current tool: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            trace.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

            // 实时消息流: 与主对话一致的渲染 (气泡 / markdown / 工具调用卡片)
            SubagentLiveConversation(trace, Modifier.weight(1f))

            if (trace.status == SubagentJobStatus.RUNNING) {
                LinearProgressIndicator(
                    progress = { trace.steps.toFloat() / trace.maxSteps.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trace.summary?.let {
                FormItem(label = { Text("Summary") }) {
                    MarkdownBlock(it)
                }
            }
        }
    }

    @Composable
    private fun ToolUIContext.subagentTrace(): SubagentTraceState? {
        val jobId = content.getStringContent("job_id") ?: return null
        val manager: SubagentJobManager = koinInject()
        val traces by manager.traces.collectAsState()
        return traces[jobId]
    }
}

/** 子代理实时消息流: 渲染成与主对话一致的外观, 新消息自动滚到底部 */
@Composable
private fun SubagentLiveConversation(trace: SubagentTraceState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val messageCount = trace.messages.size
    LaunchedEffect(messageCount) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(trace.messages) { message ->
            SubagentMessageRow(message)
        }
    }
}

@Composable
private fun SubagentMessageRow(message: UIMessage) {
    when (message.role) {
        MessageRole.USER -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        message.parts.filterIsInstance<UIMessagePart.Text>().forEach { part ->
                            MarkdownBlock(part.text)
                        }
                    }
                }
            }
        }

        MessageRole.ASSISTANT -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> if (part.text.isNotBlank()) MarkdownBlock(part.text)
                        is UIMessagePart.Reasoning -> if (part.reasoning.isNotBlank()) {
                            Text(
                                "💭 ${part.reasoning.take(240)}${if (part.reasoning.length > 240) "…" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        is UIMessagePart.Tool -> SubagentToolCallCard(part)
                        else -> Unit
                    }
                }
            }
        }

        else -> Unit
    }
}

/** 子代理的工具调用卡片: 复用主对话的 ToolUIRegistry 渲染器, 图标/标题与主对话一致 */
@Composable
private fun SubagentToolCallCard(tool: UIMessagePart.Tool) {
    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = !tool.isExecuted,
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = renderer.title(context),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tool.input.isNotBlank()) {
                Text(
                    tool.input,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tool.isExecuted) {
                val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                if (outputText.isNotBlank()) {
                    Text(
                        outputText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
