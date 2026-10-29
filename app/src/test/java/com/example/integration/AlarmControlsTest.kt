package com.example.integration

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.JarvisApplication
import com.example.TestJarvisApplication
import com.example.data.Reminder
import com.example.receiver.TaskReminderReceiver
import com.jarvis.alarm.AlarmPlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = TestJarvisApplication::class)
class AlarmControlsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (app.applicationContext as JarvisApplication).container

    @Test fun `snooze saves a new occurrence five minutes ahead and preserves daily schedule`() = runBlocking {
        val future = System.currentTimeMillis() - 1_000
        val daily = container.reminderEngine.addReminder(Reminder(title = "Dori", triggerAtMillis = future, repeatIntervalMinutes = 1440))
        container.reminderEngine.onReminderFired(daily.id)
        val nextDay = container.database.reminderDao().getById(daily.id)!!
        assertTrue(nextDay.triggerAtMillis > System.currentTimeMillis() + 23 * 3_600_000L)
        val before = System.currentTimeMillis()
        val snoozed = container.reminderEngine.snooze(daily.title, "Pauza")
        assertTrue(snoozed.triggerAtMillis in (before + 300_000)..(System.currentTimeMillis() + 300_000))
        assertEquals(0, snoozed.repeatIntervalMinutes)
        assertEquals(nextDay, container.database.reminderDao().getById(daily.id))
        assertEquals(2, shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }

    @Test fun `notification snooze action persists and schedules another alarm`() = runBlocking {
        val before = System.currentTimeMillis()
        val payload = AlarmPlaybackService.payload(app, 100_777, "Darsim bor", "12:00")
        val notification = AlarmPlaybackService.notification(app, payload)
        notification.actions[1].actionIntent.send()
        val deadline = System.currentTimeMillis() + 5_000
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        while ((container.database.reminderDao().getAll().isEmpty() || alarms.scheduledAlarms.isEmpty()) &&
            System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(10)
        }
        val reminder = container.database.reminderDao().getAll().single()
        assertEquals("Darsim bor", reminder.title)
        assertTrue(reminder.triggerAtMillis in (before + 300_000)..(System.currentTimeMillis() + 300_000))
        assertEquals(1, shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }

    @Test fun `failed reschedule write preserves original reminder and alarm`() = runBlocking {
        val dao = container.database.reminderDao()
        val original = container.reminderEngine.addReminder(Reminder(title = "Dars", triggerAtMillis = System.currentTimeMillis() + 3_600_000))
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val originalOperation = alarms.scheduledAlarms.single().operation
        val failingDao = object : com.example.data.ReminderDao by dao {
            override suspend fun update(reminder: Reminder) { error("Database write failed") }
        }
        val engine = com.jarvis.automation.ReminderEngine(app, failingDao, container.database.taskDao())
        val result = runCatching { engine.rescheduleReminder(original, original.triggerAtMillis + 3_600_000) }
        assertTrue(result.isFailure)
        assertEquals(original, dao.getById(original.id))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(original.triggerAtMillis, alarms.scheduledAlarms.single().triggerAtTime)
        assertEquals(originalOperation, alarms.scheduledAlarms.single().operation)
    }

    @Test fun `alarm controls target individual occurrence and dismiss leaves other alarm visible`() {
        val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
        val service = controller.get()
        val first = AlarmPlaybackService.payload(app, 100_001, "Dori", "12:00")
        val second = AlarmPlaybackService.payload(app, 100_002, "Dars", "12:01")
        service.onStartCommand(first, 0, 1)
        service.onStartCommand(second, 0, 2)
        val notification = AlarmPlaybackService.notification(app, first)
        assertEquals(listOf("O'tkazib yuborish", "Pauza · 5 daqiqa"), notification.actions.map { it.title.toString() })
        assertEquals(TaskReminderReceiver.ACTION_ALARM_SNOOZE, shadowOf(notification.actions[1].actionIntent).savedIntent.action)
        assertEquals(100_001, shadowOf(notification.actions[1].actionIntent).savedIntent.getIntExtra(AlarmPlaybackService.EXTRA_ID, 0))
        service.onStartCommand(android.content.Intent(first).setAction(AlarmPlaybackService.ACTION_STOP), 0, 3)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertNotNull(nm.getNotification(100_002))
        assertNull(nm.getNotification(100_001))
        controller.destroy()
        assertNull(nm.getNotification(100_002))
    }
}
