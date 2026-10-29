package com.example.integration

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.TestJarvisApplication
import com.jarvis.integrations.*
import com.jarvis.phone.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class CommunicationBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `only unambiguous text reply action is selected`() {
        val pi = PendingIntent.getBroadcast(context, 1, Intent("test.reply"), PendingIntent.FLAG_MUTABLE)
        fun action(key: String) = Notification.Action.Builder(null, "Reply", pi)
            .addRemoteInput(RemoteInput.Builder(key).setAllowFreeFormInput(true).build()).build()
        val one = action("one")
        assertSame(one, JarvisNotificationListener.replyAction(Notification.Builder(context, "test").addAction(one).build()))
        assertNull(JarvisNotificationListener.replyAction(Notification.Builder(context, "test").addAction(one).addAction(action("two")).build()))
        assertNull(JarvisNotificationListener.replyAction(Notification.Builder(context, "test").build()))
    }

    @Test fun `disconnected notification cannot be replied to`() {
        val item = NotificationItem("missing", "org.telegram.messenger", "Telegram", "Ali", "text", 1, true, true)
        assertFalse(JarvisNotificationListener.reply(item, "reply").success)
    }

    @Test fun `power invokes system menu while speaker refuses unrelated app`() = runBlocking {
        val actions = mutableListOf<PhoneStep>()
        val driver = object : PhoneDriver {
            override suspend fun screen(withImage: Boolean) = PhoneScreen("org.other.app", listOf(PhoneNode(1, "Speaker", true, false, false, false)))
            override suspend fun act(screen: PhoneScreen, step: PhoneStep): Boolean { actions += step; return true }
        }
        val settings = PhoneSettings(context, ActivityLauncher(context), driver)
        assertFalse(settings.execute("SPEAKER_ON").success)
        assertTrue(actions.isEmpty())
        assertFalse(settings.execute("POWER").success) // Requires manual power-menu confirmation.
        assertEquals(PhoneAction.POWER, actions.single().action)
    }
}
