package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryGraphLink
import me.rerere.rikkahub.data.model.MemoryGraphMatchEligibility
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.model.MemoryLink
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.data.repository.MemoryRepository

/** 记忆作用域: 助手隔离 / 全局共享, wireName 同时用于工具入参与 <memories> 标注（legacy 专用） */
enum class MemoryToolScope(val wireName: String) {
    ASSISTANT("assistant"),
    GLOBAL("global");

    companion object {
        fun fromWire(value: String?): MemoryToolScope? =
            entries.firstOrNull { it.wireName == value }
    }
}

/** 记忆类型: legacy = 传统全量注入记忆（<memories> 块）; graph = 记忆图（<memory_graph> 块） */
enum class MemoryToolType(val wireName: String) {
    LEGACY("legacy"),
    GRAPH("graph");

    companion object {
        fun fromWire(value: String?): MemoryToolType =
            entries.firstOrNull { it.wireName == value } ?: LEGACY
    }
}

/**
 * 一张可写记忆图（方案 2026-08-07 多图体系）。
 *
 * [id] 是 canonical graph id（= 节点表 scope），所有回调只吃它；
 * [slug] 只出现在 schema enum 与返回 payload 里，供模型引用。
 */
data class MemoryToolGraph(
    val id: String,
    val slug: String,
    val name: String,
)

/**
 * `list_graphs` 的返回项（阶段二 §2.6）：在 [MemoryToolGraph] 基础上补
 * 描述 / 节点数 / 挂载态，让模型能按描述挑图并看懂挂载结果。
 */
data class MemoryToolGraphInfo(
    val id: String,
    val slug: String,
    val name: String,
    val description: String = "",
    val nodeCount: Int = 0,
    /** 是否挂载到当前对话（本轮生效集合） */
    val attached: Boolean = false,
    /** 本轮是否可写 */
    val writable: Boolean = false,
)

/**
 * AI 对记忆图挂载配置的写回事件（阶段二 §2.6）。
 *
 * GenerationHandler 只负责「更新本轮内存集合 + 把事件交给 ChatService」；
 * 持久化（写会话 bindings、首次以助手值做种子物化）由 ChatService 的
 * [me.rerere.rikkahub.service.ChatService] 回调完成，生成器不碰会话存储。
 */
sealed interface MemoryGraphManageOp {
    /** 挂载 / 建图后挂载：graphId + 是否可写 */
    data class Attach(val graphId: String, val writable: Boolean) : MemoryGraphManageOp

    /** 卸载 */
    data class Detach(val graphId: String) : MemoryGraphManageOp
}

const val MEMORY_TOOL_NAME = "memory_tool"

/** graph query_nodes 返回上限与正文截断（防止一次列出整图打爆上下文）。 */
private const val GRAPH_QUERY_NODES_DEFAULT_LIMIT = 20
private const val GRAPH_QUERY_NODES_MAX_LIMIT = 100
private const val GRAPH_QUERY_NODES_CONTENT_CHARS = 200

