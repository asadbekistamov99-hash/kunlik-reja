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
 *             -> [low confidence & online] GeminiAgent (JSON Schema javobi)
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

    fun beginSession() { context.sessionActive = true }
    fun endSession() { executor.stopPhoneControl(); context.sessionActive = false; context.clear() }

    val isSessionActive: Boolean get() = context.sessionActive

    suspend fun handle(text: String, alternatives: List<String> = emptyList()): JarvisResponse = lock.withLock {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withLock JarvisResponse("", IntentType.UNKNOWN, success = false)
        val parsed = parser.parse(trimmed)
        history.addUser(trimmed)

        val control = resolver.resolve(parsed)
        val response = if (control.type == IntentType.STOP_SESSION) executor.execute(control, parsed)
        else clarifySpeechTime(parsed, alternatives) ?: followUp(parsed) ?: run {
            val (intent, understood) = decide(parsed)
            executor.execute(intent, understood)
        }
        history.addAssistant(response.text, response.intent.name)
        if (response.intent != IntentType.UNKNOWN) runCatching { memory.logCommand(response.intent.name, trimmed) }
        response
    }

    /** Recognition variants with different absolute times must not silently schedule an alarm. */
    private fun clarifySpeechTime(parsed: ParsedCommand, alternatives: List<String>): JarvisResponse? {
        if (alternatives.size < 2 || parsed.isRelative || parsed.time == null || context.pendingConfirmation != null) return null
        val pending = context.pendingIntent
        if (pending != null && context.awaitingSlot != ResolvedIntent.TIME) return null
        val intent = pending ?: resolver.resolve(parsed)
        if (intent.type !in setOf(IntentType.ADD_REMINDER, IntentType.RESCHEDULE_REMINDER, IntentType.ADD_TASK, IntentType.RESCHEDULE_TASK)) return null
        val fallbackDate = intent.slot(ResolvedIntent.DATE)?.let { java.time.LocalDate.parse(it) } ?: clock().toLocalDate()
        val times = (alternatives + parsed.original).map(parser::parse)
            .filter { !it.isRelative && it.time != null && !it.needsTimeClarification }
            .map { (it.date ?: fallbackDate) to it.time }.distinct()
        if (times.size < 2) return null
        context.pendingIntent = intent.copy(slots = intent.slots - ResolvedIntent.TIME)
        context.awaitingSlot = ResolvedIntent.TIME
        return JarvisResponse("Vaqtni turlicha eshitdim. Aniq sana va soatni qayta ayting.", intent.type,
            success = false, expectsReply = true)
    }

    /** Handles answers to Jarvis' own questions: confirmations, missing slots and list selections. */
    private suspend fun followUp(parsed: ParsedCommand): JarvisResponse? {
        val body = parsed.body
        context.pendingConfirmation?.let { pending ->
            context.pendingConfirmation = null
            return when {
                NO_RE.containsMatchIn(body) -> JarvisResponse("Bekor qilindi.", pending.type)
                YES_RE.matches(body) -> executor.executeConfirmed(pending)
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
            val answer = if (slot == ResolvedIntent.TIME && parsed.body.matches(Regex("""\d{1,2}"""))) {
                parser.parse("soat ${parsed.body}")
            } else parsed
            val extra = mutableMapOf<String, String>()
            answer.date?.let { extra[ResolvedIntent.DATE] = it.toString() }
            if (slot == ResolvedIntent.TIME && (answer.time == null || answer.needsTimeClarification)) {
                return executor.execute(pendingIntent.copy(slots = (pendingIntent.slots - ResolvedIntent.TIME) + extra), answer)
            }
            val value = when (slot) {
                ResolvedIntent.TIME -> answer.time?.let { "%02d:%02d".format(it.hour, it.minute) }
                ResolvedIntent.TITLE -> context.referencedIndex(body)?.let { index ->
                    if (index < 0) context.lastReminders.lastOrNull()?.title
                    else context.lastReminders.getOrNull(index)?.title
                } ?: parsed.body.replaceFirstChar { it.uppercase() }
                "phone" -> if (pendingIntent.type == IntentType.COMPOSE_SMS) context.callIndex(body)?.let {
                    context.lastContacts.getOrNull(it)?.phone
                } ?: parsed.original.trim() else parsed.original.trim()
                "index" -> context.callIndex(body)?.plus(1)?.toString()
                "app" -> parsed.body.lowercase()
                ResolvedIntent.TO -> parsed.body.let {
                    if (pendingIntent.type == IntentType.COMPOSE_SMS && parsed.original.any { c -> c.isDigit() }) parsed.original.trim() else it.replaceFirstChar { c -> c.uppercase() }
                }
                ResolvedIntent.BODY, ResolvedIntent.CONTENT -> parsed.original.trim()
                else -> parsed.body.replaceFirstChar { it.uppercase() }
            } ?: return null
            extra[slot] = value
            return executor.execute(pendingIntent.copy(slots = pendingIntent.slots + extra), answer)
        }
        val lastIntent = context.lastIntent
        if (lastIntent == IntentType.READ_CALL_HISTORY && context.callIndex(body) != null && !context.isStale() &&
            resolver.resolve(parsed).type in setOf(IntentType.UNKNOWN, IntentType.CALL_FROM_HISTORY)) {
            return executor.execute(ResolvedIntent(IntentType.CALL_FROM_HISTORY, 1f,
                mapOf("index" to (context.callIndex(body)!! + 1).toString())), parsed)
        }
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
        if (offline.type in LOCAL_ONLY || offline.type == IntentType.RESCHEDULE_REMINDER && offline.confidence >= CONFIDENT) {
            return offline to understood
        }
        val mode = settings().aiMode
        val agentReady = agent != null && mode != AiMode.OFFLINE_ONLY && agent.isAvailable()
        if (!agentReady) return offline to understood
        val useAgent = mode == AiMode.CLOUD_FIRST || offline.confidence < CONFIDENT
        if (!useAgent) return offline to understood
        val decided = agent!!.decide(parsed.body.ifBlank { parsed.original }, agentContext())
        return when {
            decided == null -> offline to understood
            // Keep the deterministic parse for dates/times the model may have left out.
            else -> {
                val slots = if (decided.type in setOf(IntentType.ADD_REMINDER, IntentType.RESCHEDULE_REMINDER)) {
                    decided.slots - ResolvedIntent.DATE - ResolvedIntent.TIME - "relativeAt"
                } else decided.slots
                decided.copy(slots = slots + offline.slots.filterKeys {
                    it == ResolvedIntent.DATE || it == ResolvedIntent.TIME || it == "repeatDaily"
                }) to understood
            }
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
        private val LOCAL_ONLY = setOf(IntentType.READ_CALL_HISTORY, IntentType.CALL_FROM_HISTORY, IntentType.COMPOSE_SMS,
            IntentType.SOCIAL_MESSAGE, IntentType.ADD_CONTACT, IntentType.PHONE_SETTING, IntentType.PAUSE_MICROPHONE, IntentType.RESUME_MICROPHONE, IntentType.OPEN_APP, IntentType.CONTROL_PHONE, IntentType.PLAY_GAME, IntentType.ANALYZE_CHESS, IntentType.LIST_REMINDERS, IntentType.NEXT_REMINDER, IntentType.RENAME_REMINDER, IntentType.DELETE_REMINDER, IntentType.WAKE, IntentType.START_SESSION, IntentType.STOP_SESSION, IntentType.TIME_QUERY)
        private val YES_RE = Regex("""^(?:(?:ha|xa|albatta|mayli|tasdiqla|to'g'ri|bo'pti|xo'p|ok|okey|ayni)(?:\s+(?:yubor|tasdiqla|qo'ng'iroq qil|o'chir))?|yubor)$""", RegexOption.IGNORE_CASE)
        private val NO_RE = Regex("""^(yo'q|yoq|kerak emas|bekor|bekor qil|to'xta|qo'y|shart emas)\b.*""")
    }
}
