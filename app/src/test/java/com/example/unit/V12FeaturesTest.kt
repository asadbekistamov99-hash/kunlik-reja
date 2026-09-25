package com.example.unit

import com.jarvis.core.CommandParser
import com.jarvis.core.IntentResolver
import com.jarvis.core.IntentType
import com.jarvis.core.ResolvedIntent
import com.jarvis.core.SpellCorrector
import com.jarvis.settings.JarvisSettings
import com.jarvis.settings.TtsEngineChoice
import com.jarvis.settings.VoiceGender
import com.jarvis.voice.GeminiVoice
import com.jarvis.voice.OpenAiVoice
import com.jarvis.voice.TextToSpeechManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class V12FeaturesTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 9, 23, 10, 15) }
    private val resolver = IntentResolver()
    private fun resolve(text: String) = resolver.resolve(parser.parse(text))
    /** Same repair pass JarvisEngine.understand() applies to weak readings. */
    private fun resolveWithRepair(text: String) = resolve(text).let { first ->
        if (first.confidence >= 0.75f) first else resolve(SpellCorrector.correct(parser.parse(text).normalized))
    }

    // ---------- speech-recognizer apostrophe loss ----------

    @Test fun `commands still work when the recognizer drops apostrophes`() {
        val add = resolve("Jarvis ertaga soat 9 da uchrashuv qosh")
        assertEquals(IntentType.ADD_TASK, add.type)
        assertEquals("Uchrashuv", add.slot(ResolvedIntent.TITLE))
        assertEquals(IntentType.LIST_PENDING, resolve("Jarvis tugallanmagan ishlarimni korsat").type)
        val call = resolve("Jarvis doktor bilan boglan")
        assertEquals(IntentType.CALL_CONTACT, call.type)
        assertEquals("doktor", call.slot(ResolvedIntent.CONTACT))
        assertEquals(IntentType.CALL_CONTACT, resolve("akamga qongiroq qil").type)
        assertEquals(IntentType.DELETE_TASK, resolve("kitob vazifasini ochir").type)
        assertEquals(IntentType.RESCHEDULE_TASK, resolve("uchrashuvni soat 11 ga kochir").type)
        assertEquals(IntentType.READ_NOTIFICATIONS, resolve("bildirishnomalarni oqi").type)
        assertEquals(IntentType.TIME_QUERY, resolve("soat necha boldi").type)
        assertEquals("yo'q", CommandParser.normalize("yoq"))
        assertEquals("qo'ng'iroq", CommandParser.normalize("qongiroq"))
    }

    @Test fun `wake word spelling variants from recognizers`() {
        listOf("Jarvis", "Hey Jarvis", "Xey jarvis", "Jarvas", "jarves", "Charvis", "hay jarvis").forEach {
            assertTrue(it, parser.parse(it).hasWakeWord)
        }
    }

    // ---------- misheard words ----------

    @Test fun `spell corrector snaps misheard command words`() {
        assertEquals("rejani", SpellCorrector.correctWord("rejeni"))
        assertEquals("eslat", SpellCorrector.correctWord("eslar"))
        assertEquals("tugallanmagan", SpellCorrector.correctWord("tugalanmagan"))
        assertEquals("uchrashuv", SpellCorrector.correctWord("uchrashuf"))
        // Short or unknown words and numbers are left alone.
        assertEquals("top", SpellCorrector.correctWord("top"))
        assertEquals("kompyuter", SpellCorrector.correctWord("kompyuter"))
        assertEquals("2026", SpellCorrector.correctWord("2026"))
        assertEquals(1, SpellCorrector.distance("rejeni", "rejani"))
    }

    @Test fun `repair pass turns misheard commands into the right intent`() {
        assertTrue(resolve("Jarvis bugungi rejeni tuz").confidence < 0.75f)
        assertEquals(IntentType.PLAN_DAY, resolveWithRepair("Jarvis bugungi rejeni tuz").type)
        assertEquals(IntentType.LIST_PENDING, resolveWithRepair("Jarvis tugalanmagan ishlarimni korsat").type)
        // Confident sentences are never "corrected".
        assertEquals(IntentType.ADD_TASK, resolveWithRepair("Jarvis ertaga soat 9 da uchrashuv qo'sh").type)
    }

    // ---------- voice ----------

    @Test fun `sentences are split for natural pauses`() {
        assertEquals(listOf("Bugun 5 ta vazifangiz bor.", "Birinchisi soat 9 da.", "Omad!"),
            TextToSpeechManager.splitSentences("Bugun 5 ta vazifangiz bor. Birinchisi soat 9 da. Omad!"))
        assertEquals(listOf("Salom"), TextToSpeechManager.splitSentences("Salom"))
    }

    @Test fun `device voice selection honours the chosen gender`() {
        assertEquals(VoiceGender.FEMALE, TextToSpeechManager.genderOf("tr-tr-x-female-local", emptySet()))
        assertEquals(VoiceGender.MALE, TextToSpeechManager.genderOf("ru-ru-x-abc-network", setOf("gender=male")))
        assertNull(TextToSpeechManager.genderOf("tr-tr-x-cfs-local", emptySet()))
        val wantMale = VoiceGender.MALE
        val male = TextToSpeechManager.voiceScore("x-male", emptySet(), 300, false, wantMale)
        val unlabeled = TextToSpeechManager.voiceScore("x-cfs", emptySet(), 500, false, wantMale)
        val female = TextToSpeechManager.voiceScore("x-female", emptySet(), 500, false, wantMale)
        assertTrue(male > unlabeled && unlabeled > female)
        assertTrue(TextToSpeechManager.voiceScore("a", emptySet(), 400, false, wantMale) >
            TextToSpeechManager.voiceScore("b", emptySet(), 400, true, wantMale))
    }

    @Test fun `neural voices map gender to real male and female voices`() {
        assertEquals("Charon", GeminiVoice.voiceName(VoiceGender.MALE))
        assertEquals("Kore", GeminiVoice.voiceName(VoiceGender.FEMALE))
        assertEquals("onyx", OpenAiVoice.voiceName(VoiceGender.MALE))
        assertEquals("nova", OpenAiVoice.voiceName(VoiceGender.FEMALE))
    }

    @Test fun `voice settings parse`() {
        val s = JarvisSettings.parse(mapOf(JarvisSettings.VOICE_GENDER to "FEMALE", JarvisSettings.TTS_ENGINE to "GEMINI"))
        assertEquals(VoiceGender.FEMALE, s.voiceGender)
        assertEquals(TtsEngineChoice.GEMINI, s.ttsEngine)
        val d = JarvisSettings.parse(emptyMap())
        assertEquals(VoiceGender.MALE, d.voiceGender)
        assertEquals(TtsEngineChoice.AUTO, d.ttsEngine)
    }
}
