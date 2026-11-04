package com.example.unit

import com.jarvis.phone.AppMatcher
import com.jarvis.phone.PhoneCommands
import org.junit.Assert.assertEquals
import org.junit.Test

class AppMatcherTest {
    private val phone = listOf(
        PhoneCommands.App("Instagram", "com.instagram.android"),
        PhoneCommands.App("Edits", "com.instagram.creators"),
        PhoneCommands.App("YouTube", "com.google.android.youtube"),
        PhoneCommands.App("YouTube Kids", "com.google.android.apps.youtube.kids"),
        PhoneCommands.App("Telegram", "org.telegram.messenger"),
        PhoneCommands.App("Chrome", "com.android.chrome"),
        PhoneCommands.App("WhatsApp", "com.whatsapp"),
    )

    private fun names(query: String) = AppMatcher.match(query, phone).map { it.name }

    @Test fun `instagram with an Uzbek ending picks Instagram, not Edits`() {
        assertEquals(listOf("Instagram"), names("instagramga"))
        assertEquals(listOf("Instagram"), names("instagram"))
    }

    @Test fun `an app whose own name is asked for is found`() {
        assertEquals(listOf("Edits"), names("edits ilovasini"))
        assertEquals(listOf("Edits"), names("edits"))
    }

    @Test fun `the shorter label wins when several start with the same word`() {
        assertEquals(listOf("YouTube"), names("youtubeda"))
    }

    @Test fun `common spoken aliases map to the installed app`() {
        assertEquals(listOf("WhatsApp"), names("vatsap"))
        assertEquals(listOf("YouTube"), names("yutub"))
        assertEquals(listOf("Telegram"), names("telegramni"))
    }

    @Test fun `an unknown name matches nothing`() {
        assertEquals(emptyList<String>(), names("kitob do'koni"))
        assertEquals(emptyList<String>(), names("   "))
    }

    @Test fun `short words are not cut into a different app name`() {
        // "dan" is an ending, but "dan" alone is not stripped down to nothing.
        assertEquals(emptyList<String>(), names("dan"))
    }
}
