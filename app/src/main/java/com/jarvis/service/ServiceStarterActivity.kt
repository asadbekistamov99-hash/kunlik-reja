package com.jarvis.service

import android.app.Activity
import android.os.Bundle

/**
 * Invisible, single-frame activity used after reboot on Android 14+. Those versions forbid
 * starting a microphone foreground service from BOOT_COMPLETED, but an app holding the
 * "display over other apps" permission may start an activity from the background, and a
 * visible activity may start the microphone service. The activity finishes immediately.
 */
class ServiceStarterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisServiceController.start(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
