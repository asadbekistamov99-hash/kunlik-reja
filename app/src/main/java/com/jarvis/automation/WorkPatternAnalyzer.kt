package com.jarvis.automation

import com.example.data.ConversationDao
import com.example.data.MemoryType
import com.example.data.Task
import com.example.data.TaskCategory
import com.example.data.TaskDao
import com.jarvis.memory.UserMemory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** What Jarvis has learned about how the user actually works. */
data class WorkPatterns(
    /** Completed tasks the analysis is based on. */
    val sampleSize: Int,
    /** Hours of day (0-23) when most tasks get finished, best first. */
    val productiveHours: List<Int>,
    val bestWeekday: DayOfWeek?,
    /** Share of past (due) tasks that were completed, 0..1. */
    val completionRate: Double,
    /** Share of completed tasks finished no later than one hour after their scheduled end, 0..1. */
    val onTimeRate: Double?,
    val strongestCategory: String?,
    val weakestCategory: String?,
    val avgCompletedPerActiveDay: Double,
    /** Hour the user talks to Jarvis most. */
    val mostActiveAssistantHour: Int?
) {
    val hasEnoughData: Boolean get() = sampleSize >= MIN_SAMPLES

    fun describe(): String {
        if (!hasEnoughData) {
            return "Ish odatlaringizni aniqlash uchun hali ma'lumot yetarli emas: kamida $MIN_SAMPLES ta bajarilgan vazifa kerak, hozir $sampleSize ta."
        }
        val sb = StringBuilder()
        if (productiveHours.isNotEmpty()) {
            sb.append("Siz eng samarali soat ${productiveHours.take(2).joinToString(" va ") { "%02d:00".format(it) }} atrofida ishlaysiz. ")
        }
        bestWeekday?.let { sb.append("Eng unumli kuningiz — ${WEEKDAYS_UZ[it.value - 1]}. ") }
        sb.append("Vazifalarning ${(completionRate * 100).roundToInt()}% bajariladi")
        onTimeRate?.let { sb.append(", ${(it * 100).roundToInt()}% o'z vaqtida") }
        sb.append(". ")
        strongestCategory?.let { sb.append("Eng yaxshi natija: ${categoryUz(it)}. ") }
        weakestCategory?.takeIf { it != strongestCategory }?.let { sb.append("Ko'proq e'tibor kerak: ${categoryUz(it)}. ") }
        sb.append("Faol kunlarda o'rtacha ${"%.1f".format(avgCompletedPerActiveDay)} ta vazifa bajarasiz.")
        return sb.toString().trim()
    }

    /** Memory entries (key to content) persisted so the profile and the online agent can use them. */
    fun toMemoryFacts(): List<Pair<String, String>> {
        if (!hasEnoughData) return emptyList()
        val facts = mutableListOf<Pair<String, String>>()
        if (productiveHours.isNotEmpty()) facts += "pattern:productive_hours" to
            "Eng samarali soatlar: ${productiveHours.take(3).joinToString(", ") { "%02d:00".format(it) }}"
        bestWeekday?.let { facts += "pattern:best_weekday" to "Eng unumli kun: ${WEEKDAYS_UZ[it.value - 1]}" }
        facts += "pattern:completion" to "Vazifalarni bajarish darajasi: ${(completionRate * 100).roundToInt()}%"
        onTimeRate?.let { facts += "pattern:on_time" to "O'z vaqtida bajarish: ${(it * 100).roundToInt()}%" }
        weakestCategory?.let { facts += "pattern:weak_category" to "Ko'pincha kechiktiriladi: ${categoryUz(it)}" }
        return facts
    }

    companion object {
        const val MIN_SAMPLES = 5
        val WEEKDAYS_UZ = listOf("dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba", "yakshanba")
        fun categoryUz(name: String) = runCatching { TaskCategory.valueOf(name).labelUz }.getOrDefault(name)
    }
}

object WorkPatternAnalyzer {

    fun analyze(tasks: List<Task>, assistantTimestamps: List<Long>, today: LocalDate, zone: ZoneId): WorkPatterns {
        val completed = tasks.filter { it.isCompleted }
        fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone)
        fun finishedAt(t: Task) = if (t.completedAt > 0) t.completedAt else t.timestampMillis

        val byHour = completed.groupingBy { at(finishedAt(it)).hour }.eachCount()
        val productiveHours = byHour.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(3)

        val byWeekday = completed.groupingBy { at(finishedAt(it)).dayOfWeek }.eachCount()
        val bestWeekday = byWeekday.maxByOrNull { it.value }?.takeIf { e -> byWeekday.values.count { it == e.value } == 1 }?.key

        val due = tasks.filter { runCatching { !LocalDate.parse(it.dateString).isAfter(today) }.getOrDefault(false) }
        val completionRate = if (due.isEmpty()) 0.0 else due.count { it.isCompleted }.toDouble() / due.size

        val measurable = completed.filter { it.completedAt > 0 }
        val onTimeRate = if (measurable.size < 3) null else measurable.count {
            it.completedAt <= it.timestampMillis + (it.durationMinutes + 60) * 60_000L
        }.toDouble() / measurable.size

        val categoryRates = due.groupBy { it.category }
            .filterValues { it.size >= 3 }
            .mapValues { (_, list) -> list.count { it.isCompleted }.toDouble() / list.size }
        val strongest = categoryRates.maxByOrNull { it.value }?.key
        val weakest = categoryRates.minByOrNull { it.value }?.key?.takeIf { categoryRates.size > 1 }

        val activeDays = completed.map { at(finishedAt(it)).toLocalDate() }.distinct().size
        val avgPerDay = if (activeDays == 0) 0.0 else completed.size.toDouble() / activeDays

        val activeHour = assistantTimestamps.groupingBy { at(it).hour }.eachCount().maxByOrNull { it.value }?.key

        return WorkPatterns(completed.size, productiveHours, bestWeekday, completionRate, onTimeRate,
            strongest, weakest, avgPerDay, activeHour)
    }
}

/** Runs the analysis on real data and keeps the learned patterns in long-term memory. */
class WorkPatternService(
    private val taskDao: TaskDao,
    private val conversationDao: ConversationDao,
    private val memory: UserMemory,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    @Volatile var latest: WorkPatterns? = null
        private set

    suspend fun refresh(today: LocalDate = LocalDate.now(zone())): WorkPatterns {
        val userTurns = conversationDao.getRecent(500).filter { it.role == "USER" }.map { it.timestamp }
        val patterns = WorkPatternAnalyzer.analyze(taskDao.getAll(), userTurns, today, zone())
        patterns.toMemoryFacts().forEach { (key, content) -> memory.remember(MemoryType.PATTERN, content, key = key) }
        latest = patterns
        return patterns
    }
}
