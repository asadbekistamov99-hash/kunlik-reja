package com.example.jarvis.automation

import com.example.data.Habit
import com.example.data.HabitDao
import com.example.data.TaskCategory
import java.time.LocalDate
import java.util.Locale

/** Habit streak bookkeeping and learning habits from natural-language statements. */
class HabitEngine(private val habitDao: HabitDao) {

    suspend fun toggleToday(habit: Habit, today: LocalDate = LocalDate.now()) {
        val updated = if (habit.lastCompletedDate == today.toString()) revertCompletion(habit, today)
        else applyCompletion(habit, today)
        habitDao.updateHabit(updated)
    }

    /** Creates (or returns the existing) habit described by a statement like "har kuni sport qilaman". */
    suspend fun learnFromStatement(statement: String): Habit {
        val title = titleFromStatement(statement)
        habitDao.findByTitle(title)?.let { return it }
        val habit = Habit(title = title, category = categoryOf(statement))
        val id = habitDao.insertHabit(habit)
        return habit.copy(id = id.toInt())
    }

    companion object {
        fun applyCompletion(habit: Habit, today: LocalDate): Habit {
            val last = parse(habit.lastCompletedDate)
            val streak = if (last == today.minusDays(1)) habit.currentStreak + 1 else 1
            return habit.copy(
                lastCompletedDate = today.toString(),
                currentStreak = streak,
                bestStreak = maxOf(habit.bestStreak, streak),
                totalCompletedCount = habit.totalCompletedCount + 1
            )
        }

        fun revertCompletion(habit: Habit, today: LocalDate): Habit {
            val streak = (habit.currentStreak - 1).coerceAtLeast(0)
            return habit.copy(
                lastCompletedDate = if (streak > 0) today.minusDays(1).toString() else "",
                currentStreak = streak,
                totalCompletedCount = (habit.totalCompletedCount - 1).coerceAtLeast(0)
            )
        }

        /** Streak as it should be displayed today: a streak broken before yesterday counts as 0. */
        fun effectiveStreak(habit: Habit, today: LocalDate): Int {
            val last = parse(habit.lastCompletedDate) ?: return 0
            return if (last == today || last == today.minusDays(1)) habit.currentStreak else 0
        }

        fun titleFromStatement(statement: String): String {
            val words = statement.lowercase(Locale.ROOT)
                .replace(Regex("""[^\p{L}\p{N}' ]"""), " ")
                .split(' ')
                .filter { it.isNotBlank() && it !in FILLERS }
                .toMutableList()
            if (words.isEmpty()) return statement.trim().replaceFirstChar { it.uppercase() }
            val last = words.last()
            words[words.lastIndex] = when {
                last.endsWith("yman") && last.length > 5 -> last.dropLast(4) + "sh"
                last.endsWith("aman") && last.length > 5 -> last.dropLast(4) + "ish"
                last.endsWith("man") && last.length > 4 -> last.dropLast(3) + "ish"
                else -> last
            }
            return words.joinToString(" ").replaceFirstChar { it.uppercase() }
        }

        private fun categoryOf(text: String): String {
            val t = text.lowercase(Locale.ROOT)
            return when {
                listOf("sport", "yugur", "mashq", "suv", "yur", "uxla").any { it in t } -> TaskCategory.HEALTH.name
                listOf("kitob", "o'qi", "til", "dars").any { it in t } -> TaskCategory.STUDY.name
                listOf("ish", "hisobot").any { it in t } -> TaskCategory.WORK.name
                else -> TaskCategory.PERSONAL.name
            }
        }

        private fun parse(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

        private val FILLERS = setOf(
            "men", "mening", "har", "kuni", "kunda", "doim", "odatda", "jarvis", "eslab", "qol", "yodda", "tut",
            "unutma", "ki", "hafta", "haftada"
        )
    }
}
