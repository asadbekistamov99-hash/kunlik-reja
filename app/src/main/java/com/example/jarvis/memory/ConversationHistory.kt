package com.example.jarvis.memory

import com.example.data.ConversationDao
import com.example.data.ConversationMessage
import com.example.data.ConversationRole
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Persistent log of every exchange with Jarvis (also the command history). */
class ConversationHistory(private val dao: ConversationDao) {

    @Volatile var sessionId: String = UUID.randomUUID().toString()
        private set

    val recent: Flow<List<ConversationMessage>> = dao.observeRecent(200)

    fun newSession() {
        sessionId = UUID.randomUUID().toString()
    }

    suspend fun addUser(text: String, intent: String = ""): Long =
        dao.insert(ConversationMessage(sessionId = sessionId, role = ConversationRole.USER.name, text = text, intent = intent))

    suspend fun addAssistant(text: String, intent: String = ""): Long =
        dao.insert(ConversationMessage(sessionId = sessionId, role = ConversationRole.ASSISTANT.name, text = text, intent = intent))

    suspend fun lastTurns(limit: Int = 10): List<ConversationMessage> = dao.getRecent(limit)

    suspend fun count(): Int = dao.count()

    /** Drops conversations older than [days] to bound storage. */
    suspend fun prune(days: Int = 180) = dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)

    suspend fun clear() = dao.clear()
}
