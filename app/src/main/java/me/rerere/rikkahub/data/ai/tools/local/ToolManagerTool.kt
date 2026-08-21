package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.tools.WORKSPACE_TOOL_SUMMARIES
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

/**
 * 工具来源分类，供 tool_manage 的 `source` 过滤参数使用。
 */
enum class ToolManageSource(val wire: String) {
    LOCAL("local"),
    WORKSPACE("workspace"),
    MCP("mcp"),
    SKILL("skill"),
    WEB("web");

    companion object {
        fun fromWire(value: String?): ToolManageSource? =
            value?.let { v -> entries.firstOrNull { it.wire == v.lowercase() } }
    }
}

/**
 * 一个可被 tool_manage 列出/开关的工具目录条目。
 *
 * - [id] 是稳定标识：
 *   * local:      LocalToolOption 的 serialName（如 "time_info"），多工具选项（calendar/alarm/subagent/inbox）
 *                 以「选项」为粒度，开关一个选项即同时开关它下辖的多个实际工具；
 *   * workspace:  实际工具名（workspace_read_file ...）；
 *   * mcp:        "serverId/toolName"（与 conversation.mcpTools 同一 key 格式）；
 *   * skill:      skill 名称；
 *   * web:        "web_search"（一个开关同时控制 search_web + scrape_web）。
 * - [name] 是给模型看的人类可读名字；MCP 工具还会带 server 名。
 * - [loadable] = false 表示该项无法通过开关改变（已内置/受模型能力控制/本身就是 tool_manage），
 *   列出仅供参考，不允许 enable/disable。
 */
data class ToolCatalogEntry(
    val source: ToolManageSource,
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val enabled: Boolean,
    val loadable: Boolean,
    val serverId: String? = null,
)

/**
 * tool_manage 执行开关时回传给 ChatService 的意图。ChatService 据此把变更写入
 * 对话级覆盖（localTools / workspaceTools / mcpTools / enabledSkills / enableWebSearch），
 * 并在需要时顺带挂载对应 MCP server。纯数据，不碰会话存储。
 */
sealed interface ToolManageOp {
    data class SetEnabled(val source: ToolManageSource, val id: String, val enabled: Boolean) : ToolManageOp
}

/** 本对话当前已生效的工具集合快照（tool_manage 据此判断 enabled / 组装目录）。 */
data class ToolManageContext(
    val conversation: Conversation,
    val assistant: Assistant,
    val effectiveLocal: List<LocalToolOption>,
    val effectiveWorkspace: Set<String>,
    /** workspace 工具的「默认开启」基准（workspace 配置里的默认值，已 normalize） */
    val workspaceDefaultEnabled: Set<String>,
    val workspaceAvailable: Boolean,
    val mountedMcpServers: Set<Uuid>,
    val effectiveMcpTools: Set<String>,
    /** 未挂载但已配置、可被 tool_manage 挂载的 MCP server（id -> 名字） */
    val allMcpServers: List<Triple<Uuid, String, Boolean>>,
    val effectiveSkills: Set<String>,
    val allSkills: List<Pair<String, String>>,
    val webSearchEnabled: Boolean,
    /**
     * 每个已配置 MCP server 的工具快照：(serverId, serverName, tools)。
     *
     * 由 ChatService 从 settings.mcpServers 预先算好传入，ToolManagerTool 自己不碰
     * SettingsStore / McpManager —— 保持纯数据上下文，好测、好复用，也避免在 tool
     * execute 协程里反查设置。
     */
    val mcpServerTools: List<Triple<Uuid, String, List<McpTool>>> = emptyList(),
)

private const val TOOL_MANAGE_NAME = "tool_manage"
private const val TOOL_MANAGE_DESCRIPTION_PREVIEW_LIMIT = 600

