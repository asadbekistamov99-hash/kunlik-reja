package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Standalone or task-bound reminder scheduled through AlarmManager. */
@Entity(tableName = "reminders", indices = [Index("triggerAtMillis"), Index("taskId")])
data class Reminder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Int? = null,
    val title: String,
    val message: String = "",
    val triggerAtMillis: Long,
    /** 0 = one-shot, otherwise the repeat interval in minutes (1440 = daily). */
    val repeatIntervalMinutes: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

enum class MemoryType { PREFERENCE, HABIT, FACT, IMPORTANT, PROFILE, COMMAND }

/** Long-term memory entry. [key] de-duplicates facts ("name", "habit:sport" ...). */
@Entity(tableName = "memories", indices = [Index(value = ["key"], unique = true), Index("type")])
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val key: String,
    val content: String,
    val importance: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0
)

enum class ConversationRole { USER, ASSISTANT }

@Entity(tableName = "conversations", indices = [Index("timestamp"), Index("sessionId")])
data class ConversationMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val role: String,
    val text: String,
    val intent: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_settings")
data class UserSetting(
    @PrimaryKey
    val key: String,
    val value: String
)
