package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryGraphLink
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.utils.JsonInstantPretty

/**
 * 记忆图注入（独立链路，与 [buildMemoryPrompt] 传统记忆互不干扰）：
 * 以 <memory_graph> 块 + <assistant_graph>/<global_graph> 子块输出图谱节点与边，
 * 明确区分两个 scope，模型可据此理解关系并用 memory-edit(memory_type=graph) 编辑。
 *
 * 载荷格式（2026-08-06 改为纯文本行式，实测省 ~41% token，DB 存储结构不变）：
 *   节点  `<id> <title>: <content>`
 *   关系  `<sourceId> -<type>-> <targetId> | <description>`（description 为空则省略 " | …"）
 * 旧会话里已落库的 JSON 载荷由 parseMemoryInjectionNodeIds / injectedGraphNodeIds 双格式兼容解析。
 *
 * @param includeHeader 是否输出说明头（**Graph Memories** + 用法约定 + Format 图例，约 66 token）。
 *   一次会话里说明只需在上文出现一次，后续轮次的注入块只带数据行，省掉逐轮重复开销。
 */
internal fun buildGraphMemoryPrompt(
    assistantNodes: List<MemoryGraphNode>,
    assistantLinks: List<MemoryGraphLink>,
    globalNodes: List<MemoryGraphNode>,
    globalLinks: List<MemoryGraphLink>,
    contentMaxChars: Int = 0,
    includeHeader: Boolean = true,
) = buildString {
    // contentMaxChars > 0 时逐节点截断正文，控制注入体积（0 = 原文全量）。
    fun clip(text: String): String =
        if (contentMaxChars <= 0 || text.length <= contentMaxChars) text
        else text.take(contentMaxChars) + "…"
    if (assistantNodes.isEmpty() && globalNodes.isEmpty()) return@buildString

    // 正文里的换行会破坏"一行一条"的行式结构，压成空格。
    fun flatten(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun StringBuilder.appendScope(
        tag: String,
        nodes: List<MemoryGraphNode>,
        links: List<MemoryGraphLink>,
    ) {
        if (nodes.isEmpty()) return
        appendLine("<$tag>")
        nodes.forEach { n ->
            appendLine("${n.id} ${flatten(n.title)}: ${flatten(clip(n.content))}")
        }
        links.forEach { l ->
            val note = l.description.takeIf { it.isNotBlank() }?.let { " | ${flatten(it)}" } ?: ""
            appendLine("${l.sourceId} -${l.type}-> ${l.targetId}$note")
        }
        appendLine("</$tag>")
    }

    appendLine("<memory_graph>")
    if (includeHeader) {
        appendLine()
        appendLine("**Graph Memories**")
        appendLine(
            "These are knowledge-graph memories (nodes and relationships) the user allowed you to reference. " +
                "Do not modify them unless a memory editing tool is available and the user intent justifies it."
        )
        appendLine("Format: `id title: content` for nodes, `sourceId -type-> targetId | note` for relations.")
    }
    appendScope("assistant_graph", assistantNodes, assistantLinks)
    appendScope("global_graph", globalNodes, globalLinks)
    appendLine("</memory_graph>")
}

/**
 * 记忆注入。两个作用域都开启时按 scope 分组(即使某侧为空也保留空数组,
 * 让模型能把 id 对应到 scope); 只开一个时扁平输出以省 token。
 *
 * @param wrapInMemoryBlock 记忆图开启时（注入最后一条 user 消息）用显式 <memory> 块标记，
 *                          便于未来解析/剥离；记忆图关闭的旧机制（注入 system prompt）
 *                          保持旧版格式不带块标记。
 */
internal fun buildMemoryPrompt(
    assistantMemories: List<AssistantMemory>,
    globalMemories: List<AssistantMemory>,
    groupByScope: Boolean,
    wrapInMemoryBlock: Boolean = true,
) = buildString {
    if (assistantMemories.isEmpty() && globalMemories.isEmpty()) return@buildString
    // 显式 <memory> 块标记（对齐 Operit attachment 注入风格）：注入在最新 user 消息末尾，
    // 保证 system+历史前缀稳定命中前缀缓存；块边界显式，便于未来解析/剥离。
    if (wrapInMemoryBlock) {
        appendLine("<memory>")
        appendLine()
    }
    appendLine("**Memories**")
    appendLine(
        "These are memories the user allowed you to reference for this reply. " +
            "Do not modify memory unless a memory editing tool is available and the user intent justifies it."
    )
    val json = buildJsonObject {
        if (groupByScope) {
            put("assistant", buildJsonArray {
                assistantMemories.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        put("content", m.content)
                    })
                }
            })
            put("global", buildJsonArray {
                globalMemories.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        put("content", m.content)
                    })
                }
            })
        } else {
            // 只有一个 scope 时扁平输出, 省 token
            put("memories", buildJsonArray {
                (assistantMemories + globalMemories).forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        put("content", m.content)
                    })
                }
            })
        }
    }
    append(JsonInstantPretty.encodeToString(json))
    appendLine()
    if (wrapInMemoryBlock) {
        appendLine("</memory>")
    }
}
