package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.jarvis.*
import com.example.repository.TaskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = AppDatabase.getDatabase(context)
    private val runtime = JarvisRuntime.get(context)
    private val repo = TaskRepository(db.taskDao(), db.habitDao(), context)
    @Before fun reset() = runBlocking {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        runtime.command("bekor qil")
        Unit
    }
    private suspend fun add(title: String) = repo.insertTask(Task(title = title, dateString = "2027-01-01", timeString = "10:00", timestampMillis = 0, hasReminder = false)).toInt()
    @Test fun completionIsIdempotent() = runBlocking {
        val id = add("Kitob")
        runtime.command("bajarildi kitob")
        runtime.command("bajarildi kitob")
        assertTrue(repo.getTaskById(id)!!.isCompleted)
    }
    @Test fun ambiguousDeletionDoesNotMutateAndExactDeletionNeedsConfirmation() = runBlocking {
        val id = add("Kitob"); add("Kitob")
        assertTrue(runtime.command("o'chir kitob").contains("Bir nechta"))
        assertEquals(2, repo.allTasks.first().size)
        assertTrue(runtime.command("o'chir #$id").contains("tasdiqla"))
        assertNotNull(repo.getTaskById(id))
        runtime.command("tasdiqla")
        assertNull(repo.getTaskById(id))
        assertEquals(1, repo.allTasks.first().size)
    }
    @Test fun cancelAndUnrelatedCommandsInvalidateDeletion() = runBlocking {
        val id = add("Kitob")
        runtime.command("o'chir #$id"); runtime.command("bekor qil"); runtime.command("tasdiqla")
        assertNotNull(repo.getTaskById(id))
        runtime.command("o'chir #$id"); runtime.command("statistika"); runtime.command("tasdiqla")
        assertNotNull(repo.getTaskById(id))
    }
    @Test fun updateRecomputesTimestampAndRejectsBadDates() = runBlocking {
        val id = add("Majlis")
        runtime.command("ko'chir #$id / 2027-01-02 11:30")
        assertEquals(TaskValidation.timestamp("2027-01-02", "11:30"), repo.getTaskById(id)!!.timestampMillis)
        runtime.command("ko'chir #$id / 2027-02-30 11:30")
        assertEquals("2027-01-02", repo.getTaskById(id)!!.dateString)
    }
    @Test fun invalidAddDoesNotClaimSuccessOrWrite() = runBlocking {
        assertTrue(runtime.command("ertaga 25:99 da dars qo'sh").contains("noto'g'ri"))
        assertTrue(repo.allTasks.first().isEmpty())
    }
    @Test fun focusSurvivesReopeningAndPause() {
        FocusSession.start(context, 25)
        assertTrue(FocusSession.running(context))
        assertTrue(FocusSession.remaining(context) in 1498..1500)
        FocusSession.pause(context)
        assertFalse(FocusSession.running(context))
        FocusSession.resume(context)
        assertTrue(FocusSession.running(context))
        FocusSession.stop(context)
        assertEquals(0, FocusSession.remaining(context))
    }
}
