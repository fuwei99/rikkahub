package me.rerere.ai.util

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.KeyStrategy
import java.io.File
import java.io.IOException

/** 携带 HTTP 失败码的内部异常，供 KeyRoulette 重试/轮换循环区分「换 key 可解」与「直接抛出」。 */
class KeyFailureException(
    val code: Int,
    val body: String = "",
) : Exception("HTTP $code${if (body.isNotBlank()) ": ${body.take(300)}" else ""}")

/**
 * 多 Token 轮换器。
 *
 * 三种策略：
 * - [KeyStrategy.ROUND_ROBIN]：按最近使用时间轮询（LRU），尽量均衡分摊，持久化
 * - [KeyStrategy.RANDOM]：每次随机挑一个
 * - [KeyStrategy.FAILOVER]：固定使用第一个可用 Token，仅当上报失败（401/403/422/429）才切换到下一个；
 *   其中命中 [KeyRoulette.DEFAULT_CLOSE_CODES]（默认 401/403/422）的 Token 会被**关闭**（永久剔除），
 *   调用方应通过 [closedKeys] 把它同步到设置的禁用列表里（保留但不使用），而不是删除。
 *
 * 失败重试由 [KeyRoulette.executeWithRetry] / [KeyRoulette.executeWithRetryFlow] 提供：
 * 同一 key 原地重试 [KeyRoulette.DEFAULT_RETRY_COUNT] 次、间隔 1s，重试耗尽后才上报失败并轮换。
 */
interface KeyRoulette {
    /**
     * 取下一个可用 Token；返回 null 表示没有可用 Token（全部禁用/冷却中/已关闭/为空）。
     *
     * @param disabledKeys 设置里被手动关闭的 Token（开关关闭），这些 Token 直接跳过
     */
    fun next(
        keys: String,
        providerId: String = "",
        strategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
        disabledKeys: List<String> = emptyList(),
    ): String?

    /**
     * 上报一次请求失败。
     *
     * - `code ∈ closeCodes`：永久关闭该 Token（settings 层应把它同步为禁用状态）；
     * - `code == 429`：限流，冷却 5 分钟；
     * - 其余 key 失败码：认证类，冷却 1 小时。
     */
    fun reportFailure(providerId: String, key: String, code: Int, closeCodes: Set<Int> = DEFAULT_CLOSE_CODES)

    /** 返回因报错码命中而被永久关闭的 Token 列表（调用方应把它们加入设置里的禁用列表）。 */
    fun closedKeys(providerId: String): List<String>

    /** 用户在设置里重新启用一个 Token 时调用，清除其关闭/冷却标记。 */
    fun revive(providerId: String, key: String)

