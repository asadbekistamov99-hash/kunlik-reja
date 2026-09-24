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

    /**
     * Confidence of the best "jarvis" word in a final Vosk result (requires setWords(true)),
     * or null when "jarvis" was not recognised at all.
     */
    fun jarvisConfidence(json: String): Float? = WORD_RE.findAll(json)
        .filter { it.groupValues[1] == KEYWORD }
        .mapNotNull { CONF_RE.find(it.value)?.groupValues?.get(1)?.toFloatOrNull() }
        .maxOrNull()

    /**
     * Only final results with word confidences can trigger: partial hypotheses carry no
     * confidence, and a grammar decoder will happily emit "jarvis" for similar-sounding speech.
     */
    fun onResult(json: String): Boolean = (jarvisConfidence(json) ?: 0f) >= minConfidence

    companion object {
        const val KEYWORD = "jarvis"

        /**
         * Keyword grammar with "filler" words: other speech is decoded as these competing words
         * instead of being forced onto "jarvis" (the classic keyword-spotting garbage model).
         * Includes near-homophones of "jarvis" so they are not mistaken for it.
         */
        val FILLERS = listOf(
            "service", "harvest", "travis", "davis", "java", "jazz", "jar", "jars", "nervous", "garbage", "marvel",
            "office", "purpose", "carve", "chaos", "charge", "choose", "just", "jobs", "joyous", "army", "far",
            "hey", "hi", "hello", "good", "morning", "evening", "night", "how", "are", "you", "the", "a", "and",
            "to", "is", "it", "yes", "no", "what", "time", "today", "tomorrow", "work", "call", "okay", "please",
            "thank", "this", "that", "there", "here", "with", "for", "on", "in", "of", "at", "one", "two", "three",
            "four", "five", "six", "seven", "eight", "nine", "ten", "go", "so", "do", "say", "see", "me", "my", "we",
            "she", "he", "her", "his", "car", "bus", "house", "water", "music", "phone", "open", "close", "start",
            "stop", "buy", "sell", "sir", "boss", "mister", "miss", "is", "was", "will", "can", "all", "our"
        )
        val GRAMMAR: String = (listOf(KEYWORD) + FILLERS.distinct() + "[unk]").joinToString(", ", "[", "]") { "\"$it\"" }

        private val WORD_RE = Regex("""\{[^{}]*"word"\s*:\s*"([^"]*)"[^{}]*\}""")
        private val CONF_RE = Regex(""""conf"\s*:\s*([0-9.]+)""")
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
    private val matcher = JarvisKeywordMatcher((0.98f - sensitivity * 0.3f).coerceIn(0.6f, 0.95f))
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
        val preRoll = ArrayDeque<ShortArray>()
        var active = false
        var silent = 0
        var noiseFloor = 0.004f

        fun detected() {
            val now = SystemClock.elapsedRealtime()
            rec.reset(); active = false; preRoll.clear()
            if (now - lastDetection > cooldownMs) {
                lastDetection = now
                onDetected()
            }
        }

        fun feed(frame: ShortArray): Boolean =
            rec.acceptWaveForm(frame, frame.size) && matcher.onResult(rec.result)

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
                            rec.reset(); active = false
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
