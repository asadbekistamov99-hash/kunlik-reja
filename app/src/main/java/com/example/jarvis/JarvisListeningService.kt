package com.example.jarvis

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.*
import java.util.Locale

/** User-started microphone service. Never starts from boot or silently restarts after force-stop. */
class JarvisListeningService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: JarvisRuntime
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stopped = false
    private var busy = false
    private var errors = 0
    private var speaking = false
    private val listenAgain = Runnable { listen() }
    private val speechTimeout = Runnable { speaking = false; schedule(500) }
    private val recognitionTimeout = Runnable {
        recognizer?.cancel()
        retry(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        runtime = JarvisRuntime.get(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if(recognizer != null) return START_NOT_STICKY
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runtime.report("Mikrofon ruxsati kerak. Jarvisni ilovadan yoqing."); stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if(Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis ovozli boshqaruv", NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, ID, notification("Jarvis boshla deng"), if(Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            if(!SpeechRecognizer.isRecognitionAvailable(this)) {
                runtime.report("Ovozni tanish xizmati topilmadi. Matnli buyruqlardan foydalaning."); stopSelf(); return START_NOT_STICKY
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            tts = TextToSpeech(this) { status ->
                handler.post {
                    if(!stopped && status == TextToSpeech.SUCCESS) {
                        val result = tts?.setLanguage(Locale.forLanguageTag("uz-UZ"))
                        ttsReady = result != null && result >= 0
                    }
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { handler.post { speechFinished() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { handler.post { speechFinished() } }
            })
            runtime.listening(true)
            runtime.report("Fon rejimi yoqildi. Jarvis yoki Jarvis boshla deng.")
            schedule(300)
        } catch(e: Exception) {
            runtime.report("Ovozli xizmat ishga tushmadi. Ilovani ochib, mikrofon ruxsatini tekshiring."); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 81, Intent(this, MainActivity::class.java).apply {
            putExtra("open_jarvis", true); flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 82, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis · ${if(runtime.status.value.active) "Faol" else "Kutish"}")
            .setContentText(message.take(150)).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Mikrofonni o'chirish", stop).build()
    }
    private fun schedule(delay: Long) {
        handler.removeCallbacks(listenAgain)
        if(!stopped) handler.postDelayed(listenAgain, delay)
    }
    private fun listen() {
        if(stopped || busy || speaking) return
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
            handler.removeCallbacks(recognitionTimeout)
            handler.postDelayed(recognitionTimeout, 30_000)
        } catch(e: Exception) { retry(SpeechRecognizer.ERROR_CLIENT) }
    }
    private fun speechFinished() {
        handler.removeCallbacks(speechTimeout)
        speaking = false
        schedule(600)
    }
    private fun answer(message: String) {
        if(stopped) return
        getSystemService(NotificationManager::class.java).notify(ID, notification(message))
        if(ttsReady) {
            speaking = true
            handler.postDelayed(speechTimeout, 25_000)
            val result = tts?.speak(message.take(900), TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.nanoTime()}")
            if(result != TextToSpeech.SUCCESS) speechFinished()
        } else schedule(500)
    }
    override fun onResults(results: Bundle?) {
        handler.removeCallbacks(recognitionTimeout)
        if(stopped || busy) return
        errors = 0
        val raw = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        val text = JarvisCommands.normalize(raw)
        val addressed = Regex("^jarvis(?:[ ,:]|$)").containsMatchIn(text)
        val command = JarvisCommands.parse(raw, com.example.repository.TaskRepository.getTodayDateString())
        // In standby, only a wake phrase is accepted. Unrelated ambient speech cannot mutate data.
        if(!runtime.status.value.active && !(addressed && command.action == "WAKE")) { schedule(500); return }
        if(text.isBlank()) { schedule(500); return }
        busy = true
        scope.launch {
            try { answer(runtime.command(raw)) } finally { busy = false }
        }
    }
    private fun retry(error: Int) {
        handler.removeCallbacks(recognitionTimeout)
        if(stopped) return
        if(error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            runtime.report("Mikrofon ruxsati yoki o'zbekcha ovoz tanish mavjud emas. Matnli buyruq ishlaydi."); stopSelf(); return
        }
        if(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { schedule(900); return }
        errors++
        if(errors >= 5) { runtime.report("Ovoz xizmati to'xtadi ($error). Internet va mikrofonni tekshirib, qayta yoqing."); stopSelf(); return }
        runtime.report("Ovoz xizmati qayta ulanmoqda ($errors/5).")
        schedule((1000L shl errors).coerceAtMost(16_000))
    }
    override fun onError(error: Int) = retry(error)
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        runtime.listening(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "jarvis_microphone"
        private const val ID = 2147483001
        private const val STOP = "com.example.jarvis.STOP"
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, JarvisListeningService::class.java)) }
            catch(e: Exception) { JarvisRuntime.get(context).report("Jarvisni ilova ochiq paytda yoqing. Mikrofon ruxsatini tekshiring.") }
        }
        fun stop(context: Context) { context.stopService(Intent(context, JarvisListeningService::class.java)) }
    }
}
