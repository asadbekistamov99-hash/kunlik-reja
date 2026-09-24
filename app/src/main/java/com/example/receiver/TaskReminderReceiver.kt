package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.AppDatabase
import com.example.jarvis.FocusSession
import com.example.notification.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).taskDao()
                if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) {
                    dao.getAllTasks().first().filter { it.hasReminder && !it.isCompleted }.forEach { NotificationHelper.scheduleTaskAlarm(context, it) }
                    if(FocusSession.running(context)) FocusSession.resume(context)
                } else {
                    val id = intent.getIntExtra("EXTRA_TASK_ID", 0)
                    if(FocusSession.isFocusAlarm(id)) {
                        if(!FocusSession.due(context)) return@launch
                        FocusSession.stop(context)
                        NotificationHelper.showNotification(context, "Fokus yakunlandi", "Tanaffus qiling. Bajarilgan vazifani ilovada belgilang.", id)
                    } else {
                        val task = dao.getTaskById(id)
                        if(task != null && task.hasReminder && !task.isCompleted) {
                            val expected = task.timestampMillis - task.reminderMinutesBefore * 60_000L
                            if(expected <= System.currentTimeMillis() + 1000)
                                NotificationHelper.showNotification(context, "Eslatma: ${task.title}", "${task.timeString} · ${task.description}", task.id)
                        }
                    }
                }
            } catch(e: Exception) { android.util.Log.e("TaskReminder", "Reminder could not be delivered", e) }
            finally { pending.finish() }
        }
    }
}
