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
 * 1. **真实 usage 反推**（[attachRealUsage] / [calibrateTokenCostsFromUsage]）：
 *    分词是服务端做的，只有它知道确切数字。
 * 2. **缓存的估算值**：没有 usage（本地消息、旧数据）时，首次需要用到才算一次，
 *    结果同样写进 metadata，之后直接读。part 内容不可变，所以缓存永不失效。
 * 3. **实时估算**：前两者都没有时退回 [estimateSelf]。
 *
 * ## 2026-08-30 补课：旧对话**也有** usage
 *
 * 第一版实现里有个判断错误 —— 以为「没有 part 级缓存的旧对话就只能估」。
 * 事实是 [UIMessage.usage] 本身就是随会话 JSON 一起落库的持久字段，
 * **每一条历史 assistant 消息都带着服务端返回的真实 promptTokens / completionTokens**。
 * 也就是说，全部历史的真实账本一直躺在库里，只是从没被拆开用过。
 *
 * 于是有了 [calibrateTokenCostsFromUsage]（下称「差分校准」），它比只看
 * completionTokens 的 [attachRealUsage] 强在两点：
 * - **覆盖非模型生成的内容**：user 原文、工具 output 从不进 completionTokens，
 *   但它们下一轮会作为 prompt 被收费。相邻两次请求的 `promptTokens` 之差，
 *   恰好就是这中间新增内容的真实 token 数。
 * - **自动约掉恒定开销**：system prompt + 人设 + 记忆注入 + 工具 schema 那
 *   ~80k 常数在两次 promptTokens 里都在，一做差就没了。（08-28 那次
 *   「overhead 把阈值顶爆」的病根，在差分口径下天然不存在。）
 *
 * ## 存储
 *
 * 复用每个 part 都已有的 `metadata: JsonObject?`，**不加数据库字段、不做 migration**。
 * 旧数据没有这个 key，读出来是 null，自然走 2/3 级，天然向后兼容。
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
 * 整条消息的 token 成本（按 part 累加，优先真实值）。
 * 与 [estimateTokens] 的区别：这个会命中 / 写入缓存，且尊重真实 usage。
 */
fun UIMessage.tokenCost(cacheIfMissing: Boolean = false): Long =
    4L + parts.sumOf { it.tokenCost(cacheIfMissing) } +
        (memoryInjection?.let { estimateTextTokens(it) } ?: 0L)

// ---------------------------------------------------------------------------
// 权重：把一个整数总额按「各 part 的相对大小」摊下去
// ---------------------------------------------------------------------------

/**
 * 模型**生成**侧的权重（对应 completionTokens）：
 * Tool part 只算 name + input —— output 是本地塞回去的，模型没生成它。
 */
private fun UIMessagePart.generationWeight(): Long = when (this) {
    is UIMessagePart.Tool -> estimateTextTokens(toolName) + estimateTextTokens(input)
    is UIMessagePart.Text, is UIMessagePart.Reasoning -> estimateSelf()
    else -> 0L
}

/**
 * **prompt** 侧的权重（对应下一轮 promptTokens 的增量）。
 *
 * [alreadyCoveredByCompletion] = 该 part 已经从本轮 completionTokens 里拿过真值了：
 * - Tool：入参已覆盖，只剩 output 需要从 prompt 差分里认领；
 * - Text / Reasoning：整个都覆盖了，权重归 0。否则它会先从 completion 拿一份、
 *   再从下一轮 prompt 差分里拿一份 —— 同一段文字被记两遍，保留区往小里算。
 */
private fun UIMessagePart.promptWeight(alreadyCoveredByCompletion: Boolean): Long = when {
    this is UIMessagePart.Tool -> output.sumOf { it.estimateSelf() }
    alreadyCoveredByCompletion -> 0L
    else -> estimateSelf()
}

/**
 * 按权重把 [total] 摊到 [indices] 指向的 part 上，累加进 [into]。
 *
 * 用**比例分配**而不是绝对估算：估算器的系统性偏差（CJK 0.7、拉丁 4 字符/token
 * 这些拍脑袋的常数）在做除法时会被约掉，而总和恰好等于服务端账单。
 * 压缩游标只关心累加和，正好对症。
 *
 * **为什么按下标而不是按 part 对象做 key**：[UIMessagePart] 是 data class，
 * 两个内容相同的 part（同一句话说两遍、同一个工具调两次）会 `equals`，
 * 拿它当 key 会把两笔份额合并后给两边各写一遍 = 凭空翻倍；
 * 而且 `metadata` 是 `var`，写回时修改它会当场改变 key 的 hashCode，把哈希表弄坏。
 * 位置下标既唯一又不变，两个坑一起绕开。
 *
 * 全员权重为 0（例如整条只有一个 Search part）时退化为均分，避免把 total 丢掉。
 */
