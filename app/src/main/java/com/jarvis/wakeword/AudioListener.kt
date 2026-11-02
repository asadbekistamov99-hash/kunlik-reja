package com.jarvis.wakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams 16 kHz mono PCM16 microphone audio in fixed-size frames on a dedicated thread.
 * One instance owns one AudioRecord at a time; [stop] releases the microphone so the speech
 * recognizer can take it over.
 */
class AudioListener(
    private val context: Context,
    val sampleRate: Int = SAMPLE_RATE,
    val frameSize: Int = FRAME_SAMPLES
) {
    /**
     * Run flag of the capture thread that currently owns the microphone. Each [start] gets its own
     * flag, so a thread that is still shutting down can never stop or re-enable a newer run.
     */
    @Volatile private var activeRun: AtomicBoolean? = null
    @Volatile private var thread: Thread? = null

    val isRunning: Boolean get() = activeRun?.get() == true

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked explicitly via hasPermission()
    fun start(onFrame: (ShortArray) -> Unit, onError: (Throwable) -> Unit) {
        if (!hasPermission()) {
            onError(SecurityException("RECORD_AUDIO permission not granted"))
            return
        }
        if (isRunning) return
        // Never open a second AudioRecord while the previous capture thread is still releasing one.
        thread?.let { if (it !== Thread.currentThread()) it.join(STOP_TIMEOUT_MS) }
        val run = AtomicBoolean(true)
        activeRun = run
        thread = Thread({ capture(run, onFrame, onError) }, "jarvis-audio").apply { isDaemon = true; start() }
    }

    fun stop() {
        activeRun?.set(false)
        activeRun = null
        thread?.let { if (it !== Thread.currentThread()) it.join(STOP_TIMEOUT_MS) }
        thread = null
    }

    private fun capture(run: AtomicBoolean, onFrame: (ShortArray) -> Unit, onError: (Throwable) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var record: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frameSize * 2 * 4)
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            record.startRecording()
            val frame = ShortArray(frameSize)
            while (run.get()) {
                var read = 0
                while (read < frameSize && run.get()) {
                    val n = record.read(frame, read, frameSize - read)
                    if (n < 0) throw IllegalStateException("AudioRecord read error $n")
                    if (n == 0) {
                        Thread.yield()
                        continue
                    }
                    read += n
                }
                if (read == frameSize && run.get()) onFrame(frame.copyOf())
            }
        } catch (t: Throwable) {
            // Only report failures of a run nobody stopped on purpose.
            if (run.getAndSet(false)) {
                Log.e(TAG, "Audio capture failed", t)
                onError(t)
            }
        } finally {
            run.set(false)
            runCatching { record?.stop() }
            record?.release()
        }
    }

    companion object {
        private const val TAG = "AudioListener"
        private const val STOP_TIMEOUT_MS = 500L
        const val SAMPLE_RATE = 16_000
        /** 80 ms at 16 kHz — the openWakeWord streaming step. */
        const val FRAME_SAMPLES = 1280

        /** Root-mean-square level of a PCM16 frame, 0..1. */
        fun rms(frame: ShortArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            return (kotlin.math.sqrt(sum / frame.size) / Short.MAX_VALUE).toFloat()
        }
    }
}
