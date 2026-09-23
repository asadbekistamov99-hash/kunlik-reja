package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.MainActivity
import com.example.TestJarvisApplication
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestJarvisApplication::class, qualifiers = "w412dp-h915dp-xxhdpi")
class JarvisUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String) =
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun dashboardAssistantAndAllScreens() {
        waitForTag("screen_dashboard")
        rule.onNodeWithTag("jarvis_orb").assertIsDisplayed()
        rule.onNodeWithTag("card_enable_assistant").assertIsDisplayed()
        rule.onRoot().captureRoboImage(filePath = "build/previews/01-dashboard.png")

        rule.onNodeWithTag("nav_assistant").performClick()
        waitForTag("screen_assistant")
        rule.onNodeWithTag("assistant_input").performTextInput("Jarvis ertaga soat 9 da uchrashuv qo'sh")
        rule.onNodeWithTag("assistant_send").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("ertaga soat 09:00 ga qo'shildi", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().captureRoboImage(filePath = "build/previews/02-assistant.png")

        for ((nav, screen, file) in listOf(
            Triple("nav_tasks", "fab_add_task", "03-tasks"),
            Triple("nav_calendar", "screen_calendar", "04-calendar"),
            Triple("nav_memory", "screen_memory", "05-memory"),
            Triple("nav_stats", "screen_statistics", "06-statistics"),
            Triple("nav_settings", "screen_settings", "07-settings")
        )) {
            rule.onNodeWithTag(nav).performClick()
            waitForTag(screen)
            rule.onRoot().captureRoboImage(filePath = "build/previews/$file.png")
        }
    }
}
