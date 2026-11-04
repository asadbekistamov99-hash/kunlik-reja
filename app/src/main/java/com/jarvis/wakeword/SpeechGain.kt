package com.jarvis.wakeword

/**
 * Lifts quiet speech toward the level the detectors were trained on, so "Hey Jarvis" said a
 * metre or two away, or with the phone in a pocket, still gets through. Gain only rises while
 * there is real speech energy, and it is capped so room noise is never amplified much.
 */
class SpeechGain(
    private val target: Float = TARGET_LEVEL,
    private val maxGain: Float = MAX_GAIN,
    private val floor: Float = NOISE_FLOOR
) {
    /** Recent peak level, decaying slowly so a short word does not reset the gain at once. */
    private var envelope = 0f

    fun apply(frame: ShortArray): ShortArray {
        envelope = maxOf(AudioListener.rms(frame), envelope * DECAY)
        // Below the floor it is just silence or hiss: amplifying it only adds false wake-ups.
        if (envelope < floor) return frame
        val gain = (target / envelope).coerceIn(1f, maxGain)
        if (gain <= 1f) return frame
        return ShortArray(frame.size) { i ->
            (frame[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun reset() {
        envelope = 0f
    }

    companion object {
        /** RMS (0..1) that a spoken word is lifted to. */
        const val TARGET_LEVEL = 0.08f
        /** Never boost more than this, so noise stays noise. */
        const val MAX_GAIN = 4f
        /** Below this RMS the input is treated as silence and left alone. */
        const val NOISE_FLOOR = 0.0015f
        private const val DECAY = 0.98f
    }
}
