package com.example.unit

import com.jarvis.core.*
import com.jarvis.integrations.PhoneCommunications
import org.junit.Assert.*
import org.junit.Test

class PhoneCommunicationCommandsTest {
    private fun resolve(text: String) = IntentResolver().resolve(CommandParser().parse(text))

    @Test fun `settings commands distinguish call mic from assistant mic`() {
        mapOf("Telefonni o'chir" to "POWER", "Yangi qo'ng'iroq qo'sh" to "ADD_CALL", "Bluetoothni yoq" to "BLUETOOTH_ON",
            "Bluethooh ni yoq" to "BLUETOOTH_ON", "Dinamikni o'chir" to "SPEAKER_OFF",
            "Qo'ng'iroq mikrofonini o'chir" to "CALL_MIC_OFF",
            "Qo'ng'iroq mikrofonini qayta yoq" to "CALL_MIC_ON").forEach { (text, command) ->
            val r = resolve(text)
            assertEquals(text, IntentType.PHONE_SETTING, r.type)
            assertEquals(text, command, r.slot(ResolvedIntent.CONTENT))
        }
        assertEquals(IntentType.PAUSE_MICROPHONE, resolve("Mikrofonni o'chir").type)
        assertEquals(IntentType.RESUME_MICROPHONE, resolve("Mikrofonni qayta yoq").type)
    }

    @Test fun `dictated body cannot supply recipient ordinal or control action`() {
        val r = resolve("Telegramda Aliga xabar yoz: Kontakt qo'sh, Bluetoothni yoq, 7-siga telefon qil!")
        assertEquals(IntentType.SOCIAL_MESSAGE, r.type)
        assertNull(r.slot("index"))
        assertEquals("ali", r.slot(ResolvedIntent.TO))
        assertEquals("Kontakt qo'sh, Bluetoothni yoq, 7-siga telefon qil!", r.slot(ResolvedIntent.BODY))
    }

    @Test fun `numbers reject service codes and preserve international prefix`() {
        assertEquals("+998901234567", PhoneCommunications.normalizeNumber("+998 (90) 123-45-67"))
        listOf("*123#", "123;456", "123,456", "", "12", "tel:12345", "1234567890123456").forEach {
            assertNull(it, PhoneCommunications.normalizeNumber(it))
        }
    }
}
