package com.example.jarvis.core

import com.example.data.Task
import com.example.data.TaskPriority
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class BlockKind { FIXED, MOVED, SCHEDULED, BREAK, HABIT }

data class PlanBlock(
    val title: String,
    val start: LocalTime,
    val end: LocalTime,
    val kind: BlockKind,
    val taskId: Int? = null,
    val priority: String = TaskPriority.MEDIUM.name
) {
    val minutes: Int get() = java.time.Duration.between(start, end).toMinutes().toInt()
}

/** Flexible item the planner may place anywhere in the day (e.g. a remembered habit). */
data class FlexibleItem(
    val title: String,
    val durationMinutes: Int,
    val preferredStart: LocalTime? = null
)

data class DayPlan(
    val date: LocalDate,
    val blocks: List<PlanBlock>,
    val unscheduled: List<Task>,
    val freeMinutes: Int
) {
    val taskBlocks: List<PlanBlock> get() = blocks.filter { it.taskId != null }
}

/**
 * Builds a conflict-free, time-blocked schedule for one day.
 *
 * Rules:
 *  1. Pending tasks already due later today keep their time (FIXED); overlapping ones are pushed
 *     back just enough to fit (MOVED).
 *  2. Overdue pending tasks (earlier today or previous days) are re-slotted into free time by
 *     priority, then shortest-first (SCHEDULED).
 *  3. A lunch break and remembered habits are placed when they fit.
 *  4. A short buffer separates consecutive blocks.
 */
