package com.jarvis.core

/**
 * Snaps words the speech recognizer slightly misheard onto Jarvis' command vocabulary
 * ("rejeni" → "rejani", "eslar" → "eslat"). Only used as a second attempt when the first
 * interpretation was not confident, so correct sentences are never altered.
 */
object SpellCorrector {

    val VOCABULARY: Set<String> = setOf(
        // actions
        "qo'sh", "qo'shib", "qo'shgin", "yarat", "rejalashtir", "kirit", "tuz", "tuzib", "ko'rsat", "ko'rsatgin",
        "eslat", "eslatib", "eslatgin", "bajardim", "bajarildi", "tugatdim", "o'chir", "o'chirib", "ko'chir",
        "o'zgartir", "och", "ochib", "top", "topib", "qidir", "bog'lan", "qo'ng'iroq", "o'qi", "o'qib", "tozala",
        "yubor", "yoz", "eslab", "qol", "unut", "boshla", "tugat", "to'xta", "tayyorla", "belgila",
        // objects
        "reja", "rejani", "rejam", "rejamni", "vazifa", "vazifani", "vazifalarim", "vazifalarimni", "ishlarimni",
        "ishlarim", "tugallanmagan", "bajarilmagan", "qolgan", "uchrashuv", "uchrashuvni", "yig'ilish", "majlis",
        "kamera", "kamerani", "fayl", "faylimni", "fayllarni", "hujjat", "bildirishnoma", "bildirishnomalarni",
        "xat", "xatlarni", "xatlar", "pochta", "taqvim", "taqvimni", "kalendar", "odat", "odatlarim", "eslatma",
        "doktor", "shifokor", "hisobot", "taqdimot",
        // time words
        "soat", "ertaga", "bugun", "bugungi", "ertangi", "indinga", "daqiqa", "daqiqadan", "soatdan", "keyin",
        "ertalab", "kechqurun", "kechasi", "tushdan", "yarim", "yarimda", "muddati", "dushanba", "seshanba",
        "chorshanba", "payshanba", "juma", "shanba", "yakshanba", "kuni",
        // small talk / session
        "jarvis", "yordam", "rahmat", "salom", "assalomu", "alaykum", "necha", "samarali", "qanday", "nima"
    )

    fun correct(text: String): String = text.split(' ').joinToString(" ") { word -> correctWord(word) }

    fun correctWord(word: String): String {
        if (word.length < 4 || word in VOCABULARY || word.any { it.isDigit() }) return word
        val maxDistance = if (word.length >= 8) 2 else 1
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in VOCABULARY) {
            if (kotlin.math.abs(candidate.length - word.length) > maxDistance) continue
            val d = distance(word, candidate, maxDistance)
            if (d < bestDistance) { bestDistance = d; best = candidate }
        }
        return if (best != null && bestDistance <= maxDistance) best else word
    }

    /** Levenshtein distance with early exit once every row exceeds [limit]. */
    fun distance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return rowMin
            prev = cur
        }
        return prev[b.length]
    }
}
