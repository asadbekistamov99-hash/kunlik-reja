package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.DatabaseMigrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {
    @Test fun olderTasksSurviveBothSupportedMigrations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for(version in 1..2) {
            val name = "migration-$version.db"
            context.deleteDatabase(name)
            val path = context.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, dateString TEXT NOT NULL, timeString TEXT NOT NULL, timestampMillis INTEGER NOT NULL)")
                old.execSQL("INSERT INTO tasks VALUES(7, 'Saqlangan vazifa', '2026-09-22', '09:00', 1234)")
                old.version = version
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(DatabaseMigrations.FROM_1, DatabaseMigrations.FROM_2).build()
            try {
                val task = migrated.taskDao().getTaskById(7)
                assertEquals("Saqlangan vazifa", task!!.title)
                assertEquals("2026-09-22", task.dateString)
                assertEquals("NONE", task.recurringType)
                assertEquals(0, migrated.habitDao().getHabitCount())
            } finally { migrated.close(); context.deleteDatabase(name) }
        }
    }
}