/**
 * 构造 tool_manage 工具。
 *
 * 设计要点（用户 2026-08-21 需求：查工具 + 自己加载工具，别浪费 token）：
 * - list 默认只回 `id / name / source / enabled / loadable` + 一行 summary，**不带完整 description**，
 *   把全量工具说明留给 describe 按需取（懒加载思路，跟 use_skill 一个道理）；
 * - 支持 `source` 只查某一来源（local/workspace/mcp/skill/web），`enabled_only` 只看已开的；
 * - enable/disable 直接写对话级覆盖（经 [onToggle] 回传 ChatService），持久化 + 云同步，
 *   并在结果里明确告知「下一轮回复起生效」；
 * - 开关 MCP 工具时若其所属 server 未挂载，ChatService 会顺带把 server 挂上。
 */
fun buildToolManageTool(
    contextProvider: () -> ToolManageContext,
    onToggle: suspend (ToolManageOp) -> Unit,
): Tool = Tool(
    name = TOOL_MANAGE_NAME,
    description = """
        Inspect and manage the tools available in THIS conversation. Tools come from several sources
        (local, workspace, mcp, skill, web); list only what you need with `source` to stay cheap.

        - action=list (default): compact inventory. Each item has `source`, `id`, `name`, `enabled`,
          `loadable`, and a one-line `summary`. Full schemas/descriptions are NOT returned here —
          call action=describe with the source+id to load a tool's full details before using it.
        - action=describe: full description (and, for MCP, the input schema) of one tool.
        - action=enable / action=disable: turn a tool on or off for this conversation. The change is
          persisted to the conversation and synced; it takes effect from the NEXT assistant turn.
          Enabling an MCP tool also mounts its server if not already mounted.

        You cannot toggle items with loadable=false (tool_manage itself, and tools governed by model
        capability). Use `id` exactly as returned by list.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("list")
                        add("describe")
                        add("enable")
                        add("disable")
                    })
                    put("description", "list (default) | describe | enable | disable")
                })
                put("source", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("local")
                        add("workspace")
                        add("mcp")
                        add("skill")
                        add("web")
                    })
                    put(
                        "description",
                        "list/describe: restrict to one tool source. Omit for all sources."
                    )
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "describe/enable/disable: the tool id exactly as returned by list " +
                            "(local=serialName, workspace=tool name, mcp=\"serverId/toolName\", skill=name, web=\"web_search\")."
                    )
                })
                put("enabled_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "list: only return currently enabled tools. Default false.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "list: optional case-insensitive substring filter on name/summary " +
                            "(e.g. \"shell\", \"calendar\", \"notion\")."
                    )
                })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()
            ?: "list"
        val source = ToolManageSource.fromWire(params["source"]?.jsonPrimitive?.contentOrNull)
        val ctx = contextProvider()
        val catalog = buildCatalog(ctx)
        val payload = when (action) {
            "list" -> {
                val enabledOnly = params["enabled_only"]?.jsonPrimitive?.let {
                    it.contentOrNull?.toBoolean() ?: it.booleanOrNull()
                } ?: false
                val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                    .orEmpty()
                val items = catalog
                    .asSequence()
                    .filter { source == null || it.source == source }
                    .filter { !enabledOnly || it.enabled }
                    .filter {
                        query.isBlank() ||
                            it.name.lowercase().contains(query) ||
                            it.summary.lowercase().contains(query) ||
                            it.id.lowercase().contains(query)
                    }
                    .map { it.toListJson() }
                    .toList()
                buildJsonObject {
                    put("action", "list")
                    put("count", items.size)
                    put("tools", buildJsonArray { items.forEach { add(it) } })
                    put(
                        "hint",
                        "Results are compact (one-line summaries). Use action=describe with " +
                            "source+id for a tool's full description/schema. enable/disable changes " +
                            "take effect on the next assistant turn."
                    )
                }
            }

            "describe" -> {
                val id = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("id is required for action=describe")
                val match = catalog.firstOrNull {
                    it.id == id && (source == null || it.source == source)
                } ?: catalog.firstOrNull {
                    it.id.equals(id, ignoreCase = true) && (source == null || it.source == source)
                }
                ?: error("No tool found for source=${source?.wire ?: "<any>"} id=$id")
                buildJsonObject {
                    put("action", "describe")
                    put("source", match.source.wire)
                    put("id", match.id)
                    put("name", match.name)
                    put("enabled", match.enabled)
                    put("loadable", match.loadable)
                    put("summary", match.summary)
                    put(
                        "description",
                        match.description.take(TOOL_MANAGE_DESCRIPTION_PREVIEW_LIMIT).let {
                            if (match.description.length > TOOL_MANAGE_DESCRIPTION_PREVIEW_LIMIT) {
                                "$it…(truncated)"
                            } else it
                        }
                    )
                }
            }

            "enable", "disable" -> {
                val enabled = action == "enable"
                val id = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("id is required for action=$action")
                val match = catalog.firstOrNull {
                    it.id == id && (source == null || it.source == source)
                } ?: error("No tool found for source=${source?.wire ?: "<any>"} id=$id")
                if (!match.loadable) {
                    error("Tool '${match.name}' (${match.source.wire}:${match.id}) cannot be toggled.")
                }
                onToggle(ToolManageOp.SetEnabled(match.source, match.id, enabled))
                buildJsonObject {
                    put("action", action)
                    put("source", match.source.wire)
                    put("id", match.id)
                    put("name", match.name)
                    put("enabled_now", enabled)
                    put("persisted", true)
                    put("effective", "next_turn")
                    put(
                        "message",
                        buildString {
                            append(match.name)
                            append(if (enabled) " enabled" else " disabled")
                            append(" for this conversation. ")
                            if (match.source == ToolManageSource.MCP && enabled) {
                                append(
                                    "Its MCP server will be mounted if it was not already. "
                                )
                            }
                            append("The change takes effect on your next reply.")
                        }
                    )
                }
            }

            else -> buildJsonObject {
                put("error", "unknown action: $action (expected list | describe | enable | disable)")
            }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    }
)

// ---------------------------------------------------------------- catalog

private fun buildCatalog(ctx: ToolManageContext): List<ToolCatalogEntry> = buildList {
    addAll(buildLocalCatalog(ctx))
    addAll(buildWorkspaceCatalog(ctx))
    addAll(buildMcpCatalog(ctx))
    addAll(buildSkillCatalog(ctx))
    add(buildWebEntry(ctx))
}

private fun buildLocalCatalog(ctx: ToolManageContext): List<ToolCatalogEntry> {
    val effective = ctx.effectiveLocal
    return LOCAL_OPTION_CATALOG.map { def ->
        val isSelf = def.option == LocalToolOption.ToolManage
        ToolCatalogEntry(
            source = ToolManageSource.LOCAL,
            id = def.serialName,
            name = def.title,
            summary = def.summary,
            description = def.description,
            enabled = effective.contains(def.option),
            // tool_manage 自己不能把自己关掉（关了就没法再开）；其余本地选项都可开关。
            loadable = !isSelf,
        )
    }
}

private fun buildWorkspaceCatalog(ctx: ToolManageContext): List<ToolCatalogEntry> {
    if (!ctx.workspaceAvailable) {
        return WORKSPACE_TOOL_SUMMARIES.map { (name, summary) ->
            ToolCatalogEntry(
                source = ToolManageSource.WORKSPACE,
                id = name,
                name = name,
                summary = summary,
                description = summary,
                enabled = false,
                // 没有选 workspace 时开关毫无意义；标成不可加载，让模型知道为何用不了。
                loadable = false,
            )
        }
    }
    return WORKSPACE_TOOL_SUMMARIES.map { (name, summary) ->
        ToolCatalogEntry(
            source = ToolManageSource.WORKSPACE,
            id = name,
            name = name,
            summary = summary,
            description = summary,
            enabled = name in ctx.effectiveWorkspace,
            loadable = true,
        )
    }
}

private fun buildMcpCatalog(ctx: ToolManageContext): List<ToolCatalogEntry> {
    val byId = ctx.mcpServerTools.associateBy { it.first }
    val out = mutableListOf<ToolCatalogEntry>()
    for (server in ctx.allMcpServers) {
        val (serverId, serverName, enable) = server
        val tools = byId[serverId]?.third.orEmpty()
        val mounted = serverId in ctx.mountedMcpServers
        if (tools.isEmpty()) {
            // 已配置但还没同步到工具列表：仍列出 server 本身作为一个「未同步」占位，
            // 但不可加载（没有具体工具可开）。
            out += ToolCatalogEntry(
                source = ToolManageSource.MCP,
                id = "${serverId}/",
                name = serverName.ifBlank { serverId.toString() },
                summary = buildString {
                    append("MCP server")
                    if (!enable) append(" (disabled in settings)")
                    append(if (mounted) ", mounted" else ", not mounted")
                    append(", no tools synced yet")
                },
                description = "MCP server '$serverName' ($serverId). No tool list synced yet.",
                enabled = false,
                loadable = false,
                serverId = serverId.toString(),
            )
            continue
        }
        for (tool in tools) {
            val key = "${serverId}/${tool.name}"
            out += ToolCatalogEntry(
                source = ToolManageSource.MCP,
                id = key,
                name = "${serverName.ifBlank { serverId.toString() }}::${tool.name}",
                summary = (tool.description?.take(160)?.replace("\n", " ")?.ifBlank { null }
                    ?: "MCP tool '${tool.name}' on server '$serverName'."),
                description = tool.description ?: "MCP tool '${tool.name}'.",
                // 启用 = server 已挂载 且 server 未被 settings 关掉 且 该工具在生效集合里。
                enabled = enable && mounted && key in ctx.effectiveMcpTools,
                // settings 里被 disable 的 server / 工具不允许从对话里强开。
                loadable = enable && tool.enable,
                serverId = serverId.toString(),
            )
        }
    }
    return out
}

private fun buildSkillCatalog(ctx: ToolManageContext): List<ToolCatalogEntry> {
    val enabled = ctx.effectiveSkills
    return ctx.allSkills.map { (name, desc) ->
        val summary = desc.lineSequence().firstOrNull()?.take(160)?.ifBlank { null }
            ?: "Skill: $name"
        ToolCatalogEntry(
            source = ToolManageSource.SKILL,
            id = name,
            name = name,
            summary = summary,
            description = desc,
            enabled = name in enabled,
            loadable = true,
        )
    }
}

private fun buildWebEntry(ctx: ToolManageContext): ToolCatalogEntry = ToolCatalogEntry(
    source = ToolManageSource.WEB,
    id = "web_search",
    name = "Web Search",
    summary = "Search the web (search_web) and scrape pages (scrape_web). One switch controls both.",
    description = "Web search and page scraping. When enabled the assistant gets search_web and " +
        "scrape_web to look up current information from the internet.",
    enabled = ctx.webSearchEnabled,
    loadable = true,
)

private fun ToolCatalogEntry.toListJson() = buildJsonObject {
    put("source", source.wire)
    put("id", id)
    put("name", name)
    put("enabled", enabled)
    put("loadable", loadable)
    put("summary", summary)
}

// ---------------------------------------------------------------- local option catalog

/**
 * 每个 [LocalToolOption] 的人类可读元数据。
 *
 * 「多工具选项」（calendar/alarm/subagent/inbox）以**选项**为粒度列出和开关，
 * 不开出 set_alarm/show_alarms 这种内部细节——那会让模型误以为可以单独开一个而把另一个关了，
 * 而代码里它们是同生共死的。
 */
private data class LocalOptionDef(
    val option: LocalToolOption,
    val serialName: String,
    val title: String,
    val summary: String,
    val description: String,
)

private val LOCAL_OPTION_CATALOG: List<LocalOptionDef> = listOf(
    LocalOptionDef(
        LocalToolOption.ToolManage,
        serialName = "tool_manage",
        title = "Tool Manager",
        summary = "This tool: list, inspect, and enable/disable tools for this conversation.",
        description = "Inspect available tools (local/workspace/mcp/skill/web), read their full " +
            "descriptions, and toggle them on or off. Changes persist to the conversation and take " +
            "effect on the next turn. tool_manage itself cannot be disabled.",
    ),
    LocalOptionDef(
        LocalToolOption.JavascriptEngine,
        serialName = "javascript_engine",
        title = "JavaScript Engine",
        summary = "Execute JavaScript code in a sandboxed JS session (eval_javascript).",
        description = "Run arbitrary JavaScript in a sandboxed interpreter for computation, text " +
            "processing, or quick scripting without touching the shell.",
    ),
    LocalOptionDef(
        LocalToolOption.TimeInfo,
        serialName = "time_info",
        title = "Time Info",
        summary = "Get the device's current local date/time, weekday, timezone, and timestamp.",
        description = "Get the current local date and time from the device: year/month/day, weekday, " +
            "ISO date/time, timezone, UTC offset, and epoch timestamp. Use this instead of guessing " +
            "the current time.",
    ),
    LocalOptionDef(
        LocalToolOption.Clipboard,
        serialName = "clipboard",
        title = "Clipboard",
        summary = "Read from and write to the device's system clipboard.",
        description = "Read the current clipboard content, or place text onto the system clipboard.",
    ),
    LocalOptionDef(
        LocalToolOption.Tts,
        serialName = "tts",
        title = "Text to Speech",
        summary = "Speak text aloud through the device's TTS engine.",
        description = "Synthesize text into speech and play it on the device.",
    ),
    LocalOptionDef(
        LocalToolOption.AskUser,
        serialName = "ask_user",
        title = "Ask User",
        summary = "Pop up a question to the user mid-task and wait for their answer.",
        description = "Ask the user a question during a long workflow; the run pauses until the user " +
            "responds (or a timeout answers on their behalf).",
    ),
    LocalOptionDef(
        LocalToolOption.ScreenTime,
        serialName = "screen_time",
        title = "Screen Time",
        summary = "Query per-app screen time / usage stats from the device (needs usage access).",
        description = "Read app usage statistics (foreground time, launch counts, etc.) for " +
            "supervision and accountability. Requires the usage-access permission.",
    ),
    LocalOptionDef(
        LocalToolOption.Calendar,
        serialName = "calendar",
        title = "Calendar",
        summary = "Query and create device calendar events (calendar_query + calendar_create).",
        description = "Read calendar events and create new ones on the device calendar. Grants both " +
            "calendar_query and calendar_create together.",
    ),
    LocalOptionDef(
        LocalToolOption.Alarm,
        serialName = "alarm",
        title = "Alarm",
        summary = "Open the system alarm app to set alarms, or show the alarm list (set_alarm + show_alarms).",
        description = "Open the device's clock/alarm app to create an alarm, or navigate to the alarm " +
            "management screen. Usually requires user confirmation in the system UI.",
    ),
    LocalOptionDef(
        LocalToolOption.Notification,
        serialName = "notification",
        title = "Notification",
        summary = "Post a system notification to the device (send_notification).",
        description = "Send a heads-up system notification, e.g. to alert the user when a background " +
            "task or long workflow finishes.",
    ),
    LocalOptionDef(
        LocalToolOption.ImageGeneration,
        serialName = "image_generation",
        title = "Image Generation",
        summary = "Generate or edit images from a text prompt via a configured image model.",
        description = "Generate images from a text prompt (or edit reference images) using the " +
            "configured image-generation provider. Also auto-enabled when the model natively supports " +
            "image tools.",
    ),
    LocalOptionDef(
        LocalToolOption.Subagent,
        serialName = "subagent",
        title = "Subagents / Agents",
        summary = "Spawn and manage subagents (and implicitly the mailbox for task/report messaging).",
        description = "Spawn, inspect, and review subagents for delegated work. Enabling this also " +
            "enables the mailbox (inbox/send), since subagents receive tasks and report back through it.",
    ),
    LocalOptionDef(
        LocalToolOption.Inbox,
        serialName = "inbox",
        title = "Mailbox (agent_mail)",
        summary = "Cross-conversation mailbox: read inbox and send messages to other conversations/agents.",
        description = "Read incoming mail (subagent reports, questions, instructions) and send messages " +
            "to other conversations by id. This is the channel for cross-agent communication.",
    ),
    LocalOptionDef(
        LocalToolOption.SupervisionAdmin,
        serialName = "supervision_admin",
        title = "Supervision Admin",
        summary = "Export/import settings, lock paths/conversations, and request early unlock (default off).",
        description = "Administration of the supervision/lockdown system: export/import settings JSON, " +
            "lock conversations and workspace paths, and request an early unlock. Off by default; " +
            "even when enabled it only mounts for the designated unlock-grantor assistant.",
    ),
)
