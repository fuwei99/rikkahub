package me.rerere.rikkahub.data.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/**
 * 把 `Settings` 摊成「字段名 → 值」并算内容指纹（大统一重构 v2 §2.3）。
 *
 * ## 为什么用 JsonObject 而不是 getter lambda
 *
 * `SyncFieldRegistry` 只登记字段**名**，不登记 `{ it.chatModelId }` 这种 getter。
 * 一开始看像是偷懒，实际是有意的：84 个字段就要写 84 个 getter + 84 个 setter，
 * 那 168 行 lambda 每一个都是抄错的机会（`copy(fastModelId = v)` 里写成
 * `chatModelId` 编译器完全不会报错，因为类型一样都是 `Uuid`）。
 *
 * 换成走 `kotlinx.serialization`：`Json.encodeToJsonElement(settings)` 出来的
 * `JsonObject` 的 key **就是** `descriptor.elementNames` 里的那些名字，
 * 与穷尽性测试对账用的是同一个来源，天然不可能对不上。零反射、零手写 lambda。
 *
 * 代价是每次比对要序列化一遍 `Settings`。实测这不是问题：
 * `PreferencesStore.update()` 本来就要 `JsonInstant.encodeToString` 十几个子对象
 * 才能写进 DataStore，多一次整体编码在同一个数量级，且只发生在用户改设置时。
 *
 * ## sha 的规范化要求
 *
 * 两台设备对**同一个值**必须算出**同一个 sha**，否则 §1.4 的平票裁决会永远
 * 判不平，两端各自认为自己该赢 → 反复互推（旧「平票保本地」的病）。
 * 所以 [canonicalize] 必须把 JsonObject 的 key 排序 —— kotlinx 保持声明顺序，
 * 而**不同版本的 app 声明顺序可能不同**（新版本在中间插了个字段），
 * 不排序就会让同值算出不同 sha。数组顺序**不排**（列表顺序本身是数据）。
 */
object SyncFieldDigest {

    /**
     * 序列化用的 Json 实例。
     *
     * - `encodeDefaults = true`：**必须**。默认值不编码的话，一个字段从
     *   「非默认」改回「默认」时 JsonObject 里会直接少这个 key，
     *   于是 [fieldsOf] 认为它「不存在」→ 不打戳 → 这次改动同步不出去。
     * - `explicitNulls = true`：同理，`titleModelId = null` 必须留下 key，
     *   否则「清空标题模型」这个动作会丢。
     */
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /** 摊平成 字段名 → JsonElement。key 集合等于 `descriptor.elementNames`（除 @Transient） */
    fun fieldsOf(element: JsonElement): Map<String, JsonElement> =
        (element as? JsonObject)?.toMap() ?: emptyMap()

    /**
     * 递归规范化：对象按 key 排序，数组保序，其余原样。
     *
     * 只影响 sha 计算，不影响落地的值。
     */
    fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, canonicalize(v)) }
        }

        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        // JsonNull 是 JsonPrimitive 的子类，被这一支覆盖；单独写 `JsonNull ->`
        // 会让编译器报 unreachable branch
        is JsonPrimitive -> element
    }

    /**
     * 字段值的内容指纹：规范化 JSON 的 sha256，取前 16 个 hex 字符。
     *
     * 16 hex = 64 bit。用途是「判等 + 平票定序」，不是密码学场景；
     * 64 bit 碰撞概率对 84 个字段可以忽略，省下的存储与线上流量更实在。
     */
    fun shaOf(element: JsonElement?): String {
        if (element == null) return EMPTY_SHA
        val text = canonicalize(element).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val hex = StringBuilder(16)
        for (i in 0 until 8) {
            // toInt() and 0xFF：Byte 在 Kotlin 是 signed，0x8A 会变成 -118，
            // 不掩码会拼出 "ffffff8a" 这种脏值
            val v = digest[i].toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 计算 [current] → [next] 之间**真正变化**的字段名。
     *
     * 「真正变化」= 规范化后 sha 不同。为什么不能直接比对象：
     * `Settings` 里的列表元素带 `updatedAt` 之类的时间戳字段，
     * 直接 `!=` 会把「同值但时间戳不同」判成变化，于是每轮同步都自造一次冲突
     * （现有 `stampListChanges` 的 `normalize` 钩子就是为了防这个，见 SyncVersionMap）。
     */
    fun changedFields(
        current: JsonElement,
        next: JsonElement,
    ): Set<String> {
        val a = fieldsOf(current)
        val b = fieldsOf(next)
        val names = a.keys + b.keys
        return names.filterTo(mutableSetOf()) { name ->
            shaOf(a[name]) != shaOf(b[name])
        }
    }

    /** 空值/缺失字段的 sha 占位。与任何真实值的 sha 都不相等 */
    const val EMPTY_SHA = "0000000000000000"

    /** 供外部（打戳、envelope 组装）复用同一个 Json 配置 */
    fun json(): Json = json
}
