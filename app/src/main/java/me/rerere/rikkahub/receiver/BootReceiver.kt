package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import org.koin.core.context.GlobalContext

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            ScheduledNotificationManager.rescheduleAll(context)
            // Schedule Agent 闹钟同样在开机后恢复（PLAN_SCHEDULE_AGENTS §3.1）
            // 注意：Context 不实现 ComponentCallbacks，不能用 koin 的 context.get 扩展，
            // 走 GlobalContext 取单例
            runCatching { GlobalContext.get().get<me.rerere.rikkahub.data.ai.schedule.ScheduleAgentScheduler>().rescheduleAll() }
                .onFailure { Log.w("BootReceiver", "schedule agent rescheduleAll failed", it) }
        }
    }
}
