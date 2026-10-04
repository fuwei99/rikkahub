package me.rerere.rikkahub.focus

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.util.Log

private const val TAG = "FocusAppLauncher"

/**
 * 一次拉起尝试的结果。
 *
 * 刻意把「能不能拉」([launchable]) 与「拉没拉成」([launched]) 分开报：
 * 前者查的是有没有 LAUNCHER Activity，后者是 `startActivity` 有没有炸。
 * 两者都为 true 也**不代表目标真的跑到前台了** —— 见 [FocusAppLauncher.launch] 注释。
 */
data class AppLaunchOutcome(
    val packageName: String,
    val launchable: Boolean,
    val launched: Boolean,
    val failureReason: String? = null,
)

/**
 * 按包名拉起一个应用（2026-10-04）。
 *
 * ## 它为什么存在
 *
 * 用户会主动去「设置 → 应用管理 → 不做手机控 → 强行停止」把它掐死，掐死之后
 * 就是一上午的摸鱼。系统的强停会给包打上 `FLAG_STOPPED`：隐式广播收不到、
 * AlarmManager / JobScheduler 全清、自启动彻底失效。**唯一能解冻它的手段是显式
 * Activity 启动** —— 也就是为什么手点桌面图标还能打开它。
 *
 * 所以本类只做一件事：`startActivity(launcherIntent)`。
 *
 * ## 为什么不做定时器
 *
 * 早期版本做了一条 Worker 自续链（每 N 分钟拉一次）。砍掉的理由：
 *
 * 1. 时刻表一写死就是硬编码，「多久拉一次」不该由代码替你决定；
 * 2. 每拉一次都会把目标弹到前台 —— 定时 = 定时在你脸上闪一下；
 * 3. 真正需要它的时候是「查岗 agent 发现你在摸鱼」，那是**事件驱动**的，
 *    不是「每隔 10 分钟」这种固定节拍。
 *
 * 于是改成暴露成设备桥接口（`POST /api/app/launch`），谁来调、什么时候调，
 * 由 agent / workspace shell / 外部脚本自己决定。
 *
 * ## 硬前提（做不到就是拉不起来）
 *
 * Android 10+ 禁止后台启动 Activity。豁免条件之一是应用持有
 * `SYSTEM_ALERT_WINDOW`（即「显示在其他应用上层 / 后台弹出界面」）。
 * RikkaHub 已在 manifest 声明该权限，但**用户必须在系统设置里真正授予**；
 * MIUI/HyperOS 另有一道自家闸门。`canDrawOverlays` 就是用来暴露这个前置条件的。
 *
 * ## ⚠️ 成功的定义
 *
 * `startActivity` 不抛异常 **≠** 目标到了前台。被后台启动限制静默拦下时，
 * 可能连异常都没有（只在 logcat 留一行）。所以本类的返回值只能说明
 * 「请求发出去了」，真正确认要靠 `get_focus_status` / `focus_lock.json` 里的运行态。
 */
object FocusAppLauncher {

    /** 该包是否装了且带 LAUNCHER Activity。查不到返回 false。 */
    fun isLaunchable(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }

    /** 是否持有悬浮窗权限 —— 后台启动 Activity 的关键豁免条件。 */
    fun canDrawOverlays(context: Context): Boolean =
        runCatching { AndroidSettings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * 显式拉起 [packageName]。
     *
     * 从后台 / 非 UI 上下文启动 Activity **必须**带 `FLAG_ACTIVITY_NEW_TASK`。
     * 调用方若在非主线程，自己 `withContext(Dispatchers.Main)` 包一层。
     */
    fun launch(context: Context, packageName: String): AppLaunchOutcome {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            return AppLaunchOutcome(pkg, launchable = false, launched = false, failureReason = "empty_package_name")
        }

        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent == null) {
            Log.w(TAG, "no launch intent for $pkg (未安装 / 没有 LAUNCHER Activity)")
            return AppLaunchOutcome(pkg, launchable = false, launched = false, failureReason = "no_launch_intent")
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val failure = runCatching { context.startActivity(intent) }.exceptionOrNull()
        if (failure != null) {
            Log.w(TAG, "startActivity failed for $pkg", failure)
            return AppLaunchOutcome(
                packageName = pkg,
                launchable = true,
                launched = false,
                failureReason = failure.javaClass.simpleName,
            )
        }

        Log.i(TAG, "launched $pkg")
        return AppLaunchOutcome(pkg, launchable = true, launched = true)
    }
}
