package com.example

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.jarvis.core.IntentType
import com.jarvis.memory.MemoryDatabase
import com.jarvis.security.DatabaseEncryption
import com.jarvis.service.JarvisForegroundService
import com.jarvis.service.JarvisServiceController
import com.jarvis.settings.JarvisSettings
import com.jarvis.voice.AssistantStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * On-device suite executed on Android 12, 13, 14 and 15 emulators in CI. Covers the real
 * encrypted database, the microphone foreground service, locked screen, battery saver,
 * no-internet operation and the reboot recovery path.
 */
@RunWith(AndroidJUnit4::class)
class JarvisDeviceTest {
    private val permissions = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as JarvisApplication).container

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @After fun cleanUp() {
        JarvisServiceController.stop(context)
        shell("cmd power set-mode 0")
        shell("settings put global low_power 0")
        shell("cmd battery reset")
        shell("dumpsys battery reset")
        shell("cmd connectivity airplane-mode disable")
        shell("svc wifi enable")
        shell("svc data enable")
        shell("input keyevent KEYCODE_WAKEUP")
        runBlocking {
            container.settings.set(JarvisSettings.PAUSE_IN_BATTERY_SAVER, false)
            container.settings.set(JarvisSettings.LISTEN_WHEN_LOCKED, true)
            container.settings.set(JarvisSettings.ASSISTANT_ENABLED, false)
            container.database.taskDao().clear()
        }
    }

    @Test fun databaseIsEncryptedOnDisk() {
        val file = context.getDatabasePath(MemoryDatabase.NAME)
        runBlocking { container.database.taskDao().getTaskCount() }
        assertTrue(file.exists())
        assertFalse("database must not be plaintext SQLite", DatabaseEncryption.isPlaintext(file))
    }

    @Test fun navigatesAllScreensAndRunsTypedCommand() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen_dashboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_assistant").performClick()
        compose.onNodeWithTag("assistant_input").performTextInput("Jarvis ertaga soat 9 da uchrashuv qo'sh")
        compose.onNodeWithTag("assistant_send").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("ertaga soat 09:00 ga qo'shildi", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Uchrashuv", runBlocking { container.database.taskDao().getAll() }.single().title)
        listOf("nav_tasks" to "fab_add_task", "nav_calendar" to "screen_calendar", "nav_memory" to "screen_memory",
            "nav_stats" to "screen_statistics", "nav_settings" to "screen_settings", "nav_dashboard" to "screen_dashboard"
        ).forEach { (nav, tag) ->
            compose.onNodeWithTag(nav).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        }
        // Focus timer and habit tracker open from the Tasks screen.
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("action_focus").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("action_focus").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("dialog_focus_mode").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun worksWithoutInternet() {
        shell("cmd connectivity airplane-mode enable")
        shell("svc wifi disable")
        shell("svc data disable")
        assumeTrue("emulator did not go offline", waitFor(30_000) { !container.network.isOnline() })
        val reply = runBlocking { container.engine.handle("Jarvis bugungi rejani tuz") }
        assertEquals(IntentType.PLAN_DAY, reply.intent)
        val add = runBlocking { container.engine.handle("30 daqiqadan keyin suv ichishni eslat") }
        assertTrue(add.text, add.success)
    }

    private fun startService() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen_dashboard").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(JarvisServiceController.start(context))
        assertTrue("service running", waitFor { JarvisForegroundService.isRunning })
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue("persistent notification", waitFor { nm.activeNotifications.any { it.id == JarvisForegroundService.NOTIFICATION_ID } })
    }

    @Test fun foregroundServiceKeepsRunningOnLockedScreen() {
        startService()
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(1500)
        assertTrue(JarvisForegroundService.isRunning)
        assertTrue(container.voice.state.value.status != AssistantStatus.PAUSED)
        // ... and pauses when the user opts out of locked-screen listening.
        runBlocking { container.settings.set(JarvisSettings.LISTEN_WHEN_LOCKED, false) }
        assertTrue("paused while locked", waitFor { container.voice.state.value.status == AssistantStatus.PAUSED })
        shell("input keyevent KEYCODE_WAKEUP")
        runBlocking { container.settings.set(JarvisSettings.LISTEN_WHEN_LOCKED, true) }
        assertTrue(waitFor { container.voice.state.value.status != AssistantStatus.PAUSED })
        assertTrue(JarvisForegroundService.isRunning)
    }

    @Test fun foregroundServicePausesInBatterySaver() {
        startService()
        runBlocking { container.settings.set(JarvisSettings.PAUSE_IN_BATTERY_SAVER, true) }
        shell("dumpsys battery unplug")
        shell("cmd battery unplug")
        shell("cmd power set-mode 1")
        val pm = context.getSystemService(PowerManager::class.java)
        if (!waitFor(3_000) { pm.isPowerSaveMode }) shell("settings put global low_power 1")
        assumeTrue("emulator refused battery saver", waitFor(5_000) { pm.isPowerSaveMode })
        assertTrue("paused in battery saver", waitFor { container.voice.state.value.status == AssistantStatus.PAUSED })
        shell("cmd power set-mode 0")
        shell("settings put global low_power 0")
        assertTrue(waitFor { container.voice.state.value.status != AssistantStatus.PAUSED })
        assertTrue(JarvisForegroundService.isRunning)
    }

    @Test fun restartsAutomaticallyFromBackgroundWithOverlayPermission() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen_dashboard").fetchSemanticsNodes().isNotEmpty() }
        // First launch now auto-enables the assistant; stop it so this test proves the restart.
        JarvisServiceController.stop(context)
        assertTrue(waitFor(10_000) { !JarvisForegroundService.isRunning })
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assumeTrue("overlay permission not applied", waitFor(5_000) { android.provider.Settings.canDrawOverlays(context) })
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1500)
        JarvisServiceController.startFromBackground(context)
        assertTrue("service restarted without user interaction", waitFor { JarvisForegroundService.isRunning })
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue("resume notification cleared", waitFor(5_000) { nm.activeNotifications.none { it.id == 4243 } })
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
    }

    private fun listenCount() = container.voice.state.value.listenCount

    @Test fun wakeWordInBackgroundStartsListeningWithoutOpeningTheApp() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen_dashboard").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(JarvisServiceController.start(context))
        assertTrue(waitFor { JarvisForegroundService.isRunning })
        shell("input keyevent KEYCODE_HOME")
        assertTrue("app went to background", waitFor(10_000) {
            !androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        })
        // What the service does when "Hey Jarvis" is detected:
        val before = listenCount()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.voice.onWakeWord() }
        assertTrue("voice turn started in background", waitFor(10_000) { listenCount() > before })
        assertFalse("app UI stayed closed",
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.voice.cancel() }
    }

    @Test fun assistantGestureStartsJarvisWithoutOpeningTheApp() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen_dashboard").fetchSemanticsNodes().isNotEmpty() }
        JarvisServiceController.stop(context)
        assertTrue(waitFor(10_000) { !JarvisForegroundService.isRunning })
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
        // The system sends ACTION_ASSIST when Jarvis is the default assistant (long-press power/home).
        val before = listenCount()
        shell("am start -a android.intent.action.ASSIST -n ${context.packageName}/com.jarvis.service.AssistActivity")
        assertTrue("service started by the assist gesture", waitFor { JarvisForegroundService.isRunning })
        assertTrue("listening started", waitFor(10_000) { listenCount() > before })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.voice.cancel() }
    }

    @Test fun rebootRecoveryPath() {
        val rescheduled = runBlocking {
            container.engine.handle("ertaga soat 10 da hisobot qo'sh")
            container.reminderEngine.rescheduleAll()
        }
        assertTrue(rescheduled >= 1)
        JarvisServiceController.startFromBackground(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue("resume notification on Android 14+", waitFor { nm.activeNotifications.any { it.id == 4243 } })
        } else {
            assertTrue("service restarted directly", waitFor { JarvisForegroundService.isRunning })
        }
    }
}
