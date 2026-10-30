package com.example.integration

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.TestJarvisApplication
import com.jarvis.phone.*
import com.jarvis.security.JarvisCapability
import com.jarvis.security.PermissionManager
import com.jarvis.core.CommandParser
import com.jarvis.core.IntentResolver
import com.jarvis.core.IntentType
import com.jarvis.core.ResolvedIntent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class PhoneControlTest {
    @Test fun `accessibility permission requires the explicitly enabled Jarvis component`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val permissions = PermissionManager(context)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        assertFalse(permissions.isGranted(JarvisCapability.PHONE_CONTROL))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, permissions.settingsIntent(JarvisCapability.PHONE_CONTROL)!!.action)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, JarvisAccessibilityService::class.java).flattenToString())
        assertFalse(permissions.isGranted(JarvisCapability.PHONE_CONTROL))
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        assertTrue(permissions.isGranted(JarvisCapability.PHONE_CONTROL))
    }

    @Test fun `voice routes app game and screen commands without corrupting typed text`() {
        val parser = CommandParser()
        val resolver = IntentResolver()
        fun resolve(text: String) = resolver.resolve(parser.parse(text))
        assertEquals(IntentType.OPEN_APP, resolve("Lichess ilovasini och").type)
        assertEquals(IntentType.OPEN_APP, resolve("Gmail ilovasini och").type)
        assertEquals("lichess", resolve("Lichess ilovasini och").slot(ResolvedIntent.TITLE))
        assertEquals(IntentType.PLAY_GAME, resolve("shu o'yinni o'yna").type)
        assertEquals(IntentType.CONTROL_PHONE, resolve("Play tugmasini bos").type)
        assertEquals("matn yoz: Salom Asadbek!", resolve("matn yoz: Salom Asadbek!").slot(ResolvedIntent.CONTENT))
    }

    @Test fun `accessibility acts only on the observed app and rejects password screens`() = runBlocking {
        val controller = org.robolectric.Robolectric.buildService(JarvisAccessibilityService::class.java).create()
        try {
            val service = controller.get()
            val node = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                packageName = "org.lichess"
                text = "Play"
                isVisibleToUser = true
                isClickable = true
            }
            var clicked = false
            org.robolectric.Shadows.shadowOf(node).setOnPerformActionListener { _, _ -> clicked = true; true }
            org.robolectric.Shadows.shadowOf(service).setRootInActiveWindow(node)
            val observed = service.screen(false)!!
            assertEquals("Play", observed.nodes.single().label)
            assertTrue(service.act(observed, PhoneStep(PhoneAction.CLICK, 0)))
            assertTrue(clicked)
            clicked = false
            node.packageName = "org.telegram"
            assertFalse(service.act(observed, PhoneStep(PhoneAction.CLICK, 0)))
            assertFalse(clicked)
            node.isPassword = true
            assertTrue(service.screen(false)!!.sensitive)
            assertEquals("", service.screen(false)!!.nodes.single().label)
        } finally { controller.destroy() }
    }

    @Test fun `speech router blocks recording before grant and after permission revocation`() = runBlocking {
        var granted = false
        var googleCalls = 0
        var whisperCalls = 0
        val google = object : com.jarvis.voice.SpeechToText {
            override val id = "google"
            override fun isAvailable() = true
            override suspend fun listen(events: com.jarvis.voice.SttEvents, maxMillis: Long): String? {
                googleCalls++
                granted = false
                return null
            }
        }
        val whisper = object : com.jarvis.voice.SpeechToText {
            override val id = "whisper"
            override fun isAvailable() = true
            override suspend fun listen(events: com.jarvis.voice.SttEvents, maxMillis: Long): String? { whisperCalls++; return null }
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        val model = com.jarvis.voice.VoskModel(context, OkHttpClient(), "missing-test-model", "https://example.com/model.zip", 0)
        val router = com.jarvis.voice.SpeechToTextRouter(google, whisper, com.jarvis.voice.VoskSpeechToText(model),
            { com.jarvis.settings.SttEngineChoice.GOOGLE }, { true }, { granted })
        val events = object : com.jarvis.voice.SttEvents {}
        assertFalse(router.isAvailable())
        assertTrue(runCatching { router.listen(events) }.exceptionOrNull() is com.jarvis.voice.SttException)
        assertEquals(0, googleCalls)
        granted = true
        assertTrue(router.isAvailable())
        val error = runCatching { router.listen(events) }.exceptionOrNull()
        assertTrue(error is com.jarvis.voice.SttException && !error.recoverable)
        assertEquals(1, googleCalls)
        assertEquals(0, whisperCalls)
    }

    @Test fun `post-game analysis sends only completed pgn and practice moves require matching mode`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val planner = GeminiGamePlanner(OkHttpClient(), { "test-key" }, { true }, server.url("/vision").toString())
            assertFalse(planner.analyze("1. e4 e5 *").success)
            assertEquals(0, server.requestCount)
            val pgn = "[Result \"1-0\"]\n1. e4 e5 2. Nf3 Nc6 1-0"
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Oq g'alaba qozongan. Markazni rivojlantiring."}]}}]}"""))
            val analysis = planner.analyze(pgn)
            assertTrue(analysis.text, analysis.success)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            assertEquals(1, parts.length())
            assertEquals(pgn, JSONObject(parts.getJSONObject(0).getString("text")).getString("completed_game_pgn"))
            assertTrue(runCatching { GeminiGamePlanner.parse("""{"action":"CLICK","nodeId":0}""", ChessMode.PUZZLE) }.isFailure)
            assertEquals(PhoneAction.CLICK, GeminiGamePlanner.parse("""{"action":"CLICK","nodeId":0,"screenMode":"PUZZLE"}""", ChessMode.PUZZLE).action)
            assertTrue(runCatching { GeminiGamePlanner.parse("""{"action":"CLICK","nodeId":0,"screenMode":"BOT"}""", ChessMode.PUZZLE) }.isFailure)
        } finally { server.shutdown() }
    }

    @Test fun `planner sends screen only on request and validates returned moves`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val planner = GeminiGamePlanner(OkHttpClient(), { "test-key" }, { true }, server.url("/vision").toString())
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"action\":\"TAP\",\"x\":0.5,\"y\":0.4}"}]}}]}"""))
            val step = planner.next("shaxmat o'yna", PhoneScreen("org.lichess", emptyList(), jpeg = byteArrayOf(1,2,3), width = 100, height = 200))
            assertEquals(PhoneAction.TAP, step.action)
            assertEquals("test-key", server.takeRequest().getHeader("x-goog-api-key"))
            for (invalid in listOf("""{"action":"HOME"}""", """{"action":"TAP","x":1.5,"y":0.2}""", """{"action":"CLICK","nodeId":-1}""")) {
                assertTrue(invalid, runCatching { GeminiGamePlanner.parse(invalid) }.isFailure)
            }
            assertTrue(runCatching { planner.next("o'yna", PhoneScreen("org.lichess", emptyList(), sensitive = true)) }.isFailure)
        } finally { server.shutdown() }
    }
}
