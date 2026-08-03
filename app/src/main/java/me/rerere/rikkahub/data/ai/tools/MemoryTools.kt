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
import me.rerere.rikkahub.data.model.MemoryLink
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.time.LocalDate

/** 记忆作用域: 助手隔离 / 全局共享, wireName 同时用于工具入参与 <memories> 标注 */
enum class MemoryToolScope(val wireName: String) {
    ASSISTANT("assistant"),
    GLOBAL("global");

    companion object {
        fun fromWire(value: String?): MemoryToolScope? =
            entries.firstOrNull { it.wireName == value }
    }
}

const val MEMORY_TOOL_NAME = "memory_tool"

/**
 * 记忆工具: 单工具 + 动态 scope。
 *
 * 只开启一个作用域时不注入 `scope` 入参, 描述里直接写死作用域;
 * 两个作用域都开启时才注入 `scope` enum, 由模型按 <memories> 中的标注选择。
 *
 * Phase 1 起新增图链接 action（link / query_links / unlink），
 * 原 create/edit/delete 语义不变。
 *
 * @param scopes 当前允许编辑的作用域, 为空时返回空列表(不暴露工具)
 * @param onLink 建边: (scope, sourceId, targetId, type, weight, description) -> MemoryLink
 * @param onQueryLinks 查边: (scope, memoryId?) -> List<MemoryLink>
 * @param onUnlink 删边: (scope, linkId)
 */
fun buildMemoryTool(
    scopes: List<MemoryToolScope>,
    onCreation: suspend (MemoryToolScope, String) -> AssistantMemory,
    onUpdate: suspend (MemoryToolScope, Int, String) -> AssistantMemory,
    onDelete: suspend (MemoryToolScope, Int) -> Unit,
    onLink: suspend (MemoryToolScope, Int, Int, String, Float, String) -> MemoryLink,
    onQueryLinks: suspend (MemoryToolScope, Int?) -> List<MemoryLink>,
    onUnlink: suspend (MemoryToolScope, Long) -> Unit,
): List<Tool> {
    if (scopes.isEmpty()) return emptyList()
    val multiScope = scopes.size > 1
    return listOf(
        Tool(
            name = MEMORY_TOOL_NAME,
            description = buildString {
                append("Store and relate long-term facts across conversations")
                if (multiScope) {
                    append(" in two scopes: `assistant` (this assistant only), `global` (shared by all assistants).")
                } else {
                    append(
                        when (scopes.single()) {
                            MemoryToolScope.ASSISTANT -> " in this assistant's own memory."
                            MemoryToolScope.GLOBAL -> " in the global memory shared by all assistants."
                        }
                    )
                }
                appendLine()
                append("Actions: `create` needs `content`; `edit` needs `id`+`content`; `delete` needs `id`. ")
                append("`link` needs `source_id`+`target_id` (both taken from the <memories> block), optional `type`/`weight`/`description`. ")
                append("`query_links` takes optional `memory_id` (links of that memory in scope) and optional `type` filter; omit `memory_id` to list all links in scope. ")
                append("`unlink` needs `link_id` as returned by `query_links`. ")
                append("Link `type` is one of: ${MemoryRepository.LINK_TYPES.joinToString("/")}. ")
                append("Take `id`")
                if (multiScope) append(" and `scope`")
                appendLine(" from the <memories> block.")
                append("Prefer editing a near-duplicate record over creating a new one. ")
                appendLine("Worth storing: preferred name, stable preferences, plans, work notes.")
                append("Never store ethnicity, religion, sexual orientation, political views, sex life or criminal records. ")
                appendLine("Do not quote stored memory back to the user unprompted.")
                append("Today: ${LocalDate.now()}")
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
                        if (multiScope) {
                            put("scope", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        scopes.forEach { add(it.wireName) }
                                    }
                                )
                                put("description", "Which memory store to act on.")
                            })
                        }
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "Target record id, for edit/delete.")
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
                                    MemoryRepository.LINK_TYPES.joinToString("/") + ". Default related."
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
                            put("description", "Filter query_links to links involving this memory id; omit to list all links in scope.")
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
                val requestedScope = params["scope"]?.jsonPrimitive?.contentOrNull
                val scope = if (requestedScope == null) {
                    // 单作用域时允许省略; 多作用域缺省则退回第一个可用作用域
                    scopes.first()
                } else {
                    MemoryToolScope.fromWire(requestedScope)
                }
                val payload = when {
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
    put("id", memory.id)
    put("content", memory.content)
}

private fun linkPayload(link: MemoryLink) = buildJsonObject {
    put("scope", link.scope)
    put("id", link.id)
    put("source_id", link.sourceId)
    put("source_content", link.sourceContent)
    put("target_id", link.targetId)
    put("target_content", link.targetContent)
    put("type", link.type)
    put("weight", link.weight)
    put("description", link.description)
}
