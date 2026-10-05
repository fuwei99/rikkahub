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

    /**
     * **名单模式（2026-10-05）**。两种模式互斥，同一时刻只有一套名单在起作用：
     *
     * - [FocusLockMode.WHITELIST]：只有白名单里的包能用，其余一律弹回桌面；
     *   **黑名单（长期 + 临时）整体失效**。
     * - [FocusLockMode.BLACKLIST]：只有黑名单里的包被拦，其余一律放行；
     *   **白名单（长期 + 临时）整体失效**。
     *
     * 边界：只有 [neverBlockPackages] 在两种模式下**都**放行 —— 那不是名单，是保险丝。
     */
    val mode: FocusLockMode = FocusLockMode.WHITELIST,

    /**
     * 长期黑名单：**只在监督时段内生效**（时段外手机就是个手机）。
     *
     * 只在 [FocusLockMode.BLACKLIST] 模式下被读。
     */
    val blockedPackages: Set<String> = emptySet(),

    /**
     * 保险丝：**两种模式下都永远不拦**的包。
     *
     * 存在的理由是安全，不是方便：黑名单模式下如果把「设置」封了，用户就进不去
     * 关闭无障碍服务 —— 那是**唯一能在锁机期间自救的物理后门**（另一个是长按电源重启）。
     * 一个夜里的手滑就足以造出这个局面。
     *
     * 默认值 = 系统弹窗 / 权限框 / 安装器 / 设置入口 / 锁机自身。
     * 与其它名单一样是**配置**，不满意随时改 —— 但删之前想清楚。
     */
    val neverBlockPackages: Set<String> = DEFAULT_NEVER_BLOCK_PACKAGES,

    /** Additional package names supplied by the user. */
    val additionalAllowedPackages: Set<String> = emptySet(),

    /**
     * **无条件放行**的包名（2026-10-05 从 `FocusPolicyEngine` 的硬编码常量搬到这里）。
     *
     * 搬家的直接原因是个真事故：MIUI / HyperOS 的「设置」实际以
     * `com.miui.securitycenter` 跑起来，而旧名单里只有 `com.miui.settings` ——
     * 代码注释写着「用户关掉无障碍服务的唯一物理后门，必须留」，
     * 实际那个后门在小米上是假的：锁机一开，想进设置关掉它就被弹回桌面。
     * 名单这种东西天生会缺包，所以它必须可配置。
     *
     * 生效白名单 = 本字段 ∪ [additionalAllowedPackages]
     * ∪（[allowLauncherAndSystemUi] 为真时的）[launcherAndSystemUiPackages]。
     */
    val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,

    /**
     * 受 [allowLauncherAndSystemUi] 开关约束的包名（各厂商桌面）。
     *
     * 单独分一组是因为语义不同：关掉那个开关就该连桌面一起拦 ——
     * 「违规 → 弹回桌面 → 换个图标再点」这条循环只有把桌面也拦住才断得掉。
     */
    val launcherAndSystemUiPackages: Set<String> = DEFAULT_LAUNCHER_AND_SYSTEM_UI_PACKAGES,
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

    /**
     * 临时白名单快照，格式 `<包名>=<剩余毫秒>ms`。
     *
     * ⚠️ **只读镜像**：它住在 `FocusPolicyEngine` 的内存里（带时间戳的运行态，不是配置），
     * 改这份 json 没用。要授权请走 `supervision_admin` 的
     * `grant_temporary_whitelist` / `grant_temporary_blacklist`。
     * 放进来是为了「锁机现在到底在拦什么」能一眼看完，不用再调接口。
     */
    val temporaryAllowedPackages: List<String> = emptyList(),

    /** 临时黑名单快照，同上。**听时间戳，不看监督时段**。 */
    val temporaryBlockedPackages: List<String> = emptyList(),
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

/**
 * 名单模式（2026-10-05）。
 *
 * 只存名字不存语义：语义在 `FocusPolicyEngine.isPackageAllowed` 里，
 * 那边按模式分派，两套名单**互斥生效**，永不叠加。
 */
@Serializable
enum class FocusLockMode {
    /** 白名单：名单内的能用，其余弹回桌面。黑名单失效。 */
    WHITELIST,

    /** 黑名单：名单内的被拦，其余放行。白名单失效。 */
    BLACKLIST,
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

/**
 * 无条件放行的**默认值**。
 *
 * 这是新设备/新配置的起点，不是写死的名单 —— 它会被序列化进 `focus_lock.json`
 * （`JsonInstant` 配了 `encodeDefaults = true`），用户与 agent 随意增删。
 *
 * 内容 = 「不放行就等于把用户锁死」的底线（系统弹窗、权限框、安装器、设置入口、自身）
 * + 两个高频学习/通讯工具。
 */
val DEFAULT_ALLOWED_PACKAGES: Set<String> = setOf(
    // 系统层：少任何一条，用户都可能连「关掉锁」的入口都点不到
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.huawei.settings",
    "com.hihonor.settings",
    "com.miui.settings",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    // 锁机自身：否则申诉入口、设置页都点不开
    "me.rerere.rikkahub",
    "me.rerere.rikkahub.debug",
    // 常用工具
    "com.tencent.mm",
    "com.eusoft.eudic",
)

/** 桌面类的默认值。关掉「允许桌面和系统界面」就整组被拦。 */
val DEFAULT_LAUNCHER_AND_SYSTEM_UI_PACKAGES: Set<String> = setOf(
    "com.android.launcher",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.huawei.android.launcher",
    "com.hihonor.android.launcher",
    "com.miui.home",
)

/**
 * 保险丝默认值：**黑名单模式下也永远不拦**的那批。
 *
 * 判据不是「常用」，而是「拦了会把人锁死」：
 * - `android` / SystemUI：系统弹窗、权限框、音量条、通知面板；
 * - 各家 `settings` 与 `com.miui.securitycenter`：**关掉无障碍服务的物理后门**。
 *   ⚠️ MIUI / HyperOS 的设置实际以 `com.miui.securitycenter` 跑起来，
 *   只写 `com.miui.settings` 等于没有后门（2026-10-05 实测踩过）；
 * - 权限控制 / 安装器：否则授权框一弹就被自己弹回桌面；
 * - RikkaHub 自身：否则连申诉入口、设置页都点不开。
 */
val DEFAULT_NEVER_BLOCK_PACKAGES: Set<String> = setOf(
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.miui.settings",
    "com.miui.securitycenter",
    "com.huawei.settings",
    "com.hihonor.settings",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "me.rerere.rikkahub",
    "me.rerere.rikkahub.debug",
)
