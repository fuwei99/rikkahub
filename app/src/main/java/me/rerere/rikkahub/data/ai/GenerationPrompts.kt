package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

/**
 * 记忆注入。两个作用域都开启时按 scope 分组(即使某侧为空也保留空数组,
 * 让模型能把 id 对应到 scope); 只开一个时扁平输出以省 token。
 */
internal fun buildMemoryPrompt(
    assistantMemories: List<AssistantMemory>,
    globalMemories: List<AssistantMemory>,
    groupByScope: Boolean,
) = buildString {
    if (assistantMemories.isEmpty() && globalMemories.isEmpty()) return@buildString
    // 显式 <memory> 块标记（对齐 Operit attachment 注入风格）：注入在最新 user 消息末尾，
    // 保证 system+历史前缀稳定命中前缀缓存；块边界显式，便于未来解析/剥离。
    appendLine("<memory>")
    appendLine()
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
    appendLine("</memory>")
}