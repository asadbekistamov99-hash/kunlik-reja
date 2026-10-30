package com.example.unit

import com.jarvis.phone.*
import org.junit.Assert.*
import org.junit.Test

class ChessPracticeTest {
    @Test fun `chess requires explicit puzzle or bot practice mode`() {
        assertEquals(ChessMode.PUZZLE, ChessPractice.mode("shaxmat puzzle yech", "org.lichess"))
        assertEquals(ChessMode.BOT, ChessPractice.mode("bot bilan shaxmat o'yna", "com.chess"))
        assertNull(ChessPractice.mode("shaxmat o'yna", "com.chess"))
        assertEquals(ChessMode.GENERAL, ChessPractice.mode("shu o'yinni o'yna", "com.other.game"))
    }

    @Test fun `rated label blocks practice but unrated does not match rated word`() {
        fun screen(label: String) = PhoneScreen("org.lichess", listOf(PhoneNode(0, label, false, false, false)))
        assertTrue(ChessPractice.ratedScreen(screen("Rated Blitz")))
        assertFalse(ChessPractice.ratedScreen(screen("Unrated practice")))
    }

    @Test fun `only completed bounded pgn is accepted for post-game review`() {
        assertTrue(ChessPractice.completedPgn("[Result \"1-0\"]\n1. e4 e5 2. Nf3 Nc6 1-0 {resigned}"))
        assertTrue(ChessPractice.completedPgn("1. e4 e5 1/2-1/2"))
        assertFalse(ChessPractice.completedPgn("1. e4 e5 *"))
        assertFalse(ChessPractice.completedPgn("1-0"))
        assertFalse(ChessPractice.completedPgn("1. e4 " + "a".repeat(30_000) + " 1-0"))
    }
}
