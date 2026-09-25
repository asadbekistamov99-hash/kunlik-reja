package com.jarvis.service

import android.app.Activity
import android.os.Bundle
import com.example.JarvisApplication

/**
 * Entry point for the system assistant gesture (long-press power/home when Jarvis is the default
 * assistant app) and headset voice buttons. Makes sure the 24/7 service runs, starts listening,
 * and closes immediately — the conversation continues in the background with no app UI.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JarvisApplication).container
        if (!JarvisServiceController.canStart(this)) {
            // First use: the microphone permission must be granted inside the app once.
            startActivity(android.content.Intent(this, com.example.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            if (!JarvisForegroundService.isRunning) JarvisServiceController.start(this)
            container.voice.startListening()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
