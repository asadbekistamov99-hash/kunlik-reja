package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.JarvisApplication
import com.example.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Fires task/reminder notifications and handles the "Bajarildi" notification action. */
class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as JarvisApplication).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) {
                    when (intent.action) {
                        ACTION_TASK_ALARM -> {
                            val task = container.database.taskDao().getTaskById(intent.getIntExtra(EXTRA_TASK_ID, -1))
                            if (task != null && !task.isCompleted) {
                                val msg = "Soat ${task.timeString} — ${task.description.ifBlank { "vazifa vaqti yaqinlashdi" }}"
                                NotificationHelper.showNotification(context, "⏰ ${task.title}", msg, task.id, taskId = task.id)
                                container.announce("Eslatma: ${task.title}, soat ${task.timeString} da.")
                            }
                        }
                        ACTION_REMINDER_ALARM -> {
                            val reminder = container.reminderEngine.onReminderFired(intent.getLongExtra(EXTRA_REMINDER_ID, -1))
                            if (reminder != null) {
                                NotificationHelper.showNotification(
                                    context, "⏰ ${reminder.title}", reminder.message.ifBlank { "Jarvis eslatmasi" },
                                    100_000 + reminder.id.toInt()
                                )
                                container.announce("Eslatma: ${reminder.title}")
                            }
                        }
                        ACTION_MARK_COMPLETED -> {
                            val task = container.database.taskDao().getTaskById(intent.getIntExtra(EXTRA_TASK_ID, -1))
                            if (task != null) container.taskRepository.updateTask(task.copy(isCompleted = true))
                            NotificationHelper.cancel(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, task?.id ?: 0))
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TASK_ALARM = "com.example.jarvis.ACTION_TASK_ALARM"
        const val ACTION_REMINDER_ALARM = "com.example.jarvis.ACTION_REMINDER_ALARM"
        const val ACTION_MARK_COMPLETED = "com.example.jarvis.ACTION_MARK_COMPLETED"
        const val EXTRA_TASK_ID = "EXTRA_TASK_ID"
        const val EXTRA_REMINDER_ID = "EXTRA_REMINDER_ID"
        const val EXTRA_NOTIFICATION_ID = "EXTRA_NOTIFICATION_ID"
    }
}
