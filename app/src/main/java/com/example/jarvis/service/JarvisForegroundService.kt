package com.example.jarvis.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.JarvisApplication
import com.example.R
import com.example.jarvis.AppContainer
import com.example.jarvis.settings.JarvisSettings
import com.example.jarvis.settings.SettingsSnapshot
import com.example.jarvis.voice.AssistantStatus
import com.example.jarvis.voice.WakeController
import com.example.jarvis.wakeword.WakeWordEngine
import com.example.jarvis.wakeword.WakeWordEngineFactory
import com.example.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 24/7 Jarvis: a microphone-type foreground service that keeps the offline wake-word detector
 * running while the app is closed or the screen is locked, with a persistent control notification.
 */
class JarvisForegroundService : LifecycleService(), WakeController {

    private lateinit var container: AppContainer
    private var wakeEngine: WakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var userPaused = false
    @Volatile private var systemPaused = false
    @Volatile private var detectionActive = false

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = container.settings.state.value
            when (intent.action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> updateSystemPause(s)
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> updateSystemPause(s)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as JarvisApplication).container
        NotificationHelper.createNotificationChannels(this)
        if (!startInForeground()) {
            stopSelf()
            return
        }
        container.voice.wakeController = this
        ContextCompat.registerReceiver(
            this, systemReceiver,
            IntentFilter().apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Rebuild the detector whenever engine-related settings change.
        lifecycleScope.launch {
            container.settings.state
                .map { Triple(it.wakeEngine, it.wakeSensitivity, container.picovoiceKey()) }
                .distinctUntilChanged()
                .collect { rebuildEngine() }
        }
        lifecycleScope.launch {
            container.settings.state.map { it.pauseInBatterySaver to it.listenWhenLocked }.distinctUntilChanged()
                .collect { updateSystemPause(container.settings.state.value) }
        }
        lifecycleScope.launch {
            container.voice.state.map { it.status }.distinctUntilChanged().collect { updateNotification() }
        }
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    container.settings.set(JarvisSettings.ASSISTANT_ENABLED, false)
                    stopSelf()
                }
            }
            ACTION_TOGGLE_PAUSE -> {
                userPaused = !userPaused
                applyDetectionState()
            }
            ACTION_LISTEN -> container.voice.startListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep listening when the user swipes the app away from recents.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(systemReceiver) }
        if (::container.isInitialized) {
            container.voice.wakeController = null
            container.voice.setStandby(AssistantStatus.OFF)
        }
        wakeEngine?.release()
        wakeEngine = null
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- WakeController ----

    override fun pauseDetection() {
        detectionActive = false
        wakeEngine?.stop()
    }

    override fun resumeDetection() = applyDetectionState()

    // ---- internals ----

    private fun startInForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // Android 12+ refuses mic foreground services started from the background.
        Log.w(TAG, "Cannot start foreground: ${e.message}")
        JarvisServiceController.postResumeNotification(this)
        false
    }

    private suspend fun rebuildEngine() {
        val s = container.settings.state.value
        wakeEngine?.release()
        val key = container.picovoiceKey()
        val engine = WakeWordEngineFactory.create(this, s.wakeEngine, key, s.wakeSensitivity)
        wakeEngine = engine
        container.voice.setStandby(AssistantStatus.STANDBY, engine.name, engine.keywordLabel)
        applyDetectionState()
    }

    private fun updateSystemPause(s: SettingsSnapshot) {
        val pm = getSystemService(PowerManager::class.java)
        val saver = s.pauseInBatterySaver && pm?.isPowerSaveMode == true
        val screenOff = !s.listenWhenLocked && pm?.isInteractive == false
        systemPaused = saver || screenOff
        applyDetectionState()
    }

    private fun applyDetectionState() {
        val engine = wakeEngine ?: return
        if (container.voice.isBusy) return
        val shouldRun = !userPaused && !systemPaused
        if (shouldRun && !detectionActive) {
            detectionActive = true
            lifecycleScope.launch(Dispatchers.Default) {
                engine.start(
                    onDetected = { lifecycleScope.launch(Dispatchers.Main) { container.voice.onWakeWord() } },
                    onError = { e ->
                        Log.e(TAG, "Wake word engine error", e)
                        detectionActive = false
                        lifecycleScope.launch(Dispatchers.Main) {
                            // Porcupine key rejected or model failed: fall back to the bundled engine once.
                            if (engine.name == "Porcupine") {
                                wakeEngine?.release()
                                val fallback = WakeWordEngineFactory.create(this@JarvisForegroundService,
                                    com.example.jarvis.settings.WakeEngineChoice.OPEN_WAKE_WORD, null, container.settings.state.value.wakeSensitivity)
                                wakeEngine = fallback
                                container.voice.setStandby(AssistantStatus.STANDBY, fallback.name, fallback.keywordLabel)
                                applyDetectionState()
                            } else {
                                container.voice.setStandby(AssistantStatus.ERROR)
                            }
                        }
                    }
                )
            }
            acquireWakeLock()
            container.voice.setStandby(AssistantStatus.STANDBY)
        } else if (!shouldRun) {
            detectionActive = false
            engine.stop()
            releaseWakeLock()
            container.voice.setStandby(AssistantStatus.PAUSED)
        }
        updateNotification()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeWord").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateNotification() {
        if (!isRunning) return
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val status = if (::container.isInitialized) container.voice.state.value else null
        val keyword = status?.wakeKeyword?.ifBlank { null } ?: "Jarvis"
        val text = when {
            userPaused -> "Pauza. Davom ettirish uchun bosing"
            systemPaused -> "Batareya tejash / qulf rejimida kutmoqda"
            status?.status == AssistantStatus.LISTENING -> "Tinglayapman…"
            status?.status == AssistantStatus.THINKING -> "O'ylayapman…"
            status?.status == AssistantStatus.SPEAKING -> status.lastReply.take(80)
            else -> "\"$keyword\" deb chaqiring — oflayn tinglayapman"
        }
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, JarvisForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationHelper.ASSISTANT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis Ultra")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(NotificationHelper.openAppIntent(this, 1, "assistant"))
            .addAction(0, "Gapirish", action(ACTION_LISTEN, 11))
            .addAction(0, if (userPaused) "Davom etish" else "Pauza", action(ACTION_TOGGLE_PAUSE, 12))
            .addAction(0, "O'chirish", action(ACTION_STOP, 13))
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        const val NOTIFICATION_ID = 4242
        const val ACTION_STOP = "com.example.jarvis.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.jarvis.TOGGLE_PAUSE"
        const val ACTION_LISTEN = "com.example.jarvis.LISTEN"

        @Volatile var isRunning = false
            private set
    }
}
