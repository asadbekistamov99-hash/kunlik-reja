package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.receiver.TaskReminderReceiver

object NotificationHelper {
    const val CHANNEL_ID = "kun_tartibi_reminders"
    const val ASSISTANT_CHANNEL_ID = "jarvis_assistant"
    const val ALERTS_CHANNEL_ID = "jarvis_alerts"

    private var activeRingtone: Ringtone? = null

    fun createNotificationChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alarmAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        val reminders = NotificationChannel(CHANNEL_ID, "Eslatmalar va budilnik", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Vazifa va eslatma signallari"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), alarmAttributes)
        }
        val assistant = NotificationChannel(ASSISTANT_CHANNEL_ID, "Jarvis fon xizmati", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Jarvis tinglayotganini ko'rsatuvchi doimiy panel"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(ALERTS_CHANNEL_ID, "Jarvis xabarlari", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Jarvis harakatlari: kamera, qo'ng'iroq, qayta ishga tushirish"
        }
        nm.createNotificationChannels(listOf(reminders, assistant, alerts))
    }

    fun playAlarmSound(context: Context, durationMs: Long = 3000L) {
        try {
            activeRingtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = false
            ringtone.play()
            activeRingtone = ringtone
            Handler(Looper.getMainLooper()).postDelayed({
                activeRingtone?.stop()
                activeRingtone = null
            }, durationMs)
        } catch (_: Exception) {
        }
    }

    fun openAppIntent(context: Context, requestCode: Int = 0, route: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun showNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
        taskId: Int? = null,
        channel: String = CHANNEL_ID,
        contentIntent: PendingIntent? = null
    ) {
        createNotificationChannels(context)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (channel == CHANNEL_ID) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent ?: openAppIntent(context, notificationId))
        if (taskId != null) {
            val done = PendingIntent.getBroadcast(
                context, taskId,
                Intent(context, TaskReminderReceiver::class.java)
                    .setAction(TaskReminderReceiver.ACTION_MARK_COMPLETED)
                    .putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId)
                    .putExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Bajarildi", done)
        }
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS revoked between the check and the call.
            }
        }
    }

    fun cancel(context: Context, notificationId: Int) = NotificationManagerCompat.from(context).cancel(notificationId)
}
