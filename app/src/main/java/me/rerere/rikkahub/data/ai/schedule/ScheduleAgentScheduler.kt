package me.rerere.rikkahub.data.ai.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.receiver.ScheduleAgentReceiver

private const val TAG = "ScheduleAgentScheduler"

/**
 * Schedule Agent 定时触发（PLAN_SCHEDULE_AGENTS §3.1）。
 *
 * 照抄 [me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager] 的调度骨架：
 * 每个启用的模板一条 `setExactAndAllowWhileIdle` 的 PendingIntent；
 * 触发时刻由 [ScheduleTimePlanner] 算：`windows`（时间段 + 段内周期，格子对齐窗口 start）
 * 与 `dailyTimes`（每天多个固定时刻）取最近者；都没配才回落 `intervalMinutes` 周期；
 * 进程死掉靠 `BOOT_COMPLETED` + [rescheduleAll] 恢复（manifest 已有 RECEIVE_BOOT_COMPLETED）。
 *
 * 触发后由 [ScheduleAgentReceiver] 转 [ScheduleAgentRunner] 执行，
 * 执行完（或跳过）由 [scheduleNext] 排下一次。
 */
class ScheduleAgentScheduler(
    private val context: Context,
    private val manager: ScheduleAgentManager,
) {
    companion object {
        const val ACTION_SCHEDULE_AGENT = "me.rerere.rikkahub.ACTION_SCHEDULE_AGENT"
        const val EXTRA_TEMPLATE_ID = "templateId"
    }

    /** 全部启用的模板重新排程（启动 / 开关变化 / 配置改动后调用）。 */
    fun rescheduleAll() {
        cancelAll()
        manager.listTemplates(includeDisabled = true)
            .filter { it.enabled }
            .forEach { schedule(it) }
    }

    /** 某个模板触发后 / 状态变化后排下一次；模板不存在或已禁用则取消闹钟。 */
    fun scheduleNext(templateId: String) {
        val template = manager.getTemplate(templateId) ?: return
        if (!template.enabled) {
            cancel(template.id)
            return
        }
        schedule(template)
    }

    fun cancelAll() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.listTemplates(includeDisabled = true).forEach { t ->
            alarmManager.cancel(pendingIntentOf(t.id))
        }
    }

    private fun schedule(template: ScheduleAgentTemplate) {
        if (!template.enabled) return
        val fire = ScheduleTimePlanner.nextFire(template)
        val fireAt = fire.atMillis
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleAgentReceiver::class.java).apply {
            action = ACTION_SCHEDULE_AGENT
            putExtra(EXTRA_TEMPLATE_ID, template.id)
        }
        val pendingIntent = pendingIntentOf(template.id, intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
        }
        val label = listOfNotNull(
            fire.windowName.takeIf { it.isNotBlank() },
            fire.tag.takeIf { it.isNotBlank() },
        ).joinToString("/").ifBlank { "interval" }
        Log.i(TAG, "scheduled ${template.id} at ${java.time.Instant.ofEpochMilli(fireAt)} ($label)")
    }

    private fun cancel(templateId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentOf(templateId))
    }

    private fun pendingIntentOf(
        templateId: String,
        intent: Intent = Intent(context, ScheduleAgentReceiver::class.java).apply {
            action = ACTION_SCHEDULE_AGENT
            putExtra(EXTRA_TEMPLATE_ID, templateId)
        },
    ): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // 模板 id 稳定 → hashCode 作 requestCode 稳定可替换
        return PendingIntent.getBroadcast(context, templateId.hashCode(), intent, flags)
    }

}
