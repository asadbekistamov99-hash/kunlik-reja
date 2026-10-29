package com.jarvis.phone

enum class ChessMode { GENERAL, PUZZLE, BOT }

object ChessPractice {
    fun mode(goal: String, packageName: String): ChessMode? {
        val chess = Regex("chess|lichess|shaxmat", RegexOption.IGNORE_CASE).containsMatchIn("$packageName $goal")
        if (!chess) return ChessMode.GENERAL
        return when {
            Regex("puzzle|boshqotirma|taktika", RegexOption.IGNORE_CASE).containsMatchIn(goal) -> ChessMode.PUZZLE
            Regex("\\bbot\\b|kompyuter|computer|stockfish", RegexOption.IGNORE_CASE).containsMatchIn(goal) -> ChessMode.BOT
            else -> null
        }
    }

    fun ratedScreen(screen: PhoneScreen): Boolean = screen.nodes.any {
        Regex("""\b(?:rated|ranked|reytingli|рейтингов\p{L}*)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.label)
    }

    fun completedPgn(text: String): Boolean {
        if (text.length !in 10..30_000) return false
        val moves = text.replace(Regex("""\[[^\]]*\]|\{[^}]*\}|;[^\n]*"""), " ").trim()
        return Regex("""\b1\.\s*\S+""").containsMatchIn(moves) &&
            Regex("""(?:1-0|0-1|1/2-1/2)\s*$""").containsMatchIn(moves)
    }
}
