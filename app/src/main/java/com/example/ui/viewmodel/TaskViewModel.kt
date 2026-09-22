package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Habit
import com.example.data.HabitDao
import com.example.data.Task
import com.example.data.TaskCategory
import com.example.data.TaskPriority
import com.example.notification.NotificationHelper
import com.example.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TaskViewModel(private val repository: TaskRepository) : ViewModel() {

    private val _selectedDate = MutableStateFlow(TaskRepository.getTodayDateString())
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _selectedPriority = MutableStateFlow<String?>(null) // null = all, HIGH, MEDIUM, LOW
    val selectedPriority: StateFlow<String?> = _selectedPriority.asStateFlow()

    private val _statusFilter = MutableStateFlow("ALL") // "ALL", "COMPLETED", "PENDING"
    val statusFilter: StateFlow<String> = _statusFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val allTasks: StateFlow<List<Task>> = repository.allTasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allHabits: StateFlow<List<Habit>> = repository.allHabits
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val tasksForSelectedDate: StateFlow<List<Task>> = combine(
        allTasks,
        _selectedDate,
        _selectedCategory,
        _selectedPriority,
        _statusFilter,
        _searchQuery
    ) { tasks, date, category, priority, status, query ->
        tasks.filter { task ->
            val matchesDate = task.dateString == date
            val matchesCategory = category == null || task.category == category
            val matchesPriority = priority == null || task.priority == priority
            val matchesStatus = when (status) {
                "COMPLETED" -> task.isCompleted
                "PENDING" -> !task.isCompleted
                else -> true
            }
            val matchesSearch = query.isEmpty() ||
                    task.title.contains(query, ignoreCase = true) ||
                    task.description.contains(query, ignoreCase = true)
            matchesDate && matchesCategory && matchesPriority && matchesStatus && matchesSearch
        }.sortedBy { it.timestampMillis }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            repository.initializeDefaultTasksIfEmpty()
        }
    }

    fun setSelectedDate(dateString: String) {
        _selectedDate.value = dateString
    }

    fun setSelectedCategory(categoryName: String?) {
        _selectedCategory.value = categoryName
    }

    fun setSelectedPriority(priorityName: String?) {
        _selectedPriority.value = priorityName
    }

    fun setStatusFilter(filter: String) {
        _statusFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleHabitForToday(habit: Habit) {
        viewModelScope.launch {
            repository.toggleHabitForToday(habit)
        }
    }

    fun addHabit(title: String, category: String = TaskCategory.HEALTH.name) {
        viewModelScope.launch {
            repository.insertHabit(Habit(title = title, category = category))
        }
    }

    fun deleteHabit(habit: Habit) {
        viewModelScope.launch {
            repository.deleteHabit(habit)
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            repository.updateTask(task.copy(isCompleted = !task.isCompleted))
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }

    fun addTask(
        title: String,
        description: String,
        category: String,
        priority: String,
        dateString: String,
        timeString: String,
        durationMinutes: Int,
        hasReminder: Boolean,
        reminderMinutesBefore: Int,
        recurringType: String = "NONE"
    ) {
        viewModelScope.launch {
            val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val initialCal = Calendar.getInstance()
            try {
                dateSdf.parse(dateString)?.let { initialCal.time = it }
            } catch (_: Exception) {}

            val repeatCount = when (recurringType) {
                "DAILY_7" -> 7
                "DAILY_14" -> 14
                "DAILY_30" -> 30
                "WEEKLY_4" -> 4
                "MONTHLY_3" -> 3
                else -> 1
            }

            for (i in 0 until repeatCount) {
                val taskCal = initialCal.clone() as Calendar
                when {
                    recurringType.startsWith("DAILY") -> taskCal.add(Calendar.DAY_OF_YEAR, i)
                    recurringType == "WEEKLY_4" -> taskCal.add(Calendar.WEEK_OF_YEAR, i)
                    recurringType == "MONTHLY_3" -> taskCal.add(Calendar.MONTH, i)
                }

                val curDateStr = dateSdf.format(taskCal.time)
                val timestamp = parseTimeToMillis(curDateStr, timeString)

                val newTask = Task(
                    title = title,
                    description = description,
                    category = category,
                    priority = priority,
                    dateString = curDateStr,
                    timeString = timeString,
                    timestampMillis = timestamp,
                    durationMinutes = durationMinutes,
                    isCompleted = false,
                    hasReminder = hasReminder,
                    reminderMinutesBefore = reminderMinutesBefore,
                    isRecurring = repeatCount > 1,
                    recurringType = recurringType
                )
                repository.insertTask(newTask)
            }
        }
    }

    fun updateTask(task: Task) {
        viewModelScope.launch {
            repository.updateTask(task)
        }
    }

    fun triggerTestNotification(context: Context) {
        NotificationHelper.showNotification(
            context = context,
            title = "⏰ Kun Tartibi: Test Budilnik & Eslatmasi",
            message = "Budilnik va eslatmalar tizimi muvaffaqiyatli ishlamoqda! Vazifa vaqtida budilnik chalinadi va xabar yuboriladi.",
            notificationId = 9999
        )
    }

    fun playTestAlarmSound(context: Context) {
        NotificationHelper.playAlarmSound(context, 4000L)
    }

    private fun parseTimeToMillis(dateStr: String, timeStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val date = sdf.parse("$dateStr $timeStr")
            date?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

class TaskViewModelFactory(private val repository: TaskRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TaskViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
