package com.jarvis.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.core.SpellCorrector
import com.jarvis.settings.SttEngineChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class SttException(message: String, val recoverable: Boolean = true) : Exception(message)

interface SttEvents {
    fun onReady() {}
    fun onPartial(text: String) {}
    fun onLevel(level: Float) {}
    fun onAlternatives(alternatives: List<String>) {}
}

interface SpeechToText {
    val id: String
    fun isAvailable(): Boolean
    suspend fun listen(events: SttEvents, maxMillis: Long = 10_000): String?
}

/**
 * Kuchaytirilgan Google SpeechRecognizer:
 * - O'zbek tili (uz-UZ) + ko'p variantli (top-5) gipoteza tanlash
 * - Gap o'rtasidagi qisqa pauzada uzib qo'ymaslik (1800ms silence threshold)
 * - Imlo va fonetik normallashtirish
 */
class AndroidSpeechToText(
    private val context: Context,
    private val online: () -> Boolean
) : SpeechToText {
    override val id: String = "google"

    override fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun listen(events: SttEvents, maxMillis: Long): String? {
        val firstTry = listenOnce(events, maxMillis, "uz-UZ")
        if (!firstTry.isNullOrBlank()) return SpellCorrector.correct(firstTry)
        return null
    }

    private suspend fun listenOnce(events: SttEvents, maxMillis: Long, localeTag: String): String? {
        val resultDeferred = CompletableDeferred<String?>()
        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null

        mainHandler.post {
            try {
                val sr = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    private var latestPartial: String = ""

                    override fun onReadyForSpeech(params: Bundle?) {
                        events.onReady()
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {
                        val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        events.onLevel(norm)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(latestPartial.takeIf { it.isNotBlank() })
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        if (matches.isNotEmpty()) {
                            events.onAlternatives(matches)
                            val best = pickBestUzbekCandidate(matches)
                            resultDeferred.complete(best)
                        } else {
                            resultDeferred.complete(latestPartial.takeIf { it.isNotBlank() })
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val part = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (part.isNotBlank()) {
                            latestPartial = part
                            events.onPartial(part)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
                    if (!online()) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }
                sr.startListening(intent)
            } catch (e: Throwable) {
                if (!resultDeferred.isCompleted) resultDeferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(maxMillis + 1500L) {
                resultDeferred.await()
            }
        } finally {
            mainHandler.post {
                runCatching {
                    recognizer?.stopListening()
                    recognizer?.cancel()
                    recognizer?.destroy()
                }
            }
        }
    }

    private fun pickBestUzbekCandidate(candidates: List<String>): String {
        val commandKeywords = listOf(
            "jarvis", "vazifa", "eslatma", "soat", "ertaga", "bugun",
            "qo'sh", "bajarildi", "o'chir", "taqvim", "qo'ng'iroq", "odat"
        )
        return candidates.maxByOrNull { candidate ->
            val corrected = SpellCorrector.correct(candidate).lowercase()
            val keywordHits = commandKeywords.count { corrected.contains(it) }
            keywordHits * 10 + corrected.length.coerceAtMost(40)
        } ?: candidates.first()
    }
}

/**
 * Kuchaytirilgan OpenAI Whisper STT:
 * - Aparat darajasidagi NoiseSuppressor, AutomaticGainControl va AcousticEchoCanceler
 * - Avtomatik Voice Activity Detection (VAD): foydalanuvchi gapini tugatishi bilan (1.1s sukunat)
 *   darhol yozishni yakunlab serverga yuboradi
 * - Ovoz past bo'lsa avtomatik dinamik kuchaytirish (Gain Normalization)
 * - O'zbekcha maxsus prompt konteksti bilan yuqori aniqlik
 */
class WhisperSpeechToText(
    private val context: Context,
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val online: () -> Boolean,
    private val endpoint: String = "https://api.openai.com/v1/audio/transcriptions"
) : SpeechToText {
    override val id: String = "whisper"

    override fun isAvailable(): Boolean {
        val key = apiKey()?.trim()
        return !key.isNullOrEmpty() && online()
    }

    override suspend fun listen(events: SttEvents, maxMillis: Long): String? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null
        val pcmSamples = recordWithVadAndFilter(events, maxMillis) ?: return@withContext null
        if (pcmSamples.size < SAMPLE_RATE / 3) return@withContext null

        val normalizedPcm = normalizeGain(pcmSamples)
        val wavBytes = encodeWav16BitMono(normalizedPcm, SAMPLE_RATE)

        val key = apiKey()?.trim() ?: return@withContext null
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "speech.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "uz")
            .addFormDataPart(
                "prompt",
                "Jarvis, vazifa qo'sh, eslatma o'rnat, ertaga soat, bajarildi, o'chir, bugungi reja, taqvim, qo'ng'iroq qil, o'zbek tilida aniq buyruqlar."
            )
            .build()

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(multipart)
            .build()

        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val rawText = JSONObject(body).optString("text", "").trim()
                if (rawText.isBlank()) null else SpellCorrector.correct(rawText)
            }
        }.getOrNull()
    }

    private fun recordWithVadAndFilter(events: SttEvents, maxMillis: Long): ShortArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: SecurityException) {
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        val sessionId = record.audioSessionId
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.apply { enabled = true } else null }.getOrNull()
        val agc = runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.apply { enabled = true } else null }.getOrNull()
        val aec = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } else null }.getOrNull()

        val collected = ArrayList<Short>(SAMPLE_RATE * 6)
        val frame = ShortArray(1024)
        var speechDetected = false
        var silenceAfterSpeechMs = 0L
        val startTime = System.currentTimeMillis()

        return try {
            record.startRecording()
            events.onReady()

            while (System.currentTimeMillis() - startTime < maxMillis) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) continue

                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = frame[i]
                    collected.add(s)
                    sumSq += (s.toDouble() * s.toDouble())
                }
                val rms = sqrt(sumSq / read)
                val level = (rms / 6500.0).toFloat().coerceIn(0f, 1f)
                events.onLevel(level)

                val frameMs = (read * 1000L) / SAMPLE_RATE
                if (rms > SPEECH_RMS_THRESHOLD) {
                    speechDetected = true
                    silenceAfterSpeechMs = 0L
                } else if (speechDetected) {
                    silenceAfterSpeechMs += frameMs
                    if (silenceAfterSpeechMs >= END_OF_SPEECH_SILENCE_MS) {
                        break
                    }
                }
            }
            if (!speechDetected) null else collected.toShortArray()
        } finally {
            runCatching { ns?.release() }
            runCatching { agc?.release() }
            runCatching { aec?.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun normalizeGain(samples: ShortArray): ShortArray {
        var peak = 1
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak >= 24000 || peak < 150) return samples
        val gain = (24000.0 / peak).coerceIn(1.0, 6.0)
        return ShortArray(samples.size) { idx ->
            (samples[idx] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun encodeWav16BitMono(samples: ShortArray, sampleRate: Int): ByteArray {
        val byteDataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + byteDataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + byteDataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(byteDataSize)
        out.write(header.array())

        val pcmBuf = ByteBuffer.allocate(byteDataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcmBuf.putShort(s)
        out.write(pcmBuf.array())
        return out.toByteArray()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SPEECH_RMS_THRESHOLD = 420.0
        private const val END_OF_SPEECH_SILENCE_MS = 1_150L

        /** 16-bit mono PCM ni WAV konteyneriga o'raydi (Whisper uchun). */
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

/**
 * Uzluksiz kaskadli STT marshrutizator:
 * Agar tanlangan dvigatel xato bersa yoki bo'sh natija qaytarsa,
 * avtomatik keyingi ishlaydigan dvigatelga o'tadi.
 */
class SpeechToTextRouter(
    private val google: SpeechToText,
    private val whisper: SpeechToText,
    private val vosk: VoskSpeechToText,
    private val choice: () -> SttEngineChoice,
    private val online: () -> Boolean
) : SpeechToText {

    override val id: String
        get() = selectChain().firstOrNull()?.id ?: "none"

    override fun isAvailable(): Boolean = selectChain().isNotEmpty()

    /** Oxirgi matnni bergan mexanizm id'si ("google", "whisper", "vosk"); hech narsa bo'lmasa "none". */
    @Volatile
    var lastEngineId: String = "none"
        private set

    override suspend fun listen(events: SttEvents, maxMillis: Long): String? {
        val chain = selectChain()
        for (engine in chain) {
            val text = runCatching { engine.listen(events, maxMillis) }.getOrNull()
            if (!text.isNullOrBlank()) {
                lastEngineId = engine.id
                return SpellCorrector.correct(text)
            }
        }
        lastEngineId = "none"
        return null
    }

    private fun selectChain(): List<SpeechToText> {
        val isOnline = online()
        val ordered = when (choice()) {
            SttEngineChoice.WHISPER -> listOf(whisper, google, vosk)
            SttEngineChoice.GOOGLE -> listOf(google, whisper, vosk)
            SttEngineChoice.VOSK -> listOf(vosk, google, whisper)
            SttEngineChoice.AUTO -> if (isOnline) {
                listOf(whisper, google, vosk)
            } else {
                listOf(vosk, google)
            }
        }
        return ordered.filter { it.isAvailable() }
    }
}