package me.rerere.rikkahub.ui.pages.chat.memory

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

/** 注入块里的 scope key，与 GenerationPrompts.buildGraphMemoryPrompt 的标签一一对应 */
const val TRACE_SCOPE_ASSISTANT = "assistant"
const val TRACE_SCOPE_GLOBAL = "global"

/**
 * <assistant_graph> / <global_graph> 子图 JSON。
 * 与 GenerationHandler.GRAPH_SUBGRAPH_JSON_REGEX 同源，但额外捕获 scope 名以区分两个范围。
 */
private val GRAPH_SUBGRAPH_REGEX = Regex(
    "<(assistant_graph|global_graph)>(.*?)</\\1>",
    RegexOption.DOT_MATCHES_ALL,
)

/**
 * 从一条消息的 memoryInjection 里解析出本轮实际注入的节点 id。
 *
 * 返回 Map<scope, Set<nodeId>>，scope 为 [TRACE_SCOPE_ASSISTANT] / [TRACE_SCOPE_GLOBAL]。
 * 注入块本身就是 cap 之后的最终结果，所以这里读到的就是"模型真正看到的节点"，
 * 无需另存 trace 字段（方案 2026-08-06）。
 */
fun parseMemoryInjectionNodeIds(injection: String?): Map<String, Set<Long>> {
    val block = injection?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val result = mutableMapOf<String, MutableSet<Long>>()
    GRAPH_SUBGRAPH_REGEX.findAll(block).forEach { match ->
        val scope = when (match.groupValues[1]) {
            "assistant_graph" -> TRACE_SCOPE_ASSISTANT
            "global_graph" -> TRACE_SCOPE_GLOBAL
            else -> return@forEach
        }
        runCatching {
            val obj = JsonInstant.parseToJsonElement(match.groupValues[2]).jsonObject
            obj["nodes"]?.jsonArray?.forEach { element ->
                val id = element.jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
                if (id != null) result.getOrPut(scope) { mutableSetOf() }.add(id)
            }
        }
    }
    return result.mapValues { (_, ids) -> ids.toSet() }
}

/** 该消息是否触发过记忆图注入（决定消息底部链路按钮是否显示） */
fun hasMemoryInjectionTrace(injection: String?): Boolean =
    parseMemoryInjectionNodeIds(injection).values.any { it.isNotEmpty() }
