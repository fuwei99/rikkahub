package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import me.rerere.rikkahub.data.ai.tools.local.postNotification

class ScheduledNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val title = intent.getStringExtra("title") ?: "定时提醒"
        val message = intent.getStringExtra("message") ?: ""
        val repeatRule = intent.getStringExtra("repeat")

        if (id != -1) {
            postNotification(context, title, message)
            ScheduledNotificationManager.handleFired(context, id, repeatRule)
        }
    }
}
