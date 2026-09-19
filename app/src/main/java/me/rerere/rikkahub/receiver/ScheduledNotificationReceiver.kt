package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import me.rerere.rikkahub.data.ai.tools.local.postNotification
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.event.ToastLevel
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

private const val TAG = "ScheduledNotifReceiver"

/** 定时通知到点时浮层停留多久。定时提醒通常一两句话，12 秒够看，也不用手动关。 */
private const val TOAST_DURATION_MS = 12_000L

class ScheduledNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id == -1) return

        val title = intent.getStringExtra("title") ?: "定时提醒"
        val message = intent.getStringExtra("message") ?: ""
        val repeatRule = intent.getStringExtra("repeat")

        postNotification(context, title, message)

        // 2026-09-19: 可选的屏幕浮层。系统通知躺在通知栏里，人可能几小时不看；
        // 浮层是当场跳脸。两者不互斥 —— 通知栏负责留痕，浮层负责把人叫住。
        if (intent.getBooleanExtra("deliver_toast", false)) {
            emitToast(title, message, intent.getStringExtra("toast_level"))
        }

        ScheduledNotificationManager.handleFired(context, id, repeatRule)
    }

    private fun emitToast(title: String, message: String, rawLevel: String?) {
        runCatching {
            val bus: AppEventBus = GlobalContext.get().get()
            bus.tryEmit(
                AppEvent.ToastPending(
                    toastId = Uuid.random().toString(),
                    text = message.ifBlank { title },
                    title = title,
                    level = ToastLevel.normalize(rawLevel),
                    expireAt = System.currentTimeMillis() + TOAST_DURATION_MS,
                    source = "scheduled_notification",
                )
            )
        }.onFailure { Log.w(TAG, "emitToast failed", it) }
    }
}
