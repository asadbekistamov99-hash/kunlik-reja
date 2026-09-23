package com.example.jarvis.integrations

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val clearable: Boolean
)

/** Receives other apps' notifications once the user enables notification access for Jarvis. */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        instance = null
        _active.value = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val list = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        _active.value = list
            .filter { it.packageName != packageName && !it.isOngoing }
            .mapNotNull { toItem(it) }
            .sortedByDescending { it.postTime }
    }

    private fun toItem(sbn: StatusBarNotification): NotificationItem? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val label = runCatching {
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(sbn.packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        return NotificationItem(sbn.key, sbn.packageName, label, title, text, sbn.postTime, sbn.isClearable)
    }

    companion object {
        @Volatile private var instance: JarvisNotificationListener? = null
        private val _active = MutableStateFlow<List<NotificationItem>>(emptyList())
        val active: StateFlow<List<NotificationItem>> = _active.asStateFlow()

        val isConnected: Boolean get() = instance != null

        fun dismiss(key: String): Boolean = instance?.let { it.cancelNotification(key); true } ?: false

        fun dismissAll(): Boolean = instance?.let { it.cancelAllNotifications(); true } ?: false
    }
}

/** Read/manage notifications for voice commands. */
class Notifications(private val hasAccess: () -> Boolean) {

    fun isAvailable(): Boolean = hasAccess() && JarvisNotificationListener.isConnected

    fun recent(limit: Int = 10): List<NotificationItem> = JarvisNotificationListener.active.value.take(limit)

    fun clearAll(): Boolean = JarvisNotificationListener.dismissAll()

    fun dismiss(item: NotificationItem): Boolean = JarvisNotificationListener.dismiss(item.key)

    companion object {
        fun describe(items: List<NotificationItem>): String {
            if (items.isEmpty()) return "Yangi bildirishnomalar yo'q."
            val sb = StringBuilder("${items.size} ta bildirishnoma bor. ")
            items.take(5).forEach { n ->
                sb.append("${n.appName}: ${n.title}")
                if (n.text.isNotBlank()) sb.append(" — ${n.text.take(120)}")
                sb.append(". ")
            }
            return sb.toString().trim()
        }
    }
}
