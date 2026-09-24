package com.jarvis.wakeword

import android.content.Context
import android.os.SystemClock
import com.jarvis.voice.VoskModel
import kotlinx.coroutines.runBlocking
import org.vosk.Recognizer

/**
 * Decides from Vosk JSON output whether "Jarvis" was said. Kept free of Android/JSON libraries
 * so it can be unit-tested on the JVM.
 */
class JarvisKeywordMatcher(private val minConfidence: Float) {
    private var partialHits = 0

    /** Partial hypotheses arrive every ~100 ms; two in a row avoids single-frame flukes. */
    fun onPartial(json: String): Boolean {
        val text = field(json, "partial")
        partialHits = if (containsJarvis(text)) partialHits + 1 else 0
        return partialHits >= 2
    }

    /** Final result of an utterance, with per-word confidences when available. */
    fun onResult(json: String): Boolean {
        val confidences = WORD_RE.findAll(json)
            .filter { it.groupValues.any { g -> g == KEYWORD } }
            .mapNotNull { CONF_RE.find(it.value)?.groupValues?.get(1)?.toFloatOrNull() }
            .toList()
        if (confidences.isNotEmpty()) return confidences.any { it >= minConfidence }
        return containsJarvis(field(json, "text"))
    }

    fun reset() { partialHits = 0 }

    companion object {
        const val KEYWORD = "jarvis"
        /** Vosk grammar: only "jarvis" or "unknown speech" can be recognised, which keeps decoding cheap and precise. */
        const val GRAMMAR = """["jarvis", "[unk]"]"""
        private val WORD_RE = Regex("""\{[^{}]*"word"\s*:\s*"([^"]*)"[^{}]*\}""")
        private val CONF_RE = Regex(""""conf"\s*:\s*([0-9.]+)""")

        fun field(json: String, name: String): String =
            Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1).orEmpty()

        fun containsJarvis(text: String) = text.split(' ').any { it == KEYWORD }
    }
}

/**
 * Offline wake word for the single word "Jarvis" without any API key. A grammar-restricted Vosk
 * recognizer (English small model, downloaded once) spots the keyword; audio is only decoded
 * while there is speech energy, so the recognizer idles in silence to save battery.
 */
class VoskKeywordEngine(
    context: Context,
    private val model: VoskModel,
    sensitivity: Float,
    private val cooldownMs: Long = 2000
) : WakeWordEngine {
    override val name = "Vosk"
    override val keywordLabel = "Jarvis"
    private val audio = AudioListener(context, frameSize = FRAME)
    private val matcher = JarvisKeywordMatcher((0.95f - sensitivity * 0.5f).coerceIn(0.4f, 0.9f))
    private var recognizer: Recognizer? = null
    private var lastDetection = 0L

    override fun start(onDetected: () -> Unit, onError: (Throwable) -> Unit) {
        val rec = try {
            recognizer ?: Recognizer(runBlocking { model.load() }, AudioListener.SAMPLE_RATE.toFloat(), JarvisKeywordMatcher.GRAMMAR)
                .also { it.setWords(true); recognizer = it }
        } catch (t: Throwable) {
            onError(t)
            return
        }
        rec.reset()
        matcher.reset()
        val preRoll = ArrayDeque<ShortArray>()
        var active = false
        var silent = 0
        var noiseFloor = 0.004f

        fun detected() {
            val now = SystemClock.elapsedRealtime()
            rec.reset(); matcher.reset(); active = false; preRoll.clear()
            if (now - lastDetection > cooldownMs) {
                lastDetection = now
                onDetected()
            }
        }

        fun feed(frame: ShortArray): Boolean {
            val endpoint = rec.acceptWaveForm(frame, frame.size)
            return if (endpoint) matcher.onResult(rec.result) else matcher.onPartial(rec.partialResult)
        }

        audio.start(
            onFrame = { frame ->
                try {
                    val level = AudioListener.rms(frame)
                    val voiced = level > maxOf(MIN_SPEECH_LEVEL, noiseFloor * 3f)
                    if (!active) {
                        noiseFloor = noiseFloor * 0.95f + level * 0.05f
                        preRoll.addLast(frame)
                        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        if (voiced) {
                            active = true
                            silent = 0
                            var hit = false
                            while (preRoll.isNotEmpty() && !hit) hit = feed(preRoll.removeFirst())
                            if (hit) detected()
                        }
                    } else {
                        silent = if (voiced) 0 else silent + 1
                        if (feed(frame)) detected()
                        else if (silent >= END_SILENCE_FRAMES) {
                            if (matcher.onResult(rec.finalResult)) detected()
                            rec.reset(); matcher.reset(); active = false
                        }
                    }
                } catch (t: Throwable) {
                    onError(t)
                }
            },
            onError = onError
        )
    }

    override fun stop() = audio.stop()

    override fun release() {
        stop()
        recognizer?.close()
        recognizer = null
    }

    private companion object {
        const val FRAME = 1600 // 100 ms
        const val PRE_ROLL_FRAMES = 5
        const val END_SILENCE_FRAMES = 8
        const val MIN_SPEECH_LEVEL = 0.006f
    }
}
