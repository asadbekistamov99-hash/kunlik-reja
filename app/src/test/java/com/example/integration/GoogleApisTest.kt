package com.example.integration

import androidx.test.core.app.ApplicationProvider
import com.example.TestJarvisApplication
import com.example.jarvis.core.AgentContext
import com.example.jarvis.core.GeminiAgent
import com.example.jarvis.core.IntentType
import com.example.jarvis.core.ResolvedIntent
import com.example.jarvis.integrations.CalendarEvent
import com.example.jarvis.integrations.Gmail
import com.example.jarvis.integrations.GoogleApi
import com.example.jarvis.integrations.GoogleApiException
import com.example.jarvis.integrations.GoogleCalendar
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class GoogleApisTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val zone = ZoneId.of("Asia/Tashkent")

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun api(token: String? = "tok") = GoogleApi(http) { token }

    @Test fun `calendar create list update delete over REST`() = runBlocking {
        val cal = GoogleCalendar(ApplicationProvider.getApplicationContext(), api(), { true }, server.url("/cal").toString().trimEnd('/')) { zone }
        server.enqueue(MockResponse().setBody("""{"id":"ev1","summary":"Uchrashuv","start":{"dateTime":"2026-09-24T09:00:00+05:00"},"end":{"dateTime":"2026-09-24T09:30:00+05:00"}}"""))
        val start = ZonedDateTime.of(2026, 9, 24, 9, 0, 0, 0, zone)
        val created = cal.create("Uchrashuv", start, start.plusMinutes(30))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/cal/calendars/primary/events", post.path)
        assertEquals("Bearer tok", post.getHeader("Authorization"))
        val body = JSONObject(post.body.readUtf8())
        assertEquals("Uchrashuv", body.getString("summary"))
        assertEquals("2026-09-24T09:00:00+05:00", body.getJSONObject("start").getString("dateTime"))
        assertEquals(CalendarEvent.Source.GOOGLE, created.source)

        server.enqueue(MockResponse().setBody("""{"items":[{"id":"ev1","summary":"Uchrashuv","start":{"dateTime":"2026-09-24T09:00:00+05:00"},"end":{"dateTime":"2026-09-24T09:30:00+05:00"}},{"id":"ev2","summary":"Kun bo'yi","start":{"date":"2026-09-24"},"end":{"date":"2026-09-25"}}]}"""))
        val list = cal.eventsOn(start.toLocalDate())
        assertEquals(2, list.size)
        assertEquals(9, list[0].start.hour)
        val listReq = server.takeRequest()
        assertTrue(listReq.path!!.contains("singleEvents=true"))
        assertTrue(listReq.path!!.contains("timeMin=2026-09-24T00%3A00%3A00%2B05%3A00"))

        server.enqueue(MockResponse().setBody("""{"id":"ev1","summary":"Uchrashuv","start":{"dateTime":"2026-09-24T11:00:00+05:00"},"end":{"dateTime":"2026-09-24T11:30:00+05:00"}}"""))
        val moved = cal.update(created, start.plusHours(2), start.plusHours(2).plusMinutes(30))
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals(11, moved.start.hour)

        server.enqueue(MockResponse().setResponseCode(204))
        cal.delete(created)
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/cal/calendars/primary/events/ev1", del.path)
    }

    @Test fun `gmail unread draft and send`() = runBlocking {
        val gmail = Gmail(api(), server.url("/gm").toString().trimEnd('/'))
        server.enqueue(MockResponse().setBody("""{"messages":[{"id":"m1"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"m1","snippet":"Hisobotni yubordim","payload":{"headers":[{"name":"From","value":"\"Ali\" <ali@x.uz>"},{"name":"Subject","value":"Hisobot"}]}}"""))
        val unread = gmail.unread()
        assertEquals("Ali", unread.single().from)
        assertEquals("Hisobot", unread.single().subject)
        assertTrue(server.takeRequest().path!!.contains("q=is%3Aunread"))
        server.takeRequest()

        server.enqueue(MockResponse().setBody("""{"id":"d1"}"""))
        assertEquals("d1", gmail.createDraft("ali@x.uz", "Salom", "Matn"))
        val draft = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(draft.getJSONObject("message").getString("raw").isNotBlank())

        server.enqueue(MockResponse().setBody("""{"id":"s1"}"""))
        assertEquals("s1", gmail.send("ali@x.uz", "Salom", "Matn"))
        assertEquals("/gm/messages/send", server.takeRequest().path)
    }

    @Test fun `api errors and missing auth surface as exceptions`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Insufficient Permission"}}"""))
        try {
            api().call("GET", server.url("/x").toString())
            fail()
        } catch (e: GoogleApiException) {
            assertEquals(403, e.code)
            assertEquals("Insufficient Permission", e.message)
        }
        try {
            api(null).call("GET", server.url("/x").toString())
            fail()
        } catch (e: GoogleApiException) {
            assertEquals(401, e.code)
        }
    }

    @Test fun `gemini function call maps to intent`() = runBlocking {
        val agent = GeminiAgent(http, { "key" }, { true }, server.url("/gemini").toString())
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"add_task","args":{"title":"Doktor qabuli","date":"2026-09-25","time":"16:00","duration_minutes":45}}}]}}]}"""))
        val ctx = AgentContext(LocalDateTime.of(2026, 9, 23, 10, 0), "Ism: Asadbek", "", listOf("USER" to "salom", "MODEL" to "Assalomu alaykum"))
        val intent = agent.decide("juma kuni soat 4 da doktorga yozil", ctx)!!
        assertEquals(IntentType.ADD_TASK, intent.type)
        assertEquals("Doktor qabuli", intent.slot(ResolvedIntent.TITLE))
        assertEquals("16:00", intent.slot(ResolvedIntent.TIME))
        assertEquals("45", intent.slot(ResolvedIntent.DURATION))
        val req = server.takeRequest()
        assertEquals("key", req.getHeader("x-goog-api-key"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals(3, sent.getJSONArray("contents").length())
        assertTrue(sent.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").length() >= 20)
    }

    @Test fun `gemini text answer and failures`() = runBlocking {
        val agent = GeminiAgent(http, { "key" }, { true }, server.url("/gemini").toString())
        val ctx = AgentContext(LocalDateTime.now(), "", "", emptyList())
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Toshkent O'zbekiston poytaxti."}]}}]}"""))
        assertEquals("Toshkent O'zbekiston poytaxti.", agent.decide("poytaxt qaysi", ctx)!!.slot(ResolvedIntent.ANSWER))
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(agent.decide("x", ctx))
        val noKey = GeminiAgent(http, { "" }, { true }, server.url("/gemini").toString())
        assertTrue(!noKey.isAvailable())
        val offline = GeminiAgent(http, { "key" }, { false }, server.url("/gemini").toString())
        assertTrue(!offline.isAvailable())
    }

    @Test fun `unknown function name is ignored`() {
        assertNull(GeminiAgent.fromFunctionCall("rm_rf", JSONObject()))
        assertNull(GeminiAgent.parseResponse("""{"candidates":[]}"""))
    }
}
