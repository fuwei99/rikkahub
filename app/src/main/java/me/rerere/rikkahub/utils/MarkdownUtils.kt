package me.rerere.rikkahub.utils

/**
 * 移除字符串中的Markdown格式
 * @return 移除Markdown格式后的纯文本
 */
fun String.stripMarkdown(): String {
    return this
        // 移除代码块 (```...``` 和 `...`)
        .replace(Regex("```[\\s\\S]*?```|`[^`]*?`"), "")
        // 移除图片和链接，但保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        // 移除下划线
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        // 移除删除线
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 移除标题标记 (多行模式)
        .replace(Regex("(?m)^#+\\s*"), "")
        // 移除列表标记 (多行模式)
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // 移除引用标记 (多行模式)
        .replace(Regex("(?m)^>\\s*"), "")
        // 移除水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 将多个换行符压缩，以保留段落
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * 「独占一整行的加粗文本」。
 *
 * 必须提到顶层：[extractThinkingTitle] 的调用点在组合函数体里，每重组一次就重跑一次；
 * 而这个正则原来是写在 for 循环**体内**的，等于「每扫一行就新建一个 Pattern」——
 * 线上 12 万行的 CoT 单次实测 74ms（桌面 JVM 有 JIT 的下限），流式期间每来一个 chunk 就摞一次。
 */
private val THINKING_BOLD_LINE_REGEX = Regex("^\\*\\*(.+?)\\*\\*$")

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        // 检查是否为加粗格式且独占一整行
        val match = THINKING_BOLD_LINE_REGEX.find(lines[i].trim()) ?: continue
        // 返回加粗标记内的文本内容
        return match.groupValues[1].trim().takeUnless { it.isBlank() }
    }

    return null
}
