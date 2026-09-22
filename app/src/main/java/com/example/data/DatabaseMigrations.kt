package com.example.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val FROM_1 = upgrade(1)
    val FROM_2 = upgrade(2)
    private fun upgrade(from: Int) = object : Migration(from, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val taskColumns = linkedMapOf(
                "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", "title" to "TEXT NOT NULL", "description" to "TEXT NOT NULL",
                "category" to "TEXT NOT NULL", "priority" to "TEXT NOT NULL", "dateString" to "TEXT NOT NULL", "timeString" to "TEXT NOT NULL",
                "timestampMillis" to "INTEGER NOT NULL", "durationMinutes" to "INTEGER NOT NULL", "isCompleted" to "INTEGER NOT NULL",
                "hasReminder" to "INTEGER NOT NULL", "reminderMinutesBefore" to "INTEGER NOT NULL", "isRecurring" to "INTEGER NOT NULL",
                "recurringType" to "TEXT NOT NULL", "subtasksJson" to "TEXT NOT NULL", "voiceNoteText" to "TEXT NOT NULL", "createdTimestamp" to "INTEGER NOT NULL"
            )
            val taskDefaults = mapOf("category" to "'PERSONAL'", "priority" to "'MEDIUM'", "durationMinutes" to "30", "reminderMinutesBefore" to "15", "recurringType" to "'NONE'")
            rebuild(db, "tasks", taskColumns, taskDefaults)
            val habitColumns = linkedMapOf("id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", "title" to "TEXT NOT NULL", "category" to "TEXT NOT NULL",
                "targetDaysPerWeek" to "INTEGER NOT NULL", "currentStreak" to "INTEGER NOT NULL", "bestStreak" to "INTEGER NOT NULL",
                "lastCompletedDate" to "TEXT NOT NULL", "totalCompletedCount" to "INTEGER NOT NULL", "colorHex" to "TEXT NOT NULL")
            rebuild(db, "habits", habitColumns, mapOf("category" to "'HEALTH'", "targetDaysPerWeek" to "7", "colorHex" to "'#4CAF50'"))
        }
    }
    private fun rebuild(db: SupportSQLiteDatabase, table: String, columns: Map<String, String>, defaults: Map<String, String>) {
        val present = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor -> while(cursor.moveToNext()) present += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
        db.execSQL("CREATE TABLE `${table}_migrated` (${columns.entries.joinToString { "`${it.key}` ${it.value}" }})")
        if(present.isNotEmpty()) {
            val values = columns.map { (name, type) -> if(name in present) "`$name`" else defaults[name] ?: if(type.startsWith("TEXT")) "''" else "0" }
            db.execSQL("INSERT INTO `${table}_migrated` (${columns.keys.joinToString { "`$it`" }}) SELECT ${values.joinToString()} FROM `$table`")
            db.execSQL("DROP TABLE `$table`")
        }
        db.execSQL("ALTER TABLE `${table}_migrated` RENAME TO `$table`")
    }
}
