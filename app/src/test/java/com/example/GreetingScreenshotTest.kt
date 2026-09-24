package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi", sdk = [34])
class GreetingScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun dashboardAndJarvis() {
        rule.onNodeWithTag("screen_schedule_timeline").assertExists()
        rule.onRoot().captureRoboImage(filePath = "build/previews/dashboard.png")
        rule.onNodeWithTag("fab_jarvis_ai").performClick()
        rule.onNodeWithTag("dialog_jarvis").assertExists()
        rule.onNodeWithTag("dialog_jarvis").captureRoboImage(filePath = "build/previews/jarvis.png")
    }
}
