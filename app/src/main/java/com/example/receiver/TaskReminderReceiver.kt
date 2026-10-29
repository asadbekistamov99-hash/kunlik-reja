package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.JarvisApplication
import com.jarvis.alarm.AlarmPlaybackService
import com.example.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                                AlarmPlaybackService.ring(context, task.id, task.title, msg, taskId = task.id)
                            }
                        }
                        ACTION_REMINDER_ALARM -> {
                            val reminder = container.reminderEngine.onReminderFired(intent.getLongExtra(EXTRA_REMINDER_ID, -1))
                            if (reminder != null) {
                                val exactTime = DateTimeFormatter.ofPattern("dd MMMM, HH:mm").format(Instant.ofEpochMilli(reminder.triggerAtMillis).atZone(ZoneId.systemDefault()))
                                AlarmPlaybackService.ring(context, 100_000 + reminder.id.toInt(), reminder.title,
                                    "Belgilangan vaqt: $exactTime. ${reminder.message}")
                            }
                        }
                        ACTION_ALARM_DISMISS, ACTION_ALARM_SNOOZE -> {
                            val id = intent.getIntExtra(AlarmPlaybackService.EXTRA_ID, 0)
                            AlarmPlaybackService.stop(context, id)
                            if (intent.action == ACTION_ALARM_SNOOZE) {
                                container.reminderEngine.snooze(
                                    intent.getStringExtra(AlarmPlaybackService.EXTRA_TITLE) ?: "Eslatma",
                                    "5 daqiqaga qoldirilgan eslatma")
                            }
                        }
                        ACTION_MARK_COMPLETED -> {
                            if (intent.hasExtra(AlarmPlaybackService.EXTRA_ID)) {
                                AlarmPlaybackService.stop(context, intent.getIntExtra(AlarmPlaybackService.EXTRA_ID, 0))
                            }
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
        const val ACTION_ALARM_DISMISS = "com.jarvis.ALARM_DISMISS"
        const val ACTION_ALARM_SNOOZE = "com.jarvis.ALARM_SNOOZE"
        const val ACTION_TASK_ALARM = "com.jarvis.ACTION_TASK_ALARM"
        const val ACTION_REMINDER_ALARM = "com.jarvis.ACTION_REMINDER_ALARM"
        const val ACTION_MARK_COMPLETED = "com.jarvis.ACTION_MARK_COMPLETED"
        const val EXTRA_TASK_ID = "EXTRA_TASK_ID"
        const val EXTRA_REMINDER_ID = "EXTRA_REMINDER_ID"
        const val EXTRA_NOTIFICATION_ID = "EXTRA_NOTIFICATION_ID"
    }
}
