package me.rerere.rikkahub.data.model

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Physical app-lock configuration, intentionally separate from LLM supervision rules. */
@Serializable
data class FocusLockSettings(
    val enabled: Boolean = false,
    val tasks: List<FocusLockTask> = emptyList(),
    /** Return to the launcher when an unapproved app reaches the foreground. */
    val returnHomeOnViolation: Boolean = true,
    /** Keep launcher/System UI usable; the accessibility service still catches the next app. */
    val allowLauncherAndSystemUi: Boolean = true,
    /** Additional package names supplied by the user. */
    val additionalAllowedPackages: Set<String> = emptySet(),
    /**
     * 守门员 / 定时任务通过 `supervision_admin(set_focus_lock_state)` 设的锁。
     *
     * 独立于 [enabled] 与 [tasks]：agent 可以在没有任何用户任务的情况下临时锁机。
     * 之所以要落盘，是因为旧实现把它放在内存里，进程被杀就丢，而且
     * settingsFlow 每次发射都会把它清掉（2026-08-20 bug）。
     */
    val agentLockActive: Boolean = false,

    /**
     * **运行态快照**（2026-10-04）。
     *
     * ## 为什么把它放进设置里
     *
     * 原先运行态只活在 [me.rerere.rikkahub.focus.FocusPolicyEngine] 的进程内存里，
     * 想看只能调 `supervision_admin(get_focus_status)` —— 一堆 json 直接刷在对话里，
     * 丑且不可检索。落进这个结构之后，它自动出现在
     * `/rikkahub-data/setting-json/focus_lock.json`（见 `SettingsJsonExchange`：
     * `ConfigFileSpec("focus_lock.json", listOf("focusLock"))`），
     * agent 用 workspace 的 read_file 就能看，也能 `git diff` 出变化。
     *
     * ## 它是【只读镜像】，不是配置
     *
     * - **读**：随便读，`get_focus_status` 与 json 都读它。
     * - **写**：只由 `FocusPolicyEngine` 写（节流 + 只在实质变化时落盘）。
     *   人/agent 手改这个块没有意义 —— 下一次拦截/拉起就会被覆盖。
     * - **不同步**：`focusLock` 整个字段在 `SyncFieldRegistry` 里登记为设备本地，
     *   运行态天然跟着不走云（而且它本来就描述“这台机器”的状态）。
     */
    val runtime: FocusRuntimeState = FocusRuntimeState(),
)

/**
 * 物理锁机的**运行态**快照，落进 `focusLock.json` 供人/agent 查看。
 *
 * 这里是「这台机器此刻到底在发生什么」，与 [FocusLockSettings] 的配置字段严格分开：
 * 配置回答“应该在什么时候锁”，运行态回答“实际有没有锁住”。
 * 两者分开的教训见 `FocusPolicyEngine` 的头注释：2026-08-19/20 那批 bug 全是
 * 「报喜不报忧」—— 锁态写进去了，但没人知道它其实被设置流或熔断改掉了。
 */
@Serializable
data class FocusRuntimeState(
    /** 本快照的落盘时刻（epoch ms）。用于判断这份镜像有多新。 */
    val updatedAt: Long = 0L,

    /** RikkaHub 的无障碍服务此刻有没有被启用（权限页/系统设置里关掉就是 false）。 */
    val accessibilityServiceEnabled: Boolean = false,

    /** 物理锁此刻是否**实际生效**（`FocusPolicyEngine.isLockActive`，不是配置意图）。 */
    val lockActive: Boolean = false,

    /** 锁态来源：没锁 / 命中任务时段 / 守门员 agent 临时上锁。 */
    val lockSource: FocusLockSource = FocusLockSource.OFF,

    /** 最近一次解析出的真正前台包名（用于排查误拦）。 */
    val lastResolvedForeground: String? = null,

    /** 最近一次被弹回桌面的包名。 */
    val lastInterceptPackage: String? = null,

    val lastInterceptAt: Long = 0L,

    /** 累计拦截次数（进程重启不清零，跟着快照落盘）。 */
    val interceptCountTotal: Int = 0,

    /** 熔断触发时刻；0 = 从没触发过。 */
    val fuseTrippedAt: Long = 0L,

    /** 最近一次被设备桥接口拉起的包（`POST /api/app/launch`）。 */
    val lastLaunchPackage: String? = null,
    val lastLaunchAt: Long = 0L,
    /** 最近一次拉起请求有没有发出去（≠ 目标真的到了前台，见 [FocusAppLauncher]）。 */
    val lastLaunchOk: Boolean = false,

    /** 最近若干条判定轨迹，格式 `<epoch ms>:<entry>`，最新的在后。 */
    val recentDecisions: List<String> = emptyList(),
)

