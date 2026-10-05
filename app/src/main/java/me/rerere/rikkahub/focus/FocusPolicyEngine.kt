package me.rerere.rikkahub.focus

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import me.rerere.rikkahub.data.model.FocusLockMode
import me.rerere.rikkahub.data.model.FocusLockSettings
import me.rerere.rikkahub.data.model.FocusLockSource
import me.rerere.rikkahub.data.model.FocusRuntimeState
import me.rerere.rikkahub.data.model.isActiveAt

/**
 * Small, process-local policy engine for the focus-lock vertical slice.
 *
 * The schedule agent owns the lock state and can call [setLockActive] or
 * [grantTemporary]. The accessibility service only reports window changes;
 * it does not contain schedule logic or poll UsageStats.
 *
 * 2026-08-20 修复（bugs/2026-08-20_focus-lock白名单失效全App被弹回.md）：
 * 旧实现直接把 `AccessibilityEvent.packageName` 当成前台应用判定，而
 * `TYPE_WINDOW_STATE_CHANGED` 对输入法、系统弹窗、Toast、通知面板等任何窗口都会发，
 * 于是白名单内的应用刚放行、下一个系统窗口事件就把用户弹回桌面，表现为「白名单失效」。
 * 现在改为从 `service.windows` 解析真正的前台应用窗口，并加了系统包兜底、
 * 输入法动态放行、去抖与熔断。
 */
object FocusPolicyEngine {
    private const val TAG = "FocusPolicyEngine"

    /** 同一包名在这个窗口内不重复执行返回桌面，避免一次启动连发多次 HOME。 */
    private const val DEBOUNCE_MILLIS = 1_500L

    /** 熔断窗口与阈值：短时间内拦截过于频繁，说明判定逻辑异常，宁可放开也不要把手机锁成砖头。 */
    private const val FUSE_WINDOW_MILLIS = 10_000L
    private const val FUSE_MAX_INTERCEPTS = 5

    /** 输入法列表缓存时长，避免每个事件都查 IMM。 */
    private const val IME_CACHE_MILLIS = 60_000L

    private val temporaryWhiteList = mutableMapOf<String, Long>()

    /**
     * 临时黑名单：包名 → 到期时刻（epoch ms）。**听时间戳，不看监督时段**。
     *
     * 与 [temporaryWhiteList] 对称。差别在「谁会去读它」：
     * 临时白名单只在锁本来就生效时有意义（放行）；
     * 临时黑名单不一样 —— 「现在起 30 分钟不许碰这个」本来就该**不受时段约束**，
     * 所以它在 [refreshLockState] 里能把锁整个抬起来（见那边注释）。
     */
    private val temporaryBlackList = mutableMapOf<String, Long>()
    private val stateLock = Any()

    @Volatile
    private var configuredSettings: FocusLockSettings = FocusLockSettings()

    @Volatile
    private var manualLockState: Boolean? = null

    /**
     * 把 agent 设的锁状态落盘的钩子，由 Application 注入。
     * 不落盘的话进程一被杀锁就消失（旧实现的问题之一）。
     */
    @Volatile
    var lockStatePersister: ((Boolean) -> Unit)? = null

    /**
     * 把**运行态快照**落盘的钩子，由 Application 注入（2026-10-04）。
     *
     * 与 [lockStatePersister] 分开是刻意的：那个写的是「配置意图」
     * （[FocusLockSettings.agentLockActive]，落在 focusLock 的配置字段上），
     * 这个写的是「实际发生了什么」（落在 focusLock.runtime）。
     * 两者频率、语义、失败后果都不同，混成一个钩子迟早让「配置被运行态覆盖」变成事故。
     *
     * 注入方实现应为「把快照写进 settings.focusLock.runtime」，见 RikkaHubApp。
     */
    @Volatile
    var runtimeStatePersister: ((FocusRuntimeState) -> Unit)? = null

    /** 快照最短落盘间隔。窗口事件能到每秒几十次，不节流等于拿 DataStore 当日志写。 */
    private const val RUNTIME_PERSIST_MIN_INTERVAL_MS = 60_000L

    private var lastRuntimePersistAt = 0L

    @Volatile
    private var lastPersistedRuntime: FocusRuntimeState? = null

    /** 最近一次经设备桥 API 拉起的包（`POST /api/app/launch`）。 */
    @Volatile
    private var lastLaunchPackage: String? = null

