package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.notification.NotificationHelper

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getIntExtra("EXTRA_TASK_ID", 0)
        val title = intent.getStringExtra("EXTRA_TASK_TITLE") ?: "Kun tartibi eslatmasi"
        val desc = intent.getStringExtra("EXTRA_TASK_DESC") ?: ""
        val time = intent.getStringExtra("EXTRA_TASK_TIME") ?: ""

        val notificationTitle = "⏰ Eslatma: $title"
        val notificationMessage = if (time.isNotEmpty()) {
            "Vaqti: $time. $desc".trim()
        } else {
            desc.ifEmpty { "Rejalashtirilgan vazifa vaqti keldi!" }
        }

        NotificationHelper.showNotification(
            context = context,
            title = notificationTitle,
            message = notificationMessage,
            notificationId = taskId.takeIf { it != 0 } ?: (System.currentTimeMillis() % 100000).toInt()
        )
    }
}
