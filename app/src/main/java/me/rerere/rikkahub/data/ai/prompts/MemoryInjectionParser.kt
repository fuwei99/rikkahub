package me.rerere.rikkahub.data.ai.prompts

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

/** 注入块里的 scope key，与 GenerationPrompts.buildGraphMemoryPrompt 的标签一一对应 */
const val TRACE_SCOPE_ASSISTANT = "assistant"
const val TRACE_SCOPE_GLOBAL = "global"

/**
 * <assistant_graph> / <global_graph> 子块，捕获 scope 名以区分两个范围。
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
 *
 * 双格式兼容：新载荷是纯文本行（`id title: content`），旧会话里已落库的是 JSON，
 * 故先试文本解析，为空再回落 JSON（方案 2026-08-06 注入瘦身）。
 *
 * 放在 data 层供 GenerationHandler（跨轮去重）与 UI 溯源抽屉共用，避免 data → ui 反向依赖。
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
        val payload = match.groupValues[2]
        val ids = parseNodeIdsFromText(payload).ifEmpty { parseNodeIdsFromJson(payload) }
        if (ids.isNotEmpty()) result.getOrPut(scope) { mutableSetOf() }.addAll(ids)
    }
    return result.mapValues { (_, ids) -> ids.toSet() }
}

/** 纯文本行式载荷：节点行形如 `123 标题: 正文`；关系行 `1 -type-> 2` 不含冒号，天然不匹配。 */
private val GRAPH_NODE_LINE_REGEX = Regex("^(\\d+)\\s+[^:\\n]*:", RegexOption.MULTILINE)

private fun parseNodeIdsFromText(payload: String): Set<Long> =
    GRAPH_NODE_LINE_REGEX.findAll(payload)
        .mapNotNull { it.groupValues[1].toLongOrNull() }
        .toSet()

/** 旧格式（JSON 子图）兼容解析。 */
private fun parseNodeIdsFromJson(payload: String): Set<Long> {
    val ids = mutableSetOf<Long>()
    runCatching {
        val obj = JsonInstant.parseToJsonElement(payload).jsonObject
        obj["nodes"]?.jsonArray?.forEach { element ->
            val id = element.jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
            if (id != null) ids.add(id)
        }
    }
    return ids
}

/** 该消息是否触发过记忆图注入（决定消息底部链路按钮是否显示） */
fun hasMemoryInjectionTrace(injection: String?): Boolean =
    parseMemoryInjectionNodeIds(injection).values.any { it.isNotEmpty() }
