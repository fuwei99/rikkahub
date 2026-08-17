package me.rerere.rikkahub.data.ai.schedule

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.isActiveNow

private const val TAG = "SupervisionWatcher"

/**
 * 专注监督时段的状态翻转观察者（2026-08-18，修 bug「监督期停在非白名单助手就能爽玩」）。
 *
 * 为什么需要一个独立的 watcher：
 * [me.rerere.rikkahub.data.model.SupervisionSettings.isActiveNow] 是**纯时间函数**，
 * 没有任何事件源。原实现只在「用户主动切助手」时校验白名单
 * （`PreferencesStore.updateAssistant`），于是只要在监督开始**之前**就把当前助手
 * 停在非白名单助手上，整段监督期都不会有任何一处代码去纠正它。
 *
 * 这里每 [TICK_MS] 采样一次，检测到 false → true 的翻转（或启动时已经在时段内），
 * 就把当前助手强制切回白名单，让「进入监督自动跳转」真正发生。
 *
 * 注意：本 watcher 只负责**切当前助手**这一件事。禁止生成的硬拦截在
 * `ChatService.supervisionBlockReason()`（执行级），两者互不替代 ——
 * watcher 可能因为进程被杀而漏掉一次翻转，硬拦截必须独立成立。
 */
class SupervisionWatcher(
    private val settingsStore: SettingsStore,
) {
    /** 挂在 AppScope 里长跑，随进程存亡。 */
    suspend fun run() {
        var wasActive: Boolean? = null
        while (true) {
            runCatching { tick(previousActive = wasActive) }
                .onSuccess { wasActive = it }
                .onFailure { Log.w(TAG, "tick failed", it) }
            delay(TICK_MS)
        }
    }

    /** @return 本次采样得到的 isActiveNow()，供下次比较翻转用。 */
    private suspend fun tick(previousActive: Boolean?): Boolean {
        val settings = settingsStore.settingsFlow.first()
        val sup = settings.supervision
        val active = sup.isActiveNow()
        if (!active) return false

        val allowed = sup.allowedAssistantIds
        if (allowed.isEmpty()) return true

        val current = settings.assistantId
        if (current in allowed) return true
        // 守门员也算合法停留点（申诉 / 解锁通道）
        if (current == sup.unlockGrantorAssistantId) return true

        // 目标助手：优先守门员（那才是监工本人），否则白名单里第一个仍然存在的助手
        val existingIds = settings.assistants.map { it.id }.toSet()
        val target = sup.unlockGrantorAssistantId?.takeIf { it in existingIds }
            ?: allowed.firstOrNull { it in existingIds }
        if (target == null) {
            Log.w(TAG, "no valid study assistant to switch to (allowed=$allowed)")
            return true
        }

        val reason = if (previousActive == false) "supervision just started" else "startup/recovery"
        Log.i(TAG, "switching assistant $current -> $target ($reason)")
        settingsStore.updateAssistant(target)
        return true
    }

    private companion object {
        /**
         * 30s：时段边界最多晚 30 秒生效。再短就是白烧电，
         * 真正的即时保证由 ChatService 的执行级拦截兜着。
         */
        const val TICK_MS = 30_000L
    }
}
