package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.disabledTokens
import me.rerere.ai.provider.withDisabledTokens
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.java.KoinJavaComponent.getKoin

private const val TAG = "KeyCloseSync"

/**
 * 把因报错码命中（默认 401/403/422）被 provider 关闭的 Token 同步为「禁用」状态：
 * 保留在渠道里（不删除），只是把开关关掉，用户可手动重新启用。
 * 同步会经过 SettingsStore.update 走持久化与云同步。best-effort，失败不影响主流程。
 */
fun syncClosedImageProviderKeys(provider: ImageProviderSetting) {
    try {
        val keyRoulette = KeyRoulette.lru(getKoin().get<Context>())
        val closed = keyRoulette.closedKeys(provider.id.toString())
        if (closed.isEmpty()) return
        val settingsStore = getKoin().get<SettingsStore>()
        runBlocking {
            settingsStore.update { settings ->
                settings.copy(
                    imageProviders = settings.imageProviders.map { p ->
                        if (p.id == provider.id) {
                            p.withDisabledTokens((p.disabledTokens + closed).distinct())
                        } else {
                            p
                        }
                    }
                )
            }
        }
        Log.i(TAG, "Auto-disabled closed tokens on image provider ${provider.name}: $closed")
    } catch (_: Exception) {
        // 同步是尽力而为
    }
}

/** [ProviderSetting]（LLM 渠道）版本的 [syncClosedImageProviderKeys]。 */
fun syncClosedProviderKeys(provider: ProviderSetting) {
    try {
        val keyRoulette = KeyRoulette.lru(getKoin().get<Context>())
        val closed = keyRoulette.closedKeys(provider.id.toString())
        if (closed.isEmpty()) return
        val settingsStore = getKoin().get<SettingsStore>()
        runBlocking {
            settingsStore.update { settings ->
                settings.copy(
                    providers = settings.providers.map { p ->
                        if (p.id == provider.id) {
                            p.withDisabledTokens((p.disabledTokens + closed).distinct())
                        } else {
                            p
                        }
                    }
                )
            }
        }
        Log.i(TAG, "Auto-disabled closed tokens on provider ${provider.name}: $closed")
    } catch (_: Exception) {
        // 同步是尽力而为
    }
}
