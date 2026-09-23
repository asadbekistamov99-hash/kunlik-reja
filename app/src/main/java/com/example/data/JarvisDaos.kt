package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY triggerAtMillis ASC")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE isActive = 1 ORDER BY triggerAtMillis ASC")
    suspend fun getActive(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<Reminder>

    @Query("DELETE FROM reminders")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Reminder>)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntry>>

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    suspend fun getAll(): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY updatedAt DESC")
    suspend fun getByType(type: String): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): MemoryEntry?

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' ORDER BY importance DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 10): List<MemoryEntry>

    @Upsert
    suspend fun upsert(entry: MemoryEntry): Long

    @Query("UPDATE memories SET accessCount = accessCount + 1 WHERE id IN (:ids)")
    suspend fun markAccessed(ids: List<Long>)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories WHERE type = :type AND id NOT IN (SELECT id FROM memories WHERE type = :type ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun trimType(type: String, keep: Int)

    @Query("DELETE FROM memories")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MemoryEntry>)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM (SELECT * FROM conversations ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC")
    fun observeRecent(limit: Int = 200): Flow<List<ConversationMessage>>

    @Query("SELECT * FROM (SELECT * FROM conversations ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC")
    suspend fun getRecent(limit: Int): List<ConversationMessage>

    @Insert
    suspend fun insert(message: ConversationMessage): Long

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("DELETE FROM conversations WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM conversations")
    suspend fun getAll(): List<ConversationMessage>

    @Query("DELETE FROM conversations")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ConversationMessage>)
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings")
    fun observeAll(): Flow<List<UserSetting>>

    @Query("SELECT * FROM user_settings")
    suspend fun getAll(): List<UserSetting>

    @Query("SELECT value FROM user_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: UserSetting)

    @Query("DELETE FROM user_settings WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM user_settings")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<UserSetting>)
}
