package com.example.repository

import com.example.data.Habit
import com.example.data.HabitDao
import com.example.data.Task
import com.example.data.TaskDao
import com.example.jarvis.automation.HabitEngine
import com.example.jarvis.automation.ReminderEngine
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tasks and habits, keeping AlarmManager reminders in sync with every write. */
class TaskRepository(
    private val taskDao: TaskDao,
    private val habitDao: HabitDao,
    private val reminders: ReminderEngine,
    private val habits: HabitEngine
) {
    val allTasks: Flow<List<Task>> = taskDao.getAllTasks()
    val allHabits: Flow<List<Habit>> = habitDao.getAllHabits()

    suspend fun insertHabit(habit: Habit): Long = habitDao.insertHabit(habit)

    suspend fun updateHabit(habit: Habit) = habitDao.updateHabit(habit)

    suspend fun deleteHabit(habit: Habit) = habitDao.deleteHabit(habit)

    suspend fun toggleHabitForToday(habit: Habit) = habits.toggleToday(habit)

    fun getTasksByDate(date: String): Flow<List<Task>> = taskDao.getTasksByDate(date)

    suspend fun getTaskById(id: Int): Task? = taskDao.getTaskById(id)

    suspend fun insertTask(task: Task): Task {
        val id = taskDao.insertTask(task)
        val saved = task.copy(id = id.toInt())
        reminders.scheduleTask(saved)
        return saved
    }

    suspend fun updateTask(task: Task) {
        taskDao.updateTask(task)
        reminders.scheduleTask(task)
    }

    suspend fun deleteTask(task: Task) {
        taskDao.deleteTask(task)
        reminders.cancelTask(task.id)
    }

    suspend fun pendingTasks(): List<Task> = taskDao.getPendingTasks()

    suspend fun tasksFor(date: String): List<Task> = taskDao.getTasksForDate(date)

    suspend fun search(query: String): List<Task> = taskDao.searchTasks(query)

    /**
     * Best match for a spoken task reference: exact title, then substring, then word overlap.
     * Pending tasks win over completed ones.
     */
    suspend fun findByTitle(query: String): Task? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        val all = taskDao.getAll().sortedWith(compareBy<Task> { it.isCompleted }.thenByDescending { it.timestampMillis })
        all.firstOrNull { it.title.lowercase(Locale.ROOT) == q }?.let { return it }
        all.firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) || q.contains(it.title.lowercase(Locale.ROOT)) }?.let { return it }
        val words = q.split(' ').filter { it.length >= 3 }.map { it.take(5) }
        if (words.isEmpty()) return null
        return all.maxByOrNull { t -> words.count { w -> t.title.lowercase(Locale.ROOT).contains(w) } }
            ?.takeIf { t -> words.any { w -> t.title.lowercase(Locale.ROOT).contains(w) } }
    }

    companion object {
        fun getTodayDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
