package com.jarvis.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.JarvisApplication

/** Quick Settings tile: one tap from the notification shade and Jarvis is listening. */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (JarvisForegroundService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitleCompat("Gapiring")
            updateTile()
        }
    }

    override fun onClick() {
        if (JarvisForegroundService.isRunning) {
            (application as JarvisApplication).container.voice.startListening()
            return
        }
        // Service not running: go through the invisible assist activity, which may start it.
        val intent = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun Tile.subtitleCompat(text: String) {
        if (Build.VERSION.SDK_INT >= 29) subtitle = text
    }
}
