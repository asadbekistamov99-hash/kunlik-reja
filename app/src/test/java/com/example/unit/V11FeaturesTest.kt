package com.example.unit

import com.example.data.Task
import com.jarvis.automation.SmartPlanner
import com.jarvis.automation.WorkPatternAnalyzer
import com.jarvis.core.BlockKind
import com.jarvis.core.CommandParser
import com.jarvis.core.FlexibleItem
import com.jarvis.core.IntentResolver
import com.jarvis.core.IntentType
import com.jarvis.core.ResolvedIntent
import com.jarvis.core.TaskPlanner
import com.jarvis.wakeword.JarvisKeywordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class V11FeaturesTest {
    // Wednesday 23 September 2026, 10:15
    private val now = LocalDateTime.of(2026, 9, 23, 10, 15)
    private val parser = CommandParser { now }
    private val resolver = IntentResolver()

    // ---------- deadlines ----------

    @Test fun `deadline phrases are parsed separately from the task date`() {
        val p = parser.parse("Jarvis hisobotni jumagacha tayyorla")
        assertEquals(LocalDate.of(2026, 9, 25), p.deadline)
        assertNull(p.date)
        assertEquals(LocalDate.of(2026, 9, 24), parser.parse("taqdimotni ertagacha tugat").deadline)
        assertEquals(LocalDate.of(2026, 9, 30), parser.parse("loyihani 30 sentabrgacha topshir").deadline)
        assertEquals(LocalDate.of(2026, 9, 28), parser.parse("maqola yozish muddati dushanba").deadline)
        val both = parser.parse("ertaga soat 10 da hisobot yozish jumagacha")
        assertEquals(LocalDate.of(2026, 9, 24), both.date)
        assertEquals(LocalDate.of(2026, 9, 25), both.deadline)
        assertEquals(LocalTime.of(10, 0), both.time)
        assertNull(parser.parse("kechgacha ishlayman").deadline)
    }

    @Test fun `deadline task becomes an add intent with a deadline slot`() {
        val r = resolver.resolve(parser.parse("Jarvis hisobotni jumagacha tayyorla"))
        assertEquals(IntentType.ADD_TASK, r.type)
        assertEquals("2026-09-25", r.slot(ResolvedIntent.DEADLINE))
        assertEquals("Hisobotni tayyorla", r.slot(ResolvedIntent.TITLE))
    }

    @Test fun `work pattern questions are recognised`() {
        assertEquals(IntentType.WORK_PATTERNS, resolver.resolve(parser.parse("Jarvis ish odatlarim qanday")).type)
        assertEquals(IntentType.WORK_PATTERNS, resolver.resolve(parser.parse("men qachon samarali ishlayman")).type)
    }

    @Test fun `planner schedules the nearest deadline first`() {
        val date = now.toLocalDate()
        fun t(id: Int, deadline: String, priority: String = "MEDIUM") = Task(id = id, title = "T$id", dateString = "2026-09-20",
            timeString = "09:00", timestampMillis = 0, durationMinutes = 30, priority = priority, deadline = deadline)
        val plan = TaskPlanner().plan(date, now, emptyList(),
            listOf(t(1, "", "HIGH"), t(2, "2026-09-30"), t(3, "2026-09-24", "LOW")),
            LocalTime.of(8, 0), LocalTime.of(22, 0))
        assertEquals(listOf(3, 2, 1), plan.blocks.filter { it.kind == BlockKind.SCHEDULED }.sortedBy { it.start }.map { it.taskId })
    }

    @Test fun `urgent work goes into learned productive hours`() {
        val date = now.toLocalDate()
        val urgent = Task(id = 1, title = "Muhim", dateString = "2026-09-20", timeString = "09:00", timestampMillis = 0,
            durationMinutes = 60, priority = "HIGH")
        val plan = TaskPlanner().plan(date, LocalDateTime.of(date, LocalTime.of(8, 0)), emptyList(), listOf(urgent),
            LocalTime.of(8, 0), LocalTime.of(22, 0), productiveHours = listOf(15, 9))
        assertEquals(LocalTime.of(15, 0), plan.blocks.single { it.taskId == 1 }.start)
    }

    // ---------- "Men har kuni 7 da sport qilaman" ----------

    @Test fun `habit with explicit hour keeps that hour even before the working day`() {
        val item = SmartPlanner.habitItem("Men har kuni 7 da sport qilaman")
        assertEquals(LocalTime.of(7, 0), item.preferredStart)
        assertTrue(item.exact)
        assertEquals("Sport qilish", item.title)
        val date = now.toLocalDate()
        val plan = TaskPlanner().plan(date, LocalDateTime.of(date, LocalTime.of(6, 0)), emptyList(), emptyList(),
            LocalTime.of(8, 0), LocalTime.of(22, 0), listOf(item))
        val sport = plan.blocks.single { it.kind == BlockKind.HABIT }
        assertEquals(LocalTime.of(7, 0), sport.start)
    }

    @Test fun `exact habit whose time has passed today is not moved`() {
        val date = now.toLocalDate()
        val plan = TaskPlanner().plan(date, now, emptyList(), emptyList(), LocalTime.of(8, 0), LocalTime.of(22, 0),
            listOf(FlexibleItem("Sport qilish", 30, LocalTime.of(7, 0), exact = true)))
        assertTrue(plan.blocks.none { it.kind == BlockKind.HABIT })
    }

    // ---------- work patterns ----------

    @Test fun `work patterns are learned from completion history`() {
        val zone = ZoneOffset.UTC
        fun done(day: Int, hour: Int, category: String, onTime: Boolean = true): Task {
            val scheduled = LocalDateTime.of(2026, 9, day, hour, 0).toInstant(zone).toEpochMilli()
            return Task(title = "x", dateString = "2026-09-%02d".format(day), timeString = "%02d:00".format(hour),
                timestampMillis = scheduled, durationMinutes = 30, category = category, isCompleted = true,
                completedAt = scheduled + if (onTime) 10 * 60_000L else 5 * 3_600_000L)
        }
        val tasks = listOf(
            done(14, 9, "WORK"), done(14, 9, "WORK"), done(21, 9, "WORK"), done(16, 15, "STUDY"),
            done(21, 9, "WORK", onTime = false), done(15, 20, "HEALTH"),
            Task(title = "o'tkazib yuborilgan", dateString = "2026-09-15", timeString = "11:00", timestampMillis = 0, category = "HEALTH"),
            Task(title = "o'tkazib yuborilgan 2", dateString = "2026-09-16", timeString = "11:00", timestampMillis = 0, category = "HEALTH"),
            Task(title = "kelajak", dateString = "2026-10-01", timeString = "11:00", timestampMillis = 0)
        )
        val p = WorkPatternAnalyzer.analyze(tasks, listOf(LocalDateTime.of(2026, 9, 20, 8, 5).toInstant(zone).toEpochMilli()),
            LocalDate.of(2026, 9, 23), ZoneId.of("UTC"))
        assertTrue(p.hasEnoughData)
        assertEquals(9, p.productiveHours.first())
        assertEquals(DayOfWeek.MONDAY, p.bestWeekday)
        assertEquals(6.0 / 8.0, p.completionRate, 1e-9)
        assertEquals(5.0 / 6.0, p.onTimeRate!!, 1e-9)
        assertEquals("WORK", p.strongestCategory)
        assertEquals("HEALTH", p.weakestCategory)
        assertEquals(8, p.mostActiveAssistantHour)
        assertTrue(p.describe().contains("09:00"))
        assertTrue(p.toMemoryFacts().any { it.first == "pattern:productive_hours" })
    }

    @Test fun `too little history is reported honestly`() {
        val p = WorkPatternAnalyzer.analyze(emptyList(), emptyList(), LocalDate.of(2026, 9, 23), ZoneId.of("UTC"))
        assertFalse(p.hasEnoughData)
        assertTrue(p.describe().contains("yetarli emas"))
        assertTrue(p.toMemoryFacts().isEmpty())
    }

    // ---------- "Jarvis" keyword matcher ----------

    @Test fun `keyword matcher uses confidences and partial stability`() {
        val m = JarvisKeywordMatcher(0.7f)
        assertTrue(m.onResult("""{"result":[{"conf":0.93,"end":1.0,"start":0.5,"word":"jarvis"}],"text":"jarvis"}"""))
        assertFalse(m.onResult("""{"result":[{"conf":0.41,"end":1.0,"start":0.5,"word":"jarvis"}],"text":"jarvis"}"""))
        assertFalse(m.onResult("""{"text":"[unk]"}"""))
        assertTrue(m.onResult("""{"text":"jarvis"}"""))
        m.reset()
        assertFalse(m.onPartial("""{"partial":"jarvis"}"""))
        assertTrue(m.onPartial("""{"partial":"jarvis"}"""))
        m.reset()
        assertFalse(m.onPartial("""{"partial":"jarvis"}"""))
        assertFalse(m.onPartial("""{"partial":""}"""))
        assertFalse(m.onPartial("""{"partial":"jarvis"}"""))
    }
}
