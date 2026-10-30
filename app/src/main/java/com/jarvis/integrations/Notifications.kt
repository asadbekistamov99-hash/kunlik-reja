package com.jarvis.integrations

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
    val clearable: Boolean,
    val canReply: Boolean = false
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
        return NotificationItem(sbn.key, sbn.packageName, label, title, text, sbn.postTime, sbn.isClearable, replyAction(sbn.notification) != null)
    }

    companion object {
        @Volatile private var instance: JarvisNotificationListener? = null
        private val _active = MutableStateFlow<List<NotificationItem>>(emptyList())
        val active: StateFlow<List<NotificationItem>> = _active.asStateFlow()

        val isConnected: Boolean get() = instance != null

        fun replyAction(notification: Notification): Notification.Action? {
            val candidates = notification.actions.orEmpty().filter { action ->
                action.remoteInputs.orEmpty().count { it.allowFreeFormInput } == 1
            }
            @Suppress("NewApi")
            val semantic = if (android.os.Build.VERSION.SDK_INT >= 28)
                candidates.filter { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }.singleOrNull() else null
            return semantic ?: candidates.singleOrNull()
        }

        fun reply(item: NotificationItem, text: String): com.jarvis.core.DeviceResult {
            val listener = instance ?: return com.jarvis.core.DeviceResult("Bildirishnoma xizmati ulanmagan.", false)
            return try {
                val current = listener.activeNotifications.orEmpty().firstOrNull { it.key == item.key }
                if (current == null || current.packageName != item.packageName || current.postTime != item.postTime ||
                    current.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() != item.title ||
                    (current.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: current.notification.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty() != item.text) {
                    return com.jarvis.core.DeviceResult("Xabar o'zgargan yoki yo'qolgan. Ro'yxatni qayta so'rang.", false)
                }
                val action = replyAction(current.notification) ?: return com.jarvis.core.DeviceResult("Bu xabarda tezkor javob mavjud emas.", false)
                val input = action.remoteInputs.first { it.allowFreeFormInput }
                val fill = android.content.Intent()
                android.app.RemoteInput.addResultsToIntent(action.remoteInputs, fill, android.os.Bundle().apply { putCharSequence(input.resultKey, text) })
                action.actionIntent.send(listener, 0, fill)
                com.jarvis.core.DeviceResult("Javob ${item.appName} ilovasiga uzatildi. Yetkazilganini ilovada tekshiring.", true)
            } catch (e: Exception) { com.jarvis.core.DeviceResult("Javob uzatilmadi. Xabar va bildirishnoma ruxsatini tekshiring.", false) }
        }

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
            items.forEachIndexed { index, n ->
                sb.append("${index + 1}. ${n.appName}: ${n.title}")
                if (n.text.isNotBlank()) sb.append(" — ${n.text}")
                sb.append(". ")
            }
            return sb.toString().trim()
        }
    }
}
