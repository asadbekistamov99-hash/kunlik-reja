package com.example.jarvis.core

import java.time.format.DateTimeFormatter

enum class IntentType {
    WAKE,
    START_SESSION,
    STOP_SESSION,
    ADD_TASK,
    COMPLETE_TASK,
    DELETE_TASK,
    RESCHEDULE_TASK,
    LIST_PENDING,
    PLAN_DAY,
    DAY_SUMMARY,
    SEARCH_TASKS,
    ADD_REMINDER,
    ADD_HABIT,
    REMEMBER,
    RECALL,
    FORGET,
    OPEN_CAMERA,
    FIND_FILE,
    CALL_CONTACT,
    READ_NOTIFICATIONS,
    CLEAR_NOTIFICATIONS,
    CALENDAR_READ,
    CALENDAR_DELETE,
    EMAIL_READ,
    EMAIL_DRAFT,
    EMAIL_SEND,
    TIME_QUERY,
    GREETING,
    THANKS,
    HELP,
    UNKNOWN
}

/** A resolved intent with its extracted slots (all values are strings for uniform handling). */
data class ResolvedIntent(
    val type: IntentType,
    val confidence: Float,
    val slots: Map<String, String> = emptyMap(),
    val source: String = "offline"
) {
    fun slot(name: String): String? = slots[name]?.takeIf { it.isNotBlank() }

    companion object {
        const val TITLE = "title"
        const val DATE = "date"
        const val TIME = "time"
        const val DURATION = "duration"
        const val QUERY = "query"
        const val CONTACT = "contact"
        const val EXTENSION = "extension"
        const val VIDEO = "video"
        const val TO = "to"
        const val SUBJECT = "subject"
        const val BODY = "body"
        const val MEMORY_TYPE = "memory_type"
        const val CONTENT = "content"
        const val PRIORITY = "priority"
        const val CATEGORY = "category"
        const val ANSWER = "answer"
    }
}

/**
 * Offline, rule-based intent recognition for Uzbek commands. Rules are ordered from most to least
 * specific; the first matching rule wins. Confidence below [JarvisEngine.CONFIDENT] lets the online
 * agent (when available) take a second look.
 */
class IntentResolver {

