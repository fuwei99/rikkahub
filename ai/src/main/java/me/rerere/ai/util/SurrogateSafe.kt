package me.rerere.ai.util

/**
 * UTF-16 代理对安全处理（2026-08-31 修 `loadMessageNodes` 反序列化崩溃）。
 *
 * ## 事故回放
 *
 * `ConversationTools` 的 `last_message_preview` 用 `String.take(200)` 裁剪预览文本。
 * `take()` 按 **UTF-16 code unit** 硬切，而 emoji（BMP 外字符）在 JVM String 里是
 * 两个 code unit 的代理对，例如 `👜` = U+1F45C = `D83D DC5C`。
 * 刀正好落在这对中间，预览串尾部就留下一个**孤立高位代理 U+D83D**。
 *
 * 孤立代理在 Kotlin `String` 里是合法的（String 只是 char 数组），
 * kotlinx.serialization 也照样把它写进 JSON 字符串。
 * 灾难发生在这串 UTF-16 转 UTF-8 落库时：宽松编码器碰到「高位代理 + 缺低位代理」，
 * 会抓紧跟其后的那个字节当低位代理拼合：
 *
 * ```
 * D83D + 0x5C('\')  ->  U+1F45C  ->  f0 9f 91 9c
 * ```
 *
 * 也就是说 **JSON 的转义反斜杠被吞进了 emoji 的编码里**，
 * 库里存下的字面变成 `…👜"` —— 少一个反斜杠，字符串被提前闭合。
 * 再读回来，`Json.decodeFromString` 在下一个裸 `\` 上抛：
 *
 * ```
 * JsonDecodingException: Unexpected JSON token at offset 4148:
 *   Expected beginning of the string, but got \ at path: $[0].parts[2].output[0].text
 * ```
 *
 * 结果是整个会话（乃至会话列表）加载直接崩溃闪退，而坏数据只有一条 part。
 *
 * ## 防线
 *
 * - [takeSafe] / [truncateSafe]：截断永不劈开代理对 —— 从源头不产生孤立代理。
 * - [stripLoneSurrogates]：兜底消毒，任何漏网的孤立代理替成 U+FFFD。
 *   落库前（`encodeToString` 之后）过一道，上游有一百个 `.take()` 也炸不了库。
 */

/** 高位代理 U+D800..U+DBFF */
private fun Char.isHighSurrogateChar() = this.code in 0xD800..0xDBFF

/** 低位代理 U+DC00..U+DFFF */
private fun Char.isLowSurrogateChar() = this.code in 0xDC00..0xDFFF

/**
 * 代理对安全的 `take`：截取前 [n] 个 UTF-16 code unit，
 * 但若最后一个是**高位代理**（说明刀落在代理对中间），则再退一格，宁少不残。
 *
 * 用它替换所有作用在「用户/模型/工具产出文本」上的 `String.take(n)`。
 */
fun String.takeSafe(n: Int): String {
    if (n <= 0) return ""
    if (n >= length) return this
    val end = if (this[n - 1].isHighSurrogateChar()) n - 1 else n
    return substring(0, end)
}

/**
 * 代理对安全的 `takeLast`：若首字符是**低位代理**（刀落在代理对中间），则少取一格。
 */
fun String.takeLastSafe(n: Int): String {
    if (n <= 0) return ""
    if (n >= length) return this
    val start = if (this[length - n].isLowSurrogateChar()) length - n + 1 else length - n
    return substring(start)
}

/**
 * 代理对安全的「截断 + 省略号」。超长才加 [ellipsis]，不超长原样返回。
 */
fun String.truncateSafe(limit: Int, ellipsis: String = "…"): String =
    if (length <= limit) this else takeSafe(limit) + ellipsis

/**
 * 消毒：把所有**未配对**的代理字符替换为 [replacement]（默认 U+FFFD REPLACEMENT CHARACTER）。
 *
 * 合法代理对完整保留（emoji 不会被打坏）。字符串没有孤立代理时返回**同一实例**，
 * 零拷贝，可以放心挂在热路径上。
 */
fun String.stripLoneSurrogates(replacement: Char = '\uFFFD'): String {
    if (!hasLoneSurrogate()) return this
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogateChar() -> {
                if (i + 1 < length && this[i + 1].isLowSurrogateChar()) {
                    sb.append(c).append(this[i + 1])
                    i += 2
                } else {
                    sb.append(replacement)
                    i++
                }
            }

            c.isLowSurrogateChar() -> {
                // 没有前导高位代理的孤立低位代理
                sb.append(replacement)
                i++
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

/** 是否含有未配对的代理字符。用于快速判定，避免无谓拷贝。 */
fun String.hasLoneSurrogate(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogateChar()) {
            if (i + 1 < length && this[i + 1].isLowSurrogateChar()) {
                i += 2
                continue
            }
            return true
        }
        if (c.isLowSurrogateChar()) return true
        i++
    }
    return false
}
