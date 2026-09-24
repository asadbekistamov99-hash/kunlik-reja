package com.jarvis.integrations

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.notification.NotificationHelper

/**
 * Starts activities on behalf of voice commands. Android 10+ blocks background activity starts, so
 * when Jarvis is running from the service with the app closed (and no overlay permission) it posts
 * a one-tap notification instead of failing silently.
 */
class ActivityLauncher(private val context: Context) {

    enum class Result { STARTED, NOTIFIED, NO_APP }

    fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun launch(intent: Intent, fallbackTitle: String): Result {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null && intent.action != Intent.ACTION_CALL) {
            return Result.NO_APP
        }
        if (isAppVisible() || Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                return Result.STARTED
            } catch (_: ActivityNotFoundException) {
                return Result.NO_APP
            } catch (_: SecurityException) {
                // fall through to notification
            }
        }
        val pi = PendingIntent.getActivity(
            context, fallbackTitle.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationHelper.showNotification(
            context, fallbackTitle, "Ochish uchun bosing", 200_000 + (fallbackTitle.hashCode() and 0xFFF),
            channel = NotificationHelper.ALERTS_CHANNEL_ID, contentIntent = pi
        )
        return Result.NOTIFIED
    }
}
