package com.jarvis.core

import com.jarvis.memory.ContextManager
import com.jarvis.memory.ConversationMemory
import com.jarvis.memory.UserMemory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Jarvis Ultra markaziy boshqaruv dvigateli.
 * Offlayn parser + IntentResolver hamda Gemini AI agentini birlashtirib,
 * foydalanuvchi aytgan vazifalarni 100% aniqlikda bajarishni kafolatlaydi.
 */
class JarvisEngine(
    private val parser: CommandParser,
    private val resolver: IntentResolver,
    private val executor: ActionExecutor,
    private val agent: AgentPort?,
    private val history: ConversationMemory,
    private val memory: UserMemory,
    private val context: ContextManager
) {
    private val mutex = Mutex()

    suspend fun process(text: String): JarvisResponse = mutex.withLock {
        val raw = text.trim()
        if (raw.isEmpty()) {
            return@withLock JarvisResponse(
                text = "Eshitaman, nima bajarishimni xohlaysiz?",
                intent = IntentType.WAKE,
                expectsReply = true
            )
        }

        // 1. Matnni imlo jihatdan to'g'rilab, vaqt va sanalarni ajratib olamiz
        val parsedInitial = parser.parse(raw)
        val offlineIntent = resolver.resolve(parsedInitial)

        // 2. Agar offlayn ishonch past bo'lsa yoki murakkab jumla bo'lsa, Gemini Agentdan so'raymiz
        var finalIntent = offlineIntent
        var finalParsed = parsedInitial

        val needsAiHelp = offlineIntent.type == IntentType.UNKNOWN ||
            offlineIntent.confidence < 0.85f ||
            (offlineIntent.type in listOf(
                IntentType.ADD_TASK,
                IntentType.COMPLETE_TASK,
                IntentType.DELETE_TASK,
                IntentType.RESCHEDULE_TASK
            ) && offlineIntent.slot("title").isNullOrBlank())

        if (needsAiHelp && agent?.isAvailable() == true) {
            val agentCtx = AgentContext(
                now = LocalDateTime.now(),
                profile = runCatching { memory.recall("").joinToString("; ") }.getOrDefault(""),
                todayTasks = context.lastTasks.joinToString(", ") { it.title },
                history = emptyList()
            )
            val aiDecision = runCatching { agent.decide(parsedInitial.normalized, agentCtx) }.getOrNull()
            if (aiDecision != null && (aiDecision.type != IntentType.UNKNOWN || !aiDecision.slot("reply").isNullOrBlank())) {
                val aiDate = aiDecision.slot("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val aiTime = aiDecision.slot("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val aiDuration = aiDecision.slot("durationMinutes")?.toIntOrNull()
                val aiTitle = aiDecision.slot("title")

                finalParsed = parsedInitial.copy(
                    date = parsedInitial.date ?: aiDate,
                    time = parsedInitial.time ?: aiTime,
                    durationMinutes = parsedInitial.durationMinutes ?: aiDuration,
                    remainder = if (!aiTitle.isNullOrBlank()) aiTitle else parsedInitial.remainder
                )

                val mergedSlots = offlineIntent.slots + aiDecision.slots
                finalIntent = aiDecision.copy(slots = mergedSlots)
            }
        }

        // 3. Agar suhbat savoli bo'lib, Gemini tabiiy javob ("reply") qaytargan bo'lsa:
        if (finalIntent.type == IntentType.UNKNOWN && !finalIntent.slot("reply").isNullOrBlank()) {
            val replyText = finalIntent.slot("reply")!!
            runCatching {
                history.recordUser(raw)
                history.recordAssistant(replyText)
            }
            return@withLock JarvisResponse(
                text = replyText,
                intent = IntentType.UNKNOWN,
                success = true,
                expectsReply = context.sessionActive
            )
        }

        // 4. Harakatni ActionExecutor orqali 100% bajaramiz
        val response = executor.execute(finalIntent, finalParsed)

        // 5. Suhbat tarixiga yozib qo'yamiz
        runCatching {
            history.recordUser(raw)
            history.recordAssistant(response.text)
        }

        return@withLock response
    }

    fun clearHistory() {
        runCatching { history.clear() }
        runCatching { context.clear() }
    }
}