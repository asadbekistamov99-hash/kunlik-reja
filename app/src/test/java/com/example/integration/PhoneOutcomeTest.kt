package com.example.integration

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.example.TestJarvisApplication
import com.jarvis.integrations.ActivityLauncher
import com.jarvis.phone.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class PhoneOutcomeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private class Driver : PhoneDriver {
        var current: PhoneScreen? = null
        var accept = true
        var applyText = false
        var appAfterType: String? = null
        override suspend fun screen(withImage: Boolean) = current
        override suspend fun act(screen: PhoneScreen, step: PhoneStep): Boolean {
            if (accept && applyText && step.action == PhoneAction.TYPE) {
                current = screen.copy(packageName = appAfterType ?: screen.packageName, nodes = screen.nodes.map { if (it.id == step.nodeId) it.copy(label = step.text) else it })
            }
            return accept
        }
    }
    private fun commands(driver: Driver, scope: CoroutineScope): PhoneCommands {
        val planner = object : GamePlanner {
            override fun available() = false
            override suspend fun next(goal: String, screen: PhoneScreen, previous: List<PhoneStep>, mode: com.jarvis.phone.ChessMode) = PhoneStep(PhoneAction.STOP)
        }
        return PhoneCommands(context, ActivityLauncher(context), driver, GameController(driver, planner, scope, { true }, { false }))
    }

    @Suppress("DEPRECATION")
    private fun registerApp() {
        val application = ApplicationInfo().apply { packageName = "com.dts.freefireth"; nonLocalizedLabel = "Free Fire"; enabled = true }
        val activity = ActivityInfo().apply {
            packageName = application.packageName; name = "com.dts.freefireth.MainActivity"; applicationInfo = application
            nonLocalizedLabel = "Free Fire"; enabled = true; exported = true
        }
        val info = PackageInfo().apply { packageName = application.packageName; applicationInfo = application; activities = arrayOf(activity) }
        val pm = shadowOf(context.packageManager)
        pm.installPackage(info)
        val resolved = ResolveInfo().apply { activityInfo = activity; nonLocalizedLabel = "Free Fire" }
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), resolved)
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(application.packageName), resolved)
    }

    @Test fun `launch request only reports opened after target app is observed`() = runBlocking {
        registerApp()
        ShadowSettings.setCanDrawOverlays(true)
        val driver = Driver()
        val commands = commands(driver, this)
        val unknown = commands.open("Free Fire")
        assertFalse(unknown.text, unknown.success)
        assertTrue(unknown.text, unknown.text.contains("tasdiqlay olmadim"))
        driver.current = PhoneScreen("com.dts.freefireth", emptyList())
        val verified = commands.open("Free Fire")
        assertTrue(verified.text, verified.success)
        assertTrue(verified.text, verified.text.contains("ochildi"))
    }

    @Test fun `typed text is not reported as written without observing the changed field`() = runBlocking {
        val driver = Driver().apply { current = PhoneScreen("org.lichess", listOf(PhoneNode(0, "", false, true, false))) }
        val commands = commands(driver, this)
        assertFalse(commands.execute("matn yoz: Salom Asadbek!").success)
        driver.applyText = true
        assertTrue(commands.execute("matn yoz: Salom Asadbek!").success)
        driver.appAfterType = "org.other.app"
        assertFalse(commands.execute("matn yoz: Salom Asadbek!").success)
    }

    @Test fun `missing app and rejected button action are failures`() = runBlocking {
        val driver = Driver().apply {
            current = PhoneScreen("org.lichess", listOf(PhoneNode(0, "Play", true, false, false)))
            accept = false
        }
        val commands = commands(driver, this)
        assertFalse(commands.open("Not Installed").success)
        assertFalse(commands.execute("Play tugmasini bos").success)
    }
}
