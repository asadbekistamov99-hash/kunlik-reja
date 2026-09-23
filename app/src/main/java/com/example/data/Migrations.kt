package com.example.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history:
 *  v3 — tasks + habits (Kun Tartibi planner)
 *  v4 — Jarvis Ultra: reminders, memories, conversations, user_settings
 */
object Migrations {

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`taskId` INTEGER, `title` TEXT NOT NULL, `message` TEXT NOT NULL, `triggerAtMillis` INTEGER NOT NULL, " +
                    "`repeatIntervalMinutes` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_triggerAtMillis` ON `reminders` (`triggerAtMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_taskId` ON `reminders` (`taskId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`type` TEXT NOT NULL, `key` TEXT NOT NULL, `content` TEXT NOT NULL, `importance` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_key` ON `memories` (`key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_type` ON `memories` (`type`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `intent` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_timestamp` ON `conversations` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_sessionId` ON `conversations` (`sessionId`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `user_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_3_4)
}
