package com.jarvis.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Google Gemini AI asosidagi aqlli buyruq va muloqot agenti.
 * Mo'rt Regex o'rniga responseSchema (JSON Schema) va org.json.JSONObject parseridan foydalanadi.
 * O'zbekcha gaplarni tahlil qilib, IntentType va slotlarga ajratadi. Javob kafolatlanmaydi:
 * kalit yo'q, offlayn yoki xato bo'lsa null qaytadi va JarvisEngine offlayn natijaga tayanadi.
 */
class GeminiAgent(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val online: () -> Boolean,
    private val endpoint: String = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent",
    private val timeoutMs: Long = 10_000
) : AgentPort {

    override fun isAvailable(): Boolean {
        val key = apiKey()?.trim()
        return !key.isNullOrEmpty() && online()
    }

    override suspend fun decide(text: String, context: AgentContext): ResolvedIntent? = withContext(Dispatchers.IO) {
        val key = apiKey()?.trim()
        if (key.isNullOrEmpty() || !online() || text.isBlank()) return@withContext null

        val systemPrompt = buildSystemPrompt(context)
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.15)
                put("maxOutputTokens", 512)
                put("responseMimeType", "application/json")
                put("responseSchema", responseSchema())
            })
        }

        val client = http.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val url = if (endpoint.contains("?key=")) endpoint else "$endpoint?key=$key"
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                parseGeminiResponse(bodyStr)
            }
        }.getOrNull()
    }

    private fun buildSystemPrompt(context: AgentContext): String {
        val validIntents = IntentType.values().joinToString(", ") { it.name }
        val recentHistory = context.history.takeLast(5).joinToString("\n") { (u, a) ->
            "Foydalanuvchi: $u\nJarvis: $a"
        }
        return """
Sen Jarvis — o'zbek tilida ravon, tabiiy va aniq ishlovchi shaxsiy ovozli yordamchisan.
Hozirgi vaqt: ${context.now}
Foydalanuvchi profili: ${context.profile}
Bugungi vazifalar: ${context.todayTasks}
Oxirgi suhbat tarixi:
$recentHistory

Foydalanuvchi buyrug'ini tahlil qilib, FAQAT quyidagi JSON formatida javob qaytar:
{
  "intent": "<IntentType>",
  "confidence": 0.95,
  "slots": {
    "title": "<vazifa, eslatma, odat yoki uchrashuvning toza nomi>",
    "date": "<YYYY-MM-DD agar sana aytilgan bo'lsa>",
    "time": "<HH:MM 24-soatlik formatda agar vaqt aytilgan bo'lsa>",
    "durationMinutes": "<daqiqa soni>",
    "query": "<qidiruv so'zi>",
    "name": "<kontakt ismi>",
    "fact": "<eslab qolinadigan ma'lumot>",
    "to": "<email qabul qiluvchi>",
    "subject": "<email mavzusi>",
    "body": "<email matni>",
    "reply": "<foydalanuvchiga o'zbek tilida qisqa, tabiiy, iliq va aniq ovozli javob>"
  }
}

Mavjud IntentType qiymatlari: [$validIntents].
Qoidalar:
1. Agar foydalanuvchi vazifa, ish yoki reja aytgan bo'lsa -> ADD_TASK, "title" slotiga faqat vazifa nomini yoz (masalan: "Stomatologga borish").
2. Agar foydalanuvchi eslatish haqida aytsa -> ADD_REMINDER.
3. Agar foydalanuvchi biror ishni bajarganini aytsa -> COMPLETE_TASK.
4. Agar foydalanuvchi vazifani o'chirishni so'rasa -> DELETE_TASK.
5. Agar foydalanuvchi vazifa vaqtini o'zgartirishni so'rasa -> RESCHEDULE_TASK.
6. Agar foydalanuvchi erkin savol bersa yoki suhbat qursa -> UNKNOWN va "reply" slotiga o'zbek tilida aniq, ravon, qisqa javob yoz.
""".trimIndent()
    }

    /** Gemini javobini IntentType enum va slot maydonlari bilan cheklovchi JSON Schema. */
    private fun responseSchema(): JSONObject {
        fun str(description: String) = JSONObject()
            .put("type", "STRING")
            .put("description", description)

        val slots = JSONObject()
            .put("type", "OBJECT")
            .put("description", "Faqat aytilgan qiymatlar; aytilmagan maydonlarni yozma")
            .put("properties", JSONObject()
                .put("title", str("Vazifa, eslatma, odat yoki uchrashuvning toza nomi"))
                .put("date", str("YYYY-MM-DD"))
                .put("time", str("HH:MM 24-soatlik"))
                .put("durationMinutes", str("Daqiqa soni"))
                .put("query", str("Qidiruv so'zi"))
                .put("name", str("Kontakt ismi"))
                .put("fact", str("Eslab qolinadigan ma'lumot"))
                .put("to", str("Email qabul qiluvchi"))
                .put("subject", str("Email mavzusi"))
                .put("body", str("Email matni"))
                .put("reply", str("Foydalanuvchiga qisqa o'zbekcha javob")))

        val intentNames = JSONArray()
        IntentType.values().forEach { intentNames.put(it.name) }

        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("intent", JSONObject()
                    .put("type", "STRING")
                    .put("enum", intentNames))
                .put("confidence", JSONObject().put("type", "NUMBER"))
                .put("slots", slots)
                .put("reply", str("Foydalanuvchiga qisqa o'zbekcha javob")))
            .put("required", JSONArray().put("intent").put("confidence"))
    }

    private fun parseGeminiResponse(rawBody: String): ResolvedIntent? {
        val root = JSONObject(rawBody)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null
        val textPart = parts.optJSONObject(0)?.optString("text", "") ?: return null

        val cleanJson = textPart
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val obj = JSONObject(cleanJson)
        val intentStr = obj.optString("intent", "UNKNOWN").uppercase(Locale.ROOT)
        val intentType = runCatching { IntentType.valueOf(intentStr) }.getOrDefault(IntentType.UNKNOWN)
        val confidence = obj.optDouble("confidence", 0.9).toFloat().coerceIn(0f, 1f)

        val slotsMap = mutableMapOf<String, String>()
        obj.optJSONObject("slots")?.let { slotsJson ->
            val keys = slotsJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = slotsJson.optString(k, "").trim()
                if (v.isNotEmpty() && v != "null") {
                    slotsMap[k] = v
                }
            }
        }
        val topReply = obj.optString("reply", "").trim()
        if (topReply.isNotEmpty() && !slotsMap.containsKey("reply")) {
            slotsMap["reply"] = topReply
        }

        return ResolvedIntent(
            type = intentType,
            confidence = confidence,
            slots = slotsMap,
            source = "gemini"
        )
    }

    companion object {
        const val MODEL = "gemini-2.5-flash"
    }
}