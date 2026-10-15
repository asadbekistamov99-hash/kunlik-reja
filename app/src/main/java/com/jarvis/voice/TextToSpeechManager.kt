package com.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

enum class VoiceGender { MALE, FEMALE }
enum class TtsEngineChoice { ANDROID_DEFAULT, OPENAI_NEURAL, SYSTEM_CUSTOM }

interface NeuralVoice {
    val id: String
    fun isAvailable(): Boolean
    suspend fun synthesize(text: String, gender: VoiceGender): ByteArray?
}

/**
 * Kuchaytirilgan OpenAI Neural Voice:
 * - O'zbek tili talaffuzi, urg'usi va intonatsiyasi uchun boyitilgan yo'riqnoma
 * - Ovoz ohangini yumshoq va aniq eshittirish
 */
class OpenAiVoice(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val online: () -> Boolean,
    private val endpoint: String = "https://api.openai.com/v1/audio/speech"
) : NeuralVoice {
    override val id: String = "openai"

    @Volatile
    var model: String = "gpt-4o-mini-tts"

    @Volatile
    var speed: Float = 1.0f

    override fun isAvailable(): Boolean {
        val key = apiKey()?.trim()
        return !key.isNullOrEmpty() && online()
    }

    override suspend fun synthesize(text: String, gender: VoiceGender): ByteArray? = withContext(Dispatchers.IO) {
        val key = apiKey()?.trim()
        if (key.isNullOrEmpty() || !online() || text.isBlank()) return@withContext null

        val voiceName = when (gender) {
            VoiceGender.MALE -> "onyx"
            VoiceGender.FEMALE -> "nova"
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("voice", voiceName)
            put("input", text)
            put("response_format", "pcm")
            put("speed", speed.coerceIn(0.75f, 1.35f).toDouble())
            put(
                "instructions",
                "Sof adabiy o'zbek tilida juda ravon, aniq, tabiiy intonatsiya va iliq ovoz ohangi bilan gapir. " +
                    "So'z oxiridagi urg'ularni to'g'ri qo'y, vergulda yengil nafas pauzasi qil, savol gaplarda " +
                    "tabiiy savol ohangini ber, xuddi aqlli va ishonchli Jarvis yordamchisidek dona-dona so'zla."
            )
        }

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            }
        }.getOrNull()
    }
}

/**
 * O'zbekcha matnni tabiiy nutqqa tayyorlovchi (Orfoepik & Prosody Pre-processor)
 * hamda AudioFocus va dinamik ovoz kuchaytirishga ega TextToSpeechManager.
 */
