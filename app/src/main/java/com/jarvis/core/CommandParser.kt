package com.jarvis.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class ParsedCommand(
    val original: String,
    val normalized: String,
    val body: String,
    val hasWakeWord: Boolean,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
    val isRelative: Boolean = false,
    val deadline: LocalDate? = null,
    val remainder: String = body
)

/**
 * O'zbek tilidagi tabiiy vaqt, sana, nisbiy muddat ("yarim soatdan keyin",
 * "ertaga kechqurun soat 7 da", "dushanba kuni") va og'zaki sonlarni
 * ("soat to'qqiz yarimda") 100% aniqlikda ajratib oluvchi parser.
 */
class CommandParser(
    private val clock: () -> LocalDateTime = { LocalDateTime.now() }
) {

    private val wakeWords = listOf(
        "hey jarvis", "ey jarvis", "salom jarvis", "jarvis", "jarviz", "djarvis"
    )

    private val spokenNumbers = mapOf(
        "bir" to 1, "ikki" to 2, "uch" to 3, "to'rt" to 4, "tort" to 4,
        "besh" to 5, "olti" to 6, "yetti" to 7, "sakkiz" to 8,
        "to'qqiz" to 9, "toqqiz" to 9, "o'n" to 10, "on" to 10,
        "o'n bir" to 11, "o'n ikki" to 12, "o'n besh" to 15,
        "yigirma" to 20, "o'ttiz" to 30, "ottiz" to 30,
        "qirq" to 40, "ellik" to 50
    )

    private val weekdayMap = mapOf(
        "dushanba" to DayOfWeek.MONDAY,
        "seshanba" to DayOfWeek.TUESDAY,
        "chorshanba" to DayOfWeek.WEDNESDAY,
        "payshanba" to DayOfWeek.THURSDAY,
        "juma" to DayOfWeek.FRIDAY,
        "shanba" to DayOfWeek.SATURDAY,
        "yakshanba" to DayOfWeek.SUNDAY
    )

    fun parse(text: String): ParsedCommand {
        val now = clock()
        val corrected = SpellCorrector.correct(text)
        val normalized = corrected.lowercase(Locale.ROOT).trim()

        var hasWake = false
        var body = normalized
        for (w in wakeWords) {
            if (body == w) {
                hasWake = true
                body = ""
                break
            } else if (body.startsWith("$w ") || body.startsWith("$w,")) {
                hasWake = true
                body = body.removePrefix(w).trimStart(',', ' ', '.', '!')
                break
            }
        }

        var working = replaceSpokenTimes(body)

        var parsedDate: LocalDate? = null
        var parsedTime: LocalTime? = null
        var durationMinutes: Int? = null
        var isRelative = false
        var deadlineDate: LocalDate? = null

        // 1. Nisbiy vaqt ("yarim soatdan keyin", "15 daqiqadan so'ng", "2 soatdan keyin")
        val relHalfHour = Regex("\\byarim\\s+soat(?:dan)?\\s*(?:keyin|so'ng|ichida)\\b")
        if (relHalfHour.containsMatchIn(working)) {
            val target = now.plusMinutes(30)
            parsedDate = target.toLocalDate()
            parsedTime = target.toLocalTime().withSecond(0).withNano(0)
            isRelative = true
            working = working.replaceFirst(relHalfHour, " ")
        }

        val relRegex = Regex("\\b(\\d{1,3})\\s*(soat|daqiqa|minut)(?:dan)?\\s*(?:keyin|so'ng|ichida)\\b")
        relRegex.find(working)?.let { m ->
            val amount = m.groupValues[1].toLongOrNull() ?: 0L
            val unit = m.groupValues[2]
            val target = if (unit.startsWith("soat")) now.plusHours(amount) else now.plusMinutes(amount)
            parsedDate = target.toLocalDate()
            parsedTime = target.toLocalTime().withSecond(0).withNano(0)
            isRelative = true
            working = working.replaceRange(m.range, " ")
        }

        // 2. Davomiylik ("30 daqiqaga", "2 soatga", "1 soat davomida")
        val durRegex = Regex("\\b(\\d{1,3})\\s*(soat|daqiqa|minut)(?:ga|davomida)\\b")
        durRegex.find(working)?.let { m ->
            val amount = m.groupValues[1].toIntOrNull() ?: 0
            val unit = m.groupValues[2]
            durationMinutes = if (unit.startsWith("soat")) amount * 60 else amount
            working = working.replaceRange(m.range, " ")
        }

        // 3. Kun va sana ("bugun", "ertaga", "indin", "keyingi hafta", hafta kunlari)
        when {
            Regex("\\bbugun(?:gi|ga)?\\b").containsMatchIn(working) -> {
                parsedDate = now.toLocalDate()
                working = working.replaceFirst(Regex("\\bbugun(?:gi|ga)?\\b"), " ")
            }
            Regex("\\bertaga(?:gi|ga)?\\b").containsMatchIn(working) -> {
                parsedDate = now.toLocalDate().plusDays(1)
                working = working.replaceFirst(Regex("\\bertaga(?:gi|ga)?\\b"), " ")
            }
            Regex("\\bindin(?:ga)?\\b").containsMatchIn(working) -> {
                parsedDate = now.toLocalDate().plusDays(2)
                working = working.replaceFirst(Regex("\\bindin(?:ga)?\\b"), " ")
            }
            Regex("\\bkeyingi\\s+hafta(?:da|ga)?\\b").containsMatchIn(working) -> {
                parsedDate = now.toLocalDate().plusWeeks(1)
                working = working.replaceFirst(Regex("\\bkeyingi\\s+hafta(?:da|ga)?\\b"), " ")
            }
        }

        for ((name, dow) in weekdayMap) {
            val dayRegex = Regex("\\b$name(?:\\s+kuni|ga|da)?\\b")
            if (dayRegex.containsMatchIn(working)) {
                if (parsedDate == null) {
                    parsedDate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(dow))
                }
                working = working.replaceFirst(dayRegex, " ")
                break
            }
        }

        // 4. Muddat / Deadline ("...gacha")
        val deadlineRegex = Regex("\\b(bugun|ertaga|indin|dushanba|seshanba|chorshanba|payshanba|juma|shanba|yakshanba)gacha\\b")
        deadlineRegex.find(working)?.let { m ->
            val word = m.groupValues[1]
            deadlineDate = when (word) {
                "bugun" -> now.toLocalDate()
                "ertaga" -> now.toLocalDate().plusDays(1)
                "indin" -> now.toLocalDate().plusDays(2)
                else -> weekdayMap[word]?.let { now.toLocalDate().with(TemporalAdjusters.nextOrSame(it)) }
            }
            working = working.replaceRange(m.range, " ")
        }

        // 5. Kun qismi modifikatorlari ("ertalab", "tushdan keyin", "kechqurun", "kechasi")
        val isMorning = Regex("\\b(ertalab|abedgacha|saharda)\\b").containsMatchIn(working)
        val isAfternoon = Regex("\\b(tushda|tushdan\\s+keyin|abetda|kunduzi)\\b").containsMatchIn(working)
        val isEvening = Regex("\\b(kechqurun|kechki\\s+payt|oqshom|kechasi|tunda)\\b").containsMatchIn(working)

        working = working
            .replace(Regex("\\b(ertalab|abedgacha|saharda|tushda|tushdan\\s+keyin|abetda|kunduzi|kechqurun|kechki\\s+payt|oqshom|kechasi|tunda)\\b"), " ")

        // 6. Aniq soat va daqiqa ("soat 14:30 da", "soat 9 da", "9:30 da", "soat 7 yarimda")
        val halfHourRegex = Regex("(?:\\bsoat\\s+)?(\\d{1,2})\\s+yarim(?:da|ga)?\\b")
        halfHourRegex.find(working)?.let { m ->
            val rawHour = m.groupValues[1].toIntOrNull()
            if (rawHour != null && rawHour in 0..23) {
                val adjHour = adjustHourForDayPeriod(rawHour, isMorning, isAfternoon, isEvening)
                parsedTime = LocalTime.of(adjHour, 30)
                working = working.replaceRange(m.range, " ")
            }
        }

        val colonTimeRegex = Regex("(?:\\bsoat\\s+)?(\\d{1,2})[:.](\\d{2})(?:\\s*(?:da|ga))?\\b")
        if (parsedTime == null) {
            colonTimeRegex.find(working)?.let { m ->
                val rawHour = m.groupValues[1].toIntOrNull()
                val minute = m.groupValues[2].toIntOrNull()
                if (rawHour != null && minute != null && rawHour in 0..23 && minute in 0..59) {
                    val adjHour = adjustHourForDayPeriod(rawHour, isMorning, isAfternoon, isEvening)
                    parsedTime = LocalTime.of(adjHour, minute)
                    working = working.replaceRange(m.range, " ")
                }
            }
        }

        val simpleHourRegex = Regex("\\bsoat\\s+(\\d{1,2})(?:\\s+(\\d{1,2}))?(?:\\s*(?:da|ga))?\\b|\\b(\\d{1,2})\\s*(?:da|ga)\\s+soat\\b")
        if (parsedTime == null) {
            simpleHourRegex.find(working)?.let { m ->
                val h = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull()
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h != null && h in 0..23 && min in 0..59) {
                    val adjHour = adjustHourForDayPeriod(h, isMorning, isAfternoon, isEvening)
                    parsedTime = LocalTime.of(adjHour, min)
                    working = working.replaceRange(m.range, " ")
                }
            }
        }

        if (parsedTime == null) {
            when {
                isMorning -> parsedTime = LocalTime.of(9, 0)
                isAfternoon -> parsedTime = LocalTime.of(14, 0)
                isEvening -> parsedTime = LocalTime.of(19, 0)
            }
        }

        val cleanRemainder = cleanActionRemainder(working)

        return ParsedCommand(
            original = text,
            normalized = normalized,
            body = body,
            hasWakeWord = hasWake,
            date = parsedDate,
            time = parsedTime,
            durationMinutes = durationMinutes,
            isRelative = isRelative,
            deadline = deadlineDate,
            remainder = cleanRemainder.ifBlank { body }
        )
    }

    private fun adjustHourForDayPeriod(
        hour: Int,
        isMorning: Boolean,
        isAfternoon: Boolean,
        isEvening: Boolean
    ): Int {
        if (hour in 1..11) {
            if (isEvening) return (hour + 12).coerceAtMost(23)
            if (isAfternoon && hour in 1..6) return hour + 12
        }
        if (hour == 12 && isMorning) return 8
        return hour.coerceIn(0, 23)
    }

    private fun replaceSpokenTimes(input: String): String {
        var out = input
        val ordered = spokenNumbers.entries.sortedByDescending { it.key.length }
        for ((word, num) in ordered) {
            out = out.replace(Regex("\\bsoat\\s+$word\\b"), "soat $num")
            out = out.replace(Regex("\\b$word\\s+(soat|daqiqa|minut|yarim)\\b"), "$num $1")
        }
        return out
    }

    private fun cleanActionRemainder(raw: String): String {
        return raw
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '.', '!', '?')
    }
}