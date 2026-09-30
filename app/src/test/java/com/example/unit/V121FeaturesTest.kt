package com.example.unit

import com.jarvis.core.CommandParser
import com.jarvis.core.IntentResolver
import com.jarvis.core.IntentType
import com.jarvis.settings.JarvisSettings
import com.jarvis.settings.VoiceGender
import com.jarvis.voice.OpenAiVoice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** v1.2.1: broader completion commands, OpenAI TTS voice settings. */
class V121FeaturesTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 9, 30, 10, 0) }
    private val resolver = IntentResolver()
    private fun resolve(text: String) = resolver.resolve(parser.parse(text))

    @Test fun `completion command variants resolve to COMPLETE_TASK`() {
        for (phrase in listOf(
            "suv ichishni bajarildi qilib belgila",
            "hisobotni tugatdim",
            "mashqni tugallandi",
            "vazifani bajarildi",
            "xaridni qo'ydim",
            "uchrashuvni bajardim"
        )) assertEquals(phrase, IntentType.COMPLETE_TASK, resolve(phrase).type)
    }

    @Test fun `adding a task is not mistaken for completion`() {
        assertEquals(IntentType.ADD_TASK, resolve("uchrashuv qo'sh").type)
        assertEquals(IntentType.ADD_TASK, resolve("ertaga soat 9 da uchrashuv qo'sh").type)
    }

    @Test fun `openai tts model constant and gender voices`() {
        assertEquals("gpt-4o-mini-tts", OpenAiVoice.TTS_MODEL)
        assertEquals("onyx", OpenAiVoice.voiceName(VoiceGender.MALE))
        assertEquals("nova", OpenAiVoice.voiceName(VoiceGender.FEMALE))
    }

    @Test fun `openai tts settings parse with defaults and clamping`() {
        val s = JarvisSettings.parse(mapOf(
            JarvisSettings.OPENAI_TTS_MODEL to "tts-1-hd",
            JarvisSettings.OPENAI_TTS_SPEED to "1.25"
        ))
        assertEquals("tts-1-hd", s.openaiTtsModel)
        assertEquals(1.25f, s.openaiTtsSpeed)

        val d = JarvisSettings.parse(emptyMap())
        assertEquals("gpt-4o-mini-tts", d.openaiTtsModel)
        assertEquals(1.0f, d.openaiTtsSpeed)

        val bad = JarvisSettings.parse(mapOf(JarvisSettings.OPENAI_TTS_SPEED to "99"))
        assertEquals(2.0f, bad.openaiTtsSpeed)
    }
}
