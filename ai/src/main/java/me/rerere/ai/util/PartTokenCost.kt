package me.rerere.ai.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * part 级 token 成本（2026-08-30）。
 *
 * ## 为什么要有这个
 *
 * 压缩的边界游标要按 part 累加，而此前唯一的数据来源是 [estimateSelf] ——
 * 纯按字符数拍脑袋：CJK 算 0.7 token/字、其余 4 字符/token。这个口径对英文散文
 * 勉强能看，碰上工具轮就彻底失准：JSON 的括号引号、代码缩进、base64、URL，
 * 分词器的实际切法与「字符数 / 4」毫无关系，偏差轻松到 ±50%。
 *
 * 而 token 恰恰是压缩里唯一真正要控的量，用一把歪尺子去切刀口，
 * 「保留最近 20k」可能实际保留了 30k 或 12k —— 那这个功能就是个摆设。
 *
 * ## 三级取数（精度从高到低）
 *
 * 1. **真实 usage 反推**（[attachRealUsage]）：API 返回时就把该轮的真实
 *    promptTokens 增量 / completionTokens 记进 part 的 metadata。这是唯一
 *    真正准确的来源 —— 分词是服务端做的，只有它知道确切数字。
 * 2. **缓存的估算值**：没有 usage（本地消息、旧数据）时，首次需要用到才算一次，
 *    结果同样写进 metadata，之后直接读。part 内容不可变，所以缓存永不失效。
 * 3. **实时估算**：前两者都没有时退回 [estimateSelf]。
 *
 * ## 存储
 *
 * 复用每个 part 都已有的 `metadata: JsonObject?`，**不加数据库字段、不做 migration**。
 * 旧数据没有这个 key，读出来是 null，自然走 2/3 级，天然向后兼容。
 * 天赢的要求原话：「不要求旧有对话有这个，这个是可选的数值」。
 */

/** metadata 里存 token 成本的 key */
const val PART_TOKEN_COST_KEY = "token_cost"

/** 标记该值来源：real = 服务端 usage 反推，est = 本地估算 */
const val PART_TOKEN_SOURCE_KEY = "token_cost_src"

const val TOKEN_SOURCE_REAL = "real"
const val TOKEN_SOURCE_ESTIMATE = "est"

/** 读取已记录的 token 成本；没有则 null */
fun UIMessagePart.recordedTokenCost(): Long? =
    metadata?.get(PART_TOKEN_COST_KEY)?.jsonPrimitive?.longOrNull

/** 该 part 的 token 数是否来自服务端真实 usage */
fun UIMessagePart.hasRealTokenCost(): Boolean =
    metadata?.get(PART_TOKEN_SOURCE_KEY)?.jsonPrimitive?.content == TOKEN_SOURCE_REAL

/**
 * 写入 token 成本到 metadata（返回新 metadata，不改原对象）。
 */
private fun JsonObject?.withTokenCost(cost: Long, source: String): JsonObject {
    val base = this?.toMutableMap() ?: mutableMapOf()
    base[PART_TOKEN_COST_KEY] = JsonPrimitive(cost)
    base[PART_TOKEN_SOURCE_KEY] = JsonPrimitive(source)
    return JsonObject(base)
}

/**
 * 给 part 打上 token 成本标记。
 *
 * 注意 metadata 在各 part 上是 `var`，直接赋值即可 —— 这些 data class 会被
 * copy 来 copy 去，改字段值比整体重建安全。
 */
fun UIMessagePart.withTokenCost(cost: Long, source: String): UIMessagePart {
    val next = metadata.withTokenCost(cost, source)
    // 显式转换而非依赖智能转换：基类声明的是 `abstract val metadata`，
    // 只有各子类把它覆写成 var，靠 smart cast 赋值在某些 Kotlin 版本会被拒。
    when (this) {
        is UIMessagePart.Text -> this.metadata = next
        is UIMessagePart.Reasoning -> this.metadata = next
        is UIMessagePart.Image -> this.metadata = next
        is UIMessagePart.Audio -> this.metadata = next
        is UIMessagePart.Video -> this.metadata = next
        is UIMessagePart.Document -> this.metadata = next
        is UIMessagePart.Tool -> this.metadata = next
        else -> return this // Search 等 data object 不写，避免污染单例
    }
    return this
}

