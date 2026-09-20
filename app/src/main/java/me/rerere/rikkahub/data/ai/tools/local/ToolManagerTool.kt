package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
 * 工具来源分类。package 配置没覆盖到某工具时，按来源自动成组用它作前缀。
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
 * 一个可被 tool_manage 列出/挂载的工具目录条目。
 *
 * - [id] 是稳定标识，也是**模型调用名**（`Tool.name`）：
 *   * local:      LocalToolOption 的 serialName（如 "time_info"），多工具选项以「选项」为粒度；
 *   * workspace:  实际工具名（workspace_read_file ...）；
 *   * mcp:        "mcp__server__tool"（与执行解析名一致）；
 *   * skill:      skill 名称；
 *   * web:        "web_search"（一个开关同时控制 search_web + scrape_web）。
 * - [loadable] = false 表示该项无法通过 tool_manage 挂载（已内置 / 受模型能力控制 / 本身就是 tool_manage /
 *   工作区未就绪 / MCP server 在 settings 里被关）。
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
 * tool_manage 执行挂载时回传给 ChatService 的意图。
 * ChatService 据此把变更写进会话的**租借集合**（leasedTools），不碰 tool list。
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
    /**
     * 池：全部**可现造**的 Tool（local + workspace + 已挂载 MCP），不受对话开关影响。
     * tool_manage 用它在 enable / list package= 时回 parameters；空 = 未提供（parameters 省略）。
     * （2026-09-20 工具按需挂载重构）
     */
    val toolPool: List<Tool> = emptyList(),
    /**
     * 用户配置的工具包（见 [ToolPackage] / packages.json）。
     * 空 = 未配置 → tool_manage 按来源自动成组。
     */
    val packages: List<ToolPackage> = emptyList(),
)

private const val TOOL_MANAGE_NAME = "tool_manage"
private const val TOOL_MANAGE_DESC_LIMIT = 100

/**
 * 构造 tool_manage 工具（2026-09-20 工具按需挂载重构）。
 *
 * 设计三句话（见 plan）：**包即索引，schema 走 tool result，tool list 只留稳定项。**
 *
 * - `list`（无参）→ 只列包（name + tools 数），几行而已，不再 dump 全量；
 * - `list package=P` → 该包内全部工具，**带 parameters**；
 * - `list query=Q` → 跨包搜索，按包分组、组内只放命中的 tool（description 截断，不带 parameters）；
 * - `enable ids=[...]` → 写入本对话租借集合 + 回 parameters，本轮即可直接调。
 *
 * 闭环：`query` 找钩子 → `enable` 拿 parameters → 直接调。
 */
