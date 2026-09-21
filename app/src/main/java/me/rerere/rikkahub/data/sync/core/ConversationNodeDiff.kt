package me.rerere.rikkahub.data.sync.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.util.stripLoneSurrogates
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.backend.NodePushRow
import java.security.MessageDigest

/**
 * P3 会话消息节点级增量（S2）：把推送粒度从「整个会话 JSON」降到「一条消息」。
 *
 * 本地仍以会话为单位入队 outbox（不改 Repository 写钩），flush 时用本对象对比
 * 「上次已推 node sha 表」与本地当前节点，只生成变化节点的语句，一次 `batch()`
 * 打包：长会话追加一条消息 = 1 条 UPSERT（≤ 几 KB），不再整包重传。
 *
 * 兼容策略（S4/S5）：双写期 node 表与 conversations.data 并存，老客户端仍读
 * 整包；`nodeOnlyPush` 开启后才关闭整包上行。node 表数据任何时候都可安全
 * 重建会话（pull 侧 S3），因此纯增量写入不产生任何数据损失。
 *
 * 判定规则：
 * - 新增 / sha 变化 → UPSERT（ON CONFLICT DO UPDATE，**带 LWW 仲裁**）
 * - 本地已消失的 node → tombstone（deleted=1，同样带 LWW 保护）
 * - 无变化 → 不生成语句（本地状态照常推进，跳过下一次全量比对）
 *
 * ## 2026-09-18 三处修正（D1 配额烧穿 + 结构分叉 + 旧盖新丢数据）
 *
 * 1. **idx 不再参与 UPDATE**。idx 是推送方本地下标（位置量），两端各自 append
 *    必然撞车；排序基准改由 seq_key（跨端恒定的身份量）承担。
 * 2. **UPSERT 加 LWW**。原判据只有 `sha != excluded.sha`，谁后推谁赢，导致
 *    对端的旧快照能覆盖本端完整内容（实测数据丢失）。
 * 3. **tombstone 加 LWW**。删除是不可逆动作，不能让慢时钟设备误删新数据。
 */
object ConversationNodeDiff {

    private val defaultJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * 单轮允许的最大 tombstone 数量。超过即视为异常，本轮拒绝生成任何删除语句。
     *
     * 依据：正常用户行为里「一次删掉 8 条以上消息」极罕见，而故障场景
     * （本地基准被残缺数据覆盖）一杀就是几十上百条。宁可漏删也不能错删 ——
     * 漏删的墓碑下一轮还会补上，错删的历史得从墓碑里捞。
     */
    const val MAX_TOMBSTONES_PER_ROUND = 8

    /**
     * 单轮允许删除的比例上限（相对于本地当前节点数）。
     *
     * 与 [MAX_TOMBSTONES_PER_ROUND] 取「都满足才放行」：长会话里删 8 条可能正常，
     * 但一个 74 条的会话一次删 51 条（69%）一定是故障。
     *
     * 取值 0.15 是拿 2026-09-11 三个真实事故样本反推的下界
     * （69% / 100% / 28%），同时保证「200 条长会话清掉 15 条」这类
     * 正常批量整理仍能通过。
     */
    const val MAX_TOMBSTONE_RATIO = 0.15

    data class Result(
        /** 待推送的节点 upsert 行（新增 / sha 变化） */
        val rows: List<NodePushRow>,
        /** 本地已消失、需要打墓碑的 nodeId */
        val tombstones: List<String>,
        /** 本次推送后应落盘的 nodeId -> sha 状态（供下一轮 diff 基准） */
        val newState: Map<String, String>,
        /**
         * 安全阀拦下的批量删除说明；非 null 表示本轮**故意没有**生成 tombstone 语句，
         * 调用方应打 error 日志 + 审计，并且**不要推进 sync_state 基准**
         * （否则下一轮 diff 会以为这些节点已处理完，删除被永久吞掉）。
         */
        val suppressedDeletion: String? = null,
    ) {
        val isEmpty: Boolean get() = rows.isEmpty() && tombstones.isEmpty()
    }

