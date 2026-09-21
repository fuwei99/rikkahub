package me.rerere.rikkahub.data.ai.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.AgentInboxEntity
import me.rerere.rikkahub.data.files.AppPaths
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * agent 信箱的**落盘归档**（2026-09-22）。
 *
 * ## 为什么要有这个
 *
 * 收件箱的硬不变式 I4 规定「读即已读」（`takeUnread` 一读就 `markRead`），
 * 目的是「同一封信不得两次进上下文」。这个不变式本身没问题，但它带来一个
 * 谁也没想到的副作用：**历史被读掉了。**
 *
 * 查岗 agent 读一次信，那封信就从工具视图里永久消失 ——
 * - 事后想回看「三天前主对话到底给我发了什么」→ 查不到；
 * - 想在几十封信里搜一个关键词 → 不支持；
 * - 除了 `agent_inbox` 表（要写 SQL）没有任何窗口。
 *
 * ## 所以
 *
 * 每封信在**入箱那一刻**（[AgentInboxStore.enqueue]，唯一写入口）额外追加一份
 * 明文到磁盘。归档是**只写的旁路**：不参与 I4、不影响读信语义、读多少次都在。
 *
 * 存 `<filesDir>/agent-mail/<targetId>.md`，**一个对话一个文件**：
 * - 「输入会话 id 就能查到任意对话的收件箱」= 直接开那个文件；
 * - 跨对话搜 = 一条 `rg`。所以**不需要**再单独造一个「查历史 / 搜信件」的接口，
 *   agent 有 shell 就能搜，比任何自研查询接口都灵活。
 *
 * ## 边界
 *
 * - **不进 D1 / 云同步**：与 `agent_session`、`agent_inbox` 同策略，属本地执行期数据。
 * - **只增不改不删**：不做轮转、不做清理。文件大小 ≈ 该对话收到的全部邮件正文，
 *   信箱本来就是低频小流量，暂不需要生命周期管理。真涨起来了再加按年/按月切分。
 * - 归档失败**绝不影响投递**（[append] 内部吞异常）：归档是旁路，主路是 DB。
 */
class AgentMailArchive(private val context: Context) {

    /** 串行化追加，避免并发投递把同一文件写出交错内容。 */
    private val lock = Mutex()

    /** 归档根目录 `<filesDir>/agent-mail/`。 */
    fun dir(): File = File(AppPaths.filesDir(context), DIR_NAME).apply { mkdirs() }

    /** 某个对话的收件箱归档文件 `<filesDir>/agent-mail/<targetId>.md`。 */
    fun fileFor(targetId: String): File = File(dir(), "$targetId.md")

    /** 归档文件的绝对路径（工具结果 / HTTP 响应回报给调用方「历史在哪」）。 */
    fun pathFor(targetId: String): String = fileFor(targetId).absolutePath

    /**
     * 追加一封信。调用方不需要 catch —— 失败只写日志，不向外抛。
     */
    suspend fun append(row: AgentInboxEntity) {
        val text = render(row)
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    fileFor(row.targetId).appendText(text)
                }
            }
        }
    }

    /**
     * 条目格式（刻意保持单行密信息 + 正文独立成块，`rg` 与肉眼都友好）：
     *
     * ```
     * ## #12 · 2026-09-22 02:14:33 · to=<targetId> · from=<senderId> ("标题") · sub_agent/report/mail
     * 正文……
     *
     * ```
     */
    private fun render(row: AgentInboxEntity): String = buildString {
        append("## #").append(row.id)
        append(" · ").append(formatTime(row.createdAt))
        append(" · to=").append(row.targetId)
        append(" · from=").append(row.senderId ?: "-")
        if (row.senderTitle.isNotBlank()) {
            append(" (\"").append(row.senderTitle).append("\")")
        }
        append(" · ").append(row.source).append('/').append(row.kind).append('/').append(row.urgency)
        append('\n')
        append(row.body.trimEnd())
        append("\n\n")
    }

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(FORMATTER)

    companion object {
        const val DIR_NAME = "agent-mail"

        private val FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
