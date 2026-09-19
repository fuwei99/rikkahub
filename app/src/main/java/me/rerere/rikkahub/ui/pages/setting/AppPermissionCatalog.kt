package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.focus.FocusAccessibilityService

/** 权限当前状态 */
enum class PermStatus {
    GRANTED,
    DENIED,
    /** 系统没有可查询的接口，得跳过去肉眼看 */
    UNKNOWN,
    /** 本机/本系统版本没有这项 */
    NOT_APPLICABLE,
}

/** 点击一行时的行为 */
sealed interface PermAction {
    /** 直接弹系统运行时授权框，比跳设置页体验好 */
    data class Runtime(val permission: String) : PermAction

    /** 跳到系统/厂商设置页；[build] 返回 null 时由调用方兜底到应用详情页 */
    data class Jump(val build: (Context) -> Intent?) : PermAction

    /** 纯说明项，不可点 */
    data object None : PermAction
}

/** 权限清单里的一行 */
data class PermissionEntry(
    val id: String,
    val group: String,
    val title: String,
    val desc: String,
    val status: PermStatus,
    val statusText: String,
    val action: PermAction,
)

/**
 * Rikkahub 需要的权限总目录。
 *
 * 为什么要有这个：这些权限散落在系统设置的七八个不同页面里（应用详情、特殊应用权限、
 * 无障碍、使用情况访问、电池优化、MIUI 的「其他权限」…），每次都得自己到处翻。
 * 这里把它们收在一页，点一下直接跳过去。
 *
 * ## 状态查询的坑
 *
 * - 运行时权限（相机/录音/定位/通知）有标准 API，能准确判断
 * - 特殊权限（悬浮窗/使用情况访问/无障碍/电池优化/精确闹钟）也各有 API
 * - **厂商私有权限**（后台弹出界面、锁屏显示、链式启动、获取应用列表）没有公开接口，
 *   只能标 [PermStatus.UNKNOWN]，跳过去自己看
 * - 剪贴板在 Android 10+ 压根不是权限，前台随便用、后台被禁，标为无需授权
 */
object AppPermissionCatalog {

    const val GROUP_BASIC = "基础权限"
    const val GROUP_SPECIAL = "特殊权限"
    const val GROUP_BACKGROUND = "后台与自启"

    fun build(context: Context): List<PermissionEntry> = buildList {
        // ---------- 基础权限 ----------
        add(notificationEntry(context))
        add(runtimeEntry(context, "camera", GROUP_BASIC, "相机", "扫二维码、拍照输入", Manifest.permission.CAMERA))
        add(runtimeEntry(context, "mic", GROUP_BASIC, "录音", "语音输入与语音消息", Manifest.permission.RECORD_AUDIO))
        add(runtimeEntry(context, "location", GROUP_BASIC, "定位", "给模型提供位置上下文", Manifest.permission.ACCESS_FINE_LOCATION))
        add(storageEntry(context))
        add(
            PermissionEntry(
                id = "clipboard_read",
                group = GROUP_BASIC,
                title = "读取剪贴板",
                desc = "Android 10 起剪贴板不是权限：前台应用可直接读，后台读取被系统禁止，无法授权",
                status = PermStatus.NOT_APPLICABLE,
                statusText = "无需授权",
                action = PermAction.None,
            )
        )
        add(
            PermissionEntry(
                id = "clipboard_write",
                group = GROUP_BASIC,
                title = "写入剪贴板",
                desc = "写剪贴板同样无需授权，任何应用都能写",
                status = PermStatus.NOT_APPLICABLE,
                statusText = "无需授权",
                action = PermAction.None,
            )
        )

        // ---------- 特殊权限 ----------
        add(overlayEntry(context))
        add(vendorEntry("bg_popup", GROUP_SPECIAL, "后台弹出界面", "MIUI/HyperOS 私有权限，控制后台能否拉起界面（监工弹窗要用）"))
        add(usageStatsEntry(context))
        add(accessibilityEntry(context))
        add(vendorEntry("lock_screen", GROUP_SPECIAL, "锁屏显示", "MIUI/HyperOS 私有权限，允许在锁屏上显示内容"))
        add(
            PermissionEntry(
                id = "device_admin",
                group = GROUP_SPECIAL,
                title = "设备管理权限",
                desc = "用于更强的锁定能力。本应用尚未声明设备管理员组件，需先在代码里加 DeviceAdminReceiver 才能真正授权",
                status = PermStatus.NOT_APPLICABLE,
                statusText = "暂不可授权",
                action = PermAction.Jump { Intent(Settings.ACTION_SECURITY_SETTINGS) },
            )
        )
        add(vendorEntry("pkg_list", GROUP_SPECIAL, "获取应用列表", "读取已安装应用清单。AOSP 靠 manifest 声明，MIUI 另有开关"))

        // ---------- 后台与自启 ----------
        add(
            PermissionEntry(
                id = "autostart",
                group = GROUP_BACKGROUND,
                title = "链式启动 / 自启动",
                desc = "MIUI/HyperOS 私有权限。不开的话被其他应用拉起时会被拦，后台同步容易断",
                status = PermStatus.UNKNOWN,
                statusText = "需手动确认",
                action = PermAction.Jump { miuiAutoStart() },
            )
        )
        add(batteryEntry(context))
        add(exactAlarmEntry(context))
    }

    // ---------------------------------------------------------------- 基础

