package me.rerere.ai.util

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 消息 token 估算（对话压缩「共 y tokens」统计用）。
 *
 * 为什么不复用 [UIMessage.summaryAsText]：那个函数是给「喂模型的文本快照」用的，
 * 只取 [UIMessagePart.Text]，且默认按 maxLength 截断。拿它算 token 会把
 * 工具调用（入参 + 返回体，往往是整段消息里最大的一块）、思考过程、附件全部漏掉，
 * 再叠加 `length / 4`（中文一个字远不止 1/4 token）——最终统计能比真实值小一个数量级。
 *
 * 这里的口径：
 * - 遍历**全部** part（含 Tool 的 input/output，递归 output 里的嵌套 part），不截断；
 * - CJK 与非 CJK 分开算：CJK 约 0.7 token/字，其余约 1 token / 4 字符；
 * - 二进制附件（图/音/视频）按固定成本估，避免 base64 URL 被当成正文按字符数爆算。
 */
private const val CHARS_PER_TOKEN_LATIN = 4.0
private const val TOKENS_PER_CJK_CHAR = 0.7

/** 单个附件的粗略固定成本（拿不到分辨率时的兜底） */
private const val IMAGE_TOKEN_COST = 800L
private const val MEDIA_TOKEN_COST = 1500L

/** metadata 里图片尺寸的 key（写入方见 app 层图片入库路径） */
const val PART_IMAGE_WIDTH_KEY = "width"
const val PART_IMAGE_HEIGHT_KEY = "height"

/**
 * 图片 token 按**分辨率**估算（2026-08-30，天赢提的）。
 *
 * 原来不管多大一律 800 —— 一张 4K 截图和一个 64×64 头像同价，离谱。
 * 主流视觉模型的实际口径是**分块**：把图切成 512px 的 tile，每块一份固定成本，
 * 再加一份缩略图底价（OpenAI 的 high detail 就是 170/tile + 85 base）。
 * 各家系数不一样，但「面积→分块→线性」这个形状是共同的，比常数强一个数量级。
 *
 * 还要先模拟服务端的预缩放：长边超 [MAX_SIDE] 会被等比缩下来，
 * 不缩的话一张 8000px 长图能算出个荒谬的天文数字。
 */
private const val IMAGE_TILE_PX = 512
private const val IMAGE_TILE_COST = 170L
private const val IMAGE_BASE_COST = 85L
private const val IMAGE_MAX_SIDE = 2048

fun imageTokenCost(width: Int, height: Int): Long {
    if (width <= 0 || height <= 0) return IMAGE_TOKEN_COST
    // 等比缩到长边 ≤ IMAGE_MAX_SIDE（服务端普遍会干这件事）
    val longest = maxOf(width, height)
    val scale = if (longest > IMAGE_MAX_SIDE) IMAGE_MAX_SIDE.toDouble() / longest else 1.0
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val tiles = ((w + IMAGE_TILE_PX - 1) / IMAGE_TILE_PX).toLong() *
        ((h + IMAGE_TILE_PX - 1) / IMAGE_TILE_PX).toLong()
    return IMAGE_BASE_COST + tiles * IMAGE_TILE_COST
}

/**
 * 从 part 的 metadata 读尺寸算图片成本；没写过尺寸（旧数据 / 远程 URL）就回落常数。
 * 写入是可选的，所以这里永远不会因为拿不到尺寸而报错 —— 向后兼容优先。
 *
 * TODO(2026-08-30)：写侧还没接。`FilesManager` 里已有 `inJustDecodeBounds` 拿 bounds
 * 的现成代码（约 258 / 665 行），图片入库时顺手把 width/height 写进 part metadata 即可，
 * 一张图只解一次头、不解像素，代价可忽略。接上之后这里才真正生效；
 * 在那之前所有图片继续走 800 的老口径，与改动前完全一致。
 */
private fun UIMessagePart.imageCostFromMetadata(): Long {
    val w = metadata?.get(PART_IMAGE_WIDTH_KEY)?.jsonPrimitive?.intOrNull ?: return IMAGE_TOKEN_COST
    val h = metadata?.get(PART_IMAGE_HEIGHT_KEY)?.jsonPrimitive?.intOrNull ?: return IMAGE_TOKEN_COST
    return imageTokenCost(w, h)
}

/** 纯文本 token 估算：区分 CJK / 非 CJK */
fun estimateTextTokens(text: String): Long {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    text.forEach { c ->
        if (c.isCjk()) cjk++ else other++
    }
    return (cjk * TOKENS_PER_CJK_CHAR + other / CHARS_PER_TOKEN_LATIN).toLong()
}

