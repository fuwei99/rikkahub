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
     *
     * @param pty true 时额外做 pty 专属处理: 关闭输入回显与 SIGINT 之外的特殊字符处理。
     *   管道会话下 stty 必然失败(不是 tty), 但保留 `|| true` 兜底无害。
     */
    fun initScript(pty: Boolean = false): String = buildString {
        // PS1/PS2 置空: 否则每条命令后混入提示符污染输出
        append("export PS1=''; export PS2=''; ")
        // 关闭 job control, 避免 "[1]+ Done" 之类噪音
        append("set +m; ")
        if (pty) {
            // pty 下这些才真正生效:
            // -echo   关掉输入回显, 否则每条命令原文会被 pty 回显进输出流
            // -onlcr  关掉 \n -> \r\n 转换, 免得输出里全是 \r
            // -ixon   关掉 ^S/^Q 流控, 防止命令里的字节意外冻结会话
            // isig    **保留**信号处理, 这是 \u0003 -> SIGINT 生效的前提
            append("stty -echo -onlcr -ixon isig 2>/dev/null || true; ")
            // 关掉 readline 的括号粘贴模式, 否则会往输出里塞 \e[?2004h 转义
            append("bind 'set enable-bracketed-paste off' 2>/dev/null || true; ")
        } else {
            append("stty -echo 2>/dev/null || true; ")
        }
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
     * 中断脚本(**管道会话降级路径**; pty 会话请直接写 `\u0003`)。
     *
     * ## 能力边界(2026-08-01 实测, 必读)
     * 无 pty 时内核不做信号投递, 只能自己找进程, 因此存在**理论上限**:
     * - 能中断: 会话 bash 的子孙进程(编译、下载、python 脚本等外部命令)。
     * - **不能**中断: bash 自己在执行的循环体, 例如 `while true; do sleep 1; done`。
     *   实测杀掉当前 sleep 后循环立刻起新的(pid 15494 -> 15504 递增), 变成打地鼠;
     *   即使直接对会话 bash 发 SIGINT 也无效(pid 仍递增), 因为无 pty 时它不是
     *   前台进程组 leader, 非交互 bash 会忽略 SIGINT。唯一能停的办法是 SIGTERM
     *   杀掉 bash 本身 —— 那等于关闭会话, 所以这里**不做**, 交给调用方决定。
     *
     * 也就是说: 本脚本尽力而为, 返回 interrupted 计数供调用方判断;
     * 若返回 0 且命令仍挂着, 说明撞上了上述上限, 应提示用户改用 pty 会话或 close。
     *
     * ## 实现要点
     * 1. 递归收集整棵子孙树(纯 shell BFS), 而非只看直系子进程。
     * 2. 自底向上(先叶子后父)发 SIGINT, 避免父先死导致孤儿被收养后失联。
     * 3. 等 300ms 后对残留者补 SIGTERM。
     * 4. ppid 用 `sed 's/.*) [A-Za-z] //'` 剥离而非 `awk '{print $4}'`:
     *    /proc/PID/stat 的 comm 字段含空格或括号时(如 `(a b) c`), 按空格取第 4 域
     *    会拿到状态位 `S` 而不是 ppid。已验证 4 种畸形进程名下 sed 法全部正确。
     * 5. 仍**不使用** `kill -<sig> -<pgid>`: proot 下所有进程共享同一 pgrp,
     *    那样会连带杀掉用户的定时同步进程等整个工作区的东西(已验证会波及)。
     */
    fun interruptScript(sessionPid: Int): String = buildString {
        appendLine("session_pid=" + sessionPid)
        // 收集 pid -> ppid 全表, 只读一次 /proc, 避免边杀边遍历时表变化
        appendLine("all=\$(for p in \$(ls /proc 2>/dev/null | grep -E '^[0-9]+\$'); do")
        appendLine("  [ -r \"/proc/\$p/stat\" ] || continue")
        // stat 第 4 域是 ppid; comm 可能含空格括号, 用 sed 剥掉 (...) 再取域更稳
        appendLine("  ppid=\$(sed 's/.*) [A-Za-z] //' \"/proc/\$p/stat\" 2>/dev/null | awk '{print \$1}')")
        appendLine("  [ -n \"\$ppid\" ] && echo \"\$p \$ppid\"")
        appendLine("done)")
        // 从 session_pid 出发逐层展开子孙(纯 shell BFS, 无递归函数)
        appendLine("targets=\"\"")
        appendLine("frontier=\"\$session_pid\"")
        appendLine("depth=0")
        appendLine("while [ -n \"\$frontier\" ] && [ \$depth -lt 32 ]; do")
        appendLine("  next=\"\"")
        appendLine("  for parent in \$frontier; do")
        appendLine("    kids=\$(echo \"\$all\" | awk -v par=\"\$parent\" '\$2==par {print \$1}')")
        appendLine("    for k in \$kids; do")
        appendLine("      targets=\"\$targets \$k\"")
        appendLine("      next=\"\$next \$k\"")
        appendLine("    done")
        appendLine("  done")
        appendLine("  frontier=\"\$next\"")
        appendLine("  depth=\$((depth+1))")
        appendLine("done")
        // 反转顺序: 先叶子后父, 避免父进程先死导致孤儿被 init 收养后找不到
        appendLine("ordered=\$(for t in \$targets; do echo \"\$t\"; done | tac)")
        appendLine("sent=0")
        appendLine("for t in \$ordered; do")
        appendLine("  kill -INT \"\$t\" 2>/dev/null && sent=\$((sent+1))")
        appendLine("done")
        // 给 SIGINT 一点生效时间, 然后对残留者补 SIGTERM
        appendLine("[ \$sent -gt 0 ] && sleep 0.3")
        appendLine("killed=0")
        appendLine("for t in \$ordered; do")
        appendLine("  if [ -d \"/proc/\$t\" ]; then")
        appendLine("    kill -TERM \"\$t\" 2>/dev/null && killed=\$((killed+1))")
        appendLine("  fi")
        appendLine("done")
        appendLine("echo \"interrupted=\$sent terminated=\$killed\"")
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

    /** 是否为 pty 会话(决定 interrupt 走 `\u0003` 还是 /proc 遍历) */
    @Volatile
    var usesPty: Boolean = false

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
