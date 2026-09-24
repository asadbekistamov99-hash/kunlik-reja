package com.example.data

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

object TaskValidation {
    fun timestamp(date: String, time: String): Long {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && Regex("\\d{2}:\\d{2}").matches(time)) { "Sana yyyy-MM-dd, vaqt HH:mm ko'rinishida bo'lishi kerak." }
        val input = "$date $time"
        val pos = ParsePosition(0)
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { isLenient = false }.parse(input, pos)
        require(parsed != null && pos.index == input.length) { "Sana yoki vaqt noto'g'ri." }
        return parsed.time
    }
    fun normalize(task: Task): Task {
        require(task.title.isNotBlank() && task.title.length <= 200) { "Vazifa nomi 1–200 belgidan iborat bo'lsin." }
        require(task.durationMinutes in 1..1440) { "Davomiylik 1–1440 daqiqa bo'lsin." }
        require(task.reminderMinutesBefore in 0..10080) { "Eslatma vaqti noto'g'ri." }
        require(TaskCategory.entries.any { it.name == task.category }) { "Toifa noto'g'ri." }
        require(TaskPriority.entries.any { it.name == task.priority }) { "Muhimlik noto'g'ri." }
        return task.copy(title = task.title.trim(), timestampMillis = timestamp(task.dateString, task.timeString))
    }
}