private fun Char.isCjk(): Boolean {
    val code = code
    return code in 0x2E80..0x9FFF ||      // CJK 部首扩充 ~ CJK 统一汉字
        code in 0xAC00..0xD7AF ||          // 韩文音节
        code in 0xF900..0xFAFF ||          // CJK 兼容汉字
        code in 0xFF00..0xFFEF             // 全角/半角形式
}

/** 单条消息的 token 估算（含工具调用与附件） */
@Suppress("DEPRECATION")
fun UIMessage.estimateTokens(): Long {
    var total = 4L // 每条消息的 role/分隔符固定开销
    fun countPart(part: UIMessagePart) {
        total += when (part) {
            is UIMessagePart.Text -> estimateTextTokens(part.text)
            is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
            is UIMessagePart.Image -> part.imageCostFromMetadata()
            is UIMessagePart.Audio, is UIMessagePart.Video -> MEDIA_TOKEN_COST
            is UIMessagePart.Document -> estimateTextTokens(part.fileName)
            is UIMessagePart.Tool -> {
                // 工具调用往往是整条消息里最大的一块：名字 + 入参 + 返回体都要算
                var sub = estimateTextTokens(part.toolName) + estimateTextTokens(part.input)
                part.output.forEach { out -> sub += out.estimateSelf() }
                sub
            }

            is UIMessagePart.ToolCall ->
                estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)

            is UIMessagePart.ToolResult ->
                estimateTextTokens(part.toolName) +
                    estimateTextTokens(part.content.toString()) +
                    estimateTextTokens(part.arguments.toString())

            else -> 0L
        }
    }
    parts.forEach(::countPart)
    // 记忆注入块虽然不在正文，但确实进过上下文
    memoryInjection?.let { total += estimateTextTokens(it) }
    return total
}

/**
 * 单个 part 的 token 估算。
 *
 * 2026-08-28「自动压缩 part 级下沉」后开为 public：压缩的边界游标要按 part 累加
 * （消息体积无界，只按消息切会出现「一条 300k 干爆保留区」或「刚跑完的工具轮当场失忆」
 * 两个坏选择），因此边界计算必须能问到单个 part 有多大。
 */
@Suppress("DEPRECATION")
fun UIMessagePart.estimateSelf(): Long = when (this) {
    is UIMessagePart.Text -> estimateTextTokens(text)
    is UIMessagePart.Reasoning -> estimateTextTokens(reasoning)
    is UIMessagePart.Image -> imageCostFromMetadata()
    is UIMessagePart.Audio, is UIMessagePart.Video -> MEDIA_TOKEN_COST
    is UIMessagePart.Document -> estimateTextTokens(fileName)
    is UIMessagePart.Tool -> estimateTextTokens(toolName) + estimateTextTokens(input) +
        output.sumOf { it.estimateSelf() }

    is UIMessagePart.ToolCall -> estimateTextTokens(toolName) + estimateTextTokens(arguments)
    is UIMessagePart.ToolResult -> estimateTextTokens(toolName) +
        estimateTextTokens(content.toString())

    else -> 0L
}

/**
 * 一段消息区间的 token 估算，**真实 usage 优先**。
 *
 * @param covered 要统计的区间
 * @param prior 区间之前的消息（用于取 usage 基线）
 *
 * 若区间内有 assistant 的真实 [me.rerere.ai.core.TokenUsage]：
 * 该次请求的 promptTokens 已含此前全部历史，减去区间之前最后一次 promptTokens
 * 即为本区间的真实增量，再加上区间末尾那次的输出 token。拿不到 usage（纯本地
 * 消息 / 旧数据）才退回字符估算。
 */
fun estimateMessagesTokens(covered: List<UIMessage>, prior: List<UIMessage> = emptyList()): Long {
    val fallback = covered.sumOf { it.estimateTokens() }
    val lastUsage = covered.asReversed()
        .firstNotNullOfOrNull { m -> m.usage?.takeIf { it.promptTokens > 0 } }
        ?: return fallback
    val baseline = prior.asReversed()
        .firstNotNullOfOrNull { m -> m.usage?.takeIf { it.promptTokens > 0 } }
        ?.promptTokens ?: 0
    val delta = lastUsage.promptTokens + lastUsage.completionTokens - baseline
    // usage 只在区间尾部出现、或中途换过模型/开过新会话时 delta 可能失真，
    // 取两者较大值：宁可贴近真实上限，也别再报出个荒谬的小数字。
    return maxOf(delta.toLong(), fallback).coerceAtLeast(1)
}
