package me.rerere.rikkahub.data.sync.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.ai.ui.UIMessagePart

/**
 * 会话并发合并（取代 P2 的会话互斥锁）。
 *
 * 背景：单人多设备场景下，前置锁的收益接近零，成本却是每次发送 2~3 次 D1 往返
 * 加上误报「对话被占用」。本模块把并发控制从「事前互斥」改为「事后合并」。
 *
 * 可行的前提是会话的写语义：`updateConversation` 整表重写 messageNodes，
 * 且 [MessageNode.id] 跨设备稳定（由创建端生成、随 JSON 同步）。因此两台设备
 * 各自 append 消息后，节点 id 序列必然共享一个最长公共前缀，据此可判定：
 *
 * | 关系 | 含义 | 结果 |
 * |---|---|---|
 * | 远端是本地前缀 | 本机多发了几条 | [Resolution.KeepLocal] 强推本地 |
 * | 本地是远端前缀 | 对端多发了几条 | [Resolution.TakeRemote] 快进采纳 |
 * | 完全相同 | 同一状态 | [Resolution.Identical] |
 * | 公共前缀后双方新节点 ID 无交集 | 并发追加 | [Resolution.AppendMerge] |
 * | 中途分叉（ID 重叠且内容不同） | 两端各写了不同内容 | [Resolution.Fork] |
 *
 * 只有真分叉才产生分支，因此「A 上发几条 → 换 B 继续发」这一主场景**零分支**；
 * 两端同时各发消息也能智能合并，不再产生 Fork 副本。
 */
object ConversationMerger {

    sealed interface Resolution {
        /** 内容等价，只需对齐同步基线 */
        data object Identical : Resolution

        /** 本地包含远端的全部历史：直接强推 */
        data object KeepLocal : Resolution

        /** 远端包含本地的全部历史：快进采纳远端，本地无损失 */
        data object TakeRemote : Resolution

        /**
         * 并发追加合并：公共前缀之后，双方新增节点 ID 无重叠。
         * 两端各自的追加内容可以安全拼接，不拆散 User→Assistant 因果链。
         *
         * @param commonPrefixLength 公共前缀节点数
         * @param remoteFirst true = 远端追加的节点排在前面（时间更早），false = 反之
         */
        data class AppendMerge(
            val commonPrefixLength: Int,
            val remoteFirst: Boolean,
        ) : Resolution

        /**
         * 真分叉：公共前缀之后两端内容不同且有 ID 重叠。
         * @param commonPrefixLength 公共前缀节点数，用于日志与提示
         * @param localKeepsId 本机是否保留原会话 id（另一端另存为副本）
         */
        data class Fork(
            val commonPrefixLength: Int,
            val localKeepsId: Boolean,
        ) : Resolution
    }

    /**
     * @param localTieBreak 本机裁决键（[SyncLocalPrefs.tieBreakKey]）
     * @param remoteTieBreak 远端写入者裁决键；为空表示远端行由旧版客户端写入
     */
    fun resolve(
        local: Conversation,
        remote: Conversation,
        localTieBreak: String,
        remoteTieBreak: String?,
    ): Resolution {
        // ◆ 先滤掉空壳节点再比对（无限分支增殖的直接诱因）。
        //
        // 生成被中断 / 请求失败时，会在本地留下一个 text 全空的 assistant 节点。
        // 它零信息量，却足以把两端拓扑撞出差异：一端有、一端没 → 判为分叉 →
        // Fork 另存 → 新会话再上云 → 对端拉到又分叉…… 形成自激环。
        // （2026-09-11 现场：同一 createAt 派生出 6 个副本，每个里只有一条空 assistant。）
        //
        // 空壳节点对「两端是否真的分叉了」这个判断没有任何贡献，因此不参与比对。
        val localNodes = local.messageNodes.filterNot { isEmptyPlaceholder(it) }
        val remoteNodes = remote.messageNodes.filterNot { isEmptyPlaceholder(it) }
        val prefix = commonPrefixLength(localNodes, remoteNodes)

        val localIsPrefixOfRemote = prefix == localNodes.size
        val remoteIsPrefixOfLocal = prefix == remoteNodes.size

        return when {
            localIsPrefixOfRemote && remoteIsPrefixOfLocal ->
                // 节点序列一致；元数据（标题/置顶/文件夹）仍可能不同，交由调用方按 LWW 处理
                if (metadataEquivalent(local, remote)) Resolution.Identical else Resolution.KeepLocal

            remoteIsPrefixOfLocal -> Resolution.KeepLocal

            localIsPrefixOfRemote -> Resolution.TakeRemote

            else -> {
                // 公共前缀后双方都有追加 → 尝试并集合并
                val localTail = localNodes.subList(prefix, localNodes.size)
                val remoteTail = remoteNodes.subList(prefix, remoteNodes.size)
                val localTailIds = localTail.mapTo(mutableSetOf()) { it.id.toString() }
                val remoteTailIds = remoteTail.mapTo(mutableSetOf()) { it.id.toString() }

                // 同 ID 但内容不同 = 真编辑冲突 → Fork
                val overlap = localTailIds.intersect(remoteTailIds)
                val localTailById = localTail.associateBy { it.id.toString() }
                val remoteTailById = remoteTail.associateBy { it.id.toString() }
                val hasContentConflict = overlap.any { id ->
                    val l = localTailById[id]!!
                    val r = remoteTailById[id]!!
                    !nodeEquivalent(l, r)
                }

                if (hasContentConflict) {
                    Resolution.Fork(
                        commonPrefixLength = prefix,
                        localKeepsId = remoteTieBreak.isNullOrBlank() ||
                            localTieBreak > remoteTieBreak,
                    )
                } else {
                    // 无内容冲突：求并集，按确定性排序键合并
                    // overlap 中的节点内容相同，只留一份即可
                    Resolution.AppendMerge(
                        commonPrefixLength = prefix,
                        remoteFirst = false, // 实际排序由 applyAppendMerge 按时间戳 + nodeId 决定
                    )
                }
            }
        }
    }

