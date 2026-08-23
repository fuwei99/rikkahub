package me.rerere.rikkahub.data.sync.core

import android.util.Log
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.db.dao.SyncFieldVersionDao
import me.rerere.rikkahub.data.db.entity.SyncFieldVersionEntity

/**
 * 字段级 HLC 打戳（大统一重构 v2 §2.3，阶段 A 第 4 项）。
 *
 * ## 职责
 *
 * 用户改设置时，找出**真正变化**的字段，给它们打上 `SyncClock.now()`。
 * 这是「字段级 CRDT」的数据来源：没有戳，pull 时就无从判断谁更新。
 *
 * ## 三条不能违反的规则
 *
 * ### 1. 只给变化的字段打戳
 *
 * 一次 `update()` 通常只改一个开关，剩下 83 个字段原样。若无脑全量打戳，
 * 等于宣称「我刚改了全部 84 个字段」，pull 时会把对端所有并发改动全判输
 * —— 这就是现在 bug 的加强版。变化判定走 [SyncFieldDigest.changedFields]（比 sha）。
 *
 * ### 2. 云端下拉的写入**不得**打本机戳
 *
 * `SyncApplyGate.applyingRemote == true` 时，`PreferencesStore.update()` 收到的是
 * **合并结果**。此时字段的正确 hlc 是**胜出方带来的那个 hlc**，不是本机 now()。
 * 若打本机戳：本机 now() 必然大于云端 hlc → 下一轮 push 时本机「赢」→
 * 把刚采纳的云端值当成本机新事实推回去 → **两端互推同一个值，永不静默**。
 * 这与 2026-08-18 那个 pendingUnlock 复活是同一类错误。
 * 所以远端应用路径必须走 [applyRemoteWinners]，而不是 [stampLocalChanges]。
 *
 * ### 3. 默认值永不打戳（§2.5）
 *
 * 空表 = 全字段 unknown = 首次 pull 全采纳云端。绝不能在升级/首次启动时
 * 给当前 `Settings` 全字段打 now()，否则晚升级设备的默认值会刷掉对端真实配置。
 * 本类没有任何「初始化时全量打戳」的入口，就是为了让这件事**做不到**。
 */
class SyncFieldStamper(
    private val dao: SyncFieldVersionDao,
    private val clock: SyncClock,
) {

    /**
     * 用户本机改动：给 [current] → [next] 之间变化的字段打新戳。
     *
     * @return 本次打戳的字段名 → hlc，供审计日志使用
     */
    suspend fun stampLocalChanges(
        current: JsonElement,
        next: JsonElement,
    ): Map<String, Long> {
        val changed = SyncFieldDigest.changedFields(current, next)
        if (changed.isEmpty()) return emptyMap()

        val nextFields = SyncFieldDigest.fieldsOf(next)
        val rows = mutableListOf<SyncFieldVersionEntity>()
        val stamped = mutableMapOf<String, Long>()

        changed.forEach { name ->
            // 不上云的字段没有打戳的意义：它们永远不参与裁决。
            // 少写 84 行里那十几行，也避免 LOCAL 字段（d1Config / 各种 token）
            // 的 sha 被记进账簿。
            // 注册表里查不到的字段（理论上被穷尽性测试挡住，不该发生）按不打戳处理：
            // 宁可少一个戳（退化成 unknown，保本地不上推），也不要给未知语义的字段
            // 打戳后当成事实推上云。
            val kind = SyncFieldRegistry.of(name)?.kind ?: return@forEach
            if (kind == SyncFieldKind.LOCAL || kind == SyncFieldKind.NOISE) return@forEach

            // ★ 每个字段单独取 now()：HLC 的 counter 会递增，保证同一次 update 里
            // 多个字段的 hlc 互不相同。这不是浪费 —— 若共用一个 hlc，
            // 两端在「同一毫秒各改一个字段」时会平票，退化到比 sha，
            // 结果是「按字段内容字典序」而非「按真实先后」裁决。
            val hlc = clock.now()
            stamped[name] = hlc
            rows += SyncFieldVersionEntity(
                field = name,
                hlc = hlc,
                sha = SyncFieldDigest.shaOf(nextFields[name]),
            )
        }

        if (rows.isNotEmpty()) {
            dao.putAll(rows)
            Log.d(TAG, "stamped ${rows.size} local field(s): ${rows.joinToString { it.field }}")
        }
        return stamped
    }

    /**
     * 云端下拉应用后：把**胜出方的 hlc** 写进版本表，而不是本机 now()。
     *
     * 见类注释规则 2 —— 这是「不互推」的关键。
     *
     * @param winners 字段名 → (胜出 hlc, 胜出值的 sha)
     */
    suspend fun applyRemoteWinners(winners: Map<String, Pair<Long, String>>) {
        if (winners.isEmpty()) return
        val rows = winners.mapNotNull { (name, pair) ->
            val (hlc, sha) = pair
            // hlc == UNKNOWN 的字段不写行：写进去等于把「不知道」记录成「知道且很旧」，
            // 之后这个字段会永远输给任何带戳的值，包括对端的默认值。
            if (hlc == SyncClock.UNKNOWN) null
            else SyncFieldVersionEntity(field = name, hlc = hlc, sha = sha)
        }
        if (rows.isEmpty()) return
        dao.putAll(rows)
        // 采纳外来 hlc 后必须推进本机时钟，否则本机下一次 now() 可能小于刚采纳的值，
        // 破坏 happens-before（HLC 的 observe 语义）
        winners.values.forEach { (hlc, _) -> clock.observe(hlc) }
        Log.d(TAG, "applied ${rows.size} remote winner(s)")
    }

    /** 读出全部字段版本，pull 裁决前一把捞进内存（表只有 84 行） */
    suspend fun loadVersions(): Map<String, SyncFieldVersionEntity> =
        dao.getAll().associateBy { it.field }

    /**
     * 本地被整体恢复（导入备份 / 快照回滚）后清空版本表。
     *
     * 旧版本号已经不描述当前值了；留着会让恢复出来的旧值带着大 HLC 上云，
     * 把云端较新的配置刷掉。清空后按 bootstrap 走（只 pull 不 push）。
     */
    suspend fun resetAfterRestore() {
        dao.clear()
        Log.w(TAG, "field version table cleared after local restore")
    }

    companion object {
        private const val TAG = "SyncFieldStamper"
    }
}
