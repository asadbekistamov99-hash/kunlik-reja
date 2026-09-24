package com.example

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.voice.VoskModel
import com.jarvis.wakeword.JarvisKeywordMatcher
import com.jarvis.wakeword.KeywordDetector
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds synthesized speech (generated in CI with espeak-ng, see android.yml) through both offline
 * wake-word engines on a real Android runtime. Skipped when the fixtures are absent (local runs).
 */
@RunWith(AndroidJUnit4::class)
class WakeWordAudioTest {
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clips(prefix: String): List<Pair<String, ShortArray>> {
        val names = runCatching { testAssets.list("audio")?.toList().orEmpty() }.getOrDefault(emptyList())
        return names.filter { it.startsWith(prefix) && it.endsWith(".wav") }.map { name ->
            val bytes = testAssets.open("audio/$name").use { it.readBytes() }
            val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            name to ShortArray(pcm.remaining()).also { pcm.get(it) }
        }
    }

    /** 1.5 s of silence, the clip, then 1 s of silence — like a real utterance in a quiet room. */
    private fun padded(pcm: ShortArray) = ShortArray(24_000) + pcm + ShortArray(16_000)

    @Test fun openWakeWordDetectsHeyJarvisAndIgnoresOtherSpeech() {
        val positives = clips("heyjarvis_")
        val negatives = clips("neg_")
        assumeTrue("audio fixtures not generated", positives.isNotEmpty() && negatives.isNotEmpty())
        val detector = KeywordDetector.load(target)
        fun maxScore(pcm: ShortArray): Float {
            detector.reset()
            val audio = padded(pcm)
            var best = 0f
            var i = 0
            while (i + KeywordDetector.SAMPLES_PER_FRAME <= audio.size) {
                best = maxOf(best, detector.process(audio.copyOfRange(i, i + KeywordDetector.SAMPLES_PER_FRAME)))
                i += KeywordDetector.SAMPLES_PER_FRAME
            }
            return best
        }
        val pos = positives.map { (n, pcm) -> n to maxScore(pcm) }
        val neg = negatives.map { (n, pcm) -> n to maxScore(pcm) }
        Log.i("WakeWordAudioTest", "openWakeWord positives=$pos negatives=$neg")
        detector.close()
        assertTrue("no 'hey jarvis' clip detected: $pos", pos.any { it.second >= 0.5f })
        assertTrue("false positive on other speech: $neg", neg.all { it.second < 0.5f })
    }

    @Test fun voskDetectsSingleWordJarvisAndIgnoresOtherSpeech() {
        val positives = clips("jarvis_")
        val negatives = clips("neg_")
        assumeTrue("audio fixtures not generated", positives.isNotEmpty() && negatives.isNotEmpty())
        val model = VoskModel(target, OkHttpClient(), "vosk/test-en", VoskModel.EN_URL, 40)
        assumeTrue("could not download the Vosk model", runBlocking { model.download() })
        val loaded = runBlocking { model.load() }
        fun detects(pcm: ShortArray): Boolean {
            val matcher = JarvisKeywordMatcher(0.6f)
            Recognizer(loaded, 16_000f, JarvisKeywordMatcher.GRAMMAR).use { rec ->
                rec.setWords(true)
                val audio = padded(pcm)
                var i = 0
                while (i < audio.size) {
                    val chunk = audio.copyOfRange(i, minOf(audio.size, i + 1600))
                    val hit = if (rec.acceptWaveForm(chunk, chunk.size)) matcher.onResult(rec.result) else matcher.onPartial(rec.partialResult)
                    if (hit) return true
                    i += 1600
                }
                return matcher.onResult(rec.finalResult)
            }
        }
        val pos = positives.map { (n, pcm) -> n to detects(pcm) }
        val neg = negatives.map { (n, pcm) -> n to detects(pcm) }
        Log.i("WakeWordAudioTest", "vosk positives=$pos negatives=$neg")
        assertTrue("'jarvis' not detected: $pos", pos.count { it.second } * 2 >= pos.size)
        assertTrue("false positive on other speech: $neg", neg.none { it.second })
    }
}
