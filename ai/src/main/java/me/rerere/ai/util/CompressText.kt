package me.rerere.ai.util

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 「喂给压缩模型的文本快照」渲染（2026-08-28 自动压缩 part 级下沉重构 · 刀 1）。
 *
 * 为什么不用 [UIMessage.summaryAsText] 的老实现：它是
 * ```
 * is UIMessagePart.Text -> part.text
 * else -> ""
 * ```
 * 一个 agent 回合的 assistant 消息常态是 `[Tool, Tool, Tool]`、零个 Text part，
 * 序列化出来字面就是 `[ASSISTANT]:` 三个空标签 —— 压缩模型回「没有什么可以压缩的」
 * 是唯一说实话的一方。工具入参与返回本来就是 agent 会话 90% 的信息量。
 *
 * 输出形态：
 * ```
 * [ASSISTANT]
 * <text>先看一下目录</text>
 * <tool name="workspace_shell">
 * input: {"command":"ls"}
 * output: a.kt b.kt ...
 * </tool>
 * ```
 *
 * 约定：
 * - Tool 的 output **逐 part 截断**（默认 1500 字符），防止一次 10k 的工具返回把
 *   覆盖区顶爆；
 * - Reasoning 默认不纳入（思维链污染总结），需要时显式打开；
 * - 二进制附件只留占位符，绝不把 base64 塞进 prompt。
 */
private const val DEFAULT_TOOL_OUTPUT_LIMIT = 1500

/** 单个 part 渲染成压缩文本；返回空串表示该 part 无可压缩内容。 */
@Suppress("DEPRECATION")
fun UIMessagePart.toCompressText(
    toolOutputLimit: Int = DEFAULT_TOOL_OUTPUT_LIMIT,
    includeReasoning: Boolean = false,
): String = when (this) {
    is UIMessagePart.Text -> text.trim().takeIf { it.isNotEmpty() }?.let { "<text>$it</text>" } ?: ""

    is UIMessagePart.Reasoning ->
        if (!includeReasoning) "" else reasoning.trim().takeIf { it.isNotEmpty() }
            ?.let { "<reasoning>${it.truncateForCompress(toolOutputLimit)}</reasoning>" } ?: ""

    is UIMessagePart.Tool -> buildString {
        append("<tool name=\"").append(toolName).append("\">\n")
        if (input.isNotBlank()) {
            append("input: ").append(input.trim().truncateForCompress(toolOutputLimit)).append('\n')
        }
        val out = output.joinToString("\n") {
            it.toCompressText(toolOutputLimit, includeReasoning)
        }.trim()
        if (out.isNotEmpty()) {
            append("output: ").append(out.truncateForCompress(toolOutputLimit)).append('\n')
        } else if (!isExecuted) {
            append("output: (not executed)\n")
        }
        append("</tool>")
    }

    is UIMessagePart.ToolCall ->
        "<tool name=\"$toolName\">\ninput: ${arguments.trim().truncateForCompress(toolOutputLimit)}\n</tool>"

    is UIMessagePart.ToolResult ->
        "<tool_result name=\"$toolName\">\n" +
            content.toString().truncateForCompress(toolOutputLimit) + "\n</tool_result>"

    is UIMessagePart.Image -> "[image]"
    is UIMessagePart.Audio -> "[audio]"
    is UIMessagePart.Video -> "[video]"
    is UIMessagePart.Document -> "[document: $fileName]"
    else -> ""
}

/**
 * 一段 part 渲染成压缩文本（不含 role 头）。
 */
fun List<UIMessagePart>.toCompressText(
    toolOutputLimit: Int = DEFAULT_TOOL_OUTPUT_LIMIT,
    includeReasoning: Boolean = false,
): String = mapNotNull { part ->
    part.toCompressText(toolOutputLimit, includeReasoning).takeIf { it.isNotEmpty() }
}.joinToString("\n")

/**
 * 一条消息渲染成压缩文本，形如 `[ASSISTANT]\n<text>…</text>\n<tool …>…</tool>`。
 *
 * @param partRange 只渲染该区间的 part（part 级游标用；默认整条）
 */
fun UIMessage.toCompressText(
    toolOutputLimit: Int = DEFAULT_TOOL_OUTPUT_LIMIT,
    includeReasoning: Boolean = false,
    partRange: IntRange? = null,
): String {
    val selected = partRange
        ?.let { r -> parts.filterIndexed { index, _ -> index in r } }
        ?: parts
    val body = selected.toCompressText(toolOutputLimit, includeReasoning)
    val memory = memoryInjection?.takeIf { it.isNotBlank() }
        ?.let { "\n<memory>${it.truncateForCompress(toolOutputLimit)}</memory>" }.orEmpty()
    return "[${role.name}]\n" + body + memory
}

/**
 * 剥掉 `[ROLE]` 头与标签骨架后的实际内容长度 —— 用于「真·空内容护栏」：
 * 只数条数（`messagesToCompress.isEmpty()`）拦不住一串空标签，会拿空气去烧 API 额度。
 */
fun String.compressPayloadLength(): Int = this
    .replace(Regex("^\\[[A-Z_]+]$", RegexOption.MULTILINE), "")
    .replace(Regex("</?(text|tool|tool_result|reasoning|memory)[^>]*>"), "")
    .replace(Regex("^(input|output):\\s*$", RegexOption.MULTILINE), "")
    .filterNot { it.isWhitespace() }
    .length

private fun String.truncateForCompress(limit: Int): String =
    if (length <= limit) this else take(limit) + "…(truncated ${length - limit} chars)"
