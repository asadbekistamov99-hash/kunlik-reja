package com.jarvis.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * On-device "hey Jarvis" detector: a Kotlin port of the openWakeWord streaming pipeline.
 *
 *   PCM16 (80 ms) -> melspectrogram.onnx -> 32-bin mel frames (x/10 + 2)
 *   last 76 mel frames -> embedding_model.onnx -> 96-d embedding
 *   last 16 embeddings -> hey_jarvis_v0.1.onnx -> score 0..1
 *
 * Everything runs locally with ONNX Runtime; no network is ever used.
 */
class KeywordDetector(
    private val env: OrtEnvironment,
    private val melSession: OrtSession,
    private val embeddingSession: OrtSession,
    private val keywordSession: OrtSession
) : Closeable {

    private val rawBuffer = ShortRingBuffer(SAMPLES_PER_FRAME + MEL_CONTEXT_SAMPLES)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()
    private var silentFrames = 0

    init {
        reset()
    }

    /** Clears streaming state (e.g. after a detection or when the mic restarts). */
    fun reset() {
        rawBuffer.clear()
        melFrames.clear()
        repeat(MEL_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        embeddings.clear()
        silentFrames = 0
    }

    /**
     * Feeds one 1280-sample frame and returns the keyword probability. During sustained silence the
     * expensive embedding + classifier stages are skipped, which is what keeps idle battery use low.
     */
    fun process(frame: ShortArray, silenceThreshold: Float = 0.004f): Float {
        require(frame.size == SAMPLES_PER_FRAME) { "Expected $SAMPLES_PER_FRAME samples" }
        rawBuffer.push(frame)
        appendMel(rawBuffer.snapshot())

        silentFrames = if (AudioListener.rms(frame) < silenceThreshold) silentFrames + 1 else 0
        val skip = silentFrames > FEATURE_WINDOW && embeddings.size >= FEATURE_WINDOW
        val embedding = if (skip) embeddings.last() else computeEmbedding()
        embeddings.addLast(embedding)
        while (embeddings.size > FEATURE_WINDOW) embeddings.removeFirst()
        if (skip || embeddings.size < FEATURE_WINDOW) return 0f
        return classify()
    }

    private fun appendMel(samples: ShortArray) {
        val input = FloatArray(samples.size) { samples[it].toFloat() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { tensor ->
            melSession.run(mapOf(melSession.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val frames = out.remaining() / MEL_BINS
                for (f in 0 until frames) {
                    val row = FloatArray(MEL_BINS) { out.get() / 10f + 2f }
                    melFrames.addLast(row)
                }
            }
        }
        while (melFrames.size > MEL_WINDOW + 32) melFrames.removeFirst()
    }

    private fun computeEmbedding(): FloatArray {
        val window = melFrames.toList().takeLast(MEL_WINDOW)
        val input = FloatArray(MEL_WINDOW * MEL_BINS)
        window.forEachIndexed { i, row -> System.arraycopy(row, 0, input, i * MEL_BINS, MEL_BINS) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { tensor ->
            embeddingSession.run(mapOf(embeddingSession.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(EMBEDDING_SIZE) { out.get() }
            }
        }
    }

    private fun classify(): Float {
        val input = FloatArray(FEATURE_WINDOW * EMBEDDING_SIZE)
        embeddings.forEachIndexed { i, e -> System.arraycopy(e, 0, input, i * EMBEDDING_SIZE, EMBEDDING_SIZE) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, FEATURE_WINDOW.toLong(), EMBEDDING_SIZE.toLong())).use { tensor ->
            keywordSession.run(mapOf(keywordSession.inputNames.first() to tensor)).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.get()
            }
        }
    }

    override fun close() {
        melSession.close()
        embeddingSession.close()
        keywordSession.close()
    }

    companion object {
        const val SAMPLES_PER_FRAME = 1280
        const val MEL_CONTEXT_SAMPLES = 160 * 3
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val EMBEDDING_SIZE = 96
        const val FEATURE_WINDOW = 16

        const val ASSET_DIR = "wakeword"
        const val MEL_MODEL = "melspectrogram.onnx"
        const val EMBEDDING_MODEL = "embedding_model.onnx"
        const val KEYWORD_MODEL = "hey_jarvis_v0.1.onnx"

        fun load(context: Context): KeywordDetector {
            val env = OrtEnvironment.getEnvironment()
            fun session(name: String): OrtSession {
                val bytes = context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                return env.createSession(bytes, options)
            }
            return KeywordDetector(env, session(MEL_MODEL), session(EMBEDDING_MODEL), session(KEYWORD_MODEL))
        }
    }
}

/** Fixed-capacity ring buffer holding the most recent PCM samples. */
class ShortRingBuffer(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var size = 0
    private var head = 0

    fun push(samples: ShortArray) {
        for (s in samples) {
            data[head] = s
            head = (head + 1) % capacity
            if (size < capacity) size++
        }
    }

    /** Oldest-to-newest copy of the buffered samples. */
    fun snapshot(): ShortArray {
        val out = ShortArray(size)
        val start = (head - size + capacity) % capacity
        for (i in 0 until size) out[i] = data[(start + i) % capacity]
        return out
    }

    fun clear() {
        size = 0
        head = 0
    }
}
