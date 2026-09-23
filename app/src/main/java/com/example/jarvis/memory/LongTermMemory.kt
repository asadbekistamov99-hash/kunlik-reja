package com.example.jarvis.memory

import com.example.data.MemoryDao
import com.example.data.MemoryEntry
import com.example.data.MemoryType
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** Permanent memory: preferences, habits, facts and profile details the user tells Jarvis. */
class LongTermMemory(private val dao: MemoryDao) {

    val all: Flow<List<MemoryEntry>> = dao.observeAll()

    suspend fun remember(type: MemoryType, content: String, key: String = keyFor(type, content), importance: Int = defaultImportance(type)): MemoryEntry {
        val existing = dao.getByKey(key)
        val now = System.currentTimeMillis()
        val entry = (existing ?: MemoryEntry(type = type.name, key = key, content = content, createdAt = now)).copy(
            type = type.name,
            content = content.trim().replaceFirstChar { it.uppercase() },
            importance = maxOf(importance, existing?.importance ?: 0),
            updatedAt = now
        )
        dao.upsert(entry)
        return dao.getByKey(key) ?: entry
    }

    suspend fun recall(query: String?, limit: Int = 8): List<MemoryEntry> {
        val command = MemoryType.COMMAND.name
        val results = if (query.isNullOrBlank()) dao.getAll().filter { it.type != command }.take(limit)
        else {
            val words = query.lowercase(Locale.ROOT).split(' ').filter { it.length >= 3 }
            val hits = LinkedHashMap<Long, MemoryEntry>()
            dao.search(query, limit * 2).forEach { hits[it.id] = it }
            words.forEach { w -> dao.search(w, limit * 2).forEach { hits[it.id] = it } }
            hits.values.filter { it.type != command }.take(limit)
        }
        if (results.isNotEmpty()) dao.markAccessed(results.map { it.id })
        return results
    }

    suspend fun byType(type: MemoryType): List<MemoryEntry> = dao.getByType(type.name)

    suspend fun get(key: String): MemoryEntry? = dao.getByKey(key)

    suspend fun forget(id: Long) = dao.delete(id)

    suspend fun forgetMatching(query: String): Int {
        val matches = dao.search(query, 20).filter { it.type != MemoryType.COMMAND.name }
        matches.forEach { dao.delete(it.id) }
        return matches.size
    }

    /** Keeps a bounded log of recently used commands (used for suggestions, not shown as facts). */
    suspend fun logCommand(intent: String, text: String) {
        remember(MemoryType.COMMAND, text, key = "cmd:${intent.lowercase(Locale.ROOT)}:${text.hashCode()}", importance = 0)
        dao.trimType(MemoryType.COMMAND.name, keep = 200)
    }

    suspend fun snapshotForPrompt(limit: Int = 25): List<MemoryEntry> =
        dao.getAll().filter { it.type != MemoryType.COMMAND.name }.take(limit)

    companion object {
        fun keyFor(type: MemoryType, content: String): String {
            val normalized = content.lowercase(Locale.ROOT)
                .replace(Regex("""[^\p{L}\p{N}' ]"""), " ")
                .split(' ')
                .filter { it.length > 2 && it !in STOP_WORDS }
                .sorted()
                .joinToString(" ")
            return "${type.name.lowercase(Locale.ROOT)}:${normalized.ifBlank { content.lowercase(Locale.ROOT).trim() }}"
        }

        private fun defaultImportance(type: MemoryType) = when (type) {
            MemoryType.PROFILE, MemoryType.IMPORTANT -> 3
            MemoryType.HABIT, MemoryType.PREFERENCE -> 2
            MemoryType.FACT -> 1
            MemoryType.COMMAND -> 0
        }

        private val STOP_WORDS = setOf("men", "mening", "har", "va", "bilan", "uchun", "juda", "eslab", "qol")
    }
}
