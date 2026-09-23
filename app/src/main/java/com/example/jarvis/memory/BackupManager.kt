package com.example.jarvis.memory

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.ConversationMessage
import com.example.data.Habit
import com.example.data.MemoryEntry
import com.example.data.Reminder
import com.example.data.Task
import com.example.data.UserSetting
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Password-protected backup format:
 *   "JRVSBK1" magic | 16-byte salt | 12-byte IV | AES-256-GCM(JSON)
 * The key is derived with PBKDF2-HMAC-SHA256, so a backup can be restored on any device.
 */
object BackupCodec {
    private val MAGIC = "JRVSBK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 120_000

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        val header = MAGIC.size + 16 + 12
        if (data.size <= header || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("Bu fayl Jarvis zaxira nusxasi emas")
        }
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = data.copyOfRange(MAGIC.size + 16, header)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(MAGIC)
            cipher.doFinal(data, header, data.size - header)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw BackupException("Parol noto'g'ri yoki fayl buzilgan", e)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}

/** Serializes every table to JSON and back. */
object BackupSerializer {
    const val FORMAT_VERSION = 1

    data class Snapshot(
        val tasks: List<Task>,
        val habits: List<Habit>,
        val reminders: List<Reminder>,
        val memories: List<MemoryEntry>,
        val conversations: List<ConversationMessage>,
        val settings: List<UserSetting>
    )

    fun toJson(s: Snapshot): String = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("createdAt", System.currentTimeMillis())
        put("tasks", JSONArray(s.tasks.map { t ->
            JSONObject().put("id", t.id).put("title", t.title).put("description", t.description)
                .put("category", t.category).put("priority", t.priority).put("dateString", t.dateString)
                .put("timeString", t.timeString).put("timestampMillis", t.timestampMillis)
                .put("durationMinutes", t.durationMinutes).put("isCompleted", t.isCompleted)
                .put("hasReminder", t.hasReminder).put("reminderMinutesBefore", t.reminderMinutesBefore)
                .put("isRecurring", t.isRecurring).put("recurringType", t.recurringType)
                .put("subtasksJson", t.subtasksJson).put("voiceNoteText", t.voiceNoteText)
                .put("createdTimestamp", t.createdTimestamp)
        }))
        put("habits", JSONArray(s.habits.map { h ->
            JSONObject().put("id", h.id).put("title", h.title).put("category", h.category)
                .put("targetDaysPerWeek", h.targetDaysPerWeek).put("currentStreak", h.currentStreak)
                .put("bestStreak", h.bestStreak).put("lastCompletedDate", h.lastCompletedDate)
                .put("totalCompletedCount", h.totalCompletedCount).put("colorHex", h.colorHex)
        }))
        put("reminders", JSONArray(s.reminders.map { r ->
            JSONObject().put("id", r.id).put("taskId", r.taskId ?: JSONObject.NULL).put("title", r.title)
                .put("message", r.message).put("triggerAtMillis", r.triggerAtMillis)
                .put("repeatIntervalMinutes", r.repeatIntervalMinutes).put("isActive", r.isActive)
                .put("createdAt", r.createdAt)
        }))
        put("memories", JSONArray(s.memories.map { m ->
            JSONObject().put("id", m.id).put("type", m.type).put("key", m.key).put("content", m.content)
                .put("importance", m.importance).put("createdAt", m.createdAt).put("updatedAt", m.updatedAt)
                .put("accessCount", m.accessCount)
        }))
        put("conversations", JSONArray(s.conversations.map { c ->
            JSONObject().put("id", c.id).put("sessionId", c.sessionId).put("role", c.role).put("text", c.text)
                .put("intent", c.intent).put("timestamp", c.timestamp)
        }))
        put("settings", JSONArray(s.settings.map { JSONObject().put("key", it.key).put("value", it.value) }))
    }.toString()

    fun fromJson(json: String): Snapshot {
        val root = JSONObject(json)
        val format = root.optInt("format", -1)
        if (format < 1 || format > FORMAT_VERSION) throw BackupException("Qo'llab-quvvatlanmaydigan zaxira formati: $format")
        fun arr(name: String) = root.optJSONArray(name) ?: JSONArray()
        fun <T> JSONArray.mapObjects(f: (JSONObject) -> T) = (0 until length()).map { f(getJSONObject(it)) }
        return Snapshot(
            tasks = arr("tasks").mapObjects { o ->
                Task(
                    id = o.getInt("id"), title = o.getString("title"), description = o.optString("description"),
                    category = o.optString("category", "PERSONAL"), priority = o.optString("priority", "MEDIUM"),
                    dateString = o.getString("dateString"), timeString = o.getString("timeString"),
                    timestampMillis = o.getLong("timestampMillis"), durationMinutes = o.optInt("durationMinutes", 30),
                    isCompleted = o.optBoolean("isCompleted"), hasReminder = o.optBoolean("hasReminder", true),
                    reminderMinutesBefore = o.optInt("reminderMinutesBefore", 15), isRecurring = o.optBoolean("isRecurring"),
                    recurringType = o.optString("recurringType", "NONE"), subtasksJson = o.optString("subtasksJson"),
                    voiceNoteText = o.optString("voiceNoteText"), createdTimestamp = o.optLong("createdTimestamp")
                )
            },
            habits = arr("habits").mapObjects { o ->
                Habit(
                    id = o.getInt("id"), title = o.getString("title"), category = o.optString("category", "HEALTH"),
                    targetDaysPerWeek = o.optInt("targetDaysPerWeek", 7), currentStreak = o.optInt("currentStreak"),
                    bestStreak = o.optInt("bestStreak"), lastCompletedDate = o.optString("lastCompletedDate"),
                    totalCompletedCount = o.optInt("totalCompletedCount"), colorHex = o.optString("colorHex", "#4CAF50")
                )
            },
            reminders = arr("reminders").mapObjects { o ->
                Reminder(
                    id = o.getLong("id"), taskId = if (o.isNull("taskId")) null else o.getInt("taskId"),
                    title = o.getString("title"), message = o.optString("message"),
                    triggerAtMillis = o.getLong("triggerAtMillis"), repeatIntervalMinutes = o.optInt("repeatIntervalMinutes"),
                    isActive = o.optBoolean("isActive", true), createdAt = o.optLong("createdAt")
                )
            },
            memories = arr("memories").mapObjects { o ->
                MemoryEntry(
                    id = o.getLong("id"), type = o.getString("type"), key = o.getString("key"), content = o.getString("content"),
                    importance = o.optInt("importance", 1), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                    accessCount = o.optInt("accessCount")
                )
            },
            conversations = arr("conversations").mapObjects { o ->
                ConversationMessage(
                    id = o.getLong("id"), sessionId = o.getString("sessionId"), role = o.getString("role"),
                    text = o.getString("text"), intent = o.optString("intent"), timestamp = o.getLong("timestamp")
                )
            },
            settings = arr("settings").mapObjects { o -> UserSetting(o.getString("key"), o.getString("value")) }
        )
    }
}