    private fun notificationEntry(context: Context): PermissionEntry {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionEntry(
                "notification", GROUP_BASIC, "通知",
                "Android 13 以下通知默认开启，无需授权",
                PermStatus.NOT_APPLICABLE, "无需授权", PermAction.None,
            )
        }
        val ok = isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        return PermissionEntry(
            "notification", GROUP_BASIC, "通知",
            "同步结果、定时提醒、监工提醒都靠它送达",
            ok.toStatus(), ok.toStatusText(),
            PermAction.Runtime(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun runtimeEntry(
        context: Context,
        id: String,
        group: String,
        title: String,
        desc: String,
        permission: String,
    ): PermissionEntry {
        val ok = isGranted(context, permission)
        return PermissionEntry(id, group, title, desc, ok.toStatus(), ok.toStatusText(), PermAction.Runtime(permission))
    }

    private fun storageEntry(context: Context): PermissionEntry {
        // Android 11+：走「所有文件访问」，是个特殊权限，不是运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = Environment.isExternalStorageManager()
            return PermissionEntry(
                "storage", GROUP_BASIC, "文件与存储",
                "读写本地文件（导入导出、附件、Obsidian 目录）",
                ok.toStatus(), ok.toStatusText(),
                PermAction.Jump { ctx ->
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    )
                },
            )
        }
        @Suppress("DEPRECATION")
        val ok = isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        @Suppress("DEPRECATION")
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        return PermissionEntry(
            "storage", GROUP_BASIC, "文件与存储",
            "读写本地文件（导入导出、附件）",
            ok.toStatus(), ok.toStatusText(),
            PermAction.Runtime(perm),
        )
    }

    // ---------------------------------------------------------------- 特殊

    private fun overlayEntry(context: Context): PermissionEntry {
        val ok = Settings.canDrawOverlays(context)
        return PermissionEntry(
            "overlay", GROUP_SPECIAL, "悬浮窗",
            "在其他应用上层显示（监工弹窗、浮层提示）",
            ok.toStatus(), ok.toStatusText(),
            PermAction.Jump { ctx ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            },
        )
    }

    private fun usageStatsEntry(context: Context): PermissionEntry {
        val ok = hasUsageStatsAccess(context)
        return PermissionEntry(
            "usage_stats", GROUP_SPECIAL, "获取屏幕时间",
            "读取使用情况统计（屏幕时间采集与跨设备同步的数据源）",
            ok.toStatus(), ok.toStatusText(),
            PermAction.Jump { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
        )
    }

    private fun accessibilityEntry(context: Context): PermissionEntry {
        val ok = isAccessibilityServiceEnabled(context)
        return PermissionEntry(
            "accessibility", GROUP_SPECIAL, "辅助服务",
            "Rikkahub 的无障碍服务，监工锁与 HOME 拦截依赖它",
            ok.toStatus(), ok.toStatusText(),
            PermAction.Jump { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        )
    }

    private fun vendorEntry(id: String, group: String, title: String, desc: String): PermissionEntry =
        PermissionEntry(
            id, group, title, desc,
            PermStatus.UNKNOWN, "需手动确认",
            PermAction.Jump { miuiPermissionEditor(it) },
        )

    // ---------------------------------------------------------------- 后台

    private fun batteryEntry(context: Context): PermissionEntry {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ok = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        return PermissionEntry(
            "battery", GROUP_BACKGROUND, "电池优化白名单",
            "加入白名单后系统才不会在息屏时掐掉后台同步与采集",
            ok.toStatus(), ok.toStatusText(),
            PermAction.Jump { ctx ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            },
        )
    }

    private fun exactAlarmEntry(context: Context): PermissionEntry {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return PermissionEntry(
                "exact_alarm", GROUP_BACKGROUND, "精确闹钟",
                "Android 12 以下精确闹钟无需单独授权",
                PermStatus.NOT_APPLICABLE, "无需授权", PermAction.None,
            )
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val ok = am?.canScheduleExactAlarms() == true
        return PermissionEntry(
            "exact_alarm", GROUP_BACKGROUND, "精确闹钟",
            "到点准时触发（定时提醒、定时同步）。关掉后系统会把闹钟推迟到它高兴的时候",
            ok.toStatus(), ok.toStatusText(),
            PermAction.Jump { ctx ->
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
            },
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun Boolean.toStatus(): PermStatus = if (this) PermStatus.GRANTED else PermStatus.DENIED

    private fun Boolean.toStatusText(): String = if (this) "已授权" else "未授权"

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 使用情况访问权限没有 checkSelfPermission，只能查 AppOps */
    private fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 比对 ENABLED_ACCESSIBILITY_SERVICES 里的组件名，两种 flatten 格式都认 */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val component = ComponentName(context, FocusAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    /** 应用详情页：所有跳转失败时的兜底落点 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /**
     * MIUI/HyperOS 的「应用权限管理」页（含后台弹出界面、锁屏显示、获取应用列表等私有开关）。
     * 非 MIUI 上这个组件不存在，startActivity 会抛，调用方需兜底到应用详情页。
     */
    private fun miuiPermissionEditor(context: Context): Intent =
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            )
            putExtra("extra_pkgname", context.packageName)
        }

    /** MIUI/HyperOS 的自启动管理页 */
    private fun miuiAutoStart(): Intent =
        Intent().apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        }
}
