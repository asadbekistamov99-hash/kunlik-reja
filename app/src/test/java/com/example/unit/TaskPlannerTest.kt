package com.example.unit

import com.example.data.Task
import com.jarvis.core.BlockKind
import com.jarvis.core.FlexibleItem
import com.jarvis.core.TaskPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TaskPlannerTest {
    private val date = LocalDate.of(2026, 9, 23)
    private val planner = TaskPlanner()

    private fun task(id: Int, title: String, time: String, minutes: Int = 30, priority: String = "MEDIUM", done: Boolean = false, day: LocalDate = date) =
        Task(id = id, title = title, dateString = day.toString(), timeString = time, timestampMillis = 0,
            durationMinutes = minutes, priority = priority, isCompleted = done)

    private fun noOverlaps(blocks: List<com.jarvis.core.PlanBlock>) {
        blocks.sortedBy { it.start }.zipWithNext().forEach { (a, b) ->
            assertFalse("${a.title} overlaps ${b.title}", TaskPlanner.overlaps(a.start, a.end, b.start, b.end))
        }
    }

    @Test fun `keeps fixed tasks and resolves conflicts`() {
        val plan = planner.plan(date, LocalDateTime.of(date, LocalTime.of(7, 0)),
            listOf(task(1, "Majlis", "09:00", 60), task(2, "Qo'ng'iroq", "09:30", 30), task(3, "Dars", "15:00", 90)),
            emptyList(), LocalTime.of(8, 0), LocalTime.of(22, 0))
        val byId = plan.taskBlocks.associateBy { it.taskId }
        assertEquals(BlockKind.FIXED, byId[1]!!.kind)
        assertEquals(BlockKind.MOVED, byId[2]!!.kind)
        assertEquals(LocalTime.of(10, 10), byId[2]!!.start)
        assertEquals(LocalTime.of(15, 0), byId[3]!!.start)
        assertTrue(plan.blocks.any { it.kind == BlockKind.BREAK })
        noOverlaps(plan.blocks)
    }

    @Test fun `reschedules overdue tasks by priority into free slots`() {
        val now = LocalDateTime.of(date, LocalTime.of(10, 2))
        val plan = planner.plan(date, now,
            listOf(task(1, "Ertalabki ish", "08:00", 30), task(2, "Tushlikdan keyin", "14:30", 60)),
            listOf(task(3, "Kechagi muhim", "18:00", 45, "HIGH", day = date.minusDays(1))),
            LocalTime.of(8, 0), LocalTime.of(22, 0))
        val scheduled = plan.blocks.filter { it.kind == BlockKind.SCHEDULED }
        assertEquals(listOf(3, 1), scheduled.map { it.taskId })
        assertTrue(scheduled.all { !it.start.isBefore(LocalTime.of(10, 5)) })
        noOverlaps(plan.blocks)
    }

    @Test fun `completed tasks are ignored and habits placed at preferred time`() {
        val plan = planner.plan(date, LocalDateTime.of(date, LocalTime.of(6, 0)),
            listOf(task(1, "Bajarilgan", "09:00", done = true)), emptyList(), LocalTime.of(6, 0), LocalTime.of(22, 0),
            listOf(FlexibleItem("Sport", 30, LocalTime.of(7, 0))))
        assertTrue(plan.taskBlocks.isEmpty())
        val habit = plan.blocks.single { it.kind == BlockKind.HABIT }
        assertEquals(LocalTime.of(7, 0), habit.start)
    }

    @Test fun `tasks that do not fit are reported`() {
        val plan = planner.plan(date, LocalDateTime.of(date, LocalTime.of(21, 0)), emptyList(),
            listOf(task(1, "Uzun ish", "10:00", 180, day = date.minusDays(1))), LocalTime.of(8, 0), LocalTime.of(22, 0))
        assertEquals(listOf(1), plan.unscheduled.map { it.id })
    }

    @Test fun `helpers`() {
        assertEquals(LocalTime.of(10, 5), TaskPlanner.roundUp(LocalTime.of(10, 1)))
        assertEquals(LocalTime.of(23, 59), TaskPlanner.safePlus(LocalTime.of(23, 30), 60))
        assertEquals(3, TaskPlanner.priorityLevel("HIGH"))
        assertEquals(2, TaskPlanner.priorityLevel("garbage"))
        assertTrue(TaskPlanner.describe(planner.plan(date, LocalDateTime.of(date, LocalTime.of(7, 0)),
            listOf(task(1, "A", "09:00")), emptyList(), LocalTime.of(8, 0), LocalTime.of(22, 0))).contains("1 ta vazifa"))
    }
}
