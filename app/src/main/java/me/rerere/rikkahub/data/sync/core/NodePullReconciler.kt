package me.rerere.rikkahub.data.sync.core

/**
 * node 级增量 pull 的**纯函数**重建器（阶段 A / P0 止血）。
 *
 * ## 为什么要单独抽出来
 *
 * 原 `SyncEngine.pullNodeIncremental` 把「云端清单 → 本地节点表」的重建逻辑和
 * D1 查询、Room 写入、R2 hydrate 混在一个 suspend 函数里，导致**这段最容易吃数据的
 * 逻辑完全无法单测**。抽成纯函数后由 `NodePullReconcilerTest` 覆盖。
 *
 * ## 原实现的数据丢失 bug（P0）
 *
 * ```kotlin
 * val need = alive.filter { localState[it.nodeId] != it.sha }   // 只挑 sha 变化的
 * val dataById = /* 只查 need 的 data */
 * val cloudNodes = alive.sortedBy { it.idx }.mapNotNull { cn ->
 *     val d = dataById[cn.nodeId] ?: return@mapNotNull null      // ← 未变化的节点全被 null 掉
 *     decode(d)
 * }
 * val merged = localConv.copy(messageNodes = cloudNodes + localExtra)   // ← 整表重写
 * ```
 *
 * `alive` 是**全量**节点，`dataById` 只有**变化的**节点，于是没变化的历史消息被
 * `mapNotNull` 全部丢弃。而 `localExtra` 定义为「本地有、云端 alive 里没有」，
 * 未变化节点在 alive 里是**有**的，所以也捞不回来。
 *
 * 净效果：**对端改了最后一条消息，本端把 200 条历史裁成 1 条。**
 *
 * 二次伤害：裁剩的结果被 `updateConversation` 写库并入 outbox，下一轮
 * `pushConversationNodes` 用残缺基准做 diff，`ConversationNodeDiff` 给「消失的节点」
 * 生成 tombstone → **云端历史也被标删**，两端同时归零。
 *
 * ## 本实现的三层防御
 *
 * 1. **补齐**：取不到 data 的节点用本地同 id 原节点填回（`localById`）
 * 2. **安全阀**：除非云端有显式 `deleted=1` 墓碑，节点数只增不减；不满足直接 [Outcome.Abort]，
 *    调用方**不写库、不推进基准**，下一轮重试或回落整包路径
 * 3. **基准修正**：被补齐的节点写回**本地原 sha**（而非云端 sha），
 *    保证下一轮仍会尝试拉取它真正的 data，不会误判成「已同步」
 */
object NodePullReconciler {

    /** 云端 conv_nodes 清单里的一行（只含元数据，data 按需取） */
    data class CloudNode(
        val nodeId: String,
        val idx: Int,
        val sha: String,
        val deleted: Boolean,
    )

    sealed interface Outcome<out T> {
        /**
         * 重建成功。
         *
         * @param nodes       重建后的节点序列（按云端 idx 排序 + 本地独有项追加在末尾）
         * @param nextState   下一轮的 node 基准（nodeId -> sha）
         * @param needRepush  存在本地独有节点或补齐节点，需要回推云端
         */
        data class Merged<out T>(
            val nodes: List<T>,
            val nextState: Map<String, String>,
            val needRepush: Boolean,
        ) : Outcome<T>

        /** 无需改动本地（云端没有任何本端未见过的变化） */
        data object NoChange : Outcome<Nothing>

        /**
         * 检测到会收缩节点表，中止本轮。
         *
         * 调用方必须：不写库、不推进 `sync_state` 基准、打 error 日志 + 审计。
         */
        data class Abort(val reason: String) : Outcome<Nothing>
    }

    /**
     * @param cloud        云端 conv_nodes 全量清单（含 deleted 行）
     * @param localNodes   本地当前节点序列
     * @param localState   本地 node 基准（nodeId -> 上次同步的 sha）
     * @param fetchedData  本轮实际取到的节点 data（nodeId -> 已解码对象）；
     *                     解码失败的节点**不要**放进来，让补齐逻辑接管
     * @param idOf         取节点 id
     */
    fun <T : Any> reconcile(
        cloud: List<CloudNode>,
        localNodes: List<T>,
        localState: Map<String, String>,
        fetchedData: Map<String, T>,
        idOf: (T) -> String,
    ): Outcome<T> {
        val alive = cloud.filter { !it.deleted }
        if (alive.isEmpty()) {
            // 云端全是墓碑：可能是对端清空，也可能是清单查询本身出了问题。
            // 保守起见交回 NoChange，让整包路径去裁决，绝不在这里清空本地。
            return Outcome.NoChange
        }

        val localById = localNodes.associateBy(idOf)
        val aliveIds = alive.mapTo(mutableSetOf()) { it.nodeId }

        // 云端显式墓碑：这些是「真的该消失」的节点，允许它们从本地移除
        val deletedIds = cloud.filter { it.deleted }.mapTo(mutableSetOf()) { it.nodeId }

        val rebuilt = mutableListOf<T>()
        val nextState = mutableMapOf<String, String>()
        var filledFromLocal = 0

        alive.sortedBy { it.idx }.forEach { cn ->
            val fetched = fetchedData[cn.nodeId]
            if (fetched != null) {
                rebuilt += fetched
                // 真正取到 data 才推进到云端 sha
                nextState[cn.nodeId] = cn.sha
                return@forEach
            }
            // ★ P0 修复核心：取不到 data 不代表节点不存在，用本地原节点补齐
            val local = localById[cn.nodeId]
            if (local != null) {
                rebuilt += local
                filledFromLocal++
                // 基准保持「本地原 sha」：与云端 sha 不同时下一轮会重新尝试拉取，
                // 若本地压根没有基准记录则留空，同样会在下一轮进入 need 列表
                localState[cn.nodeId]?.let { nextState[cn.nodeId] = it }
            }
            // 本地也没有、云端 data 又取不到 → 只能跳过；下面的安全阀会判断是否可接受
        }

        // 本地有而云端 alive/墓碑里都没有的节点：对端旧版本只写过整包 data 的场景，保留 + 回推
        val localExtra = localNodes.filter {
            val id = idOf(it)
            id !in aliveIds && id !in deletedIds
        }
        localExtra.forEach { node ->
            val id = idOf(node)
            localState[id]?.let { nextState[id] = it }
        }

        val merged = rebuilt + localExtra

        // ★ 安全阀：只有云端显式墓碑才允许缩减
        val explicitlyDeletedLocally = localNodes.count { idOf(it) in deletedIds }
        val expectedMin = localNodes.size - explicitlyDeletedLocally
        if (merged.size < expectedMin) {
            return Outcome.Abort(
                "would shrink ${localNodes.size} -> ${merged.size} " +
                    "(explicit tombstones=$explicitlyDeletedLocally, expectedMin=$expectedMin)"
            )
        }

        val unchanged = merged.size == localNodes.size &&
            merged.zip(localNodes).all { (a, b) -> idOf(a) == idOf(b) } &&
            fetchedData.isEmpty()
        if (unchanged) return Outcome.NoChange

        return Outcome.Merged(
            nodes = merged,
            nextState = nextState,
            needRepush = localExtra.isNotEmpty() || filledFromLocal > 0,
        )
    }
}
