package com.jarvis.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Result of parsing one utterance: normalized text plus the date/time/duration it mentions. */
data class ParsedCommand(
    val original: String,
    /** Lower-case, Latin-script, punctuation-normalized text including the wake word. */
    val normalized: String,
    /** [normalized] with the leading wake word ("jarvis", "hey jarvis") removed. */
    val body: String,
    val hasWakeWord: Boolean,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
    /** True when the time came from a relative phrase such as "30 daqiqadan keyin". */
    val isRelative: Boolean = false,
    /** Due date from phrases like "jumagacha" / "muddati 25 sentabr". */
    val deadline: LocalDate? = null,
    /** [body] with date/time/duration phrases removed — the raw material for titles. */
    val remainder: String = body
)

/**
 * Deterministic parser for Uzbek (Latin or Cyrillic) voice commands. It never needs the network,
 * which is what lets Jarvis keep working offline.
 */
class CommandParser(private val clock: () -> LocalDateTime = { LocalDateTime.now() }) {

    fun parse(text: String): ParsedCommand {
        val normalized = normalize(text)
        val (body, hasWake) = stripWakeWord(normalized)
        val now = clock()
        var working = body
        var date: LocalDate? = null
        var time: LocalTime? = null
        var duration: Int? = null
        var relative = false

        // 1. Relative offsets: "30 daqiqadan keyin", "2 soatdan so'ng", "yarim soatdan keyin"
        RELATIVE_RE.find(working)?.let { m ->
            val amount = if (m.groupValues[1] == "yarim") 30 else m.groupValues[1].toInt()
            val minutes = if (m.groupValues[2].startsWith("soat") && m.groupValues[1] != "yarim") amount * 60 else amount
            val target = now.plusMinutes(minutes.toLong())
            date = target.toLocalDate()
            time = target.toLocalTime().withSecond(0).withNano(0)
            relative = true
            working = working.removeRange(m.range)
        }

        // 2. Durations: "30 daqiqalik", "1 soatlik", "yarim soat", "45 daqiqa"
        DURATION_RE.find(working)?.let { m ->
            val raw = m.groupValues[1]
            val unit = m.groupValues[2]
            duration = when {
                raw == "yarim" -> 30
                unit.startsWith("soat") -> raw.toInt() * 60
                else -> raw.toInt()
            }
            working = working.removeRange(m.range)
        }

        // 3. Deadlines ("jumagacha", "25 sentabrgacha", "muddati ertaga") before plain dates,
        //    so "jumagacha" is read as a due date rather than the day to do the task.
        var deadline: LocalDate? = null
        for (m in DEADLINE_RE.findAll(working)) {
            val phrase = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                .replace(Regex("""^erta$"""), "ertaga")
            val found = extractDate(phrase, now) ?: continue
            deadline = found.first
            working = working.removeRange(m.range)
            break
        }

        // 4. Explicit dates
        if (date == null) {
            extractDate(working, now)?.let { (d, range) ->
                date = d
                working = working.removeRange(range)
            }
        }

        // 5. Times
        var period: String? = null
        PERIOD_RE.find(working)?.let { m ->
            period = m.groupValues[1]
            working = working.removeRange(m.range)
        }
        if (time == null) {
            val clockMatch = CLOCK_RE.find(working) ?: SOAT_DOT_RE.find(working)
            if (clockMatch != null) {
                val h = clockMatch.groupValues[1].toInt()
                val min = clockMatch.groupValues[2].toInt()
                if (h in 0..23 && min in 0..59) {
                    time = LocalTime.of(adjustHour(h, period, explicitMinutes = true), min)
                    working = working.removeRange(clockMatch.range)
                }
            }
        }
        if (time == null) {
            val m = SOAT_RE.find(working) ?: BARE_HOUR_RE.find(working)
            if (m != null) {
                val h = m.groupValues[1].toInt()
                if (h in 0..23) {
                    val half = m.groupValues.getOrNull(2)?.contains("yarim") == true
                    time = LocalTime.of(adjustHour(h, period, explicitMinutes = false), if (half) 30 else 0)
                    working = working.removeRange(m.range)
                }
            }
        }
        if (time == null && period != null) {
            time = DEFAULT_PERIOD_TIME[period]
        }

        return ParsedCommand(
            original = text,
            normalized = normalized,
            body = body,
            hasWakeWord = hasWake,
            date = date,
            time = time,
            durationMinutes = duration,
            isRelative = relative,
            deadline = deadline,
            remainder = working.replace(SPACES, " ").trim()
        )
    }

