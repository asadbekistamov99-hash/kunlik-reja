package com.jarvis.core

import java.util.Locale

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
    WORK_PATTERNS,
    TIME_QUERY,
    GREETING,
    THANKS,
    HELP,
    UNKNOWN
}

data class ResolvedIntent(
    val type: IntentType,
    val confidence: Float,
    val slots: Map<String, String> = emptyMap(),
    val source: String = "offline"
) {
    fun slot(name: String): String? = slots[name]?.takeIf { it.isNotBlank() }
}

/**
 * O'zbek tilidagi og'zaki va adabiy buyruqlarni yuqori aniqlik bilan
 * 32 ta tizim IntentType'ga ajratuvchi va slotlarni toza chiqarib beruvchi yechimchi.
 */
class IntentResolver {

    fun resolve(cmd: ParsedCommand): ResolvedIntent {
        val body = cmd.body.trim().lowercase(Locale.ROOT)
        if (body.isBlank()) {
            return if (cmd.hasWakeWord) {
                ResolvedIntent(IntentType.WAKE, 0.99f)
            } else {
                ResolvedIntent(IntentType.UNKNOWN, 0.1f)
            }
        }

        // 1. Suhbatni boshlash / to'xtatish
        if (body.matches(Regex("^(muloqotni\\s+boshla|meni\\s+eshit|gaplashamiz|suhbatni\\s+boshla|tingla)$"))) {
            return ResolvedIntent(IntentType.START_SESSION, 0.98f)
        }
        if (body.matches(Regex("^(to'xta|uxla|jim\\s+bo'l|yetarli|suhbatni\\s+tugat|xayr|bo'ldi\\s+rahmat)$"))) {
            return ResolvedIntent(IntentType.STOP_SESSION, 0.98f)
        }

        // 2. Salomlashish / Rahmat / Yordam / Soat
        if (body.matches(Regex("^(salom|assalomu\\s+alaykum|xayrli\\s+tong|xayrli\\s+kun|xayrli\\s+kech|qalaysan|ishlar\\s+qalay)$"))) {
            return ResolvedIntent(IntentType.GREETING, 0.96f)
        }
        if (body.matches(Regex("^(rahmat|katta\\s+rahmat|tashakkur|barakalla|yaxshi|zo'r)$"))) {
            return ResolvedIntent(IntentType.THANKS, 0.95f)
        }
        if (body.matches(Regex("^(yordam|nima\\s+qila\\s+olasan|imkoniyatlaring|buyruqlar|qanday\\s+ishlaysan).*$"))) {
            return ResolvedIntent(IntentType.HELP, 0.95f)
        }
        if (body.contains("soat necha") || body.contains("vaqt necha") || body.contains("bugun qaysi kun") || body.contains("sana necha")) {
            return ResolvedIntent(IntentType.TIME_QUERY, 0.97f)
        }

        // 3. Kamera
        if (body.contains("kamerani och") || body.contains("rasmga ol") || body.contains("suratga ol") || body.contains("videoga ol")) {
            val isVideo = body.contains("video")
            return ResolvedIntent(IntentType.OPEN_CAMERA, 0.96f, mapOf("mode" to if (isVideo) "video" else "photo"))
        }

        // 4. Qo'ng'iroq qilish
        Regex("^(?:iltimos\\s+)?(.+?)(?:ga|ni\\s+raqamiga)\\s+(?:qo'ng'iroq\\s+qil|telefon\\s+qil|sim\\s+qoq|ter)\\b").find(body)?.let { m ->
            val contactName = cleanEntityName(m.groupValues[1])
            if (contactName.isNotBlank()) {
                return ResolvedIntent(IntentType.CALL_CONTACT, 0.96f, mapOf("name" to contactName))
            }
        }
        Regex("^(?:qo'ng'iroq\\s+qil|telefon\\s+qil)\\s+(.+)$").find(body)?.let { m ->
            val contactName = cleanEntityName(m.groupValues[1])
            if (contactName.isNotBlank()) {
                return ResolvedIntent(IntentType.CALL_CONTACT, 0.95f, mapOf("name" to contactName))
            }
        }

        // 5. Bildirishnomalar
        if (body.contains("bildirishnomalarni o'chir") || body.contains("xabarlarni tozalab tashla")) {
            return ResolvedIntent(IntentType.CLEAR_NOTIFICATIONS, 0.95f)
        }
        if (body.contains("bildirishnoma") || body.contains("yangi xabarlar bormi") || body.contains("kim yozdi")) {
            return ResolvedIntent(IntentType.READ_NOTIFICATIONS, 0.93f)
        }

        // 6. Email (Gmail)
        if (body.contains("xatlarni o'qi") || body.contains("pochta") || body.contains("yangi email")) {
            return ResolvedIntent(IntentType.EMAIL_READ, 0.92f)
        }
        if (body.contains("email yubor") || body.contains("xat jo'nat")) {
            return ResolvedIntent(IntentType.EMAIL_SEND, 0.90f, mapOf("body" to cmd.remainder))
        }
        if (body.contains("qoralama yoz") || body.contains("email yoz") || body.contains("xat tayyorla")) {
            return ResolvedIntent(IntentType.EMAIL_DRAFT, 0.90f, mapOf("body" to cmd.remainder))
        }

        // 7. Taqvim (Google Calendar)
        if (body.contains("taqvimdan o'chir") || body.contains("uchrashuvni bekor qil")) {
            val title = extractCleanTitle(cmd.remainder)
            return ResolvedIntent(IntentType.CALENDAR_DELETE, 0.92f, mapOf("title" to title))
        }
        if (body.contains("taqvimda nima bor") || body.contains("uchrashuvlarim") || body.contains("kalendarni o'qi")) {
            return ResolvedIntent(IntentType.CALENDAR_READ, 0.93f)
        }

        // 8. Fayl qidirish
        Regex("(?:faylni\\s+top|hujjatni\\s+och|fayl\\s+qidir)\\s*(.*)").find(body)?.let { m ->
            val q = cleanEntityName(m.groupValues[1].ifBlank { cmd.remainder })
            return ResolvedIntent(IntentType.FIND_FILE, 0.92f, mapOf("query" to q))
        }

        // 9. Xotira (Eslab qol / Eslat / Unut)
        Regex("^(?:eslab\\s+qol|xotiraga\\s+yoz|yodda\\s+saqla)[:\\s]+(.+)$").find(body)?.let { m ->
            return ResolvedIntent(IntentType.REMEMBER, 0.96f, mapOf("fact" to m.groupValues[1].trim()))
        }
        Regex("^(.+?)\\s+(?:eslab\\s+qol|yodda\\s+tut)$").find(body)?.let { m ->
            return ResolvedIntent(IntentType.REMEMBER, 0.95f, mapOf("fact" to m.groupValues[1].trim()))
        }
        if (body.contains("men haqimda nima bilasan") || body.contains("esingdami") || (body.startsWith("mening ") && body.endsWith(" nima"))) {
            return ResolvedIntent(IntentType.RECALL, 0.92f, mapOf("query" to cmd.remainder))
        }
        if (body.startsWith("unut ") || body.endsWith("xotiradan o'chir")) {
            val q = extractCleanTitle(cmd.remainder)
            return ResolvedIntent(IntentType.FORGET, 0.93f, mapOf("query" to q))
        }

        // 10. Odat (Habit)
        if (body.contains("har kuni") || body.contains("odat qo'sh") || body.contains("odatim")) {
            val title = extractCleanTitle(cmd.remainder)
            return ResolvedIntent(IntentType.ADD_HABIT, 0.94f, mapOf("title" to title))
        }

        // 11. Vazifani bajarilgan qilish (Complete Task)
        if (body.contains("bajarildi") || body.contains("bajardim") || body.contains("bitdi") || body.contains("yakunlandi") || body.contains("tayyor bo'ldi")) {
            val title = extractCleanTitle(cmd.remainder)
            return ResolvedIntent(IntentType.COMPLETE_TASK, 0.96f, mapOf("title" to title))
        }

        // 12. Vazifani o'chirish (Delete Task)
        if (body.contains("vazifani o'chir") || body.contains("rejadan olib tashla") || body.endsWith("ni o'chir") || body.startsWith("o'chir ")) {
            val title = extractCleanTitle(cmd.remainder)
            return ResolvedIntent(IntentType.DELETE_TASK, 0.95f, mapOf("title" to title))
        }

        // 13. Vazifani boshqa vaqtga ko'chirish (Reschedule Task)
        if (body.contains("ko'chir") || body.contains("surib qo'y") || body.contains("kechiktir") || body.contains("vaqtini o'zgartir")) {
            val title = extractCleanTitle(cmd.remainder)
            return ResolvedIntent(IntentType.RESCHEDULE_TASK, 0.94f, mapOf("title" to title))
        }

        // 14. Kunlik reja, xulosa, ro'yxat va ish unumdorligi
        if (body.contains("kunni rejalashtir") || body.contains("jadval tuz") || body.contains("bo'sh vaqtga joyla")) {
            return ResolvedIntent(IntentType.PLAN_DAY, 0.95f)
        }
        if (body.contains("kunlik xulosa") || body.contains("bugungi hisobot") || body.contains("bugun nima qildim") || body.contains("bugungi reja")) {
            return ResolvedIntent(IntentType.DAY_SUMMARY, 0.95f)
        }
        if (body.contains("vazifalar ro'yxati") || body.contains("qanday vazifalar bor") || body.contains("nima ishlarim bor") || body.contains("bajarilmagan vazifalar")) {
            return ResolvedIntent(IntentType.LIST_PENDING, 0.95f)
        }
        if (body.contains("ishlash odatlarim") || body.contains("unumdorlik") || body.contains("qachon yaxshi ishlayman")) {
            return ResolvedIntent(IntentType.WORK_PATTERNS, 0.93f)
        }
        if (body.startsWith("vazifa qidir") || body.contains("vazifalar ichidan top")) {
            val q = extractCleanTitle(cmd.remainder)
            return ResolvedIntent(IntentType.SEARCH_TASKS, 0.92f, mapOf("query" to q))
        }

        // 15. Eslatma (Reminder)
        if (body.contains("eslatma") || body.contains("eslatib qo'y") || body.contains("esimga sol") || body.contains("budilnik") || body.contains("taymer")) {
            val title = extractCleanTitle(cmd.remainder).ifBlank { "Eslatma" }
            return ResolvedIntent(IntentType.ADD_REMINDER, 0.96f, mapOf("title" to title))
        }

        // 16. Vazifa qo'shish (Add Task) вЂ” aniq kalit so'zlar yoki vaqt/sana + harakat bo'lganda
        if (
            body.contains("vazifa qo'sh") ||
            body.contains("rejaga qo'sh") ||
            body.contains("yozib qo'y") ||
            body.contains("qo'shib qo'y") ||
            body.contains("qilishim kerak") ||
            body.contains("borishim kerak") ||
            ((cmd.time != null || cmd.date != null) && cmd.remainder.isNotBlank())
        ) {
            val title = extractCleanTitle(cmd.remainder)
            if (title.isNotBlank()) {
                return ResolvedIntent(IntentType.ADD_TASK, 0.94f, mapOf("title" to title))
            }
        }

        return ResolvedIntent(IntentType.UNKNOWN, 0.35f, mapOf("query" to cmd.body))
    }

    private fun extractCleanTitle(raw: String): String {
        val noisePatterns = listOf(
            Regex("^(?:iltimos|jarvis|menga|shu|yangi)\\s+", RegexOption.IGNORE_CASE),
            Regex("\\b(?:vazifani|vazifa|rejaga|rejani|eslatmani|eslatma|jadvalga)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:qo'shib\\s+qo'y|yozib\\s+qo'y|eslatib\\s+qo'y|esimga\\s+sol|qo'sh|yarat|o'rnat|bajarildi\\s+deb\\s+belgila|bajarildi|bajardim|bitdi|o'chirib\\s+tashla|o'chir|ko'chirib\\s+qo'y|ko'chir)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:deb|degan|nomli)\\b", RegexOption.IGNORE_CASE)
        )
        var result = raw
        for (p in noisePatterns) {
            result = result.replace(p, " ")
        }
        result = result
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '.', '!', '?', '-', ':')

        if (result.endsWith("shni") && result.length > 6) {
            result = result.removeSuffix("ni")
        }
        return result.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun cleanEntityName(raw: String): String {
        return raw
            .replace(Regex("\\b(iltimos|jarvis|menga|tezda|hozir)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}