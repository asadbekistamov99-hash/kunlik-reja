package com.example.jarvis.wakeword

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.jarvis.settings.WakeEngineChoice

/** Offline wake-word detector. Implementations own the microphone while started. */
interface WakeWordEngine {
    val name: String
    val keywordLabel: String
    fun start(onDetected: () -> Unit, onError: (Throwable) -> Unit)
    fun stop()
    fun release()
}

/**
 * Picovoice Porcupine with its built-in "Jarvis" keyword. Detection runs fully on-device; the
 * AccessKey is only validated with Picovoice's servers periodically.
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val accessKey: String,
    private val sensitivity: Float
) : WakeWordEngine {
    override val name = "Porcupine"
    override val keywordLabel = "Jarvis"
    private var manager: PorcupineManager? = null

    override fun start(onDetected: () -> Unit, onError: (Throwable) -> Unit) {
        try {
            if (manager == null) {
                manager = PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(sensitivity.coerceIn(0f, 1f))
                    .setErrorCallback { e -> onError(e) }
                    .build(context, PorcupineManagerCallback { onDetected() })
            }
            manager?.start()
        } catch (e: PorcupineException) {
            onError(e)
        }
    }

    override fun stop() {
        try { manager?.stop() } catch (e: PorcupineException) { Log.w(TAG, "stop", e) }
    }

    override fun release() {
        stop()
        manager?.delete()
        manager = null
    }

    private companion object { const val TAG = "PorcupineEngine" }
}

/** Bundled openWakeWord "hey Jarvis" model — needs no key and no network at all. */
class OpenWakeWordEngine(
    private val context: Context,
    private val sensitivity: Float,
    private val cooldownMs: Long = 2000
) : WakeWordEngine {
    override val name = "openWakeWord"
    override val keywordLabel = "Hey Jarvis"
    private val audio = AudioListener(context)
    private var model: JarvisKeywordModel? = null
    private var lastDetection = 0L

    /** Maps the 0.1..0.95 sensitivity slider to a score threshold (higher sensitivity = lower threshold). */
    private val threshold: Float get() = (0.85f - sensitivity * 0.6f).coerceIn(0.25f, 0.8f)

    override fun start(onDetected: () -> Unit, onError: (Throwable) -> Unit) {
        val m = try {
            model ?: JarvisKeywordModel.load(context).also { model = it }
        } catch (t: Throwable) {
            onError(t)
            return
        }
        m.reset()
        audio.start(
            onFrame = { frame ->
                val score = try { m.process(frame) } catch (t: Throwable) { onError(t); 0f }
                val now = SystemClock.elapsedRealtime()
                if (score >= threshold && now - lastDetection > cooldownMs) {
                    lastDetection = now
                    m.reset()
                    onDetected()
                }
            },
            onError = onError
        )
    }

    override fun stop() = audio.stop()

    override fun release() {
        stop()
        model?.close()
        model = null
    }
}

object WakeWordEngineFactory {
    fun create(context: Context, choice: WakeEngineChoice, picovoiceKey: String?, sensitivity: Float): WakeWordEngine {
        val hasKey = !picovoiceKey.isNullOrBlank() && picovoiceKey != "MY_PICOVOICE_ACCESS_KEY"
        return when {
            choice == WakeEngineChoice.OPEN_WAKE_WORD -> OpenWakeWordEngine(context, sensitivity)
            hasKey -> PorcupineWakeWordEngine(context, picovoiceKey!!, sensitivity)
            else -> OpenWakeWordEngine(context, sensitivity)
        }
    }
}
