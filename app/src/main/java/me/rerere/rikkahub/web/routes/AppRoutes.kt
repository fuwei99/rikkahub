package me.rerere.rikkahub.web.routes

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.focus.FocusAppLauncher
import me.rerere.rikkahub.focus.FocusPolicyEngine
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.dto.AppLaunchResponse
import me.rerere.rikkahub.web.dto.AppStatusResponse

/**
 * 应用拉起接口（2026-10-04）。
 *
 * ## 为什么要有它
 *
 * 用户会主动把「不做手机控」`com.pl.getaway.getaway` 在设置里**强行停止**，
 * 停完就是一上午的摸鱼。系统的强停会给包打上 `FLAG_STOPPED`：隐式广播收不到、
 * 闹钟与 Job 全清、自启动彻底失效 —— **只有显式 Activity 启动能解冻它**。
 *
 * 早期实现是一条 Worker 自续链，每 N 分钟无脑拉一次。拆掉的三个理由：
 *
 * 1. 固定时刻表 = 硬编码。「多久拉一次」不该由代码替你决定；
 * 2. 每拉一次都会把目标弹到前台，定时 = 定时在你脸上闪一下；
 * 3. 真正需要它的是**事件**：查岗 agent 发现你在摸鱼、或你自己意识到要玩手机了。
 *
 * 所以改成接口：谁来调、什么时候调、拉哪个包，全交给调用方（查岗 agent /
 * workspace shell / 自己的脚本）。这是「能力」而不是「策略」。
 *
 * ## 鉴权
 *
 * 走**设备桥独立 Bearer**（[requireDeviceBridgeToken]，与 `/api/shell*`、
 * `/api/notify*`、`/api/tools*` 共用 `shellBridgeToken`，设备本地 JSON、不上云）。
 * 刻意**不看** `shellBridgeEnabled`：那个开关是「要不要把 shell(2000) 交出去」
 * 的安全闸；拉个 App 不提权，是两件事（见 DeviceBridgeAuth 注释）。
 *
 * ## 端点
 *
 * - `GET  /api/app/status?packageName=com.x.y` → 可拉起性 + 本机前置条件
 * - `POST /api/app/launch` → `{"packageName":"com.x.y"}`（`package` 也认）
 *
 * ## 安全边界
 *
 * 这个接口能把这个设备上的**任意应用弹到前台**。威力不小但比不上 `/api/shell`
 * （那个能改系统设置）。仍建议配合 web server 的「仅本机模式」，token 泄露立刻换。
 */
fun Route.appRoutes(
    context: Context,
    advancedConfigStore: SyncAdvancedConfigStore,
) {
    route("/app") {
        get("/status") {
            call.requireDeviceBridgeToken(advancedConfigStore, "app")

            val packageName = (call.request.queryParameters["packageName"]
                ?: call.request.queryParameters["package"])
                ?.trim()
                .orEmpty()

            call.respond(
                HttpStatusCode.OK,
                AppStatusResponse(
                    packageName = packageName,
                    launchable = FocusAppLauncher.isLaunchable(context, packageName),
                    canDrawOverlays = FocusAppLauncher.canDrawOverlays(context),
                    accessibilityServiceEnabled = FocusPolicyEngine.isAccessibilityServiceEnabled(context),
                ),
            )
        }

        post("/launch") {
            call.requireDeviceBridgeToken(advancedConfigStore, "app")

            // 不做 DTO：`package` 是 Kotlin 关键字，做不成属性名。
            // 两个键都认，省掉调用方「字段名写错但 200 成功」的坑。
            val body = call.receive<JsonObject>()
            val packageName = (body["packageName"]?.jsonPrimitive?.contentOrNull
                ?: body["package"]?.jsonPrimitive?.contentOrNull)
                ?.trim()
                .orEmpty()

            if (packageName.isEmpty()) {
                throw BadRequestException("packageName is required（也可以写 package）")
            }

            // startActivity 走主线程更稳：非 UI 上下文起 Activity 本身允许，
            // 但没必要在 Ktor 的 IO 线程上冒这个险。
            val outcome = withContext(Dispatchers.Main) {
                FocusAppLauncher.launch(context, packageName)
            }

            // 记进运行态：会出现在 focus_lock.json 的 runtime.lastLaunch* 里
            FocusPolicyEngine.recordAppLaunch(packageName, outcome.launched)

            val canDrawOverlays = FocusAppLauncher.canDrawOverlays(context)
            call.respond(
                HttpStatusCode.OK,
                AppLaunchResponse(
                    success = outcome.launched,
                    packageName = outcome.packageName,
                    launchable = outcome.launchable,
                    launched = outcome.launched,
                    canDrawOverlays = canDrawOverlays,
                    accessibilityServiceEnabled =
                        FocusPolicyEngine.isAccessibilityServiceEnabled(context),
                    failureReason = outcome.failureReason,
                    hint = buildHint(outcome.launchable, outcome.launched, canDrawOverlays),
                ),
            )
        }
    }
}

/**
 * 失败时给一句能直接照做的人话。
 *
 * 后台启动 Activity 是这套里最容易踩的一环：Android 10+ 默认禁止，
 * 豁免靠 `SYSTEM_ALERT_WINDOW`；MIUI/HyperOS 还有一道自家闸门（「后台弹出界面」），
 * 而 AOSP 的悬浮窗权限在 MIUI 上**不自动等价于**它。所以两句都得提。
 */
private fun buildHint(launchable: Boolean, launched: Boolean, canDrawOverlays: Boolean): String? = when {
    !launchable -> "目标包没装，或者没有 LAUNCHER Activity（用 GET /api/app/status 先探）"
    launched && !canDrawOverlays ->
        "请求已发出，但 RikkaHub 没有悬浮窗权限，可能被 Android 10+ 的后台启动限制静默拦下。" +
            "去系统设置给 RikkaHub 开「显示在其他应用上层 / 后台弹出界面」（MIUI 还要单独开「后台弹出界面」）。"
    !launched ->
        "startActivity 抛异常了，多半是后台启动 Activity 被拦：给 RikkaHub 开悬浮窗 / 后台弹出界面权限。"
    else -> null
}
