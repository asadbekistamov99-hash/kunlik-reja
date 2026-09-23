package com.example.jarvis.core

import com.example.data.ConversationRole
import com.example.jarvis.memory.ContextMemory
import com.example.jarvis.memory.ConversationHistory
import com.example.jarvis.memory.LongTermMemory
import com.example.jarvis.memory.UserProfile
import com.example.jarvis.settings.AiMode
import com.example.jarvis.settings.SettingsSnapshot
import com.example.repository.TaskRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/**
 * The brain: text in, action + spoken reply out.
 *
 *   utterance -> CommandParser -> (pending follow-up?) -> IntentResolver (offline)
 *             -> [low confidence & online] GeminiAgent function call
 *             -> ActionExecutor -> reply, persisted to conversation history
 */
class JarvisEngine(
    private val parser: CommandParser,
    private val resolver: IntentResolver,
    private val executor: ActionExecutor,
    private val agent: AgentPort?,
    private val history: ConversationHistory,
    private val memory: LongTermMemory,
    private val context: ContextMemory,
    private val tasks: TaskRepository,
    private val settings: () -> SettingsSnapshot,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() }
) {
    private val lock = Mutex()

    val isSessionActive: Boolean get() = context.sessionActive

    suspend fun handle(text: String): JarvisResponse = lock.withLock {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withLock JarvisResponse("", IntentType.UNKNOWN, success = false)
        val parsed = parser.parse(trimmed)
        history.addUser(trimmed)

        val response = followUp(parsed) ?: run {
            val intent = decide(parsed)
            executor.execute(intent, parsed)
        }
        history.addAssistant(response.text, response.intent.name)
        if (response.intent != IntentType.UNKNOWN) runCatching { memory.logCommand(response.intent.name, trimmed) }
        response
    }

    /** Handles answers to Jarvis' own questions: confirmations, missing slots and list selections. */
    private suspend fun followUp(parsed: ParsedCommand): JarvisResponse? {
        val body = parsed.body
        context.pendingConfirmation?.let { pending ->
            context.pendingConfirmation = null
            return when {
                YES_RE.containsMatchIn(body) -> executor.executeConfirmed(pending)
                NO_RE.containsMatchIn(body) -> JarvisResponse("Bekor qilindi.", pending.type)
                else -> null // unrelated new command: drop the confirmation and continue
            }
        }
        val pendingIntent = context.pendingIntent
        val slot = context.awaitingSlot
        if (pendingIntent != null && slot != null) {
            context.pendingIntent = null
            context.awaitingSlot = null
            if (NO_RE.matches(body)) return JarvisResponse("Bekor qilindi.", pendingIntent.type)
            val fresh = resolver.resolve(parsed)
            // A clearly different command wins over filling the slot.
            if (fresh.type != IntentType.UNKNOWN && fresh.confidence >= CONFIDENT && fresh.type != pendingIntent.type &&
                slot != ResolvedIntent.BODY && slot != ResolvedIntent.CONTENT) return null
            val value = when (slot) {
                ResolvedIntent.TIME -> parsed.time?.let { "%02d:%02d".format(it.hour, it.minute) }
                ResolvedIntent.BODY, ResolvedIntent.CONTENT -> parsed.original.trim()
                else -> parsed.body.replaceFirstChar { it.uppercase() }
            } ?: return null
            val extra = mutableMapOf(slot to value)
            parsed.date?.let { if (pendingIntent.slot(ResolvedIntent.DATE) == null) extra[ResolvedIntent.DATE] = it.toString() }
            return executor.execute(pendingIntent.copy(slots = pendingIntent.slots + extra), parsed)
        }
        val lastIntent = context.lastIntent
        if (lastIntent == IntentType.FIND_FILE || lastIntent == IntentType.CALL_CONTACT) {
            val idx = context.referencedIndex(body)
            if (idx != null && !context.isStale()) {
                return if (lastIntent == IntentType.FIND_FILE) executor.openFileAt(idx) else executor.callContactAt(idx)
            }
        }
        return null
    }

    private suspend fun decide(parsed: ParsedCommand): ResolvedIntent {
        val offline = resolver.resolve(parsed)
        if (offline.type in LOCAL_ONLY) return offline
        val mode = settings().aiMode
        val agentReady = agent != null && mode != AiMode.OFFLINE_ONLY && agent.isAvailable()
        if (!agentReady) return offline
        val useAgent = mode == AiMode.CLOUD_FIRST || offline.confidence < CONFIDENT
        if (!useAgent) return offline
        val decided = agent!!.decide(parsed.body.ifBlank { parsed.original }, agentContext())
        return when {
            decided == null -> offline
            // Keep the deterministic parse for dates/times the model may have left out.
            else -> decided.copy(slots = offline.slots.filterKeys { it == ResolvedIntent.DATE || it == ResolvedIntent.TIME } + decided.slots)
        }
    }

    private suspend fun agentContext(): AgentContext {
        val now = clock()
        val profile = UserProfile.from(memory.snapshotForPrompt(), settings().userName).toPrompt()
        val today = tasks.tasksFor(now.toLocalDate().toString())
            .joinToString("; ") { "${it.timeString} ${it.title}${if (it.isCompleted) " (bajarilgan)" else ""}" }
        val turns = history.lastTurns(8).map { (if (it.role == ConversationRole.USER.name) "USER" else "MODEL") to it.text }
        return AgentContext(now, profile, today, turns.dropLast(1))
    }

    companion object {
        const val CONFIDENT = 0.75f
        private val LOCAL_ONLY = setOf(IntentType.WAKE, IntentType.START_SESSION, IntentType.STOP_SESSION, IntentType.TIME_QUERY)
        private val YES_RE = Regex("""\b(ha|xa|albatta|mayli|yubor|tasdiqla|to'g'ri|bo'pti|xo'p|ok|okey|ayni)\b""")
        private val NO_RE = Regex("""^(yo'q|yoq|kerak emas|bekor|bekor qil|to'xta|qo'y|shart emas)\b.*""")
    }
}
