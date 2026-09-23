package com.example.jarvis.security

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.jarvis.integrations.JarvisNotificationListener

/** Every capability Jarvis can ask for, with the runtime permissions or settings screen behind it. */
enum class JarvisCapability(val titleUz: String, val reasonUz: String) {
    MICROPHONE("Mikrofon", "\"Jarvis\" so'zini eshitish va ovozli buyruqlar uchun"),
    NOTIFICATIONS("Bildirishnomalar", "Eslatmalar va doimiy Jarvis paneli uchun"),
    EXACT_ALARMS("Aniq budilnik", "Eslatmalar aynan belgilangan vaqtda chalinishi uchun"),
    BATTERY("Batareya cheklovisiz", "Jarvis fonda 24/7 ishlashi uchun"),
    CONTACTS("Kontaktlar", "\"doktor bilan bog'lan\" kabi buyruqlar uchun"),
    PHONE("Qo'ng'iroq", "Kontaktga to'g'ridan-to'g'ri qo'ng'iroq qilish uchun"),
    CALENDAR("Taqvim", "Qurilma taqvimi bilan ishlash uchun (Google hisobisiz rejim)"),
    MEDIA("Media fayllar", "Rasm, video va audio fayllarni topish uchun"),
    NOTIFICATION_ACCESS("Bildirishnomalarni o'qish", "Boshqa ilovalar xabarlarini o'qib berish uchun"),
    OVERLAY("Boshqa ilovalar ustida", "Ekran qulflangan paytda kamera va qo'ng'iroqni ochish uchun");
}

class PermissionManager(private val context: Context) {

    fun runtimePermissions(capability: JarvisCapability): Array<String> = when (capability) {
        JarvisCapability.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        JarvisCapability.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        JarvisCapability.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        JarvisCapability.PHONE -> arrayOf(Manifest.permission.CALL_PHONE)
        JarvisCapability.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        JarvisCapability.MEDIA ->
            if (Build.VERSION.SDK_INT >= 33) arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyArray()
    }

    fun isGranted(capability: JarvisCapability): Boolean = when (capability) {
        JarvisCapability.EXACT_ALARMS -> canScheduleExactAlarms()
        JarvisCapability.BATTERY -> isIgnoringBatteryOptimizations()
        JarvisCapability.NOTIFICATION_ACCESS -> hasNotificationAccess()
        JarvisCapability.OVERLAY -> Settings.canDrawOverlays(context)
        else -> runtimePermissions(capability).all { has(it) }
    }

    /** Intent for capabilities granted on a system settings page instead of a runtime dialog. */
    fun settingsIntent(capability: JarvisCapability): Intent? = when (capability) {
        JarvisCapability.EXACT_ALARMS ->
            if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()) else null
        JarvisCapability.BATTERY ->
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
        JarvisCapability.NOTIFICATION_ACCESS ->
            if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, JarvisNotificationListener::class.java).flattenToString()
                )
            } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        JarvisCapability.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())
        else -> null
    }

    fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun packageUri(): Uri = Uri.parse("package:${context.packageName}")
}
