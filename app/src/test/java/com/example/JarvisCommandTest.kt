package com.example

import com.example.jarvis.JarvisCommands
import com.example.data.TaskValidation
import org.junit.Assert.*
import org.junit.Test

class JarvisCommandTest {
    private fun parse(text: String, date: String = "2026-09-22") = JarvisCommands.parse(text, date)
    @Test fun wakeAndSleepAreExactCommands() {
        assertEquals("WAKE", parse("Jarvis").action)
        assertEquals("WAKE", parse("Jarvis boshla!").action)
        assertEquals("SLEEP", parse("Jarvis tugat").action)
        assertEquals("FOCUS_STOP", parse("Jarvis fokus tugat").action)
    }
    @Test fun apostrophesAndMidnightRollover() {
        val c = parse("Jarvis ertaga 09:30 da kitob o‘qish qo‘sh", "2026-12-31")
        assertEquals("ADD", c.action)
        assertEquals("kitob o'qish", c.target)
        assertEquals("2027-01-01", c.date)
        assertEquals("09:30", c.time)
    }
    @Test fun leapYearAndHalfHour() {
        val c = parse("ertaga soat 9 yarimda dars qo'sh", "2028-02-28")
        assertEquals("2028-02-29", c.date)
        assertEquals("09:30", c.time)
        assertEquals("dars", c.target)
    }
    @Test fun scheduleDoesNotBecomeSearch() {
        assertEquals("SCHEDULE", parse("Bugungi vazifalarimni ko'rsat").action)
        assertEquals("2026-09-23", parse("Ertangi rejani o'qi").date)
        assertEquals("SEARCH", parse("qidir majlis").action)
    }
    @Test fun noSubstringMutationsOrBlankDeletion() {
        assertEquals("UNKNOWN", parse("topologiya nima").action)
        assertEquals("UNKNOWN", parse("o'chir").action)
        assertEquals("UNKNOWN", parse("men darsni bajarildi deb o'ylamagandim").action)
        assertEquals("DELETE", parse("o'chir #5").action)
        assertEquals("#5", parse("o'chir #5").target)
    }
    @Test fun structuredEditsAndHabits() {
        assertEquals("uchrashuv", parse("nomini o'zgartir majlis / uchrashuv").value)
        assertEquals("HABIT_DONE", parse("odat bajarildi suv").action)
        assertEquals("SUBTASK_ADD", parse("kichik vazifa qo'sh kitob / 1-bob").action)
        assertEquals("MOVE", parse("ko'chir majlis / ertaga 10:00").action)
        assertEquals("STATS", parse("statistika").action)
        assertEquals("25", parse("fokus boshla").value)
    }
    @Test fun strictTimeValidation() {
        for((date, time) in listOf("2026-02-30" to "09:00", "2026-09-22" to "25:00", "2026-09-22" to "09:99", "2026-09-22garbage" to "09:00")) {
            assertThrows(IllegalArgumentException::class.java) { TaskValidation.timestamp(date, time) }
        }
        assertTrue(TaskValidation.timestamp("2028-02-29", "23:59") > 0)
    }
}
