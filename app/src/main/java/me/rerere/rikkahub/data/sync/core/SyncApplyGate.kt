package me.rerere.rikkahub.data.sync.core

/**
 * 回环抑制开关：当 SyncEngine 正在"应用云端变更"（pull / 冲突采纳云端）时置 true，
 * 各 Repository 的写钩检查到本标志后跳过 outbox 入队，避免 拉→推→拉 死循环。
 * 进程内单例，无需跨进程。
 */
object SyncApplyGate {
    @Volatile
    var applyingRemote: Boolean = false
}
