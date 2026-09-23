package com.example.integration

import com.example.jarvis.core.CalendarPort
import com.example.jarvis.core.DeviceActions
import com.example.jarvis.core.MailPort
import com.example.jarvis.integrations.ActivityLauncher
import com.example.jarvis.integrations.CalendarEvent
import com.example.jarvis.integrations.ContactHit
import com.example.jarvis.integrations.EmailSummary
import com.example.jarvis.integrations.FileHit
import com.example.jarvis.integrations.NotificationItem
import java.time.LocalDate
import java.time.ZonedDateTime

class FakeDevice : DeviceActions {
    var cameraOpened: Boolean? = null
    val calls = mutableListOf<ContactHit>()
    val opened = mutableListOf<FileHit>()
    var files: List<FileHit> = emptyList()
    var contacts: List<ContactHit> = emptyList()
    var notifications: List<NotificationItem> = emptyList()
    var cleared = false
    var folders = true

    override fun openCamera(video: Boolean): ActivityLauncher.Result { cameraOpened = video; return ActivityLauncher.Result.STARTED }
    override fun hasFileFolders() = folders
    override fun findFiles(query: String, extension: String?) = files.filter { extension == null || it.name.endsWith(".$extension") }
    override fun openFile(hit: FileHit): ActivityLauncher.Result { opened += hit; return ActivityLauncher.Result.STARTED }
    override fun hasContactsPermission() = true
    override fun findContacts(name: String) = contacts.filter { it.name.contains(name, ignoreCase = true) }
    override fun findEmail(name: String) = contacts.firstOrNull { it.name.contains(name, ignoreCase = true) }?.email
    override fun call(contact: ContactHit): ActivityLauncher.Result { calls += contact; return ActivityLauncher.Result.STARTED }
    override fun notificationsAvailable() = true
    override fun recentNotifications() = notifications
    override fun clearNotifications(): Boolean { cleared = true; notifications = emptyList(); return true }
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
    val drafts = mutableListOf<Triple<String, String, String>>()
    var inbox = listOf(EmailSummary("1", "Ali", "Hisobot", "Salom", ""))
    override fun isConnected() = connected
    override suspend fun unread(max: Int) = inbox
    override suspend fun draft(to: String, subject: String, body: String) = "d1".also { drafts += Triple(to, subject, body) }
    override suspend fun send(to: String, subject: String, body: String) = "m1".also { sent += Triple(to, subject, body) }
}