class TaskPlanner(
    private val bufferMinutes: Long = 10,
    private val lunch: Pair<LocalTime, LocalTime>? = LocalTime.of(13, 0) to LocalTime.of(14, 0)
) {

    fun plan(
        date: LocalDate,
        now: LocalDateTime,
        tasksForDate: List<Task>,
        overdue: List<Task>,
        dayStart: LocalTime,
        dayEnd: LocalTime,
        flexible: List<FlexibleItem> = emptyList()
    ): DayPlan {
        val earliest = if (date == now.toLocalDate()) maxOf(dayStart, roundUp(now.toLocalTime())) else dayStart
        val blocks = mutableListOf<PlanBlock>()

        // 1. Fixed tasks, resolving overlaps in chronological order.
        val pendingToday = tasksForDate.filter { !it.isCompleted }
        val (upcoming, missed) = pendingToday.partition { parseTime(it.timeString)?.let { t -> !t.isBefore(earliest) } ?: false }
        var cursor = earliest
        for (task in upcoming.sortedBy { it.timeString }) {
            val wanted = parseTime(task.timeString) ?: continue
            val start = if (wanted.isBefore(cursor)) cursor else wanted
            val end = safePlus(start, task.durationMinutes.toLong())
            if (end.isAfter(dayEnd) || end.isBefore(start)) {
                continue // does not fit today; reported as unscheduled below
            }
            blocks += PlanBlock(task.title, start, end, if (start == wanted) BlockKind.FIXED else BlockKind.MOVED, task.id, task.priority)
            cursor = safePlus(end, bufferMinutes)
        }

        // 2. Lunch break if it doesn't collide with fixed tasks.
        lunch?.let { (ls, le) ->
            if (!ls.isBefore(earliest) && !le.isAfter(dayEnd) && blocks.none { overlaps(it.start, it.end, ls, le) }) {
                blocks += PlanBlock("Tushlik va dam olish", ls, le, BlockKind.BREAK)
            }
        }

        // 3. Overdue / missed tasks by priority then duration.
        val flexibleTasks = (missed + overdue.filter { !it.isCompleted && it.id !in pendingToday.map { p -> p.id } })
            .sortedWith(compareByDescending<Task> { priorityLevel(it.priority) }.thenBy { it.durationMinutes })
        val unscheduled = mutableListOf<Task>()
        for (task in flexibleTasks) {
            val slot = findSlot(blocks, earliest, dayEnd, task.durationMinutes.toLong(), null)
            if (slot == null) unscheduled += task
            else blocks += PlanBlock(task.title, slot, safePlus(slot, task.durationMinutes.toLong()), BlockKind.SCHEDULED, task.id, task.priority)
        }

        // Tasks that could not keep their fixed time because the day ran out.
        val placedIds = blocks.mapNotNull { it.taskId }.toSet()
        unscheduled += upcoming.filter { it.id !in placedIds }

        // 4. Habits / flexible items.
        for (item in flexible) {
            val slot = findSlot(blocks, earliest, dayEnd, item.durationMinutes.toLong(), item.preferredStart) ?: continue
            blocks += PlanBlock(item.title, slot, safePlus(slot, item.durationMinutes.toLong()), BlockKind.HABIT)
        }

        val sorted = blocks.sortedBy { it.start }
        val busy = sorted.sumOf { it.minutes }
        val window = java.time.Duration.between(earliest, dayEnd).toMinutes().toInt().coerceAtLeast(0)
        return DayPlan(date, sorted, unscheduled, (window - busy).coerceAtLeast(0))
    }

    private fun findSlot(blocks: List<PlanBlock>, from: LocalTime, to: LocalTime, minutes: Long, preferred: LocalTime?): LocalTime? {
        val sorted = blocks.sortedBy { it.start }
        val candidates = mutableListOf<LocalTime>()
        if (preferred != null && !preferred.isBefore(from)) candidates += preferred
        candidates += from
        sorted.forEach { candidates += safePlus(it.end, bufferMinutes) }
        for (start in candidates.distinct().sorted().let { list ->
            // Try the preferred time first, then chronological order.
            if (preferred != null && preferred in list) listOf(preferred) + (list - preferred) else list
        }) {
            if (start.isBefore(from)) continue
            val end = safePlus(start, minutes)
            if (end.isBefore(start) || end.isAfter(to)) continue
            val clash = sorted.any { overlaps(start, safePlus(end, bufferMinutes - 1), it.start, it.end) }
            if (!clash) return start
        }
        return null
    }

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value, HHMM) }.getOrNull()

        fun overlaps(aStart: LocalTime, aEnd: LocalTime, bStart: LocalTime, bEnd: LocalTime): Boolean =
            aStart.isBefore(bEnd) && bStart.isBefore(aEnd)

        fun priorityLevel(priority: String): Int =
            runCatching { TaskPriority.valueOf(priority).level }.getOrDefault(TaskPriority.MEDIUM.level)

        /** Adds minutes without wrapping past midnight (clamps to 23:59). */
        fun safePlus(t: LocalTime, minutes: Long): LocalTime {
            val total = t.toSecondOfDay() / 60 + minutes
            return if (total >= 24 * 60) LocalTime.of(23, 59) else LocalTime.ofSecondOfDay(total.coerceAtLeast(0) * 60)
        }

        fun roundUp(t: LocalTime, step: Int = 5): LocalTime {
            val m = t.hour * 60 + t.minute + (if (t.second > 0 || t.nano > 0) 1 else 0)
            val rounded = ((m + step - 1) / step) * step
            return if (rounded >= 24 * 60) LocalTime.of(23, 59) else LocalTime.of(rounded / 60, rounded % 60)
        }

        fun describe(plan: DayPlan): String {
            val tasks = plan.taskBlocks
            if (tasks.isEmpty()) return "Bu kun uchun rejalashtiriladigan vazifa yo'q. Bo'sh vaqtingiz ${plan.freeMinutes / 60} soat."
            val sb = StringBuilder("Rejangiz tayyor: ${tasks.size} ta vazifa. ")
            tasks.take(4).forEach { sb.append("${it.start.format(HHMM)} da ${it.title}. ") }
            if (tasks.size > 4) sb.append("Va yana ${tasks.size - 4} ta. ")
            val moved = plan.blocks.count { it.kind == BlockKind.MOVED || it.kind == BlockKind.SCHEDULED }
            if (moved > 0) sb.append("$moved ta vazifa vaqti moslashtirildi. ")
            if (plan.unscheduled.isNotEmpty()) sb.append("${plan.unscheduled.size} ta vazifa bugunga sig'madi.")
            return sb.toString().trim()
        }
    }
}
