package com.example.jarvis.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.data.ConversationDao
import com.example.data.ConversationMessage
import com.example.data.Habit
import com.example.data.HabitDao
import com.example.data.MemoryDao
import com.example.data.MemoryEntry
import com.example.data.Migrations
import com.example.data.Reminder
import com.example.data.ReminderDao
import com.example.data.Task
import com.example.data.TaskDao
import com.example.data.UserSetting
import com.example.data.UserSettingsDao

/**
 * Single Room database for Jarvis. On device it is encrypted with SQLCipher (see
 * [com.example.jarvis.security.DatabaseEncryption]); tests build it without an open-helper factory.
 */
@Database(
    entities = [
        Task::class,
        Habit::class,
        Reminder::class,
        MemoryEntry::class,
        ConversationMessage::class,
        UserSetting::class
    ],
    version = 4,
    exportSchema = true
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun habitDao(): HabitDao
    abstract fun reminderDao(): ReminderDao
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        /** File name kept from the original app so existing data is migrated, not lost. */
        const val NAME = "kun_tartibi_db"

        fun build(context: Context, factory: SupportSQLiteOpenHelper.Factory?, name: String = NAME): MemoryDatabase =
            Room.databaseBuilder(context.applicationContext, MemoryDatabase::class.java, name)
                .apply { if (factory != null) openHelperFactory(factory) }
                .addMigrations(*Migrations.ALL)
                // v1/v2 only ever existed in pre-release builds.
                .fallbackToDestructiveMigrationFrom(true, 1, 2)
                .build()

        fun inMemory(context: Context): MemoryDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, MemoryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
