package com.example.jarvis

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Deterministic commands: the same grammar is used for speech and text. */
data class JarvisCommand(val action: String, val target: String = "", val value: String = "", val date: String = "", val time: String = "", val description: String = "", val category: String = "PERSONAL", val priority: String = "MEDIUM", val duration: Int = 30)

object JarvisCommands {
    fun normalize(text: String) = text.lowercase(Locale.ROOT)
        .replace(Regex("[‘’ʻʼ`]") , "'").replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')

    fun parse(raw: String, today: String): JarvisCommand {
        val text = normalize(raw).replace(" ajrat ", " / ").replace(Regex("^jarvis(?:[ ,:]+|$)"), "").trim()
        fun c(action: String, target: String = "", value: String = "") = JarvisCommand(action, target, value)
        when (text) {
            "", "boshla" -> return c("WAKE")
            "tugat" -> return c("SLEEP")
            "tasdiqla", "ha tasdiqla" -> return c("CONFIRM")
            "bekor", "bekor qil" -> return c("CANCEL")
            "yordam", "buyruqlar", "nima qila olasan" -> return c("HELP")
            "statistika", "statistikani ko'rsat" -> return c("STATS")
            "odatlar", "odatlarni ko'rsat" -> return c("HABITS")
            "jadvalni nusxala" -> return c("COPY")
            "eksport", "jadvalni ulash", "jadvalni eksport qil" -> return c("EXPORT")
            "yangi vazifa" -> return c("NEW_TASK")
            "filtrni tozala", "filtrlarni tozala" -> return c("CLEAR_FILTERS")
            "tungi rejim", "qorong'i rejim" -> return c("THEME", value = "dark")
            "yorug' rejim", "kunduzgi rejim" -> return c("THEME", value = "light")
            "tizim mavzusi" -> return c("THEME", value = "system")
            "fokus pauza", "diqqat pauza" -> return c("FOCUS_PAUSE")
            "fokus davom et", "diqqat davom et" -> return c("FOCUS_RESUME")
            "fokus tugat", "diqqat tugat" -> return c("FOCUS_STOP")
            "fokus", "diqqat" -> return c("FOCUS_SHOW")
            "test eslatma" -> return c("TEST_NOTIFICATION")
            "test budilnik" -> return c("TEST_ALARM")
            "bajarilganlarni ko'rsat" -> return c("FILTER_STATUS", value = "COMPLETED")
            "bajarilmaganlarni ko'rsat" -> return c("FILTER_STATUS", value = "PENDING")
        }
        Regex("^(?:fokus|diqqat) boshla(?: (\\d{1,3})(?: daqiqa)?)?$").matchEntire(text)?.let {
            return c("FOCUS_START", value = it.groupValues[1].ifBlank { "25" })
        }
        val date = when {
            Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").containsMatchIn(text) -> Regex("\\d{4}-\\d{2}-\\d{2}").find(text)!!.value
            text.contains("ertaga") || text.contains("ertangi") -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
                val cal = Calendar.getInstance().apply { time = sdf.parse(today)!!; add(Calendar.DAY_OF_MONTH, 1) }
                sdf.format(cal.time)
            }
            else -> today
        }
        if (Regex("^(bugungi|ertangi|bugun|ertaga)? ?(reja|jadval|vazifalar|vazifalarim|ro'yxat)(ni|imni)?( o'qi| ko'rsat)?$").matches(text))
            return JarvisCommand("SCHEDULE", date = date)
        val prefixes = linkedMapOf(
            "odat qo'sh " to "HABIT_ADD", "odat bajarildi " to "HABIT_DONE", "odat bekor " to "HABIT_UNDO", "odat o'chir " to "HABIT_DELETE",
            "toifa filtri " to "FILTER_CATEGORY", "muhimlik filtri " to "FILTER_PRIORITY", "qidir " to "SEARCH", "tahrirla " to "EDIT_TASK", "bajarildi " to "COMPLETE", "bajarilmadi " to "UNCOMPLETE", "o'chir " to "DELETE",
            "nomini o'zgartir " to "RENAME", "tavsif " to "DESCRIPTION", "davomiylik " to "DURATION", "eslatma " to "REMINDER", "muhimlik " to "PRIORITY", "toifa " to "CATEGORY",
            "kichik vazifa qo'sh " to "SUBTASK_ADD", "kichik vazifa bajarildi " to "SUBTASK_DONE", "qayd " to "NOTE", "takrorla " to "REPEAT"
        )
        for ((prefix, action) in prefixes) if (text.startsWith(prefix)) {
            val parts = text.removePrefix(prefix).split(" / ", limit = 2)
            return c(action, parts[0].trim(), parts.getOrElse(1) { "" }.trim())
        }
        Regex("^(.+?) (bajarildi|bajarilmadi|o'chir)$").matchEntire(text)?.let {
            return c(when(it.groupValues[2]) { "bajarildi" -> "COMPLETE"; "bajarilmadi" -> "UNCOMPLETE"; else -> "DELETE" }, it.groupValues[1])
        }
        val tm = Regex("(?:soat\\s*)?(\\d{1,2}):([0-9]{2})").find(text)
        val hour = Regex("soat\\s*(\\d{1,2})(?:\\s*(yarim))?").find(text)
        val time = tm?.let { "${it.groupValues[1].padStart(2, '0')}:${it.groupValues[2]}" }
            ?: hour?.let { "${it.groupValues[1].padStart(2, '0')}:${if(it.groupValues[2].isNotBlank()) "30" else "00"}" } ?: "09:00"
        if (text.startsWith("ko'chir ")) {
            val parts = text.removePrefix("ko'chir ").split(" / ", limit = 2)
            if (parts.size == 2 && (tm != null || hour != null)) return JarvisCommand("MOVE", parts[0], date = date, time = time)
            return c("UNKNOWN")
        }
        if (Regex("\\b(qo'sh|yarat|rejalashtir|eslat)$").containsMatchIn(text) || text.startsWith("vazifa qo'sh ")) {
            val title = text.replace(Regex("^vazifa qo'sh\\s+"), "")
                .replace(Regex("\\b(qo'sh|yarat|rejalashtir|eslat)$"), "")
                .replace(Regex("\\b(bugun|ertaga)\\b|\\d{4}-\\d{2}-\\d{2}"), "")
                .replace(Regex("(?:soat\\s*)?\\d{1,2}:\\d{2}(?:\\s*da)?|soat\\s*\\d{1,2}(?:\\s*yarim)?(?:da)?"), "")
                .replace(Regex("\\s+"), " ").trim()
            return JarvisCommand("ADD", title, date = date, time = time)
        }
        return c("UNKNOWN", raw)
    }

    val examples = listOf("Jarvis boshla", "Ertaga 09:00 da majlis qo'sh", "Bugungi rejani o'qi", "Bajarildi majlis", "Qidir majlis", "Fokus boshla 25", "Odat qo'sh kitob o'qish", "Statistika", "Jarvis tugat")
    val help = "Jarvis boshla — faollashtirish. Jarvis tugat — kutish. Vazifa qo'sh kitob o'qish; bajarildi kitob; o'chir kitob (tasdiq so'raladi); qidir kitob; bugungi rejani o'qi; tahrirla kitob; ko'chir kitob / ertaga 10:00; nomini o'zgartir kitob / mutolaa; tavsif kitob / 20 sahifa; davomiylik kitob / 45; muhimlik kitob / yuqori; toifa kitob / o'qish; eslatma kitob / 10; takrorla kitob / DAILY_7; kichik vazifa qo'sh kitob / 1-bob; kichik vazifa bajarildi kitob / 1-bob; qayd kitob / izoh; odat qo'sh suv; odat bajarildi suv; odat bekor suv; odat o'chir suv; fokus boshla 25; fokus pauza; fokus davom et; fokus tugat; statistika; eksport; jadvalni nusxala; toifa filtri ish; muhimlik filtri yuqori; tungi rejim; yorug' rejim; filtrni tozala; test eslatma. Murakkab buyruqlarda nom va qiymat orasiga / yozing yoki ajrat deng."
}
