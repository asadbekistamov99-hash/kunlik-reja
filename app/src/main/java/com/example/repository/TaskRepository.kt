package com.example.repository

import android.content.Context
import com.example.data.Habit
import com.example.data.HabitDao
import com.example.data.Task
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
                lastCompletedDate = "",
                currentStreak = (habit.currentStreak - 1).coerceAtLeast(0),
                totalCompletedCount = (habit.totalCompletedCount - 1).coerceAtLeast(0)
            )
            updateHabit(updated)
        } else {
            // Check for today
            val newStreak = habit.currentStreak + 1
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
        val id = taskDao.insertTask(task)
        val newTask = task.copy(id = id.toInt())
        if (newTask.hasReminder) {
            NotificationHelper.scheduleTaskAlarm(context, newTask)
        }
        return id
    }

    suspend fun updateTask(task: Task) {
        taskDao.updateTask(task)
        if (task.hasReminder && !task.isCompleted) {
            NotificationHelper.scheduleTaskAlarm(context, task)
        } else {
            NotificationHelper.cancelTaskAlarm(context, task.id)
        }
    }

    suspend fun deleteTask(task: Task) {
        taskDao.deleteTask(task)
        NotificationHelper.cancelTaskAlarm(context, task.id)
    }

    suspend fun initializeDefaultTasksIfEmpty() {
        if (taskDao.getTaskCount() == 0) {
            val todayStr = getTodayDateString()
            val defaultTasks = listOf(
                createSampleTask(
                    title = "Ertalabki badantarbiya va tetiklik",
                    description = "15 daqiqa badantarbiya va 2 stakan suv ichish",
                    category = TaskCategory.HEALTH.name,
                    priority = TaskPriority.HIGH.name,
                    dateString = todayStr,
                    timeString = "07:00",
                    hour = 7, minute = 0,
                    duration = 30,
                    isCompleted = true
                ),
                createSampleTask(
                    title = "Kunlik rejalarni tasdiqlash",
                    description = "Bugungi muhim vazifalar ro'yxatini ko'rib chiqish",
                    category = TaskCategory.WORK.name,
                    priority = TaskPriority.HIGH.name,
                    dateString = todayStr,
                    timeString = "08:15",
                    hour = 8, minute = 15,
                    duration = 20,
                    isCompleted = true
                ),
                createSampleTask(
                    title = "Loyiha va o'quv mashg'ulotlari",
                    description = "Asosiy vazifalar ustida diqqat bilan ishlash",
                    category = TaskCategory.STUDY.name,
                    priority = TaskPriority.HIGH.name,
                    dateString = todayStr,
                    timeString = "10:00",
                    hour = 10, minute = 0,
                    duration = 90,
                    isCompleted = false
                ),
                createSampleTask(
                    title = "Tushlik va dam olish",
                    description = "Foydali tushlik va toza havoda sayr qilish",
                    category = TaskCategory.HEALTH.name,
                    priority = TaskPriority.LOW.name,
                    dateString = todayStr,
                    timeString = "13:00",
                    hour = 13, minute = 0,
                    duration = 45,
                    isCompleted = false
                ),
                createSampleTask(
                    title = "Kitob o'qish (Mutolaa vaqti)",
                    description = "Kamida 25-30 sahifa foydali adabiyot o'qish",
                    category = TaskCategory.PERSONAL.name,
                    priority = TaskPriority.MEDIUM.name,
                    dateString = todayStr,
                    timeString = "19:30",
                    hour = 19, minute = 30,
                    duration = 40,
                    isCompleted = false
                ),
                createSampleTask(
                    title = "Kun natijalarini sarhisob qilish",
                    description = "Bajarilgan vazifalarni belgilash va ertangi kunni rejalash",
                    category = TaskCategory.PERSONAL.name,
                    priority = TaskPriority.LOW.name,
                    dateString = todayStr,
                    timeString = "21:30",
                    hour = 21, minute = 30,
                    duration = 20,
                    isCompleted = false
                )
            )

            for (t in defaultTasks) {
                insertTask(t)
            }
        }

        if (habitDao != null && habitDao.getHabitCount() == 0) {
            val sampleHabits = listOf(
                Habit(title = "Ertalab 2 stakan suv ichish", category = TaskCategory.HEALTH.name, currentStreak = 3, bestStreak = 7, totalCompletedCount = 12),
                Habit(title = "10,000 qadam yurish", category = TaskCategory.HEALTH.name, currentStreak = 5, bestStreak = 10, totalCompletedCount = 18),
                Habit(title = "30 daqiqa kitob mutolaasi", category = TaskCategory.PERSONAL.name, currentStreak = 2, bestStreak = 5, totalCompletedCount = 8),
                Habit(title = "Ingliz tili lug'at (10 ta so'z)", category = TaskCategory.STUDY.name, currentStreak = 4, bestStreak = 6, totalCompletedCount = 14)
            )
            for (h in sampleHabits) {
                insertHabit(h)
            }
        }
    }

    private fun createSampleTask(
        title: String,
        description: String,
        category: String,
        priority: String,
        dateString: String,
        timeString: String,
        hour: Int,
        minute: Int,
        duration: Int,
        isCompleted: Boolean
    ): Task {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return Task(
            title = title,
            description = description,
            category = category,
            priority = priority,
            dateString = dateString,
            timeString = timeString,
            timestampMillis = cal.timeInMillis,
            durationMinutes = duration,
            isCompleted = isCompleted,
            hasReminder = true,
            reminderMinutesBefore = 15
        )
    }

    companion object {
        fun getTodayDateString(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            return sdf.format(Date())
        }
    }
}
