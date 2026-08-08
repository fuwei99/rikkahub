package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.schedule.ScheduleAgentRunner
import me.rerere.rikkahub.data.ai.schedule.ScheduleAgentScheduler
import org.koin.android.ext.android.get

private const val TAG = "ScheduleAgentReceiver"

/**
 * Schedule Agent 闹钟触发入口（PLAN_SCHEDULE_AGENTS §3.1）。
 *
 * 先同步排下一次（与执行成败解耦：就算这次执行抛异常，下一轮照常），
 * 再把执行丢到 AppScope 上异步跑（BroadcastReceiver 只有约 10s 窗口，不能阻塞）。
 */
class ScheduleAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleAgentScheduler.ACTION_SCHEDULE_AGENT) return
        val templateId = intent.getStringExtra(ScheduleAgentScheduler.EXTRA_TEMPLATE_ID) ?: return

        // 排下一次：与执行成败解耦，闹钟永不丢
        runCatching { context.get<ScheduleAgentScheduler>().scheduleNext(templateId) }
            .onFailure { Log.e(TAG, "scheduleNext failed for $templateId", it) }

        val scope = runCatching { context.get<AppScope>() }.getOrNull() ?: return
        scope.launch(Dispatchers.Default) {
            runCatching { context.get<ScheduleAgentRunner>().run(templateId) }
                .onFailure { Log.e(TAG, "run failed for $templateId", it) }
        }
    }
}
