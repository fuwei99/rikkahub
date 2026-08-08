package me.rerere.ai.provider

import me.rerere.ai.ui.UIMessage

/**
 * 统一出口的请求净化器：所有 [Provider.generateText] / [Provider.streamText] 发送前都会调用。
 *
 * 这是 LLM 调用的最终汇聚点（对话 / 工具 / 记忆选择器 / 抽取器 / subagent / 翻译全部经过
 * Provider 接口），在此做一层 sanitize 即全局生效，无需在各调用方分别打补丁。
 *
 * App 层可注入敏感词映射替换等实现（针对 `Model.hasContentModeration` 标记的模型，
 * 在发往带审核的出口前替换词条，避免整包 PROHIBITED_CONTENT / Content Exists Risk）。
 */
fun interface MessageSanitizer {
    /** @param model 本次请求使用的模型（可用 [Model.hasContentModeration] 决定是否处理） */
    fun sanitize(model: Model, messages: List<UIMessage>): List<UIMessage>

    companion object {
        val NoOp = MessageSanitizer { _, messages -> messages }
    }
}
