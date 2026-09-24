package com.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jarvis.settings.SttEngineChoice
import com.jarvis.wakeword.AudioListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SttException(message: String, val recoverable: Boolean = true) : Exception(message)

/** Callbacks for live UI feedback while listening. */
interface SttEvents {
    fun onReady() {}
    fun onPartial(text: String) {}
    /** Normalized input level 0..1 for the waveform. */
    fun onLevel(level: Float) {}
}

interface SpeechToText {
    val id: String
    fun isAvailable(): Boolean
    /** Listens for one utterance; returns the transcript, or null if nothing was said. */
    suspend fun listen(events: SttEvents, maxMillis: Long = 10_000): String?
}

/** Google speech recognition through Android's SpeechRecognizer (on-device when offline). */
class AndroidSpeechToText(private val context: Context, private val online: () -> Boolean) : SpeechToText {
    override val id = "google"
    private val main = Handler(Looper.getMainLooper())

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun listen(events: SttEvents, maxMillis: Long): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(maxMillis + 4_000) {
            suspendCancellableCoroutine { cont ->
                val offline = !online()
                val recognizer = if (offline && android.os.Build.VERSION.SDK_INT >= 33 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)

                fun done(block: () -> Unit) {
                    main.post { runCatching { recognizer.destroy() } }
                    if (cont.isActive) block()
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = events.onReady()
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) = events.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = events.onLevel(0f)
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }?.let(events::onPartial)
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        done { cont.resume(text?.takeIf { it.isNotBlank() }) }
                    }
                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> done { cont.resume(null) }
                            else -> done { cont.resumeWithException(SttException(describe(error), recoverable = error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)) }
                        }
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer.startListening(intent)
                cont.invokeOnCancellation {
                    main.post { runCatching { recognizer.cancel(); recognizer.destroy() } }
                }
            }
        }
    }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tarmoq xatosi"
        SpeechRecognizer.ERROR_AUDIO -> "Mikrofon xatosi"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon uchun ruxsat yo'q"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ovoz tanish xizmati band"
        SpeechRecognizer.ERROR_SERVER -> "Server xatosi"
        12, 13 -> "Bu til qurilmada qo'llab-quvvatlanmaydi"
        else -> "Ovoz tanish xatosi ($error)"
    }
}

/**
 * OpenAI Whisper transcription. Audio is captured locally with a simple energy VAD (stops after
 * ~1 s of silence) and uploaded as WAV only when the user enabled this engine with their own key.
 */
class WhisperSpeechToText(
    private val context: Context,
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val online: () -> Boolean,
    private val endpoint: String = "https://api.openai.com/v1/audio/transcriptions"
) : SpeechToText {
    override val id = "whisper"

    override fun isAvailable(): Boolean = !apiKey().isNullOrBlank() && online()

    override suspend fun listen(events: SttEvents, maxMillis: Long): String? {
        val pcm = record(events, maxMillis) ?: return null
        val key = apiKey() ?: throw SttException("OpenAI kaliti kiritilmagan", recoverable = false)
        return withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "uz")
                .addFormDataPart("response_format", "json")
                .addFormDataPart("file", "speech.wav", wav(pcm, AudioListener.SAMPLE_RATE).toRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(body).build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw SttException("Whisper xatosi: HTTP ${resp.code}")
                JSONObject(text).optString("text").trim().ifBlank { null }
            }
        }
    }

    private suspend fun record(events: SttEvents, maxMillis: Long): ShortArray? = suspendCancellableCoroutine { cont ->
        val listener = AudioListener(context, frameSize = 480) // 30 ms frames
        val out = ArrayList<Short>(AudioListener.SAMPLE_RATE * 10)
        val maxFrames = (maxMillis / 30).toInt()
        var frames = 0
        var speechFrames = 0
        var silenceAfterSpeech = 0
        var noiseFloor = 0.01f
        events.onReady()
        listener.start(
            onFrame = { frame ->
                frames++
                val level = AudioListener.rms(frame)
                events.onLevel((level * 12f).coerceIn(0f, 1f))
                if (frames <= 10) noiseFloor = maxOf(noiseFloor, level * 1.5f)
                val voiced = level > noiseFloor * 2.2f
                if (voiced) { speechFrames++; silenceAfterSpeech = 0 } else if (speechFrames > 0) silenceAfterSpeech++
                if (speechFrames > 0) frame.forEach { out.add(it) }
                val finished = (speechFrames > 5 && silenceAfterSpeech > 33) || frames >= maxFrames ||
                    (speechFrames == 0 && frames > 5000 / 30)
                if (finished) {
                    Thread { listener.stop() }.start()
                    if (cont.isActive) cont.resume(if (speechFrames > 5) out.toShortArray() else null)
                }
            },
            onError = { e -> if (cont.isActive) cont.resumeWithException(SttException(e.message ?: "Mikrofon xatosi")) }
        )
        cont.invokeOnCancellation { listener.stop() }
    }

    companion object {
        fun wav(pcm: ShortArray, sampleRate: Int): ByteArray {
            val dataLen = pcm.size * 2
            val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
            buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(2); buf.putShort(16)
            buf.put("data".toByteArray()); buf.putInt(dataLen)
            pcm.forEach { buf.putShort(it) }
            return buf.array()
        }
    }
}

/** Picks the best engine for the moment and falls back when one fails (e.g. network drops). */
class SpeechToTextRouter(
    private val google: SpeechToText,
    private val whisper: SpeechToText,
    private val vosk: VoskSpeechToText,
    private val choice: () -> SttEngineChoice,
    private val online: () -> Boolean
) {
    fun order(): List<SpeechToText> {
        val isOnline = online()
        val preferred = when (choice()) {
            SttEngineChoice.GOOGLE -> listOf(google, vosk)
            SttEngineChoice.WHISPER -> listOf(whisper, google, vosk)
            SttEngineChoice.VOSK -> listOf(vosk, google)
            SttEngineChoice.AUTO -> if (isOnline) listOf(google, whisper, vosk) else listOf(vosk, google)
        }
        return preferred.filter { it.isAvailable() }.ifEmpty { listOf(google) }
    }

    suspend fun listen(events: SttEvents, maxMillis: Long = 10_000): Pair<String?, String> {
        var lastError: SttException? = null
        for (engine in order()) {
            try {
                return engine.listen(events, maxMillis) to engine.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: SttException) {
                Log.w("SttRouter", "${engine.id} failed: ${e.message}")
                lastError = e
                if (!e.recoverable) break
            }
        }
        throw lastError ?: SttException("Ovoz tanish mavjud emas")
    }
}
