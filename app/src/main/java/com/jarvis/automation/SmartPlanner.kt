package com.jarvis.automation

import com.example.data.MemoryType
import com.example.data.Task
import com.example.data.TaskDao
import com.jarvis.core.BlockKind
import com.jarvis.core.CommandParser
import com.jarvis.core.DayPlan
import com.jarvis.core.FlexibleItem
import com.jarvis.core.TaskPlanner
import com.jarvis.memory.UserMemory
import com.jarvis.settings.SettingsSnapshot
import com.example.repository.TaskRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Applies [TaskPlanner] to real data and produces spoken summaries. */
class SmartPlanner(
    private val taskDao: TaskDao,
    private val repository: TaskRepository,
    private val memory: UserMemory,
    private val planner: TaskPlanner = TaskPlanner(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now() }
) {

    suspend fun plan(date: LocalDate, settings: SettingsSnapshot, apply: Boolean): DayPlan {
        val now = clock()
        val tasks = taskDao.getTasksForDate(date.toString())
        val pending = taskDao.getPendingTasks()
        val overdue = if (date == now.toLocalDate()) {
            pending.filter { runCatching { LocalDate.parse(it.dateString) }.getOrNull()?.isBefore(date) == true }
        } else emptyList()
        // Tasks planned for later whose deadline is due by tomorrow are pulled into this day.
        val deadlineUrgent = pending.filter {
            it.deadline.isNotBlank() && it.deadline <= date.plusDays(1).toString() && it.dateString > date.toString()
        }
        val habits = memory.byType(MemoryType.HABIT).map { habitItem(it.content) }
        val patterns = WorkPatternAnalyzer.analyze(taskDao.getAll(), emptyList(), now.toLocalDate(), ZoneId.systemDefault())
        val plan = planner.plan(
            date = date,
            now = now,
            tasksForDate = tasks,
            overdue = overdue + deadlineUrgent,
            dayStart = TaskPlanner.parseTime(settings.dayStart) ?: LocalTime.of(8, 0),
            dayEnd = TaskPlanner.parseTime(settings.dayEnd) ?: LocalTime.of(22, 0),
            flexible = habits.filter { h -> tasks.none { it.title.equals(h.title, ignoreCase = true) } },
            productiveHours = if (patterns.hasEnoughData) patterns.productiveHours else emptyList()
        )
        if (apply) applyPlan(plan, tasks + overdue + deadlineUrgent)
        return plan
    }

    private suspend fun applyPlan(plan: DayPlan, source: List<Task>) {
        val byId = source.associateBy { it.id }
        plan.blocks.filter { it.kind == BlockKind.MOVED || it.kind == BlockKind.SCHEDULED }.forEach { block ->
            val task = byId[block.taskId] ?: return@forEach
            val time = block.start.format(HHMM)
            val millis = LocalDateTime.of(plan.date, block.start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repository.updateTask(task.copy(dateString = plan.date.toString(), timeString = time, timestampMillis = millis))
        }
    }

    suspend fun summary(date: LocalDate): String {
        val tasks = taskDao.getTasksForDate(date.toString())
        val label = dayLabel(date)
        if (tasks.isEmpty()) return "$label rejalashtirilgan vazifa yo'q."
        val done = tasks.count { it.isCompleted }
        val pending = tasks.filter { !it.isCompleted }
        val sb = StringBuilder("$label ${tasks.size} ta vazifangiz bor")
        if (done > 0) sb.append(", $done tasi bajarilgan")
        sb.append(". ")
        val nowTime = clock().toLocalTime().format(HHMM)
        val next = pending.filter { date != clock().toLocalDate() || it.timeString >= nowTime }.minByOrNull { it.timeString }
            ?: pending.minByOrNull { it.timeString }
        next?.let { sb.append("Keyingisi soat ${it.timeString} da: ${it.title}. ") }
        val due = taskDao.getPendingWithDeadlineBy(date.plusDays(1).toString())
        if (due.isNotEmpty()) {
            val late = due.count { it.deadline < date.toString() }
            sb.append("Muddati yaqin ${due.size} ta vazifa bor")
            if (late > 0) sb.append(", $late tasining muddati o'tgan")
            sb.append(": ${due.take(3).joinToString(", ") { it.title }}.")
        }
        return sb.toString().trim()
    }

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
            today -> "Bugun"
            today.plusDays(1) -> "Ertaga"
            today.minusDays(1) -> "Kecha"
            else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " kuni"
        }

        /** Turns a remembered habit ("Har kuni ertalab sport qilaman") into a plan block. */
        fun habitItem(content: String): FlexibleItem {
            val lower = content.lowercase()
            // An explicit time ("har kuni 7 da sport") wins over time-of-day words.
            val explicit = CommandParser { LocalDateTime.of(2000, 1, 1, 0, 0) }.parse(content)
                .takeIf { !it.isRelative }?.time
            val preferred = explicit ?: when {
                "ertalab" in lower || "tong" in lower -> LocalTime.of(7, 0)
                "tush" in lower -> LocalTime.of(13, 0)
                "kechqurun" in lower || "kech" in lower -> LocalTime.of(19, 0)
                else -> null
            }
            return FlexibleItem(HabitEngine.titleFromStatement(content), 30, preferred, exact = explicit != null)
        }
    }
}
