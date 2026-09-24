package com.example.jarvis

import android.content.Context
import com.example.data.Task
import com.example.notification.NotificationHelper

/** Deadline-based timer: survives backgrounding and process recreation. */
object FocusSession {
    private const val ID = 2147483000
    private fun prefs(c: Context) = c.getSharedPreferences("focus_session", Context.MODE_PRIVATE)
    fun remaining(c: Context): Int {
        val p = prefs(c)
        return if(p.getBoolean("running", false)) ((p.getLong("deadline", 0) - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
        else p.getInt("remaining", 25 * 60)
    }
    fun running(c: Context) = prefs(c).getBoolean("running", false) && remaining(c) > 0
    fun total(c: Context) = prefs(c).getInt("total", 25 * 60)
    fun start(c: Context, minutes: Int) {
        require(minutes in 1..180) { "Fokus 1–180 daqiqa bo'lsin." }
        prefs(c).edit().putInt("total", minutes * 60).putInt("remaining", minutes * 60).apply()
        resume(c)
    }
    fun resume(c: Context) {
        val seconds = remaining(c)
        require(seconds > 0) { "Yangi fokus sessiyasini boshlang." }
        val deadline = System.currentTimeMillis() + seconds * 1000L
        prefs(c).edit().putBoolean("running", true).putLong("deadline", deadline).apply()
        NotificationHelper.scheduleTaskAlarm(c, Task(id = ID, title = "Fokus yakunlandi", description = "Dam olish vaqti.", dateString = "", timeString = "", timestampMillis = deadline, reminderMinutesBefore = 0))
    }
    fun pause(c: Context) {
        prefs(c).edit().putInt("remaining", remaining(c)).putBoolean("running", false).apply()
        NotificationHelper.cancelTaskAlarm(c, ID)
    }
    fun stop(c: Context) {
        prefs(c).edit().putBoolean("running", false).putInt("remaining", 0).apply()
        NotificationHelper.cancelTaskAlarm(c, ID)
    }
    fun due(c: Context) = prefs(c).getBoolean("running", false) && remaining(c) == 0
    fun isFocusAlarm(id: Int) = id == ID
}
