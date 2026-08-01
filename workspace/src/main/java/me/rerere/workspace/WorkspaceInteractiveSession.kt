package me.rerere.workspace

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 会话内一次命令执行的结果。
 *
 * @param stdout 本次命令的输出(已剥离哨兵行; stderr 已合并入此)
 * @param exitCode 命令退出码; null 表示尚未结束(超时返回了部分输出)
 * @param stillRunning true 表示命令仍在跑, 可用 read 续读
 * @param cursor 下次续读应传入的游标
 * @param droppedChars 因输出缓冲溢出而丢失的字符数
 */
data class SessionExecResult(
    val stdout: String,
    val exitCode: Int?,
    val stillRunning: Boolean,
    val cursor: Long,
    val droppedChars: Long,
)

/**
 * 交互式 shell 会话的哨兵协议。
 *
 * ## 为什么需要哨兵
 * 常驻 bash 的 stdout 是一条连续的流, 没有天然的「命令边界」。要切出「本次命令的输出」
 * 和「本次的退出码」, 必须让 bash 自己在命令结束后打印唯一标记。
 *
 * 发往 stdin 的实际内容:
 * ```
 * eval "$(echo <BASE64> | base64 -d)"; __rk=$?; printf '\n__RK_<NONCE>_%d__\n' "$__rk"
 * ```
 *
 * ## 三个关键设计(均经 2026-08-01 proot 实测验证)
 * 1. **命令走 base64**: 多行、heredoc、引号、反引号、`${}` 全部免疫转义。
 *    实测含 `$HOME` / `"quoted"` / 反引号 / `\\` / `${x}` 的 heredoc 原样通过。
 * 2. **nonce 每次随机**: 防止命令自身输出伪造哨兵导致提前截断。
 * 3. **超时不杀命令**: 未见哨兵则返回 partial + stillRunning, 游标推进, 之后可续读。
 *    实测 6 秒循环在 2.5 秒超时后, 续读能完整拿到剩余输出与退出码。
 *
 * ## 已知限制
 * - 无 pty: 向 stdin 写 `\u0003` **不会**产生 SIGINT(无 tty line discipline),
 *   反而会污染下一条命令(实测报 `$'\003eval': command not found`)。
 *   中断必须走 [interruptScript]。
 * - proot 下所有进程共享同一 pgrp, **严禁** `kill -<sig> -<pgid>`, 会波及整个工作区。
 */
object WorkspaceSessionProtocol {

    const val SESSION_PID_PREFIX = "__RK_SPID_"
    const val SENTINEL_SUFFIX = "__"

    private val sessionPidRegex =
        Regex(Regex.escape(SESSION_PID_PREFIX) + "(\\d+)" + Regex.escape(SENTINEL_SUFFIX))

    /**
     * 会话启动后立即写入的初始化串:
     * 抑制 prompt / job control / 回显, 并上报 rootfs 内真实 pid。
     */
    fun initScript(): String = buildString {
        // PS1/PS2 置空: 否则每条命令后混入提示符污染输出
        append("export PS1=''; export PS2=''; ")
        // 关闭 job control, 避免 "[1]+ Done" 之类噪音
        append("set +m; ")
        // 非 tty 下 stty 必然失败, 兜底避免中断初始化
        append("stty -echo 2>/dev/null || true; ")
        // 上报会话 bash 在 rootfs 内的 pid, interrupt 用它定位前台子进程
        append("printf '")
        append(SESSION_PID_PREFIX)
        append("%d")
        append(SENTINEL_SUFFIX)
        append("\\n' \"\$\$\"\n")
    }

    /** 从会话初始输出里解析出 bash 的 rootfs 内 pid */
    fun parseSessionPid(output: String): Int? =
        sessionPidRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** 8 位十六进制随机 nonce */
    fun newNonce(): String = buildString {
        repeat(4) { append("%02x".format(Random.nextInt(256))) }
    }

