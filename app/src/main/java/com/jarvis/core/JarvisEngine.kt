package com.jarvis.core

import com.example.data.ConversationRole
import com.jarvis.memory.ContextManager
import com.jarvis.memory.ConversationMemory
import com.jarvis.memory.UserMemory
import com.jarvis.memory.UserProfile
import com.jarvis.settings.AiMode
import com.jarvis.settings.SettingsSnapshot
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
    private val history: ConversationMemory,
    private val memory: UserMemory,
    private val context: ContextManager,
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
            val (intent, understood) = decide(parsed)
            executor.execute(intent, understood)
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

    /**
     * Offline understanding with one repair pass: if the first reading is not confident, misheard
     * words are snapped to the command vocabulary and the better reading wins.
     */
    fun understand(parsed: ParsedCommand): Pair<ResolvedIntent, ParsedCommand> {
        val first = resolver.resolve(parsed)
        if (first.confidence >= CONFIDENT) return first to parsed
        val corrected = SpellCorrector.correct(parsed.normalized)
        if (corrected == parsed.normalized) return first to parsed
        val reparsed = parser.parse(corrected)
        val second = resolver.resolve(reparsed)
        return if (second.confidence > first.confidence) second to reparsed else first to parsed
    }

    /** How well Jarvis understands [text] offline, 0..1 — used to pick among recognizer alternatives. */
    fun understandingScore(text: String): Float = understand(parser.parse(text)).first.confidence

    /**
     * Speech recognizers return several guesses; pick the one Jarvis understands best. Earlier
     * (more likely) guesses win ties.
     */
    fun bestTranscript(alternatives: List<String>): String? =
        alternatives.filter { it.isNotBlank() }
            .withIndex()
            .maxByOrNull { (i, text) -> understandingScore(text) - i * 0.01f }
            ?.value

    private suspend fun decide(parsed: ParsedCommand): Pair<ResolvedIntent, ParsedCommand> {
        val (offline, understood) = understand(parsed)
        if (offline.type in LOCAL_ONLY) return offline to understood
        val mode = settings().aiMode
        val agentReady = agent != null && mode != AiMode.OFFLINE_ONLY && agent.isAvailable()
        if (!agentReady) return offline to understood
        val useAgent = mode == AiMode.CLOUD_FIRST || offline.confidence < CONFIDENT
        if (!useAgent) return offline to understood
        val decided = agent!!.decide(parsed.body.ifBlank { parsed.original }, agentContext())
        return when {
            decided == null -> offline to understood
            // Keep the deterministic parse for dates/times the model may have left out.
            else -> decided.copy(slots = offline.slots.filterKeys { it == ResolvedIntent.DATE || it == ResolvedIntent.TIME } + decided.slots) to understood
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
