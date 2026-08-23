package me.rerere.rikkahub.data.sync.core

/**
 * 字段级 CRDT 合并原语（大统一重构 v2 §2.4，阶段 A）。
 *
 * ## 这个文件是整个方案的正确性来源
 *
 * 现状 `SyncSettingsFilter.mergeRemote()` 的最后一句是：
 *
 * ```kotlin
 * return remote.copy(displaySetting = local.displaySetting, ...)
 * ```
 *
 * **骨架取自云端**，只有显式列进那串 `copy` 参数的字段才保本地。于是每加一个
 * `Settings` 字段而忘了登记，它就会在两台设备之间互相抹平 —— 而且不报错。
 *
 * 本文件把骨架反过来：**逐字段挑胜者**。谁赢由 [SyncClock.decide] 的全序裁决决定，
 * 与「谁是骨架」无关。这一个改动就是本次重构的全部正确性，其余（分片、envelope、
 * 审计）都是配套工程。
 *
 * ## 与既有 [mergeListByVersion] 的关系
 *
 * 集合类字段**继续走** `mergeListByVersion` / `stampListChanges`。
 * 那套逻辑（逐项 LWW + 墓碑竞争 + 存活项墓碑裁剪 + normalize 防死循环打戳）
 * 是被线上打磨过的，本次重构**明确不重写**，只是把它从「5 个列表的特例」
 * 提升为「所有集合字段的通则」。
 */
object SyncCrdt {

    /**
     * 一次字段级裁决的结果。
     *
     * @param takeRemote 是否采纳云端值
     * @param winnerHlc  胜出方的 hlc，写回本地字段版本表
     * @param reason     裁决依据，写审计日志用；出问题时这一行能省两小时
     */
    data class Decision(
        val takeRemote: Boolean,
        val winnerHlc: Long,
        val reason: String,
    )

    /**
     * 标量字段的 LWW 裁决。
     *
     * @param localSha  本地值的内容 sha（规范化 JSON）
     * @param remoteSha 云端值的内容 sha
     */
    fun decideScalar(
        field: String,
        localHlc: Long,
        remoteHlc: Long,
        localSha: String,
        remoteSha: String,
    ): Decision {
        if (localSha == remoteSha) {
            return Decision(false, maxOf(localHlc, remoteHlc), "identical")
        }
        return when (SyncClock.decide(localHlc, remoteHlc, localSha, remoteSha)) {
            SyncClock.Winner.REMOTE -> Decision(
                takeRemote = true,
                winnerHlc = remoteHlc,
                reason = if (remoteHlc == localHlc) "tie-sha remote>local" else "hlc remote>local",
            )

            SyncClock.Winner.LOCAL -> Decision(
                takeRemote = false,
                winnerHlc = localHlc,
                reason = if (remoteHlc == localHlc) "tie-sha local>=remote" else "hlc local>remote",
            )

            // 双方都没打过戳：保本地，且**不得**把本地值当新事实上推。
            // 见 §2.5：若在升级时给全字段打 now()，晚升级那台的默认值会带着
            // 更大的 hlc 上云，把先升级那台的真实配置全刷成默认值。
            SyncClock.Winner.LOCAL_UNKNOWN -> Decision(
                takeRemote = false,
                winnerHlc = SyncClock.UNKNOWN,
                reason = "both-unknown keep-local no-assert",
            )
        }
    }

    /**
     * 逐字段合并的执行器。
     *
     * 之所以做成「收集 [FieldPlan] 再统一 apply」而不是直接 copy：
     * 派生字段（[SyncFieldKind.DERIVED]）必须等它依赖的字段合并完才能重算，
     * 一趟 `copy` 表达不了这个先后关系。
     */
    data class FieldPlan(
        val field: String,
        val decision: Decision,
        val kind: SyncFieldKind,
    )

    /**
     * 生成合并计划。真正的赋值由调用方按 [SyncFieldRegistry] 的 setter 执行，
     * 本函数只负责「谁赢」这个纯决策，便于单测。
     *
     * @param shaOf 取某字段在某侧的内容 sha；调用方用规范化 JSON 计算
     */
    fun planShard(
        shard: SyncShard,
        localHlcOf: (String) -> Long,
        remoteHlcOf: (String) -> Long,
        localShaOf: (String) -> String,
        remoteShaOf: (String) -> String,
    ): List<FieldPlan> = SyncFieldRegistry.ofShard(shard).mapNotNull { entry ->
        when (entry.kind) {
            // 设备本地 / 噪音：永不被云端改写，也不参与上行
            SyncFieldKind.LOCAL, SyncFieldKind.NOISE -> null

            // 派生：主循环跳过，收尾统一重算
            SyncFieldKind.DERIVED -> FieldPlan(
                entry.name,
                Decision(false, SyncClock.UNKNOWN, "derived-recompute"),
                entry.kind,
            )

            // 自定义：交给专属合并器（supervision 走事件日志 fold）
            SyncFieldKind.CUSTOM -> FieldPlan(
                entry.name,
                Decision(false, SyncClock.UNKNOWN, "custom-merger"),
                entry.kind,
            )

            SyncFieldKind.LWW, SyncFieldKind.OR_SET -> FieldPlan(
                field = entry.name,
                decision = decideScalar(
                    field = entry.name,
                    localHlc = localHlcOf(entry.name),
                    remoteHlc = remoteHlcOf(entry.name),
                    localSha = localShaOf(entry.name),
                    remoteSha = remoteShaOf(entry.name),
                ),
                kind = entry.kind,
            )
        }
    }
}
