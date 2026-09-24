package com.example.integration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.TestJarvisApplication
import com.example.data.ConversationMessage
import com.example.data.Habit
import com.example.data.MemoryEntry
import com.example.data.Reminder
import com.example.data.Task
import com.example.data.UserSetting
import com.jarvis.memory.BackupCodec
import com.jarvis.memory.BackupSerializer
import com.jarvis.memory.BackupManager
import com.jarvis.memory.MemoryDatabase
import com.jarvis.security.DatabaseEncryption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class DatabaseAndBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Creates the exact v3 schema shipped by the original Kun Tartibi app. */
    private fun createV3(name: String): File {
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `priority` TEXT NOT NULL, `dateString` TEXT NOT NULL, `timeString` TEXT NOT NULL, `timestampMillis` INTEGER NOT NULL, `durationMinutes` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, `hasReminder` INTEGER NOT NULL, `reminderMinutesBefore` INTEGER NOT NULL, `isRecurring` INTEGER NOT NULL, `recurringType` TEXT NOT NULL, `subtasksJson` TEXT NOT NULL, `voiceNoteText` TEXT NOT NULL, `createdTimestamp` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `habits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `targetDaysPerWeek` INTEGER NOT NULL, `currentStreak` INTEGER NOT NULL, `bestStreak` INTEGER NOT NULL, `lastCompletedDate` TEXT NOT NULL, `totalCompletedCount` INTEGER NOT NULL, `colorHex` TEXT NOT NULL)")
            db.execSQL("INSERT INTO tasks VALUES(7,'Saqlangan vazifa','','WORK','HIGH','2026-09-22','09:00',1234,30,0,1,15,0,'NONE','','',1)")
            db.execSQL("INSERT INTO habits VALUES(3,'Sport','HEALTH',7,4,9,'2026-09-22',20,'#4CAF50')")
            db.version = 3
        }
        return path
    }

    @Test fun `v3 planner data survives migration to v4`() = runBlocking {
        val name = "migration-test.db"
        val file = createV3(name)
        assertTrue(DatabaseEncryption.isPlaintext(file))
        val db = MemoryDatabase.build(context, null, name)
        try {
            val task = db.taskDao().getTaskById(7)!!
            assertEquals("Saqlangan vazifa", task.title)
            assertEquals("HIGH", task.priority)
            assertEquals(4, db.habitDao().getAll().single().currentStreak)
            // New v4 tables are usable.
            db.memoryDao().upsert(MemoryEntry(type = "FACT", key = "k", content = "c"))
            db.reminderDao().insert(Reminder(title = "r", triggerAtMillis = 1))
            db.conversationDao().insert(ConversationMessage(sessionId = "s", role = "USER", text = "salom"))
            db.userSettingsDao().put(UserSetting("a", "b"))
            assertEquals(1, db.memoryDao().getAll().size)
            assertEquals("b", db.userSettingsDao().get("a"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `plaintext header detection`() {
        val f = File(context.cacheDir, "notdb.bin").apply { writeBytes(ByteArray(64) { 7 }) }
        assertFalse(DatabaseEncryption.isPlaintext(f))
        assertFalse(DatabaseEncryption.isPlaintext(File(context.cacheDir, "missing")))
    }

    @Test fun `backup serializes every table and restores atomically`() = runBlocking {
        val source = MemoryDatabase.inMemory(context)
        source.taskDao().insertTask(Task(id = 5, title = "Vazifa", dateString = "2026-09-23", timeString = "10:00", timestampMillis = 42, isCompleted = true))
        source.habitDao().insertHabit(Habit(id = 2, title = "Suv", currentStreak = 3))
        source.reminderDao().insert(Reminder(id = 9, taskId = 5, title = "Eslat", triggerAtMillis = 99, repeatIntervalMinutes = 1440))
        source.memoryDao().upsert(MemoryEntry(id = 4, type = "PROFILE", key = "profile:name", content = "Asadbek", importance = 3))
        source.conversationDao().insert(ConversationMessage(id = 1, sessionId = "s", role = "USER", text = "salom"))
        source.userSettingsDao().put(UserSetting("user_name", "Asadbek"))
        source.userSettingsDao().put(UserSetting("google_connected", "true"))
        val snapshot = BackupManager(context, source).snapshot()
        assertFalse("device-local keys are not exported", snapshot.settings.any { it.key == "google_connected" })

        val json = BackupSerializer.toJson(snapshot)
        val encrypted = BackupCodec.encrypt(json.toByteArray(), "parol-123".toCharArray())
        val restoredSnap = BackupSerializer.fromJson(String(BackupCodec.decrypt(encrypted, "parol-123".toCharArray())))
        assertEquals(snapshot, restoredSnap)

        val target = MemoryDatabase.inMemory(context)
        target.taskDao().insertTask(Task(title = "Eski", dateString = "", timeString = "", timestampMillis = 0))
        target.userSettingsDao().put(UserSetting("google_connected", "false"))
        BackupManager(context, target).restore(restoredSnap)
        assertEquals(listOf("Vazifa"), target.taskDao().getAll().map { it.title })
        assertEquals(5, target.reminderDao().getAll().single().taskId)
        assertEquals("Asadbek", target.memoryDao().getByKey("profile:name")!!.content)
        assertEquals("false", target.userSettingsDao().get("google_connected"))
        assertEquals("Asadbek", target.userSettingsDao().get("user_name"))
        source.close(); target.close()
    }
}
