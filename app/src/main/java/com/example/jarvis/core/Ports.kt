package com.example.jarvis.core

import com.example.jarvis.integrations.ActivityLauncher
import com.example.jarvis.integrations.CalendarEvent
import com.example.jarvis.integrations.ContactHit
import com.example.jarvis.integrations.EmailSummary
import com.example.jarvis.integrations.FileHit
import com.example.jarvis.integrations.NotificationItem
import java.time.LocalDate
import java.time.ZonedDateTime

/** Phone capabilities the agent can use. Abstracted so the agent logic is testable off-device. */
interface DeviceActions {
    fun openCamera(video: Boolean): ActivityLauncher.Result
    fun hasFileFolders(): Boolean
    fun findFiles(query: String, extension: String?): List<FileHit>
    fun openFile(hit: FileHit): ActivityLauncher.Result
    fun hasContactsPermission(): Boolean
    fun findContacts(name: String): List<ContactHit>
    fun findEmail(name: String): String?
    fun call(contact: ContactHit): ActivityLauncher.Result
    fun notificationsAvailable(): Boolean
    fun recentNotifications(): List<NotificationItem>
    fun clearNotifications(): Boolean
}

interface CalendarPort {
    suspend fun available(): Boolean
    suspend fun eventsOn(date: LocalDate): List<CalendarEvent>
    suspend fun create(title: String, start: ZonedDateTime, end: ZonedDateTime, description: String = ""): CalendarEvent
    suspend fun findByTitle(title: String, date: LocalDate?): CalendarEvent?
    suspend fun update(event: CalendarEvent, start: ZonedDateTime, end: ZonedDateTime): CalendarEvent
    suspend fun delete(event: CalendarEvent)
}

interface MailPort {
    fun isConnected(): Boolean
    suspend fun unread(max: Int = 5): List<EmailSummary>
    suspend fun draft(to: String, subject: String, body: String): String
    suspend fun send(to: String, subject: String, body: String): String
}

/** Optional online reasoning (LLM function calling). */
interface AgentPort {
    fun isAvailable(): Boolean
    suspend fun decide(text: String, context: AgentContext): ResolvedIntent?
}

data class AgentContext(
    val now: java.time.LocalDateTime,
    val profile: String,
    val todayTasks: String,
    val history: List<Pair<String, String>>
)

data class ResponseCard(val title: String, val items: List<String>)

data class JarvisResponse(
    val text: String,
    val intent: IntentType,
    val success: Boolean = true,
    val card: ResponseCard? = null,
    /** Jarvis asked a question and should listen again without the wake word. */
    val expectsReply: Boolean = false,
    val startSession: Boolean = false,
    val endSession: Boolean = false
)
