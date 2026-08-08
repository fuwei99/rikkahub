package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import org.koin.android.ext.android.get

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            ScheduledNotificationManager.rescheduleAll(context)
            // Schedule Agent 闹钟同样在开机后恢复（PLAN_SCHEDULE_AGENTS §3.1）
            runCatching { context.get<me.rerere.rikkahub.data.ai.schedule.ScheduleAgentScheduler>().rescheduleAll() }
                .onFailure { Log.w("BootReceiver", "schedule agent rescheduleAll failed", it) }
        }
    }
}
