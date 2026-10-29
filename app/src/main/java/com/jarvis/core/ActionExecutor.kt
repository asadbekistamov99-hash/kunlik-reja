package com.jarvis.core

import com.example.data.MemoryType
import com.example.data.Reminder
import com.example.data.Task
import com.example.data.TaskCategory
import com.example.data.TaskPriority
import com.jarvis.automation.HabitEngine
import com.jarvis.automation.ReminderEngine
import com.jarvis.automation.SmartPlanner
import com.jarvis.integrations.ActivityLauncher
import com.jarvis.integrations.GmailManager
import com.jarvis.memory.ContextManager
import com.jarvis.memory.UserMemory
import com.jarvis.memory.UserProfile
import com.jarvis.settings.JarvisSettings
import com.example.repository.TaskRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Executes a resolved intent against the app's data and the phone, producing Jarvis' reply. */
class ActionExecutor(
    private val tasks: TaskRepository,
    private val reminders: ReminderEngine,
    private val habits: HabitEngine,
    private val planner: SmartPlanner,
    private val memory: UserMemory,
    private val context: ContextManager,
    private val settings: JarvisSettings,
    private val device: DeviceActions,
    private val calendar: CalendarPort,
    private val mail: MailPort,
    private val patterns: com.jarvis.automation.WorkPatternService? = null,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val random: kotlin.random.Random = kotlin.random.Random.Default
) {

    suspend fun execute(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        context.onIntent(intent.type)
        return try {
            dispatch(intent, parsed)
        } catch (e: Exception) {
            failure(intent, e)
        }
    }

    private fun failure(intent: ResolvedIntent, e: Exception): JarvisResponse {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val message = if (e is java.io.IOException) "Tarmoq bilan aloqa uzildi. Amal bajarilganini tasdiqlay olmadim; qayta urinishdan oldin tekshiring."
            else "Amal bajarilganini tasdiqlay olmadim. Ruxsatlar va ilova holatini tekshiring."
        return JarvisResponse(message, intent.type, success = false)
    }

    fun stopPhoneControl() = device.stopPhoneControl()

    private suspend fun dispatch(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse = when (intent.type) {
        IntentType.READ_CALL_HISTORY -> readCallHistory(intent)
        IntentType.CALL_FROM_HISTORY -> callFromHistory(intent)
        IntentType.COMPOSE_SMS -> composeSms(intent)
        IntentType.SOCIAL_MESSAGE -> composeSocialMessage(intent)
        IntentType.ADD_CONTACT -> addContact(intent)
        IntentType.PHONE_SETTING -> device.phoneSetting(intent.slot(ResolvedIntent.CONTENT).orEmpty()).let { JarvisResponse(it.text, intent.type, success = it.success) }
        IntentType.PAUSE_MICROPHONE -> {
            context.sessionActive = false
            context.clear()
            device.stopPhoneControl()
            JarvisResponse("Jarvis mikrofoni pauzaga o'tadi. Qayta yoqish uchun bildirishnomadagi Gapirish tugmasini bosing.", intent.type, endSession = true)
        }
        IntentType.RESUME_MICROPHONE -> {
            context.sessionActive = true
            JarvisResponse("Jarvis mikrofoni yoqildi. Tinglayapman.", intent.type, expectsReply = true)
        }
        IntentType.OPEN_APP -> device.openApp(intent.slot(ResolvedIntent.TITLE).orEmpty()).let { JarvisResponse(it.text, intent.type, success = it.success) }
        IntentType.CONTROL_PHONE -> device.controlPhone(intent.slot(ResolvedIntent.CONTENT).orEmpty()).let { JarvisResponse(it.text, intent.type, success = it.success) }
        IntentType.PLAY_GAME -> device.playGame(intent.slot(ResolvedIntent.CONTENT).orEmpty()).let { JarvisResponse(it.text, intent.type, success = it.success) }
        IntentType.ANALYZE_CHESS -> {
            val pgn = intent.slot(ResolvedIntent.CONTENT)
            if (pgn == null) askFor(intent, ResolvedIntent.CONTENT, "Tugagan partiyaning PGN yozuvini yuboring.")
            else device.analyzeChess(pgn).let { JarvisResponse(it.text, intent.type, success = it.success) }
        }
        IntentType.WAKE -> {
            context.sessionActive = true
            JarvisResponse("Labbay! Sizni tinglayapman. To'xtatish uchun Stop Jarvis deng.", intent.type, expectsReply = true)
        }
        IntentType.START_SESSION -> {
            context.sessionActive = true
            JarvisResponse("Suhbat rejimi yoqildi. Men sizni tinglayapman.", intent.type, startSession = true, expectsReply = true)
        }
        IntentType.STOP_SESSION -> {
            device.stopPhoneControl()
            context.sessionActive = false
            context.clear()
            JarvisResponse(pick("Xo'p. Kerak bo'lsam, \"Jarvis\" deb chaqiring.", "Mayli, dam oling. Kerak bo'lsam shu yerdaman.",
                "Tushunarli. \"Hey Jarvis\" desangiz, darrov javob beraman."), intent.type, endSession = true)
        }
        IntentType.ADD_TASK -> addTask(intent)
        IntentType.COMPLETE_TASK -> completeTask(intent, parsed)
        IntentType.DELETE_TASK -> deleteTask(intent, parsed)
        IntentType.RESCHEDULE_TASK -> rescheduleTask(intent, parsed)
        IntentType.LIST_PENDING -> listPending(intent)
        IntentType.PLAN_DAY -> planDay(intent)
        IntentType.DAY_SUMMARY -> daySummary(intent)
        IntentType.SEARCH_TASKS -> searchTasks(intent)
        IntentType.ADD_REMINDER -> addReminder(intent, parsed)
        IntentType.DELETE_REMINDER -> deleteReminder(intent, parsed)
        IntentType.RENAME_REMINDER -> renameReminder(intent, parsed)
        IntentType.NEXT_REMINDER -> nextReminder(intent)
        IntentType.RESCHEDULE_REMINDER -> rescheduleReminder(intent, parsed)
        IntentType.LIST_REMINDERS -> listReminders(intent)
        IntentType.ADD_HABIT -> addHabit(intent)
        IntentType.REMEMBER -> remember(intent)
        IntentType.RECALL -> recall(intent)
        IntentType.FORGET -> forget(intent)
        IntentType.OPEN_CAMERA -> openCamera(intent)
        IntentType.FIND_FILE -> findFile(intent)
        IntentType.CALL_CONTACT -> callContact(intent)
        IntentType.READ_NOTIFICATIONS -> readNotifications(intent)
        IntentType.CLEAR_NOTIFICATIONS -> clearNotifications(intent)
        IntentType.CALENDAR_READ -> calendarRead(intent)
        IntentType.CALENDAR_DELETE -> calendarDelete(intent)
        IntentType.EMAIL_READ -> emailRead(intent)
        IntentType.EMAIL_DRAFT, IntentType.EMAIL_SEND -> composeEmail(intent)
        IntentType.WORK_PATTERNS -> workPatterns(intent)
        IntentType.TIME_QUERY -> JarvisResponse(timeAnswer(), intent.type)
        IntentType.GREETING -> greeting(intent)
        IntentType.THANKS -> JarvisResponse(pick("Arzimaydi! Yana nima yordam kerak?", "Doim xizmatingizdaman!",
            "Marhamat! Yana biror narsa bo'lsa, aytavering."), intent.type)
        IntentType.HELP -> JarvisResponse(HELP_TEXT, intent.type, card = ResponseCard("Buyruqlar", HELP_EXAMPLES))
        IntentType.UNKNOWN -> intent.slot(ResolvedIntent.ANSWER)?.let { JarvisResponse(it, intent.type) }
            ?: JarvisResponse(pick("Kechirasiz, tushunmadim. Boshqacharoq aytib ko'ra olasizmi?",
                "Buni to'liq anglay olmadim. \"Jarvis, yordam\" desangiz, nima qila olishimni aytib beraman.",
                "Uzr, yana bir bor aytib bera olasizmi?"), intent.type, success = false)
    }

    /** Runs a previously confirmed action (the user said "ha"). */
    suspend fun executeConfirmed(intent: ResolvedIntent): JarvisResponse {
        return try { confirmedAction(intent) } catch (e: Exception) { failure(intent, e) }
    }

    private suspend fun confirmedAction(intent: ResolvedIntent): JarvisResponse = when (intent.type) {
        IntentType.CALL_FROM_HISTORY -> {
            val number = intent.slot("phone") ?: return JarvisResponse("Raqam aniqlanmadi.", intent.type, success = false)
            launchResponse(device.call(com.jarvis.integrations.ContactHit(intent.slot(ResolvedIntent.CONTACT) ?: number, number)),
                "Qo'ng'iroq buyrug'i yuborildi.", "Qo'ng'iroq", intent.type)
        }
        IntentType.SOCIAL_MESSAGE -> {
            val item = context.lastNotifications.firstOrNull { it.key == intent.slot("notificationKey") }
                ?: return JarvisResponse("Tanlangan xabar yo'qolgan. Ro'yxatni qayta so'rang.", intent.type, success = false)
            device.replyNotification(item, intent.slot(ResolvedIntent.BODY).orEmpty()).let { JarvisResponse(it.text, intent.type, success = it.success) }
        }
        IntentType.EMAIL_SEND -> {
            val to = intent.slot(ResolvedIntent.TO)!!
            mail.send(to, intent.slot(ResolvedIntent.SUBJECT) ?: "", intent.slot(ResolvedIntent.BODY) ?: "")
            JarvisResponse("Xat $to manziliga yuborildi.", intent.type)
        }
        IntentType.CLEAR_NOTIFICATIONS -> {
            val cleared = device.clearNotifications()
            JarvisResponse(if (cleared) "Barcha bildirishnomalar tozalandi." else "Bildirishnomalar tozalanmadi. Ruxsat va ulanishni tekshiring.", intent.type, success = cleared)
        }
        IntentType.CALL_CONTACT -> {
            val name = intent.slot(ResolvedIntent.CONTACT).orEmpty()
            val contact = device.findContacts(name).firstOrNull()
                ?: return JarvisResponse("Kontakt topilmadi.", intent.type, success = false)
            launchResponse(device.call(contact), "${contact.name} uchun qo'ng'iroq buyrug'i yuborildi.", "Qo'ng'iroq", intent.type)
        }
        IntentType.DELETE_TASK -> {
            val title = intent.slot(ResolvedIntent.TITLE).orEmpty()
            val task = tasks.findByTitle(title)
                ?: return JarvisResponse("Vazifa topilmadi.", intent.type, success = false)
            tasks.deleteTask(task)
            JarvisResponse("\"${task.title}\" vazifasi o'chirildi.", intent.type)
        }
        IntentType.DELETE_REMINDER -> {
            val reminder = intent.slot("reminderId")?.toLongOrNull()?.let { reminders.getReminder(it) }
                ?: return JarvisResponse("Eslatma topilmadi.", intent.type, success = false)
            reminders.cancelReminder(reminder.id)
            context.selectReminder(reminder)
            JarvisResponse("\"${reminder.title}\" eslatmasi o'chirildi.", intent.type)
        }
        else -> execute(intent, ParsedCommand("", "", "", false))
    }

    // ---------------- Tasks ----------------

    private suspend fun addTask(intent: ResolvedIntent): JarvisResponse {
        val title = intent.slot(ResolvedIntent.TITLE)
            ?: return askFor(intent, ResolvedIntent.TITLE, "Qanday vazifa qo'shay?")
        val now = clock()
        val deadline = intent.slot(ResolvedIntent.DEADLINE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val explicitDate = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val explicitTime = intent.slot(ResolvedIntent.TIME)?.let { TaskPlanner.parseTime(it) }
        val duration = intent.slot(ResolvedIntent.DURATION)?.toIntOrNull()?.coerceIn(5, 600) ?: 30
        var start = when {
            explicitTime != null -> LocalDateTime.of(explicitDate ?: now.toLocalDate(), explicitTime)
            explicitDate != null && explicitDate != now.toLocalDate() -> LocalDateTime.of(explicitDate, LocalTime.of(9, 0))
            else -> now.plusMinutes(60).let { t -> LocalDateTime.of(t.toLocalDate(), TaskPlanner.roundUp(t.toLocalTime(), 30)) }
        }
        // "soat 9 da" said at 20:00 without a day means tomorrow morning.
        if (explicitDate == null && start.isBefore(now)) start = start.plusDays(1)
        val date = start.toLocalDate()
        val time = start.toLocalTime()
        val millis = start.atZone(zone()).toInstant().toEpochMilli()
        val minutesAway = java.time.Duration.between(now, start).toMinutes()
        val task = tasks.insertTask(
            Task(
                title = title,
                category = intent.slot(ResolvedIntent.CATEGORY)?.takeIf { c -> TaskCategory.entries.any { it.name == c } } ?: TaskCategory.PERSONAL.name,
                priority = intent.slot(ResolvedIntent.PRIORITY)?.takeIf { p -> TaskPriority.entries.any { it.name == p } } ?: TaskPriority.MEDIUM.name,
                dateString = date.toString(),
                timeString = time.format(HHMM),
                timestampMillis = millis,
                durationMinutes = duration,
                hasReminder = true,
                reminderMinutesBefore = if (minutesAway in 0..15) 0 else 15,
                voiceNoteText = "Jarvis orqali qo'shildi",
                deadline = deadline?.toString().orEmpty()
            )
        )
        context.rememberTasks(listOf(task))
        var reply = "\"${task.title}\" ${dayPhrase(date)} soat ${task.timeString} ga qo'shildi."
        deadline?.let { reply += " Muddati: ${dayPhrase(it)}." }
        if (settings.state.value.syncTasksToCalendar && calendar.available()) {
            runCatching {
                calendar.create(task.title, start.atZone(zone()), start.plusMinutes(duration.toLong()).atZone(zone()), "Jarvis Ultra")
            }.onSuccess { reply += " Taqvimga ham yozildi." }
        }
        return JarvisResponse(reply, IntentType.ADD_TASK)
    }

    private suspend fun resolveTask(intent: ResolvedIntent, parsed: ParsedCommand): Task? {
        intent.slot(ResolvedIntent.TITLE)?.let { title -> tasks.findByTitle(title)?.let { return it } }
        val idx = context.referencedIndex(parsed.body) ?: return null
        val list = context.lastTasks
        if (list.isEmpty()) return null
        return if (idx < 0) list.last() else list.getOrNull(idx)
    }

    private suspend fun completeTask(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        val task = resolveTask(intent, parsed)
        return if (task == null) {
            // A fuzzy title may have matched the wrong task; confirm with the user instead of
            // silently completing something they didn't mean.
            val title = intent.slot(ResolvedIntent.TITLE)
            val notFound = JarvisResponse("Qaysi vazifa bajarilganini topa olmadim. Nomini aniqroq ayting.", intent.type, success = false)
            if (!title.isNullOrBlank() && title.length > 3) {
                val candidates = tasks.search(title).filter { !it.isCompleted }
                if (candidates.size == 1) {
                    context.pendingConfirmation = intent.copy(slots = intent.slots + (ResolvedIntent.TITLE to candidates[0].title))
                    JarvisResponse("\"${candidates[0].title}\" bajarilgan deb belgilaymi?", intent.type, expectsReply = true)
                } else notFound
            } else notFound
        } else {
            // Copy with isCompleted=true lets TaskRepository stamp completedAt once; that drives
            // work-pattern learning and cancels the reminder (scheduleTask skips completed tasks).
            tasks.updateTask(task.copy(isCompleted = true))
            val remaining = tasks.tasksFor(clock().toLocalDate().toString()).count { !it.isCompleted && it.id != task.id }
            val extra = buildString {
                if (task.deadline.isNotBlank() && task.deadline <= clock().toLocalDate().toString()) append(" Muddatidan oldin bajarildi!")
                if (remaining == 0) append(" Bugungi barcha ishlar tugadi, barakalla!")
                else append(" Bugun yana $remaining ta vazifa qoldi.")
            }
            JarvisResponse("Barakalla! \"${task.title}\" bajarildi.$extra", intent.type)
        }
    }

    private suspend fun deleteTask(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        val task = resolveTask(intent, parsed)
            ?: return JarvisResponse("O'chiriladigan vazifani topa olmadim.", intent.type, success = false)
        context.pendingConfirmation = intent.copy(slots = intent.slots + (ResolvedIntent.TITLE to task.title))
        return JarvisResponse("\"${task.title}\" vazifasini o'chiraymi?", intent.type, expectsReply = true)
    }

    private suspend fun rescheduleTask(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        val task = resolveTask(intent, parsed)
            ?: return JarvisResponse("Qaysi vazifani ko'chirishni topa olmadim.", intent.type, success = false)
        val date = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: runCatching { LocalDate.parse(task.dateString) }.getOrDefault(clock().toLocalDate())
        val time = intent.slot(ResolvedIntent.TIME)?.let { TaskPlanner.parseTime(it) }
            ?: TaskPlanner.parseTime(task.timeString) ?: LocalTime.of(9, 0)
        val start = LocalDateTime.of(date, time)
        val updated = task.copy(
            dateString = date.toString(), timeString = time.format(HHMM),
            timestampMillis = start.atZone(zone()).toInstant().toEpochMilli()
        )
        tasks.updateTask(updated)
        runCatching {
            if (calendar.available()) calendar.findByTitle(task.title, runCatching { LocalDate.parse(task.dateString) }.getOrNull())
                ?.let { calendar.update(it, start.atZone(zone()), start.plusMinutes(task.durationMinutes.toLong()).atZone(zone())) }
        }
        return JarvisResponse("\"${task.title}\" ${dayPhrase(date)} soat ${updated.timeString} ga ko'chirildi.", intent.type)
    }

    private suspend fun listPending(intent: ResolvedIntent): JarvisResponse {
        val pending = tasks.pendingTasks().sortedWith(compareBy<Task> { it.dateString }.thenBy { it.timeString })
        context.rememberTasks(pending)
        if (pending.isEmpty()) return JarvisResponse("Tugallanmagan ishlaringiz yo'q. Ajoyib!", intent.type)
        val today = clock().toLocalDate().toString()
        val overdue = pending.count { it.dateString < today }
        val sb = StringBuilder("Sizda ${pending.size} ta tugallanmagan ish bor")
        if (overdue > 0) sb.append(", ulardan $overdue tasi o'z kunidan o'tib ketgan")
        val lateDeadline = pending.count { it.deadline.isNotBlank() && it.deadline < today }
        val dueSoon = pending.count { it.deadline.isNotBlank() && it.deadline >= today && it.deadline <= clock().toLocalDate().plusDays(2).toString() }
        if (lateDeadline > 0) sb.append(", $lateDeadline tasining muddati tugagan")
        if (dueSoon > 0) sb.append(", $dueSoon tasining muddati yaqin")
        sb.append(". ")
        pending.take(5).forEachIndexed { i, t -> sb.append("${i + 1}. ${t.title}. ") }
        return JarvisResponse(sb.toString().trim(), intent.type,
            card = ResponseCard("Tugallanmagan ishlar", pending.take(30).map {
                "${it.dateString} ${it.timeString} — ${it.title}" + if (it.deadline.isNotBlank()) " (muddat: ${it.deadline})" else ""
            }))
    }

    private suspend fun planDay(intent: ResolvedIntent): JarvisResponse {
        val date = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: clock().toLocalDate()
        val plan = planner.plan(date, settings.state.value, apply = true)
        return JarvisResponse(
            TaskPlanner.describe(plan), intent.type,
            card = ResponseCard("${SmartPlanner.dayLabel(date, clock().toLocalDate())} rejasi",
                plan.blocks.map { "${it.start.format(HHMM)}–${it.end.format(HHMM)}  ${it.title}" })
        )
    }

    private suspend fun daySummary(intent: ResolvedIntent): JarvisResponse {
        val date = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: clock().toLocalDate()
        val list = tasks.tasksFor(date.toString())
        context.rememberTasks(list)
        return JarvisResponse(planner.summary(date), intent.type,
            card = if (list.isEmpty()) null else ResponseCard(SmartPlanner.dayLabel(date, clock().toLocalDate()),
                list.map { "${if (it.isCompleted) "✓" else "•"} ${it.timeString} ${it.title}" }))
    }

    private suspend fun searchTasks(intent: ResolvedIntent): JarvisResponse {
        val q = intent.slot(ResolvedIntent.QUERY) ?: return askFor(intent, ResolvedIntent.QUERY, "Nimani qidiray?")
        val found = tasks.search(q)
        context.rememberTasks(found)
        if (found.isEmpty()) return JarvisResponse("\"$q\" bo'yicha vazifa topilmadi.", intent.type, success = false)
        return JarvisResponse("\"$q\" bo'yicha ${found.size} ta vazifa topildi.", intent.type,
            card = ResponseCard("Qidiruv: $q", found.map { "${it.dateString} ${it.timeString} — ${it.title}" }))
    }

    private suspend fun workPatterns(intent: ResolvedIntent): JarvisResponse {
        val service = patterns ?: return JarvisResponse("Tahlil xizmati mavjud emas.", intent.type, success = false)
        val p = service.refresh(clock().toLocalDate())
        val card = if (!p.hasEnoughData) null else ResponseCard("Ish odatlaringiz", buildList {
            if (p.productiveHours.isNotEmpty()) add("Samarali soatlar: " + p.productiveHours.joinToString(", ") { "%02d:00".format(it) })
            p.bestWeekday?.let { add("Eng unumli kun: " + com.jarvis.automation.WorkPatterns.WEEKDAYS_UZ[it.value - 1]) }
            add("Bajarish darajasi: ${(p.completionRate * 100).toInt()}%")
            p.onTimeRate?.let { add("O'z vaqtida: ${(it * 100).toInt()}%") }
            p.mostActiveAssistantHour?.let { add("Jarvis bilan eng faol soat: %02d:00".format(it)) }
        })
        return JarvisResponse(p.describe(), intent.type, success = p.hasEnoughData, card = card)
    }

    // ---------------- Reminders & habits ----------------

    private suspend fun addReminder(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        if (parsed.needsTimeClarification) {
            return askFor(intent.copy(slots = intent.slots - ResolvedIntent.TIME), ResolvedIntent.TIME,
                "Aniq soat nechada eslatay? Masalan: ertalab 06:30 yoki kechqurun 18:30.")
        }
        val time = parsed.time ?: intent.slot(ResolvedIntent.TIME)?.let { TaskPlanner.parseTime(it) }
            ?: return askFor(intent.copy(slots = intent.slots - ResolvedIntent.TIME), ResolvedIntent.TIME, "Qachon eslatay? Masalan: 30 daqiqadan keyin yoki soat 18 da.")
        val now = clock()
        var date = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: now.toLocalDate()
        val daily = intent.slot("repeatDaily") == "true" || Regex("""\bhar kuni\b""").containsMatchIn(parsed.body)
        if (daily && intent.slot(ResolvedIntent.DATE) == null && !LocalDateTime.of(date, time).isAfter(now)) date = date.plusDays(1)
        val at = parsed.relativeTarget ?: intent.slot("relativeAt")?.let { LocalDateTime.parse(it) } ?: LocalDateTime.of(date, time)
        if (!at.isAfter(now)) {
            return askFor(intent.copy(slots = intent.slots - ResolvedIntent.TIME), ResolvedIntent.TIME,
                "Bu vaqt o'tib ketgan. Qaysi sana va soatda eslatay?")
        }
        val title = intent.slot(ResolvedIntent.TITLE) ?: return askFor(
            intent.copy(slots = intent.slots + (ResolvedIntent.DATE to date.toString()) +
                (ResolvedIntent.TIME to time.format(HHMM)) + mapOfNotNullRelative(parsed.relativeTarget)),
            ResolvedIntent.TITLE, "${dayPhrase(date).replaceFirstChar { it.uppercase() }} soat ${time.format(HHMM)} da nimani eslatay?")
        val saved = reminders.addReminder(
            Reminder(
                title = title,
                message = "Jarvis eslatmasi",
                triggerAtMillis = at.atZone(zone()).toInstant().toEpochMilli(),
                repeatIntervalMinutes = if (daily) 1440 else 0
            )
        )
        context.rememberReminders(listOf(saved))
        val whenText = if (daily) "har kuni soat ${time.format(HHMM)} da" else "${dayPhrase(date)} soat ${time.format(HHMM)} da"
        val precisionNotice = if (reminders.canScheduleExactly()) "" else
            " Aniq vaqtda ishlashi uchun telefon sozlamalarida Jarvis uchun 'Signallar va eslatmalar' ruxsatini yoqing. Hozir eslatma kechikishi mumkin."
        return JarvisResponse("Xo'p, $whenText eslataman: $title.$precisionNotice", intent.type)
    }

    private fun mapOfNotNullRelative(at: LocalDateTime?): Map<String, String> =
        at?.let { mapOf("relativeAt" to it.toString()) }.orEmpty()

    private suspend fun reminderCandidates(intent: ResolvedIntent, parsed: ParsedCommand): List<Reminder> {
        intent.slot("reminderId")?.toLongOrNull()?.let { return listOfNotNull(reminders.getReminder(it)) }
        val index = context.referencedIndex(parsed.body)
        if (index != null) {
            val explicitOrdinal = Regex("""\b(?:birinchi|ikkinchi|uchinchi|to'rtinchi|beshinchi|oxirgi|\d+\s*-?chi)""").containsMatchIn(parsed.body)
            val selected = if (explicitOrdinal) context.lastReminders.getOrNull(if (index < 0) context.lastReminders.lastIndex else index)
                else context.selectedReminder ?: context.lastReminders.singleOrNull()
            if (selected == null && !explicitOrdinal) return context.lastReminders.mapNotNull { reminders.getReminder(it.id) }.distinctBy { it.id }
            return listOfNotNull(selected?.let { reminders.getReminder(it.id) })
        }
        val query = intent.slot(ResolvedIntent.TITLE).orEmpty()
        if (query.isBlank()) {
            context.selectedReminder?.let { selected -> return listOfNotNull(reminders.getReminder(selected.id)) }
            return reminders.activeReminders()
        }
        val words = CommandParser.normalize(query).split(' ').filter { it.isNotBlank() }
        return reminders.allReminders().filter { r -> words.all { it in CommandParser.normalize(r.title) } }
    }

    private fun askWhichReminder(intent: ResolvedIntent, matches: List<Reminder>): JarvisResponse {
        context.rememberReminders(matches)
        return askFor(intent.copy(slots = intent.slots - ResolvedIntent.TITLE - "reminderId"), ResolvedIntent.TITLE,
            if (matches.isEmpty()) "Qaysi eslatma? Nomini aniqroq ayting." else
                "Qaysi eslatma? " + matches.mapIndexed { i, r -> "${i + 1}. ${r.title}" }.joinToString(". "))
    }

    private suspend fun rescheduleReminder(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        val matches = reminderCandidates(intent, parsed)
        if (matches.size != 1) return askWhichReminder(intent, matches)
        val reminder = matches.single()
        val selected = intent.copy(slots = intent.slots + ("reminderId" to reminder.id.toString()))
        context.selectReminder(reminder)
        val previous = java.time.Instant.ofEpochMilli(reminder.triggerAtMillis).atZone(zone())
        val date = intent.slot(ResolvedIntent.DATE)?.let { LocalDate.parse(it) } ?: previous.toLocalDate()
        val time = intent.slot(ResolvedIntent.TIME)?.let { TaskPlanner.parseTime(it) }
            ?: previous.toLocalTime().takeIf { intent.slot(ResolvedIntent.DATE) != null }
        if (parsed.needsTimeClarification || time == null) return askFor(
            selected.copy(slots = selected.slots - ResolvedIntent.TIME), ResolvedIntent.TIME, "Eslatmani aniq qaysi vaqtga o'zgartiray?")
        val at = parsed.relativeTarget ?: LocalDateTime.of(date, time)
        if (!at.isAfter(clock())) return askFor(selected.copy(slots = selected.slots - ResolvedIntent.TIME),
            ResolvedIntent.TIME, "Bu vaqt o'tgan. Qaysi sana va soatga o'zgartiray?")
        val updated = reminders.rescheduleReminder(reminder, at.atZone(zone()).toInstant().toEpochMilli())
        context.selectReminder(updated)
        return JarvisResponse("${reminder.title} eslatmasi ${dayPhrase(date)} soat ${time.format(HHMM)} ga o'zgartirildi.", intent.type)
    }

    private suspend fun renameReminder(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        val matches = reminderCandidates(intent, parsed)
        if (matches.size != 1) return askWhichReminder(intent, matches)
        val reminder = matches.single()
        val title = intent.slot(ResolvedIntent.CONTENT)?.trim()?.replaceFirstChar { it.uppercase() }
            ?: return askFor(intent.copy(slots = intent.slots + ("reminderId" to reminder.id.toString())), ResolvedIntent.CONTENT,
                "${reminder.title} eslatmasining yangi nomi qanday bo'lsin?")
        val updated = reminders.renameReminder(reminder, title)
        context.selectReminder(updated)
        return JarvisResponse("Eslatma nomi \"$title\" deb o'zgartirildi.", intent.type)
    }

    private suspend fun deleteReminder(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        val matches = reminderCandidates(intent, parsed)
        if (matches.size != 1) return askWhichReminder(intent, matches)
        val reminder = matches.single()
        context.selectReminder(reminder)
        context.pendingConfirmation = intent.copy(slots = intent.slots + ("reminderId" to reminder.id.toString()))
        return JarvisResponse("\"${reminder.title}\" eslatmasini o'chiraymi?", intent.type, expectsReply = true)
    }

    private fun reminderLine(reminder: Reminder): String {
        val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(reminder.triggerAtMillis), zone())
        val state = if (!reminder.isActive || at.isBefore(clock())) " — vaqti o'tgan yoki faol emas" else ""
        return "${reminder.title}, ${dayPhrase(at.toLocalDate())} soat ${at.toLocalTime().format(HHMM)}$state"
    }

    private suspend fun listReminders(intent: ResolvedIntent): JarvisResponse {
        val date = intent.slot(ResolvedIntent.DATE)?.let { LocalDate.parse(it) }
        val list = if (date == null) reminders.activeReminders() else reminders.onDate(date, zone())
        context.rememberReminders(list)
        val label = date?.let { dayPhrase(it).replaceFirstChar { c -> c.uppercase() } } ?: "Faol"
        if (list.isEmpty()) return JarvisResponse("$label eslatmalar yo'q.", intent.type)
        val lines = list.mapIndexed { index, reminder -> "${index + 1}. ${reminderLine(reminder)}" }
        return JarvisResponse("$label ${list.size} ta eslatma bor. " + lines.joinToString(". "), intent.type,
            card = ResponseCard("$label eslatmalar", lines))
    }

    private suspend fun nextReminder(intent: ResolvedIntent): JarvisResponse {
        val now = clock().atZone(zone()).toInstant().toEpochMilli()
        val cursor = context.selectedReminder.takeIf { intent.slot(ResolvedIntent.CONTENT) == "advance" }
        val list = reminders.activeReminders().mapNotNull { r ->
            val after = if (cursor == null) now else maxOf(now,
                cursor.triggerAtMillis - if (r.id > cursor.id) 1 else 0)
            ReminderEngine.nextTrigger(r, after)?.let { r.copy(triggerAtMillis = it) }
        }.sortedWith(compareBy<Reminder> { it.triggerAtMillis }.thenBy { it.id })
        val next = list.firstOrNull() ?: return JarvisResponse("Boshqa yaqinlashayotgan eslatma yo'q.", intent.type)
        context.rememberReminders(list)
        context.selectReminder(next)
        return JarvisResponse("Keyingi eslatma: ${reminderLine(next)}.", intent.type,
            card = ResponseCard("Keyingi eslatma", listOf(reminderLine(next))))
    }

    private suspend fun addHabit(intent: ResolvedIntent): JarvisResponse {
        val title = intent.slot(ResolvedIntent.TITLE) ?: return askFor(intent, ResolvedIntent.TITLE, "Qanday odat qo'shay?")
        val habit = habits.learnFromStatement(title)
        memory.remember(MemoryType.HABIT, title)
        return JarvisResponse("\"${habit.title}\" odati kuzatuvga qo'shildi.", intent.type)
    }

    // ---------------- Memory ----------------

    private suspend fun remember(intent: ResolvedIntent): JarvisResponse {
        val content = intent.slot(ResolvedIntent.CONTENT)
            ?: return askFor(intent, ResolvedIntent.CONTENT, "Nimani eslab qolay?")
        val type = runCatching { MemoryType.valueOf(intent.slot(ResolvedIntent.MEMORY_TYPE) ?: "FACT") }.getOrDefault(MemoryType.FACT)
        val statement = content.replaceFirstChar { it.uppercase() }
        UserProfile.extractName(content)?.let { name ->
            memory.remember(MemoryType.PROFILE, name, key = UserProfile.PROFILE_NAME_KEY)
            settings.set(JarvisSettings.USER_NAME, name)
            return JarvisResponse("Tanishganimdan xursandman, $name! Ismingizni eslab qoldim.", intent.type)
        }
        memory.remember(type, statement)
        if (type == MemoryType.HABIT) {
            val habit = habits.learnFromStatement(content)
            val time = intent.slot(ResolvedIntent.TIME)?.let { TaskPlanner.parseTime(it) }
            if (time != null) {
                val now = clock()
                var first = LocalDateTime.of(now.toLocalDate(), time)
                if (first.isBefore(now)) first = first.plusDays(1)
                reminders.addReminder(
                    Reminder(title = habit.title, message = "Kundalik odat", triggerAtMillis = first.atZone(zone()).toInstant().toEpochMilli(),
                        repeatIntervalMinutes = 1440)
                )
                return JarvisResponse("Eslab qoldim: $statement. \"${habit.title}\" odatini kuzataman va har kuni soat ${time.format(HHMM)} da eslataman.", intent.type)
            }
            return JarvisResponse("Eslab qoldim: $statement. \"${habit.title}\" odatini kuzatib boraman.", intent.type)
        }
        return JarvisResponse("Eslab qoldim: $statement.", intent.type)
    }

    private suspend fun recall(intent: ResolvedIntent): JarvisResponse {
        val query = intent.slot(ResolvedIntent.QUERY)
        val snapshot = settings.state.value
        if (query != null && Regex("""\bism""").containsMatchIn(query)) {
            val name = snapshot.userName.ifBlank { memory.get(UserProfile.PROFILE_NAME_KEY)?.content.orEmpty() }
            return if (name.isNotBlank()) JarvisResponse("Ismingiz $name.", intent.type)
            else JarvisResponse("Ismingizni hali aytmagansiz. \"Mening ismim ...\" deb ayting.", intent.type)
        }
        val items = memory.recall(query)
        if (items.isEmpty()) return JarvisResponse("Bu haqda hali hech narsa eslab qolmaganman.", intent.type)
        val sb = StringBuilder("Men bilganlarim: ")
        items.take(5).forEach { sb.append(it.content.trimEnd('.')).append(". ") }
        return JarvisResponse(sb.toString().trim(), intent.type, card = ResponseCard("Xotira", items.map { it.content }))
    }

    private suspend fun forget(intent: ResolvedIntent): JarvisResponse {
        val q = intent.slot(ResolvedIntent.QUERY) ?: return askFor(intent, ResolvedIntent.QUERY, "Nimani unutay?")
        val n = memory.forgetMatching(q)
        return if (n > 0) JarvisResponse("$n ta yozuv xotiradan o'chirildi.", intent.type)
        else JarvisResponse("\"$q\" haqida xotirada hech narsa yo'q.", intent.type, success = false)
    }

    // ---------------- Phone ----------------

    private fun launchResponse(result: ActivityLauncher.Result, ok: String, what: String, type: IntentType): JarvisResponse {
        val text = when (result) {
            ActivityLauncher.Result.STARTED -> ok
            ActivityLauncher.Result.NOTIFIED -> "$what tayyor — bildirishnomani bosing."
            ActivityLauncher.Result.NO_APP -> "Kechirasiz, bu amal uchun ilova topilmadi."
            ActivityLauncher.Result.DIALER -> "Raqam terish oynasi ochildi. Qo'ng'iroqni boshlash uchun telefon tugmasini bosing."
        }
        return JarvisResponse(text, type, success = result == ActivityLauncher.Result.STARTED)
    }

    private fun openCamera(intent: ResolvedIntent): JarvisResponse {
        val video = intent.slot(ResolvedIntent.VIDEO) == "true"
        val r = device.openCamera(video)
        return launchResponse(r, if (video) "Video kamerani ochish buyrug'i yuborildi." else "Kamerani ochish buyrug'i yuborildi.", "Kamera", intent.type)
    }

    private fun findFile(intent: ResolvedIntent): JarvisResponse {
        val ext = intent.slot(ResolvedIntent.EXTENSION)
        val query = intent.slot(ResolvedIntent.QUERY).orEmpty()
        val hits = device.findFiles(query, ext)
        context.rememberFiles(hits)
        val kind = ext?.uppercase() ?: "fayl"
        return when {
            hits.isEmpty() && !device.hasFileFolders() && ext != null && ext != "img" ->
                JarvisResponse("Hujjatlarni qidirish uchun Sozlamalar → Fayl papkalari bo'limida papkaga ruxsat bering.", intent.type, success = false)
            hits.isEmpty() -> JarvisResponse("${kind.replaceFirstChar { it.uppercase() }} topilmadi${if (query.isNotBlank()) ": \"$query\"" else ""}.", intent.type, success = false)
            hits.size == 1 -> {
                val r = device.openFile(hits[0])
                launchResponse(r, "\"${hits[0].name}\" faylini ochish buyrug'i yuborildi.", hits[0].name, intent.type)
            }
            else -> JarvisResponse(
                "${hits.size} ta $kind topildi. Eng yangisi: ${hits[0].name}. Qaysi birini ochay?", intent.type,
                card = ResponseCard("Topilgan fayllar", hits.mapIndexed { i, h -> "${i + 1}. ${h.name}" }), expectsReply = true
            )
        }
    }

    fun openFileAt(index: Int): JarvisResponse {
        val list = context.lastFiles
        val hit = (if (index < 0) list.lastOrNull() else list.getOrNull(index))
            ?: return JarvisResponse("Bunday raqamli fayl yo'q.", IntentType.FIND_FILE, success = false)
        return launchResponse(device.openFile(hit), "\"${hit.name}\" faylini ochish buyrug'i yuborildi.", hit.name, IntentType.FIND_FILE)
    }

    private fun callContact(intent: ResolvedIntent): JarvisResponse {
        if (!device.hasContactsPermission()) {
            return JarvisResponse("Kontaktlarni ko'rish uchun ruxsat bering: Sozlamalar → Ruxsatlar.", intent.type, success = false)
        }
        val name = intent.slot(ResolvedIntent.CONTACT) ?: return askFor(intent, ResolvedIntent.CONTACT, "Kim bilan bog'lanay?")
        val found = device.findContacts(name)
        context.rememberContacts(found)
        return when (found.size) {
            0 -> JarvisResponse("\"$name\" ismli kontakt topilmadi.", intent.type, success = false)
            1 -> {
                context.pendingConfirmation = intent.copy(slots = intent.slots + (ResolvedIntent.CONTACT to found[0].name))
                JarvisResponse("${found[0].name} raqamiga qo'ng'iroq qilaymi?", intent.type, expectsReply = true)
            }
            else -> JarvisResponse(
                "${found.size} ta kontakt topildi: ${found.joinToString(", ") { it.name }}. Qaysi biriga qo'ng'iroq qilay?", intent.type,
                card = ResponseCard("Kontaktlar", found.mapIndexed { i, c -> "${i + 1}. ${c.name} — ${c.phone}" }), expectsReply = true
            )
        }
    }

    fun callContactAt(index: Int): JarvisResponse {
        val list = context.lastContacts
        val c = (if (index < 0) list.lastOrNull() else list.getOrNull(index))
            ?: return JarvisResponse("Bunday raqamli kontakt yo'q.", IntentType.CALL_CONTACT, success = false)
        return launchResponse(device.call(c), "${c.name} uchun qo'ng'iroq buyrug'i yuborildi.", "Qo'ng'iroq", IntentType.CALL_CONTACT)
    }

    private fun messageMatchesApp(item: com.jarvis.integrations.NotificationItem, app: String): Boolean = when (app) {
        "telegram" -> item.packageName in setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.thunderdog.challegram")
        "instagram" -> item.packageName == "com.instagram.android"
        else -> true
    }

    private fun readNotifications(intent: ResolvedIntent): JarvisResponse {
        if (!device.notificationsAvailable()) return JarvisResponse("Bildirishnomalarni o'qish ruxsatini Sozlamalarda yoqing.", intent.type, success = false)
        val limit = intent.slot("count")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val items = device.recentNotifications(Int.MAX_VALUE).filter { messageMatchesApp(it, intent.slot("app").orEmpty()) }.take(limit)
        context.rememberNotifications(items)
        val label = intent.slot("app")?.replaceFirstChar { it.uppercase() } ?: "Ilovalar"
        return JarvisResponse("$label faol bildirishnomalari. " + com.jarvis.integrations.Notifications.describe(items), intent.type,
            card = if (items.isEmpty()) null else ResponseCard("Faol bildirishnomalar", items.mapIndexed { i, n -> "${i + 1}. ${n.appName}: ${n.title} ${n.text}".trim() }))
    }

    private fun readCallHistory(intent: ResolvedIntent): JarvisResponse {
        if (!device.callHistoryAvailable()) return JarvisResponse("Sozlamalar → Ruxsatlar → Qo'ng'iroqlar tarixi ruxsatini bering.", intent.type, success = false)
        val limit = intent.slot("count")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val calls = device.recentCalls(limit)
        context.rememberCalls(calls)
        if (calls.isEmpty()) return JarvisResponse("Qo'ng'iroqlar tarixi bo'sh.", intent.type)
        val format = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")
        val lines = calls.mapIndexed { i, c -> "${i + 1}. ${c.name}, ${c.kind}, ${java.time.Instant.ofEpochMilli(c.timeMillis).atZone(zone()).format(format)}" }
        return JarvisResponse("Oxirgi ${calls.size} ta qo'ng'iroq: " + lines.joinToString(". "), intent.type, card = ResponseCard("Oxirgi qo'ng'iroqlar", lines))
    }

    private fun callFromHistory(intent: ResolvedIntent): JarvisResponse {
        if (!device.callHistoryAvailable()) return JarvisResponse("Qo'ng'iroqlar tarixi ruxsatini yoqing.", intent.type, success = false)
        val fresh = intent.slot("count") != null || intent.slot(ResolvedIntent.QUERY) == "latest"
        val calls = if (fresh) device.recentCalls(intent.slot("count")?.toIntOrNull()?.coerceIn(1, 50) ?: 10)
            else context.lastCalls
        if (calls.isEmpty()) return JarvisResponse("Avval oxirgi qo'ng'iroqlarni ko'rsatishni so'rang yoki tarix bo'sh.", intent.type, success = false)
        context.rememberCalls(calls)
        val index = intent.slot("index")?.toIntOrNull()?.minus(1) ?: 0
        val call = calls.getOrNull(index) ?: return JarvisResponse("Ro'yxatda bunday raqamli qo'ng'iroq yo'q.", intent.type, success = false)
        if (!call.callable || com.jarvis.integrations.PhoneCommunications.normalizeNumber(call.number) == null) return JarvisResponse("Bu yozuvning raqami yopiq yoki noto'g'ri; qo'ng'iroq qilib bo'lmaydi.", intent.type, success = false)
        context.pendingConfirmation = intent.copy(slots = intent.slots + mapOf("phone" to call.number, ResolvedIntent.CONTACT to call.name))
        return JarvisResponse("${call.name}, ${call.number} raqamiga qo'ng'iroq qilaymi?", intent.type, expectsReply = true)
    }

    private fun composeSms(intent: ResolvedIntent): JarvisResponse {
        var number = intent.slot("phone")
        if (number == null && intent.slot("index") != null) {
            if (!device.callHistoryAvailable()) return JarvisResponse("Qo'ng'iroqlar tarixi ruxsatini yoqing.", intent.type, success = false)
            val calls = if (intent.slot("count") != null) device.recentCalls(intent.slot("count")!!.toInt().coerceIn(1, 50)) else context.lastCalls
            val row = calls.getOrNull(intent.slot("index")!!.toInt() - 1)
                ?: return JarvisResponse("Qo'ng'iroqlar ro'yxatidagi raqam topilmadi.", intent.type, success = false)
            if (!row.callable) return JarvisResponse("Tanlangan raqam yopiq.", intent.type, success = false)
            number = row.number
        }
        if (number == null) {
            val to = intent.slot(ResolvedIntent.TO) ?: return askFor(intent, ResolvedIntent.TO, "SMS kimga? Kontakt ismini yoki telefon raqamini ayting.")
            number = com.jarvis.integrations.PhoneCommunications.normalizeNumber(to)
            if (number == null) {
                if (!device.hasContactsPermission()) return JarvisResponse("Kontaktlar ruxsatini yoqing yoki raqamni ayting.", intent.type, success = false)
                val contacts = device.findContacts(to)
                if (contacts.size != 1) {
                    context.rememberContacts(contacts)
                    return askFor(intent, "phone", if (contacts.isEmpty()) "Telefon raqamini ayting." else
                        "Qaysi kontakt? " + contacts.mapIndexed { i, c -> "${i + 1}. ${c.name}: ${c.phone}" }.joinToString(". "))
                }
                number = contacts.single().phone
            }
        }
        val body = intent.slot(ResolvedIntent.BODY) ?: return askFor(intent.copy(slots = intent.slots + ("phone" to number)), ResolvedIntent.BODY, "SMS matnini ayting.")
        return device.draftSms(number, body).let { JarvisResponse(it.text, intent.type, success = it.success) }
    }

    private fun addContact(intent: ResolvedIntent): JarvisResponse {
        val name = intent.slot(ResolvedIntent.CONTACT) ?: return askFor(intent, ResolvedIntent.CONTACT, "Yangi kontaktning ismi qanday?")
        val number = intent.slot("phone") ?: return askFor(intent, "phone", "Telefon raqamini ayting.")
        return device.addContact(name, number).let { JarvisResponse(it.text, intent.type, success = it.success) }
    }

    private fun composeSocialMessage(intent: ResolvedIntent): JarvisResponse {
        val app = intent.slot("app").orEmpty()
        val body = intent.slot(ResolvedIntent.BODY) ?: return askFor(intent, ResolvedIntent.BODY, "Xabar matnini ayting.")
        val index = intent.slot("index")?.toIntOrNull()?.minus(1)
        val snapshot = context.lastNotifications.filter { messageMatchesApp(it, app) }
        val to = intent.slot(ResolvedIntent.TO)
        val candidates = when {
            index != null -> listOfNotNull(snapshot.getOrNull(index))
            to != null && device.notificationsAvailable() -> device.recentNotifications(Int.MAX_VALUE).filter {
                messageMatchesApp(it, app) && it.title.contains(to, ignoreCase = true)
            }
            else -> snapshot
        }
        if (candidates.size == 1) {
            val item = candidates.single()
            if (!item.canReply) return JarvisResponse("Bu bildirishnomada tezkor javob yo'q. Ilovadagi chatni ochib, 'matn yoz: ...' buyrug'idan foydalaning.", intent.type, success = false)
            context.rememberNotifications(listOf(item))
            context.pendingConfirmation = intent.copy(slots = intent.slots + ("notificationKey" to item.key))
            return JarvisResponse("${item.appName}: ${item.title} ga \"$body\" deb javob yuboraymi?", intent.type, expectsReply = true)
        }
        if (index != null) return JarvisResponse("Xabarlar ro'yxatida bunday tartib raqami yo'q. Ro'yxatni qayta so'rang.", intent.type, success = false)
        if (candidates.size > 1) {
            context.rememberNotifications(candidates)
            return askFor(intent, "index", "Qaysi xabarga? " + candidates.mapIndexed { i, n -> "${i + 1}. ${n.title}" }.joinToString(". "))
        }
        if (app.isBlank()) return askFor(intent, "app", "Telegram yoki Instagram'da yozaymi?")
        return device.socialDraft(app, body).let { JarvisResponse(it.text, intent.type, success = it.success) }
    }

    private fun clearNotifications(intent: ResolvedIntent): JarvisResponse {
        if (!device.notificationsAvailable()) {
            return JarvisResponse("Bildirishnomalarni boshqarish uchun ruxsat bering.", intent.type, success = false)
        }
        val n = device.recentNotifications().size
        if (n == 0) return JarvisResponse("Tozalanadigan bildirishnoma yo'q.", intent.type)
        context.pendingConfirmation = intent
        return JarvisResponse("$n ta bildirishnomani tozalaymi?", intent.type, expectsReply = true)
    }

    // ---------------- Calendar & mail ----------------

    private suspend fun calendarRead(intent: ResolvedIntent): JarvisResponse {
        if (!calendar.available()) {
            return JarvisResponse("Taqvim ulanmagan. Sozlamalarda Google hisobini ulang yoki taqvim ruxsatini bering.", intent.type, success = false)
        }
        val date = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: clock().toLocalDate()
        val events = calendar.eventsOn(date)
        val label = SmartPlanner.dayLabel(date, clock().toLocalDate())
        if (events.isEmpty()) return JarvisResponse("$label taqvimda tadbir yo'q.", intent.type)
        val sb = StringBuilder("$label taqvimda ${events.size} ta tadbir: ")
        events.take(5).forEach { sb.append("${it.start.toLocalTime().format(HHMM)} da ${it.title}. ") }
        return JarvisResponse(sb.toString().trim(), intent.type,
            card = ResponseCard("Taqvim — $label", events.map { "${it.start.toLocalTime().format(HHMM)}–${it.end.toLocalTime().format(HHMM)} ${it.title}" }))
    }

    private suspend fun calendarDelete(intent: ResolvedIntent): JarvisResponse {
        if (!calendar.available()) return JarvisResponse("Taqvim ulanmagan.", intent.type, success = false)
        val title = intent.slot(ResolvedIntent.TITLE) ?: return askFor(intent, ResolvedIntent.TITLE, "Qaysi tadbirni o'chiray?")
        val date = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val event = calendar.findByTitle(title, date)
            ?: return JarvisResponse("\"$title\" tadbiri topilmadi.", intent.type, success = false)
        calendar.delete(event)
        return JarvisResponse("\"${event.title}\" taqvimdan o'chirildi.", intent.type)
    }

    private suspend fun emailRead(intent: ResolvedIntent): JarvisResponse {
        if (!mail.isConnected()) return JarvisResponse("Gmail ulanmagan. Sozlamalarda Google hisobini ulang.", intent.type, success = false)
        val mails = mail.unread()
        return JarvisResponse(GmailManager.describe(mails), intent.type,
            card = if (mails.isEmpty()) null else ResponseCard("O'qilmagan xatlar", mails.map { "${it.from}: ${it.subject}" }))
    }

    private suspend fun composeEmail(intent: ResolvedIntent): JarvisResponse {
        if (!mail.isConnected()) return JarvisResponse("Gmail ulanmagan. Sozlamalarda Google hisobini ulang.", intent.type, success = false)
        val rawTo = intent.slot(ResolvedIntent.TO) ?: return askFor(intent, ResolvedIntent.TO, "Kimga yozay? Email manzil yoki kontakt ismini ayting.")
        val to = if ('@' in rawTo) rawTo else device.findEmail(rawTo)
            ?: return JarvisResponse("\"$rawTo\" uchun email manzil topilmadi.", intent.type, success = false)
        val body = intent.slot(ResolvedIntent.BODY) ?: return askFor(
            intent.copy(slots = intent.slots + (ResolvedIntent.TO to to)), ResolvedIntent.BODY, "Xat matnini ayting."
        )
        val subject = intent.slot(ResolvedIntent.SUBJECT) ?: body.split(' ').take(6).joinToString(" ")
        val complete = intent.copy(slots = intent.slots + mapOf(ResolvedIntent.TO to to, ResolvedIntent.SUBJECT to subject, ResolvedIntent.BODY to body))
        return if (intent.type == IntentType.EMAIL_DRAFT) {
            mail.draft(to, subject, body)
            JarvisResponse("$to uchun qoralama saqlandi.", intent.type)
        } else {
            context.pendingConfirmation = complete
            JarvisResponse("$to manziliga \"$body\" matnli xat yuborilsinmi?", intent.type, expectsReply = true)
        }
    }

    // ---------------- Small talk ----------------

    private suspend fun greeting(intent: ResolvedIntent): JarvisResponse {
        val name = settings.state.value.userName
        val hour = clock().hour
        val salute = when (hour) {
            in 5..10 -> "Xayrli tong"
            in 11..17 -> "Assalomu alaykum"
            else -> "Xayrli kech"
        }
        val summary = planner.summary(clock().toLocalDate())
        return JarvisResponse("$salute${if (name.isNotBlank()) ", $name" else ""}! $summary", intent.type)
    }

    private fun timeAnswer(): String {
        val now = clock()
        return "Soat ${now.toLocalTime().format(HHMM)}. Bugun ${now.dayOfMonth}-${MONTHS[now.monthValue - 1]}, ${WEEKDAYS[now.dayOfWeek.value - 1]}."
    }

    private fun pick(vararg options: String): String = options[random.nextInt(options.size)]

    private fun askFor(intent: ResolvedIntent, slot: String, question: String): JarvisResponse {
        context.pendingIntent = intent
        context.awaitingSlot = slot
        return JarvisResponse(question, intent.type, expectsReply = true)
    }

    private fun dayPhrase(date: LocalDate): String = when (date) {
        clock().toLocalDate() -> "bugun"
        clock().toLocalDate().plusDays(1) -> "ertaga"
        clock().toLocalDate().plusDays(2) -> "indinga"
        else -> "${date.dayOfMonth}-${MONTHS[date.monthValue - 1]}"
    }

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        val MONTHS = listOf("yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr")
        val WEEKDAYS = listOf("dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba", "yakshanba")

        private const val HELP_TEXT = "Men vazifa qo'shaman, kuningizni rejalashtiraman, eslataman, eslab qolaman, " +
            "kamerani ochaman, fayl topaman, qo'ng'iroq qilaman, taqvim va Gmail bilan ishlayman."
        val HELP_EXAMPLES = listOf(
            "Oxirgi 10 ta qo'ng'iroqni ayt",
            "7-siga telefon qil",
            "Ali ga SMS yoz: Salom!",
            "Telegramdagi oxirgi 10 ta xabarni ayt",
            "7-siga javob yoz: Rahmat!",
            "Bluetoothni yoq",
            "Jarvis ertaga soat 9 da uchrashuv qo'sh",
            "Jarvis bugungi rejani tuz",
            "Jarvis tugallanmagan ishlarimni ko'rsat",
            "Jarvis 30 daqiqadan keyin suv ichishni eslat",
            "Men har kuni ertalab sport qilaman",
            "Jarvis kamera och",
            "Jarvis PDF faylimni top",
            "Jarvis doktor bilan bog'lan",
            "Jarvis bildirishnomalarni o'qi",
            "Jarvis ertangi taqvimni ko'rsat",
            "Jarvis yangi xatlarni o'qi",
            "Jarvis hisobotni jumagacha tayyorla",
            "Jarvis ish odatlarim qanday?",
            "Jarvis bugungi jami eslatmalarni ayt",
            "Jarvis keyingi eslatmam qaysi? / undan keyingisi qaysi?",
            "Shu eslatmaning nomini dars deb o'zgartir",
            "Shuni kunini ertaga o'zgartir",
            "Jarvis shaxmat puzzle yech / bot bilan shaxmat o'yna",
            "Jarvis shaxmat partiyasini tahlil qil",
            "Jarvis boshla / Jarvis tugat"
        )
    }
}
