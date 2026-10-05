package com.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.jarvis.settings.TtsEngineChoice
import com.jarvis.settings.VoiceGender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.speech.tts.TextToSpeech as AndroidTts

/** A cloud voice that turns text into 24 kHz 16-bit mono PCM. */
interface NeuralVoice {
    val id: String
    fun isAvailable(): Boolean
    suspend fun synthesize(text: String, gender: VoiceGender): ByteArray?
    /** Why the last [synthesize] returned null (HTTP status + the API's message), for diagnostics. */
    val lastError: String? get() = null
}

/** "HTTP 400: API key not valid..." from an error response body. */
internal fun describeHttpError(code: Int, body: String): String {
    val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
    return "HTTP $code" + (message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" } ?: "")
}

/** Gemini native TTS (gemini-2.5-flash-preview-tts): expressive multilingual voices. */
class GeminiVoice(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val online: () -> Boolean,
    private val endpoint: String = DEFAULT_ENDPOINT,
    /** Tried in order when the model is not found (preview model names get retired). */
    private val fallbackEndpoints: List<String> = if (endpoint == DEFAULT_ENDPOINT) FALLBACK_ENDPOINTS else emptyList()
) : NeuralVoice {
    override val id = "gemini"
    @Volatile override var lastError: String? = null
    override fun isAvailable() = !apiKey().isNullOrBlank() && online()

    override suspend fun synthesize(text: String, gender: VoiceGender): ByteArray? = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext null
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "O'zbek tilida tabiiy, iliq va ravon, xuddi suhbatdoshdek gapiring: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voiceName(gender))))))
        lastError = null
        for (url in listOf(endpoint) + fallbackEndpoints) {
            val request = Request.Builder().url(url).header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(JSON)).build()
            val outcome = http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = describeHttpError(resp.code, raw)
                    Log.w("GeminiVoice", lastError.orEmpty())
                    return@use if (resp.code == 404) null to true else null to false
                }
                val data = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optJSONObject("inlineData")?.optString("data")
                val pcm = data?.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
                if (pcm == null) lastError = "Javobda audio yo'q"
                pcm to false
            }
            if (outcome.first != null) return@withContext outcome.first
            if (!outcome.second) return@withContext null // only a missing model is worth another try
        }
        null
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        const val DEFAULT_ENDPOINT = BASE + "gemini-2.5-flash-preview-tts:generateContent"
        val FALLBACK_ENDPOINTS = listOf(BASE + "gemini-2.5-flash-tts:generateContent", BASE + "gemini-2.5-pro-preview-tts:generateContent")
        fun voiceName(gender: VoiceGender) = if (gender == VoiceGender.MALE) "Charon" else "Kore"
    }
}

/** OpenAI TTS (gpt-4o-mini-tts) with style instructions; returns raw 24 kHz PCM. */
class OpenAiVoice(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val online: () -> Boolean,
    private val endpoint: String = "https://api.openai.com/v1/audio/speech",
    var model: String = TTS_MODEL,
    var speed: Float = 1.0f
) : NeuralVoice {
    override val id = "openai"
    @Volatile override var lastError: String? = null
    override fun isAvailable() = !apiKey().isNullOrBlank() && online()

    override suspend fun synthesize(text: String, gender: VoiceGender): ByteArray? = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext null
        val body = JSONObject()
            .put("model", model)
            .put("voice", voiceName(gender))
            .put("input", text)
            .put("response_format", "pcm")
            .put("speed", speed.toDouble())
            .put("instructions", "O'zbek tilida tabiiy, aniq va ravon so'zla, xuddi iliq va ishonchli shaxsiy yordamchidek. Har bir harfni tushunarli talaffuz qil, jumlalar orasida qisqa tabiiy pauza qil, robotdek ovoz chiqarma. Son va vaqtlarni o'zbekcha o'qi.")
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        lastError = null
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                lastError = describeHttpError(resp.code, resp.body?.string().orEmpty())
                Log.w("OpenAiVoice", lastError.orEmpty())
                null
            } else resp.body?.bytes()
        }
    }

    companion object {
        /** Higher-fidelity TTS model; upgrade point for future voices. */
        const val TTS_MODEL = "gpt-4o-mini-tts"
        fun voiceName(gender: VoiceGender) = if (gender == VoiceGender.MALE) "onyx" else "nova"
    }
}

