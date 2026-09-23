package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY timestampMillis ASC")
    fun getAllTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE dateString = :date ORDER BY timestampMillis ASC")
    fun getTasksByDate(date: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE dateString = :date ORDER BY timestampMillis ASC")
    suspend fun getTasksForDate(date: String): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY timestampMillis ASC")
    suspend fun getPendingTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' ORDER BY timestampMillis ASC")
    suspend fun searchTasks(query: String): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND hasReminder = 1 AND timestampMillis > :now")
    suspend fun getUpcomingWithReminder(now: Long): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: Int): Task?

    @Insert
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Delete
    suspend fun deleteTask(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Int)

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun getTaskCount(): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 1")
    suspend fun getCompletedTaskCount(): Int

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<Task>

    @Query("DELETE FROM tasks")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Task>)
}