    companion object {
        /** 触发切换 Token 的失败码：认证失败 / 无权限 / 额度耗尽 / 限流。 */
        val KEY_FAILURE_CODES = setOf(401, 403, 422, 429)

        /** 默认的「报错即关闭」码：命中后关闭（禁用）该 Token，而不是删除。 */
        val DEFAULT_CLOSE_CODES = setOf(401, 403, 422)

        /** 默认失败重试次数（含首次尝试，即最多尝试 3 次）。 */
        const val DEFAULT_RETRY_COUNT = 3

        /** 默认失败重试间隔（毫秒）。 */
        const val DEFAULT_RETRY_INTERVAL_MS = 1000L

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
    override fun next(
        keys: String,
        providerId: String,
        strategy: KeyStrategy,
        disabledKeys: List<String>,
    ): String? {
        val live = splitKey(keys).filter { it !in disabledKeys }
        return live.randomOrNull()
    }

    override fun reportFailure(providerId: String, key: String, code: Int, closeCodes: Set<Int>) = Unit

    override fun closedKeys(providerId: String): List<String> = emptyList()

    override fun revive(providerId: String, key: String) = Unit
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
    var current: String? = null, // FAILOVER：当前粘住的 Token
    val dead: MutableMap<String, DeadKeyInfo> = mutableMapOf(), // 不可用 Token
)

// 文件结构: Map<providerId, ProviderKeyState>
private typealias LruCache = MutableMap<String, ProviderKeyState>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(
        keys: String,
        providerId: String,
        strategy: KeyStrategy,
        disabledKeys: List<String>,
    ): String? {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return null
        val disabledSet = disabledKeys.toSet()

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val state = allCache.getOrPut(providerId) { ProviderKeyState() }
            cleanupState(state, now, keyList, disabledSet)

            // 可用 Token：排除手动禁用 / 仍在冷却期 / 已永久关闭的
            val isLive: (String) -> Boolean = { key ->
                if (key in disabledSet) {
                    false
                } else {
                    val dead = state.dead[key]
                    dead == null || (!dead.permanent && dead.until <= now)
                }
            }
            val liveKeys = keyList.filter(isLive)
            if (liveKeys.isEmpty()) {
                // 全部不可用：返回 null，由调用方给出清晰错误
                return null
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

    override fun reportFailure(providerId: String, key: String, code: Int, closeCodes: Set<Int>) {
        if (key.isBlank()) return

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val state = allCache.getOrPut(providerId) { ProviderKeyState() }

            state.dead[key] = when {
                code in closeCodes -> DeadKeyInfo(until = PERMANENT_UNTIL, permanent = true) // 报错码命中：关闭（settings 层同步为禁用）
                code == 429 -> DeadKeyInfo(until = now + COOLDOWN_429_MS) // 限流：冷却一会儿
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

    override fun closedKeys(providerId: String): List<String> = synchronized(LruFileLock) {
        loadCache()[providerId]?.dead
            ?.filterValues { it.permanent }
            ?.keys
            ?.toList()
            ?: emptyList()
    }

    override fun revive(providerId: String, key: String) {
        if (key.isBlank()) return
        synchronized(LruFileLock) {
            val allCache = loadCache().toMutableMap()
            val state = allCache[providerId] ?: return
            state.dead.remove(key)
            saveCache(allCache)
        }
    }

    /** 清掉已过冷却期的 dead 条目、以及不在当前 key 列表里 / 已过期的 lru 记录。 */
    private fun cleanupState(state: ProviderKeyState, now: Long, keyList: List<String>, disabledSet: Set<String>) {
        state.dead.entries.removeIf { (_, info) -> !info.permanent && info.until <= now }
        state.lru.entries.removeIf { (k, lastUsed) -> k !in keyList || now - lastUsed >= EXPIRE_DURATION_MS }
        if (state.current != null && state.current !in keyList) {
            state.current = null
        }
        // 已从 key 列表里移除的关闭记录一并清掉
        state.dead.entries.removeIf { (k, _) -> k !in keyList }
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

/**
 * 带失败重试的 Token 轮换执行器（生图 / 非流式 LLM 共用）。
 *
 * - 每个轮换轮次先取一个可用 key（跳过手动禁用 / 冷却中 / 已关闭的）；
 * - 请求抛出 [KeyFailureException] 或 [IOException] 时按 `retryCount` / `retryIntervalMs` 原地重试同一个 key
 *   （默认 3 次 / 间隔 1s；「失败后才轮询」，普通轮询仍是一次换一个）；
 * - 重试耗尽后：
 *   - `code ∈ closeCodes` → 上报为永久关闭（settings 层应同步为禁用）；
 *   - `code ∈ KEY_FAILURE_CODES ∪ closeCodes` → 上报冷却/剔除并换下一个 key；
 *   - 其它错误码 → 原样抛出；
 * - 所有 key 都失败后抛出汇总错误。
 */
suspend fun <T> KeyRoulette.executeWithRetry(
    keys: String,
    providerId: String,
    strategy: KeyStrategy,
    disabledKeys: List<String> = emptyList(),
    retryCount: Int = KeyRoulette.DEFAULT_RETRY_COUNT,
    retryIntervalMs: Long = KeyRoulette.DEFAULT_RETRY_INTERVAL_MS,
    closeCodes: Set<Int> = KeyRoulette.DEFAULT_CLOSE_CODES,
    request: suspend (key: String) -> T,
): T {
    val disabledSet = disabledKeys.toSet()
    val allKeyList = splitKey(keys)
    val keyList = allKeyList.filter { it !in disabledSet }
    if (keyList.isEmpty()) {
        error(
            if (allKeyList.isEmpty()) {
                "No API tokens configured for provider: $providerId"
            } else {
                "All API tokens are disabled for provider: $providerId"
            }
        )
    }
    val rotationCodes = KeyRoulette.KEY_FAILURE_CODES + closeCodes
    var lastFailure: KeyFailureException? = null

    for (attempt in keyList.indices) {
        val key = next(keys, providerId, strategy, disabledKeys) ?: break
        var retried = 0
        while (true) {
            try {
                return request(key)
            } catch (e: KeyFailureException) {
                lastFailure = e
                retried++
                if (retried < retryCount) {
                    delay(retryIntervalMs)
                    continue
                }
                // 重试耗尽
                if (e.code in rotationCodes) {
                    reportFailure(providerId, key, e.code, closeCodes)
                } else {
                    throw e
                }
                break
            } catch (e: IOException) {
                // 网络层错误：只重试，不轮换
                retried++
                if (retried < retryCount) {
                    delay(retryIntervalMs)
                    continue
                }
                throw e
            }
        }
    }
    val last = lastFailure
    error(
        if (last != null) {
            "All tokens failed for provider $providerId (HTTP ${last.code}): ${last.body.take(300)}"
        } else {
            "No available API tokens for provider $providerId (all tokens are disabled or in cooldown)"
        }
    )
}

/**
 * 流式版本的失败重试 + Token 轮换。
 *
 * 与 [executeWithRetry] 相同，但额外保证：**一旦已发射过任何 chunk 就不再重试**
 * （无法撤回已输出内容），只有连接阶段（首个 chunk 之前）失败才原地重试 / 换 key。
 */
fun <T> KeyRoulette.executeWithRetryFlow(
    keys: String,
    providerId: String,
    strategy: KeyStrategy,
    disabledKeys: List<String> = emptyList(),
    retryCount: Int = KeyRoulette.DEFAULT_RETRY_COUNT,
    retryIntervalMs: Long = KeyRoulette.DEFAULT_RETRY_INTERVAL_MS,
    closeCodes: Set<Int> = KeyRoulette.DEFAULT_CLOSE_CODES,
    request: suspend (key: String) -> Flow<T>,
): Flow<T> = flow {
    val disabledSet = disabledKeys.toSet()
    val allKeyList = splitKey(keys)
    val keyList = allKeyList.filter { it !in disabledSet }
    if (keyList.isEmpty()) {
        error(
            if (allKeyList.isEmpty()) {
                "No API tokens configured for provider: $providerId"
            } else {
                "All API tokens are disabled for provider: $providerId"
            }
        )
    }
    val rotationCodes = KeyRoulette.KEY_FAILURE_CODES + closeCodes
    var emitted = false
    var lastFailure: KeyFailureException? = null

    for (attempt in keyList.indices) {
        val key = next(keys, providerId, strategy, disabledKeys) ?: break
        var retried = 0
        while (true) {
            try {
                request(key).collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
                return@flow // 流正常结束
            } catch (e: KeyFailureException) {
                if (emitted) throw e // 已输出内容，无法重试
                lastFailure = e
                retried++
                if (retried < retryCount) {
                    delay(retryIntervalMs)
                    continue
                }
                if (e.code in rotationCodes) {
                    reportFailure(providerId, key, e.code, closeCodes)
                } else {
                    throw e
                }
                break
            } catch (e: IOException) {
                if (emitted) throw e
                retried++
                if (retried < retryCount) {
                    delay(retryIntervalMs)
                    continue
                }
                throw e
            }
        }
    }
    val last = lastFailure
    error(
        if (last != null) {
            "All tokens failed for provider $providerId (HTTP ${last.code}): ${last.body.take(300)}"
        } else {
            "No available API tokens for provider $providerId (all tokens are disabled or in cooldown)"
        }
    )
}
