package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.apiKeyTokens
import me.rerere.ai.provider.disabledTokens
import me.rerere.ai.provider.withDisabledTokens
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.java.KoinJavaComponent.getKoin

private const val TAG = "KeyCloseSync"

/**
 * 计算「本次要同步为禁用的 Token」，并保证渠道至少留一个活口。
 *
 * 自动关闭的本意是「多 Token 时把死号剔掉」，不是「单 Token 时自杀」。
 * 一旦把最后一个 Token 也禁掉，用户后续每次发消息只会看到
 * "All API tokens are disabled"，真实的 401/403 原因反而被吞掉，
 * 还得自己摸进设置里挨个把开关翻回来。
 *
 * KeyRoulette.reportFailure 已在源头拦了一道，这里是第二道闸：
 * 兼顾**存量设备**——它们的 cache 里可能已存着旧逻辑写下的 permanent 标记。
 *
 * @return 需要写回的禁用列表；null 表示无需改动
 */
private fun resolveDisabledTokens(
    allTokens: List<String>,
    currentDisabled: List<String>,
    closed: List<String>,
    keyRoulette: KeyRoulette,
    providerId: String,
    providerName: String,
): List<String>? {
    val roster = allTokens.filter { it.isNotBlank() }.distinct()
    if (roster.isEmpty()) return null

    val merged = (currentDisabled + closed).distinct()
    val survivors = roster.filter { it !in merged }

    if (survivors.isNotEmpty()) {
        return merged.takeIf { it.size != currentDisabled.size || it.toSet() != currentDisabled.toSet() }
    }

    // 全灭了：保留名册里的最后一个 Token 当活口，并把它在 roulette 里复活，
    // 否则 next() 那边仍会认为它 permanent、继续返回 null。
    val keepAlive = roster.last()
    keyRoulette.revive(providerId, keepAlive)
    val rescued = merged.filter { it != keepAlive }
    Log.w(
        TAG,
        "refused to disable the last token on $providerName; keeping ${keepAlive.take(6)}… alive"
    )
    return rescued.takeIf { it.toSet() != currentDisabled.toSet() }
}

/**
 * 把因报错码命中（默认 401/403/422）被 provider 关闭的 Token 同步为「禁用」状态：
 * 保留在渠道里（不删除），只是把开关关掉，用户可手动重新启用。
 * 同步会经过 SettingsStore.update 走持久化与云同步。best-effort，失败不影响主流程。
 *
 * **绝不会禁掉渠道的最后一个可用 Token**，详见 [resolveDisabledTokens]。
 */
fun syncClosedImageProviderKeys(provider: ImageProviderSetting) {
    try {
        val keyRoulette = KeyRoulette.lru(getKoin().get<Context>())
        val providerId = provider.id.toString()
        val closed = keyRoulette.closedKeys(providerId)
        if (closed.isEmpty()) return
        val next = resolveDisabledTokens(
            allTokens = provider.apiKeyTokens,
            currentDisabled = provider.disabledTokens,
            closed = closed,
            keyRoulette = keyRoulette,
            providerId = providerId,
            providerName = provider.name,
        ) ?: return
        val settingsStore = getKoin().get<SettingsStore>()
        runBlocking {
            settingsStore.update { settings ->
                settings.copy(
                    imageProviders = settings.imageProviders.map { p ->
                        if (p.id == provider.id) p.withDisabledTokens(next) else p
                    }
                )
            }
        }
        Log.i(TAG, "Synced disabled tokens on image provider ${provider.name}: $next")
    } catch (_: Exception) {
        // 同步是尽力而为
    }
}

/** [ProviderSetting]（LLM 渠道）版本的 [syncClosedImageProviderKeys]。 */
fun syncClosedProviderKeys(provider: ProviderSetting) {
    try {
        val keyRoulette = KeyRoulette.lru(getKoin().get<Context>())
        val providerId = provider.id.toString()
        val closed = keyRoulette.closedKeys(providerId)
        if (closed.isEmpty()) return
        val next = resolveDisabledTokens(
            allTokens = provider.apiKeyTokens,
            currentDisabled = provider.disabledTokens,
            closed = closed,
            keyRoulette = keyRoulette,
            providerId = providerId,
            providerName = provider.name,
        ) ?: return
        val settingsStore = getKoin().get<SettingsStore>()
        runBlocking {
            settingsStore.update { settings ->
                settings.copy(
                    providers = settings.providers.map { p ->
                        if (p.id == provider.id) p.withDisabledTokens(next) else p
                    }
                )
            }
        }
        Log.i(TAG, "Synced disabled tokens on provider ${provider.name}: $next")
    } catch (_: Exception) {
        // 同步是尽力而为
    }
}
