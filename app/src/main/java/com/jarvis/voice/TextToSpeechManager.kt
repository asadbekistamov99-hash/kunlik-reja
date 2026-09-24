package com.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * Jarvis' voice. Prefers an Uzbek voice; if the device has none it falls back to Turkish (whose
 * Latin phonetics read Uzbek text far better than Russian or English), then Russian, then default.
 */
class TextToSpeechManager(private val context: Context) : AndroidTts.OnInitListener {

    private val tts = AndroidTts(context.applicationContext, this)
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    @Volatile var activeLocale: Locale? = null
        private set
    private var languagePreference = "auto"
    private var rate = 1.0f

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
        applyLanguage()
        tts.setSpeechRate(rate)
        ready.complete(true)
    }

    private fun finish(id: String?, ok: Boolean) {
        id?.let { pending.remove(it)?.complete(ok) }
        if (pending.isEmpty()) _speaking.value = false
    }

    fun configure(language: String, speechRate: Float) {
        languagePreference = language
        rate = speechRate
        if (ready.isCompleted) {
            applyLanguage()
            tts.setSpeechRate(rate)
        }
    }

    private fun applyLanguage() {
        val candidates = if (languagePreference != "auto") listOf(Locale.forLanguageTag(languagePreference)) + FALLBACKS else FALLBACKS
        for (locale in candidates + Locale.getDefault()) {
            val r = tts.isLanguageAvailable(locale)
            if (r >= AndroidTts.LANG_AVAILABLE) {
                tts.setLanguage(locale)
                activeLocale = locale
                return
            }
        }
    }

    /** Speaks [text] and suspends until playback finishes (or fails/stops). */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return true
        val ok = withTimeoutOrNull(5_000) { ready.await() } ?: false
        if (!ok) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        _speaking.value = true
        val result = tts.speak(text, AndroidTts.QUEUE_FLUSH, Bundle(), id)
        if (result != AndroidTts.SUCCESS) {
            finish(id, false)
            return false
        }
        // Generous upper bound: ~60 ms per character plus slack.
        return withTimeoutOrNull(10_000L + text.length * 90L) { done.await() } ?: run { finish(id, false); false }
    }

    fun stop() {
        tts.stop()
        pending.keys.toList().forEach { finish(it, false) }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "JarvisTTS"
        val FALLBACKS = listOf(Locale.forLanguageTag("uz-UZ"), Locale.forLanguageTag("uz"), Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ru-RU"))
    }
}