private fun distributeByWeight(
    total: Long,
    indices: List<Int>,
    weights: List<Long>,
    into: LongArray,
) {
    if (total <= 0 || indices.isEmpty()) return
    val sum = weights.sum()
    if (sum <= 0) {
        val each = total / indices.size
        if (each <= 0) return
        indices.forEach { into[it] += each }
        return
    }
    // 末位补齐：整数除法的余数全给最后一个非零权重的 part，保证 Σ 严格等于 total
    var remaining = total
    var lastSlot = -1
    indices.forEachIndexed { i, slot ->
        if (weights[i] > 0) {
            val share = total * weights[i] / sum
            into[slot] += share
            remaining -= share
            lastSlot = slot
        }
    }
    if (remaining > 0 && lastSlot >= 0) into[lastSlot] += remaining
}

/** 差分校准的执行结果，只用于日志与可观测性。 */
data class TokenCalibrationReport(
    /** 拿到真实值的 part 数 */
    val realParts: Int = 0,
    /** 仍然只能靠估算的 part 数 */
    val estimatedParts: Int = 0,
    /** 真实值合计 */
    val realTokens: Long = 0,
    /** 成功做出差分的区段数（0 = 整段对话一次 usage 都没有，全靠估） */
    val segments: Int = 0,
) {
    val coverage: Double
        get() = (realParts + estimatedParts).takeIf { it > 0 }
            ?.let { realParts.toDouble() / it } ?: 0.0

    override fun toString(): String =
        "real=$realParts/est=$estimatedParts parts, realTokens=$realTokens, " +
            "segments=$segments, coverage=${"%.1f".format(coverage * 100)}%"
}

/**
 * **用落库的真实 usage 给整段消息做 part 级 token 校准（promptTokens 差分法）。**
 *
 * ## 原理
 *
 * 每条 assistant 消息都带着那一次请求的 [TokenUsage]。设第 k 次请求的
 * promptTokens 为 `P_k`，则：
 *
 * ```
 * P_k - P_{k-1}  =  第 k-1 次回复之后、第 k 次请求之前新增进上下文的全部内容
 *                =  上一轮 assistant 的正文/工具入参 + 工具 output + 用户新消息
 * ```
 *
 * 这个差值是**服务端分词器给出的真数**，且两侧都含同样的 system/人设/工具 schema
 * 常数开销，一减就没了 —— 不需要（也不该）去猜那 80k 到底是多少。
 *
 * 于是每个区段拿到一个真实总额，再按各 part 的估算值**按比例**摊下去。
 * 单个 part 的分配是近似的，但**区段总和是真的**，而压缩游标只做累加和比较，
 * 精度正好落在需要的地方。
 *
 * 另外每条 assistant 自己的 completionTokens 直接摊给它本轮生成的 part
 * （Text / Reasoning / Tool 的入参），这部分不用等下一次差分。
 *
 * ## 不重复计费
 *
 * 一段 delta 里同时含着「上一轮 assistant 的产出」和「工具 output + 用户新消息」。
 * 前者已经从那一轮的 completionTokens 拿到了真值，所以做差分前先把它减掉，
 * 并把它们在 pending 里的权重归 0（Tool 只保留 output 权重）。
 * 不这么干的话同一段正文会被计两遍，游标会以为保留区比实际胖，刀口往后跑。
 *
 * ## 幂等
 *
 * 整个函数是「先算一张完整的分配表，最后一次性写回」，重复调用得到同样结果，
 * 不会像逐 part 累加那样越调越大。没被真实值覆盖到的 part 一律回落估算并标 est。
 *
 * ## 边界与降级
 *
 * - 第一条带 usage 的消息**不做差分**（没有基线，`P_1 - 0` 会把 80k 常数
 *   全摊到开头几条消息上，是纯污染），只用它建立基线。
 * - 差值 ≤ 0（换模型、开新会话、服务端口径变化、上下文被折叠过）→ 跳过该段，
 *   段内 part 回落估算。宁可这一段不准，也不能把负数或荒谬值灌进去。
 * - 一次 usage 都没有的对话 → 全部走估算，与改动前完全一致（零回归）。
 */
