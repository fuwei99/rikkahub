package me.rerere.rikkahub.ui.pages.chat.memory

/**
 * 记忆注入溯源解析：实现已下沉到 data 层
 * [me.rerere.rikkahub.data.ai.prompts.parseMemoryInjectionNodeIds]，
 * 供 GenerationHandler（跨轮去重）与本页共用，避免 data → ui 反向依赖。
 * 这里保留转发别名，UI 侧原有 import 不变。
 */

const val TRACE_SCOPE_ASSISTANT = me.rerere.rikkahub.data.ai.prompts.TRACE_SCOPE_ASSISTANT
const val TRACE_SCOPE_GLOBAL = me.rerere.rikkahub.data.ai.prompts.TRACE_SCOPE_GLOBAL

fun parseMemoryInjectionNodeIds(injection: String?): Map<String, Set<Long>> =
    me.rerere.rikkahub.data.ai.prompts.parseMemoryInjectionNodeIds(injection)

fun hasMemoryInjectionTrace(injection: String?): Boolean =
    me.rerere.rikkahub.data.ai.prompts.hasMemoryInjectionTrace(injection)
