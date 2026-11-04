package com.jarvis.phone

/**
 * Turns a spoken app name ("instagramga", "telegramni", "yutub") into the installed app it means.
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object AppMatcher {

    /** Common spoken forms that differ from the app's label. */
    private val ALIASES = mapOf(
        "shaxmat" to "chess",
        "yutub" to "youtube",
        "vatsap" to "whatsapp",
        "watsapp" to "whatsapp",
        "brauzer" to "chrome",
        "pochta" to "gmail",
        "xarita" to "maps",
        "kalkulyator" to "calculator",
    )

    /** Uzbek case endings a name may carry in speech: "instagramga", "telegramni", "youtube'da". */
    private val ENDINGS = listOf("ning", "dan", "ga", "ka", "qa", "ni", "da")

    /**
     * Returns the apps the spoken [name] refers to. One element means a clear match; more means the
     * name really is ambiguous. Label matches always beat package-name matches.
     */
    fun match(name: String, installed: List<PhoneCommands.App>): List<PhoneCommands.App> {
        val query = name.trim().lowercase()
        if (query.isBlank()) return emptyList()
        val stem = ENDINGS.firstOrNull { query.length > it.length + 2 && query.endsWith(it) }
            ?.let { query.removeSuffix(it) } ?: query
        val terms = listOf(query, stem, ALIASES[query], ALIASES[stem]).filterNotNull().filter { it.isNotBlank() }.distinct()

        val tiers: List<(PhoneCommands.App) -> Boolean> = listOf(
            { app -> terms.any { app.name.equals(it, true) } },
            { app -> terms.any { app.name.startsWith(it, true) } },
            { app -> terms.any { app.name.contains(it, true) } },
            // The speech may carry extra words: "edits ilovasini" still means the app "Edits".
            { app -> app.name.length >= 3 && terms.any { it.contains(app.name, true) } },
            { app -> terms.any { app.packageName.equals(it, true) || app.packageName.contains(it, true) } }
        )
        for (tier in tiers) {
            val hits = installed.filter(tier)
            if (hits.isEmpty()) continue
            // Among equally good hits, the shortest label is the most literal one ("YouTube" over "YouTube Kids").
            val shortest = hits.minOf { it.name.length }
            return hits.filter { it.name.length == shortest }
        }
        return emptyList()
    }
}
