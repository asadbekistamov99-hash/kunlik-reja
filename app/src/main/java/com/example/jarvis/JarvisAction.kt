package com.example.jarvis

import com.example.data.TaskCategory
import com.example.data.TaskPriority

enum class JarvisActionType {
    ADD_TASK,
    TOGGLE_COMPLETED,
    DELETE_TASK,
    SEARCH_TASKS,
    READ_SCHEDULE,
    GENERAL_RESPONSE
}

data class JarvisParsedResponse(
    val action: JarvisActionType,
    val title: String = "",
    val description: String = "",
    val category: String = TaskCategory.PERSONAL.name,
    val priority: String = TaskPriority.MEDIUM.name,
    val dateString: String = "",
    val timeString: String = "09:00",
    val durationMinutes: Int = 30,
    val searchQuery: String = "",
    val targetTaskTitle: String = "",
    val responseMessage: String = "Tushundim, vazifa bajarildi."
)