    @Volatile
    private var lastLaunchAt: Long = 0L

    @Volatile
    private var lastLaunchOk: Boolean = false

    /** 从无障碍事件里顺手拿到 Application Context，用于探测无障碍服务是否启用。 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 当前是否身处**监督时段**（由 RikkaHubApp 从 `supervision.schedules` 算出后灌进来）。
     *
     * 默认 **true** = 拿不到监督时段信息时**保持旧行为**（助手锁 24 小时生效）。
     * 这是刻意的失败方向：锁这种东西出问题时该偏向「锁着」，而不是默不作声地放开。
     */
    @Volatile
    private var supervisionWindowActive: Boolean = true

    /**
     * 更新「现在在不在监督时段」。2026-10-05 新增。
     *
     * 时段表的权威定义在 `SupervisionSettings.schedules`（早自习 / 午间 / 午自习 /
     * 晚自习 / 夜间），这里**不新增任何配置项**，只是把已有的判定结果喂进来。
     * 旧行为里助手锁完全看不到时段，一旦上锁就 24 小时不放。
     */
    fun setSupervisionWindowActive(active: Boolean) {
        if (supervisionWindowActive == active) return
        supervisionWindowActive = active
        refreshLockState()
        persistRuntime(force = true)
    }

    /**
     * 生效**长期白名单**：全部来自配置（2026-10-05）。
     *
     * 以前这里躺着两批硬编码常量（`baseWhiteList` / `bootstrapAllowList`）。
     * 搬走的原因是个真事故：MIUI / HyperOS 的「设置」实际以 `com.miui.securitycenter`
     * 跑起来，而名单里只有 `com.miui.settings` —— 注释写着「用户关掉无障碍服务的
     * 唯一物理后门，必须留」，实际那个后门在小米上是假的：锁机一开，想进设置关掉它
     * 就被弹回桌面。名单这种东西天生会缺包，所以它必须可配置。
     *
     * 现在三批（无条件 / 用户额外 / 桌面）都在 `FocusLockSettings` 里，落在 `focus_lock.json`。
     * 只在 [me.rerere.rikkahub.data.model.FocusLockMode.WHITELIST] 模式下被读。
     */
    fun effectiveWhitelist(): Set<String> {
        val s = configuredSettings
        return buildSet {
            addAll(s.allowedPackages)
            addAll(s.additionalAllowedPackages)
            if (s.allowLauncherAndSystemUi) addAll(s.launcherAndSystemUiPackages)
        }
    }

    // ---- 诊断信息：给 supervision_admin 的 get_focus_status 用，避免「报喜不报忧」 ----
    @Volatile
    private var lastInterceptPackage: String? = null

    @Volatile
    private var lastInterceptAt: Long = 0L

    @Volatile
    private var lastResolvedPackage: String? = null

    @Volatile
    private var lastFuseTrippedAt: Long = 0L

    @Volatile
    private var interceptCountTotal: Int = 0

    private val recentInterceptAt = ArrayDeque<Long>()
    private val recentDecisions = ArrayDeque<String>()

    @Volatile
    private var imeCache: Set<String> = emptySet()

    @Volatile
    private var imeCacheAt: Long = 0L

    @Volatile
    var isLockActive: Boolean = false
        private set

    fun setLockActive(active: Boolean) {
        manualLockState = active
        if (!active) resetFuseState()
        refreshLockState()
        runCatching { lockStatePersister?.invoke(active) }
            .onFailure { Log.w(TAG, "persist focus lock state failed", it) }
        // 锁态翻转是「实质变化」，不等节流，立刻把运行态镜像刷一遍，
        // 否则 focus_lock.json 里的 lockActive 会落后最多一分钟。
        persistRuntime(force = true)
    }

    /**
     * 设置流推送新配置。
     *
     * ⚠️ 这里**不能**因为 `settings.enabled == false` 就把 [manualLockState] 清掉：
     * `settingsFlow` 是常驻订阅，任何无关设置改动（换模型、发消息触发落盘）都会走到这里，
     * 会把 agent 刚设的锁静默抹掉（2026-08-20 发现的第二个 bug）。
     * agent 锁状态只跟随落盘字段 [FocusLockSettings.agentLockActive]，
     * 由用户在锁机设置页显式关闭总开关时一起清零。
     */
    fun updateSettings(settings: FocusLockSettings) {
        configuredSettings = settings
        manualLockState = if (settings.agentLockActive) true else null
        refreshLockState()
    }

