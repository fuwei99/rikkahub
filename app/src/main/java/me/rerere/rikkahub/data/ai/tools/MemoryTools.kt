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
    graphOnCreate: suspend (String, String, String) -> MemoryGraphNode,
    graphOnUpdate: suspend (String, Long, String, String) -> MemoryGraphNode,
    graphOnDelete: suspend (String, Long) -> Unit,
    graphOnLink: suspend (String, Long, Long, String, Float, String) -> MemoryGraphLink,
    graphOnQueryLinks: suspend (String, Long?) -> List<MemoryGraphLink>,
    graphOnUnlink: suspend (String, Long) -> Unit,
    /** 就地改边：type/weight/description 任一为 null 表示保持原值（免去 unlink 再重连）。 */
    graphOnUpdateLink: suspend (String, Long, String?, Float?, String?) -> MemoryGraphLink,
    /** 查节点：query 为空则列出该图全部节点，否则关键词检索；limit 为返回上限。 */
    graphOnQueryNodes: suspend (String, String?, Int) -> List<MemoryGraphNode>,
): List<Tool> {
    val initialGraphs = graphsProvider()
    if (scopes.isEmpty() && initialGraphs.isEmpty()) return emptyList()
    // legacy 专属：graph 侧图数变多不应该把 scope 变成 legacy 的 required
    val legacyMultiScope = scopes.size > 1
    val graphEnabled = initialGraphs.isNotEmpty()
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
                        if (graphEnabled) {
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
                                        when {
                                            title.isNullOrBlank() -> errorPayload("title is required for graph create")
                                            content.isNullOrBlank() -> errorPayload("content is required for graph create")
                                            else -> graphNodePayload(target, graphOnCreate(graphId, title, content))
                                        }
                                    }

                                    "edit" -> {
                                        val id = params["id"]?.jsonPrimitive?.longOrNull
                                        val title = params["title"]?.jsonPrimitive?.contentOrNull
                                        val content = params["content"]?.jsonPrimitive?.contentOrNull
                                        when {
                                            id == null -> errorPayload("id is required for graph edit")
                                            title.isNullOrBlank() && content.isNullOrBlank() ->
                                                errorPayload("title or content is required for graph edit")

                                            else -> graphNodePayload(
                                                target,
                                                graphOnUpdate(graphId, id, title.orEmpty(), content.orEmpty())
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
                                        "unknown action: $action, must be one of [create, edit, delete, link, update_link, query_nodes, query_links, unlink]"
                                    )
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

private fun graphNodePayload(graph: MemoryToolGraph, node: MemoryGraphNode) = buildJsonObject {
    put("graph", graph.slug)
    put("scope", graph.slug)
    put("memory_type", "graph")
    put("id", node.id)
    put("title", node.title)
    put("content", node.content)
}

/**
 * query_nodes 的列表项：正文截断，避免一次列出整图把上下文撑爆
 * （拿到 id 后可用注入块或后续 action 取全文）。
 */
private fun graphNodeBriefPayload(node: MemoryGraphNode) = buildJsonObject {
    put("id", node.id)
    put("title", node.title)
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
