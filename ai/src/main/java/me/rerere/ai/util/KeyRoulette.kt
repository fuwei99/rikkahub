package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.KeyStrategy
import java.io.File

/**
 * 多 Token 轮换器。
 *
 * 三种策略：
 * - [KeyStrategy.ROUND_ROBIN]：按最近使用时间轮询（LRU），尽量均衡分摊，持久化
 * - [KeyStrategy.RANDOM]：每次随机挑一个
 * - [KeyStrategy.FAILOVER]：固定使用第一个可用 Token，仅当上报失败（401/403/422/429）才切换到下一个；
 *   其中 422 视为额度耗尽，永久剔除（可通过 [exhaustedKeys] 查询，调用方应把它从设置里删掉）
 */
interface KeyRoulette {
    fun next(
        keys: String,
        providerId: String = "",
        strategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
    ): String

    /** 上报一次请求失败；code 属于 key 失败码时会把该 Token 标记为不可用（冷却或永久剔除）。 */
    fun reportFailure(providerId: String, key: String, code: Int)

    /** 返回因额度耗尽（422）被永久剔除的 Token 列表。 */
    fun exhaustedKeys(providerId: String): List<String>

    companion object {
        /** 需要切换 Token 的失败码：认证失败 / 无权限 / 额度耗尽 / 限流。 */
        val KEY_FAILURE_CODES = setOf(401, 403, 422, 429)

        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * 持久化轮询，存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String, strategy: KeyStrategy): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }

    override fun reportFailure(providerId: String, key: String, code: Int) = Unit

    override fun exhaustedKeys(providerId: String): List<String> = emptyList()
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天
private const val COOLDOWN_429_MS = 5 * 60 * 1000L // 429 限流：5 分钟冷却
private const val COOLDOWN_AUTH_MS = 60 * 60 * 1000L // 401/403：1 小时冷却
private const val PERMANENT_UNTIL = Long.MAX_VALUE

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

@Serializable
private data class DeadKeyInfo(
    val until: Long,
    val permanent: Boolean = false,
)

@Serializable
private data class ProviderKeyState(
    val lru: MutableMap<String, Long> = mutableMapOf(), // ROUND_ROBIN：最近使用时间
    val current: String? = null, // FAILOVER：当前粘住的 Token
    val dead: MutableMap<String, DeadKeyInfo> = mutableMapOf(), // 不可用 Token
)

// 文件结构: Map<providerId, ProviderKeyState>
private typealias LruCache = MutableMap<String, ProviderKeyState>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String, strategy: KeyStrategy): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val state = allCache.getOrPut(providerId) { ProviderKeyState() }
            cleanupState(state, now, keyList)

            // 可用 Token：排除仍在冷却期 / 已永久剔除的
            val isLive: (String) -> Boolean = { key ->
                val dead = state.dead[key]
                dead == null || (!dead.permanent && dead.until <= now)
            }
            val liveKeys = keyList.filter(isLive)
            if (liveKeys.isEmpty()) {
                // 全部不可用：退回原始列表，让真实错误信息从 API 冒出来
                return keyList.first()
            }

            val selected = when (strategy) {
                KeyStrategy.ROUND_ROBIN -> {
                    val liveCache = state.lru.filterKeys { it in liveKeys }
                    // 优先选从未使用过的 key，否则选最久未使用的
                    val pick = keyList.firstOrNull { isLive(it) && it !in liveCache }
                        ?: liveCache.minByOrNull { it.value }!!.key
                    state.lru[pick] = now
                    pick
                }

                KeyStrategy.RANDOM -> liveKeys.random()

                KeyStrategy.FAILOVER -> {
                    val current = state.current?.takeIf { isLive(it) && it in keyList }
                        ?: liveKeys.first()
                    state.current = current
                    current
                }
            }

            // 清理其它 provider 的空状态记录
            allCache.entries.removeIf { (id, s) ->
                id != providerId && s.lru.isEmpty() && s.dead.isEmpty() && s.current == null
            }

            allCache[providerId] = state
            saveCache(allCache)
            return selected
        }
    }

    override fun reportFailure(providerId: String, key: String, code: Int) {
        if (key.isBlank()) return
        if (code !in KeyRoulette.KEY_FAILURE_CODES) return

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val state = allCache.getOrPut(providerId) { ProviderKeyState() }

            state.dead[key] = when (code) {
                422 -> DeadKeyInfo(until = PERMANENT_UNTIL, permanent = true) // 额度耗尽：永久剔除
                429 -> DeadKeyInfo(until = now + COOLDOWN_429_MS) // 限流：冷却一会儿
                else -> DeadKeyInfo(until = now + COOLDOWN_AUTH_MS) // 401/403：认证类，冷却较久
            }
            // FAILOVER：当前粘住的 Token 失效后，下一次强制换到下一个可用 Token
            if (state.current == key) {
                state.current = null
            }

            allCache[providerId] = state
            saveCache(allCache)
        }
    }

    override fun exhaustedKeys(providerId: String): List<String> = synchronized(LruFileLock) {
        loadCache()[providerId]?.dead
            ?.filterValues { it.permanent }
            ?.keys
            ?.toList()
            ?: emptyList()
    }

    /** 清掉已过冷却期的 dead 条目、以及不在当前 key 列表里的 lru 记录。 */
    private fun cleanupState(state: ProviderKeyState, now: Long, keyList: List<String>) {
        state.dead.entries.removeIf { (_, info) -> !info.permanent && info.until <= now }
        state.lru.entries.removeIf { (k, _) -> k !in keyList }
        if (state.current != null && state.current !in keyList) {
            state.current = null
        }
    }

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) mutableMapOf()
            else Json.decodeFromString<LruCache>(file.readText()).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}