    fun resolve(cmd: ParsedCommand): ResolvedIntent {
        val t = cmd.body
        val slots = LinkedHashMap<String, String>()
        cmd.date?.let { slots[ResolvedIntent.DATE] = it.toString() }
        cmd.time?.let { slots[ResolvedIntent.TIME] = it.format(HHMM) }
        cmd.durationMinutes?.let { slots[ResolvedIntent.DURATION] = it.toString() }

        fun hit(type: IntentType, confidence: Float = 0.9f, vararg extra: Pair<String, String>) =
            ResolvedIntent(type, confidence, slots + extra.filter { it.second.isNotBlank() })

        if (t.isBlank()) return hit(if (cmd.hasWakeWord) IntentType.WAKE else IntentType.UNKNOWN, if (cmd.hasWakeWord) 1f else 0f)
        if (STOP_RE.matches(t)) return hit(IntentType.STOP_SESSION, 1f)
        if (START_RE.matches(t)) return hit(IntentType.START_SESSION, 1f)

        if (FORGET_RE.containsMatchIn(t)) {
            return hit(IntentType.FORGET, 0.85f, ResolvedIntent.QUERY to strip(cmd.remainder, FORGET_RE, FILLER_RE))
        }
        if (RECALL_RE.containsMatchIn(t)) {
            return hit(IntentType.RECALL, 0.9f, ResolvedIntent.QUERY to strip(cmd.remainder, RECALL_RE, FILLER_RE, QUESTION_RE))
        }
        if (REMEMBER_EXPLICIT_RE.containsMatchIn(t) || PERSONAL_FACT_RE.containsMatchIn(t)) {
            val content = strip(cmd.body, REMEMBER_EXPLICIT_RE, Regex("""\b(?:ki|shuni)\b"""))
            return hit(
                IntentType.REMEMBER, 0.9f,
                ResolvedIntent.CONTENT to content,
                ResolvedIntent.MEMORY_TYPE to classifyMemory(content)
            )
        }

        if (CAMERA_RE.containsMatchIn(t)) {
            return hit(IntentType.OPEN_CAMERA, 0.95f, ResolvedIntent.VIDEO to VIDEO_RE.containsMatchIn(t).toString())
        }
        if (FILE_NOUN_RE.containsMatchIn(t) && FILE_VERB_RE.containsMatchIn(t)) {
            val ext = EXTENSIONS.entries.firstOrNull { (k, _) -> Regex("""\b$k\w*""").containsMatchIn(t) }?.value.orEmpty()
            val query = strip(cmd.remainder, FILE_VERB_RE, FILE_NOUN_RE, FILLER_RE, Regex("""\b(?:pdf|word|excel|docx?|xlsx?|pptx?|txt)\w*\b"""))
            return hit(IntentType.FIND_FILE, 0.9f, ResolvedIntent.EXTENSION to ext, ResolvedIntent.QUERY to query)
        }
        if (CALL_RE.containsMatchIn(t)) {
            val name = strip(cmd.remainder, CALL_RE, FILLER_RE, Regex("""\b(?:bilan|ni|raqamini|raqamiga)\b"""))
            return hit(IntentType.CALL_CONTACT, 0.9f, ResolvedIntent.CONTACT to stripDative(name))
        }
        if (NOTIFICATION_RE.containsMatchIn(t)) {
            return if (CLEAR_RE.containsMatchIn(t)) hit(IntentType.CLEAR_NOTIFICATIONS, 0.9f)
            else hit(IntentType.READ_NOTIFICATIONS, 0.9f)
        }
        if (EMAIL_RE.containsMatchIn(t)) {
            val to = EMAIL_ADDRESS_RE.find(cmd.normalized)?.value.orEmpty()
            val body = BODY_RE.find(cmd.original)?.groupValues?.get(1)?.trim().orEmpty()
            val subject = SUBJECT_RE.find(cmd.body)?.groupValues?.get(1)?.trim().orEmpty()
            val recipientName = if (to.isBlank()) {
                RECIPIENT_RE.find(cmd.body)?.groupValues?.get(1).orEmpty()
            } else ""
            return when {
                SEND_RE.containsMatchIn(t) -> hit(
                    IntentType.EMAIL_SEND, 0.9f, ResolvedIntent.TO to to.ifBlank { recipientName },
                    ResolvedIntent.SUBJECT to subject, ResolvedIntent.BODY to body
                )
                DRAFT_RE.containsMatchIn(t) -> hit(
                    IntentType.EMAIL_DRAFT, 0.9f, ResolvedIntent.TO to to.ifBlank { recipientName },
                    ResolvedIntent.SUBJECT to subject, ResolvedIntent.BODY to body
                )
                else -> hit(IntentType.EMAIL_READ, 0.9f)
            }
        }
        if (CALENDAR_RE.containsMatchIn(t)) {
            return if (DELETE_RE.containsMatchIn(t)) {
                hit(IntentType.CALENDAR_DELETE, 0.85f, ResolvedIntent.TITLE to titleFrom(cmd.remainder, CALENDAR_RE, DELETE_RE))
            } else if (ADD_RE.containsMatchIn(t)) {
                hit(IntentType.ADD_TASK, 0.9f, ResolvedIntent.TITLE to titleFrom(cmd.remainder, CALENDAR_RE, ADD_RE))
            } else hit(IntentType.CALENDAR_READ, 0.9f)
        }

        if (REMINDER_RE.containsMatchIn(t)) {
            return hit(IntentType.ADD_REMINDER, if (cmd.time != null) 0.95f else 0.8f,
                ResolvedIntent.TITLE to titleFrom(cmd.remainder, REMINDER_RE))
        }
        if (HABIT_RE.containsMatchIn(t) && ADD_RE.containsMatchIn(t)) {
            return hit(IntentType.ADD_HABIT, 0.9f, ResolvedIntent.TITLE to titleFrom(cmd.remainder, HABIT_RE, ADD_RE))
        }
        if (PENDING_RE.containsMatchIn(t)) return hit(IntentType.LIST_PENDING, 0.95f)
        if (PLAN_RE.containsMatchIn(t)) return hit(IntentType.PLAN_DAY, 0.95f)
        if (COMPLETE_RE.containsMatchIn(t)) {
            return hit(IntentType.COMPLETE_TASK, 0.85f, ResolvedIntent.TITLE to titleFrom(cmd.remainder, COMPLETE_RE))
        }
        if (RESCHEDULE_RE.containsMatchIn(t) && (cmd.time != null || cmd.date != null)) {
            return hit(IntentType.RESCHEDULE_TASK, 0.85f, ResolvedIntent.TITLE to titleFrom(cmd.remainder, RESCHEDULE_RE))
        }
        if (DELETE_RE.containsMatchIn(t)) {
            return hit(IntentType.DELETE_TASK, 0.85f, ResolvedIntent.TITLE to titleFrom(cmd.remainder, DELETE_RE))
        }
        if (SUMMARY_RE.containsMatchIn(t)) return hit(IntentType.DAY_SUMMARY, 0.9f)
        if (ADD_RE.containsMatchIn(t)) {
            val title = titleFrom(cmd.remainder, ADD_RE)
            return hit(IntentType.ADD_TASK, if (title.isNotBlank()) 0.9f else 0.7f,
                ResolvedIntent.TITLE to title,
                ResolvedIntent.PRIORITY to priorityOf(t),
                ResolvedIntent.CATEGORY to categoryOf(t))
        }
        if (SEARCH_RE.containsMatchIn(t)) {
            return hit(IntentType.SEARCH_TASKS, 0.8f, ResolvedIntent.QUERY to strip(cmd.remainder, SEARCH_RE, FILLER_RE))
        }
        if (TIME_QUERY_RE.containsMatchIn(t)) return hit(IntentType.TIME_QUERY, 0.95f)
        if (GREETING_RE.containsMatchIn(t)) return hit(IntentType.GREETING, 0.8f)
        if (THANKS_RE.containsMatchIn(t)) return hit(IntentType.THANKS, 0.9f)
        if (HELP_RE.containsMatchIn(t)) return hit(IntentType.HELP, 0.9f)
        // A time plus a noun phrase ("ertaga soat 9 da uchrashuv") is almost always a new task.
        if ((cmd.time != null || cmd.date != null) && cmd.remainder.split(' ').size in 1..6) {
            return hit(IntentType.ADD_TASK, 0.6f,
                ResolvedIntent.TITLE to titleFrom(cmd.remainder),
                ResolvedIntent.PRIORITY to priorityOf(t),
                ResolvedIntent.CATEGORY to categoryOf(t))
        }
        return hit(IntentType.UNKNOWN, 0.1f)
    }

