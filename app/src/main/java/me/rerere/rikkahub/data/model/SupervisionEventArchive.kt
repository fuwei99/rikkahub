package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 监督事件的**本地审计归档**（2026-10-04）。
 *
 * ## 为什么需要它
 *
 * [SupervisionEventLog.pruneInert] 裁掉的是「永久失效」的事件，也就是历史。
 * 而历史是有用的：申诉、复盘、「我到底哪天在摸鱼」。所以被裁的事件不是丢掉，
 * 是**降级成本机只读档案**（JSON Lines，一行一个事件，方便 `grep` / `wc -l`）。
 *
 * ## 为什么不留在同步集合里
 *
 * 那正是要解决的问题：OR-Set 里删不掉，留着就一直长。审计读的是「历史」，
 * 不需要参与 CRDT 合并，更不需要每 5 分钟跟着快同步来回搬。
 * 放本地文件（`files/` 下，不上云）是唯一同时满足「留着」与「不涨同步体积」的形态。
 *
 * ## 为什么不用 android.util.Log
 *
 * 这个类落在 `data/model`，而 model 层会被 JVM 单测直接编译/加载。
 * 引 android 的日志工具会让「跑单测时不小心碰到它」当场炸 `not mocked`。
 * 所以失败只返回 false，日志由调用方（`PreferencesStore`，本来就是 Android 侧）记。
 */
object SupervisionEventArchive {

    /** 相对 `files/` 的路径。写在这里而不是散在调用点，方便排障时直接找。 */
    const val RELATIVE_PATH = "supervision/event_archive.jsonl"

    /**
     * 追加一批已被裁掉的事件。
     *
     * @return 是否写成功。**不抛异常**：归档是旁路，写失败绝不能连累设置落盘。
     */
    fun append(target: Path, events: List<SupervisionEvent>): Boolean {
        if (events.isEmpty()) return true
        return runCatching {
            target.parent?.let { Files.createDirectories(it) }
            val text = buildString {
                events.forEach { event ->
                    // 用**显式 serializer**（StringFormat 的成员重载），不依赖
                    // `kotlinx.serialization.json.encodeToString` 那个 reified 扩展的 import ——
                    // 后者靠 import 才能解析，容易在本文件漏掉而编译失败。
                    append(JsonInstant.encodeToString(SupervisionEvent.serializer(), event))
                    append('\n')
                }
            }
            Files.writeString(target, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            true
        }.getOrDefault(false)
    }
}
