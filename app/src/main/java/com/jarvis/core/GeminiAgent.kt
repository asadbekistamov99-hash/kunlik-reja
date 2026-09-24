package com.jarvis.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Online reasoning via Gemini function calling. The model sees Jarvis' tools as function
 * declarations and either calls one (mapped back to a [ResolvedIntent]) or answers in text.
 */
class GeminiAgent(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val online: () -> Boolean,
    private val endpoint: String = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent",
    private val timeoutMs: Long = 8_000
) : AgentPort {

    override fun isAvailable(): Boolean = !apiKey().isNullOrBlank() && online()

    override suspend fun decide(text: String, context: AgentContext): ResolvedIntent? {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .header("x-goog-api-key", key)
                        .post(buildRequest(text, context).toString().toRequestBody(JSON))
                        .build()
                    http.newCall(request).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "Gemini HTTP ${resp.code}")
                            null
                        } else parseResponse(body)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini call failed: ${e.message}")
                    null
                }
            }
        }
    }

    companion object {
        private const val TAG = "GeminiAgent"
        const val MODEL = "gemini-2.5-flash"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun buildRequest(text: String, ctx: AgentContext): JSONObject {
            val system = """
                Siz "Jarvis" — o'zbek tilida gapiradigan shaxsiy AI yordamchisiz. Qisqa, aniq va samimiy javob bering.
                Hozir: ${ctx.now}. Sana formati YYYY-MM-DD, vaqt HH:mm (24 soat).
                Foydalanuvchi haqida: ${ctx.profile.ifBlank { "ma'lumot yo'q" }}
                Bugungi vazifalar: ${ctx.todayTasks.ifBlank { "yo'q" }}
                Agar so'rov quyidagi funksiyalardan biriga mos kelsa, albatta funksiyani chaqiring.
                Aks holda o'zbek tilida 1-3 gapdan iborat javob bering.
            """.trimIndent()
            val contents = JSONArray()
            ctx.history.takeLast(8).forEach { (role, msg) ->
                contents.put(JSONObject().put("role", if (role == "USER") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", msg))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations())))
                .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
                .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 512))
        }

        fun parseResponse(body: String): ResolvedIntent? {
            val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val texts = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                part.optJSONObject("functionCall")?.let { call ->
                    return fromFunctionCall(call.getString("name"), call.optJSONObject("args") ?: JSONObject())
                }
                part.optString("text").takeIf { it.isNotBlank() }?.let { texts.append(it) }
            }
            val answer = texts.toString().trim()
            return if (answer.isBlank()) null
            else ResolvedIntent(IntentType.UNKNOWN, 0.9f, mapOf(ResolvedIntent.ANSWER to answer), source = "gemini")
        }

        private data class Fn(val name: String, val intent: IntentType, val description: String, val params: List<Param>)
        private data class Param(val name: String, val slot: String, val type: String, val description: String, val required: Boolean = false)

        private val FUNCTIONS = listOf(
            Fn("add_task", IntentType.ADD_TASK, "Yangi vazifa yoki uchrashuv qo'shadi", listOf(
                Param("title", ResolvedIntent.TITLE, "STRING", "Vazifa nomi", true),
                Param("date", ResolvedIntent.DATE, "STRING", "YYYY-MM-DD"),
                Param("time", ResolvedIntent.TIME, "STRING", "HH:mm"),
                Param("deadline", ResolvedIntent.DEADLINE, "STRING", "Oxirgi muddat YYYY-MM-DD"),
                Param("duration_minutes", ResolvedIntent.DURATION, "INTEGER", "Davomiyligi"),
                Param("priority", ResolvedIntent.PRIORITY, "STRING", "HIGH, MEDIUM yoki LOW"),
                Param("category", ResolvedIntent.CATEGORY, "STRING", "WORK, STUDY, PERSONAL, HEALTH, HOME, OTHER"))),
            Fn("complete_task", IntentType.COMPLETE_TASK, "Vazifani bajarildi deb belgilaydi", listOf(Param("title", ResolvedIntent.TITLE, "STRING", "Vazifa nomi", true))),
            Fn("delete_task", IntentType.DELETE_TASK, "Vazifani o'chiradi", listOf(Param("title", ResolvedIntent.TITLE, "STRING", "Vazifa nomi", true))),
            Fn("reschedule_task", IntentType.RESCHEDULE_TASK, "Vazifa vaqtini o'zgartiradi", listOf(
                Param("title", ResolvedIntent.TITLE, "STRING", "Vazifa nomi", true),
                Param("date", ResolvedIntent.DATE, "STRING", "YYYY-MM-DD"), Param("time", ResolvedIntent.TIME, "STRING", "HH:mm"))),
            Fn("work_patterns", IntentType.WORK_PATTERNS, "Foydalanuvchining ish odatlari va samarali soatlari tahlili", emptyList()),
            Fn("list_pending_tasks", IntentType.LIST_PENDING, "Tugallanmagan ishlarni ko'rsatadi", emptyList()),
            Fn("plan_day", IntentType.PLAN_DAY, "Kun rejasini tuzadi va vazifalarni vaqtga joylaydi", listOf(Param("date", ResolvedIntent.DATE, "STRING", "YYYY-MM-DD"))),
            Fn("day_summary", IntentType.DAY_SUMMARY, "Kun vazifalari haqida qisqacha ma'lumot", listOf(Param("date", ResolvedIntent.DATE, "STRING", "YYYY-MM-DD"))),
            Fn("search_tasks", IntentType.SEARCH_TASKS, "Vazifalarni qidiradi", listOf(Param("query", ResolvedIntent.QUERY, "STRING", "Qidiruv", true))),
            Fn("add_reminder", IntentType.ADD_REMINDER, "Eslatma o'rnatadi", listOf(
                Param("title", ResolvedIntent.TITLE, "STRING", "Nima haqida", true),
                Param("date", ResolvedIntent.DATE, "STRING", "YYYY-MM-DD"), Param("time", ResolvedIntent.TIME, "STRING", "HH:mm", true))),
            Fn("add_habit", IntentType.ADD_HABIT, "Kuzatiladigan odat qo'shadi", listOf(Param("title", ResolvedIntent.TITLE, "STRING", "Odat", true))),
            Fn("remember", IntentType.REMEMBER, "Foydalanuvchi haqidagi ma'lumotni doimiy eslab qoladi", listOf(
                Param("content", ResolvedIntent.CONTENT, "STRING", "Eslab qolinadigan gap", true),
                Param("memory_type", ResolvedIntent.MEMORY_TYPE, "STRING", "PREFERENCE, HABIT, FACT, IMPORTANT yoki PROFILE"))),
            Fn("recall", IntentType.RECALL, "Xotiradan ma'lumot oladi", listOf(Param("query", ResolvedIntent.QUERY, "STRING", "Mavzu"))),
            Fn("open_camera", IntentType.OPEN_CAMERA, "Kamerani ochadi", listOf(Param("video", ResolvedIntent.VIDEO, "BOOLEAN", "Video rejimi"))),
            Fn("find_file", IntentType.FIND_FILE, "Telefondan fayl topadi va ochadi", listOf(
                Param("query", ResolvedIntent.QUERY, "STRING", "Fayl nomidagi so'zlar"),
                Param("extension", ResolvedIntent.EXTENSION, "STRING", "pdf, doc, xls, ppt yoki txt"))),
            Fn("call_contact", IntentType.CALL_CONTACT, "Kontaktga qo'ng'iroq qiladi", listOf(Param("name", ResolvedIntent.CONTACT, "STRING", "Kontakt ismi", true))),
            Fn("read_notifications", IntentType.READ_NOTIFICATIONS, "Bildirishnomalarni o'qiydi", emptyList()),
            Fn("clear_notifications", IntentType.CLEAR_NOTIFICATIONS, "Bildirishnomalarni tozalaydi", emptyList()),
            Fn("calendar_events", IntentType.CALENDAR_READ, "Google taqvim tadbirlarini o'qiydi", listOf(Param("date", ResolvedIntent.DATE, "STRING", "YYYY-MM-DD"))),
            Fn("delete_calendar_event", IntentType.CALENDAR_DELETE, "Taqvimdan tadbirni o'chiradi", listOf(
                Param("title", ResolvedIntent.TITLE, "STRING", "Tadbir nomi", true), Param("date", ResolvedIntent.DATE, "STRING", "YYYY-MM-DD"))),
            Fn("read_emails", IntentType.EMAIL_READ, "O'qilmagan Gmail xatlarini o'qiydi", emptyList()),
            Fn("draft_email", IntentType.EMAIL_DRAFT, "Gmail qoralamasini yaratadi", listOf(
                Param("to", ResolvedIntent.TO, "STRING", "Email yoki kontakt ismi", true),
                Param("subject", ResolvedIntent.SUBJECT, "STRING", "Mavzu"), Param("body", ResolvedIntent.BODY, "STRING", "Matn", true))),
            Fn("send_email", IntentType.EMAIL_SEND, "Gmail orqali xat yuboradi (foydalanuvchi tasdiqlaydi)", listOf(
                Param("to", ResolvedIntent.TO, "STRING", "Email yoki kontakt ismi", true),
                Param("subject", ResolvedIntent.SUBJECT, "STRING", "Mavzu"), Param("body", ResolvedIntent.BODY, "STRING", "Matn", true)))
        )

        fun functionDeclarations(): JSONArray = JSONArray().apply {
            FUNCTIONS.forEach { fn ->
                val decl = JSONObject().put("name", fn.name).put("description", fn.description)
                if (fn.params.isNotEmpty()) {
                    val props = JSONObject()
                    fn.params.forEach { p -> props.put(p.name, JSONObject().put("type", p.type).put("description", p.description)) }
                    val schema = JSONObject().put("type", "OBJECT").put("properties", props)
                    val required = fn.params.filter { it.required }.map { it.name }
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required))
                    decl.put("parameters", schema)
                }
                put(decl)
            }
        }

        fun fromFunctionCall(name: String, args: JSONObject): ResolvedIntent? {
            val fn = FUNCTIONS.firstOrNull { it.name == name } ?: return null
            val slots = LinkedHashMap<String, String>()
            fn.params.forEach { p ->
                if (args.has(p.name) && !args.isNull(p.name)) {
                    val v = args.get(p.name)
                    slots[p.slot] = when (v) {
                        is Number -> if (p.type == "INTEGER") v.toInt().toString() else v.toString()
                        else -> v.toString()
                    }
                }
            }
            return ResolvedIntent(fn.intent, 0.95f, slots, source = "gemini")
        }
    }
}
