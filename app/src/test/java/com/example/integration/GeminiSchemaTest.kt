package com.example.integration

import com.example.TestJarvisApplication
import com.jarvis.core.AgentContext
import com.jarvis.core.GeminiAgent
import com.jarvis.core.IntentType
import com.jarvis.core.ResolvedIntent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class GeminiSchemaTest {
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    @Test fun `request constrains the answer to the intent schema`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"candidates":[{"content":{"parts":[{"text":"{\"intent\":\"ADD_TASK\",\"confidence\":0.9,\"slots\":{\"title\":\"Stomatolog\"}}"}]}}]}"""
        ))
        val agent = GeminiAgent(OkHttpClient(), { "key" }, { true }, server.url("/gemini").toString())
        val ctx = AgentContext(LocalDateTime.of(2026, 9, 23, 10, 0), "", "", emptyList())

        val intent = agent.decide("ertaga stomatologga borish", ctx)!!
        assertEquals(IntentType.ADD_TASK, intent.type)
        assertEquals("Stomatolog", intent.slot(ResolvedIntent.TITLE))

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        val gen = sent.getJSONObject("generationConfig")
        assertEquals("application/json", gen.getString("responseMimeType"))

        val schema = gen.getJSONObject("responseSchema")
        assertEquals("OBJECT", schema.getString("type"))
        assertEquals(listOf("intent", "confidence"), schema.getJSONArray("required").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        })

        val intentEnum = schema.getJSONObject("properties").getJSONObject("intent").getJSONArray("enum")
        val allowed = (0 until intentEnum.length()).map { intentEnum.getString(it) }.toSet()
        assertEquals(IntentType.values().map { it.name }.toSet(), allowed)
        assertTrue(allowed.contains("ADD_TASK"))
    }
}
