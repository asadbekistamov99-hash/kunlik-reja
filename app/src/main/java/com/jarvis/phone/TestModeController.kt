package com.jarvis.phone

import android.content.Context
import android.content.Intent

/** Explicit test overlay state; Accessibility alone never enables it. */
object TestModeController {
    const val ACTION = "com.jarvis.TEST_MODE"
    private const val PREFS = "jarvis_test_mode"
    private const val KEY_ENABLED = "enabled"
    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName).putExtra(KEY_ENABLED, enabled))
    }
}
