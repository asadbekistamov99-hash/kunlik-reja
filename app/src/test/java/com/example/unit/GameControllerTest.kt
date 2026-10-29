package com.example.unit

import com.jarvis.phone.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GameControllerTest {
    private class Driver : PhoneDriver {
        var current = PhoneScreen("org.lichess", listOf(PhoneNode(0, "Play", true, false, false)))
        var acted = 0
        override suspend fun screen(withImage: Boolean) = current
        override suspend fun act(screen: PhoneScreen, step: PhoneStep): Boolean {
            if (current.packageName != screen.packageName) return false
            acted++
            return true
        }
    }
    private class Planner(val choose: suspend (PhoneScreen) -> PhoneStep) : GamePlanner {
        var calls = 0
        override fun available() = true
        override suspend fun next(goal: String, screen: PhoneScreen, previous: List<PhoneStep>, mode: com.jarvis.phone.ChessMode): PhoneStep {
            calls++
            return choose(screen)
        }
    }

    @Test fun `no consent means no screenshot planning or moves`() = runTest {
        val driver = Driver()
        val planner = Planner { PhoneStep(PhoneAction.CLICK, 0) }
        val game = GameController(driver, planner, this, { true }, { false }, 0)
        assertFalse(game.start("puzzle yech").success)
        advanceUntilIdle()
        assertEquals(0, planner.calls)
        assertEquals(0, driver.acted)
    }

    @Test fun `game ends only on model done and reports status`() = runTest {
        val driver = Driver()
        var count = 0
        val planner = Planner { if (count++ == 0) PhoneStep(PhoneAction.CLICK, 0) else PhoneStep(PhoneAction.DONE) }
        val game = GameController(driver, planner, this, { true }, { true }, 0)
        assertTrue(game.start("puzzle yech").success)
        advanceUntilIdle()
        assertEquals(1, driver.acted)
        assertEquals(2, planner.calls)
        assertTrue(game.status.value.contains("yakunlandi"))
        assertFalse(game.running)
    }

    @Test fun `stop during planning prevents a delayed move`() = runTest {
        val driver = Driver()
        val result = CompletableDeferred<PhoneStep>()
        val planner = Planner { result.await() }
        val game = GameController(driver, planner, this, { true }, { true }, 0)
        game.start("bot bilan shaxmat o'yna")
        runCurrent()
        game.stop()
        result.complete(PhoneStep(PhoneAction.CLICK, 0))
        advanceUntilIdle()
        assertEquals(0, driver.acted)
    }

    @Test fun `changing app stops moves and prevents next app upload`() = runTest {
        val driver = Driver()
        val planner = Planner {
            driver.current = PhoneScreen("org.telegram", emptyList())
            PhoneStep(PhoneAction.CLICK, 0)
        }
        val game = GameController(driver, planner, this, { true }, { true }, 0)
        game.start("bot bilan shaxmat o'yna")
        advanceUntilIdle()
        assertEquals(0, driver.acted)
        assertEquals(1, planner.calls)
        assertTrue(game.status.value.startsWith("To'xtadi"))
    }

    @Test fun `repeated moves are bounded and password screen is not sent`() = runTest {
        val driver = Driver()
        val planner = Planner { PhoneStep(PhoneAction.CLICK, 0) }
        val game = GameController(driver, planner, this, { true }, { true }, 0)
        game.start("puzzle yech")
        advanceUntilIdle()
        assertEquals(12, driver.acted)
        assertFalse(game.running)
        driver.current = driver.current.copy(sensitive = true)
        game.start("puzzle yech")
        advanceUntilIdle()
        assertEquals(12, planner.calls)
    }

    @Test fun `consent revoked between moves stops further planning`() = runTest {
        val driver = Driver()
        var consent = true
        val planner = Planner { consent = false; PhoneStep(PhoneAction.CLICK, 0) }
        val game = GameController(driver, planner, this, { true }, { consent }, 0)
        game.start("puzzle yech")
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        assertEquals(0, driver.acted)
        assertTrue(game.status.value.startsWith("To'xtadi"))
    }
    @Test fun `rated chess and unspecified chess do not invoke the planner`() = runTest {
        val driver = Driver()
        val planner = Planner { PhoneStep(PhoneAction.CLICK, 0) }
        val game = GameController(driver, planner, this, { true }, { true }, 0)
        assertFalse(game.start("shaxmat o'yna").success)
        driver.current = driver.current.copy(nodes = listOf(PhoneNode(0, "Rated", true, false, false)))
        assertFalse(game.start("bot bilan shaxmat o'yna").success)
        advanceUntilIdle()
        assertEquals(0, planner.calls)
        assertEquals(0, driver.acted)
    }

}