/**
 * 记忆工具: 单工具 + memory_type（legacy / graph，默认 legacy 向后兼容）。
 *
 * - legacy：传统记忆表（create/edit/delete/link/query_links/unlink），用 `scope` 参数（assistant/global 枚举），
 *   id 取自 <memories> 块（Int）；
 * - graph：独立记忆图（create/edit/delete/link/update_link/query_nodes/query_links/unlink），
 *   用 **`graph` 参数**（图 id / slug / assistant / global 别名）寻址，id 取自 <memory_graph> 块（Long）。
 *
 * 为什么 graph 侧不复用 `scope`（review2 §二.A）：多图之后 graph 的取值是任意 slug，
 * 与 legacy 的 assistant/global 枚举撞在同一个 key 上，模型必然混用
 * （"assistant" 到底是 legacy 助手记忆还是助手图？）；而且 multiScope 的计算会因为 graph 侧变多
 * 而把 `scope` 变成 legacy 的 required，白改 legacy 行为。故 graph 侧独立参数，
 * `scope` 在 graph 分支仅作向后兼容别名读取（老会话的 tool call 不能失效）。
 *
 * @param scopes 允许编辑传统记忆的作用域, 为空时表示 legacy 编辑全关
 * @param graphsProvider **实时**返回当前可写图列表。
 *   必须是 lambda 而非快照 list：tool 在 `for (stepIndex in 0 until maxSteps)` 循环体内逐 step 重建，
 *   但 `assistant`/`conversation` 是捕获的入参，AI 中途挂载新图后重建 tool 读到的仍是陈旧对象，
 *   新图永远进不了可写集合 → 建完图立刻被拒（review2 §一.2）。
 * @param graphResolve 把模型给的 ref（id / slug / 别名）解析成 canonical 图；解析不出返回 null。
 *   schema enum 在请求开始就固定了，故 execute 侧宽松接受任意字符串，再查注册表鉴权。
 * @param graphListEnabled 有已绑定图（或允许管理）时 true：把 `list_graphs` 暴露给模型。
 *   它是 memory_tool 的构建条件之一——用户只读挂载了图（未开可写）时 AI 也应能查看。
 * @param graphManageEnabled 允许 AI 自管理记忆图（`allowManageMemoryGraphs`）：
 *   暴露 `create_graph` / `attach_graph`。默认关（阶段二 §2.6 前置条件）。
 */
