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
            JarvisResponse("Kechirasiz, bajarishda xatolik yuz berdi: ${e.message ?: e.javaClass.simpleName}", intent.type, success = false)
        }
    }

    private suspend fun dispatch(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse = when (intent.type) {
        IntentType.WAKE -> JarvisResponse(pick("Labbay, eshitaman.", "Ha, xizmatingizdaman.", "Eshitaman, gapiring."), intent.type, expectsReply = true)
        IntentType.START_SESSION -> {
            context.sessionActive = true
            JarvisResponse("Suhbat rejimi yoqildi. Men sizni tinglayapman.", intent.type, startSession = true, expectsReply = true)
        }
        IntentType.STOP_SESSION -> {
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
    suspend fun executeConfirmed(intent: ResolvedIntent): JarvisResponse = when (intent.type) {
        IntentType.EMAIL_SEND -> {
            val to = intent.slot(ResolvedIntent.TO)!!
            mail.send(to, intent.slot(ResolvedIntent.SUBJECT) ?: "", intent.slot(ResolvedIntent.BODY) ?: "")
            JarvisResponse("Xat $to manziliga yuborildi.", intent.type)
        }
        IntentType.CLEAR_NOTIFICATIONS -> {
            device.clearNotifications()
            JarvisResponse("Barcha bildirishnomalar tozalandi.", intent.type)
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
            ?: return JarvisResponse("Qaysi vazifa bajarilganini topa olmadim. Nomini aniqroq ayting.", intent.type, success = false)
        tasks.updateTask(task.copy(isCompleted = true))
        val remaining = tasks.tasksFor(clock().toLocalDate().toString()).count { !it.isCompleted && it.id != task.id }
        return JarvisResponse("Barakalla! \"${task.title}\" bajarildi. Bugun yana $remaining ta vazifa qoldi.", intent.type)
    }

    private suspend fun deleteTask(intent: ResolvedIntent, parsed: ParsedCommand): JarvisResponse {
        val task = resolveTask(intent, parsed)
            ?: return JarvisResponse("O'chiriladigan vazifani topa olmadim.", intent.type, success = false)
        tasks.deleteTask(task)
        return JarvisResponse("\"${task.title}\" o'chirildi.", intent.type)
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
        val time = intent.slot(ResolvedIntent.TIME)?.let { TaskPlanner.parseTime(it) }
            ?: return askFor(intent, ResolvedIntent.TIME, "Qachon eslatay? Masalan: 30 daqiqadan keyin yoki soat 18 da.")
        val now = clock()
        var date = intent.slot(ResolvedIntent.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: now.toLocalDate()
        if (LocalDateTime.of(date, time).isBefore(now) && intent.slot(ResolvedIntent.DATE) == null) date = date.plusDays(1)
        val title = intent.slot(ResolvedIntent.TITLE) ?: "Eslatma"
        val daily = Regex("""\bhar kuni\b""").containsMatchIn(parsed.body)
        val at = LocalDateTime.of(date, time)
        reminders.addReminder(
            Reminder(
                title = title,
                message = "Jarvis eslatmasi",
                triggerAtMillis = at.atZone(zone()).toInstant().toEpochMilli(),
                repeatIntervalMinutes = if (daily) 1440 else 0
            )
        )
        val whenText = if (daily) "har kuni soat ${time.format(HHMM)} da" else "${dayPhrase(date)} soat ${time.format(HHMM)} da"
        return JarvisResponse("Xo'p, $whenText eslataman: $title.", intent.type)
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

    private fun launchReply(result: ActivityLauncher.Result, ok: String, what: String) = when (result) {
        ActivityLauncher.Result.STARTED -> ok
        ActivityLauncher.Result.NOTIFIED -> "$what tayyor — bildirishnomani bosing."
        ActivityLauncher.Result.NO_APP -> "Kechirasiz, bu amal uchun ilova topilmadi."
    }

    private fun openCamera(intent: ResolvedIntent): JarvisResponse {
        val video = intent.slot(ResolvedIntent.VIDEO) == "true"
        val r = device.openCamera(video)
        return JarvisResponse(launchReply(r, if (video) "Video kamera ochildi." else "Kamera ochildi.", "Kamera"), intent.type,
            success = r != ActivityLauncher.Result.NO_APP)
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
                JarvisResponse(launchReply(r, "\"${hits[0].name}\" ochildi.", hits[0].name), intent.type)
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
        return JarvisResponse(launchReply(device.openFile(hit), "\"${hit.name}\" ochildi.", hit.name), IntentType.FIND_FILE)
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
            1 -> JarvisResponse(launchReply(device.call(found[0]), "${found[0].name} raqamiga qo'ng'iroq qilinmoqda.", "Qo'ng'iroq"), intent.type)
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
        return JarvisResponse(launchReply(device.call(c), "${c.name} raqamiga qo'ng'iroq qilinmoqda.", "Qo'ng'iroq"), IntentType.CALL_CONTACT)
    }

    private fun readNotifications(intent: ResolvedIntent): JarvisResponse {
        if (!device.notificationsAvailable()) {
            return JarvisResponse("Bildirishnomalarni o'qish uchun Sozlamalarda \"Bildirishnomalarni o'qish\" ruxsatini yoqing.", intent.type, success = false)
        }
        val items = device.recentNotifications()
        return JarvisResponse(com.jarvis.integrations.Notifications.describe(items), intent.type,
            card = if (items.isEmpty()) null else ResponseCard("Bildirishnomalar", items.map { "${it.appName}: ${it.title} ${it.text}".trim() }))
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
            "Jarvis boshla / Jarvis tugat"
        )
    }
}
