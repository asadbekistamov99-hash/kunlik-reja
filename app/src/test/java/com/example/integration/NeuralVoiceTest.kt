package com.example.integration

import android.util.Base64
import com.example.TestJarvisApplication
import com.jarvis.settings.VoiceGender
import com.jarvis.voice.GeminiVoice
import com.jarvis.voice.OpenAiVoice
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class NeuralVoiceTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val pcm = ByteArray(4800) { (it % 7).toByte() }

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun `gemini voice requests audio with the chosen voice and decodes PCM`() = runBlocking {
        val voice = GeminiVoice(http, { "gk" }, { true }, server.url("/tts").toString())
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=24000","data":"$b64"}}]}}]}"""))
        assertArrayEquals(pcm, voice.synthesize("Bugun 5 ta vazifangiz bor.", VoiceGender.FEMALE))
        val req = server.takeRequest()
        assertEquals("gk", req.getHeader("x-goog-api-key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("AUDIO", body.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", body.getJSONObject("generationConfig").getJSONObject("speechConfig")
            .getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertTrue(body.toString().contains("Bugun 5 ta vazifangiz bor."))
    }

    @Test fun `openai voice streams raw PCM for the male voice`() = runBlocking {
        val voice = OpenAiVoice(http, { "ok" }, { true }, server.url("/speech").toString())
        server.enqueue(MockResponse().setBody(Buffer().write(pcm)))
        assertArrayEquals(pcm, voice.synthesize("Salom", VoiceGender.MALE))
        val req = server.takeRequest()
        assertEquals("Bearer ok", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("onyx", body.getString("voice"))
        assertEquals("pcm", body.getString("response_format"))
    }

    @Test fun `neural voices are unavailable without key or network and fail soft on errors`() = runBlocking {
        assertFalse(GeminiVoice(http, { null }, { true }).isAvailable())
        assertFalse(OpenAiVoice(http, { "k" }, { false }).isAvailable())
        server.enqueue(MockResponse().setResponseCode(429))
        assertNull(GeminiVoice(http, { "gk" }, { true }, server.url("/tts").toString()).synthesize("x", VoiceGender.MALE))
    }
}