fun buildMemoryTool(
    scopes: List<MemoryToolScope>,
    onCreation: suspend (MemoryToolScope, String) -> AssistantMemory,
    onUpdate: suspend (MemoryToolScope, Int, String) -> AssistantMemory,
    onDelete: suspend (MemoryToolScope, Int) -> Unit,
    onLink: suspend (MemoryToolScope, Int, Int, String, Float, String) -> MemoryLink,
    onQueryLinks: suspend (MemoryToolScope, Int?) -> List<MemoryLink>,
    onUnlink: suspend (MemoryToolScope, Long) -> Unit,
    graphsProvider: () -> List<MemoryToolGraph>,
    graphResolve: suspend (String) -> MemoryToolGraph?,
    graphOnCreate: suspend (String, String, String, Int?) -> MemoryGraphNode,
    /** @param matchEligibility null = 保持原值 */
    graphOnUpdate: suspend (String, Long, String, String, Int?) -> MemoryGraphNode,
    graphOnDelete: suspend (String, Long) -> Unit,
    graphOnLink: suspend (String, Long, Long, String, Float, String) -> MemoryGraphLink,
    graphOnQueryLinks: suspend (String, Long?) -> List<MemoryGraphLink>,
    graphOnUnlink: suspend (String, Long) -> Unit,
    /** 就地改边：type/weight/description 任一为 null 表示保持原值（免去 unlink 再重连）。 */
    graphOnUpdateLink: suspend (String, Long, String?, Float?, String?) -> MemoryGraphLink,
    /** 查节点：query 为空则列出该图全部节点，否则关键词检索；limit 为返回上限。 */
    graphOnQueryNodes: suspend (String, String?, Int) -> List<MemoryGraphNode>,
    graphListEnabled: Boolean = false,
    graphManageEnabled: Boolean = false,
    /** `list_graphs`：返回全部图信息（含 attached/writable 态），生成器负责按权限过滤。 */
    graphOnListGraphs: suspend () -> List<MemoryToolGraphInfo> = { emptyList() },
    /** `create_graph`：建图（createdBy=AI）并挂到当前对话；返回 null 表示失败。 */
    graphOnCreateGraph: suspend (String, String, String?) -> MemoryToolGraph? = { _, _, _ -> null },
    /** `attach_graph`：挂载/卸载到当前对话；返回 null = 成功，非 null = 失败原因。 */
    graphOnAttachGraph: suspend (String, Boolean, Boolean) -> String? = { _, _, _ -> "attach_graph is unavailable" },
): List<Tool> {
    val initialGraphs = graphsProvider()
    if (scopes.isEmpty() && initialGraphs.isEmpty() && !graphListEnabled && !graphManageEnabled) return emptyList()
    // legacy 专属：graph 侧图数变多不应该把 scope 变成 legacy 的 required
    val legacyMultiScope = scopes.size > 1
    val graphEnabled = initialGraphs.isNotEmpty()
    val graphManageActions = graphManageEnabled
    return listOf(
        Tool(
            name = MEMORY_TOOL_NAME,
            description = buildString {
                append("Store long-term facts and relationships")
                if (legacyMultiScope) {
                    append(" in `assistant` or shared `global` memory.")
                } else {
                    append(
                        when (scopes.firstOrNull()) {
                            MemoryToolScope.ASSISTANT -> " in this assistant's memory."
                            MemoryToolScope.GLOBAL -> " in shared global memory."
                            null -> "."
                        }
                    )
                }
                appendLine()
                appendLine("Types: `legacy` uses <memories>; `graph` uses <memory_graph> (lines `id title: content`); default is `legacy`.")
                appendLine("create: `content` (graph also `title`); edit: `id`+`content` (graph also `title`); delete: `id`.")
                appendLine("link: `source_id`+`target_id`; query_links: optional `memory_id`/`node_id`; unlink: `link_id`.")
                appendLine("update_link (graph only): `link_id` + any of `type`/`weight`/`description`; omitted fields keep their current value.")
                appendLine("query_nodes (graph only): optional `query` keyword (omit to list all) + optional `limit` (default 20).")
                appendLine(
                    "graph create/edit: optional `match_eligibility` (\"always\" default | \"gated\"). " +
                        "Gated nodes are low-frequency details locked out of keyword/semantic matching until " +
                        "their connected context activates them (auto: single neighbor hit, or activated neighbor " +
                        "weight sum reaching the configurable unlock threshold; direct title mention also unlocks). " +
                        "Use it for one-off items/events tied to a specific story. " +
                        "You never need to create unlock edges; unlocking is automatic."
                )
                if (graphListEnabled) {
                    appendLine(
                        "list_graphs: list memory graphs with id/slug/name/description/node_count/attached/writable; " +
                            "use it before creating or attaching graphs."
                    )
                }
                if (graphManageActions) {
                    appendLine(
                        "create_graph: `name`+`description` (+optional `emoji`) creates a new graph and attaches it to this conversation as writable." +
                            "attach_graph: `graph` (id or slug) + `writable` (bool, default false) attaches, or `detach` (bool) detaches, from this conversation."
                    )
                }
                if (graphEnabled) {
                    appendLine(
                        "For memory_type=graph, target a graph with `graph` (id or slug): " +
                            initialGraphs.joinToString(", ") { "${it.slug} (${it.name})" } + "."
                    )
                }
                appendLine("Use query_nodes to look up ids of memories not present in the injected block before editing/linking them.")
                append("Use ids from the memory block. Prefer editing duplicates. ")
                append("Do not quote stored memory back to the user unprompted.")
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add("create")
                                    add("edit")
                                    add("delete")
                                    add("link")
                                    add("update_link")
                                    add("query_nodes")
                                    add("query_links")
                                    add("unlink")
                                    if (graphListEnabled) add("list_graphs")
                                    if (graphManageActions) {
                                        add("create_graph")
                                        add("attach_graph")
                                    }
                                }
                            )
                        })
                        put("memory_type", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("legacy")
                                add("graph")
                            })
                            put("description", "Which memory store to act on. Default `legacy`.")
                        })
                        if (legacyMultiScope) {
                            put("scope", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        scopes.forEach { add(it.wireName) }
                                    }
                                )
                                put("description", "legacy only: which memory store to act on.")
                            })
                        }
                        if (graphEnabled || graphManageActions) {
                            put("graph", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        initialGraphs.forEach { add(it.slug) }
                                        add("assistant")
                                        add("global")
                                    }
                                )
                                put(
                                    "description",
                                    "graph only: which memory graph to act on (id or slug). Writable: " +
                                        initialGraphs.joinToString(", ") { "${it.slug}=${it.name}" } +
                                        ". Aliases: assistant, global. Defaults to " +
                                        (initialGraphs.firstOrNull()?.slug ?: "the first writable graph") + "."
                                )
                            })
                        }
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "Target record id, for edit/delete (legacy: memory id from <memories>; graph: node id from <memory_graph>).")
                        })
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "Node title, required for graph create/edit.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Record text, for create/edit.")
                        })
                        put("match_eligibility", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add("always")
                                    add("gated")
                                }
                            )
                            put(
                                "description",
                                "graph only: \"always\" (default) stays in the matchable pool; " +
                                    "\"gated\" locks the node out of keyword/semantic matching until its connected " +
                                    "context activates it (unlocking is automatic, no unlock edges needed). " +
                                    "For edit, omit to keep current value."
                            )
                        })
                        put("source_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "Source record id, for link.")
                        })
                        put("target_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "Target record id, for link.")
                        })
                        put("type", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Link type (link) or link type filter (query_links). One of: " +
                                    (MemoryRepository.LINK_TYPES + MemoryGraphRepository.LINK_TYPES).distinct().joinToString("/") + ". Default related."
                            )
                        })
                        put("weight", buildJsonObject {
                            put("type", "number")
                            put("description", "Link strength 0..1 (default 0.7 on link). For update_link, omit to keep current value.")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Explanation of the relationship (link/update_link). For update_link, omit to keep current value; pass \"\" to clear.")
                        })
                        put("memory_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "legacy: filter query_links to links involving this memory id; omit to list all links in scope.")
                        })
                        put("node_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "graph: filter query_links to links involving this node id; omit to list all links in the graph.")
                        })
                        put("link_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "Link id as returned by query_links, for unlink/update_link.")
                        })
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "graph query_nodes: keyword to match node title/content; omit to list all nodes in the graph.")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "graph query_nodes: max nodes to return, default 20, capped at 100.")
                        })
                        if (graphManageActions) {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "create_graph only: name of the new memory graph (required).")
                            })
                            put("emoji", buildJsonObject {
                                put("type", "string")
                                put("description", "create_graph only: optional emoji for the new memory graph.")
                            })
                            put("writable", buildJsonObject {
                                put("type", "boolean")
                                put("description", "attach_graph only: whether this conversation may edit the graph (default false).")
                            })
                            put("detach", buildJsonObject {
                                put("type", "boolean")
                                put("description", "attach_graph only: set true to detach the graph from this conversation instead of attaching.")
                            })
                        }
                    },
                    required = buildList<String> {
                        add("action")
                        if (legacyMultiScope) add("scope")
                    }
                )
            },
            execute = { raw ->
                val params = raw.jsonObject
                val memoryType = MemoryToolType.fromWire(params["memory_type"]?.jsonPrimitive?.contentOrNull)
                val requestedScope = params["scope"]?.jsonPrimitive?.contentOrNull
                val payload = when (memoryType) {
                    MemoryToolType.GRAPH -> {
                        val action = params["action"]?.jsonPrimitive?.contentOrNull
                        when (action) {
                            // 阶段二自管理动作：不需要可写目标图，在可写鉴权之前处理
                            "list_graphs" -> buildJsonObject {
                                val graphs = graphOnListGraphs()
                                put("memory_type", "graph")
                                put("count", graphs.size)
                                put("graphs", buildJsonArray {
                                    graphs.forEach { add(graphInfoPayload(it)) }
                                })
                            }

                            "create_graph" -> {
                                if (!graphManageActions) {
                                    errorPayload("create_graph is not enabled by the user")
                                } else {
                                    val name = params["name"]?.jsonPrimitive?.contentOrNull
                                    val description = params["description"]?.jsonPrimitive?.contentOrNull
                                    when {
                                        name.isNullOrBlank() -> errorPayload("name is required for create_graph")
                                        description.isNullOrBlank() -> errorPayload("description is required for create_graph")
                                        else -> runCatching {
                                            graphOnCreateGraph(
                                                name,
                                                description,
                                                params["emoji"]?.jsonPrimitive?.contentOrNull,
                                            )
                                        }.fold(
                                            onSuccess = { created ->
                                                if (created == null) {
                                                    errorPayload("create_graph failed")
                                                } else {
                                                    buildJsonObject {
                                                        put("success", true)
                                                        put("graph", created.slug)
                                                        put("id", created.id)
                                                        put("name", created.name)
                                                        // 建图即挂载到当前对话（阶段二 §2.6），writable=true
                                                        put("attached", true)
                                                        put("writable", true)
                                                    }
                                                }
                                            },
                                            onFailure = { errorPayload(it.message ?: "create_graph failed") },
                                        )
                                    }
                                }
                            }

                            "attach_graph" -> {
                                if (!graphManageActions) {
                                    errorPayload("attach_graph is not enabled by the user")
                                } else {
                                    val graphRef = params["graph"]?.jsonPrimitive?.contentOrNull
                                        ?.takeIf { it.isNotBlank() }
                                        ?: requestedScope?.takeIf { it.isNotBlank() }
                                    if (graphRef == null) {
                                        errorPayload("graph is required for attach_graph")
                                    } else {
                                        val target = graphResolve(graphRef)
                                        if (target == null) {
                                            errorPayload("unknown graph: $graphRef")
                                        } else {
                                            val writable = params["writable"]?.jsonPrimitive?.contentOrNull
                                                ?.toBooleanStrictOrNull() ?: false
                                            val detach = params["detach"]?.jsonPrimitive?.contentOrNull
                                                ?.toBooleanStrictOrNull() ?: false
                                            val error = graphOnAttachGraph(target.id, writable, detach)
                                            if (error == null) {
                                                buildJsonObject {
                                                    put("success", true)
                                                    put("graph", target.slug)
                                                    put("attached", !detach)
                                                    put("writable", !detach && writable)
                                                }
                                            } else {
                                                errorPayload(error)
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                        // 实时读取，别用捕获快照：AI 中途挂载的新图必须立刻可写
                        val writableGraphs = graphsProvider()
                        // `graph` 为正式参数，`scope` 作为老会话 tool call 的兼容别名
                        val graphRef = params["graph"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: requestedScope?.takeIf { it.isNotBlank() }
                        val target = if (graphRef == null) {
                            writableGraphs.firstOrNull()
                        } else {
                            graphResolve(graphRef)
                        }
                        when {
                            writableGraphs.isEmpty() -> errorPayload(
                                "graph memory editing is not enabled by the user"
                            )

                            target == null -> errorPayload(
                                "unknown graph: $graphRef, must be one of " +
                                    writableGraphs.joinToString { it.slug } + " (or aliases assistant/global)"
                            )

                            writableGraphs.none { it.id == target.id } -> errorPayload(
                                "graph ${target.slug} is not writable in this conversation, writable: [" +
                                    writableGraphs.joinToString { it.slug } + "]"
                            )

                            else -> {
                                val graphId = target.id
                                val action = params["action"]?.jsonPrimitive?.contentOrNull
                                when (action) {
                                    "create" -> {
                                        val title = params["title"]?.jsonPrimitive?.contentOrNull
                                        val content = params["content"]?.jsonPrimitive?.contentOrNull
                                        val matchEligibility = MemoryGraphMatchEligibility.fromWire(
                                            params["match_eligibility"]?.jsonPrimitive?.contentOrNull
                                        )
                                        when {
                                            title.isNullOrBlank() -> errorPayload("title is required for graph create")
                                            content.isNullOrBlank() -> errorPayload("content is required for graph create")
                                            else -> graphNodePayload(
                                                target,
                                                graphOnCreate(
                                                    graphId,
                                                    title,
                                                    content,
                                                    // 新建默认 always；显式 gated 才传 GATED
                                                    if (matchEligibility == MemoryGraphMatchEligibility.GATED) {
                                                        MemoryGraphMatchEligibility.GATED
                                                    } else {
                                                        null
                                                    },
                                                )
                                            )
                                        }
                                    }

                                    "edit" -> {
                                        val id = params["id"]?.jsonPrimitive?.longOrNull
                                        val title = params["title"]?.jsonPrimitive?.contentOrNull
                                        val content = params["content"]?.jsonPrimitive?.contentOrNull
                                        val matchEligibility = params["match_eligibility"]?.jsonPrimitive?.contentOrNull
                                            ?.let { MemoryGraphMatchEligibility.fromWire(it) }
                                        when {
                                            id == null -> errorPayload("id is required for graph edit")
                                            title.isNullOrBlank() && content.isNullOrBlank() && matchEligibility == null ->
                                                errorPayload("title, content or match_eligibility is required for graph edit")

                                            else -> graphNodePayload(
                                                target,
                                                graphOnUpdate(graphId, id, title.orEmpty(), content.orEmpty(), matchEligibility)
                                            )
                                        }
                                    }

                                    "delete" -> {
                                        val id = params["id"]?.jsonPrimitive?.longOrNull
                                        if (id == null) {
                                            errorPayload("id is required for graph delete")
                                        } else {
                                            graphOnDelete(graphId, id)
                                            buildJsonObject {
                                                put("success", true)
                                                put("graph", target.slug)
                                                put("scope", target.slug)
                                                put("memory_type", "graph")
                                                put("id", id)
                                            }
                                        }
                                    }

                                    "link" -> {
                                        val sourceId = params["source_id"]?.jsonPrimitive?.longOrNull
                                        val targetId = params["target_id"]?.jsonPrimitive?.longOrNull
                                        when {
                                            sourceId == null || targetId == null ->
                                                errorPayload("source_id and target_id are required for graph link")

                                            else -> {
                                                val type = params["type"]?.jsonPrimitive?.contentOrNull ?: "related"
                                                val weight = params["weight"]?.jsonPrimitive?.contentOrNull
                                                    ?.toFloatOrNull() ?: 0.7f
                                                val description = params["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                                graphLinkPayload(
                                                    graphOnLink(graphId, sourceId, targetId, type, weight, description)
                                                )
                                            }
                                        }
                                    }

                                    // 就地改边：免去"unlink 再重连"（会丢 id、丢 created_at）。
                                    // 省略的字段保持原值；description 传 "" 表示清空。
                                    "update_link" -> {
                                        val linkId = params["link_id"]?.jsonPrimitive?.longOrNull
                                            ?: params["link_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                        val type = params["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        val weight = params["weight"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                                        // contentOrNull 已能区分"未传"(null) 与"传空串"(清空 description)
                                        val description = params["description"]?.jsonPrimitive?.contentOrNull
                                        when {
                                            linkId == null -> errorPayload("link_id is required for graph update_link")
                                            type == null && weight == null && description == null ->
                                                errorPayload("at least one of type/weight/description is required for graph update_link")

                                            else -> runCatching {
                                                graphOnUpdateLink(graphId, linkId, type, weight, description)
                                            }.fold(
                                                onSuccess = { graphLinkPayload(it) },
                                                onFailure = { errorPayload(it.message ?: "update_link failed") },
                                            )
                                        }
                                    }

                                    "query_links" -> {
                                        val nodeId = params["node_id"]?.jsonPrimitive?.longOrNull
                                            ?: params["memory_id"]?.jsonPrimitive?.longOrNull
                                        val typeFilter = params["type"]?.jsonPrimitive?.contentOrNull
                                        val links = graphOnQueryLinks(graphId, nodeId)
                                            .filter { typeFilter == null || it.type == typeFilter }
                                        buildJsonObject {
                                            put("graph", target.slug)
                                            put("scope", target.slug)
                                            put("memory_type", "graph")
                                            put("links", buildJsonArray {
                                                links.forEach { add(graphLinkPayload(it)) }
                                            })
                                        }
                                    }

                                    // 主动查节点：注入块只含检索命中的子图，模型需要按需捞出其余节点的 id。
                                    "query_nodes" -> {
                                        val query = params["query"]?.jsonPrimitive?.contentOrNull
                                            ?.takeIf { it.isNotBlank() }
                                        val limit = (params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                                            ?: GRAPH_QUERY_NODES_DEFAULT_LIMIT)
                                            .coerceIn(1, GRAPH_QUERY_NODES_MAX_LIMIT)
                                        val nodes = graphOnQueryNodes(graphId, query, limit)
                                        buildJsonObject {
                                            put("graph", target.slug)
                                            put("scope", target.slug)
                                            put("memory_type", "graph")
                                            put("query", query ?: "")
                                            put("count", nodes.size)
                                            put("nodes", buildJsonArray {
                                                nodes.forEach { add(graphNodeBriefPayload(it)) }
                                            })
                                        }
                                    }

                                    "unlink" -> {
                                        val linkId = params["link_id"]?.jsonPrimitive?.longOrNull
                                            ?: params["link_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                        if (linkId == null) {
                                            errorPayload("link_id is required for graph unlink")
                                        } else {
                                            graphOnUnlink(graphId, linkId)
                                            buildJsonObject {
                                                put("success", true)
                                                put("graph", target.slug)
                                                put("scope", target.slug)
                                                put("memory_type", "graph")
                                                put("link_id", linkId)
                                            }
                                        }
                                    }

                                    else -> errorPayload(
                                        "unknown action: $action, must be one of [create, edit, delete, link, update_link, query_nodes, query_links, unlink, list_graphs, create_graph, attach_graph]"
                                    )
                                }
                            }
                        }
                    }
                        }
                    }

                    MemoryToolType.LEGACY -> {
                        val scope = if (requestedScope == null) {
                            // 单作用域时允许省略; 多作用域缺省则退回第一个可用作用域
                            scopes.firstOrNull()
                        } else {
                            MemoryToolScope.fromWire(requestedScope)
                        }
                        when {
                            scopes.isEmpty() -> errorPayload(
                                "legacy memory editing is not enabled by the user"
                            )

                            scope == null -> errorPayload(
                                "unknown scope: $requestedScope, must be one of ${scopes.joinToString { it.wireName }}"
                            )

                            scope !in scopes -> errorPayload(
                                "scope ${scope.wireName} is not enabled by the user, available: ${scopes.joinToString { it.wireName }}"
                            )

                            else -> {
                                val action = params["action"]?.jsonPrimitive?.contentOrNull
                                when (action) {
                                    "create" -> {
                                        val content = params["content"]?.jsonPrimitive?.contentOrNull
                                        if (content.isNullOrBlank()) {
                                            errorPayload("content is required for create")
                                        } else {
                                            memoryPayload(scope, onCreation(scope, content))
                                        }
                                    }

                                    "edit" -> {
                                        val id = params["id"]?.jsonPrimitive?.intOrNull
                                        val content = params["content"]?.jsonPrimitive?.contentOrNull
                                        when {
                                            id == null -> errorPayload("id is required for edit")
                                            content.isNullOrBlank() -> errorPayload("content is required for edit")
                                            else -> memoryPayload(scope, onUpdate(scope, id, content))
                                        }
                                    }

                                    "delete" -> {
                                        val id = params["id"]?.jsonPrimitive?.intOrNull
                                        if (id == null) {
                                            errorPayload("id is required for delete")
                                        } else {
                                            onDelete(scope, id)
                                            buildJsonObject {
                                                put("success", true)
                                                put("scope", scope.wireName)
                                                put("memory_type", "legacy")
                                                put("id", id)
                                            }
                                        }
                                    }

                                    "link" -> {
                                        val sourceId = params["source_id"]?.jsonPrimitive?.intOrNull
                                        val targetId = params["target_id"]?.jsonPrimitive?.intOrNull
                                        when {
                                            sourceId == null || targetId == null ->
                                                errorPayload("source_id and target_id are required for link")

                                            else -> {
                                                val type = params["type"]?.jsonPrimitive?.contentOrNull ?: "related"
                                                val weight = params["weight"]?.jsonPrimitive?.contentOrNull
                                                    ?.toFloatOrNull() ?: 0.7f
                                                val description = params["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                                linkPayload(onLink(scope, sourceId, targetId, type, weight, description))
                                            }
                                        }
                                    }

                                    "query_links" -> {
                                        val memoryId = params["memory_id"]?.jsonPrimitive?.intOrNull
                                        val typeFilter = params["type"]?.jsonPrimitive?.contentOrNull
                                        val links = onQueryLinks(scope, memoryId)
                                            .filter { typeFilter == null || it.type == typeFilter }
                                        buildJsonObject {
                                            put("scope", scope.wireName)
                                            put("memory_type", "legacy")
                                            put("links", buildJsonArray {
                                                links.forEach { add(linkPayload(it)) }
                                            })
                                        }
                                    }

                                    "unlink" -> {
                                        val linkId = params["link_id"]?.jsonPrimitive?.longOrNull
                                            ?: params["link_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                        if (linkId == null) {
                                            errorPayload("link_id is required for unlink")
                                        } else {
                                            onUnlink(scope, linkId)
                                            buildJsonObject {
                                                put("success", true)
                                                put("scope", scope.wireName)
                                                put("memory_type", "legacy")
                                                put("link_id", linkId)
                                            }
                                        }
                                    }

                                    else -> errorPayload(
                                        if (action == "update_link" || action == "query_nodes") {
                                            "$action is only available for memory_type=graph"
                                        } else {
                                            "unknown action: $action, must be one of [create, edit, delete, link, query_links, unlink]"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    )
}

private fun errorPayload(message: String) = buildJsonObject {
    put("error", message)
}

private fun memoryPayload(scope: MemoryToolScope, memory: AssistantMemory) = buildJsonObject {
    put("scope", scope.wireName)
    put("memory_type", "legacy")
    put("id", memory.id)
    put("content", memory.content)
}

private fun linkPayload(link: MemoryLink) = buildJsonObject {
    put("scope", link.scope)
    put("memory_type", "legacy")
    put("id", link.id)
    put("source_id", link.sourceId)
    put("source_content", link.sourceContent)
    put("target_id", link.targetId)
    put("target_content", link.targetContent)
    put("type", link.type)
    put("weight", link.weight)
    put("description", link.description)
}

private fun graphInfoPayload(graph: MemoryToolGraphInfo) = buildJsonObject {
    put("id", graph.id)
    put("slug", graph.slug)
    put("name", graph.name)
    put("description", graph.description)
    put("node_count", graph.nodeCount)
    put("attached", graph.attached)
    put("writable", graph.writable)
}

private fun graphNodePayload(graph: MemoryToolGraph, node: MemoryGraphNode) = buildJsonObject {
    put("graph", graph.slug)
    put("scope", graph.slug)
    put("memory_type", "graph")
    put("id", node.id)
    put("title", node.title)
    put("content", node.content)
    put("match_eligibility", MemoryGraphMatchEligibility.wire(node.matchEligibility) ?: "always")
}

/**
 * query_nodes 的列表项：正文截断，避免一次列出整图把上下文撑爆
 * （拿到 id 后可用注入块或后续 action 取全文）。
 */
private fun graphNodeBriefPayload(node: MemoryGraphNode) = buildJsonObject {
    put("id", node.id)
    put("title", node.title)
    put("match_eligibility", MemoryGraphMatchEligibility.wire(node.matchEligibility) ?: "always")
    val content = node.content
    if (content.length > GRAPH_QUERY_NODES_CONTENT_CHARS) {
        put("content", content.take(GRAPH_QUERY_NODES_CONTENT_CHARS) + "…")
        put("truncated", true)
    } else {
        put("content", content)
    }
}

private fun graphLinkPayload(link: MemoryGraphLink) = buildJsonObject {
    put("scope", link.scope)
    put("memory_type", "graph")
    put("id", link.id)
    put("source_id", link.sourceId)
    put("source_title", link.sourceTitle)
    put("target_id", link.targetId)
    put("target_title", link.targetTitle)
    put("type", link.type)
    put("weight", link.weight)
    put("description", link.description)
}
