package com.example.jarvis.memory

import com.example.data.Task
import com.example.jarvis.core.IntentType
import com.example.jarvis.core.ResolvedIntent
import com.example.jarvis.integrations.ContactHit
import com.example.jarvis.integrations.FileHit

/**
 * Short-term working memory for the current conversation: what was just discussed, so that
 * follow-ups like "uni o'chir" (delete it) or "ikkinchisini och" (open the second one) resolve.
 */
class ContextMemory {
    @Volatile var lastIntent: IntentType? = null
        private set
    @Volatile var lastTasks: List<Task> = emptyList()
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

    fun rememberTasks(tasks: List<Task>) { lastTasks = tasks.take(20) }
    fun rememberFiles(files: List<FileHit>) { lastFiles = files.take(20) }
    fun rememberContacts(contacts: List<ContactHit>) { lastContacts = contacts.take(20) }

    /** "uni", "shuni", "birinchisi", "2-chisi" → index into the last shown list, or null. */
    fun referencedIndex(text: String): Int? {
        ORDINALS.entries.firstOrNull { (word, _) -> Regex("""\b$word\w*""").containsMatchIn(text) }?.let { return it.value }
        Regex("""\b(\d{1,2})\s*-?\s*(?:chi|nchi|inchi)\w*""").find(text)?.let { return it.groupValues[1].toInt() - 1 }
        if (Regex("""\b(?:uni|shuni|buni|o'shani)\b""").containsMatchIn(text)) return 0
        return null
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean = now - lastInteractionAt > STALE_AFTER_MS

    fun clear() {
        lastIntent = null
        lastTasks = emptyList()
        lastFiles = emptyList()
        lastContacts = emptyList()
        pendingConfirmation = null
        pendingIntent = null
        awaitingSlot = null
    }

    companion object {
        private const val STALE_AFTER_MS = 10 * 60 * 1000L
        private val ORDINALS = linkedMapOf(
            "birinchi" to 0, "ikkinchi" to 1, "uchinchi" to 2, "to'rtinchi" to 3, "beshinchi" to 4, "oxirgi" to -1
        )
    }
}