class TextToSpeechManager(
    private val context: Context,
    private val neuralVoices: List<NeuralVoice> = emptyList()
) : AndroidTts.OnInitListener {

    private var tts: AndroidTts? = null
    private val ready = CompletableDeferred<Boolean>()
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var languageTag: String = "uz-UZ"

    @Volatile
    private var rate: Float = 1.0f

    @Volatile
    private var gender: VoiceGender = VoiceGender.MALE

    @Volatile
    private var engineChoice: TtsEngineChoice = TtsEngineChoice.OPENAI_NEURAL

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        runCatching {
            tts = AndroidTts(context.applicationContext, this)
        }.onFailure {
            if (!ready.isCompleted) ready.complete(false)
        }
    }

    override fun onInit(status: Int) {
        val ok = status == AndroidTts.SUCCESS
        if (ok) {
            applyVoice()
        }
        if (!ready.isCompleted) {
            ready.complete(ok)
        }
    }

    fun configure(
        language: String,
        speechRate: Float,
        voiceGender: VoiceGender = gender,
        engine: TtsEngineChoice = engineChoice
    ) {
        languageTag = language.ifBlank { "uz-UZ" }
        rate = speechRate.coerceIn(0.65f, 1.6f)
        gender = voiceGender
        engineChoice = engine
        if (ready.isCompleted) {
            applyVoice()
        }
    }

    fun openaiSettings(model: String, speed: Float) {
        neuralVoices.filterIsInstance<OpenAiVoice>().forEach { v ->
            if (model.isNotBlank()) v.model = model
            v.speed = speed.coerceIn(0.75f, 1.35f)
        }
    }

    suspend fun speak(text: String): Boolean {
        val prepared = prepareUzbekSpeechText(text)
        if (prepared.isBlank()) return false
        stopped.set(false)

        return withAudioFocus {
            val neural = pickNeural()
            if (neural != null) {
                val pcm = withTimeoutOrNull(NEURAL_TIMEOUT_MS) {
                    neural.synthesize(prepared, gender)
                }
                if (pcm != null && pcm.size > 1000 && !stopped.get()) {
                    val enhancedPcm = enhanceAndNormalizePcm24k(pcm)
                    val played = playPcm24kMono(enhancedPcm)
                    if (played) return@withAudioFocus true
                }
            }
            speakWithDevice(prepared)
        }
    }

    fun stop() {
        stopped.set(true)
        runCatching {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.stop()
        }
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
    }

    private fun pickNeural(): NeuralVoice? {
        if (engineChoice == TtsEngineChoice.ANDROID_DEFAULT) return null
        return neuralVoices.firstOrNull { it.isAvailable() }
    }

    private fun applyVoice() {
        val engine = tts ?: return
        val candidateLocales = listOf(
            Locale.forLanguageTag(languageTag),
            Locale.forLanguageTag("uz-UZ"),
            Locale.forLanguageTag("uz"),
            Locale.forLanguageTag("tr-TR"),
            Locale.getDefault()
        )

        for (loc in candidateLocales) {
            val res = runCatching { engine.setLanguage(loc) }.getOrDefault(AndroidTts.LANG_NOT_SUPPORTED)
            if (res != AndroidTts.LANG_MISSING_DATA && res != AndroidTts.LANG_NOT_SUPPORTED) {
                selectBestMatchingVoice(engine, loc)
                break
            }
        }

        val naturalPitch = when (gender) {
            VoiceGender.MALE -> 0.94f
            VoiceGender.FEMALE -> 1.05f
        }
        runCatching {
            engine.setPitch(naturalPitch)
            engine.setSpeechRate(rate.coerceIn(0.85f, 1.25f))
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    private fun selectBestMatchingVoice(engine: AndroidTts, targetLocale: Locale) {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return
        val lang = targetLocale.language.lowercase(Locale.ROOT)
        val wantsMale = gender == VoiceGender.MALE

        val best: Voice? = voices
            .filter { it.locale.language.lowercase(Locale.ROOT) == lang }
            .maxByOrNull { v ->
                var score = v.quality
                if (!v.isNetworkConnectionRequired) score += 150
                val n = v.name.lowercase(Locale.ROOT)
                if (wantsMale && (n.contains("male") || n.contains("man") || n.contains("#male"))) score += 300
                if (!wantsMale && (n.contains("female") || n.contains("woman") || n.contains("#female"))) score += 300
                score
            }
        if (best != null) {
            runCatching { engine.voice = best }
        }
    }

    private suspend fun speakWithDevice(text: String): Boolean {
        val isReady = withTimeoutOrNull(3_000L) { ready.await() } ?: false
        val engine = tts ?: return false
        if (!isReady) return false

        val sentences = splitIntoNaturalSentences(text)
        if (sentences.isEmpty()) return false

        for ((index, sentence) in sentences.withIndex()) {
            if (stopped.get()) return false
            val done = CompletableDeferred<Boolean>()
            val utteranceId = "jarvis_${UUID.randomUUID()}"

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && !done.isCompleted) done.complete(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && !done.isCompleted) done.complete(false)
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId && !done.isCompleted) done.complete(false)
                }
            })

            val params = Bundle().apply {
                putFloat(AndroidTts.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val queueMode = if (index == 0) AndroidTts.QUEUE_FLUSH else AndroidTts.QUEUE_ADD
            val code = engine.speak(sentence, queueMode, params, utteranceId)
            if (code != AndroidTts.SUCCESS) return false

            withTimeoutOrNull(15_000L) { done.await() }
        }
        return true
    }

    private suspend fun playPcm24kMono(pcmBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val sampleRate = 24_000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeTrack = track
        try {
            track.play()
            var offset = 0
            val chunk = 4096
            while (offset < pcmBytes.size && !stopped.get()) {
                val toWrite = (pcmBytes.size - offset).coerceAtMost(chunk)
                val written = track.write(pcmBytes, offset, toWrite)
                if (written <= 0) break
                offset += written
            }
            !stopped.get()
        } catch (e: Throwable) {
            false
        } finally {
            activeTrack = null
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    /**
     * PCM 16-bit audio signalini tiniqlashtirish, ovoz balandligini normallashtirish
     * hamda boshlanish/tugashda qirsillash (click/pop) bo'lmasligi uchun yumshoq fade qo'llash.
     */
    private fun enhanceAndNormalizePcm24k(rawPcm: ByteArray): ByteArray {
        val count = rawPcm.size / 2
        if (count < 64) return rawPcm
        val inBuf = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(count) { inBuf.short }

        var peak = 1
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }

        val targetPeak = 28500.0
        val gain = if (peak in 500..27000) (targetPeak / peak).coerceIn(1.0, 2.4) else 1.0
        val fadeSamples = 240.coerceAtMost(count / 4)

        val outBuf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            var env = 1.0
            if (i < fadeSamples) {
                env = i.toDouble() / fadeSamples
            } else if (i > count - fadeSamples) {
                env = (count - i).toDouble() / fadeSamples
            }
            val boosted = (samples[i] * gain * env).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            outBuf.putShort(boosted)
        }
        return outBuf.array()
    }

    /**
     * O'zbek tilida raqamlar, soatlar va qisqartmalarni ravon o'qilishi uchun
     * og'zaki matnga aylantiradi.
     */
    private fun prepareUzbekSpeechText(raw: String): String {
        var text = raw
            .replace(Regex("[*_#`~]+"), "")
            .replace("o'", "o\u2018")
            .replace("g'", "g\u2018")
            .replace("O'", "O\u2018")
            .replace("G'", "G\u2018")

        text = text.replace(Regex("\\b0(\\d):00\\b"), "$1:00")
        text = text.replace(Regex("\\b(\\d{1,2}):00\\b"), "soat $1")
        text = text.replace(Regex("\\b0(\\d):(\\d{2})\\b"), "$1:$2")
        text = text.replace(Regex("%"), " foiz")

        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun splitIntoNaturalSentences(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private suspend fun <T> withAudioFocus(block: suspend () -> T): T {
        val am = audioManager ?: return block()
        var focusReq: AudioFocusRequest? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            runCatching { am.requestAudioFocus(focusReq) }
        }
        return try {
            block()
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusReq != null) {
                runCatching { am.abandonAudioFocusRequest(focusReq) }
            }
        }
    }

    companion object {
        private const val NEURAL_TIMEOUT_MS = 9_000L
    }
}