fun List<UIMessage>.calibrateTokenCostsFromUsage(): TokenCalibrationReport {
    if (isEmpty()) return TokenCalibrationReport()

    // 把全部 part 拉成一条平铺序列，用**位置下标**做唯一标识。
    // 不用 part 对象当 key 的理由见 [distributeByWeight]（data class 值相等 + var metadata）。
    val flat = ArrayList<UIMessagePart>()
    val ownerRange = ArrayList<IntRange>(size)
    forEach { m ->
        val from = flat.size
        flat.addAll(m.parts)
        ownerRange += from until flat.size
    }
    val real = LongArray(flat.size)
    // 标记哪些 part 已经从 completionTokens 拿过真值，避免下一轮 prompt 差分里重复计入
    val coveredByCompletion = BooleanArray(flat.size)

    var prevPrompt = 0
    var haveBaseline = false
    var segments = 0
    // 上一条 assistant 的 completionTokens：它已被单独认领，下一次差分要从 delta 里扣掉
    var prevCompletion = 0
    // 等待被下一次 promptTokens 差分认领的 part 下标（user 原文、工具 output 等）
    val pending = mutableListOf<Int>()

    forEachIndexed { msgIdx, message ->
        val range = ownerRange[msgIdx]
        val usage = message.usage
        val prompt = usage?.promptTokens ?: 0
        if (usage == null || prompt <= 0) {
            // 无 usage 的消息（user / 本地插入 / 旧数据）整体挂起，等下一次差分认领
            pending += range
            return@forEachIndexed
        }

        // 扣掉上一轮已由 completionTokens 认领的那部分，剩下的才是
        // 「工具 output + 用户新消息」这些非模型生成内容的真实成本。
        val delta = prompt - prevPrompt - prevCompletion
        if (haveBaseline && delta > 0 && pending.isNotEmpty()) {
            distributeByWeight(
                total = delta.toLong(),
                indices = pending.toList(),
                weights = pending.map {
                    flat[it].promptWeight(coveredByCompletion[it]).coerceAtLeast(0)
                },
                into = real,
            )
            segments++
        }
        pending.clear()
        prevPrompt = prompt
        haveBaseline = true

        // 本条自己的产出 = completionTokens，直接摊给生成侧 part
        val completion = usage.completionTokens
        prevCompletion = completion.coerceAtLeast(0)
        if (completion > 0) {
            val generated = range.filter { flat[it].generationWeight() > 0 }
            if (generated.isNotEmpty()) {
                distributeByWeight(
                    total = completion.toLong(),
                    indices = generated,
                    weights = generated.map { flat[it].generationWeight() },
                    into = real,
                )
                generated.forEach { coveredByCompletion[it] = true }
            }
        }
        // 工具 output 不由模型生成、不进 completionTokens，但下一轮要按 prompt 收费，
        // 所以把本条的 part 继续挂到 pending 里等下一次差分（Tool part 会因此
        // 同时拿到「入参份额 + output 份额」两笔，正是它的真实成本）。
        pending += range
    }

    // ---- 一次性写回 ----
    var realParts = 0
    var estParts = 0
    var realTokens = 0L
    flat.forEachIndexed { i, part ->
        val v = real[i]
        when {
            v > 0 -> {
                part.withTokenCost(v, TOKEN_SOURCE_REAL)
                realParts++
                realTokens += v
            }
            // 已有的 real 不得被降级成 est：流式落库时 attachRealUsage 打上的值
            // 覆盖的是对话尾巴那几条——它们还没有「下一次 promptTokens」可供差分。
            part.hasRealTokenCost() -> {
                realParts++
                realTokens += part.recordedTokenCost() ?: 0L
            }

            else -> {
                // 没被真实值覆盖到 → 估算并缓存，标 est
                part.withTokenCost(part.estimateSelf(), TOKEN_SOURCE_ESTIMATE)
                estParts++
            }
        }
    }
    return TokenCalibrationReport(
        realParts = realParts,
        estimatedParts = estParts,
        realTokens = realTokens,
        segments = segments,
    )
}

/**
 * 把**单次**请求的真实 [TokenUsage] 拆分并写进本轮新生成的 part 上。
 *
 * 这是流式生成落库那一刻的「即时挂载」，只能用到 completionTokens
 * （本轮的 promptTokens 要等下一轮才能做差分）。整段历史的完整校准由
 * [calibrateTokenCostsFromUsage] 负责，两者写的是同一组 metadata key，
 * 后者幂等重算，不会互相打架。
 */
fun UIMessage.attachRealUsage(usage: TokenUsage?): UIMessage {
    if (usage == null || usage.completionTokens <= 0) return this
    val generated = parts.indices.filter { parts[it].generationWeight() > 0 }
    if (generated.isEmpty()) return this

    val real = LongArray(parts.size)
    distributeByWeight(
        total = usage.completionTokens.toLong(),
        indices = generated,
        weights = generated.map { parts[it].generationWeight() },
        into = real,
    )
    generated.forEach { i ->
        val part = parts[i]
        val share = real[i]
        if (share <= 0) return@forEach
        if (part is UIMessagePart.Tool) {
            // 工具：入参是真实摊派，output 这一刻只能估 —— 等下一轮 promptTokens
            // 差分校准时会被 calibrateTokenCostsFromUsage 覆盖成真数。
            part.withTokenCost(share + part.output.sumOf { it.estimateSelf() }, TOKEN_SOURCE_ESTIMATE)
        } else {
            part.withTokenCost(share, TOKEN_SOURCE_REAL)
        }
    }
    return this
}
