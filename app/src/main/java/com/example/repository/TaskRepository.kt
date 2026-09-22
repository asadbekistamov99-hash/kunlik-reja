package com.example.repository

import android.content.Context
import com.example.data.Habit
import com.example.data.HabitDao
import com.example.data.Task
import com.example.data.TaskValidation
import com.example.data.TaskCategory
import com.example.data.TaskDao
import com.example.data.TaskPriority
import com.example.notification.NotificationHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TaskRepository(
    private val taskDao: TaskDao,
    private val habitDao: HabitDao? = null,
    private val context: Context
) {
    val allTasks: Flow<List<Task>> = taskDao.getAllTasks()
    val allHabits: Flow<List<Habit>> = habitDao?.getAllHabits() ?: flowOf(emptyList())

    suspend fun insertHabit(habit: Habit): Long {
        return habitDao?.insertHabit(habit) ?: 0L
    }

    suspend fun updateHabit(habit: Habit) {
        habitDao?.updateHabit(habit)
    }

    suspend fun deleteHabit(habit: Habit) {
        habitDao?.deleteHabit(habit)
    }

    suspend fun toggleHabitForToday(habit: Habit) {
        val todayStr = getTodayDateString()
        if (habit.lastCompletedDate == todayStr) {
            // Uncheck for today
            val updated = habit.copy(
                lastCompletedDate = if(habit.currentStreak > 1) SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(
                    Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time) else "",
                currentStreak = (habit.currentStreak - 1).coerceAtLeast(0),
                totalCompletedCount = (habit.totalCompletedCount - 1).coerceAtLeast(0)
            )
            updateHabit(updated)
        } else {
            // Check for today
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(yesterday.time)
            val newStreak = if (habit.lastCompletedDate == yesterdayStr) habit.currentStreak + 1 else 1
            val updated = habit.copy(
                lastCompletedDate = todayStr,
                currentStreak = newStreak,
                bestStreak = maxOf(habit.bestStreak, newStreak),
                totalCompletedCount = habit.totalCompletedCount + 1
            )
            updateHabit(updated)
        }
    }

    fun getTasksByDate(date: String): Flow<List<Task>> = taskDao.getTasksByDate(date)

    suspend fun getTaskById(id: Int): Task? = taskDao.getTaskById(id)

    suspend fun insertTask(task: Task): Long {
        val validTask = TaskValidation.normalize(task)
        val id = taskDao.insertTask(validTask)
        val newTask = validTask.copy(id = id.toInt())
        if (newTask.hasReminder && !newTask.isCompleted) {
            NotificationHelper.scheduleTaskAlarm(context, newTask)
        }
        return id
    }

    suspend fun updateTask(task: Task) {
        val validTask = TaskValidation.normalize(task)
        taskDao.updateTask(validTask)
        NotificationHelper.cancelTaskAlarm(context, task.id)
        if (validTask.hasReminder && !validTask.isCompleted) {
            NotificationHelper.scheduleTaskAlarm(context, validTask)
        } else {
            NotificationHelper.cancelTaskAlarm(context, task.id)
        }
    }

    suspend fun deleteTask(task: Task) {
        taskDao.deleteTask(task)
        NotificationHelper.cancelTaskAlarm(context, task.id)
    }

    companion object {
        fun getTodayDateString(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            return sdf.format(Date())
        }
    }
}