/** 锁态来源。 */
@Serializable
enum class FocusLockSource {
    /** 没在锁 */
    OFF,

    /** 命中用户配的 [FocusLockTask] 时间窗 */
    SCHEDULE,

    /** 守门员 / 定时任务经 `set_focus_lock_state` 上的临时锁 */
    AGENT,
}

@Serializable
enum class FocusLockTaskMode {
    POMODORO,
    FIXED_WINDOW,
}

/** One user-created time window. Days use ISO numbering: Monday = 1 … Sunday = 7. */
@Serializable
data class FocusLockTask(
    val id: Uuid = Uuid.random(),
    val name: String = "番茄锁机",
    val enabled: Boolean = true,
    val daysOfWeek: Set<Int> = (1..5).toSet(),
    val startMinute: Int = 8 * 60 + 30,
    val endMinute: Int = 11 * 60 + 50,
    val mode: FocusLockTaskMode = FocusLockTaskMode.POMODORO,
    val workMinutes: Int = 45,
    val breakMinutes: Int = 10,
    /** 0 = repeat until the window ends. */
    val cycles: Int = 0,
    /** If true, the physical lock remains active during pomodoro breaks. */
    val lockDuringBreak: Boolean = false,
) {
    fun containsWindow(minuteOfDay: Int, isoDay: Int): Boolean {
        return if (startMinute <= endMinute) {
            isoDay in daysOfWeek && minuteOfDay in startMinute until endMinute
        } else if (minuteOfDay >= startMinute) {
            isoDay in daysOfWeek
        } else {
            val previousDay = if (isoDay == 1) 7 else isoDay - 1
            previousDay in daysOfWeek
        }
    }

    fun isActiveAt(epochMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || startMinute == endMinute) return false
        val local = Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val minute = local.hour * 60 + local.minute
        if (!containsWindow(minute, local.dayOfWeek.isoDayNumber)) return false
        if (mode == FocusLockTaskMode.FIXED_WINDOW) return true

        val elapsed = if (startMinute <= endMinute) {
            minute - startMinute
        } else if (minute >= startMinute) {
            minute - startMinute
        } else {
            24 * 60 - startMinute + minute
        }
        val work = workMinutes.coerceAtLeast(1)
        val rest = breakMinutes.coerceAtLeast(0)
        val cycleLength = work + rest
        if (cycles > 0 && elapsed >= cycleLength * cycles) return false
        if (rest == 0) return true
        val phase = elapsed % cycleLength
        return phase < work || lockDuringBreak
    }
}

fun FocusLockSettings.isActiveAt(epochMillis: Long = System.currentTimeMillis()): Boolean =
    enabled && tasks.any { it.isActiveAt(epochMillis) }

/**
 * 是否有任意一个启用中的任务窗口命中当前时刻——**不看总开关 [FocusLockSettings.enabled]**。
 *
 * 与 [isActiveAt] 的区别是「锁机没在锁」不等于「不在学习时段」：
 * 用户手动把不做手机控掐死之后锁就不生效了，但那正是最该提醒他的时候。
 */
fun FocusLockSettings.isAnyTaskWindowAt(epochMillis: Long = System.currentTimeMillis()): Boolean =
    tasks.any { it.isActiveAt(epochMillis) }