    fun classifyMemory(content: String): String = when {
        HABIT_STATEMENT_RE.containsMatchIn(content) -> "HABIT"
        PROFILE_RE.containsMatchIn(content) -> "PROFILE"
        PREFERENCE_RE.containsMatchIn(content) -> "PREFERENCE"
        IMPORTANT_RE.containsMatchIn(content) -> "IMPORTANT"
        else -> "FACT"
    }

    private fun titleFrom(remainder: String, vararg verbs: Regex): String {
        val stripped = strip(remainder, *verbs, TASK_NOUN_RE, FILLER_RE, PRIORITY_WORDS_RE)
        return stripped.replaceFirstChar { it.uppercase() }
    }

    private fun strip(text: String, vararg patterns: Regex): String {
        var out = text
        for (p in patterns) out = out.replace(p, " ")
        return out.replace(Regex("""\s+"""), " ").trim().trim(',', '.', '-', ':')
    }

    private fun stripDative(name: String): String {
        val words = name.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (words.isEmpty()) return ""
        val last = words.last()
        val stripped = DATIVE_RE.replace(last, "")
        if (stripped.length >= 3) words[words.lastIndex] = stripped
        return words.joinToString(" ")
    }

    private fun priorityOf(t: String) = when {
        Regex("""\b(muhim|tezkor|shoshilinch|zudlik|yuqori)\w*""").containsMatchIn(t) -> "HIGH"
        Regex("""\b(past|shoshilmasdan|keyinroq)\w*""").containsMatchIn(t) -> "LOW"
        else -> "MEDIUM"
    }

