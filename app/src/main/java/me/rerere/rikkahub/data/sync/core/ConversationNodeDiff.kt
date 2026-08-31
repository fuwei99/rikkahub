package me.rerere.rikkahub.data.sync.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.util.stripLoneSurrogates
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.d1.D1Statement
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
 * - 新增 / sha 变化 → UPSERT（ON CONFLICT DO UPDATE WHERE sha != excluded.sha）
 * - 本地已消失的 node → tombstone（deleted=1）
 * - 无变化 → 不生成语句（本地 state 照常推进，跳过下一次全量比对）
 */
object ConversationNodeDiff {

    private val defaultJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    data class Result(
        /** 待一次性 batch 的 SQL 语句；为空表示无节点变化 */
        val statements: List<D1Statement>,
        /** 本次推送后应落盘的 nodeId -> sha 状态（供下一轮 diff 基准） */
        val newState: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = statements.isEmpty()
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
        val statements = mutableListOf<D1Statement>()
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
                statements += upsertStatement(convId, nodeId, idx, node.selectIndex, now, sha, data, myDevice)
            }
        }

        // 本地已删除的节点 → 云端 tombstone（幂等，且保留行便于审计）
        oldState.keys.forEach { nodeId ->
            if (nodeId !in seen) {
                statements += D1Statement(
                    """
                    UPDATE conv_nodes SET deleted = 1, updated_at = ?, sha = 'tombstone'
                    WHERE conv_id = ? AND node_id = ? AND deleted = 0
                    """.trimIndent(),
                    listOf(now, convId, nodeId)
                )
            }
        }

        return Result(statements, newState)
    }

    private fun upsertStatement(
        convId: String,
        nodeId: String,
        idx: Int,
        selectIndex: Int,
        now: Long,
        sha: String,
        data: String,
        myDevice: String,
    ): D1Statement = D1Statement(
        """
        INSERT INTO conv_nodes(conv_id, node_id, idx, select_index, updated_at, deleted, sha, data, last_device)
        VALUES(?,?,?,?,?,0,?,?,?)
        ON CONFLICT(conv_id, node_id) DO UPDATE SET
          idx = excluded.idx,
          select_index = excluded.select_index,
          updated_at = excluded.updated_at,
          deleted = 0,
          sha = excluded.sha,
          data = excluded.data,
          last_device = excluded.last_device
        WHERE conv_nodes.sha != excluded.sha
        """.trimIndent(),
        listOf(convId, nodeId, idx, selectIndex, now, sha, data, myDevice)
    )

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
