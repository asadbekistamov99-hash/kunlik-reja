package com.jarvis.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.jarvis.core.JarvisEngine
import com.jarvis.core.JarvisResponse
import com.jarvis.core.ResponseCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

enum class AssistantStatus { OFF, STANDBY, PAUSED, LISTENING, THINKING, SPEAKING, ERROR }

data class VoiceUiState(
    val status: AssistantStatus = AssistantStatus.OFF,
    val level: Float = 0f,
    val partial: String = "",
    val lastUser: String = "",
    val lastReply: String = "",
    val lastCard: ResponseCard? = null,
    val error: String? = null,
    val sessionActive: Boolean = false,
    val wakeEngine: String = "",
    val wakeKeyword: String = "",
    val sttEngine: String = "",
    /** Number of listening turns started since launch (observable proof a wake/assist trigger worked). */
    val listenCount: Int = 0
)

/** Implemented by the foreground service, which owns the wake-word microphone. */
interface WakeController {
    fun pauseDetection()
    fun resumeDetection()
    fun pauseMicrophone() = pauseDetection()
    fun resumeMicrophone() = resumeDetection()
}

/**
 * Drives one voice interaction: the wake word (or mic button) opens a turn; Jarvis listens,
 * thinks, answers aloud and — in session mode ("Jarvis boshla") or after asking a question —
 * keeps listening across silent turns until "Stop Jarvis" or explicit cancellation.
 */
class VoiceSession(
    private val engine: JarvisEngine,
    private val stt: SpeechToText,
    private val tts: SpeechOutput,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    @Volatile var wakeController: WakeController? = null
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun setStandby(status: AssistantStatus, engineName: String = _state.value.wakeEngine, keyword: String = _state.value.wakeKeyword) {
        _state.update { if (isBusy) it.copy(wakeEngine = engineName, wakeKeyword = keyword) else it.copy(status = status, wakeEngine = engineName, wakeKeyword = keyword, level = 0f) }
    }

    fun onWakeWord() {
        if (isBusy) return
        beep()
        startConversation()
    }

    /** Mic button in the UI. */
    fun startListening() {
        if (isBusy) {
            cancel()
            return
        }
        startConversation()
    }

    /** Typed command from the assistant screen; the answer is spoken too. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        job?.cancel()
        engine.beginSession()
        launchTurn {
            val response = think(text)
            speak(response)
            if (response.expectsReply || engine.isSessionActive) conversationLoop()
        }
    }

    fun cancel() {
        engine.endSession()
        job?.cancel()
        tts.stop()
    }

    private fun startConversation() {
        engine.beginSession()
        launchTurn { conversationLoop() }
    }

    /**
     * Runs one interaction with the wake-word mic released. Detection resumes from
     * invokeOnCompletion so the service sees the turn as finished (job no longer active).
     */
    private fun launchTurn(block: suspend () -> Unit) {
        val turn = scope.launch(start = CoroutineStart.LAZY) {
            _state.update { it.copy(error = null) }
            try {
                wakeController?.pauseDetection()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Voice turn failed", e)
                engine.endSession()
                _state.update { it.copy(status = AssistantStatus.ERROR, error = "Ovozli yordamchi to'xtadi. Mikrofon va ovoz xizmatini tekshirib, qayta urinib ko'ring.") }
            }
        }
        job = turn
        turn.invokeOnCompletion { if (job === turn || job?.isActive != true) finishTurn() }
        turn.start()
    }

    private suspend fun conversationLoop() {
        while (currentCoroutineContext().isActive) {
            if (!stt.isAvailable()) {
                engine.endSession()
                _state.update { it.copy(status = AssistantStatus.ERROR, error = "Mikrofon ruxsati yoki ovozni tanish xizmati mavjud emas. Sozlamalarni tekshiring.") }
                return
            }
            _state.update { it.copy(status = AssistantStatus.LISTENING, partial = "", error = null, level = 0f, listenCount = it.listenCount + 1) }
            var alts: List<String> = emptyList()
            val text = try {
                val heard = stt.listen(object : SttEvents {
                    override fun onPartial(text: String) = _state.update { it.copy(partial = text) }
                    override fun onLevel(level: Float) = _state.update { it.copy(level = level) }
                    override fun onAlternatives(alternatives: List<String>) { alts = alternatives }
                })
                _state.update { it.copy(sttEngine = stt.id) }
                // Of the recognizer's guesses, act on the one Jarvis understands best.
                if (alts.size > 1) engine.bestTranscript(alts) ?: heard else heard
            } catch (e: CancellationException) {
                throw e
            } catch (e: SttException) {
                Log.w(TAG, "STT failed", e)
                _state.update { it.copy(status = AssistantStatus.ERROR, error = e.message) }
                if (!e.recoverable) { engine.endSession(); return }
                delay(1_000)
                continue
            }
            if (text.isNullOrBlank()) {
                if (!engine.isSessionActive) return
                delay(750)
                continue
            }
            val response = think(text, alts)
            speak(response)
            if (response.endSession) return
            if (!response.expectsReply && !engine.isSessionActive) return
        }
    }

    private suspend fun think(text: String, alternatives: List<String> = emptyList()): JarvisResponse {
        _state.update { it.copy(status = AssistantStatus.THINKING, lastUser = text, partial = "", level = 0f) }
        val response = engine.handle(text, alternatives)
        _state.update {
            it.copy(lastReply = response.text, lastCard = response.card ?: it.lastCard.takeIf { response.text.isBlank() },
                sessionActive = engine.isSessionActive)
        }
        return response
    }

    private suspend fun speak(response: JarvisResponse) {
        if (response.text.isBlank()) return
        _state.update { it.copy(status = AssistantStatus.SPEAKING) }
        tts.speak(response.text)
        when (response.intent) {
            com.jarvis.core.IntentType.PAUSE_MICROPHONE -> wakeController?.pauseMicrophone()
            com.jarvis.core.IntentType.RESUME_MICROPHONE -> wakeController?.resumeMicrophone()
            else -> Unit
        }
    }

    private fun finishTurn() {
        _state.update {
            val base = if (it.status == AssistantStatus.ERROR) it else it.copy(error = null)
            base.copy(status = if (it.status == AssistantStatus.ERROR) AssistantStatus.ERROR else if (wakeController != null) AssistantStatus.STANDBY else AssistantStatus.OFF,
                level = 0f, partial = "", sessionActive = engine.isSessionActive)
        }
        wakeController?.resumeDetection()
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            scope.launch { kotlinx.coroutines.delay(300); tone.release() }
        }
    }

    private companion object {
        const val TAG = "VoiceSession"
    }
}