    fun refreshLockState() {
        isLockActive = when {
            // 助手锁受**监督时段**门控（2026-10-05）：时段外完全休眠，时段内自动恢复。
            // 旧写法 `manualLockState ?: configuredSettings.isActiveAt()` 里那个 `?:` 短路了
            // 整条时间表 —— agent 上过锁就 24 小时接管，午饭、夜里照样弹回桌面。
            // 实测 29 次拦截里有相当一部分发生在自由时段，包括把「不做手机控」本身
            // 弹回去（想开解药反被锁机拦下）。
            manualLockState == true -> supervisionWindowActive

            // 临时黑名单"听时间戳"：它自己就能把锁抬起来，不等监督时段。
            // 否则时段外 isLockActive=false → handleWindowEvent 早退 → 临时黑名单是张废纸。
            hasActiveTemporaryBlock() -> true

            else -> configuredSettings.isActiveAt()
        }
    }

    /**
     * Grant a package a short-lived pass. Non-positive durations are rejected
     * rather than accidentally creating an already-expired exception.
     */
    fun grantTemporary(packageName: String, durationMinutes: Int): Boolean {
        if (packageName.isBlank() || durationMinutes <= 0) return false
        val expireAt = System.currentTimeMillis() + durationMinutes * 60_000L
        synchronized(stateLock) {
            temporaryWhiteList[packageName] = expireAt
        }
        return true
    }

    /**
     * 临时**黑名单**授权（2026-10-05）：从现在起 [durationMinutes] 分钟内拦掉这个包。
     *
     * 与 [grantTemporary] 的关键差别：这个**不受监督时段约束** ——
     * 授完就会经 [refreshLockState] 把锁抬起来。
     */
    fun grantTemporaryBlock(packageName: String, durationMinutes: Int): Boolean {
        if (packageName.isBlank() || durationMinutes <= 0) return false
        val expireAt = System.currentTimeMillis() + durationMinutes * 60_000L
        synchronized(stateLock) {
            temporaryBlackList[packageName] = expireAt
        }
        refreshLockState()
        persistRuntime(force = true)
        return true
    }

    fun revokeTemporaryBlock(packageName: String) {
        synchronized(stateLock) {
            temporaryBlackList.remove(packageName)
        }
        refreshLockState()
        persistRuntime(force = true)
    }

    fun revokeTemporary(packageName: String) {
        synchronized(stateLock) {
            temporaryWhiteList.remove(packageName)
        }
        persistRuntime(force = true)
    }

