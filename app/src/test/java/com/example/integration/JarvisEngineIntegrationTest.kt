package com.example.integration

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.TestJarvisApplication
import com.example.data.MemoryType
import com.example.data.Task
import com.example.jarvis.automation.HabitEngine
import com.example.jarvis.automation.ReminderEngine
import com.example.jarvis.automation.SmartPlanner
import com.example.jarvis.core.ActionExecutor
import com.example.jarvis.core.AgentContext
import com.example.jarvis.core.AgentPort
import com.example.jarvis.core.CommandParser
import com.example.jarvis.core.IntentResolver
import com.example.jarvis.core.IntentType
import com.example.jarvis.core.JarvisEngine
import com.example.jarvis.core.ResolvedIntent
import com.example.jarvis.integrations.ContactHit
import com.example.jarvis.integrations.FileHit
import com.example.jarvis.integrations.NotificationItem
import com.example.jarvis.memory.ContextMemory
import com.example.jarvis.memory.ConversationHistory
import com.example.jarvis.memory.LongTermMemory
import com.example.jarvis.memory.MemoryDatabase
import com.example.jarvis.settings.AiMode
import com.example.jarvis.settings.JarvisSettings
import com.example.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * End-to-end agent flows on a real (in-memory) Room database with the offline NLU only —
 * i.e. exactly what runs with no internet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestJarvisApplication::class)
class JarvisEngineIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: MemoryDatabase
    private lateinit var engine: JarvisEngine
    private lateinit var tasks: TaskRepository
    private lateinit var settings: JarvisSettings
    private lateinit var memory: LongTermMemory
    private val device = FakeDevice()
    private val calendar = FakeCalendar()
    private val mail = FakeMail()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = LocalDateTime.of(2026, 9, 23, 10, 15)
    private var agent: AgentPort? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = MemoryDatabase.inMemory(context)
        val clockMillis = { now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        val reminders = ReminderEngine(context, db.reminderDao(), db.taskDao(), clockMillis)
        val habits = HabitEngine(db.habitDao())
        tasks = TaskRepository(db.taskDao(), db.habitDao(), reminders, habits)
        memory = LongTermMemory(db.memoryDao())
        settings = JarvisSettings(db.userSettingsDao(), scope)
        val ctx = ContextMemory()
        val planner = SmartPlanner(db.taskDao(), tasks, memory) { now }
        val executor = ActionExecutor(tasks, reminders, habits, planner, memory, ctx, settings, device, calendar, mail, { now })
        engine = JarvisEngine(CommandParser { now }, IntentResolver(), executor, object : AgentPort {
            override fun isAvailable() = agent?.isAvailable() == true
            override suspend fun decide(text: String, context: AgentContext) = agent?.decide(text, context)
        }, ConversationHistory(db.conversationDao()), memory, ctx, tasks, { settings.state.value }, { now })
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun say(text: String) = runBlocking { engine.handle(text) }

    @Test fun `adds tomorrow 9am meeting, schedules reminder and syncs calendar`() = runBlocking {
        val r = say("Jarvis ertaga soat 9 da uchrashuv qo'sh")
        assertEquals(IntentType.ADD_TASK, r.intent)
        assertTrue(r.text, r.text.contains("ertaga soat 09:00"))
        val saved = db.taskDao().getAll().single()
        assertEquals("Uchrashuv", saved.title)
        assertEquals("2026-09-24", saved.dateString)
        assertEquals("09:00", saved.timeString)
        assertEquals("WORK", saved.category)
        assertEquals(1, calendar.events.size)
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
        assertTrue("reminder alarm scheduled", alarms.isNotEmpty())
    }

    @Test fun `plans the day and moves overdue tasks`() = runBlocking {
        tasks.insertTask(task("Hisobot", "2026-09-22", "18:00", "HIGH"))
        tasks.insertTask(task("Majlis", "2026-09-23", "11:00"))
        val r = say("Jarvis bugungi rejani tuz")
        assertEquals(IntentType.PLAN_DAY, r.intent)
        assertNotNull(r.card)
        val moved = db.taskDao().getAll().first { it.title == "Hisobot" }
        assertEquals("2026-09-23", moved.dateString)
        assertTrue(moved.timeString >= "10:15")
    }

    @Test fun `lists unfinished work`() = runBlocking {
        tasks.insertTask(task("Kitob o'qish", "2026-09-23", "20:00"))
        tasks.insertTask(task("Bajarilgan", "2026-09-23", "08:00").copy(isCompleted = true))
        val r = say("Jarvis tugallanmagan ishlarimni ko'rsat")
        assertEquals(IntentType.LIST_PENDING, r.intent)
        assertTrue(r.text, r.text.contains("1 ta tugallanmagan"))
        assertEquals(listOf("2026-09-23 20:00 — Kitob o'qish"), r.card!!.items)
    }

    @Test fun `remembers daily habit permanently and creates habit tracker`() = runBlocking {
        val r = say("Men har kuni ertalab sport qilaman")
        assertEquals(IntentType.REMEMBER, r.intent)
        val habits = memory.byType(MemoryType.HABIT)
        assertEquals(1, habits.size)
        assertTrue(habits[0].content.contains("sport"))
        assertEquals("Ertalab sport qilish", db.habitDao().getAll().single().title)
        assertEquals(1440, db.reminderDao().getAll().single().repeatIntervalMinutes)
        // Saying it again does not duplicate.
        say("men har kuni ertalab sport qilaman")
        assertEquals(1, memory.byType(MemoryType.HABIT).size)
        val recall = say("men haqimda nima bilasan")
        assertTrue(recall.text, recall.text.contains("sport"))
    }

    @Test fun `profile name is remembered`() = runBlocking {
        say("mening ismim Asadbek")
        assertEquals("Ismingiz Asadbek.", say("ismim nima").text)
    }

    @Test fun `session start and stop`() {
        assertTrue(say("Jarvis boshla").startSession)
        assertTrue(engine.isSessionActive)
        val end = say("Jarvis tugat")
        assertTrue(end.endSession)
        assertFalse(engine.isSessionActive)
    }

    @Test fun `asks for missing title then completes the task`() = runBlocking {
        val q = say("vazifa qo'sh")
        assertTrue(q.expectsReply)
        say("Stomatologga borish")
        assertEquals("Stomatologga borish", db.taskDao().getAll().single().title)
    }

    @Test fun `email send requires confirmation`() {
        val ask = say("ali@example.com ga xat yubor: ertaga uchrashamiz")
        assertTrue(ask.expectsReply)
        assertTrue(mail.sent.isEmpty())
        say("ha")
        assertEquals("ali@example.com", mail.sent.single().first)
        say("ali@example.com ga xat yubor: bekor bo'ladi")
        say("yo'q")
        assertEquals(1, mail.sent.size)
    }

    @Test fun `phone integrations`() {
        assertEquals(false, say("Jarvis kamera och").let { device.cameraOpened })
        device.contacts = listOf(ContactHit("Doktor Karimov", "+998901234567"))
        say("Jarvis doktor bilan bog'lan")
        assertEquals("Doktor Karimov", device.calls.single().name)
        device.files = listOf(FileHit("hisobot.pdf", Uri.parse("content://a/1"), "application/pdf", 1, 2),
            FileHit("shartnoma.pdf", Uri.parse("content://a/2"), "application/pdf", 1, 1))
        val found = say("Jarvis PDF faylimni top")
        assertTrue(found.expectsReply)
        say("ikkinchisini och")
        assertEquals("shartnoma.pdf", device.opened.single().name)
        device.notifications = listOf(NotificationItem("k", "p", "Telegram", "Ali", "Salom", 0, true))
        assertTrue(say("bildirishnomalarni o'qi").text.contains("Telegram"))
        say("bildirishnomalarni tozala"); say("ha")
        assertTrue(device.cleared)
    }

    @Test fun `complete and reschedule by voice`() = runBlocking {
        tasks.insertTask(task("Uchrashuv", "2026-09-23", "15:00"))
        say("uchrashuvni soat 17 ga ko'chir")
        assertEquals("17:00", db.taskDao().getAll().single().timeString)
        say("uchrashuvni bajardim")
        assertTrue(db.taskDao().getAll().single().isCompleted)
    }

    @Test fun `reminder in 30 minutes`() = runBlocking {
        say("30 daqiqadan keyin suv ichishni eslat")
        val r = db.reminderDao().getAll().single()
        assertEquals(now.plusMinutes(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), r.triggerAtMillis)
    }

    @Test fun `offline mode never calls the agent and unknown falls back gracefully`() = runBlocking {
        var called = false
        agent = object : AgentPort {
            override fun isAvailable() = true
            override suspend fun decide(text: String, context: AgentContext): ResolvedIntent? {
                called = true
                return ResolvedIntent(IntentType.UNKNOWN, 0.9f, mapOf(ResolvedIntent.ANSWER to "Koinot 13.8 mlrd yosh."))
            }
        }
        settings.set(JarvisSettings.AI_MODE, AiMode.OFFLINE_ONLY.name)
        waitForSettings { it.aiMode == AiMode.OFFLINE_ONLY }
        assertFalse(say("koinotning yoshi qancha").success)
        assertFalse(called)
        settings.set(JarvisSettings.AI_MODE, AiMode.HYBRID.name)
        waitForSettings { it.aiMode == AiMode.HYBRID }
        assertEquals("Koinot 13.8 mlrd yosh.", say("koinotning yoshi qancha").text)
        assertTrue(called)
        // High-confidence commands stay on-device even when the agent is available.
        called = false
        say("Jarvis tugallanmagan ishlarimni ko'rsat")
        assertFalse(called)
    }

    @Test fun `conversation history is persisted`() = runBlocking {
        say("salom")
        val log = db.conversationDao().getAll()
        assertEquals(2, log.size)
        assertEquals("USER", log[0].role)
    }

    private fun waitForSettings(predicate: (com.example.jarvis.settings.SettingsSnapshot) -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!predicate(settings.state.value) && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun task(title: String, date: String, time: String, priority: String = "MEDIUM") = Task(
        title = title, dateString = date, timeString = time, priority = priority,
        timestampMillis = LocalDateTime.parse("${date}T$time").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
}
