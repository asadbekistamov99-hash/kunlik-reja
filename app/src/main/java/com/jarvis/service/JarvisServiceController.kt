package com.jarvis.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.notification.NotificationHelper

object JarvisServiceController {
    private const val RESUME_NOTIFICATION_ID = 4243

    fun canStart(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Start from a foreground context (UI). */
    fun start(context: Context): Boolean {
        if (!canStart(context)) return false
        return try {
            ContextCompat.startForegroundService(context, Intent(context, JarvisForegroundService::class.java))
            NotificationHelper.cancel(context, RESUME_NOTIFICATION_ID)
            true
        } catch (e: Exception) {
            Log.w("JarvisService", "start failed: ${e.message}")
            false
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, JarvisForegroundService::class.java))
    }

    /**
     * After reboot/update. Android 14+ forbids starting a microphone foreground service from
     * BOOT_COMPLETED, so there a one-tap notification restarts Jarvis (a notification tap is an
     * allowed start). Older versions start directly and fall back to the same notification.
     */
    fun startFromBackground(context: Context) {
        if (!canStart(context)) return
        if (Build.VERSION.SDK_INT < 34) {
            if (!start(context)) postResumeNotification(context)
            return
        }
        // Fallback first; ServiceStarterActivity's successful start() cancels it again.
        postResumeNotification(context)
        if (Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(context, ServiceStarterActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            }.onFailure { Log.w("JarvisService", "Overlay restart failed: ${it.message}") }
        }
    }

    fun postResumeNotification(context: Context) {
        val pi = PendingIntent.getForegroundService(
            context, 7, Intent(context, JarvisForegroundService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationHelper.showNotification(
            context, "Jarvis tayyor", "Fonda tinglashni davom ettirish uchun bosing",
            RESUME_NOTIFICATION_ID, channel = NotificationHelper.ALERTS_CHANNEL_ID, contentIntent = pi
        )
    }
}
