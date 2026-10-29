package com.example.integration

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.TestJarvisApplication
import com.example.data.MemoryType
import com.example.data.Task
import com.jarvis.automation.HabitEngine
import com.jarvis.automation.ReminderEngine
import com.jarvis.automation.SmartPlanner
import com.jarvis.automation.WorkPatternService
import com.jarvis.core.ActionExecutor
import com.jarvis.core.AgentContext
import com.jarvis.core.AgentPort
import com.jarvis.core.CommandParser
import com.jarvis.core.IntentResolver
import com.jarvis.core.IntentType
import com.jarvis.core.JarvisEngine
import com.jarvis.core.ResolvedIntent
import com.jarvis.integrations.ContactHit
import com.jarvis.integrations.FileHit
import com.jarvis.integrations.NotificationItem
import com.jarvis.memory.ContextManager
import com.jarvis.memory.ConversationMemory
import com.jarvis.memory.UserMemory
import com.jarvis.memory.MemoryDatabase
import com.jarvis.settings.AiMode
import com.jarvis.settings.JarvisSettings
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
    private lateinit var memory: UserMemory
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
        memory = UserMemory(db.memoryDao())
        settings = JarvisSettings(db.userSettingsDao(), scope)
        val ctx = ContextManager()
        val planner = SmartPlanner(db.taskDao(), tasks, memory) { now }
        val patterns = WorkPatternService(db.taskDao(), db.conversationDao(), memory)
        val executor = ActionExecutor(tasks, reminders, habits, planner, memory, ctx, settings, device, calendar, mail,
            patterns = patterns, clock = { now })
        engine = JarvisEngine(CommandParser { now }, IntentResolver(), executor, object : AgentPort {
            override fun isAvailable() = agent?.isAvailable() == true
            override suspend fun decide(text: String, context: AgentContext) = agent?.decide(text, context)
        }, ConversationMemory(db.conversationDao()), memory, ctx, tasks, { settings.state.value }, { now })
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
        assertEquals("Sport qilish", db.habitDao().getAll().single().title)
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
        val callAsk = say("Jarvis doktor bilan bog'lan")
        assertTrue(callAsk.expectsReply)
        assertTrue(device.calls.isEmpty())
        say("ha")
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

    @Test fun `deadline task is stored and planned first`() = runBlocking {
        val r = say("Jarvis hisobotni jumagacha tayyorla")
        assertEquals(IntentType.ADD_TASK, r.intent)
        assertTrue(r.text, r.text.contains("Muddati"))
        val saved = db.taskDao().getAll().single()
        assertEquals("2026-09-25", saved.deadline)
        // A task planned for next week whose deadline is tomorrow is pulled into today's plan.
        tasks.insertTask(task("Taqdimot", "2026-09-28", "10:00").copy(deadline = "2026-09-24"))
        say("Jarvis bugungi rejani tuz")
        assertEquals("2026-09-23", db.taskDao().getAll().first { it.title == "Taqdimot" }.dateString)
        assertTrue(say("Jarvis tugallanmagan ishlarimni ko'rsat").text.contains("muddati yaqin"))
    }

    @Test fun `habit said with an exact hour lands at that hour in tomorrow's plan`() = runBlocking {
        say("Men har kuni 7 da sport qilaman")
        assertEquals("Sport qilish", db.habitDao().getAll().single().title)
        val plan = say("Jarvis ertangi rejani tuz")
        assertEquals(IntentType.PLAN_DAY, plan.intent)
        assertTrue(plan.card!!.items.toString(), plan.card!!.items.any { it.startsWith("07:00") && it.contains("Sport qilish") })
    }

    @Test fun `work patterns are analysed and remembered`() = runBlocking {
        assertFalse(say("Jarvis ish odatlarim qanday").success)
        repeat(6) { i ->
            val t = tasks.insertTask(task("Ish $i", "2026-09-2${i % 3}", "09:00"))
            tasks.updateTask(t.copy(isCompleted = true, completedAt = t.timestampMillis + 600_000))
        }
        val r = say("Jarvis ish odatlarim qanday")
        assertTrue(r.text, r.success)
        assertTrue(r.text, r.text.contains("09:00"))
        assertTrue(memory.byType(MemoryType.PATTERN).isNotEmpty())
    }

    @Test fun `best recognizer alternative is chosen and misheard words are repaired`() = runBlocking {
        assertEquals("Jarvis bugungi rejani tuz",
            engine.bestTranscript(listOf("Jarvis bu gun gi re jani", "Jarvis bugungi rejani tuz", "")))
        // Apostrophe-less and misheard input still executes the right action.
        assertEquals(IntentType.PLAN_DAY, say("Jarvis bugungi rejeni tuz").intent)
        assertEquals(IntentType.ADD_TASK, say("Jarvis ertaga soat 10 da yigilish qosh").intent)
        assertEquals("Yig'ilish", db.taskDao().getAll().single().title)
    }

    @Test fun `conversation history is persisted`() = runBlocking {
        say("salom")
        val log = db.conversationDao().getAll()
        assertEquals(2, log.size)
        assertEquals("USER", log[0].role)
    }


    @Test fun `reminder asks for exact morning time and keeps title date and daily repetition`() = runBlocking {
        assertTrue(say("har kuni ertalab dori ichishni eslat").expectsReply)
        assertTrue(db.reminderDao().getAll().isEmpty())
        assertTrue(say("bilmayman").expectsReply)
        assertTrue(db.reminderDao().getAll().isEmpty())
        val reply = say("ertalab soat olti o'ttizda")
        val saved = db.reminderDao().getAll().single()
        assertEquals(1440, saved.repeatIntervalMinutes)
        assertTrue(saved.title, saved.title.contains("Dori", ignoreCase = true))
        assertEquals(now.toLocalDate().plusDays(1).atTime(6, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), saved.triggerAtMillis)
        assertTrue(reply.text, reply.text.contains("06:30"))
    }

    @Test fun `past explicit reminder is not saved and followup can replace its date`() = runBlocking {
        assertTrue(say("bugun soat 09:00 da dori ichishni eslat").expectsReply)
        assertTrue(db.reminderDao().getAll().isEmpty())
        val response = say("ertaga soat 09:30 da")
        assertFalse(response.expectsReply)
        assertEquals(now.toLocalDate().plusDays(1).atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            db.reminderDao().getAll().single().triggerAtMillis)
    }

    @Test fun `ambiguous reminder requires period before scheduling`() = runBlocking {
        assertTrue(say("ertaga soat 3 da uchrashuvni eslat").expectsReply)
        assertTrue(db.reminderDao().getAll().isEmpty())
        say("tushdan keyin soat 3 da")
        assertEquals(now.toLocalDate().plusDays(1).atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            db.reminderDao().getAll().single().triggerAtMillis)
    }

    @Test fun `missing exact alarm permission is disclosed`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val response = say("ertaga soat 18:30 da dori ichishni eslat")
        assertTrue(response.text, response.text.contains("kechikishi mumkin"))
        assertEquals(1, db.reminderDao().getAll().size)
    }

    @Test fun `cloud cannot replace a spoken reminder time or invent a missing one`() = runBlocking {
        agent = object : AgentPort {
            override fun isAvailable() = true
            override suspend fun decide(text: String, context: AgentContext) = ResolvedIntent(
                IntentType.ADD_REMINDER, 0.99f, mapOf(ResolvedIntent.TITLE to "Dori", ResolvedIntent.TIME to "22:00",
                    ResolvedIntent.DATE to "2026-09-30"))
        }
        settings.set(JarvisSettings.AI_MODE, AiMode.CLOUD_FIRST.name)
        waitForSettings { it.aiMode == AiMode.CLOUD_FIRST }
        say("ertaga soat 18:30 da dori ichishni eslat")
        assertEquals(now.toLocalDate().plusDays(1).atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            db.reminderDao().getAll().single().triggerAtMillis)
        assertTrue(say("dori ichishni eslat").expectsReply)
        assertEquals(1, db.reminderDao().getAll().size)
    }

    @Test fun `time-only reminder is today and asks for its title`() = runBlocking {
        assertTrue(say("soat 12:00 ga eslatma qo'y").expectsReply)
        assertTrue(db.reminderDao().getAll().isEmpty())
        say("darsim bor")
        val saved = db.reminderDao().getAll().single()
        assertEquals("Darsim bor", saved.title)
        assertEquals(now.toLocalDate().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), saved.triggerAtMillis)
    }

    @Test fun `undated elapsed reminder never moves silently to tomorrow`() = runBlocking {
        val reply = say("soat 09:00 ga darsimni eslat")
        assertTrue(reply.expectsReply)
        assertTrue(reply.text, reply.text.contains("o'tib ketgan"))
        assertTrue(db.reminderDao().getAll().isEmpty())
    }

    @Test fun `correcting last reminder edits existing alarm rather than adding one`() = runBlocking {
        say("soat 12:03 ga eslatma qo'y")
        say("darsim bor")
        val before = db.reminderDao().getAll().single()
        val reply = say("12:03 ga qo'yding shuni 12:00 ga o'zgartir")
        assertEquals(IntentType.RESCHEDULE_REMINDER, reply.intent)
        val after = db.reminderDao().getAll().single()
        assertEquals(before.id, after.id)
        assertEquals(before.title, after.title)
        assertEquals(now.toLocalDate().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), after.triggerAtMillis)
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(after.triggerAtMillis, alarms.single().triggerAtTime)
    }

    @Test fun `wake opens continuous session and stop jarvis closes it`() {
        say("hey jarvis")
        assertTrue(engine.isSessionActive)
        say("salom")
        assertTrue(engine.isSessionActive)
        assertTrue(say("stop jarvis").endSession)
        assertFalse(engine.isSessionActive)
    }

    @Test fun `voice loop survives silence and more than 25 turns until stop`() = runBlocking {
        var calls = 0
        val recognizer = object : com.jarvis.voice.SpeechToText {
            override val id = "test"
            override fun isAvailable() = true
            override suspend fun listen(events: com.jarvis.voice.SttEvents, maxMillis: Long): String? {
                kotlinx.coroutines.delay(1)
                calls++
                return when {
                    calls <= 3 -> null
                    calls <= 33 -> "salom"
                    else -> "stop jarvis"
                }
            }
        }
        val output = object : com.jarvis.voice.SpeechOutput {
            override suspend fun speak(text: String) = true
            override fun stop() {}
        }
        val voice = com.jarvis.voice.VoiceSession(engine, recognizer, output, scope)
        voice.startListening()
        kotlinx.coroutines.withTimeout(15_000) {
            while (voice.isBusy) kotlinx.coroutines.delay(10)
        }
        assertEquals(34, calls)
        assertFalse(engine.isSessionActive)
        assertTrue(voice.state.value.lastUser.contains("stop"))
    }

    @Test fun `stop jarvis cancels a pending question`() = runBlocking {
        say("hey jarvis")
        assertTrue(say("soat 12:00 ga eslatma qo'y").expectsReply)
        assertTrue(say("stop jarvis").endSession)
        assertFalse(engine.isSessionActive)
        say("darsim bor")
        assertTrue(db.reminderDao().getAll().isEmpty())
    }

    @Test fun `rejection and delayed send are never confirmation`() {
        for (answer in listOf("yo'q, keyin yubor", "keyin yubor", "ha, yuborma", "yo'q, ha demadim")) {
            assertTrue(say("ali@example.com ga xat yubor: ertaga uchrashamiz").expectsReply)
            say(answer)
            assertTrue(answer, mail.sent.isEmpty())
        }
        say("ali@example.com ga xat yubor: ertaga uchrashamiz")
        say("ha, yubor")
        assertEquals(1, mail.sent.size)
    }

    @Test fun `date-only clarification is preserved until the time arrives`() = runBlocking {
        assertTrue(say("bugun soat 09:00 da dori ichishni eslat").expectsReply)
        assertTrue(say("ertaga").expectsReply)
        assertTrue(db.reminderDao().getAll().isEmpty())
        say("soat 12:00 da")
        val saved = db.reminderDao().getAll().single()
        assertEquals(now.toLocalDate().plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), saved.triggerAtMillis)
        assertTrue(saved.title, saved.title.contains("Dori", ignoreCase = true))
    }

    @Test fun `conflicting speech times ask before scheduling and keep the reminder title`() = runBlocking {
        val first = "ertaga soat 12:00 da darsimni eslat"
        val question = engine.handle(first, listOf(first, "ertaga soat 12:03 da darsimni eslat"))
        assertTrue(question.expectsReply)
        assertFalse(question.success)
        assertTrue(db.reminderDao().getAll().isEmpty())
        say("soat 12:00 da")
        val saved = db.reminderDao().getAll().single()
        assertTrue(saved.title, saved.title.contains("Darsim", true))
        assertEquals(now.toLocalDate().plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), saved.triggerAtMillis)
    }

    @Test fun `conflicting times in followup cannot bypass clarification`() = runBlocking {
        say("dori ichishni eslat")
        val response = engine.handle("soat 12:00 da", listOf("soat 12:00 da", "soat 12:03 da"))
        assertTrue(response.expectsReply)
        assertTrue(db.reminderDao().getAll().isEmpty())
        say("soat 12:03 da")
        assertEquals(now.toLocalDate().atTime(12, 3).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), db.reminderDao().getAll().single().triggerAtMillis)
    }

    @Test fun `unavailable phone commands and notification failures are not successful`() {
        assertFalse(say("Free Fire ilovasini och").success)
        assertFalse(say("ekranni o'qi").success)
        assertFalse(say("shu o'yinni o'yna").success)
        device.notifications = listOf(NotificationItem("k", "p", "Telegram", "Ali", "Salom", 0, true))
        device.clearResult = false
        say("bildirishnomalarni tozala")
        assertFalse(say("ha").success)
        assertEquals(1, device.notifications.size)
    }

    @Test fun `dialer and missing application never claim a completed call`() {
        device.contacts = listOf(ContactHit("Ali", "+998901234567"))
        device.launchResult = com.jarvis.integrations.ActivityLauncher.Result.DIALER
        say("Ali bilan bog'lan")
        val dialer = say("ha")
        assertFalse(dialer.success)
        assertTrue(dialer.text, dialer.text.contains("Raqam terish oynasi"))
        device.launchResult = com.jarvis.integrations.ActivityLauncher.Result.NO_APP
        say("Ali bilan bog'lan")
        assertFalse(say("ha").success)
    }

    @Test fun `confirmed network failure is reported and next command still works`() {
        mail.sendFailure = java.io.IOException("Connection lost")
        say("ali@example.com ga xat yubor: salom")
        val response = say("ha")
        assertFalse(response.success)
        assertTrue(response.text, response.text.contains("tasdiqlay olmadim"))
        assertTrue(mail.sent.isEmpty())
        assertEquals(IntentType.GREETING, say("salom").intent)
    }

    @Test fun `confirmed cancellation is propagated rather than converted to a result`() {
        mail.sendFailure = kotlinx.coroutines.CancellationException("User stopped")
        say("ali@example.com ga xat yubor: salom")
        assertTrue(runCatching { say("ha") }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun `unexpected recognizer error is visible without crashing the conversation scope`() = runBlocking {
        val recognizer = object : com.jarvis.voice.SpeechToText {
            override val id = "broken"
            override fun isAvailable() = true
            override suspend fun listen(events: com.jarvis.voice.SttEvents, maxMillis: Long): String? = error("Recognition service failed")
        }
        val output = object : com.jarvis.voice.SpeechOutput {
            override suspend fun speak(text: String) = true
            override fun stop() {}
        }
        val voice = com.jarvis.voice.VoiceSession(engine, recognizer, output, scope)
        voice.startListening()
        kotlinx.coroutines.withTimeout(5_000) { while (voice.isBusy) kotlinx.coroutines.delay(10) }
        assertFalse(engine.isSessionActive)
        assertNotNull(voice.state.value.error)
        assertEquals(com.jarvis.voice.AssistantStatus.ERROR, voice.state.value.status)
    }

    @Test fun `voice session forwards conflicting hypotheses and waits for exact time`() = runBlocking {
        var turn = 0
        val replies = mutableListOf<String>()
        val recognizer = object : com.jarvis.voice.SpeechToText {
            override val id = "test"
            override fun isAvailable() = true
            override suspend fun listen(events: com.jarvis.voice.SttEvents, maxMillis: Long): String {
                kotlinx.coroutines.delay(1)
                return when (turn++) {
                    0 -> {
                        val text = "ertaga soat 12:00 da darsimni eslat"
                        events.onAlternatives(listOf(text, "ertaga soat 12:03 da darsimni eslat"))
                        text
                    }
                    1 -> {
                        check(db.reminderDao().getAll().isEmpty())
                        "soat 12:00 da"
                    }
                    else -> "stop jarvis"
                }
            }
        }
        val output = object : com.jarvis.voice.SpeechOutput {
            override suspend fun speak(text: String): Boolean { replies += text; return true }
            override fun stop() {}
        }
        val voice = com.jarvis.voice.VoiceSession(engine, recognizer, output, scope)
        voice.startListening()
        kotlinx.coroutines.withTimeout(5_000) { while (voice.isBusy) kotlinx.coroutines.delay(10) }
        assertTrue(replies.first(), replies.first().contains("turlicha eshitdim"))
        assertEquals(3, turn)
        assertEquals(now.toLocalDate().plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), db.reminderDao().getAll().single().triggerAtMillis)
    }

    @Test fun `today reminder list counts only selected day's records`() = runBlocking {
        say("bugun soat 12:00 da darsni eslat")
        say("bugun soat 14:00 da dorini eslat")
        say("ertaga soat 12:00 da sportni eslat")
        val response = say("bugungi jami eslatmalarni ayt")
        assertEquals(IntentType.LIST_REMINDERS, response.intent)
        assertEquals(2, response.card!!.items.size)
        assertFalse(response.text, response.text.contains("Sport"))
    }

    @Test fun `next reminder conversation advances including equal-time occurrences and stops at end`() = runBlocking {
        say("soat 12:00 da darsni eslat")
        say("soat 12:00 da dorini eslat")
        say("soat 14:00 da sportni eslat")
        assertTrue(say("keyingi eslatmam qaysi").text.contains("Dars"))
        assertTrue(say("undan keyin qaysi").text.contains("Dori"))
        assertTrue(say("undan keyingisi qaysi").text.contains("Sport"))
        assertTrue(say("undan keyin qaysi").text.contains("yo'q"))
        assertTrue(say("keyingi eslatmam qaysi").text.contains("Dars"))
    }

    @Test fun `rename and day-only time-only updates preserve reminder id and alarm`() = runBlocking {
        say("soat 12:00 da darsni eslat")
        val original = db.reminderDao().getAll().single()
        say("shuni nomini ingliz tili deb o'zgartir")
        var updated = db.reminderDao().getAll().single()
        assertEquals("Ingliz tili", updated.title)
        assertEquals(original.triggerAtMillis, updated.triggerAtMillis)
        say("shuni kunini ertaga o'zgartir")
        say("shuni vaqtini soat 14:30 ga o'zgartir")
        updated = db.reminderDao().getAll().single()
        assertEquals(original.id, updated.id)
        assertEquals(now.toLocalDate().plusDays(1).atTime(14, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), updated.triggerAtMillis)
        assertEquals(1, shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.size)
    }

    @Test fun `new reminder name can be supplied in followup`() = runBlocking {
        say("soat 12:00 da darsni eslat")
        assertTrue(say("eslatma nomini o'zgartir").expectsReply)
        say("Matematika darsi")
        assertEquals("Matematika darsi", db.reminderDao().getAll().single().title)
    }

    @Test fun `duplicate reminder names are deleted by selected id only`() = runBlocking {
        say("soat 12:00 da darsni eslat")
        say("soat 14:00 da darsni eslat")
        val before = db.reminderDao().getAll().sortedBy { it.triggerAtMillis }
        assertTrue(say("dars eslatmasini o'chir").expectsReply)
        assertTrue(say("ikkinchisi").expectsReply)
        say("ha")
        assertEquals(before.first().id, db.reminderDao().getAll().single().id)
    }

    @Test fun `selected second reminder keeps identity while asking for its time`() = runBlocking {
        say("soat 12:00 da darsni eslat")
        say("soat 14:00 da dorini eslat")
        val before = db.reminderDao().getAll().sortedBy { it.triggerAtMillis }
        say("eslatmalarni ko'rsat")
        assertTrue(say("ikkinchi eslatmani o'zgartir").expectsReply)
        say("soat 15:00 da")
        assertEquals(before.first(), db.reminderDao().getById(before.first().id))
        assertEquals(now.toLocalDate().atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), db.reminderDao().getById(before.last().id)!!.triggerAtMillis)
    }

    @Test fun `today includes daily occurrence after it has rearmed for tomorrow but not before first day`() = runBlocking {
        val zone = ZoneId.systemDefault()
        val yesterday = now.toLocalDate().minusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        val today = now.toLocalDate().atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        val engine = ReminderEngine(context, db.reminderDao(), db.taskDao()) { now.atZone(zone).toInstant().toEpochMilli() }
        val daily = engine.addReminder(com.example.data.Reminder(title = "Dori", triggerAtMillis = today,
            firstTriggerAtMillis = yesterday, repeatIntervalMinutes = 1440))
        engine.onReminderFired(daily.id)
        engine.addReminder(com.example.data.Reminder(title = "Kelasi hafta", triggerAtMillis = today + 7 * 86_400_000L, repeatIntervalMinutes = 1440))
        val response = say("bugungi jami eslatmalarni ayt")
        assertEquals(1, response.card!!.items.size)
        assertTrue(response.text.contains("Dori"))
        assertFalse(response.text.contains("Kelasi hafta"))
    }

    @Test fun `completed chess pgn is preserved through a followup question`() {
        val pgn = "1. e4 e5 2. Nf3 Nc6 1-0"
        assertTrue(say("shaxmat partiyasini tahlil qil").expectsReply)
        assertEquals(IntentType.ANALYZE_CHESS, say(pgn).intent)
        assertEquals(pgn, device.analyzedPgn)
    }

    @Test fun `daily reminder navigation continues with tomorrow's occurrence without adding records`() = runBlocking {
        say("har kuni soat 12:00 da dorini eslat")
        assertTrue(say("keyingi eslatmam qaysi").text.contains("bugun"))
        assertTrue(say("undan keyingisi qaysi").text.contains("ertaga"))
        assertTrue(say("undan keyingisi qaysi").text.contains("indinga"))
        assertEquals(1, db.reminderDao().getAll().size)
    }

    @Test fun `ambiguous pronoun after a list asks which reminder rather than renaming the first`() = runBlocking {
        say("soat 12:00 da darsni eslat")
        say("soat 14:00 da dorini eslat")
        val original = db.reminderDao().getAll()
        say("bugungi jami eslatmalarni ayt")
        assertTrue(say("shuni nomini yangi deb o'zgartir").expectsReply)
        assertEquals(original, db.reminderDao().getAll())
        say("ikkinchisi")
        assertEquals("Yangi", db.reminderDao().getById(original.last().id)!!.title)
    }

    @Test fun `after deleting selected reminder the next query advances from its former place`() = runBlocking {
        say("soat 12:00 da darsni eslat")
        say("soat 14:00 da dorini eslat")
        say("soat 15:00 da sportni eslat")
        say("keyingi eslatmam qaysi")
        say("undan keyingisi qaysi")
        say("uni o'chir")
        say("ha")
        assertTrue(say("undan keyingisi qaysi").text.contains("Sport"))
        assertEquals(2, db.reminderDao().getAll().size)
    }

    private fun waitForSettings(predicate: (com.jarvis.settings.SettingsSnapshot) -> Boolean) {
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

    @Test fun `seventh recent call freezes phone until confirmation`() {
        device.callRows = (1..10).map { com.jarvis.integrations.CallRecord(it.toLong(), "Ali $it", "+9989012345${it.toString().padStart(2, '0')}", 1000L, "kiruvchi") }
        val answer = say("Oxirgi 10 ta qo'ng'iroqdan 7-siga telefon qil")
        assertEquals(IntentType.CALL_FROM_HISTORY, answer.intent)
        assertTrue(answer.text, answer.expectsReply)
        assertTrue(device.calls.isEmpty())
        device.callRows = emptyList()
        say("ha")
        assertEquals("+998901234507", device.calls.single().phone)
    }

    @Test fun `listed calls retain ordering and reject hidden number`() {
        device.callRows = listOf(
            com.jarvis.integrations.CallRecord(1, "Yopiq", "", 1000, "kiruvchi", false),
            com.jarvis.integrations.CallRecord(2, "Ali", "+998901234567", 1000, "kiruvchi"))
        assertEquals(IntentType.READ_CALL_HISTORY, say("Oxirgi qo'ng'iroqlarni ayt").intent)
        assertFalse(say("1-siga telefon qil").success)
        assertFalse(say("7-siga telefon qil").success)
        device.callRows = emptyList()
        assertTrue(say("2-siga telefon qil").expectsReply)
        say("yo'q")
        assertTrue(device.calls.isEmpty())
    }

    @Test fun `sms preserves dictated body and asks missing body`() {
        val first = say("+998901234567 ga SMS yoz")
        assertEquals(IntentType.COMPOSE_SMS, first.intent)
        assertTrue(first.text, first.expectsReply)
        val body = "Salom Ali! Ertaga 12:00 da kel."
        val draft = say(body)
        assertEquals("+998901234567" to body, device.smsDrafts.single())
        assertFalse(draft.success)
    }

    @Test fun `sms body mentioning power is treated as data`() {
        val r = say("+998901234567 ga SMS yoz: Telefonni o'chir, keyin menga yoz!")
        assertEquals(IntentType.COMPOSE_SMS, r.intent)
        assertEquals("Telefonni o'chir, keyin menga yoz!", device.smsDrafts.single().second)
    }

    @Test fun `history sms requires call log permission`() {
        device.callHistoryEnabled = false
        assertFalse(say("Oxirgi 10 ta qo'ng'iroqdan 7-siga SMS yoz: Salom").success)
        assertTrue(device.smsDrafts.isEmpty())
    }

    @Test fun `telegram tenth notification is read and seventh reply requires confirmation`() {
        device.notifications = (1..10).map { NotificationItem("key$it", "org.telegram.messenger", "Telegram", "Ali $it", "Xabar $it", it.toLong(), true, true) }
        val r = say("Telegramdagi oxirgi 10 ta xabarni ayt")
        assertEquals(IntentType.READ_NOTIFICATIONS, r.intent)
        assertTrue(r.text, r.text.contains("Xabar 10"))
        val prompt = say("7-siga javob yoz: Salom Ali!")
        assertTrue(prompt.text, prompt.expectsReply)
        assertTrue(device.replies.isEmpty())
        say("ha")
        assertEquals("key7", device.replies.single().first.key)
        assertEquals("Salom Ali!", device.replies.single().second)
    }

    @Test fun `notification without reply action cannot send`() {
        device.notifications = listOf(NotificationItem("key", "com.instagram.android", "Instagram", "Ali", "Salom", 1L, true, false))
        say("Instagram xabarlarini ayt")
        assertFalse(say("1-siga javob yoz: Salom!").success)
        assertTrue(device.replies.isEmpty())
    }

    @Test fun `assistant microphone pause applies after speech and ends session`() = runBlocking {
        var paused = false
        var spoke = false
        var turns = 0
        val recognizer = object : com.jarvis.voice.SpeechToText {
            override val id = "test"
            override fun isAvailable() = true
            override suspend fun listen(events: com.jarvis.voice.SttEvents, maxMillis: Long): String {
                turns++
                return "Mikrofonni o'chir"
            }
        }
        val output = object : com.jarvis.voice.SpeechOutput {
            override suspend fun speak(text: String): Boolean {
                assertFalse(paused)
                spoke = true
                return true
            }
            override fun stop() {}
        }
        val voice = com.jarvis.voice.VoiceSession(engine, recognizer, output, scope)
        voice.wakeController = object : com.jarvis.voice.WakeController {
            override fun pauseDetection() {}
            override fun resumeDetection() {}
            override fun pauseMicrophone() { assertTrue(spoke); paused = true }
        }
        voice.startListening()
        kotlinx.coroutines.withTimeout(5000) { while (voice.isBusy) kotlinx.coroutines.delay(10) }
        assertTrue(paused)
        assertFalse(engine.isSessionActive)
        assertEquals(1, turns)
    }
}
