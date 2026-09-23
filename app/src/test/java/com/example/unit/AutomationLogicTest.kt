package com.example.unit

import com.example.data.Habit
import com.example.data.MemoryType
import com.example.data.Reminder
import com.example.data.Task
import com.example.jarvis.automation.HabitEngine
import com.example.jarvis.automation.ReminderEngine
import com.example.jarvis.automation.SmartPlanner
import com.example.jarvis.memory.LongTermMemory
import com.example.jarvis.settings.AiMode
import com.example.jarvis.settings.JarvisSettings
import com.example.jarvis.settings.SttEngineChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AutomationLogicTest {

    @Test fun `one-shot reminder in the past never fires again`() {
        val r = Reminder(title = "x", triggerAtMillis = 1_000)
        assertNull(ReminderEngine.nextTrigger(r, 2_000))
        assertEquals(5_000L, ReminderEngine.nextTrigger(r.copy(triggerAtMillis = 5_000), 2_000))
        assertNull(ReminderEngine.nextTrigger(r.copy(triggerAtMillis = 5_000, isActive = false), 2_000))
    }

    @Test fun `repeating reminder skips missed periods`() {
        val day = 86_400_000L
        val r = Reminder(title = "x", triggerAtMillis = 0, repeatIntervalMinutes = 1440)
        assertEquals(3 * day, ReminderEngine.nextTrigger(r, 2 * day + 10))
        assertEquals(day, ReminderEngine.nextTrigger(r, 0))
    }

    @Test fun `task trigger honours lead time`() {
        val t = Task(title = "t", dateString = "", timeString = "", timestampMillis = 3_600_000, reminderMinutesBefore = 15)
        assertEquals(2_700_000L, ReminderEngine.taskTrigger(t))
        assertNull(ReminderEngine.taskTrigger(t.copy(hasReminder = false)))
    }

    @Test fun `habit streaks continue break and revert`() {
        val today = LocalDate.of(2026, 9, 23)
        val h = Habit(title = "Sport", currentStreak = 4, bestStreak = 4, lastCompletedDate = "2026-09-22", totalCompletedCount = 10)
        val done = HabitEngine.applyCompletion(h, today)
        assertEquals(5, done.currentStreak)
        assertEquals(5, done.bestStreak)
        assertEquals(11, done.totalCompletedCount)
        val broken = HabitEngine.applyCompletion(h.copy(lastCompletedDate = "2026-09-20"), today)
        assertEquals(1, broken.currentStreak)
        val reverted = HabitEngine.revertCompletion(done, today)
        assertEquals(4, reverted.currentStreak)
        assertEquals("2026-09-22", reverted.lastCompletedDate)
        assertEquals(0, HabitEngine.effectiveStreak(h.copy(lastCompletedDate = "2026-09-20"), today))
        assertEquals(4, HabitEngine.effectiveStreak(h, today))
    }

    @Test fun `habit titles from statements`() {
        assertEquals("Ertalab sport qilish", HabitEngine.titleFromStatement("Men har kuni ertalab sport qilaman"))
        assertEquals("Kitob o'qish", HabitEngine.titleFromStatement("men har kuni kitob o'qiyman"))
        assertEquals("2 litr suv ichish", HabitEngine.titleFromStatement("Har kuni 2 litr suv ichaman"))
    }

    @Test fun `habit memory maps to preferred plan time`() {
        val item = SmartPlanner.habitItem("Men har kuni ertalab sport qilaman")
        assertEquals(LocalTime.of(7, 0), item.preferredStart)
        assertEquals("Ertalab sport qilish", item.title)
        assertEquals("Bugun", SmartPlanner.dayLabel(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)))
        assertEquals("Ertaga", SmartPlanner.dayLabel(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)))
    }

    @Test fun `memory keys are order-insensitive and dedupe`() {
        assertEquals(
            LongTermMemory.keyFor(MemoryType.HABIT, "Men har kuni ertalab sport qilaman"),
            LongTermMemory.keyFor(MemoryType.HABIT, "har kuni sport qilaman ertalab")
        )
    }

    @Test fun `settings parse with defaults and validation`() {
        val s = JarvisSettings.parse(mapOf(
            JarvisSettings.AI_MODE to "OFFLINE_ONLY", JarvisSettings.STT_ENGINE to "bogus",
            JarvisSettings.WAKE_SENSITIVITY to "5", JarvisSettings.DAY_START to "7:00",
            JarvisSettings.FILE_TREES to "content://a\ncontent://b"
        ))
        assertEquals(AiMode.OFFLINE_ONLY, s.aiMode)
        assertEquals(SttEngineChoice.AUTO, s.sttEngine)
        assertEquals(0.95f, s.wakeSensitivity)
        assertEquals("08:00", s.dayStart)
        assertEquals(2, s.fileTreeUris.size)
    }
}