    private fun categoryOf(t: String) = when {
        Regex("""\b(ish|majlis|uchrashuv|yig'ilish|loyiha|mijoz|hisobot|ofis)\w*""").containsMatchIn(t) -> "WORK"
        Regex("""\b(dars|o'qish|imtihon|kurs|kitob|universitet|maktab)\w*""").containsMatchIn(t) -> "STUDY"
        Regex("""\b(sport|yugur|mashq|zal|shifokor|doktor|dori|salomatlik|badantarbiya)\w*""").containsMatchIn(t) -> "HEALTH"
        Regex("""\b(uy|bozor|ovqat|tozala|kir|xarid)\w*""").containsMatchIn(t) -> "HOME"
        else -> "PERSONAL"
    }

    companion object {
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private val STOP_RE = Regex("""(?:tugat|to'xta|to'xtat|bas|yetarli|xayr|stop|o'chir o'zingni|jim bo'l)(?:ing|gin|ish)?""")
        private val START_RE = Regex("""(?:boshla|boshlaymiz|boshlagin|ishga tush|uyg'on|tayyormisan|eshit|eshityapsanmi|labbay)""")

        private val FORGET_RE = Regex("""\b(?:unut|esingdan chiqar|xotiradan o'chir)\w*""")
        private val RECALL_RE = Regex("""\b(?:nimani eslab qolding|men haqimda|meni haqimda|eslaysanmi|xotirangda|nima bilasan|ismim nima|eslatib ber)\w*""")
        private val REMEMBER_EXPLICIT_RE = Regex("""\b(?:eslab qol|yodda tut|yodingda tut|esingda tut|xotirangga yoz|unutma)\w*""")
        private val PERSONAL_FACT_RE = Regex("""^(?:men\b.*\b(?:har kuni|har ertalab|har kech|har hafta|doim|odatda|yaxshi ko'raman|yoqtiraman|yoqmaydi|ishlayman|yashayman|o'qiyman)|mening\s+\w+|ismim\s+\w+)""")
        private val HABIT_STATEMENT_RE = Regex("""\b(?:har kuni|har ertalab|har kech|har hafta|doim|odatda|kunda)\b""")
        private val PROFILE_RE = Regex("""\b(?:ismim|ismi|yoshim|tug'ilgan|ishlayman|yashayman|kasbim|o'qiyman|familiyam)\w*""")
        private val PREFERENCE_RE = Regex("""\b(?:yaxshi ko'raman|yoqtiraman|yoqmaydi|afzal|sevaman|ichmayman|yemayman)\w*""")
        private val IMPORTANT_RE = Regex("""\b(?:muhim|parol emas|kod|raqam|manzil|sana)\w*""")

        private val CAMERA_RE = Regex("""\b(?:kamera\w*|rasmga ol\w*|surat\w* ol\w*|rasm ol\w*|video\w* (?:ol|yoz)\w*|selfi\w*)""")
        private val VIDEO_RE = Regex("""\bvideo""")
        private val FILE_NOUN_RE = Regex("""\b(?:fayl\w*|pdf\w*|hujjat\w*|docx?\w*|word\w*|excel\w*|xlsx?\w*|pptx?\w*|taqdimot\w*|prezentatsiya\w*|txt)\b""")
        private val FILE_VERB_RE = Regex("""\b(?:top|qidir|och|ko'rsat|izla)\w*""")
        private val EXTENSIONS = linkedMapOf(
            "pdf" to "pdf", "docx" to "doc", "doc" to "doc", "word" to "doc", "excel" to "xls", "xls" to "xls",
            "pptx" to "ppt", "ppt" to "ppt", "taqdimot" to "ppt", "prezentatsiya" to "ppt", "txt" to "txt"
        )
        private val CALL_RE = Regex("""\b(?:bog'lan\w*|qo'ng'iroq\w*(?: qil\w*)?|telefon qil\w*|tel qil\w*|chaqir\w*|zvonok\w*|qo'ng'iroq)""")
        private val DATIVE_RE = Regex("""(?:ga|ka|qa|gа)$""")
        private val NOTIFICATION_RE = Regex("""\b(?:bildirishnoma\w*|xabarnoma\w*|notifikatsiya\w*|notification\w*|bildirish)""")
        private val CLEAR_RE = Regex("""\b(?:tozala|o'chir|yop|tashla)\w*""")
        private val EMAIL_RE = Regex("""\b(?:email\w*|e-mail\w*|pochta\w*|gmail\w*|xat\b|xatlar\w*|xatni|xatga|maktub\w*|xat yoz\w*|xat yubor\w*)""")
        private val EMAIL_ADDRESS_RE = Regex("""[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}""")
        private val SEND_RE = Regex("""\b(?:yubor|jo'nat)\w*""")
        private val DRAFT_RE = Regex("""\b(?:qoralama\w*|tayyorla\w*|yoz\w*)""")
        private val BODY_RE = Regex("""(?i)(?::|\bmatni\b|\bdeb yoz\b)\s*(.+)$""")
        private val SUBJECT_RE = Regex("""\bmavzu(?:si)?\s+(.+?)(?:\s+matni\b|$)""")
        private val RECIPIENT_RE = Regex("""^(?:\w+\s+){0,2}?(\w+?)(?:ga|ka|qa)\s+(?:xat|email|pochta)""")
        private val CALENDAR_RE = Regex("""\b(?:taqvim\w*|kalendar\w*|calendar\w*|tadbir\w*)""")

        private val REMINDER_RE = Regex("""\b(?:eslat\w*|budilnik\w*|signal qo'y\w*)""")
        private val HABIT_RE = Regex("""\b(?:odat\w*)""")
        private val PENDING_RE = Regex("""\b(?:tugallanmagan|bajarilmagan|qolgan|qilinmagan|kutilayotgan|tugatilmagan|bitmagan)\w*""")
        private val PLAN_RE = Regex("""\b(?:reja\w*\s+tuz\w*|rejala\w*\s+tuz\w*|rejalashtirib ber\w*|kunimni rejala\w*|kunni rejala\w*|jadval\w* tuz\w*|kun tartib\w* tuz\w*|rejani yangila\w*)""")
        private val COMPLETE_RE = Regex("""\b(?:bajardim|bajarildi|bajarilgan deb|tugatdim|tugatildi|bitdi|bitirdim|qildim|tayyor bo'ldi|belgila\w*)""")
        private val RESCHEDULE_RE = Regex("""\b(?:ko'chir\w*|sur\w*|o'zgartir\w*|kechiktir\w*)""")
        private val DELETE_RE = Regex("""\b(?:o'chir\w*|bekor qil\w*|olib tashla\w*|yo'q qil\w*)""")
        private val SUMMARY_RE = Regex("""\b(?:bugungi reja\w*|rejam\w*|rejalarim\w*|vazifalarim\w*|nima bor|nima ishlar|nechta vazifa\w*|kun tartib\w*|jadvalim\w*|nima qilishim kerak)""")
        private val ADD_RE = Regex("""\b(?:qo'sh\w*|yarat\w*|rejalashtir\w*|kirit\w*|belgilab qo'y\w*|yozib qo'y\w*|yozib ol\w*|qo'yib qo'y\w*)""")
        private val SEARCH_RE = Regex("""\b(?:qidir\w*|top\b|topib ber\w*|izla\w*)""")
        private val TIME_QUERY_RE = Regex("""\b(?:soat necha|necha bo'ldi|bugun nechanchi|qaysi kun|bugun qanday kun|sana qanday)""")
        private val GREETING_RE = Regex("""\b(?:salom|assalomu|assalom|hayrli tong|xayrli tong|hayrli kech|xayrli kech|qalaysan|qalay)\w*""")
        private val THANKS_RE = Regex("""\b(?:rahmat|tashakkur|barakalla|ofarin|zo'r)\w*""")
        private val HELP_RE = Regex("""\b(?:nima qila olasan|yordam|imkoniyat\w*|qanday buyruq\w*|kimsan)""")

        private val TASK_NOUN_RE = Regex("""\b(?:vazifa\w*|ish\s+(?=qo'sh)|topshiriq\w*|eslatma\w*|reja\w*|taqvimga|kalendarga)\b""")
        private val PRIORITY_WORDS_RE = Regex("""\b(?:muhim|tezkor|shoshilinch)\b""")
        private val FILLER_RE = Regex("""\b(?:iltimos|menga|mening|meni|mani|manga|uchun|kuni|bilan birga|deb|ni|ga|qilib|ber|qo'y|kerak|hamma|barcha|ham|endi|bitta|yangi|yana)\b""")
        private val QUESTION_RE = Regex("""\b(?:nima|qanday|qaysi|mi)\b""")
    }
}