    /** Finds the first date expression in [text]; returns the date and the matched range. */
    private fun extractDate(text: String, now: LocalDateTime): Pair<LocalDate, IntRange>? {
        val today = now.toLocalDate()
        ISO_DATE_RE.find(text)?.let { m ->
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                .getOrNull()?.let { return it to m.range }
        }
        MONTH_DATE_RE.find(text)?.let { m ->
            val month = MONTHS.indexOfFirst { m.groupValues[2].startsWith(it) } + 1
            runCatching { rollForward(LocalDate.of(now.year, month, m.groupValues[1].toInt()), today) }
                .getOrNull()?.let { return it to m.range }
        }
        DOTTED_DATE_RE.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            if (day in 1..31 && month in 1..12 && (day > 23 || m.value.contains('/'))) {
                runCatching { rollForward(LocalDate.of(now.year, month, day), today) }.getOrNull()?.let { return it to m.range }
            }
        }
        IN_DAYS_RE.find(text)?.let { m -> return today.plusDays(m.groupValues[1].toLong()) to m.range }
        for ((re, offset) in RELATIVE_DAYS) {
            val m = re.find(text) ?: continue
            return today.plusDays(offset) to m.range
        }
        WEEKDAY_RE.find(text)?.let { m ->
            val dow = WEEKDAYS.getValue(m.groupValues[1])
            val date = if (m.groupValues[0].contains("keyingi")) today.plusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
            else today.with(TemporalAdjusters.nextOrSame(dow))
            return date to m.range
        }
        return null
    }

    private fun adjustHour(h: Int, period: String?, explicitMinutes: Boolean): Int = when {
        period == null -> if (!explicitMinutes && h in 1..6) h + 12 else h
        period in EVENING_PERIODS -> if (h in 1..11) h + 12 else h
        period == "kechasi" -> if (h == 12) 0 else if (h in 7..11) h + 12 else h
        period in AFTERNOON_PERIODS -> if (h in 1..7) h + 12 else h
        else -> if (h == 12) 0 else h // ertalab / tongda
    }

    private fun rollForward(date: LocalDate, today: LocalDate) =
        if (date.isBefore(today)) date.plusYears(1) else date

    companion object {
        private val SPACES = Regex("""\s+""")

        private val CYRILLIC = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "j",
            'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
            'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "ts",
            'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "'", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu",
            'я' to "ya", 'ў' to "o'", 'қ' to "q", 'ғ' to "g'", 'ҳ' to "h"
        )

        private val NUMBER_WORDS = mapOf(
            "bir" to 1, "ikki" to 2, "uch" to 3, "to'rt" to 4, "besh" to 5, "olti" to 6, "yetti" to 7,
            "sakkiz" to 8, "to'qqiz" to 9, "o'n" to 10, "yigirma" to 20, "o'ttiz" to 30, "qirq" to 40,
            "ellik" to 50, "oltmish" to 60
        )

        /** Lower-cases, transliterates Cyrillic, unifies apostrophes and converts number words. */
        fun normalize(text: String): String {
            val sb = StringBuilder()
            for (ch in text.lowercase(Locale.ROOT)) {
                when {
                    CYRILLIC.containsKey(ch) -> sb.append(CYRILLIC.getValue(ch))
                    ch in "ʻʼ‘’`´′" -> sb.append('\'')
                    ch.isLetterOrDigit() || ch == '\'' || ch == ':' || ch == '.' || ch == '-' || ch == '/' || ch == '@' || ch == '_' -> sb.append(ch)
                    else -> sb.append(' ')
                }
            }
            // Trailing sentence dots are noise; keep dots inside tokens (times, emails, file names).
            val cleaned = sb.toString().replace(Regex("""(?<![\w@])[.\-/]|[.\-/](?![\w])"""), " ")
            return wordsToNumbers(cleaned.replace(SPACES, " ").trim())
        }

        private fun wordsToNumbers(text: String): String {
            val tokens = text.split(' ')
            val out = ArrayList<String>(tokens.size)
            var i = 0
            while (i < tokens.size) {
                val value = NUMBER_WORDS[tokens[i]]
                if (value != null) {
                    var total = value
                    if (value >= 10 && i + 1 < tokens.size) {
                        val unit = NUMBER_WORDS[tokens[i + 1]]
                        if (unit != null && unit < 10) {
                            total += unit
                            i++
                        }
                    }
                    out += total.toString()
                } else {
                    out += tokens[i]
                }
                i++
            }
            return out.joinToString(" ")
        }

        private val WAKE_RE = Regex("""^(?:hey |hi |ey |salom )?(?:jarvis|jarviz|djarvis|jarvis's|jervis)\b[,\s]*""")

        fun stripWakeWord(normalized: String): Pair<String, Boolean> {
            val m = WAKE_RE.find(normalized) ?: return normalized to false
            return normalized.removeRange(m.range).trim() to true
        }

        private val MONTHS = listOf(
            "yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr"
        )
        private val WEEKDAYS = mapOf(
            "dushanba" to DayOfWeek.MONDAY, "seshanba" to DayOfWeek.TUESDAY, "chorshanba" to DayOfWeek.WEDNESDAY,
            "payshanba" to DayOfWeek.THURSDAY, "juma" to DayOfWeek.FRIDAY, "shanba" to DayOfWeek.SATURDAY,
            "yakshanba" to DayOfWeek.SUNDAY
        )
        private val EVENING_PERIODS = setOf("kechqurun", "kechki", "kech")
        private val AFTERNOON_PERIODS = setOf("tushdan keyin", "kunduzi", "tushlikdan keyin", "peshin")
        private val DEFAULT_PERIOD_TIME = mapOf(
            "ertalab" to LocalTime.of(9, 0), "tongda" to LocalTime.of(7, 0), "tushlikda" to LocalTime.of(13, 0),
            "tushdan keyin" to LocalTime.of(15, 0), "tushlikdan keyin" to LocalTime.of(15, 0),
            "kunduzi" to LocalTime.of(14, 0), "peshin" to LocalTime.of(13, 0), "kechqurun" to LocalTime.of(19, 0),
            "kechki" to LocalTime.of(19, 0), "kech" to LocalTime.of(19, 0), "kechasi" to LocalTime.of(22, 0)
        )

        private val RELATIVE_RE = Regex("""\b(\d{1,3}|yarim)\s*(daqiqa|minut|soat)(?:dan|lardan)?\s+(?:keyin|so'ng|o'tib)\b""")
        private val DURATION_RE = Regex("""\b(\d{1,3}|yarim)\s*(daqiqa|minut|soat)(?:lik|ga)?\b(?!\s*(?:da|dan)\b)""")
        private val DEADLINE_RE = Regex("""(\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[\s-]*[a-z']+|[a-z']+)(?:\s+kuni)?gacha\b|\bmuddati\s+((?:\d{1,2}\s+)?[a-z0-9'-]+)""")
        private val ISO_DATE_RE = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
        private val MONTH_DATE_RE = Regex("""\b(\d{1,2})[\s-]*(?:chi\s+|-chi\s+)?(yanvar|fevral|mart|aprel|may|iyun|iyul|avgust|sentabr|oktabr|noyabr|dekabr)\w*""")
        private val DOTTED_DATE_RE = Regex("""\b(\d{1,2})[./](\d{1,2})\b(?![./:]\d)""")
        private val IN_DAYS_RE = Regex("""\b(\d{1,2})\s*kun(?:dan)?\s+(?:keyin|so'ng)\b""")
        private val RELATIVE_DAYS = listOf(
            Regex("""\bertadan keyin\b|\bindinga\b|\bindin\b""") to 2L,
            Regex("""\bertaga\b|\bertangi\b|\bertasi\b""") to 1L,
            Regex("""\bbugun\w*\b|\bhozir\b""") to 0L,
            Regex("""\bkecha\b|\bkechagi\b""") to -1L,
            Regex("""\bkeyingi haftada\b|\bkeyingi hafta\b""") to 7L
        )
        private val WEEKDAY_RE = Regex("""(?:keyingi\s+)?\b(dushanba|seshanba|chorshanba|payshanba|juma|shanba|yakshanba)\w*(?:\s+kuni)?""")
        private val PERIOD_RE = Regex("""\b(ertalab|tongda|tushlikdan keyin|tushdan keyin|tushlikda|kunduzi|peshin|kechqurun|kechki|kechasi)\b""")
        private val CLOCK_RE = Regex("""(?:\bsoat\s*)?\b(\d{1,2}):(\d{2})\b(?:\s*(?:da|ga|gacha|dan)\b)?""")
        private val SOAT_DOT_RE = Regex("""\bsoat\s*(\d{1,2})\.(\d{2})\b(?:\s*(?:da|ga)\b)?""")
        private val SOAT_RE = Regex("""\bsoat\s*(\d{1,2})\s*(yarim\w*|da|ga|larda|lar|dan)?\b(?:\s*(?:da|ga)\b)?""")
        private val BARE_HOUR_RE = Regex("""\b(\d{1,2})\s*(yarimda|da|ga|larda)\b""")
    }
}
