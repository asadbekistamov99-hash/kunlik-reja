package com.example.jarvis.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.Reminder
import com.example.data.ReminderDao
import com.example.data.Task
import com.example.data.TaskDao
import com.example.receiver.TaskReminderReceiver

/**
 * Schedules task and standalone reminders with AlarmManager. Exact alarms are used when the user
 * granted them (Android 12+), otherwise an inexact-while-idle alarm keeps reminders working in
 * Doze and battery saver at the cost of a few minutes' drift.
 */
class ReminderEngine(
    private val context: Context,
    private val reminderDao: ReminderDao,
    private val taskDao: TaskDao,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val alarmManager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    suspend fun addReminder(reminder: Reminder): Reminder {
        val id = reminderDao.insert(reminder)
        val saved = reminder.copy(id = id)
        schedule(saved)
        return saved
    }

    suspend fun cancelReminder(id: Long) {
        cancel(reminderIntent(id))
        reminderDao.delete(id)
    }

    fun schedule(reminder: Reminder) {
        if (!reminder.isActive) return
        val trigger = nextTrigger(reminder, clock()) ?: return
        setAlarm(trigger, reminderIntent(reminder.id))
    }

    fun scheduleTask(task: Task) {
        val trigger = taskTrigger(task)
        if (trigger == null || task.isCompleted || !task.hasReminder || trigger <= clock()) {
            cancelTask(task.id)
            return
        }
        setAlarm(trigger, taskIntent(task.id))
    }

    fun cancelTask(taskId: Int) = cancel(taskIntent(taskId))

    /** Called when a reminder fired: re-arms repeating ones, deactivates one-shots. */
    suspend fun onReminderFired(id: Long): Reminder? {
        val reminder = reminderDao.getById(id) ?: return null
        if (reminder.repeatIntervalMinutes > 0) {
            val next = nextTrigger(reminder, clock() + 1000) ?: return reminder
            val updated = reminder.copy(triggerAtMillis = next)
            reminderDao.update(updated)
            schedule(updated)
        } else {
            reminderDao.update(reminder.copy(isActive = false))
        }
        return reminder
    }

    /** Re-arms everything; used after reboot, app update, time/zone change and exact-alarm grant. */
    suspend fun rescheduleAll(): Int {
        var count = 0
        reminderDao.getActive().forEach { r ->
            if (nextTrigger(r, clock()) == null) reminderDao.update(r.copy(isActive = false))
            else { schedule(r); count++ }
        }
        taskDao.getUpcomingWithReminder(clock()).forEach { scheduleTask(it); count++ }
        Log.i(TAG, "Rescheduled $count alarms")
        return count
    }

    private fun setAlarm(triggerAt: Long, pi: PendingIntent) {
        val am = alarmManager ?: return
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancel(pi: PendingIntent) {
        alarmManager?.cancel(pi)
        pi.cancel()
    }

    private fun taskIntent(taskId: Int): PendingIntent = PendingIntent.getBroadcast(
        context, taskId,
        Intent(context, TaskReminderReceiver::class.java).setAction(TaskReminderReceiver.ACTION_TASK_ALARM)
            .putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun reminderIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, TaskReminderReceiver::class.java).setAction(TaskReminderReceiver.ACTION_REMINDER_ALARM)
            .putExtra(TaskReminderReceiver.EXTRA_REMINDER_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val TAG = "ReminderEngine"

        fun taskTrigger(task: Task): Long? =
            if (!task.hasReminder) null else task.timestampMillis - task.reminderMinutesBefore * 60_000L

        /**
         * Next firing time strictly after [now]. One-shot reminders in the past return null;
         * repeating ones advance by whole intervals so a missed period is skipped, not replayed.
         */
        fun nextTrigger(reminder: Reminder, now: Long): Long? {
            if (!reminder.isActive) return null
            if (reminder.triggerAtMillis > now) return reminder.triggerAtMillis
            if (reminder.repeatIntervalMinutes <= 0) return null
            val interval = reminder.repeatIntervalMinutes * 60_000L
            val periods = (now - reminder.triggerAtMillis) / interval + 1
            return reminder.triggerAtMillis + periods * interval
        }
    }
}