    /**
     * @param convId 会话 id
     * @param nodes 本地当前节点序列（已 offload 大 part）
     * @param oldState 上次成功推送后的 nodeId -> sha；首次推送传空 map（全量上推）
     * @param myDevice 写入者裁决键（[SyncLocalPrefs.tieBreakKey]）
     */
    fun compute(
        convId: String,
        nodes: List<MessageNode>,
        oldState: Map<String, String>,
        myDevice: String,
        now: Long,
        json: Json = defaultJson,
    ): Result {
        val rows = mutableListOf<NodePushRow>()
        val newState = LinkedHashMap<String, String>()
        val seen = HashSet<String>(nodes.size)

        nodes.forEachIndexed { idx, node ->
            val nodeId = node.id.toString()
            seen += nodeId
            // 消毒后再算 sha：孤立代理若带进上行 JSON，云端存下来的就是破损字面
            // （同一根因见 ai/util/SurrogateSafe.kt）
            val data = json.encodeToString(node).stripLoneSurrogates()
            val sha = sha256Hex(data)
            newState[nodeId] = sha
            if (oldState[nodeId] != sha) {
                rows += NodePushRow(
                    convId = convId,
                    nodeId = nodeId,
                    idx = idx,
                    seqKey = seqKeyOf(node),
                    selectIndex = node.selectIndex,
                    updatedAt = now,
                    deleted = 0,
                    sha = sha,
                    data = data,
                    lastDevice = myDevice,
                )
            }
        }

        // 本地已删除的节点 → 云端 tombstone（幂等，且保留行便于审计）
        val vanished = oldState.keys.filter { it !in seen }

        // ★ 批量删除安全阀（2026-09-11 数据丢失事故）
        //
        // 事故链：Fork 熔断退化为 TakeRemote → 用云端**空整包**覆盖本地会话 →
        // 本地只剩残骸 → 下一轮本函数看见「几十个节点消失了」→ 老老实实全部
        // tombstone → 云端历史同步归零。两端同归于尽，一秒内 51 条。
        //
        // 教训：diff 是**无状态的机械对比**，它无法分辨「用户真删了」和
        // 「上游把我的输入搞坏了」。既然分辨不了，就必须对「删除」这个不可逆
        // 动作设硬上限 —— 正常编辑永远碰不到这条线，故障永远撞得上。
        //
        // 拦截后不推进基准：下一轮拿同样的 oldState 重新 diff。若确实是用户删的，
        // 用户会继续删/或分批次落到阈值内；若是故障，pull 会把节点补回来，
        // vanished 自然消失。两种情况都能自愈，唯独不会误杀历史。
        val aliveCount = nodes.size
        // 条数闸：绝对数量过大 = 一定不是人手删的
        val overCount = vanished.size > MAX_TOMBSTONES_PER_ROUND
        // 比例闸：本地全空时视为 100%（这是最危险的信号，绝不能因 alive=0 而漏判 ——
        // 事故里 conv e3157067 正是「本地被清成 0 条、云端 112 条全灭」）
        val ratio = if (aliveCount == 0) 1.0
        else vanished.size.toDouble() / (aliveCount + vanished.size)
        val overRatio = ratio > MAX_TOMBSTONE_RATIO
        if (vanished.isNotEmpty() && overCount && overRatio) {
            return Result(
                rows = rows,
                tombstones = emptyList(),
                // 基准保持原样：把消失节点的旧 sha 留在 state 里，下轮继续观察
                newState = newState.apply {
                    vanished.forEach { id -> oldState[id]?.let { put(id, it) } }
                },
                suppressedDeletion = "conv=$convId would tombstone ${vanished.size} nodes " +
                    "(local alive=$aliveCount, limit=$MAX_TOMBSTONES_PER_ROUND/" +
                    "${(MAX_TOMBSTONE_RATIO * 100).toInt()}%); refusing — likely upstream corruption",
            )
        }

        // 墓碑的实际 UPDATE 挪到后端（[StorageBackend.tombstoneNodes]）——
        // 语义接口里两个 driver 各自表达；本对象只负责「哪些节点该打墓碑」。
        return Result(rows = rows, tombstones = vanished, newState = newState)
    }

    /*
     * ★ 关于 idx / LWW / 墓碑守卫 —— 三处 2026-09-18 修正的落点搬家说明
     *
     * 本对象原来直接**拼 SQL**（`upsertStatement` + 一段墓碑 UPDATE），Step I-5 把它
     * 降级成「只算哪些节点变了」，实际语句由后端表达：
     *
     * - `idx` 仍**只在 INSERT 出现**，UPDATE 分支不碰它。idx 是推送方本地下标（位置量），
     *   两端各自 append 必然撞车；排序基准由 `seq_key`（跨端恒定的身份量）承担。
     * - LWW 仲裁改由后端 SQL / Postgres 函数执行，规则逐字不变：
     *   `sha != excluded.sha AND (updated_at > ... OR (= AND last_device >))`。
     * - ★ **一处刻意的收紧**：后端（`D1Backend.UPSERT_NODE_SQL` /
     *   `jf_upsert_nodes`）比原来的 `upsertStatement` **多一道墓碑守卫**
     *   `NOT (deleted = 1 AND excluded.deleted = 0)`。
     *
     *   原实现 UPDATE 分支硬写 `deleted = 0`，于是**已墓碑的节点会被任何一次
     *   upsert 复活**：设备 A 删了一条消息，设备 B 还没拉、拿着旧 sha 推上来，
     *   就把它救回来了 —— 删除不可逆这条规矩在节点上其实是破的。
     *   多出的这道闸让节点与 conversations 行的规则一致（那边一直有这道闸，
     *   见 `UPSERT_CONVERSATION_SQL`），代价是「已删消息不会复活」。
     *   端上没有撤销删除的入口，所以这条收紧不会有用户可见的功能损失。
     */

    /**
     * 跨端确定性排序键（方案 B 核心）。
     *
     * 格式：`<16 位零填充的 UTC 毫秒时间戳>:<nodeId>`
     *
     * 两个不可妥协的约束：
     * 1. **只看节点自身内容**。一旦掺进「本地下标」「推送时间」这类环境量，
     *    两端就会算出不同的 key，排序立刻发散 —— idx 就是这么死的。
     * 2. **必须用 UTC**。createdAt 是不带时区的 LocalDateTime，用系统默认时区转换
     *    会让两台不同时区的设备算出相差几小时的戳（同 D2 bug）。
     *
     * 零填充到 16 位：毫秒时间戳当前是 13 位，留到 16 位可用到公元 33 万年；
     * 定长保证字典序 == 数值序，于是云端 `ORDER BY seq_key` 直接可用。
     */
    fun seqKeyOf(node: MessageNode): String {
        val ts = node.messages.mapNotNull { msg ->
            runCatching { msg.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
        }.minOrNull() ?: Long.MAX_VALUE
        return "%016d:%s".format(ts, node.id.toString())
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
