package me.rerere.rikkahub.utils

/**
 * 思维链（CoT）渲染层的成本控制与降级。**只作用于显示，不改任何存储、不改业务数据。**
 *
 * 背景（2026-09-22 事故取证）：模型跑偏时会以「一行两三个字 + 空行」的形态刷屏，本地会话库里
 * 真实存在 336,917 字符 / 121,374 行 / 60,450 段落的 CoT。markdown 会把每一段都当成独立块，
 * 组合树里被塞进 **181,444 个顶层 composable**，主线程每来一个流式 chunk 就重建一遍，
 * 最终 `SlotWriter.insertSlots` 申请 20MB 槽位表失败 → OOM 闪退。
 *
 * 本文件提供三件事：
 * 1. [exceedsCotRenderBudget]：体量闸门，超限就让上层「整块折叠、一个字符都不渲染」；
 * 2. [takeTailOnLineBoundary]：流式期间只取尾部窗口（预览卡本来就只有 100dp 高且自动贴底）；
 * 3. [normalizeFragmentedLineBreaks]：把连续的超短行压成同一行，消掉碎片化换行。
 */

/** 超过这个字符数直接放弃渲染（宁可看不到，也不能卡死/闪退）。 */
const val COT_RENDER_MAX_CHARS: Int = 60_000

/** 超过这个行数直接放弃渲染。碎片化 CoT 的杀伤力在**块数量**上，不在字符数上。 */
const val COT_RENDER_MAX_LINES: Int = 2_000

/** 流式预览只渲染尾部这么多字符。 */
const val COT_STREAM_TAIL_CHARS: Int = 4_000

/** 多短的「可见行」算碎片。 */
private const val FRAGMENT_LINE_MAX_CHARS: Int = 6

/** 连续多少行碎片才触发合并（低于此数保持原样，不误伤正常排版）。 */
private const val FRAGMENT_RUN_MIN_LINES: Int = 3

/** 带这些字符的行视为有 markdown 结构，不参与合并（列表/标题/引用/表格/代码/强调/链接）。 */
private const val MARKDOWN_STRUCTURE_CHARS = "#*>|`[]_~"

/**
 * 体量是否超出渲染预算。
 *
 * 逐字符扫、超上限立刻返回，不分配任何中间对象——它本身也要在组合里被调用。
 */
fun String.exceedsCotRenderBudget(): Boolean {
    if (length > COT_RENDER_MAX_CHARS) return true
    var lines = 1
    for (ch in this) {
        if (ch == '\n' && ++lines > COT_RENDER_MAX_LINES) return true
    }
    return false
}

/**
 * 取尾部 [maxChars] 个字符，并在最近的换行处切断（避免半行破坏 markdown 结构）。
 *
 * 流式预览卡高度固定 100dp 且自动贴底，用户看到的本来就只有尾巴——渲染全篇纯浪费。
 */
fun String.takeTailOnLineBoundary(maxChars: Int): String {
    if (length <= maxChars) return this
    val from = length - maxChars
    val cut = indexOf('\n', from)
    return if (cut == -1) substring(from) else substring(cut + 1)
}

/**
 * 把连续的超短行（可见长度 <= [FRAGMENT_LINE_MAX_CHARS] 且不带 markdown 结构）合并成同一行，
 * 中间的空行一并吃掉；代码围栏（``` / ~~~）内部原样保留。
 *
 * 处理前：
 *
 *     （输出）
 *
 *     好。
 *
 *     我输出。
 *
 * 处理后（三个碎片并成一行，空行消失，markdown 只产出 1 个块而不是 3 个段落）：
 *
 *     （输出） 好。 我输出。
 *
 * 这不叫「改内容」——原文一个字没动，只是决定它被画成几行。
 */
fun String.normalizeFragmentedLineBreaks(): String {
    if (length < FRAGMENT_RUN_MIN_LINES * FRAGMENT_LINE_MAX_CHARS) return this
    val lines = split('\n')
    val out = ArrayList<String>(lines.size)
    var inFence = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmedStart = line.trimStart()
        if (trimmedStart.startsWith("```") || trimmedStart.startsWith("~~~")) {
            inFence = !inFence
            out.add(line)
            i++
            continue
        }
        if (inFence) {
            out.add(line)
            i++
            continue
        }
        if (isFragmentLine(line)) {
            // 向前看：把「碎片行 + 空行」串成一个 run
            val merged = StringBuilder()
            var fragments = 0
            var lastFragmentEnd = i
            var j = i
            while (j < lines.size) {
                val candidate = lines[j]
                if (candidate.isBlank()) {
                    j++
                    continue
                }
                if (!isFragmentLine(candidate)) break
                if (merged.isNotEmpty()) merged.append(' ')
                merged.append(candidate.trim())
                fragments++
                lastFragmentEnd = j + 1
                j++
            }
            if (fragments >= FRAGMENT_RUN_MIN_LINES) {
                out.add(merged.toString())
                // 只推进到最后一个碎片行之后：run 后面的空行留给下一轮，段落分隔不会塌
                i = lastFragmentEnd
                continue
            }
        }
        out.add(line)
        i++
    }
    return out.joinToString("\n")
}

private fun isFragmentLine(raw: String): Boolean {
    val text = raw.trim()
    if (text.isEmpty() || text.length > FRAGMENT_LINE_MAX_CHARS) return false
    if (text[0] == '-' || text[0] == '+') return false                        // 无序列表项
    if (text.length > 1 && text[0].isDigit() && text[1] == '.') return false  // 有序列表项
    return text.none { it in MARKDOWN_STRUCTURE_CHARS }
}
