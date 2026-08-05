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

/** 记忆作用域: 助手隔离 / 全局共享, wireName 同时用于工具入参与 <memories>/<memory_graph> 标注 */
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

const val MEMORY_TOOL_NAME = "memory_tool"

/**
 * 记忆工具: 单工具 + 动态 scope + memory_type（legacy / graph，默认 legacy 向后兼容）。
 *
 * - legacy：传统记忆表（create/edit/delete/link/query_links/unlink），id 取自 <memories> 块（Int）；
 * - graph：独立记忆图（create/edit/delete/link/query_links/unlink），id 取自 <memory_graph> 块（Long），
 *   graph create 需要 title + content。
 * 两个 scope 都开启时注入 scope enum；只开一个时描述里写死作用域。
 *
 * @param scopes 允许编辑传统记忆的作用域, 为空时表示 legacy 编辑全关
 * @param graphScopes 允许编辑记忆图的作用域, 为空时表示 graph 编辑全关
 * （两个列表同时为空时返回空列表, 不暴露工具）
 */
fun buildMemoryTool(
    scopes: List<MemoryToolScope>,
    onCreation: suspend (MemoryToolScope, String) -> AssistantMemory,
    onUpdate: suspend (MemoryToolScope, Int, String) -> AssistantMemory,
    onDelete: suspend (MemoryToolScope, Int) -> Unit,
    onLink: suspend (MemoryToolScope, Int, Int, String, Float, String) -> MemoryLink,
    onQueryLinks: suspend (MemoryToolScope, Int?) -> List<MemoryLink>,
    onUnlink: suspend (MemoryToolScope, Long) -> Unit,
    graphScopes: List<MemoryToolScope>,
    graphOnCreate: suspend (MemoryToolScope, String, String) -> MemoryGraphNode,
    graphOnUpdate: suspend (MemoryToolScope, Long, String, String) -> MemoryGraphNode,
    graphOnDelete: suspend (MemoryToolScope, Long) -> Unit,
    graphOnLink: suspend (MemoryToolScope, Long, Long, String, Float, String) -> MemoryGraphLink,
    graphOnQueryLinks: suspend (MemoryToolScope, Long?) -> List<MemoryGraphLink>,
    graphOnUnlink: suspend (MemoryToolScope, Long) -> Unit,
): List<Tool> {
    if (scopes.isEmpty() && graphScopes.isEmpty()) return emptyList()
    val multiScope = scopes.size > 1 || graphScopes.size > 1
    return listOf(
        Tool(
            name = MEMORY_TOOL_NAME,
            description = buildString {
                append("Store long-term facts and relationships")
                if (multiScope) {
                    append(" in `assistant` or shared `global` memory.")
                } else {
                    append(
                        when ((scopes + graphScopes).firstOrNull()) {
                            MemoryToolScope.ASSISTANT -> " in this assistant's memory."
                            MemoryToolScope.GLOBAL -> " in shared global memory."
                            null -> "."
                        }
                    )
                }
                appendLine()
                appendLine("Types: `legacy` uses <memories>; `graph` uses <memory_graph>; default is `legacy`.")
                appendLine("create: `content` (graph also `title`); edit: `id`+`content` (graph also `title`); delete: `id`.")
                appendLine("link: `source_id`+`target_id`; query_links: optional `memory_id`/`node_id`; unlink: `link_id`.")
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
                        if (multiScope) {
                            put("scope", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        (scopes + graphScopes).distinct().forEach { add(it.wireName) }
                                    }
                                )
                                put("description", "Which memory store to act on.")
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
                            put("description", "Link strength 0..1, default 0.7. Only for link.")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Explanation of the relationship, only for link.")
                        })
                        put("memory_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "legacy: filter query_links to links involving this memory id; omit to list all links in scope.")
                        })
                        put("node_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "graph: filter query_links to links involving this node id; omit to list all links in scope.")
                        })
                        put("link_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "Link id as returned by query_links, for unlink.")
                        })
                    },
                    required = buildList<String> {
                        add("action")
                        if (multiScope) add("scope")
                    }
                )
            },
            execute = { raw ->
                val params = raw.jsonObject
                val memoryType = MemoryToolType.fromWire(params["memory_type"]?.jsonPrimitive?.contentOrNull)
                val requestedScope = params["scope"]?.jsonPrimitive?.contentOrNull
                val payload = when (memoryType) {
                    MemoryToolType.GRAPH -> {
                        val scope = if (requestedScope == null) graphScopes.firstOrNull() else MemoryToolScope.fromWire(requestedScope)
                        when {
                            graphScopes.isEmpty() -> errorPayload(
                                "graph memory editing is not enabled by the user"
                            )

                            scope == null -> errorPayload(
                                "unknown scope: $requestedScope, must be one of ${graphScopes.joinToString { it.wireName }}"
                            )

                            scope !in graphScopes -> errorPayload(
                                "scope ${scope.wireName} is not enabled for graph memory, available: ${graphScopes.joinToString { it.wireName }}"
                            )

                            else -> {
                                val action = params["action"]?.jsonPrimitive?.contentOrNull
                                when (action) {
                                    "create" -> {
                                        val title = params["title"]?.jsonPrimitive?.contentOrNull
                                        val content = params["content"]?.jsonPrimitive?.contentOrNull
                                        when {
                                            title.isNullOrBlank() -> errorPayload("title is required for graph create")
                                            content.isNullOrBlank() -> errorPayload("content is required for graph create")
                                            else -> graphNodePayload(scope, graphOnCreate(scope, title, content))
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

                                            else -> graphNodePayload(scope, graphOnUpdate(scope, id, title.orEmpty(), content.orEmpty()))
                                        }
                                    }

                                    "delete" -> {
                                        val id = params["id"]?.jsonPrimitive?.longOrNull
                                        if (id == null) {
                                            errorPayload("id is required for graph delete")
                                        } else {
                                            graphOnDelete(scope, id)
                                            buildJsonObject {
                                                put("success", true)
                                                put("scope", scope.wireName)
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
                                                graphLinkPayload(graphOnLink(scope, sourceId, targetId, type, weight, description))
                                            }
                                        }
                                    }

                                    "query_links" -> {
                                        val nodeId = params["node_id"]?.jsonPrimitive?.longOrNull
                                            ?: params["memory_id"]?.jsonPrimitive?.longOrNull
                                        val typeFilter = params["type"]?.jsonPrimitive?.contentOrNull
                                        val links = graphOnQueryLinks(scope, nodeId)
                                            .filter { typeFilter == null || it.type == typeFilter }
                                        buildJsonObject {
                                            put("scope", scope.wireName)
                                            put("memory_type", "graph")
                                            put("links", buildJsonArray {
                                                links.forEach { add(graphLinkPayload(it)) }
                                            })
                                        }
                                    }

                                    "unlink" -> {
                                        val linkId = params["link_id"]?.jsonPrimitive?.longOrNull
                                            ?: params["link_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                        if (linkId == null) {
                                            errorPayload("link_id is required for graph unlink")
                                        } else {
                                            graphOnUnlink(scope, linkId)
                                            buildJsonObject {
                                                put("success", true)
                                                put("scope", scope.wireName)
                                                put("memory_type", "graph")
                                                put("link_id", linkId)
                                            }
                                        }
                                    }

                                    else -> errorPayload(
                                        "unknown action: $action, must be one of [create, edit, delete, link, query_links, unlink]"
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
                                        "unknown action: $action, must be one of [create, edit, delete, link, query_links, unlink]"
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

private fun graphNodePayload(scope: MemoryToolScope, node: MemoryGraphNode) = buildJsonObject {
    put("scope", scope.wireName)
    put("memory_type", "graph")
    put("id", node.id)
    put("title", node.title)
    put("content", node.content)
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
