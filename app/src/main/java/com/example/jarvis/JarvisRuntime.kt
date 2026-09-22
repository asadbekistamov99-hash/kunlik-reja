package com.example.jarvis

import androidx.room.withTransaction
import android.content.Context
import com.example.data.*
import com.example.notification.NotificationHelper
import com.example.repository.TaskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat

/** Process-wide state survives Activity/dialog recreation. Room is the source of truth. */
data class JarvisUiRequest(val action: String, val value: String = "", val taskId: Int = 0, val token: Long = System.nanoTime())
data class JarvisStatus(val listening: Boolean = false, val active: Boolean = false, val busy: Boolean = false,
    val heard: String = "", val message: String = "Jarvis tayyor. Buyruq yozing yoki fon rejimini yoqing.")

class JarvisRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val db = AppDatabase.getDatabase(app)
    private val repo = TaskRepository(db.taskDao(), db.habitDao(), app)
    private val mutex = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow(JarvisStatus())
    val status = _status.asStateFlow()
    private val _request = MutableStateFlow<JarvisUiRequest?>(null)
    val request = _request.asStateFlow()
    private val prefs = app.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    val theme = _theme.asStateFlow()
    private var pendingDelete: Pair<String, Int>? = null
    private var deleteDeadline = 0L
    fun openPanel() { if (_request.value == null) _request.value = JarvisUiRequest("PANEL") }
    fun acknowledge(token: Long) { if (_request.value?.token == token) _request.value = null }
    fun listening(enabled: Boolean) { _status.update { it.copy(listening = enabled, active = if(enabled) it.active else false) } }
    fun report(message: String) { _status.update { it.copy(message = message) } }
    fun submit(text: String) { scope.launch { command(text) } }
    suspend fun command(raw: String): String = mutex.withLock {
        _status.update { it.copy(busy = true, heard = raw) }
        try {
            val result = execute(JarvisCommands.parse(raw, TaskRepository.getTodayDateString()))
            report(result)
            result
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            val msg = if (e is IllegalArgumentException) e.message ?: "Buyruq noto'g'ri." else "Amal bajarilmadi. Qayta urinib ko'ring."
            report(msg); msg
        } finally { _status.update { it.copy(busy = false) } }
    }
    private fun show(action: String, value: String = "", taskId: Int = 0): String {
        _request.value = JarvisUiRequest(action, value, taskId)
        return "Tayyor. Natijani ilovada ko'ring; ilova yopiq bo'lsa, Jarvis bildirishnomasini bosing."
    }
    private suspend fun task(query: String): Task {
        require(query.isNotBlank()) { "Vazifa nomini ayting." }
        val all = repo.allTasks.first()
        val id = query.removePrefix("#").toIntOrNull()
        val exact = all.filter { (id != null && it.id == id) || JarvisCommands.normalize(it.title) == query }
        val found = if (exact.isNotEmpty()) exact else all.filter { JarvisCommands.normalize(it.title).contains(query) }
        require(found.isNotEmpty()) { "'$query' vazifasi topilmadi." }
        require(found.size == 1) { "Bir nechta vazifa topildi: ${found.take(4).joinToString { "#${it.id} ${it.title} (${it.dateString})" }}. #raqam bilan aniqlashtiring." }
        return found.single()
    }
    private suspend fun habit(query: String): Habit {
        require(query.isNotBlank()) { "Odat nomini ayting." }
        val all = repo.allHabits.first()
        val exact = all.filter { JarvisCommands.normalize(it.title) == query || "#${it.id}" == query }
        val found = if(exact.isNotEmpty()) exact else all.filter { JarvisCommands.normalize(it.title).contains(query) }
        require(found.size == 1) { "Odat topilmadi yoki nom bir nechta odatga mos. Aniq nom yoki #raqamni yozing." }
        return found.single()
    }
    private suspend fun execute(c: JarvisCommand): String {
        if (c.action !in setOf("CONFIRM", "CANCEL")) pendingDelete = null
        when (c.action) {
            "WAKE" -> { _status.update { it.copy(active = true) }; return "Jarvis faol. Buyrug'ingizni ayting." }
            "SLEEP" -> { _status.update { it.copy(active = false) }; return "Kutish rejimi. Davom etish uchun Jarvis boshla deng." }
            "HELP" -> return JarvisCommands.help
            "CANCEL" -> { pendingDelete = null; return "Bekor qilindi." }
            "CONFIRM" -> {
                val pending = pendingDelete
                pendingDelete = null
                require(pending != null && android.os.SystemClock.elapsedRealtime() < deleteDeadline) { "Tasdiqlash uchun amal yo'q yoki 30 soniya o'tdi." }
                if(pending.first == "task") repo.getTaskById(pending.second)?.let { repo.deleteTask(it) }
                else repo.allHabits.first().find { it.id == pending.second }?.let { repo.deleteHabit(it) }
                return "O'chirildi."
            }
            "ADD" -> {
                val id = repo.insertTask(Task(title = c.target, dateString = c.date, timeString = c.time,
                    timestampMillis = TaskValidation.timestamp(c.date, c.time), description = c.description, category = c.category, priority = c.priority, durationMinutes = c.duration))
                return "#${id}: ${c.target}, ${c.date} ${c.time} uchun saqlandi."
            }
            "DELETE", "HABIT_DELETE" -> {
                val name: String
                val id: Int
                if(c.action == "DELETE") { val t = task(c.target); name = t.title; id = t.id }
                else { val h = habit(c.target); name = h.title; id = h.id }
                pendingDelete = (if(c.action == "DELETE") "task" else "habit") to id
                deleteDeadline = android.os.SystemClock.elapsedRealtime() + 30_000
                return "'$name' o'chirilsinmi? 30 soniya ichida tasdiqla yoki bekor qil deng."
            }
            "COMPLETE", "UNCOMPLETE", "MOVE", "RENAME", "DESCRIPTION", "DURATION", "PRIORITY", "CATEGORY", "REMINDER", "SUBTASK_ADD", "SUBTASK_DONE", "NOTE", "REPEAT" -> {
                val t = task(c.target)
                val changed = when(c.action) {
                    "COMPLETE" -> t.copy(isCompleted = true)
                    "UNCOMPLETE" -> t.copy(isCompleted = false)
                    "MOVE" -> t.copy(dateString = c.date, timeString = c.time)
                    "RENAME" -> t.copy(title = c.value)
                    "DESCRIPTION" -> t.copy(description = c.value)
                    "DURATION" -> t.copy(durationMinutes = c.value.toIntOrNull() ?: 0)
                    "NOTE" -> t.copy(voiceNoteText = c.value)
                    "PRIORITY" -> t.copy(priority = when(c.value) { "yuqori" -> "HIGH"; "past" -> "LOW"; "o'rtacha" -> "MEDIUM"; else -> c.value.uppercase(Locale.ROOT) })
                    "CATEGORY" -> t.copy(category = when(c.value) { "ish" -> "WORK"; "o'qish" -> "STUDY"; "shaxsiy" -> "PERSONAL"; "salomatlik" -> "HEALTH"; "uy" -> "HOME"; "boshqa" -> "OTHER"; else -> c.value.uppercase(Locale.ROOT) })
                    "REMINDER" -> {
                        require(c.value == "o'chir" || c.value.toIntOrNull() != null) { "Eslatma nom / daqiqa yoki eslatma nom / o'chir deb yozing." }
                        t.copy(hasReminder = c.value != "o'chir", reminderMinutesBefore = c.value.toIntOrNull() ?: 15)
                    }
                    "SUBTASK_ADD" -> {
                        require(c.value.isNotBlank() && !c.value.contains("|") && !c.value.contains(";;")) { "Kichik vazifa nomini / dan keyin yozing; | va ;; ishlatmang." }
                        t.copy(subtasksJson = SubtaskItem.serializeList(SubtaskItem.parseList(t.subtasksJson) + SubtaskItem(c.value)))
                    }
                    "SUBTASK_DONE" -> {
                        val items = SubtaskItem.parseList(t.subtasksJson)
                        require(items.count { JarvisCommands.normalize(it.title) == c.value } == 1) { "Kichik vazifa nomini aniq yozing." }
                        t.copy(subtasksJson = SubtaskItem.serializeList(items.map { if(JarvisCommands.normalize(it.title) == c.value) it.copy(isCompleted = true) else it }))
                    }
                    "REPEAT" -> {
                        val mode = c.value.uppercase(Locale.ROOT)
                        val count = mapOf("DAILY_7" to 7, "DAILY_14" to 14, "DAILY_30" to 30, "WEEKLY_4" to 4, "MONTHLY_3" to 3)[mode]
                        require(count != null) { "Takrorlash: DAILY_7, DAILY_14, DAILY_30, WEEKLY_4 yoki MONTHLY_3." }
                        require(!t.isRecurring) { "Bu vazifa allaqachon takrorlangan. Qayta nusxalar yaratilmadi." }
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                        val cal = Calendar.getInstance().apply { time = sdf.parse(t.dateString)!! }
                        db.withTransaction {
                            for(i in 1 until count) {
                                val next = (cal.clone() as Calendar).apply { add(when { mode.startsWith("DAILY") -> Calendar.DAY_OF_YEAR; mode.startsWith("WEEKLY") -> Calendar.WEEK_OF_YEAR; else -> Calendar.MONTH }, i) }
                                val copy = TaskValidation.normalize(t.copy(id = 0, dateString = sdf.format(next.time), isCompleted = false, isRecurring = true, recurringType = mode))
                                db.taskDao().insertTask(copy)
                            }
                            db.taskDao().updateTask(t.copy(isRecurring = true, recurringType = mode))
                        }
                        repo.allTasks.first().filter { it.isRecurring && !it.isCompleted && it.hasReminder }.forEach { NotificationHelper.scheduleTaskAlarm(app, it) }
                        return "${count - 1} ta kelgusi takror yaratildi."
                    }
                    else -> t
                }
                repo.updateTask(changed)
                return "'${changed.title}' saqlandi."
            }
            "SCHEDULE", "SEARCH" -> {
                val matches = if(c.action == "SEARCH") repo.allTasks.first().filter { it.title.contains(c.target, true) || it.description.contains(c.target, true) }
                    else repo.allTasks.first().filter { it.dateString == c.date }
                if(c.action == "SCHEDULE") show("SCHEDULE", c.date) else show("SEARCH", c.target)
                return if(matches.isEmpty()) "Vazifalar topilmadi." else "${matches.size} ta vazifa. " + matches.take(12).joinToString(". ") { "#${it.id} ${it.timeString} ${it.title}${if(it.isCompleted) ", bajarilgan" else ""}" }
            }
            "EDIT_TASK" -> return show("EDIT_TASK", taskId = task(c.target).id)
            "NEW_TASK", "EXPORT", "COPY", "CLEAR_FILTERS" -> return show(c.action)
            "FILTER_CATEGORY" -> {
                val value = TaskCategory.entries.find { JarvisCommands.normalize(it.labelUz) == c.target || it.name.equals(c.target, true) }?.name
                require(value != null) { "Toifa: ish, o'qish, shaxsiy, salomatlik, uy-ro'zg'or, boshqa." }
                return show("FILTER_CATEGORY", value)
            }
            "FILTER_PRIORITY" -> {
                val value = TaskPriority.entries.find { JarvisCommands.normalize(it.labelUz) == c.target || it.name.equals(c.target, true) }?.name
                require(value != null) { "Muhimlik: yuqori, o'rtacha, past." }
                return show("FILTER_PRIORITY", value)
            }
            "FILTER_STATUS" -> return show(c.action, c.value)
            "STATS" -> { val list = repo.allTasks.first(); show("STATS"); return "Jami ${list.size} ta vazifa, ${list.count { it.isCompleted }} ta bajarilgan, ${list.count { !it.isCompleted }} ta qolgan." }
            "HABITS" -> { val list = repo.allHabits.first(); show("HABITS"); return if(list.isEmpty()) "Hozircha odat yo'q." else list.joinToString(". ") { "#${it.id} ${it.title}: ${it.currentStreak} kun" } }
            "HABIT_ADD" -> { require(c.target.isNotBlank()) { "Odat nomini yozing." }; repo.insertHabit(Habit(title = c.target)); return "Odat saqlandi." }
            "HABIT_DONE", "HABIT_UNDO" -> {
                val h = habit(c.target)
                val done = h.lastCompletedDate == TaskRepository.getTodayDateString()
                if(done != (c.action == "HABIT_DONE")) repo.toggleHabitForToday(h)
                return "Odat holati saqlandi."
            }
            "THEME" -> { prefs.edit().putString("theme", c.value).apply(); _theme.value = c.value; return "Mavzu o'zgartirildi." }
            "FOCUS_START" -> { FocusSession.start(app, c.value.toIntOrNull() ?: 25); return "Fokus ${c.value} daqiqaga boshlandi." }
            "FOCUS_PAUSE" -> { FocusSession.pause(app); return "Fokus pauzada." }
            "FOCUS_RESUME" -> { FocusSession.resume(app); return "Fokus davom etmoqda." }
            "FOCUS_STOP" -> { FocusSession.stop(app); return "Fokus tugatildi." }
            "FOCUS_SHOW" -> return show("FOCUS")
            "TEST_NOTIFICATION" -> { NotificationHelper.showNotification(app, "Sinov eslatmasi", "Eslatmalar yoqilgan.", 9999); return "Sinov eslatmasi yuborildi." }
            "TEST_ALARM" -> { NotificationHelper.playAlarmSound(app); return "Budilnik sinovi boshlandi." }
            else -> {
                val parsed = JarvisAiService.processUserVoiceCommand(c.target)
                val command = when(parsed.action) {
                    JarvisActionType.ADD_TASK -> JarvisCommand("ADD", parsed.title, date = parsed.dateString, time = parsed.timeString,
                        description = parsed.description, category = parsed.category, priority = parsed.priority, duration = parsed.durationMinutes)
                    JarvisActionType.TOGGLE_COMPLETED -> JarvisCommand("COMPLETE", JarvisCommands.normalize(parsed.targetTaskTitle))
                    JarvisActionType.DELETE_TASK -> JarvisCommand("DELETE", JarvisCommands.normalize(parsed.targetTaskTitle))
                    JarvisActionType.SEARCH_TASKS -> JarvisCommand("SEARCH", parsed.searchQuery)
                    JarvisActionType.READ_SCHEDULE -> JarvisCommand("SCHEDULE", date = parsed.dateString.ifBlank { TaskRepository.getTodayDateString() })
                    JarvisActionType.GENERAL_RESPONSE -> return parsed.responseMessage
                }
                return execute(command)
            }
        }
    }
    companion object {
        @Volatile private var instance: JarvisRuntime? = null
        fun get(context: Context): JarvisRuntime = instance ?: synchronized(this) { instance ?: JarvisRuntime(context).also { instance = it } }
    }
}