fun buildToolManageTool(
    contextProvider: () -> ToolManageContext,
    onToggle: suspend (ToolManageOp) -> Unit,
): Tool = Tool(
    name = TOOL_MANAGE_NAME,
    description = """
        Browse and load tools for this conversation. Tools are grouped into packages.

        - action=list (default)
            no args   -> all packages
            package=P -> that package's tools, with parameters
            query=Q   -> substring search over tool id + description, across all packages
        - action=enable
            ids=[...] -> load those tools, returning their parameters. Callable immediately.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("list")
                        add("enable")
                    })
                    put("description", "list (default) | enable")
                })
                put("package", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "list: one package id, e.g. \"workspace\", \"mcp:zhihu\". Returns its tools with parameters."
                    )
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "list: case-insensitive substring matched against tool id and description, across all packages."
                    )
                })
                put("ids", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "enable: tool ids to load (multiple allowed).")
                })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "list"
        val ctx = contextProvider()
        val catalog = buildCatalog(ctx)
        val packages = resolvePackages(ctx.packages, catalog)
        val poolById = ctx.toolPool.associateBy { it.name }
        val payload = when (action) {
            "enable" -> {
                val ids = params["ids"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                enableTools(ids, packages, catalog, poolById, onToggle)
            }

            "list" -> {
                val pkgKey = params["package"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                when {
                    pkgKey != null -> listOnePackage(packages, pkgKey, poolById)
                    query != null -> listByQuery(packages, query)
                    else -> listPackages(packages)
                }
            }

            else -> buildJsonObject {
                put("error", "unknown action: $action (expected list | enable)")
            }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    }
)

// ---------------------------------------------------------------- package resolution

/** 一个解析好的包：配置包优先，未被认领的工具按来源自动成组。 */
private data class ResolvedPackage(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val entries: List<ToolCatalogEntry>,
)

/**
 * 把用户配置的包和目录条目合到一起。
 *
 * 1. 配置包（顺序即优先级）：`tools` 里声明且存在于目录的条目归入该包；一个 id 被多包声明时先声明的赢。
 * 2. 未被任何配置包认领的条目，按来源自动成组（local / workspace / skill / web / mcp:<server>），默认 enabled。
 *
 * 这样即使 packages.json 只写了一小撮工具，其余工具也不会被藏起来 —— 保证向后兼容。
 */
private fun resolvePackages(
    config: List<ToolPackage>,
    catalog: List<ToolCatalogEntry>,
): List<ResolvedPackage> {
    val byId = catalog.associateBy { it.id }
    val claimed = mutableMapOf<String, String>() // toolId -> packageId
    val out = mutableListOf<ResolvedPackage>()

    for (pkg in config) {
        val members = pkg.tools
            .mapNotNull { byId[it] }
            .filter { claimed.putIfAbsent(it.id, pkg.id) == null }
        if (members.isNotEmpty()) {
            out += ResolvedPackage(
                id = pkg.id,
                name = pkg.name.ifBlank { pkg.id },
                enabled = pkg.enabled,
                entries = members,
            )
        }
    }

    val leftovers = catalog.filter { it.id !in claimed }
    val autoGroups = LinkedHashMap<String, MutableList<ToolCatalogEntry>>()
    val autoNames = mutableMapOf<String, String>()
    for (entry in leftovers) {
        val (gid, gname) = autoGroupOf(entry)
        autoGroups.getOrPut(gid) { mutableListOf() }.add(entry)
        autoNames[gid] = gname
    }
    for ((gid, entries) in autoGroups) {
        out += ResolvedPackage(
            id = gid,
            name = autoNames[gid] ?: gid,
            enabled = true,
            entries = entries,
        )
    }
    return out
}

private fun autoGroupOf(entry: ToolCatalogEntry): Pair<String, String> = when (entry.source) {
    ToolManageSource.LOCAL -> "local" to "本地工具"
    ToolManageSource.WORKSPACE -> "workspace" to "工作区"
    ToolManageSource.SKILL -> "skill" to "技能"
    ToolManageSource.WEB -> "web" to "联网"
    ToolManageSource.MCP -> {
        val sid = entry.serverId.orEmpty()
        val sname = entry.name.substringBefore("::").ifBlank { sid }
        "mcp:$sname" to sname
    }
}

// ---------------------------------------------------------------- return forms

/** ① list（无参）→ 只列包：id + name + 工具数。 */
private fun listPackages(packages: List<ResolvedPackage>) = buildJsonObject {
    put("action", "list")
    put("packages", buildJsonArray {
        packages.filter { it.enabled }.forEach { pkg ->
            add(buildJsonObject {
                put("package", pkg.id)
                put("name", pkg.name)
                put("tools", pkg.entries.size)
            })
        }
    })
}

/** ② list package=P → 包内全部工具（含 parameters）。 */
private fun listOnePackage(
    packages: List<ResolvedPackage>,
    key: String,
    pool: Map<String, Tool>,
) = buildJsonObject {
    val pkg = packages.firstOrNull {
        it.enabled && (it.id.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true))
    }
    if (pkg == null) {
        put("action", "list")
        put("error", "no such package: $key")
        put("packages", buildJsonArray {
            packages.filter { it.enabled }.forEach { add(it.id) }
        })
    } else {
        put("action", "list")
        put("package", pkg.id)
        put("name", pkg.name)
        put("tools", buildJsonArray {
            pkg.entries.forEach { add(it.toDetailJson(pool)) }
        })
    }
}

/** ③ list query=Q → 跨包搜索，按包分组，组内只放命中的 tool。 */
private fun listByQuery(
    packages: List<ResolvedPackage>,
    query: String,
) = buildJsonObject {
    val q = query.lowercase()
    var count = 0
    val groups = buildJsonArray {
        packages.filter { it.enabled }.forEach { pkg ->
            val idHits = mutableListOf<ToolCatalogEntry>()
            val descHits = mutableListOf<ToolCatalogEntry>()
            pkg.entries.forEach { entry ->
                val idMatch = entry.id.lowercase().contains(q) ||
                    entry.name.lowercase().contains(q)
                val descMatch = entry.description.lowercase().contains(q) ||
                    entry.summary.lowercase().contains(q)
                when {
                    idMatch -> idHits += entry
                    descMatch -> descHits += entry
                }
            }
            if (idHits.isNotEmpty() || descHits.isNotEmpty()) {
                count += idHits.size + descHits.size
                add(buildJsonObject {
                    put("package", pkg.id)
                    put("name", pkg.name)
                    put("tools", buildJsonArray { idHits.forEach { add(it.toBriefJson()) } })
                    if (descHits.isNotEmpty()) {
                        put("matched_by_description_only", buildJsonArray {
                            descHits.forEach { add(it.toBriefJson()) }
                        })
                    }
                })
            }
        }
    }
    put("action", "list")
    put("query", query)
    put("count", count)
    put("packages", groups)
}

/** ④ enable ids=[...] → 写入租借集合 + 回 parameters。 */
private suspend fun enableTools(
    ids: List<String>,
    packages: List<ResolvedPackage>,
    catalog: List<ToolCatalogEntry>,
    pool: Map<String, Tool>,
    onToggle: suspend (ToolManageOp) -> Unit,
): kotlinx.serialization.json.JsonObject {
    val enabledById = mutableMapOf<String, String>() // toolId -> packageId
    packages.filter { it.enabled }.forEach { pkg ->
        pkg.entries.forEach { enabledById.putIfAbsent(it.id, pkg.id) }
    }
    val catalogById = catalog.associateBy { it.id }

    val loaded = mutableListOf<kotlinx.serialization.json.JsonObject>()
    val unknown = mutableListOf<String>()
    val notReady = mutableListOf<kotlinx.serialization.json.JsonObject>()

    for (id in ids) {
        val entry = catalogById[id]
        val pkgId = enabledById[id]
        if (entry == null || pkgId == null || !entry.loadable) {
            unknown += id
            continue
        }
        val tool = pool[id]
        if (tool == null) {
            // 目录里有、但此刻构造不出来：工作区未就绪 / MCP server 未挂载。
            // 不写租借集合（写了也调不通），明确回报原因，别谎报成功。
            notReady += buildJsonObject {
                put("id", id)
                put("package", pkgId)
                put("reason", if (entry.source == ToolManageSource.WORKSPACE) {
                    "workspace not ready"
                } else if (entry.source == ToolManageSource.MCP) {
                    "its MCP server is not mounted"
                } else {
                    "not constructible right now"
                })
            }
            continue
        }
        onToggle(ToolManageOp.SetEnabled(entry.source, entry.id, true))
        loaded += buildJsonObject {
            put("id", entry.id)
            put("package", pkgId)
            tool.parameters()?.let { schema ->
                put("parameters", Json.encodeToJsonElement(InputSchema.serializer(), schema))
            }
        }
    }

    return buildJsonObject {
        put("action", "enable")
        put("loaded", buildJsonArray { loaded.forEach { add(it) } })
        if (notReady.isNotEmpty()) {
            put("not_ready", buildJsonArray { notReady.forEach { add(it) } })
        }
        if (unknown.isNotEmpty()) {
            put("unknown", buildJsonArray { unknown.forEach { add(it) } })
        }
    }
}

private fun truncateDesc(text: String): String {
    val flat = text.replace("\n", " ").trim()
    return if (flat.length <= TOOL_MANAGE_DESC_LIMIT) flat else flat.take(TOOL_MANAGE_DESC_LIMIT) + "…"
}

private fun ToolCatalogEntry.toBriefJson() = buildJsonObject {
    put("id", id)
    put("description", truncateDesc(description))
}

private fun ToolCatalogEntry.toDetailJson(pool: Map<String, Tool>) = buildJsonObject {
    put("id", id)
    put("description", truncateDesc(description))
    pool[id]?.parameters()?.let { schema ->
        put("parameters", Json.encodeToJsonElement(InputSchema.serializer(), schema))
    }
}

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
            // id 统一为「模型调用名」Tool.name（2026-09-20）：MCP 实际调用名是 mcp__server__tool。
            val legacyKey = "${serverId}/${tool.name}"
            val key = "mcp__${serverName}__${tool.name}"
            out += ToolCatalogEntry(
                source = ToolManageSource.MCP,
                id = key,
                name = "${serverName.ifBlank { serverId.toString() }}::${tool.name}",
                summary = (tool.description?.take(160)?.replace("\n", " ")?.ifBlank { null }
                    ?: "MCP tool '${tool.name}' on server '$serverName'."),
                description = tool.description ?: "MCP tool '${tool.name}'.",
                // 启用 = server 已挂载 且 server 未被 settings 关掉 且 该工具在生效集合里。
                enabled = enable && mounted && legacyKey in ctx.effectiveMcpTools,
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
        summary = "This tool: browse packages, and load tools for this conversation.",
        description = "Browse tool packages and load tools on demand. Loaded tools become callable " +
            "immediately; their schemas come back in the tool result. tool_manage itself cannot be disabled.",
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
        LocalToolOption.NotifyToast,
        serialName = "notify_toast",
        title = "Screen Toast",
        summary = "Show a non-blocking floating toast on screen (notify_toast).",
        description = "Pop a short floating banner on the user's screen. Non-blocking: it does not " +
            "wait for any answer and does not pause generation. Use ask_user instead when you " +
            "actually need a reply from the user.",
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