    /**
     * 执行 AppendMerge：按 node id 求并集 + 确定性全局排序。
     *
     * 排序键：earliestTimestamp (UTC) → nodeId 字典序兜底。
     * 任何一端、任何顺序、跑多少遍，算出来的序列都相同 → 幂等 + 收敛。
     *
     * @return 合并后的完整节点列表
     */
    fun applyAppendMerge(
        localNodes: List<MessageNode>,
        remoteNodes: List<MessageNode>,
        resolution: Resolution.AppendMerge,
    ): List<MessageNode> {
        val prefix = localNodes.subList(0, resolution.commonPrefixLength)
        val localTail = localNodes.subList(resolution.commonPrefixLength, localNodes.size)
        val remoteTail = remoteNodes.subList(resolution.commonPrefixLength, remoteNodes.size)

        // 按 nodeId 求并集（同 ID 取 local 版本，因为 resolve 已确认内容等价）
        val seen = mutableSetOf<String>()
        val union = mutableListOf<MessageNode>()
        for (node in localTail) {
            val id = node.id.toString()
            if (seen.add(id)) union += node
        }
        for (node in remoteTail) {
            val id = node.id.toString()
            if (seen.add(id)) union += node
        }

        // 确定性排序：UTC 时间戳 → nodeId 字典序
        union.sortWith(compareBy<MessageNode> { it.earliestTimestamp() }.thenBy { it.id.toString() })

        return prefix + union
    }

    /**
     * 逐节点比对求公共前缀长度。
     *
     * 同一 id 的节点仍可能内容不同（对端编辑了历史消息、或生成中途的快照与
     * 最终结果不同），因此不能只比 id —— 必须连消息内容一起比，否则会把
     * 「对端改了第 3 条」误判成前缀相同而静默丢弃对方的编辑。
     */
    private fun commonPrefixLength(a: List<MessageNode>, b: List<MessageNode>): Int {
        val limit = minOf(a.size, b.size)
        var i = 0
        while (i < limit && nodeEquivalent(a[i], b[i])) i++
        return i
    }

    /**
     * 空壳占位节点：所有消息都没有任何实质内容。
     *
     * 典型来源是流式生成刚建好占位就被中断 / 报错，留下一个 text="" 的 assistant。
     * 判定故意保守：只要带了工具调用、图片、文件等任何非文本 part，就**不算**空壳，
     * 宁可漏判也不能误删用户真实数据。
     */
    private fun isEmptyPlaceholder(node: MessageNode): Boolean {
        if (node.messages.isEmpty()) return true
        return node.messages.all { msg ->
            msg.parts.all { part ->
                part is UIMessagePart.Text && part.text.isBlank()
            }
        }
    }

    private fun nodeEquivalent(a: MessageNode, b: MessageNode): Boolean {
        if (a.id != b.id) return false
        if (a.messages.size != b.messages.size) return false
        // selectIndex 是分支选择，属于可分歧的偏好，不参与内容判定
        return a.messages.indices.all { idx ->
            val ma = a.messages[idx]
            val mb = b.messages[idx]
            ma.id == mb.id && ma.role == mb.role && ma.parts == mb.parts
        }
    }

    private fun metadataEquivalent(a: Conversation, b: Conversation): Boolean =
        a.title == b.title &&
            a.isPinned == b.isPinned &&
            a.folderId == b.folderId &&
            a.assistantId == b.assistantId &&
            a.customSystemPrompt == b.customSystemPrompt

    /** 分叉副本标题：`原标题-<对端label>`（用户拍板的命名） */
    fun forkTitle(title: String, label: String): String {
        val base = title.ifBlank { "对话" }
        val suffix = label.trim().ifBlank { "device" }
        return if (base.endsWith("-$suffix")) base else "$base-$suffix"
    }

    /**
     * 取节点中最早的消息创建时间，用于 AppendMerge 排序。
     * 若无时间信息则返回 MAX_VALUE（排到最后）。
     *
     * ⚠️ D2 修复（2026-09-11）：使用 UTC 而非 currentSystemDefault()。
     * 原来用系统默认时区会导致跨时区设备算出不同的排序键 → 两端
     * remoteFirst 不一致 → 合并结果不同 → 二次冲突。
     * createdAt 是 LocalDateTime 没有时区信息，但只要两端用同一个常量时区
     * 转换，排序顺序就确定性一致，UTC 是唯一安全选择。
     */
    private fun MessageNode.earliestTimestamp(): Long {
        return messages.mapNotNull { msg ->
            runCatching {
                msg.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
            }.getOrNull()
        }.minOrNull() ?: Long.MAX_VALUE
    }
}
