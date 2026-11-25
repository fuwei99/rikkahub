package me.rerere.rikkahub.focus

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * 锁机冷静期（2026-11-25）：把旧的 fail-open 熔断换成 fail-closed 的惩罚锁。
 *
 * ## 旧熔断为什么必须删
 *
 * 旧实现：10 秒内拦截超过 5 次 → `setLockActive(false)` **自动解除锁机**。
 * 本意是「判定逻辑跑飞时别把手机锁成砖」，实际效果是给了一条明路：
 * 对着被拦的 App 狂点几下，锁自己就没了 —— 监工亲手把门踹开。
 *
 * ## 现在的机制
 *
 * 1. 连续违规达到阈值 → [trigger]：立即 `lockNow()` 锁屏，进入 60 秒冷静期；
 * 2. 冷静期内监听 `ACTION_USER_PRESENT`（解锁成功）→ 立刻再 `lockNow()`；
 *    也就是「解开一次锁回一次」，反复一分钟，等于这段时间压根打不开；
 * 3. 60 秒结束自动停止回锁，锁机本身回到普通的 HOME 拦截（**绝不解除锁机**）。
 *
 * ## 诚实的边界（别指望它防住铁了心的人）
 *
 * - `lockNow()` 是**系统锁屏**，拦不住用户输自己的 PIN / 指纹；这套机制靠的是
 *   「解锁即回锁」的骚扰，不是密码学意义上的不可绕过。
 * - 设备管理权限可以在系统设置里被用户取消激活 —— 那是系统给机主的后门，
 *   任何 App 都堵不住。
 * - 冷静期状态落 SharedPreferences：进程被杀后重启，若仍在 60 秒窗口内会继续生效；
 *   超过窗口自然失效 —— **绝不会**出现「重启完还是砖」。
 * - 不申请 `resetPassword` / `wipeData` 那类真·变砖能力。
 */
object CooldownScreenLock {
    private const val TAG = "CooldownScreenLock"
    private const val PREFS = "focus_cooldown"
    private const val KEY_UNTIL = "cooldown_until"

    /** 冷静期时长：60 秒（锁一分钟冷静冷静）。 */
    const val COOLDOWN_MILLIS: Long = 60_000L

    /** 冷静期截止时刻（epoch ms）。0 = 不在冷静期。 */
    @Volatile
    private var cooldownUntil: Long = 0L

    @Volatile
    private var receiverRegistered = false

    private var appContext: Context? = null

    /** 冷静期内用户解锁 → 立刻再锁回去。 */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            if (!isActive()) return
            val ctx = context?.applicationContext ?: appContext ?: return
            Log.i(TAG, "unlocked during cooldown -> locking again")
            lockNow(ctx)
        }
    }

    /** 当前是否处于冷静期。 */
    fun isActive(now: Long = System.currentTimeMillis()): Boolean = now < cooldownUntil

    /** 冷静期剩余毫秒。 */
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long =
        (cooldownUntil - now).coerceAtLeast(0L)

    /** 冷静期截止时刻（epoch ms）；0 = 不在冷静期。 */
    fun cooldownUntilMillis(): Long = cooldownUntil

    /** 设备管理权限是否已激活。 */
    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val component = ComponentName(context, FocusDeviceAdminReceiver::class.java)
        return runCatching { dpm.isAdminActive(component) }.getOrDefault(false)
    }

    /**
     * 触发冷静期：立即锁屏并开始 60 秒回锁窗口。
     *
     * @return true = 锁屏已发出且设备管理权限有效；false = 冷静期已就位但锁屏失败
     *   （多半是没激活设备管理权限 —— 此时锁机本身仍然不解除，照常拦截）
     */
    fun trigger(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx
        ensureReceiver(ctx)

        val until = System.currentTimeMillis() + COOLDOWN_MILLIS
        cooldownUntil = until
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_UNTIL, until).apply()
        }

        val locked = lockNow(ctx)
        Log.w(TAG, "cooldown triggered: until=$until locked=$locked")
        return locked
    }

    /** 已在冷静期时只补一次锁屏，**不重置**倒计时（防止连点无限延长冷静期）。 */
    fun relock(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!isActive()) return
        lockNow(ctx)
    }

    /** 进程启动时调用：恢复未过期的冷静期 + 注册解锁监听。 */
    fun attach(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val saved = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UNTIL, 0L)
        }.getOrDefault(0L)
        if (saved > System.currentTimeMillis()) {
            cooldownUntil = saved
            Log.w(TAG, "restored cooldown until $saved")
            lockNow(ctx)
        }
        ensureReceiver(ctx)
    }

    /** 合法解锁（用户显式关闭锁机）时清除冷静期。 */
    fun cancel(context: Context? = null) {
        cooldownUntil = 0L
        val ctx = context?.applicationContext ?: appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_UNTIL, 0L).apply()
        }
    }

    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        runCatching {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            // ACTION_USER_PRESENT 是 protected broadcast，第三方伪造不了；
            // Android 14+ 动态注册必须显式给 flag，系统广播用 EXPORTED。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(unlockReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { Log.w(TAG, "register USER_PRESENT receiver failed", it) }
    }

    private fun lockNow(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val component = ComponentName(context, FocusDeviceAdminReceiver::class.java)
        if (!runCatching { dpm.isAdminActive(component) }.getOrDefault(false)) return false
        return runCatching {
            dpm.lockNow()
            true
        }.onFailure { Log.w(TAG, "lockNow failed", it) }.getOrDefault(false)
    }
}
