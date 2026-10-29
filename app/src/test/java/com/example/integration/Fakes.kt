package com.example.integration

import com.jarvis.core.CalendarPort
import com.jarvis.core.DeviceActions
import com.jarvis.core.MailPort
import com.jarvis.integrations.ActivityLauncher
import com.jarvis.integrations.CalendarEvent
import com.jarvis.integrations.ContactHit
import com.jarvis.integrations.EmailSummary
import com.jarvis.integrations.FileHit
import com.jarvis.integrations.NotificationItem
import java.time.LocalDate
import java.time.ZonedDateTime

class FakeDevice : DeviceActions {
    var cameraOpened: Boolean? = null
    val calls = mutableListOf<ContactHit>()
    val opened = mutableListOf<FileHit>()
    var files: List<FileHit> = emptyList()
    var contacts: List<ContactHit> = emptyList()
    var notifications: List<NotificationItem> = emptyList()
    var callHistoryEnabled = true
    var callRows: List<com.jarvis.integrations.CallRecord> = emptyList()
    val smsDrafts = mutableListOf<Pair<String, String>>()
    val replies = mutableListOf<Pair<NotificationItem, String>>()
    override fun callHistoryAvailable() = callHistoryEnabled
    override fun recentCalls(limit: Int) = callRows.take(limit)
    override fun draftSms(number: String, text: String): com.jarvis.core.DeviceResult {
        smsDrafts += number to text
        return com.jarvis.core.DeviceResult("SMS qoralamasi ochildi; yuborishni tasdiqlang.", false)
    }
    override fun replyNotification(item: NotificationItem, text: String): com.jarvis.core.DeviceResult {
        replies += item to text
        return com.jarvis.core.DeviceResult("Javob ilovaga uzatildi.", true)
    }
    var cleared = false
    var folders = true
    var launchResult = ActivityLauncher.Result.STARTED
    var clearResult = true
    var analyzedPgn: String? = null
    override suspend fun analyzeChess(pgn: String): com.jarvis.core.DeviceResult {
        analyzedPgn = pgn
        return com.jarvis.core.DeviceResult("AI tahlili", true)
    }

    override fun openCamera(video: Boolean): ActivityLauncher.Result { cameraOpened = video; return launchResult }
    override fun hasFileFolders() = folders
    override fun findFiles(query: String, extension: String?) = files.filter { extension == null || it.name.endsWith(".$extension") }
    override fun openFile(hit: FileHit): ActivityLauncher.Result { opened += hit; return launchResult }
    override fun hasContactsPermission() = true
    override fun findContacts(name: String) = contacts.filter { it.name.contains(name, ignoreCase = true) }
    override fun findEmail(name: String) = contacts.firstOrNull { it.name.contains(name, ignoreCase = true) }?.email
    override fun call(contact: ContactHit): ActivityLauncher.Result { calls += contact; return launchResult }
    override fun notificationsAvailable() = true
    override fun recentNotifications(limit: Int) = notifications.take(limit)
    override fun clearNotifications(): Boolean {
        if (clearResult) { cleared = true; notifications = emptyList() }
        return clearResult
    }
}

class FakeCalendar(private val enabled: Boolean = true) : CalendarPort {
    val events = mutableListOf<CalendarEvent>()
    override suspend fun available() = enabled
    override suspend fun eventsOn(date: LocalDate) = events.filter { it.start.toLocalDate() == date }
    override suspend fun create(title: String, start: ZonedDateTime, end: ZonedDateTime, description: String): CalendarEvent =
        CalendarEvent("e${events.size}", title, start, end, "", CalendarEvent.Source.GOOGLE).also { events += it }
    override suspend fun findByTitle(title: String, date: LocalDate?) = events.firstOrNull { it.title.contains(title, true) }
    override suspend fun update(event: CalendarEvent, start: ZonedDateTime, end: ZonedDateTime): CalendarEvent {
        events.remove(event); return event.copy(start = start, end = end).also { events += it }
    }
    override suspend fun delete(event: CalendarEvent) { events.remove(event) }
}

class FakeMail(private val connected: Boolean = true) : MailPort {
    val sent = mutableListOf<Triple<String, String, String>>()
    var sendFailure: Exception? = null
    val drafts = mutableListOf<Triple<String, String, String>>()
    var inbox = listOf(EmailSummary("1", "Ali", "Hisobot", "Salom", ""))
    override fun isConnected() = connected
    override suspend fun unread(max: Int) = inbox
    override suspend fun draft(to: String, subject: String, body: String) = "d1".also { drafts += Triple(to, subject, body) }
    override suspend fun send(to: String, subject: String, body: String): String {
        sendFailure?.let { throw it }
        sent += Triple(to, subject, body)
        return "m1"
    }
}
