package com.example.unit

import com.example.jarvis.integrations.Gmail
import com.example.jarvis.integrations.MimeMessage
import com.example.jarvis.memory.BackupCodec
import com.example.jarvis.memory.BackupException
import com.example.jarvis.voice.WhisperSpeechToText
import com.example.jarvis.wakeword.AudioListener
import com.example.jarvis.wakeword.ShortRingBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SecurityAndCodecTest {

    @Test fun `backup encryption round trip`() {
        val data = "{\"tasks\":[1,2,3]} — o'zbekcha matn".toByteArray()
        val enc = BackupCodec.encrypt(data, "parol12345".toCharArray())
        assertNotEquals(String(data), String(enc))
        assertArrayEquals(data, BackupCodec.decrypt(enc, "parol12345".toCharArray()))
    }

    @Test(expected = BackupException::class)
    fun `wrong backup password is rejected`() {
        val enc = BackupCodec.encrypt("secret".toByteArray(), "togri-parol".toCharArray())
        BackupCodec.decrypt(enc, "notogri-parol".toCharArray())
    }

    @Test(expected = BackupException::class)
    fun `non backup file is rejected`() {
        BackupCodec.decrypt("hello world, not a backup".toByteArray(), "x".toCharArray())
    }

    @Test fun `tampered backup is rejected`() {
        val enc = BackupCodec.encrypt("secret".toByteArray(), "pw123456".toCharArray())
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 1).toByte()
        val result = runCatching { BackupCodec.decrypt(enc, "pw123456".toCharArray()) }
        assertTrue(result.exceptionOrNull() is BackupException)
    }

    @Test fun `gmail raw message is valid base64url mime with utf8 subject`() {
        val raw = MimeMessage.build("ali@example.com", "Salom — uchrashuv", "Ertaga soat 9 da.\nRahmat")
        assertTrue(raw.none { it == '+' || it == '/' || it == '=' })
        val decoded = String(Base64.getUrlDecoder().decode(raw))
        assertTrue(decoded.startsWith("To: ali@example.com\r\n"))
        assertTrue(decoded.contains("Subject: =?UTF-8?B?"))
        val body = decoded.substringAfter("\r\n\r\n").replace("\r\n", "")
        assertEquals("Ertaga soat 9 da.\nRahmat", String(Base64.getDecoder().decode(body)))
    }

    @Test fun `header injection is stripped from recipient`() {
        val decoded = String(Base64.getUrlDecoder().decode(MimeMessage.build("a@b.com\r\nBcc: evil@x.com", "s", "b")))
        assertTrue(decoded.lines().none { it.startsWith("Bcc:") })
    }

    @Test fun `sender display name`() {
        assertEquals("Ali Valiyev", Gmail.displayName("\"Ali Valiyev\" <ali@example.com>"))
        assertEquals("ali@example.com", Gmail.displayName("<ali@example.com>"))
    }

    @Test fun `wav header is correct`() {
        val wav = WhisperSpeechToText.wav(ShortArray(160) { 1000 }, 16000)
        assertEquals(44 + 320, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
    }

    @Test fun `ring buffer keeps newest samples in order`() {
        val rb = ShortRingBuffer(5)
        rb.push(shortArrayOf(1, 2, 3))
        rb.push(shortArrayOf(4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), rb.snapshot())
    }

    @Test fun `rms level`() {
        assertEquals(0f, AudioListener.rms(ShortArray(10)))
        assertEquals(1f, AudioListener.rms(ShortArray(10) { Short.MAX_VALUE }), 0.001f)
    }
}