class BackupManager(private val context: Context, private val db: MemoryDatabase) {

    suspend fun snapshot(): BackupSerializer.Snapshot = BackupSerializer.Snapshot(
        tasks = db.taskDao().getAll(),
        habits = db.habitDao().getAll(),
        reminders = db.reminderDao().getAll(),
        memories = db.memoryDao().getAll(),
        conversations = db.conversationDao().getAll(),
        // Google connection state is device-specific and re-established by signing in again.
        settings = db.userSettingsDao().getAll().filterNot { it.key in DEVICE_LOCAL_KEYS }
    )

    suspend fun exportTo(uri: Uri, password: CharArray): Int {
        val snap = snapshot()
        val bytes = BackupCodec.encrypt(BackupSerializer.toJson(snap).toByteArray(Charsets.UTF_8), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw BackupException("Faylga yozib bo'lmadi")
        return snap.tasks.size + snap.memories.size + snap.habits.size
    }

    suspend fun importFrom(uri: Uri, password: CharArray): BackupSerializer.Snapshot {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException("Faylni o'qib bo'lmadi")
        val snap = BackupSerializer.fromJson(String(BackupCodec.decrypt(bytes, password), Charsets.UTF_8))
        restore(snap)
        return snap
    }

    /** Replaces all data atomically — either everything is restored or nothing changes. */
    suspend fun restore(snap: BackupSerializer.Snapshot) {
        db.withTransaction {
            db.taskDao().clear(); db.taskDao().insertAll(snap.tasks)
            db.habitDao().clear(); db.habitDao().insertAll(snap.habits)
            db.reminderDao().clear(); db.reminderDao().insertAll(snap.reminders)
            db.memoryDao().clear(); db.memoryDao().insertAll(snap.memories)
            db.conversationDao().clear(); db.conversationDao().insertAll(snap.conversations)
            val local = db.userSettingsDao().getAll().filter { it.key in DEVICE_LOCAL_KEYS }
            db.userSettingsDao().clear()
            db.userSettingsDao().insertAll(snap.settings.filterNot { it.key in DEVICE_LOCAL_KEYS } + local)
        }
    }

    companion object {
        val DEVICE_LOCAL_KEYS = setOf("google_connected", "google_account", "file_trees")
    }
}
