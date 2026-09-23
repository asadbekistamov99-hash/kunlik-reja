package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY id DESC")
    fun getAllHabits(): Flow<List<Habit>>

    @Query("SELECT * FROM habits ORDER BY id DESC")
    suspend fun getAll(): List<Habit>

    @Query("SELECT * FROM habits WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun findByTitle(title: String): Habit?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabit(habit: Habit): Long

    @Update
    suspend fun updateHabit(habit: Habit)

    @Delete
    suspend fun deleteHabit(habit: Habit)

    @Query("SELECT COUNT(*) FROM habits")
    suspend fun getHabitCount(): Int

    @Query("DELETE FROM habits")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Habit>)
}
