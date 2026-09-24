package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskPriority(val labelUz: String, val level: Int) {
    HIGH("Yuqori", 3),
    MEDIUM("O'rtacha", 2),
    LOW("Past", 1)
}

enum class TaskCategory(val labelUz: String, val iconName: String) {
    WORK("Ish", "work"),
    STUDY("O'qish", "school"),
    PERSONAL("Shaxsiy", "person"),
    HEALTH("Salomatlik", "fitness"),
    HOME("Uy-ro'zg'or", "home"),
    OTHER("Boshqa", "category")
}

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val description: String = "",
    val category: String = TaskCategory.PERSONAL.name,
    val priority: String = TaskPriority.MEDIUM.name,
    val dateString: String, // Format: YYYY-MM-DD
    val timeString: String, // Format: HH:mm (e.g. 08:30)
    val timestampMillis: Long,
    val durationMinutes: Int = 30,
    val isCompleted: Boolean = false,
    val hasReminder: Boolean = true,
    val reminderMinutesBefore: Int = 15, // 0 = at event time, 15 = 15 mins before
    val isRecurring: Boolean = false,
    val recurringType: String = "NONE", // "NONE", "DAILY_7", "DAILY_14", "DAILY_30", "WEEKLY_4", "MONTHLY_3"
    val subtasksJson: String = "", // Format: "Title 1|1,Title 2|0"
    val voiceNoteText: String = "",
    val createdTimestamp: Long = System.currentTimeMillis(),
    /** Optional due date (YYYY-MM-DD); empty when the task has no deadline. */
    @ColumnInfo(defaultValue = "''")
    val deadline: String = "",
    /** When the task was marked done (epoch millis), 0 while pending. Feeds work-pattern analysis. */
    @ColumnInfo(defaultValue = "0")
    val completedAt: Long = 0
)

data class SubtaskItem(
    val title: String,
    val isCompleted: Boolean = false
) {
    fun toSerialized(): String = "$title|${if (isCompleted) 1 else 0}"

    companion object {
        fun parseList(serialized: String): List<SubtaskItem> {
            if (serialized.isBlank()) return emptyList()
            return serialized.split(";;").mapNotNull { part ->
                val tokens = part.split("|")
                if (tokens.size >= 2) {
                    SubtaskItem(tokens[0], tokens[1] == "1")
                } else if (tokens.isNotEmpty() && tokens[0].isNotBlank()) {
                    SubtaskItem(tokens[0], false)
                } else null
            }
        }

        fun serializeList(list: List<SubtaskItem>): String {
            return list.joinToString(";;") { it.toSerialized() }
        }
    }
}
