package me.rerere.rikkahub.data.sync.core

import kotlinx.serialization.Serializable

/**
 * 列表类设置项的外挂同步元数据。
 *
 * 为什么不像 ProviderSetting 那样往条目里加 `updatedAt`：
 * imageProviders / ttsProviders / asrProviders / searchServices 四者合计 40 个 sealed 子类，
 * 其中 SearchServiceOptions 的 18 个子类连 copyProvider 都没有。逐个加字段既是体力活，
 * 也会把同步机制的关注点扩散到每个业务 data class 里。
 *
 * 改成按 id 外挂版本号：条目本身零改动，版本与墓碑集中管理，新增子类自动受保护。
 *
 * @param versions   条目 id -> 最后修改时间（epoch millis）
 * @param tombstones 条目 id -> 删除时间（epoch millis），用于抵抗默认项补种与跨设备复活
 */
@Serializable
data class SyncVersionMap(
    val versions: Map<String, Long> = emptyMap(),
    val tombstones: Map<String, Long> = emptyMap(),
) {
    fun versionOf(id: String): Long = versions[id] ?: 0L
    fun tombstoneOf(id: String): Long? = tombstones[id]
    fun isTombstoned(id: String): Boolean = tombstones.containsKey(id)
}

/**
 * 列表逐项 LWW 合并 + 墓碑裁剪的通用实现。
 *
 * 规则与 providers 保持一致：
 * - 只存于一边 → 采纳，除非被不旧于它的墓碑盖掉
 * - 两边都有 → 版本号大的赢；相等保本地，避免无意义的 UI 闪动
 * - 墓碑时间 >= 胜出版本 → 真删；墓碑更旧 → 认为是「删除后又被编辑」，编辑胜出
 *
 * 顺序以 remote 为骨架再追加本地独有项，保证两端最终收敛到同一序列。
 */
fun <T : Any> mergeListByVersion(
    local: List<T>,
    remote: List<T>,
    localMeta: SyncVersionMap,
    remoteMeta: SyncVersionMap,
    idOf: (T) -> String,
): Pair<List<T>, SyncVersionMap> {
    val mergedTombstones = mergeLongMaps(localMeta.tombstones, remoteMeta.tombstones)
    val localById = local.associateBy(idOf)
    val remoteById = remote.associateBy(idOf)

    val ids = LinkedHashSet<String>().apply {
        addAll(remote.map(idOf))
        addAll(local.map(idOf))
    }

    val mergedItems = mutableListOf<T>()
    val mergedVersions = mutableMapOf<String, Long>()

    ids.forEach { id ->
        val l = localById[id]
        val r = remoteById[id]
        val lv = localMeta.versionOf(id)
        val rv = remoteMeta.versionOf(id)

        val winner: T
        val winnerVersion: Long
        when {
            l == null -> {
                winner = r ?: return@forEach
                winnerVersion = rv
            }

            r == null -> {
                winner = l
                winnerVersion = lv
            }

            rv > lv -> {
                winner = r
                winnerVersion = rv
            }

            else -> {
                winner = l
                winnerVersion = lv
            }
        }

        val tombstonedAt = mergedTombstones[id]
        if (tombstonedAt != null && tombstonedAt >= winnerVersion) return@forEach

        mergedItems += winner
        if (winnerVersion > 0L) mergedVersions[id] = winnerVersion
    }

    // 存活条目不该同时挂着墓碑（删除输给了编辑），否则下一轮读流又把它滤掉
    val survivingIds = mergedItems.mapTo(mutableSetOf(), idOf)
    val prunedTombstones = mergedTombstones.filterKeys { it !in survivingIds }

    return mergedItems to SyncVersionMap(
        versions = mergedVersions,
        tombstones = prunedTombstones,
    )
}

/**
 * 本地写入时的打戳 + 墓碑登记。
 *
 * @param old  写入前的列表
 * @param next 写入后的列表
 * @return 更新后的元数据；内容变化的条目版本推到 now，消失的条目登记墓碑，
 *         重新出现的条目消墓碑（用户手动重建默认项的场景）
 */
fun <T : Any> stampListChanges(
    old: List<T>,
    next: List<T>,
    meta: SyncVersionMap,
    now: Long,
    /**
     * 比较前的规范化钩子。读流里有 distinctBy / 预置元数据回填 / 火山 baseUrl 自动升级等
     * 加工逻辑，会让「内容其实没变」的条目每轮都产出不等价对象。若直接比对象，
     * 每次读流都会被判成变更并打戳，把同步刷成死循环。
     */
    normalize: (T) -> Any = { it },
    idOf: (T) -> String,
): SyncVersionMap {
    val oldById = old.associateBy(idOf)
    val versions = meta.versions.toMutableMap()

    next.forEach { item ->
        val id = idOf(item)
        val oldItem = oldById[id]
        when {
            // 新增项必须打戳，否则版本 0 永远输给云端
            oldItem == null -> versions[id] = now
            normalize(oldItem) != normalize(item) -> versions[id] = now
        }
    }

    val nextIds = next.mapTo(mutableSetOf(), idOf)
    val tombstones = meta.tombstones.toMutableMap()
    old.map(idOf).forEach { id ->
        if (id !in nextIds) {
            tombstones[id] = now
            versions.remove(id)
        }
    }
    nextIds.forEach { tombstones.remove(it) }

    // 已不在列表且没墓碑的陈旧版本号清掉，避免 map 无限增长
    val staleVersionIds = versions.keys.filter { it !in nextIds }
    staleVersionIds.forEach { versions.remove(it) }

    return SyncVersionMap(versions = versions, tombstones = tombstones)
}

/** 同 key 取较大值的并集，用于墓碑/版本合并 */
internal fun mergeLongMaps(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
    buildMap {
        putAll(b)
        a.forEach { (k, v) ->
            val existing = this[k]
            if (existing == null || v > existing) this[k] = v
        }
    }
