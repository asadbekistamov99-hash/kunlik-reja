package com.jarvis

import android.content.Context
import com.example.BuildConfig
import com.jarvis.automation.HabitEngine
import com.jarvis.automation.ReminderEngine
import com.jarvis.automation.SmartPlanner
import com.jarvis.automation.WorkPatternService
import com.jarvis.core.ActionExecutor
import com.jarvis.core.CalendarPort
import com.jarvis.core.CommandParser
import com.jarvis.core.DeviceActions
import com.jarvis.core.GeminiAgent
import com.jarvis.core.IntentResolver
import com.jarvis.core.JarvisEngine
import com.jarvis.core.MailPort
import com.jarvis.integrations.ActivityLauncher
import com.jarvis.integrations.CalendarEvent
import com.jarvis.integrations.CameraManager
import com.jarvis.integrations.ContactHit
import com.jarvis.integrations.ContactManager
import com.jarvis.integrations.EmailSummary
import com.jarvis.integrations.FileHit
import com.jarvis.integrations.FileManager
import com.jarvis.integrations.GmailManager
import com.jarvis.integrations.GoogleApi
import com.jarvis.integrations.GoogleAuth
import com.jarvis.integrations.CalendarManager
import com.jarvis.integrations.NotificationItem
import com.jarvis.integrations.Notifications
import com.jarvis.memory.BackupManager
import com.jarvis.memory.ContextManager
import com.jarvis.memory.ConversationMemory
import com.jarvis.memory.UserMemory
import com.jarvis.memory.MemoryDatabase
import com.jarvis.security.DatabaseEncryption
import com.jarvis.security.PermissionManager
import com.jarvis.security.SecureStore
import com.jarvis.settings.JarvisSettings
import com.jarvis.voice.AndroidSpeechToText
import com.jarvis.voice.NetworkMonitor
import com.jarvis.voice.SpeechToTextRouter
import com.jarvis.voice.GeminiVoice
import com.jarvis.voice.OpenAiVoice
import com.jarvis.voice.TextToSpeechManager
import com.jarvis.voice.VoiceSession
import com.jarvis.voice.VoskModel
import com.jarvis.voice.VoskSpeechToText
import com.jarvis.voice.WhisperSpeechToText
import com.example.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val reminderAnnouncements = Channel<String>(capacity = 32)

    init {
        appScope.launch {
            for (text in reminderAnnouncements) {
                while (voice.isBusy) delay(250)
                val s = settings.state.first()
                if (!s.speakReminders) continue
                withContext(Dispatchers.Main) {
                    tts.configure(s.ttsLanguage, s.ttsRate, s.voiceGender, s.ttsEngine)
                    tts.speak(text)
                }
            }
        }
    }

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
    val longTermMemory by lazy { UserMemory(database.memoryDao()) }
    val conversationHistory by lazy { ConversationMemory(database.conversationDao()) }
    val contextMemory by lazy { ContextManager() }
    val smartPlanner by lazy { SmartPlanner(database.taskDao(), taskRepository, longTermMemory) }
    val workPatterns by lazy { WorkPatternService(database.taskDao(), database.conversationDao(), longTermMemory) }
    val backupManager by lazy { BackupManager(context, database) }

    // ---- integrations ----
    val activityLauncher by lazy { ActivityLauncher(context) }
    val camera by lazy { CameraManager(activityLauncher) }
    val files by lazy { FileManager(context, activityLauncher) { settings.state.value.fileTreeUris } }
    val phoneCommunications by lazy { com.jarvis.integrations.PhoneCommunications(context, activityLauncher) }
    val phoneSettings by lazy { com.jarvis.phone.PhoneSettings(context, activityLauncher, phoneDriver) }
    val contacts by lazy { ContactManager(context, activityLauncher) }
    val notifications by lazy { Notifications { permissions.hasNotificationAccess() } }
    private val phoneDriver = object : com.jarvis.phone.PhoneDriver {
        override suspend fun screen(withImage: Boolean) = com.jarvis.phone.JarvisAccessibilityService.instance?.screen(withImage)
        override suspend fun act(screen: com.jarvis.phone.PhoneScreen, step: com.jarvis.phone.PhoneStep) =
            com.jarvis.phone.JarvisAccessibilityService.instance?.act(screen, step) == true
    }
    private val gamePlanner by lazy { com.jarvis.phone.GeminiGamePlanner(http,
        { apiKey(SecureStore.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEY, "MY_GEMINI_API_KEY") }, network::isOnline) }
    suspend fun analyzeChess(pgn: String): com.jarvis.core.DeviceResult =
        if (settings.state.value.aiMode == com.jarvis.settings.AiMode.OFFLINE_ONLY)
            com.jarvis.core.DeviceResult("Partiya tahlili uchun oflayn rejimini o'chiring.", false)
        else gamePlanner.analyze(pgn)
    val gameController: com.jarvis.phone.GameController by lazy { com.jarvis.phone.GameController(phoneDriver, gamePlanner, appScope,
        { phoneCommands.isGame(it) }, { settings.state.value.gameScreenConsent && settings.state.value.aiMode != com.jarvis.settings.AiMode.OFFLINE_ONLY }) }
    private val phoneCommands: com.jarvis.phone.PhoneCommands by lazy { com.jarvis.phone.PhoneCommands(context, activityLauncher, phoneDriver, gameController) }
    val googleAuth by lazy { GoogleAuth(context, http) }
    private val googleApi by lazy { GoogleApi(http) { googleAuth.accessToken() } }
    val calendar by lazy {
        CalendarManager(context, googleApi, useRemote = { settings.state.value.googleConnected && network.isOnline() })
    }
    val gmail by lazy { GmailManager(googleApi) }

    // ---- brain & voice ----
    fun apiKey(name: String, buildDefault: String, placeholder: String): String? =
        secureStore.getString(name)?.takeIf { it.isNotBlank() }
            ?: buildDefault.takeIf { BuildConfig.DEBUG && it.isNotBlank() && it != placeholder }

    fun picovoiceKey(): String? =
        apiKey(SecureStore.PICOVOICE_ACCESS_KEY, BuildConfig.PICOVOICE_ACCESS_KEY, "MY_PICOVOICE_ACCESS_KEY")

    val geminiAgent by lazy {
        GeminiAgent(http, { apiKey(SecureStore.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEY, "MY_GEMINI_API_KEY") }, network::isOnline)
    }

    val executor by lazy {
        ActionExecutor(
            tasks = taskRepository, reminders = reminderEngine, habits = habitEngine, planner = smartPlanner,
            memory = longTermMemory, context = contextMemory, settings = settings,
            device = deviceActions, calendar = calendarPort, mail = mailPort, patterns = workPatterns
        )
    }

    val engine by lazy {
        JarvisEngine(
            parser = CommandParser(), resolver = IntentResolver(), executor = executor, agent = geminiAgent,
            history = conversationHistory, memory = longTermMemory, context = contextMemory,
            tasks = taskRepository, settings = { settings.state.value }
        )
    }

    val tts by lazy {
        TextToSpeechManager(context, listOf(
            GeminiVoice(http, { apiKey(SecureStore.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEY, "MY_GEMINI_API_KEY") }, network::isOnline),
            OpenAiVoice(http, { apiKey(SecureStore.OPENAI_API_KEY, BuildConfig.OPENAI_API_KEY, "MY_OPENAI_API_KEY") }, network::isOnline)
        )).also { tts ->
            // Apply the user's OpenAI voice settings (model + speed) as soon as settings load.
            appScope.launch {
                settings.state.collect { s -> tts.openaiSettings(s.openaiTtsModel, s.openaiTtsSpeed) }
            }
        }
    }
    /** Offline Uzbek speech recognition model. */
    val voskUzModel by lazy { VoskModel(context, http, "vosk/model-uz", VoskModel.UZ_URL, 50) }
    /** Offline English model used only for grammar-restricted "Jarvis" keyword spotting. */
    val voskKeywordModel by lazy { VoskModel(context, http, "vosk/model-en", VoskModel.EN_URL, 40) }
    val vosk by lazy { VoskSpeechToText(voskUzModel) }
    val sttRouter by lazy {
        SpeechToTextRouter(
            google = AndroidSpeechToText(context, network::isOnline),
            whisper = WhisperSpeechToText(context, http,
                { apiKey(SecureStore.OPENAI_API_KEY, BuildConfig.OPENAI_API_KEY, "MY_OPENAI_API_KEY") }, network::isOnline),
            vosk = vosk,
            choice = { settings.state.value.sttEngine },
            online = network::isOnline,
            microphoneGranted = { permissions.isGranted(com.jarvis.security.JarvisCapability.MICROPHONE) }
        )
    }
    val voice by lazy { VoiceSession(engine, sttRouter, tts, appScope) }

    /** Long-running wiring that should live as long as the process. */
    fun start() {
        appScope.launch {
            settings.state.collect { s -> tts.configure(s.ttsLanguage, s.ttsRate, s.voiceGender, s.ttsEngine) }
        }
        appScope.launch {
            settings.state.map { it.assistantEnabled && it.autoDownloadModels }.distinctUntilChanged()
                .collect { if (it) downloadModelsIfAllowed() }
        }
        runCatching { network.observe { appScope.launch { downloadModelsIfAllowed() } } }
        // Re-learn work patterns from the latest history once per process start.
        appScope.launch(Dispatchers.IO) { runCatching { workPatterns.refresh() } }
    }

    private val modelDownloadLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Fetches missing offline models (keyword "Jarvis" first, then Uzbek STT) on unmetered networks,
     * so offline features work without the user having to visit Settings.
     */
    suspend fun downloadModelsIfAllowed() {
        if (!modelDownloadLock.tryLock()) return
        try {
            val s = settings.current()
            if (!s.assistantEnabled || !s.autoDownloadModels || !network.isUnmetered()) return
            var changed = false
            for (model in listOf(voskKeywordModel, voskUzModel)) {
                if (!model.isInstalled() && model.download()) changed = true
            }
            if (changed) settings.set(JarvisSettings.MODELS_REV, System.currentTimeMillis())
        } finally {
            modelDownloadLock.unlock()
        }
    }

    /** Queues reminder announcements so they are not lost during another voice turn. */
    suspend fun announce(text: String) {
        if (text.isBlank()) return
        reminderAnnouncements.trySend(text)
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
        override fun recentNotifications(limit: Int): List<NotificationItem> = notifications.recent(limit)
        override fun clearNotifications() = notifications.clearAll()
        override suspend fun openApp(name: String) = phoneCommands.open(name)
        override suspend fun controlPhone(instruction: String) = phoneCommands.execute(instruction)
        override suspend fun playGame(goal: String) = gameController.start(goal)
        override suspend fun analyzeChess(pgn: String) = this@AppContainer.analyzeChess(pgn)
        override fun callHistoryAvailable() = phoneCommunications.historyAvailable()
        override fun recentCalls(limit: Int) = phoneCommunications.recentCalls(limit)
        override fun draftSms(number: String, text: String) = phoneCommunications.sms(number, text)
        override fun addContact(name: String, number: String) = phoneCommunications.addContact(name, number)
        override fun socialDraft(app: String, text: String) = phoneCommunications.socialDraft(app, text)
        override fun replyNotification(item: NotificationItem, text: String) = com.jarvis.integrations.JarvisNotificationListener.reply(item, text)
        override suspend fun phoneSetting(command: String) = phoneSettings.execute(command)
        override fun stopPhoneControl() = gameController.stop()
    }

    private val calendarPort = object : CalendarPort {
        override suspend fun available(): Boolean =
            (settings.state.value.googleConnected && network.isOnline()) ||
                permissions.isGranted(com.jarvis.security.JarvisCapability.CALENDAR)
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
