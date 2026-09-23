package com.example.jarvis

import android.content.Context
import com.example.BuildConfig
import com.example.jarvis.automation.HabitEngine
import com.example.jarvis.automation.ReminderEngine
import com.example.jarvis.automation.SmartPlanner
import com.example.jarvis.core.ActionExecutor
import com.example.jarvis.core.CalendarPort
import com.example.jarvis.core.CommandParser
import com.example.jarvis.core.DeviceActions
import com.example.jarvis.core.GeminiAgent
import com.example.jarvis.core.IntentResolver
import com.example.jarvis.core.JarvisEngine
import com.example.jarvis.core.MailPort
import com.example.jarvis.integrations.ActivityLauncher
import com.example.jarvis.integrations.CalendarEvent
import com.example.jarvis.integrations.CameraController
import com.example.jarvis.integrations.ContactHit
import com.example.jarvis.integrations.Contacts
import com.example.jarvis.integrations.EmailSummary
import com.example.jarvis.integrations.FileHit
import com.example.jarvis.integrations.FileManager
import com.example.jarvis.integrations.Gmail
import com.example.jarvis.integrations.GoogleApi
import com.example.jarvis.integrations.GoogleAuth
import com.example.jarvis.integrations.GoogleCalendar
import com.example.jarvis.integrations.NotificationItem
import com.example.jarvis.integrations.Notifications
import com.example.jarvis.memory.BackupManager
import com.example.jarvis.memory.ContextMemory
import com.example.jarvis.memory.ConversationHistory
import com.example.jarvis.memory.LongTermMemory
import com.example.jarvis.memory.MemoryDatabase
import com.example.jarvis.security.DatabaseEncryption
import com.example.jarvis.security.PermissionManager
import com.example.jarvis.security.SecureStore
import com.example.jarvis.settings.JarvisSettings
import com.example.jarvis.voice.AndroidSpeechToText
import com.example.jarvis.voice.NetworkMonitor
import com.example.jarvis.voice.SpeechToTextRouter
import com.example.jarvis.voice.TextToSpeechEngine
import com.example.jarvis.voice.VoiceSessionManager
import com.example.jarvis.voice.VoskSpeechToText
import com.example.jarvis.voice.WhisperSpeechToText
import com.example.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Manual dependency graph shared by the UI, the foreground service and broadcast receivers. */
class AppContainer(
    private val context: Context,
    databaseOverride: MemoryDatabase? = null,
    secureStoreOverride: SecureStore? = null
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val secureStore: SecureStore by lazy { secureStoreOverride ?: SecureStore(context) }
    val permissions by lazy { PermissionManager(context) }
    val network by lazy { NetworkMonitor(context) }

    val database: MemoryDatabase = databaseOverride ?: MemoryDatabase.build(
        context, DatabaseEncryption(context, secureStore).prepare(MemoryDatabase.NAME)
    )

    val settings = JarvisSettings(database.userSettingsDao(), appScope)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---- data & automation ----
    val reminderEngine by lazy { ReminderEngine(context, database.reminderDao(), database.taskDao()) }
    val habitEngine by lazy { HabitEngine(database.habitDao()) }
    val taskRepository by lazy { TaskRepository(database.taskDao(), database.habitDao(), reminderEngine, habitEngine) }
    val longTermMemory by lazy { LongTermMemory(database.memoryDao()) }
    val conversationHistory by lazy { ConversationHistory(database.conversationDao()) }
    val contextMemory by lazy { ContextMemory() }
    val smartPlanner by lazy { SmartPlanner(database.taskDao(), taskRepository, longTermMemory) }
    val backupManager by lazy { BackupManager(context, database) }

    // ---- integrations ----
    val activityLauncher by lazy { ActivityLauncher(context) }
    val camera by lazy { CameraController(activityLauncher) }
    val files by lazy { FileManager(context, activityLauncher) { settings.state.value.fileTreeUris } }
    val contacts by lazy { Contacts(context, activityLauncher) }
    val notifications by lazy { Notifications { permissions.hasNotificationAccess() } }
    val googleAuth by lazy { GoogleAuth(context, http) }
    private val googleApi by lazy { GoogleApi(http) { googleAuth.accessToken() } }
    val calendar by lazy {
        GoogleCalendar(context, googleApi, useRemote = { settings.state.value.googleConnected && network.isOnline() })
    }
    val gmail by lazy { Gmail(googleApi) }

    // ---- brain & voice ----
    fun apiKey(name: String, buildDefault: String, placeholder: String): String? =
        secureStore.getString(name)?.takeIf { it.isNotBlank() }
            ?: buildDefault.takeIf { it.isNotBlank() && it != placeholder }

    fun picovoiceKey(): String? =
        apiKey(SecureStore.PICOVOICE_ACCESS_KEY, BuildConfig.PICOVOICE_ACCESS_KEY, "MY_PICOVOICE_ACCESS_KEY")

    val geminiAgent by lazy {
        GeminiAgent(http, { apiKey(SecureStore.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEY, "MY_GEMINI_API_KEY") }, network::isOnline)
    }

    val executor by lazy {
        ActionExecutor(
            tasks = taskRepository, reminders = reminderEngine, habits = habitEngine, planner = smartPlanner,
            memory = longTermMemory, context = contextMemory, settings = settings,
            device = deviceActions, calendar = calendarPort, mail = mailPort
        )
    }

    val engine by lazy {
        JarvisEngine(
            parser = CommandParser(), resolver = IntentResolver(), executor = executor, agent = geminiAgent,
            history = conversationHistory, memory = longTermMemory, context = contextMemory,
            tasks = taskRepository, settings = { settings.state.value }
        )
    }

    val tts by lazy { TextToSpeechEngine(context) }
    val vosk by lazy { VoskSpeechToText(context, http) }
    val sttRouter by lazy {
        SpeechToTextRouter(
            google = AndroidSpeechToText(context, network::isOnline),
            whisper = WhisperSpeechToText(context, http,
                { apiKey(SecureStore.OPENAI_API_KEY, BuildConfig.OPENAI_API_KEY, "MY_OPENAI_API_KEY") }, network::isOnline),
            vosk = vosk,
            choice = { settings.state.value.sttEngine },
            online = network::isOnline
        )
    }
    val voice by lazy { VoiceSessionManager(engine, sttRouter, tts, appScope) }

    /** Long-running wiring that should live as long as the process. */
    fun start() {
        appScope.launch {
            settings.state.collect { s -> tts.configure(s.ttsLanguage, s.ttsRate) }
        }
    }

    /** Speaks reminder announcements when enabled and Jarvis isn't mid-conversation. */
    suspend fun announce(text: String) {
        val s = settings.state.first()
        if (!s.speakReminders || voice.isBusy) return
        withContext(Dispatchers.Main) {
            tts.configure(s.ttsLanguage, s.ttsRate)
            tts.speak(text)
        }
    }

    // ---- port adapters ----
    private val deviceActions = object : DeviceActions {
        override fun openCamera(video: Boolean) = camera.open(video)
        override fun hasFileFolders() = settings.state.value.fileTreeUris.isNotEmpty()
        override fun findFiles(query: String, extension: String?): List<FileHit> = files.search(query, extension)
        override fun openFile(hit: FileHit) = files.open(hit)
        override fun hasContactsPermission() = contacts.hasPermission()
        override fun findContacts(name: String): List<ContactHit> = contacts.find(name)
        override fun findEmail(name: String): String? = contacts.findEmail(name)
        override fun call(contact: ContactHit) = contacts.call(contact)
        override fun notificationsAvailable() = notifications.isAvailable()
        override fun recentNotifications(): List<NotificationItem> = notifications.recent()
        override fun clearNotifications() = notifications.clearAll()
    }

    private val calendarPort = object : CalendarPort {
        override suspend fun available(): Boolean =
            (settings.state.value.googleConnected && network.isOnline()) ||
                permissions.isGranted(com.example.jarvis.security.JarvisCapability.CALENDAR)
        override suspend fun eventsOn(date: LocalDate): List<CalendarEvent> = calendar.eventsOn(date)
        override suspend fun create(title: String, start: ZonedDateTime, end: ZonedDateTime, description: String) =
            calendar.create(title, start, end, description)
        override suspend fun findByTitle(title: String, date: LocalDate?) = calendar.findByTitle(title, date)
        override suspend fun update(event: CalendarEvent, start: ZonedDateTime, end: ZonedDateTime) = calendar.update(event, start, end)
        override suspend fun delete(event: CalendarEvent) = calendar.delete(event)
    }

    private val mailPort = object : MailPort {
        override fun isConnected() = settings.state.value.googleConnected && network.isOnline()
        override suspend fun unread(max: Int): List<EmailSummary> = gmail.unread(max)
        override suspend fun draft(to: String, subject: String, body: String) = gmail.createDraft(to, subject, body)
        override suspend fun send(to: String, subject: String, body: String) = gmail.send(to, subject, body)
    }
}
