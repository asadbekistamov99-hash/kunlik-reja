package com.example.unit

import com.jarvis.core.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CommandParserTest {
    // Wednesday 23 September 2026, 10:15
    private val now = LocalDateTime.of(2026, 9, 23, 10, 15)
    private val parser = CommandParser { now }

    @Test fun `strips wake word and detects it`() {
        val p = parser.parse("Jarvis, bugungi rejani tuz")
        assertTrue(p.hasWakeWord)
        assertEquals("bugungi rejani tuz", p.body)
        assertFalse(parser.parse("bugungi rejani tuz").hasWakeWord)
        assertTrue(parser.parse("Hey Jarvis").hasWakeWord)
    }

    @Test fun `tomorrow at 9 meeting`() {
        val p = parser.parse("Jarvis ertaga soat 9 da uchrashuv qo'sh")
        assertEquals(LocalDate.of(2026, 9, 24), p.date)
        assertEquals(LocalTime.of(9, 0), p.time)
        assertEquals("uchrashuv qo'sh", p.remainder)
    }

    @Test fun `clock time with minutes and evening period`() {
        assertEquals(LocalTime.of(14, 30), parser.parse("soat 14:30 da majlis").time)
        assertEquals(LocalTime.of(19, 0), parser.parse("kechqurun soat 7 da kino").time)
        assertEquals(LocalTime.of(9, 30), parser.parse("soat 9 yarimda dars").time)
    }

    @Test fun `small hours without period are afternoon`() {
        assertEquals(LocalTime.of(15, 0), parser.parse("ertaga 3 da qo'ng'iroq").time)
        assertEquals(LocalTime.of(8, 0), parser.parse("soat 8 da nonushta").time)
    }

    @Test fun `relative minutes and hours`() {
        val p = parser.parse("30 daqiqadan keyin suv ichishni eslat")
        assertTrue(p.isRelative)
        assertEquals(LocalTime.of(10, 45), p.time)
        assertEquals(now.toLocalDate(), p.date)
        assertEquals(LocalTime.of(12, 15), parser.parse("2 soatdan keyin eslat").time)
        assertEquals(LocalTime.of(10, 45), parser.parse("yarim soatdan keyin").time)
    }

    @Test fun `weekday and explicit dates`() {
        assertEquals(LocalDate.of(2026, 9, 25), parser.parse("juma kuni hisobot").date)
        assertEquals(LocalDate.of(2026, 9, 23), parser.parse("chorshanba kuni").date)
        assertEquals(LocalDate.of(2026, 9, 25), parser.parse("indinga uchrashuv").date)
        assertEquals(LocalDate.of(2026, 10, 5), parser.parse("5 oktabr tug'ilgan kun").date)
        assertEquals(LocalDate.of(2027, 1, 10), parser.parse("10 yanvar imtihon").date)
        assertEquals(LocalDate.of(2026, 12, 1), parser.parse("2026-12-01 da reja").date)
        assertEquals(LocalDate.of(2026, 9, 26), parser.parse("3 kundan keyin").date)
    }

    @Test fun `durations`() {
        assertEquals(45, parser.parse("45 daqiqalik yugurish").durationMinutes)
        assertEquals(120, parser.parse("2 soatlik dars").durationMinutes)
        assertEquals(30, parser.parse("yarim soat mashq").durationMinutes)
        assertNull(parser.parse("soat 9 da").durationMinutes)
    }

    @Test fun `normalizes apostrophes cyrillic and number words`() {
        assertEquals("qo'sh", CommandParser.normalize("Qoʻsh"))
        assertEquals("qo'sh", CommandParser.normalize("qo’sh"))
        assertEquals("jarvis salom", CommandParser.normalize("Джарвис салом").replace("djarvis", "jarvis"))
        assertEquals(LocalTime.of(10, 0), parser.parse("ertaga soat o'n da").time)
        assertEquals(LocalTime.of(12, 0), parser.parse("soat o'n ikki da").time)
    }

    @Test fun `period alone gives default time`() {
        assertEquals(LocalTime.of(9, 0), parser.parse("ertaga ertalab yugurish").time)
        assertEquals(LocalTime.of(19, 0), parser.parse("kechqurun kitob o'qish").time)
    }
}
