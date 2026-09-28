package me.rerere.rikkahub.ui.components.richtext

/**
 * 单行渲染的字符上限。
 *
 * Compose 的 `Constraints` 每个维度只有 18 bit，上限 0x3FFFF = 262143。
 * `Modifier.width(IntrinsicSize.Max)` 会拿「最宽一行」的固有宽度去 createConstraints，
 * 等宽 11sp 下 1 字符约 17px —— 一行两万字符算出来就是 34 万 px，直接越界抛
 * `IllegalArgumentException: Can't represent a width of ... in Constraints`。
 *
 * 就算没崩，Prism 高亮的正则和 Compose 的文本测量在超长单行上也是 O(n²) 级的卡顿源
 * （工具调用把一大坨 JSON 压成一行塞进消息，就是这个形状）。
 *
 * 手机屏一行最多也就读一百来个字符，2000 已经是给足余量的上限。
 */
internal const val MAX_RENDER_LINE_CHARS = 2000

private const val CLAMP_MARK_PREFIX = " …[+"
private const val CLAMP_MARK_SUFFIX = "]"

/**
 * 把超长单行截断成「前 [maxChars] 字符 + `…[+N]`」，**不改动行数，其余内容原样保留**。
 *
 * 只用于渲染前的显示层：拷贝 / 下载 / 落盘 / 送模型都必须拿原始字符串，
 * 千万别把这个结果当成数据源。
 */
internal fun clampLongLines(text: String, maxChars: Int = MAX_RENDER_LINE_CHARS): String {
    if (maxChars <= 0) return text
    if (text.length <= maxChars) return text

    // 先扫一遍：没有任何一行超长就原样返回，不复制、不分配
    var lineStart = 0
    var needsClamp = false
    var i = 0
    val n = text.length
    while (i <= n) {
        if (i == n || text[i] == '\n') {
            if (i - lineStart > maxChars) {
                needsClamp = true
                break
            }
            lineStart = i + 1
        }
        i++
    }
    if (!needsClamp) return text

    // 用 split('\n') 而不是 lineSequence()：后者会吃掉行尾空行，行号会错位
    return text.split('\n').joinToString("\n") { line ->
        if (line.length <= maxChars) {
            line
        } else {
            line.take(maxChars) + CLAMP_MARK_PREFIX + (line.length - maxChars) + CLAMP_MARK_SUFFIX
        }
    }
}