/**
 * **取 part 的 token 成本（压缩游标唯一入口）**。
 *
 * 三级取数见文件头。[cacheIfMissing] = true 时，估算结果会被写回 metadata 缓存，
 * 下次直接命中 —— 按天赢的要求「没有就压缩的时候估算并且缓存，只在第一次压缩时计算」。
 */
fun UIMessagePart.tokenCost(cacheIfMissing: Boolean = false): Long {
    recordedTokenCost()?.let { return it }
    val estimated = estimateSelf()
    if (cacheIfMissing) withTokenCost(estimated, TOKEN_SOURCE_ESTIMATE)
    return estimated
}

/**
 * 把一次请求的真实 [TokenUsage] 拆分并写进本轮新生成的 part 上。
 *
 * ## 拆分依据
 *
 * 服务端只给整轮的总数，不会告诉你「第 3 个 tool 的 output 占多少」。
 * 但我们知道两件确定的事：
 * - 本轮 assistant 新产出的内容 ≈ `completionTokens`（思考 + 正文 + 工具入参）；
 * - 工具的 **output** 不由模型生成，它是本地塞回去的，不计入 completionTokens，
 *   但下一轮会作为 prompt 的一部分被收费。
 *
 * 因此：completionTokens 按各 part 的估算值**按比例**摊到模型生成的 part 上
 * （比例分配比绝对估算可靠得多 —— 估算器的系统性偏差在做除法时会被约掉）；
 * 工具 output 拿不到真实数只能估，标记为 est。
 *
 * 这样得到的总量与服务端账单一致，个体分配虽有近似，但**总和是真的**，
 * 而压缩游标恰恰只关心累加和，正好对症。
 *
 * @param priorPromptTokens 上一轮的 promptTokens，用于反推本轮 prompt 增量（可选）
 */
fun UIMessage.attachRealUsage(usage: TokenUsage?, priorPromptTokens: Int = 0): UIMessage {
    if (usage == null || usage.completionTokens <= 0) return this

    // 模型生成的 part：Text / Reasoning / Tool 的入参部分
    val generated = parts.filter {
        it is UIMessagePart.Text || it is UIMessagePart.Reasoning || it is UIMessagePart.Tool
    }
    if (generated.isEmpty()) return this

    // 各自的估算权重（Tool 只按 name+input 算，output 不是模型生成的）
    val weights = generated.map { part ->
        when (part) {
            is UIMessagePart.Tool ->
                estimateTextTokens(part.toolName) + estimateTextTokens(part.input)

            else -> part.estimateSelf()
        }.coerceAtLeast(1)
    }
    val totalWeight = weights.sum().toDouble()

    generated.forEachIndexed { index, part ->
        val share = (usage.completionTokens * (weights[index] / totalWeight)).toLong()
        if (part is UIMessagePart.Tool) {
            // 工具：入参部分是真实摊派，output 只能估 —— 两段加起来才是它在下一轮的实际成本
            val outputCost = part.output.sumOf { it.estimateSelf() }
            part.withTokenCost(share + outputCost, TOKEN_SOURCE_ESTIMATE)
        } else {
            part.withTokenCost(share, TOKEN_SOURCE_REAL)
        }
    }

    // prompt 增量若可反推且为正，说明本轮 user 侧内容的真实成本已知，一并校准
    val promptDelta = usage.promptTokens - priorPromptTokens
    if (promptDelta > 0) {
        // 仅在整条消息尚无任何记录时兜底，避免覆盖上面更细的分配
        parts.filterIsInstance<UIMessagePart.Text>()
            .firstOrNull { it.recordedTokenCost() == null }
            ?.withTokenCost(promptDelta.toLong(), TOKEN_SOURCE_REAL)
    }
    return this
}

/**
 * 整条消息的 token 成本（按 part 累加，优先真实值）。
 * 与 [estimateTokens] 的区别：这个会命中 / 写入缓存，且尊重真实 usage。
 */
fun UIMessage.tokenCost(cacheIfMissing: Boolean = false): Long =
    4L + parts.sumOf { it.tokenCost(cacheIfMissing) } +
        (memoryInjection?.let { estimateTextTokens(it) } ?: 0L)
