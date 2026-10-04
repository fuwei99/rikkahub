package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable

/**
 * 按包名拉起应用的响应（2026-10-04）。
 *
 * 请求体**故意不做成 DTO**：调用方（agent / 脚本）很容易写成 `package`，
 * 而 `package` 是 Kotlin 关键字，做不成属性名。所以路由层直接读 `JsonObject`，
 * `packageName` 与 `package` 两个键都认，见 `AppRoutes`。
 */
@Serializable
data class AppLaunchResponse(
    val success: Boolean,
    val packageName: String,

    /** 装了且带 LAUNCHER Activity（= 至少知道往哪儿跳） */
    val launchable: Boolean,

    /**
     * `startActivity` 有没有把请求发出去。
     *
     * ⚠️ **true 不代表目标真的到了前台** —— Android 10+ 的后台启动限制可能静默
     * 拦掉它（连异常都不抛，只在 logcat 留一行）。确认手段是运行态：
     * `focus_lock.json` 的 `runtime.lastLaunch*`，或 `get_focus_status`。
     */
    val launched: Boolean,

    /** RikkaHub 有没有悬浮窗权限 —— 后台启动 Activity 的关键豁免条件。 */
    val canDrawOverlays: Boolean,

    /** RikkaHub 的无障碍服务此刻有没有开。锁机没用它就不生效。 */
    val accessibilityServiceEnabled: Boolean,

    /** 失败原因码（如 `no_launch_intent`）；成功为 null。 */
    val failureReason: String? = null,

    /** 人话提示：前置条件没满足时告诉调用方该去开什么权限。 */
    val hint: String? = null,
)

/** 目标包的可拉起性 + 本机前置条件，给调用方先探路用。 */
@Serializable
data class AppStatusResponse(
    val packageName: String,
    val launchable: Boolean,
    val canDrawOverlays: Boolean,
    val accessibilityServiceEnabled: Boolean,
)
