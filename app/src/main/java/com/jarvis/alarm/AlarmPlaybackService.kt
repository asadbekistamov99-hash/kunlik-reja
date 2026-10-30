package com.jarvis.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.notification.NotificationHelper
import com.example.receiver.TaskReminderReceiver

/** Owns alarm audio independently of the microphone/TTS service and the receiver's lifetime. */
class AlarmPlaybackService : Service() {
    private val alarms = linkedMapOf<Int, Intent>()
    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (intent.action == ACTION_STOP) {
            alarms.remove(id)
            getSystemService(NotificationManager::class.java).cancel(id)
            val remaining = alarms.values.lastOrNull()
            if (remaining == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                startForeground(remaining.getIntExtra(EXTRA_ID, 0), notification(this, remaining))
            }
            return START_NOT_STICKY
        }
        if (id == 0 || intent.getStringExtra(EXTRA_TITLE).isNullOrBlank()) {
            stopSelf(); return START_NOT_STICKY
        }
        alarms[id] = Intent(intent)
        startForeground(id, notification(this, intent))
        if (player == null) startAudio()
        return START_NOT_STICKY
    }

    private fun startAudio() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            media.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            media.setDataSource(this, uri)
            media.isLooping = true
            media.prepare()
            media.start()
            player = media
        } catch (_: Exception) {
            media.release() // The notification channel still provides its configured alarm sound.
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        alarms.keys.forEach { getSystemService(NotificationManager::class.java).cancel(it) }
        alarms.clear()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.jarvis.alarm.RING"
        const val ACTION_STOP = "com.jarvis.alarm.STOP"
        const val EXTRA_ID = "alarm_notification_id"
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_MESSAGE = "alarm_message"

        fun payload(context: Context, id: Int, title: String, message: String): Intent =
            Intent(context, AlarmPlaybackService::class.java).setAction(ACTION_RING)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_MESSAGE, message)

        fun ring(context: Context, id: Int, title: String, message: String, taskId: Int? = null) {
            val intent = payload(context, id, title, message)
            taskId?.let { intent.putExtra(TaskReminderReceiver.EXTRA_TASK_ID, it) }
            val manager = androidx.core.app.NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) runCatching { manager.notify(id, notification(context, intent)) }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: IllegalStateException) {
                // Without the exact-alarm exemption Android may refuse a background FGS.
                // The actionable notification posted above remains available.
            } catch (_: SecurityException) {
                // The OS can revoke a foreground-service exemption between the checks.
            }
        }

        fun stop(context: Context, id: Int) {
            context.startService(Intent(context, AlarmPlaybackService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
            NotificationHelper.cancel(context, id)
        }

        fun action(context: Context, payload: Intent, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, payload.getIntExtra(EXTRA_ID, 0),
            Intent(context, TaskReminderReceiver::class.java).setAction(action).putExtras(payload)
                .putExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID, payload.getIntExtra(EXTRA_ID, 0)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        fun notification(context: Context, payload: Intent): Notification {
            NotificationHelper.createNotificationChannels(context)
            val id = payload.getIntExtra(EXTRA_ID, 0)
            val screen = PendingIntent.getActivity(context, id,
                Intent(context, AlarmActivity::class.java).putExtras(payload)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle(payload.getStringExtra(EXTRA_TITLE))
                .setContentText(payload.getStringExtra(EXTRA_MESSAGE))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(screen).setFullScreenIntent(screen, true)
                .setDeleteIntent(action(context, payload, TaskReminderReceiver.ACTION_ALARM_DISMISS))
                .addAction(0, "O'tkazib yuborish", action(context, payload, TaskReminderReceiver.ACTION_ALARM_DISMISS))
                .addAction(0, "Pauza · 5 daqiqa", action(context, payload, TaskReminderReceiver.ACTION_ALARM_SNOOZE))
            if (payload.hasExtra(TaskReminderReceiver.EXTRA_TASK_ID)) {
                builder.addAction(0, "Bajarildi", action(context, payload, TaskReminderReceiver.ACTION_MARK_COMPLETED))
            }
            return builder.build()
        }
    }
}