    fun sentinelRegex(nonce: String): Regex =
        Regex("__RK_" + Regex.escape(nonce) + "_(-?\\d+)__")

    /** 构造一条带哨兵的命令行(自带结尾换行), 直接写入 stdin */
    fun wrapCommand(command: String, nonce: String): String {
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        return "eval \"\$(echo " + encoded + " | base64 -d)\"; __rk=\$?; " +
            "printf '\\n__RK_" + nonce + "_%d__\\n' \"\$__rk\"\n"
    }

    /**
     * 中断脚本: 遍历 /proc 找出会话 bash 的直系子进程(即当前前台命令)并发 SIGINT。
     * 在**另一个一次性 shell** 中执行(会话前台被占用时无法 exec),
     * 一次性 shell 与会话共享 rootfs 与 pid 空间, 实测可见彼此进程。
     *
     * 不能用 `kill -INT -<pgid>`: proot 下所有进程 pgrp 相同, 会杀掉整个工作区
     * (包括用户的定时同步进程)。必须逐个精确匹配 ppid。
     */
    fun interruptScript(sessionPid: Int): String = buildString {
        appendLine("killed=0")
        appendLine("for p in \$(ls /proc 2>/dev/null | grep -E '^[0-9]+\$'); do")
        appendLine("  [ -r \"/proc/\$p/stat\" ] || continue")
        appendLine("  ppid=\$(awk '{print \$4}' \"/proc/\$p/stat\" 2>/dev/null)")
        appendLine("  if [ \"\$ppid\" = \"" + sessionPid + "\" ]; then")
        appendLine("    kill -INT \"\$p\" 2>/dev/null && killed=\$((killed+1))")
        appendLine("  fi")
        appendLine("done")
        appendLine("echo \"interrupted=\$killed\"")
    }

    /**
     * 从增量输出中切出本次命令的结果。
     *
     * @return 命中哨兵返回 (纯输出, exitCode); 未命中返回 (原样输出, null)
     */
    fun splitBySentinel(chunk: String, nonce: String): Pair<String, Int?> {
        val match = sentinelRegex(nonce).find(chunk) ?: return chunk to null
        val exitCode = match.groupValues.getOrNull(1)?.toIntOrNull()
        // 哨兵前那个 \n 是我们自己加的, 去掉避免尾部多余空行
        val body = chunk.substring(0, match.range.first).removeSuffix("\n")
        return body to exitCode
    }
}

/**
 * 一个交互式会话的协议层可变状态。
 * 进程句柄本身存放在 [WorkspaceBackgroundProcessRegistry](pinned = true)。
 */
class WorkspaceSessionState(
    val id: String,
    val root: String,
) {
    /** 会话 bash 在 rootfs 内的 pid, 用于 interrupt 定位前台子进程 */
    @Volatile
    var sessionPid: Int? = null

    /** 输出读取游标 */
    @Volatile
    var cursor: Long = 0L

    /**
     * 上一条命令若超时未结束, 记录其 nonce; 续读时用它继续找哨兵。
     * null 表示无悬挂命令。
     */
    @Volatile
    var pendingNonce: String? = null

    /** 悬挂命令原文(截断), 便于 list 时告知「卡在哪条命令」 */
    @Volatile
    var pendingCommand: String? = null
}

/** 会话协议状态注册表 */
class WorkspaceSessionRegistry {
    private val states = ConcurrentHashMap<String, WorkspaceSessionState>()

    fun register(state: WorkspaceSessionState) {
        states[state.id] = state
    }

    fun get(id: String): WorkspaceSessionState? = states[id]

    fun remove(id: String): WorkspaceSessionState? = states.remove(id)

    fun list(root: String): List<WorkspaceSessionState> =
        states.values.filter { it.root == root }

    /** 清理已无对应进程的孤儿状态 */
    fun pruneOrphans(aliveIds: Set<String>) {
        states.keys.toList().forEach { if (it !in aliveIds) states.remove(it) }
    }
}
