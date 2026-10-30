package com.jarvis.automation

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

    suspend fun rescheduleReminder(reminder: Reminder, triggerAtMillis: Long): Reminder {
        require(triggerAtMillis > clock()) { "Eslatma vaqti o'tib ketgan" }
        val updated = reminder.copy(triggerAtMillis = triggerAtMillis, firstTriggerAtMillis = triggerAtMillis, isActive = true)
        reminderDao.update(updated)
        // The same PendingIntent replaces the old alarm only after the database write succeeds.
        schedule(updated)
        return updated
    }

    /** A separate occurrence keeps a daily reminder's next scheduled day intact. */
    suspend fun snooze(title: String, message: String): Reminder = addReminder(
        Reminder(title = title, message = message, triggerAtMillis = clock() + 5 * 60_000L)
    )

    suspend fun allReminders(): List<Reminder> = reminderDao.getAll().sortedBy { it.triggerAtMillis }
    suspend fun onDate(date: java.time.LocalDate, zone: java.time.ZoneId): List<Reminder> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return allReminders().flatMap { reminder ->
            if (reminder.repeatIntervalMinutes <= 0 || !reminder.isActive) {
                listOfNotNull(reminder.takeIf { it.triggerAtMillis in start until end })
            } else {
                val interval = reminder.repeatIntervalMinutes * 60_000L
                val anchor = reminder.firstTriggerAtMillis.takeIf { it > 0 } ?: reminder.triggerAtMillis
                val offset = if (anchor >= start) 0 else (start - anchor - 1) / interval + 1
                generateSequence(anchor + offset * interval) { it + interval }.takeWhile { it < end }
                    .map { reminder.copy(triggerAtMillis = it) }.toList()
            }
        }.sortedWith(compareBy<Reminder> { it.triggerAtMillis }.thenBy { it.id })
    }

    suspend fun getReminder(id: Long): Reminder? = reminderDao.getById(id)

    suspend fun renameReminder(reminder: Reminder, title: String): Reminder {
        require(title.isNotBlank()) { "Eslatma nomi bo'sh" }
        val updated = reminder.copy(title = title.trim())
        reminderDao.update(updated)
        return updated
    }

    suspend fun cancelReminder(id: Long) {
        cancel(reminderIntent(id))
        reminderDao.delete(id)
    }

    /** Returns active reminders ordered by their next trigger time. */
    suspend fun activeReminders(): List<Reminder> = reminderDao.getActive()

    /** Returns active reminders whose title contains all words from the spoken query. */
    suspend fun findActive(query: String): List<Reminder> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return reminderDao.getActive().filter { reminder ->
            val title = normalize(reminder.title)
            words.all { it in title }
        }
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
        val reminder = reminderDao.getById(id)?.takeIf { it.isActive } ?: return null
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

    fun canScheduleExactly(): Boolean = alarmManager?.let {
        Build.VERSION.SDK_INT < 31 || it.canScheduleExactAlarms()
    } == true

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

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

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
