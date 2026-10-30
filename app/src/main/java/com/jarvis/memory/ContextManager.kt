package com.jarvis.memory

import com.example.data.Task
import com.example.data.Reminder
import com.jarvis.core.IntentType
import com.jarvis.core.ResolvedIntent
import com.jarvis.integrations.ContactHit
import com.jarvis.integrations.FileHit

/**
 * Short-term working memory for the current conversation: what was just discussed, so that
 * follow-ups like "uni o'chir" (delete it) or "ikkinchisini och" (open the second one) resolve.
 */
class ContextManager {
    @Volatile var lastIntent: IntentType? = null
        private set
    @Volatile var lastTasks: List<Task> = emptyList()
        private set
    @Volatile var lastReminders: List<Reminder> = emptyList()
        private set
    @Volatile var selectedReminder: Reminder? = null
        private set
    @Volatile var lastCalls: List<com.jarvis.integrations.CallRecord> = emptyList()
        private set
    @Volatile var lastNotifications: List<com.jarvis.integrations.NotificationItem> = emptyList()
        private set
    @Volatile var lastFiles: List<FileHit> = emptyList()
        private set
    @Volatile var lastContacts: List<ContactHit> = emptyList()
        private set
    @Volatile var sessionActive: Boolean = false
    /** An action waiting for "ha"/"yo'q" (e.g. sending an e-mail). */
    @Volatile var pendingConfirmation: ResolvedIntent? = null
    /** An intent missing one slot; the next utterance fills [awaitingSlot]. */
    @Volatile var pendingIntent: ResolvedIntent? = null
    @Volatile var awaitingSlot: String? = null
    @Volatile var lastInteractionAt: Long = 0L
        private set

    fun onIntent(intent: IntentType) {
        lastIntent = intent
        lastInteractionAt = System.currentTimeMillis()
    }

    fun rememberCalls(calls: List<com.jarvis.integrations.CallRecord>) { lastCalls = calls.toList() }
    fun rememberNotifications(items: List<com.jarvis.integrations.NotificationItem>) { lastNotifications = items.toList() }
    fun callIndex(text: String): Int? {
        Regex("""\b(\d{1,2})\s*-?\s*(?:chi\w*|si\w*)\b""").find(text)?.let { return it.groupValues[1].toInt() - 1 }
        text.trim().toIntOrNull()?.let { return it - 1 }
        return referencedIndex(text)
    }

    fun rememberTasks(tasks: List<Task>) { lastTasks = tasks.take(20) }
    fun rememberReminders(reminders: List<Reminder>) {
        lastReminders = reminders.toList()
        selectedReminder = reminders.singleOrNull()
    }
    fun selectReminder(reminder: Reminder) { selectedReminder = reminder }
    fun rememberFiles(files: List<FileHit>) { lastFiles = files.take(20) }
    fun rememberContacts(contacts: List<ContactHit>) { lastContacts = contacts.take(20) }

    /** "uni", "shuni", "birinchisi", "2-chisi" → index into the last shown list, or null. */
    fun referencedIndex(text: String): Int? {
        ORDINALS.entries.firstOrNull { (word, _) -> Regex("""\b$word\w*""").containsMatchIn(text) }?.let { return it.value }
        Regex("""\b(\d{1,2})\s*-?\s*(?:chi|nchi|inchi)\w*""").find(text)?.let { return it.groupValues[1].toInt() - 1 }
        if (Regex("""\b(?:uni|uning|shuni|shuning|buni|buning|shu|bu|o'shani|o'shaning)\b""").containsMatchIn(text)) return 0
        return null
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean = now - lastInteractionAt > STALE_AFTER_MS

    fun clear() {
        lastIntent = null
        lastTasks = emptyList()
        lastReminders = emptyList()
        selectedReminder = null
        lastCalls = emptyList()
        lastNotifications = emptyList()
        lastFiles = emptyList()
        lastContacts = emptyList()
        pendingConfirmation = null
        pendingIntent = null
        awaitingSlot = null
    }

    companion object {
        private const val STALE_AFTER_MS = 10 * 60 * 1000L
        private val ORDINALS = linkedMapOf(
            "birinchi" to 0, "ikkinchi" to 1, "uchinchi" to 2, "to'rtinchi" to 3, "beshinchi" to 4, "oltinchi" to 5, "yettinchi" to 6, "sakkizinchi" to 7, "to'qqizinchi" to 8, "o'ninchi" to 9, "oxirgi" to -1
        )
    }
}
