package com.example.jarvis.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fully offline Uzbek speech recognition with a downloadable Vosk model (~50 MB). */
class VoskSpeechToText(
    private val context: Context,
    private val http: OkHttpClient,
    private val modelUrl: String = DEFAULT_MODEL_URL
) : SpeechToText {
    override val id = "vosk"
    private val modelDir get() = File(context.filesDir, "vosk/model-uz")
    private var model: Model? = null
    private val loadLock = Mutex()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    fun isInstalled(): Boolean = File(modelDir, "conf").exists() || File(modelDir, "am").exists()

    override fun isAvailable(): Boolean = isInstalled()

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "vosk/tmp").apply { deleteRecursively(); mkdirs() }
        try {
            _downloadProgress.value = 0f
            http.newCall(Request.Builder().url(modelUrl).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body ?: error("empty body")
                val total = body.contentLength().toFloat()
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    var read = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { if (it > 0) { read += it; if (total > 0) _downloadProgress.value = read / total } }
                }
                ZipInputStream(counting).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        // Strip the archive's top-level folder (vosk-model-small-uz-0.22/...).
                        val relative = entry.name.substringAfter('/', "")
                        if (relative.isNotEmpty()) {
                            val target = File(tmp, relative)
                            check(target.canonicalPath.startsWith(tmp.canonicalPath)) { "Bad zip entry" }
                            if (entry.isDirectory) target.mkdirs()
                            else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            modelDir.deleteRecursively()
            modelDir.parentFile?.mkdirs()
            check(tmp.renameTo(modelDir)) { "rename failed" }
            model?.close(); model = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk model download failed", e)
            tmp.deleteRecursively()
            false
        } finally {
            _downloadProgress.value = null
        }
    }

    fun delete() {
        model?.close(); model = null
        modelDir.deleteRecursively()
    }

    private suspend fun loadModel(): Model = loadLock.withLock {
        model ?: withContext(Dispatchers.IO) { Model(modelDir.absolutePath) }.also { model = it }
    }

    override suspend fun listen(events: SttEvents, maxMillis: Long): String? {
        if (!isInstalled()) throw SttException("Oflayn ovoz modeli yuklanmagan")
        val m = loadModel()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val recognizer = Recognizer(m, 16000f)
                val service = try {
                    SpeechService(recognizer, 16000f)
                } catch (e: Exception) {
                    recognizer.close()
                    cont.resumeWithException(SttException("Mikrofon band: ${e.message}"))
                    return@suspendCancellableCoroutine
                }
                fun finish(result: String?) {
                    service.stop(); service.shutdown(); recognizer.close()
                    if (cont.isActive) cont.resume(result)
                }
                events.onReady()
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        val partial = hypothesis?.let { runCatching { JSONObject(it).optString("partial") }.getOrNull() }
                        if (!partial.isNullOrBlank()) { events.onPartial(partial); events.onLevel(0.6f) } else events.onLevel(0.1f)
                    }
                    override fun onResult(hypothesis: String?) {
                        val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }
                        if (!text.isNullOrBlank()) finish(text)
                    }
                    override fun onFinalResult(hypothesis: String?) {
                        finish(hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }?.ifBlank { null })
                    }
                    override fun onError(exception: Exception?) {
                        service.shutdown(); recognizer.close()
                        if (cont.isActive) cont.resumeWithException(SttException(exception?.message ?: "Vosk xatosi"))
                    }
                    override fun onTimeout() = finish(null)
                }, maxMillis.toInt())
                cont.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { service.stop(); service.shutdown(); recognizer.close() }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoskStt"
        const val DEFAULT_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip"
    }
}
