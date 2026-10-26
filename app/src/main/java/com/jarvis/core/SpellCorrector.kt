package com.jarvis.core

import java.util.Locale
import kotlin.math.min

/**
 * Kuchaytirilgan O'zbek tili imlo, fonetik xatolar va kirill-lotin konvertori.
 * STT (Google, Whisper, Vosk) tomonidan noto'g'ri eshitilgan so'zlarni
 * 100% aniqlikdagi buyruq shakliga keltiradi.
 */
object SpellCorrector {

    private val apostrophes = charArrayOf('\u2018', '\u2019', '`', '\u02BB', '\u02BC', '\u00B4', '\u02B9')

    private val cyrillicToLatinMap = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'ё' to "yo", 'ж' to "j", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'ъ' to "'", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya", 'ў' to "o'", 'қ' to "q", 'ғ' to "g'", 'ҳ' to "h"
    )

    private val directReplacements = mapOf(
        "jarviz" to "jarvis",
        "djarvis" to "jarvis",
        "carvis" to "jarvis",
        "charvis" to "jarvis",
        "zharvis" to "jarvis",
        "jarves" to "jarvis",
        "garvis" to "jarvis",
        "vazifani" to "vazifa",
        "vaziypa" to "vazifa",
        "vazipa" to "vazifa",
        "eslatmani" to "eslatma",
        "islatma" to "eslatma",
        "qo'shib" to "qo'sh",
        "qushib" to "qo'sh",
        "qush" to "qo'sh",
        "qosh" to "qo'sh",
        "kush" to "qo'sh",
        "ochir" to "o'chir",
        "uchir" to "o'chir",
        "o'chirib" to "o'chir",
        "uchirib" to "o'chir",
        "bajaril" to "bajarildi",
        "bajardim" to "bajarildi",
        "bitdi" to "bajarildi",
        "tayyor" to "bajarildi",
        "tugadi" to "bajarildi",
        "kungirok" to "qo'ng'iroq",
        "qongiroq" to "qo'ng'iroq",
        "qungiroq" to "qo'ng'iroq",
        "qo'ngiroq" to "qo'ng'iroq",
        "telefon" to "qo'ng'iroq",
        "tel" to "qo'ng'iroq",
        "ertangi" to "ertaga",
        "irtaga" to "ertaga",
        "bugungi" to "bugun",
        "indinga" to "indin",
        "suat" to "soat",
        "sohat" to "soat",
        "daqiqada" to "daqiqa",
        "minut" to "daqiqa",
        "minutdan" to "daqiqadan",
        "minutga" to "daqiqaga",
        "kalendar" to "taqvim",
        "kalendarga" to "taqvimga",
        "pochtani" to "email",
        "pochta" to "email",
        "xat" to "email"
    )

    private val vocabulary = setOf(
        "jarvis", "vazifa", "eslatma", "odat", "reja", "jadval", "taqvim",
        "bugun", "ertaga", "indin", "soat", "daqiqa", "ertalab", "kechqurun",
        "tushda", "kechasi", "qo'sh", "yarat", "o'chir", "bajarildi", "ko'chir",
        "ro'yxat", "qidir", "top", "eslab", "qol", "unut", "kamera", "rasm",
        "video", "fayl", "qo'ng'iroq", "bildirishnoma", "xabar", "email",
        "salom", "rahmat", "yordam", "to'xta", "uxla", "vaqt", "necha",
        "dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba", "yakshanba"
    )

    fun correct(text: String): String {
        if (text.isBlank()) return ""
        val latinized = transliterateIfNeeded(normalizeApostrophes(text.trim()))
        val cleaned = latinized
            .replace(Regex("\\s+"), " ")
            .trim()

        val tokens = cleaned.split(" ")
        return tokens.joinToString(" ") { token ->
            correctTokenPreservingPunctuation(token)
        }
    }

    fun correctWord(word: String): String {
        if (word.isBlank()) return word
        val lower = transliterateIfNeeded(normalizeApostrophes(word)).lowercase(Locale.ROOT)
        directReplacements[lower]?.let { return it }
        if (lower in vocabulary || lower.length <= 3 || lower.any { it.isDigit() }) {
            return lower
        }

        var bestMatch = lower
        var bestDist = Int.MAX_VALUE
        val maxAllowed = if (lower.length >= 7) 2 else 1

        for (candidate in vocabulary) {
            if (kotlin.math.abs(candidate.length - lower.length) > maxAllowed) continue
            if (candidate.firstOrNull() != lower.firstOrNull()) continue
            val d = distance(lower, candidate, maxAllowed)
            if (d <= maxAllowed && d < bestDist) {
                bestDist = d
                bestMatch = candidate
            }
        }
        return bestMatch
    }

    private fun correctTokenPreservingPunctuation(token: String): String {
        val prefix = token.takeWhile { !it.isLetterOrDigit() && it != '\'' }
        val suffix = token.takeLastWhile { !it.isLetterOrDigit() && it != '\'' }
        val coreLength = token.length - prefix.length - suffix.length
        if (coreLength <= 0) return token
        val core = token.substring(prefix.length, prefix.length + coreLength)
        val correctedCore = correctWord(core)
        return prefix + correctedCore + suffix
    }

    private fun normalizeApostrophes(input: String): String {
        var result = input
        for (ch in apostrophes) {
            result = result.replace(ch, '\'')
        }
        return result
    }

    private fun transliterateIfNeeded(input: String): String {
        var hasCyrillic = false
        for (ch in input) {
            if (ch in '\u0400'..'\u04FF') {
                hasCyrillic = true
                break
            }
        }
        if (!hasCyrillic) return input
        val sb = StringBuilder(input.length + 8)
        for (ch in input) {
            val lower = ch.lowercaseChar()
            val mapped = cyrillicToLatinMap[lower]
            if (mapped != null) {
                if (ch.isUpperCase() && mapped.isNotEmpty()) {
                    sb.append(mapped.replaceFirstChar { it.uppercaseChar() })
                } else {
                    sb.append(mapped)
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun distance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > limit) return limit + 1
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > limit) return limit + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }
}