/**
 * Jarvis' voice.
 *
 * Neural cloud voices (Gemini / OpenAI) are used when chosen and available — they are the natural,
 * fluent option with a true male/female voice. Otherwise the best installed device voice is used:
 * Uzbek if present, else Turkish (closest Latin phonetics), Russian, then the system default; the
 * highest-quality voice matching the chosen gender is selected, pitch adapts to the gender when the
 * engine doesn't label voices, and sentences get short natural pauses.
 */
class TextToSpeechManager(
    private val context: Context,
    private val neuralVoices: List<NeuralVoice> = emptyList()
) : AndroidTts.OnInitListener {

    private val tts = AndroidTts(context.applicationContext, this)
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    @Volatile var activeLocale: Locale? = null
        private set
    @Volatile var activeVoiceName: String = ""
        private set
    @Volatile var lastEngine: String = "device"
        private set
    /** Why the neural voices were skipped on the last [speak] (null when one of them spoke). */
    @Volatile var lastNeuralError: String? = null
        private set
    @Volatile private var openaiTtsModel: String = OpenAiVoice.TTS_MODEL
    @Volatile private var openaiTtsSpeed: Float = 1.0f

    private var languagePreference = "auto"
    private var rate = 1.0f
    private var gender = VoiceGender.MALE
    private var engineChoice = TtsEngineChoice.AUTO
    @Volatile private var player: PcmPlayer? = null

    override fun onInit(status: Int) {
        if (status != AndroidTts.SUCCESS) {
            Log.e(TAG, "TTS init failed: $status")
            ready.complete(false)
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
        applyVoice()
        ready.complete(true)
    }

    private fun finish(id: String?, ok: Boolean) {
        id?.let { pending.remove(it)?.complete(ok) }
        if (pending.isEmpty()) _speaking.value = false
    }

    fun configure(language: String, speechRate: Float, voiceGender: VoiceGender = gender, engine: TtsEngineChoice = engineChoice) {
        languagePreference = language
        rate = speechRate
        gender = voiceGender
        engineChoice = engine
        if (ready.isCompleted) applyVoice()
    }

    /** Fine-tunes the OpenAI neural voice: model and speaking speed (0.5–2.0). */
    fun openaiSettings(model: String, speed: Float) {
        openaiTtsModel = model.ifBlank { OpenAiVoice.TTS_MODEL }
        openaiTtsSpeed = speed.coerceIn(0.5f, 2.0f)
        neuralVoices.filterIsInstance<OpenAiVoice>().forEach { it.model = openaiTtsModel; it.speed = openaiTtsSpeed }
    }

    private fun applyVoice() {
        val candidates = if (languagePreference != "auto") listOf(Locale.forLanguageTag(languagePreference)) + FALLBACKS else FALLBACKS
        for (locale in candidates + Locale.getDefault()) {
            if (tts.isLanguageAvailable(locale) >= AndroidTts.LANG_AVAILABLE) {
                tts.setLanguage(locale)
                activeLocale = locale
                break
            }
        }
        val locale = activeLocale
        val voices = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        val best = locale?.let { l ->
            voices.filter { it.locale.language == l.language && !it.features.orEmpty().contains(AndroidTts.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .maxByOrNull { voiceScore(it.name, it.features.orEmpty(), it.quality, it.isNetworkConnectionRequired, gender) }
        }
        best?.let { runCatching { tts.voice = it; activeVoiceName = it.name } }
        val labelled = best?.let { genderOf(it.name, it.features.orEmpty()) } != null
        // When the engine doesn't tell us a voice's gender, shape it with pitch instead.
        tts.setPitch(if (labelled) 1.0f else if (gender == VoiceGender.MALE) 0.86f else 1.14f)
        tts.setSpeechRate(rate * 0.97f)
    }

    /** Speaks [text] and suspends until playback finishes (or fails/stops). */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return true
        val failures = mutableListOf<String>()
        val candidates = neuralCandidates()
        if (engineChoice != TtsEngineChoice.DEVICE && candidates.isEmpty()) {
            failures += "kalit kiritilmagan yoki internet yo'q"
        }
        // A failing neural voice must not silence the others: try each in turn.
        for (voice in candidates) {
            _speaking.value = true
            val pcm = try {
                withTimeoutOrNull(NEURAL_TIMEOUT_MS) { voice.synthesize(text, gender) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${voice.id}: tarmoq xatosi (${e.javaClass.simpleName})"
                null
            }
            if (pcm != null && pcm.size > 1000) {
                lastEngine = voice.id
                lastNeuralError = null
                val p = PcmPlayer(NEURAL_SAMPLE_RATE)
                player = p
                val ok = try { p.play(pcm) } finally { player = null; _speaking.value = false }
                return ok
            }
            _speaking.value = false
            if (failures.none { it.startsWith("${voice.id}:") }) {
                failures += "${voice.id}: ${voice.lastError ?: "javob vaqtida kelmadi (${NEURAL_TIMEOUT_MS / 1000} s)"}"
            }
            Log.w(TAG, "Neural voice ${voice.id} failed: ${failures.last()}")
        }
        lastNeuralError = failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
        lastEngine = "device"
        return speakWithDevice(text)
    }

    private fun neuralCandidates(): List<NeuralVoice> = when (engineChoice) {
        TtsEngineChoice.DEVICE -> emptyList()
        TtsEngineChoice.GEMINI -> neuralVoices.filter { it.id == "gemini" && it.isAvailable() }
        TtsEngineChoice.OPENAI -> neuralVoices.filter { it.id == "openai" && it.isAvailable() }
        TtsEngineChoice.AUTO -> neuralVoices.filter { it.isAvailable() }
    }

    private suspend fun speakWithDevice(text: String): Boolean {
        val ok = withTimeoutOrNull(5_000) { ready.await() } ?: false
        if (!ok) return false
        val sentences = splitSentences(text)
        var last: CompletableDeferred<Boolean>? = null
        sentences.forEachIndexed { i, sentence ->
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Boolean>()
            pending[id] = done
            _speaking.value = true
            val mode = if (i == 0) AndroidTts.QUEUE_FLUSH else AndroidTts.QUEUE_ADD
            if (i > 0) tts.playSilentUtterance(SENTENCE_PAUSE_MS, AndroidTts.QUEUE_ADD, null)
            if (tts.speak(sentence, mode, Bundle(), id) != AndroidTts.SUCCESS) {
                finish(id, false)
                return false
            }
            last = done
        }
        // Generous upper bound: ~90 ms per character plus slack.
        return withTimeoutOrNull(10_000L + text.length * 90L) { last?.await() ?: true } ?: run { stop(); false }
    }

    fun stop() {
        player?.stop()
        tts.stop()
        pending.keys.toList().forEach { finish(it, false) }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "JarvisTTS"
        private const val SENTENCE_PAUSE_MS = 180L
        private const val NEURAL_TIMEOUT_MS = 15_000L
        const val NEURAL_SAMPLE_RATE = 24_000
        val FALLBACKS = listOf(Locale.forLanguageTag("uz-UZ"), Locale.forLanguageTag("uz"), Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ru-RU"))

        /** Splits text into sentences so the device voice can breathe between them. */
        fun splitSentences(text: String): List<String> =
            text.trim().split(Regex("""(?<=[.!?…])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text.trim()) }

        /** "male"/"female" from a voice's name or features, or null if the engine doesn't say. */
        fun genderOf(name: String, features: Set<String>): VoiceGender? {
            val hay = (features.joinToString(" ") + " " + name).lowercase(Locale.ROOT)
            return when {
                Regex("""\bfemale\b|gender=female|#female|_female|-female""").containsMatchIn(hay) -> VoiceGender.FEMALE
                Regex("""\bmale\b|gender=male|#male|_male|-male""").containsMatchIn(hay) -> VoiceGender.MALE
                else -> null
            }
        }

        /** Ranks candidate device voices: gender match, then quality, then offline availability. */
        fun voiceScore(name: String, features: Set<String>, quality: Int, needsNetwork: Boolean, wanted: VoiceGender): Int {
            val g = genderOf(name, features)
            val genderScore = when (g) { wanted -> 1000; null -> 500; else -> 0 }
            return genderScore + quality + (if (needsNetwork) 0 else 50)
        }
    }
}

/** Plays 16-bit mono PCM and suspends until it has been heard (or [stop] is called). */
class PcmPlayer(private val sampleRate: Int) {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    suspend fun play(pcm: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 2))
            .build()
        track = t
        try {
            t.play()
            var offset = 0
            while (offset < pcm.size && !stopped && isActive) {
                val n = t.write(pcm, offset, minOf(8192, pcm.size - offset))
                if (n <= 0) break
                offset += n
            }
            val totalFrames = pcm.size / 2
            val deadline = System.currentTimeMillis() + totalFrames * 1000L / sampleRate + 1500
            while (!stopped && isActive && t.playbackHeadPosition < totalFrames && System.currentTimeMillis() < deadline) delay(40)
            !stopped
        } finally {
            runCatching { t.stop() }
            t.release()
            track = null
        }
    }

    fun stop() {
        stopped = true
        runCatching { track?.pause(); track?.flush() }
    }
}
