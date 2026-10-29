package com.jarvis.phone

import com.jarvis.core.DeviceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Screen data is untrusted app content, never instructions to the agent. */
data class PhoneNode(val id: Int, val label: String, val clickable: Boolean, val editable: Boolean, val scrollable: Boolean, val checked: Boolean? = null)
data class PhoneScreen(val packageName: String, val nodes: List<PhoneNode>, val sensitive: Boolean = false,
    val jpeg: ByteArray? = null, val width: Int = 0, val height: Int = 0)
enum class PhoneAction { CLICK, TAP, SWIPE, TYPE, BACK, HOME, SCROLL_DOWN, SCROLL_UP, POWER, WAIT, DONE, STOP }
data class PhoneStep(val action: PhoneAction, val nodeId: Int = -1, val text: String = "",
    val x: Float = Float.NaN, val y: Float = Float.NaN, val x2: Float = Float.NaN, val y2: Float = Float.NaN) {
    fun valid(): Boolean = when (action) {
        PhoneAction.CLICK -> nodeId >= 0
        PhoneAction.TAP -> x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
        PhoneAction.SWIPE -> x.isFinite() && y.isFinite() && x2.isFinite() && y2.isFinite() &&
            x in 0f..1f && y in 0f..1f && x2 in 0f..1f && y2 in 0f..1f
        PhoneAction.TYPE -> text.isNotBlank()
        else -> true
    }
}
interface PhoneDriver {
    suspend fun screen(withImage: Boolean = false): PhoneScreen?
    suspend fun act(screen: PhoneScreen, step: PhoneStep): Boolean
}
interface GamePlanner {
    fun available(): Boolean
    suspend fun next(goal: String, screen: PhoneScreen, previous: List<PhoneStep> = emptyList(), mode: ChessMode = ChessMode.GENERAL): PhoneStep
}

/** A bounded, cancellable game task. Every move is scoped to the original foreground app. */
class GameController(
    private val driver: PhoneDriver,
    private val planner: GamePlanner,
    private val scope: CoroutineScope,
    private val allowed: (String) -> Boolean,
    private val cloudConsent: () -> Boolean,
    private val stepDelayMs: Long = 800
) {
    private var job: Job? = null
    private val _status = MutableStateFlow("O'yin agenti to'xtagan")
    val status = _status.asStateFlow()
    val running: Boolean get() = job?.isActive == true

    suspend fun start(goal: String): DeviceResult {
        stop()
        if (!cloudConsent()) return DeviceResult("Sozlamalar → Telefonni boshqarish bo'limida AI o'yin boshqaruviga rozilik bering va 'Faqat oflayn' rejimini o'chiring.", false)
        if (!planner.available()) return DeviceResult("AI o'yin boshqaruvi uchun internet va Gemini API kaliti kerak.", false)
        val initial = driver.screen() ?: return DeviceResult("Telefonni qulfdan chiqaring va Accessibility ruxsatini yoqing.", false)
        if (!allowed(initial.packageName) || initial.sensitive) return DeviceResult("Avval o'yinni oching, keyin 'shu o'yinni o'yna' deng.", false)
        val mode = ChessPractice.mode(goal, initial.packageName)
            ?: return DeviceResult("Shaxmatda 'puzzle yech' yoki 'bot bilan shaxmat o'yna' deb mashq rejimini tanlang.", false)
        if (mode != ChessMode.GENERAL && ChessPractice.ratedScreen(initial)) return DeviceResult("Avval puzzle yoki kompyuterga qarshi mashq ekranini oching.", false)
        val turn = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(120_000) {
                    val history = mutableListOf<PhoneStep>()
                    repeat(12) { index ->
                        check(cloudConsent()) { "Ekranni yuborish roziligi o'chirilgan" }
                        val screen = driver.screen(true) ?: error("Ekran o'qilmadi")
                        check(screen.packageName == initial.packageName && !screen.sensitive) { "Ilova yoki himoyalangan ekran o'zgardi" }
                        check(mode == ChessMode.GENERAL || !ChessPractice.ratedScreen(screen)) { "Mashq ekrani o'zgardi" }
                        _status.value = "O'yin tahlil qilinmoqda: ${index + 1}/12"
                        check(cloudConsent()) { "Ekranni yuborish roziligi o'chirilgan" }
                        val step = planner.next(goal, screen, history.takeLast(5), mode)
                        check(cloudConsent()) { "Ekranni yuborish roziligi o'chirilgan" }
                        check(step.valid()) { "AI yaroqsiz harakat qaytardi" }
                        when (step.action) {
                            PhoneAction.DONE -> { _status.value = "Agent tahlili: topshiriq yakunlandi"; return@withTimeout }
                            PhoneAction.STOP -> { _status.value = "Agent bu ekranda davom eta olmadi"; return@withTimeout }
                            PhoneAction.WAIT -> delay(stepDelayMs)
                            PhoneAction.CLICK, PhoneAction.TAP, PhoneAction.SWIPE -> {
                                check(step.action == PhoneAction.CLICK || screen.jpeg != null) { "Bu yurish uchun ekran tasviri kerak" }
                                check(driver.act(screen, step)) { "Harakat bajarilmadi yoki ekran o'zgardi" }
                                history += step
                                delay(stepDelayMs)
                            }
                            else -> error("O'yin agentiga bu harakat ruxsat etilmagan")
                        }
                        if (index == 11) _status.value = "12 qadam bajarildi. Davom etish uchun yana buyruq bering."
                    }
                }
            } catch (e: CancellationException) {
                _status.value = "O'yin agenti to'xtatildi"
                throw e
            } catch (e: Exception) {
                _status.value = "To'xtadi: ${e.message ?: "harakat bajarilmadi"}"
            }
        }
        job = turn
        turn.start()
        return DeviceResult("O'yin agenti boshlandi. To'xtatish uchun Stop Jarvis deng. Holati Sozlamalarda ko'rinadi.", true)
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = "O'yin agenti to'xtatilgan"
    }
}