    /** Snapshot intended for the supervision tool, with expired entries removed. */
    fun temporaryWhiteListSnapshot(): List<String> {
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            temporaryWhiteList.entries.removeAll { it.value < now }
            return temporaryWhiteList.entries
                .sortedBy { it.key }
                .map { (packageName, expireAt) -> "$packageName=${(expireAt - now).coerceAtLeast(0L)}ms" }
        }
    }

    /**
     * 临时黑名单快照，格式与 [temporaryWhiteListSnapshot] 一致：`<包名>=<剩余毫秒>ms`。
     *
     * 用剩余时间而不是绝对时间戳：读的人是人 / agent，看「还剩多久」比
     * 看一个 epoch 好使得多。
     */
    fun temporaryBlackListSnapshot(): List<String> {
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            temporaryBlackList.entries.removeAll { it.value < now }
            return temporaryBlackList.entries
                .sortedBy { it.key }
                .map { (packageName, expireAt) -> "$packageName=${(expireAt - now).coerceAtLeast(0L)}ms" }
        }
    }

    /**
     * 实际生效状态快照。工具层直接透出，让「意图」和「生效」分开可见。
     */
    fun diagnosticsSnapshot(): Map<String, Any?> = synchronized(stateLock) {
        mapOf(
            "is_lock_active" to isLockActive,
            "lock_source" to currentLockSource().name,
            "agent_lock_state" to manualLockState,
            "schedule_window_active" to configuredSettings.isActiveAt(),
            "settings_enabled" to configuredSettings.enabled,
            "mode" to configuredSettings.mode.name,
            "supervision_window_active" to supervisionWindowActive,
            "blocked_count" to configuredSettings.blockedPackages.size,
            "temporary_block_count" to temporaryBlackListSnapshot().size,
            "effective_whitelist_count" to effectiveWhitelist().size,
            "accessibility_service_enabled" to isAccessibilityServiceEnabledNow(),
            "last_launch_package" to lastLaunchPackage,
            "last_launch_at" to lastLaunchAt,
            "last_launch_ok" to lastLaunchOk,
            "last_resolved_foreground" to lastResolvedPackage,
            "last_intercept_package" to lastInterceptPackage,
            "last_intercept_at" to lastInterceptAt,
            "intercept_count_total" to interceptCountTotal,
            "fuse_tripped_at" to lastFuseTrippedAt,
            "recent_decisions" to recentDecisions.toList(),
        )
    }

    /**
     * 当前这个包能不能用。**模式互斥**是这里的核心不变式：
     *
     * - [FocusLockMode.WHITELIST]：看白名单。黑名单（长期 + 临时）**整体不参与**。
     * - [FocusLockMode.BLACKLIST]：看黑名单。白名单（长期 + 临时）**整体不参与**。
     * - [FocusLockSettings.neverBlockPackages]：**两种模式都放行** —— 它是保险丝不是名单。
     *
     * 两套临时名单只认自己的绝对时间戳（「听时间戳」），由
     * [temporaryAllowed] / [temporaryBlocked] 顺手做过期清理。
     *
     * @return true 表示允许（不拦）。
     */
    fun isPackageAllowed(packageName: String): Boolean {
        val s = configuredSettings
        val now = System.currentTimeMillis()

        // 保险丝：先于一切名单。拦了它会把人锁死（进不去关锁的入口）
        if (packageName in s.neverBlockPackages) return true

        return when (s.mode) {
            FocusLockMode.WHITELIST ->
                temporaryAllowed(packageName, now) || packageName in effectiveWhitelist()

            FocusLockMode.BLACKLIST ->
                !(temporaryBlocked(packageName, now) || packageName in s.blockedPackages)
        }
    }

    /** 临时白名单：未到期 = true；已到期就顺手清掉（惰性清理，不用定时器）。 */
    private fun temporaryAllowed(packageName: String, now: Long): Boolean = synchronized(stateLock) {
        val expireAt = temporaryWhiteList[packageName] ?: return false
        if (now <= expireAt) return true
        temporaryWhiteList.remove(packageName)
        false
    }

    /** 临时黑名单，同上。 */
    private fun temporaryBlocked(packageName: String, now: Long): Boolean = synchronized(stateLock) {
        val expireAt = temporaryBlackList[packageName] ?: return false
        if (now <= expireAt) return true
        temporaryBlackList.remove(packageName)
        false
    }

    /** 还有没有未到期的临时黑名单条目（决定锁要不要在时段外也生效）。 */
    private fun hasActiveTemporaryBlock(): Boolean = synchronized(stateLock) {
        val now = System.currentTimeMillis()
        temporaryBlackList.entries.removeAll { it.value < now }
        temporaryBlackList.isNotEmpty()
    }

    /**
     * 处理一次窗口变化事件。
     *
     * [eventPackage] / [eventClassName] 只作为兜底线索，真正的前台包名从
     * [resolveForegroundPackage] 解析，解析不出来就**放行**（宁漏不误杀）。
     */
    fun handleWindowEvent(
        service: AccessibilityService,
        eventPackage: String,
        eventClassName: String,
    ) {
        // 先安排好 Application Context 与运行态镜像，再判锁。
        //
        // 顺序很重要：`accessibilityServiceEnabled` 这个字段在**锁没生效时同样有意义**
        // —— 用户往往正是先去系统设置里关掉无障碍，锁才不生效的。
        // 如果把它放在 `if (!isLockActive) return` 后面采集，那个字段就永远是 false，
        // 运行态镜像也就废了。
        appContext = service.applicationContext
        refreshLockState()
        persistRuntime()

        if (!isLockActive) return
        if (!configuredSettings.returnHomeOnViolation) return

        val foreground = resolveForegroundPackage(service, eventPackage, eventClassName) ?: return
        lastResolvedPackage = foreground
        // 拦截判定走完后再冲一次（仍然受 60s 节流 + 实质变化判定约束）
        persistRuntime()
        if (foreground == service.packageName) return
        if (foreground in currentImePackages(service)) return
        if (isPackageAllowed(foreground)) return
        if (!shouldIntercept(foreground)) return

        if (tripFuseIfNeeded(service, foreground)) return

        recordDecision("intercept:$foreground")
        lastInterceptPackage = foreground
        lastInterceptAt = System.currentTimeMillis()
        interceptCountTotal++
        Log.d(TAG, "focus lock intercept: $foreground (event=$eventPackage/$eventClassName)")

        // Phase 1 deliberately uses the reliable system HOME action only.
        // Overlay UI and appeal presentation belong to a later phase.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * 解析真正的前台应用包名。
     *
     * 优先取 `windows` 里 active/focused 的 `TYPE_APPLICATION` 窗口——输入法、系统弹窗、
     * Toast 都不是这个类型，天然被排除。拿不到窗口列表时退回事件包名，
     * 但必须能解析成真实 Activity 才算（否则一律放行）。
     */
    private fun resolveForegroundPackage(
        service: AccessibilityService,
        eventPackage: String,
        eventClassName: String,
    ): String? {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isNotEmpty()) {
            val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val target = appWindows.firstOrNull { it.isActive }
                ?: appWindows.firstOrNull { it.isFocused }
                ?: appWindows.maxByOrNull { it.layer }
            val fromWindow = target?.let { window ->
                runCatching { window.root?.packageName?.toString() }.getOrNull()
            }
            if (!fromWindow.isNullOrBlank()) return fromWindow
            // 有窗口列表但没有任何应用窗口：说明当前前台不是 App（纯系统层），放行。
            if (appWindows.isEmpty()) return null
        }
        return eventPackage.takeIf { it.isNotBlank() && isRealActivity(service, it, eventClassName) }
    }

    /** 事件的 package/class 能拼成一个真实 Activity 才认为是应用切换。 */
    private fun isRealActivity(context: Context, packageName: String, className: String): Boolean {
        if (className.isBlank()) return false
        return runCatching {
            context.packageManager.getActivityInfo(ComponentName(packageName, className), 0)
            true
        }.getOrDefault(false)
    }

    /** 当前启用的输入法包名（含系统默认输入法），动态放行，避免打字被弹回桌面。 */
    private fun currentImePackages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (now - imeCacheAt < IME_CACHE_MILLIS && imeCache.isNotEmpty()) return imeCache
        val resolved = runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val enabled = imm?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet().orEmpty()
            val default = AndroidSettings.Secure
                .getString(context.contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
            enabled + listOfNotNull(default)
        }.getOrDefault(emptySet())
        imeCache = resolved
        imeCacheAt = now
        return resolved
    }

    /** 同一包名短时间内只拦一次，避免连发 HOME 把系统按崩。 */
    private fun shouldIntercept(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        if (packageName == lastInterceptPackage && now - lastInterceptAt < DEBOUNCE_MILLIS) {
            return false
        }
        return true
    }

    /**
     * 熔断：[FUSE_WINDOW_MILLIS] 内拦截超过 [FUSE_MAX_INTERCEPTS] 次，
     * 说明判定逻辑跑飞了（正常使用不可能这么密），自动解除锁定并通知用户。
     *
     * @return true 表示本次已被熔断吃掉，不要再执行返回桌面。
     */
    private fun tripFuseIfNeeded(service: AccessibilityService, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val tripped = synchronized(stateLock) {
            while (recentInterceptAt.isNotEmpty() && now - recentInterceptAt.first() > FUSE_WINDOW_MILLIS) {
                recentInterceptAt.removeFirst()
            }
            recentInterceptAt.addLast(now)
            recentInterceptAt.size > FUSE_MAX_INTERCEPTS
        }
        if (!tripped) return false

        lastFuseTrippedAt = now
        recordDecision("fuse-tripped:$packageName")
        Log.w(TAG, "focus lock fuse tripped at $packageName, releasing lock")
        setLockActive(false)
        runCatching {
            me.rerere.rikkahub.data.ai.tools.local.postNotification(
                service,
                "锁机已自动解除",
                "短时间内连续拦截 ${FUSE_MAX_INTERCEPTS + 1} 次（最后一次：$packageName），" +
                    "判定逻辑可能异常，已自动放开以免设备不可用。",
            )
        }.onFailure { Log.w(TAG, "fuse notification failed", it) }
        return true
    }

    private fun resetFuseState() {
        synchronized(stateLock) {
            recentInterceptAt.clear()
        }
    }

    private fun recordDecision(entry: String) {
        synchronized(stateLock) {
            recentDecisions.addLast("${System.currentTimeMillis()}:$entry")
            while (recentDecisions.size > 20) recentDecisions.removeFirst()
        }
    }

    // ---------------- 运行态：快照与落盘（2026-10-04） ----------------

    /**
     * 记录一次设备桥拉起的结果（`POST /api/app/launch`）。
     *
     * 由 Web 路由调用。这里只做两件事：更新内存诊断 + **强制**刷快照 ——
     * 「有人刚拉了一个 App」是低频且值得立刻看到的事件，不该被 60s 节流吞掉。
     */
    fun recordAppLaunch(packageName: String, launched: Boolean) {
        lastLaunchPackage = packageName
        lastLaunchAt = System.currentTimeMillis()
        lastLaunchOk = launched
        recordDecision(if (launched) "api-launch:$packageName" else "api-launch-failed:$packageName")
        persistRuntime(force = true)
    }

    /**
     * 无障碍服务此刻是否已被系统启用。
     *
     * 比对 `ENABLED_ACCESSIBILITY_SERVICES` 里的组件名，两种 flatten 格式都认
     * （与 `AppPermissionCatalog` 同一套判定，那边是 private 的，不能复用）。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val raw = runCatching {
            AndroidSettings.Secure.getString(
                context.contentResolver,
                AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull()
        if (raw.isNullOrBlank()) return false
        val component = ComponentName(context, FocusAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return raw.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun isAccessibilityServiceEnabledNow(): Boolean =
        appContext?.let { isAccessibilityServiceEnabled(it) } ?: false

    private fun currentLockSource(): FocusLockSource = when {
        manualLockState == true -> FocusLockSource.AGENT
        configuredSettings.isActiveAt() -> FocusLockSource.SCHEDULE
        else -> FocusLockSource.OFF
    }

    /**
     * 当前运行态快照。全是「实际发生了什么」，**不掺任何配置意图**。
     * 让 `get_focus_status` 与 `focus_lock.json` 看到同一份真相。
     */
    fun runtimeSnapshot(nowMs: Long = System.currentTimeMillis()): FocusRuntimeState =
        FocusRuntimeState(
            updatedAt = nowMs,
            accessibilityServiceEnabled = isAccessibilityServiceEnabledNow(),
            lockActive = isLockActive,
            lockSource = currentLockSource(),
            lastResolvedForeground = lastResolvedPackage,
            lastInterceptPackage = lastInterceptPackage,
            lastInterceptAt = lastInterceptAt,
            interceptCountTotal = interceptCountTotal,
            fuseTrippedAt = lastFuseTrippedAt,
            lastLaunchPackage = lastLaunchPackage,
            lastLaunchAt = lastLaunchAt,
            lastLaunchOk = lastLaunchOk,
            recentDecisions = synchronized(stateLock) { recentDecisions.toList() },
            temporaryAllowedPackages = temporaryWhiteListSnapshot(),
            temporaryBlockedPackages = temporaryBlackListSnapshot(),
        )

    /**
     * 把快照交给注入方落盘。
     *
     * 两道闸，缺一不可：
     * 1. **节流** [RUNTIME_PERSIST_MIN_INTERVAL_MS] —— 窗口事件高频，不节流等于拿
     *    DataStore 当日志文件写（每次 update 都会全量重编码整个 settings）。
     * 2. **实质变化判定** —— 值没变就不落盘。判定时必须把 [FocusRuntimeState.updatedAt]
     *    与 [FocusRuntimeState.recentDecisions] 排除掉：时间戳每帧都不同、
     *    判定轨迹每拦截一次就变，留着它们第二条闸形同不存在。
     */
    private fun persistRuntime(force: Boolean = false) {
        val persister = runtimeStatePersister ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRuntimePersistAt < RUNTIME_PERSIST_MIN_INTERVAL_MS) return
        val snapshot = runtimeSnapshot(now)
        if (!force && sameRuntime(snapshot, lastPersistedRuntime)) return
        lastRuntimePersistAt = now
        lastPersistedRuntime = snapshot
        runCatching { persister(snapshot) }
            .onFailure { Log.w(TAG, "persist focus runtime failed", it) }
    }

    private fun sameRuntime(a: FocusRuntimeState, b: FocusRuntimeState?): Boolean {
        if (b == null) return false
        return a.copy(updatedAt = 0L, recentDecisions = emptyList()) ==
            b.copy(updatedAt = 0L, recentDecisions = emptyList())
    }
}
