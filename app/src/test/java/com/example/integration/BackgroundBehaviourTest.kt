package com.example.integration

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.JarvisApplication
import com.example.TestJarvisApplication
import com.example.data.Reminder
import com.example.data.Task
import com.jarvis.service.JarvisForegroundService
import com.jarvis.service.JarvisServiceController
import com.jarvis.settings.JarvisSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Reboot / background behaviour across Android 12 (31), 13 (33), 14 (34) and 15 (35).
 * Real-device coverage of the same paths runs in the emulator matrix (androidTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 34, 35], application = TestJarvisApplication::class)
class BackgroundBehaviourTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val container get() = (app as JarvisApplication).container

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
    }

    @Test fun `reboot re-arms reminders and restores the assistant`() = runBlocking {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val future = System.currentTimeMillis() + 3_600_000
        container.database.reminderDao().insert(Reminder(title = "Dori ichish", triggerAtMillis = future))
        container.database.taskDao().insertTask(Task(title = "Majlis", dateString = "", timeString = "",
            timestampMillis = future + 3_600_000, hasReminder = true))
        container.settings.set(JarvisSettings.ASSISTANT_ENABLED, true)

        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        waitUntil { alarms.scheduledAlarms.size >= 2 }
        assertEquals(2, alarms.scheduledAlarms.size)

        val started = shadowOf(app).nextStartedService
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: mic FGS may not start from BOOT_COMPLETED -> one-tap resume notification.
            waitUntil { shadowOf(nm).allNotifications.isNotEmpty() }
            assertNull(started)
            assertTrue(shadowOf(nm).allNotifications.any { shadowOf(it).contentTitle == "Jarvis tayyor" })
        } else {
            waitUntil { shadowOf(app).peekNextStartedService() != null || started != null }
            val svc = started ?: shadowOf(app).nextStartedService
            assertNotNull(svc)
            assertEquals(JarvisForegroundService::class.java.name, svc!!.component!!.className)
        }
    }

    @Test fun `assistant does not start without microphone permission`() {
        shadowOf(app).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertTrue(!JarvisServiceController.start(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `task alarms fire notification with done action`() = runBlocking {
        val ctx: Context = app
        val t = container.taskRepository.insertTask(Task(title = "Suv ichish", dateString = "2026-09-23", timeString = "10:00",
            timestampMillis = System.currentTimeMillis() + 60_000, reminderMinutesBefore = 0))
        val alarms = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        val scheduled = alarms.scheduledAlarms.single()
        scheduled.operation!!.send()
        idle()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        waitUntil { shadowOf(nm).allNotifications.isNotEmpty() }
        val n = shadowOf(nm).allNotifications.single()
        assertTrue(shadowOf(n).contentTitle.toString().contains("Suv ichish"))
        assertEquals("Bajarildi", n.actions.single().title)
        // Completing the task cancels its alarm.
        container.taskRepository.updateTask(t.copy(isCompleted = true))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
