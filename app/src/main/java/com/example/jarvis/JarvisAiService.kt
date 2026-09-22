package com.example.jarvis

import android.util.Log
import com.example.BuildConfig
import com.example.data.TaskCategory
import com.example.data.TaskPriority
import com.example.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

object JarvisAiService {

    private const val TAG = "JarvisAiService"
    private const val MODEL_NAME = "gemini-2.5-flash"
    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun processUserVoiceCommand(
        command: String,
        todayTasksSummary: String = ""
    ): JarvisParsedResponse = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val todayStr = TaskRepository.getTodayDateString()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.d(TAG, "Gemini API key not configured, using fast offline Uzbek NLU engine")
            return@withContext processOfflineUzbekCommand(command, todayStr)
        }

        // Wrap network call in 3000ms max timeout so user never waits more than 3s
        val result = withTimeoutOrNull(3000) {
            try {
                val systemInstructionText = """
                    Siz 'Jarvis' nomli juda samimiy, intellektual, mohir va har tomonlama bilimdon o'zbek tilidagi AI yordamchisiz.
                    Foydalanuvchi bilan erkin muloqot qila olasiz, savollariga javob bera olasiz, maslahatlar bera olasiz hamda Kun Tartibi ilovasidagi barcha vazifalarni mukammal bajarib berasiz.
                    Bugungi sana: $todayStr.
                    Foydalanuvchining bugungi mavjud vazifalari: $todayTasksSummary.

                    Muloqot va buyruqlarni tahlil qilib, faqat quyidagi JSON formatida javob bering (hech qanday markdown kod bloklarisiz, faqat sof JSON):
                    {
                      "action": "ADD_TASK" | "TOGGLE_COMPLETED" | "DELETE_TASK" | "SEARCH_TASKS" | "READ_SCHEDULE" | "GENERAL_RESPONSE",
                      "title": "vazifa sarlavhasi",
                      "description": "tavsifi",
                      "category": "WORK" | "STUDY" | "PERSONAL" | "HEALTH" | "HOME" | "OTHER",
                      "priority": "HIGH" | "MEDIUM" | "LOW",
                      "dateString": "YYYY-MM-DD",
                      "timeString": "HH:mm",
                      "durationMinutes": 30,
                      "searchQuery": "qidirilayotgan ibora",
                      "targetTaskTitle": "bajarilgan yoki o'chirilishi kerak bo'lgan vazifa nomi",
                      "responseMessage": "Ovoz bilan aytiladigan samimiy, do'stona va foydali o'zbekcha javob matni"
                    }
                """.trimIndent()

                val rootJson = JSONObject()

                val contentsArray = JSONArray()
                val contentObj = JSONObject()
                contentObj.put("role", "user")

                val partsArray = JSONArray()
                val partObj = JSONObject()
                partObj.put("text", command)
                partsArray.put(partObj)
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
                rootJson.put("contents", contentsArray)

                val sysInstObj = JSONObject()
                val sysPartsArray = JSONArray()
                val sysPartObj = JSONObject()
                sysPartObj.put("text", systemInstructionText)
                sysPartsArray.put(sysPartObj)
                sysInstObj.put("parts", sysPartsArray)
                rootJson.put("systemInstruction", sysInstObj)

                val genConfigObj = JSONObject()
                val respFormatObj = JSONObject()
                respFormatObj.put("responseMimeType", "application/json")
                genConfigObj.put("responseFormat", respFormatObj)
                genConfigObj.put("temperature", 0.2)
                rootJson.put("generationConfig", genConfigObj)

                val requestBody = rootJson.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$API_URL?key=$apiKey")
                    .post(requestBody)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini API error ${response.code}: $responseStr")
                    null
                } else {
                    val respJson = JSONObject(responseStr)
                    val candidates = respJson.optJSONArray("candidates")
                    val firstCandidate = candidates?.optJSONObject(0)
                    val content = firstCandidate?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val textResult = parts?.optJSONObject(0)?.optString("text") ?: ""

                    parseJarvisJsonResponse(textResult, command, todayStr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API: ${e.message}")
                null
            }
        }

        result ?: processOfflineUzbekCommand(command, todayStr)
    }

    private fun parseJarvisJsonResponse(
        jsonString: String,
        originalCommand: String,
        todayStr: String
    ): JarvisParsedResponse {
        return try {
            val cleanJson = jsonString.replace("```json", "").replace("```", "").trim()
            val obj = JSONObject(cleanJson)

            val actionStr = obj.optString("action", "GENERAL_RESPONSE")
            val actionType = try {
                JarvisActionType.valueOf(actionStr)
            } catch (_: Exception) {
                JarvisActionType.GENERAL_RESPONSE
            }

            JarvisParsedResponse(
                action = actionType,
                title = obj.optString("title", "Yangi vazifa"),
                description = obj.optString("description", ""),
                category = obj.optString("category", TaskCategory.PERSONAL.name),
                priority = obj.optString("priority", TaskPriority.MEDIUM.name),
                dateString = obj.optString("dateString", todayStr).ifBlank { todayStr },
                timeString = obj.optString("timeString", "09:00").ifBlank { "09:00" },
                durationMinutes = obj.optInt("durationMinutes", 30),
                searchQuery = obj.optString("searchQuery", ""),
                targetTaskTitle = obj.optString("targetTaskTitle", ""),
                responseMessage = obj.optString("responseMessage", "Buyrug'ingiz bajarildi.")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON from Gemini: ${e.message}")
            processOfflineUzbekCommand(originalCommand, todayStr)
        }
    }

    // Offline Uzbek Natural Language Processing Fallback
    fun processOfflineUzbekCommand(
        command: String,
        todayStr: String = TaskRepository.getTodayDateString()
    ): JarvisParsedResponse {
        val cmd = command.lowercase(Locale.getDefault()).trim()

        // Extract Time (e.g., "15:00", "soat 10 da", "soat 9 yarimda")
        val timeRegex = Regex("""(?:soat\s*)?(\d{1,2})[:.](\d{2})""")
        val timeMatch = timeRegex.find(cmd)
        var extractedTime = "09:00"
        if (timeMatch != null) {
            val h = timeMatch.groupValues[1].padStart(2, '0')
            val m = timeMatch.groupValues[2].padStart(2, '0')
            extractedTime = "$h:$m"
        } else {
            val simpleHourRegex = Regex("""soat\s*(\d{1,2})""")
            val hourMatch = simpleHourRegex.find(cmd)
            if (hourMatch != null) {
                val h = hourMatch.groupValues[1].padStart(2, '0')
                extractedTime = "$h:00"
            }
        }

        // Date extraction (ertaga, bugun, inshoat)
        val targetDate = when {
            cmd.contains("ertaga") -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 1)
                SimpleDateFormat("yyyy-MM-DD", Locale.getDefault()).format(cal.time)
            }
            else -> todayStr
        }

        // Action detection
        val isAdd = cmd.contains("qo'sh") || cmd.contains("yarat") || cmd.contains("rejalashtir") || cmd.contains("kirit") || cmd.contains("eslat")
        val isDelete = cmd.contains("o'chir") || cmd.contains("yo'qot") || cmd.contains("tashla")
        val isComplete = cmd.contains("bajarildi") || cmd.contains("bajarilgan") || cmd.contains("bitdi") || cmd.contains("tamom")
        val isSearch = cmd.contains("qidir") || cmd.contains("top") || cmd.contains("ko'rsat") || cmd.contains("qani")
        val isSchedule = cmd.contains("bugungi") || cmd.contains("ro'yxat") || cmd.contains("reja") || cmd.contains("jadval")

        return when {
            isAdd -> {
                // Clean title
                var cleanedTitle = command
                    .replace(Regex("""(?i)ertaga|bugun|soat\s*\d{1,2}(?::\d{2})?|qo'shsh|qo'shgin|qo'sh|yarat|rejalashtir|vazifasini|vazifa"""), "")
                    .trim()
                if (cleanedTitle.length < 2) cleanedTitle = "Ovozli kiritilgan vazifa"

                // Category detection
                val category = when {
                    cmd.contains("ish") || cmd.contains("majlis") || cmd.contains("loyixa") -> TaskCategory.WORK.name
                    cmd.contains("dars") || cmd.contains("o'qish") || cmd.contains("kitob") -> TaskCategory.STUDY.name
                    cmd.contains("sport") || cmd.contains("badantarbiya") || cmd.contains("dori") || cmd.contains("shifokor") -> TaskCategory.HEALTH.name
                    cmd.contains("uy") || cmd.contains("bozor") || cmd.contains("ovqat") -> TaskCategory.HOME.name
                    else -> TaskCategory.PERSONAL.name
                }

                // Priority
                val priority = when {
                    cmd.contains("muhim") || cmd.contains("tezkor") || cmd.contains("yuqori") -> TaskPriority.HIGH.name
                    cmd.contains("sekin") || cmd.contains("past") -> TaskPriority.LOW.name
                    else -> TaskPriority.MEDIUM.name
                }

                JarvisParsedResponse(
                    action = JarvisActionType.ADD_TASK,
                    title = cleanedTitle,
                    category = category,
                    priority = priority,
                    dateString = targetDate,
                    timeString = extractedTime,
                    responseMessage = "Jarvis: '$cleanedTitle' vazifasi soat $extractedTime uchun muvaffaqiyatli qo'shildi!"
                )
            }
            isComplete -> {
                val targetTitle = command.replace(Regex("""(?i)bajarildi|bajarilgan|deb|belgilagin|belgila|vazifasini"""), "").trim()
                JarvisParsedResponse(
                    action = JarvisActionType.TOGGLE_COMPLETED,
                    targetTaskTitle = targetTitle,
                    responseMessage = "Jarvis: Vazifa bajarildi deb belgilandi."
                )
            }
            isDelete -> {
                val targetTitle = command.replace(Regex("""(?i)o'chir|yo'qot|tashla|vazifasini"""), "").trim()
                JarvisParsedResponse(
                    action = JarvisActionType.DELETE_TASK,
                    targetTaskTitle = targetTitle,
                    responseMessage = "Jarvis: Vazifa muvaffaqiyatli o'chirildi."
                )
            }
            isSearch -> {
                val query = command.replace(Regex("""(?i)qidir|top|ko'rsat|bo'yicha|vazifalarni"""), "").trim()
                JarvisParsedResponse(
                    action = JarvisActionType.SEARCH_TASKS,
                    searchQuery = query,
                    responseMessage = "Jarvis: '$query' bo'yicha vazifalar saralandi."
                )
            }
            isSchedule -> {
                JarvisParsedResponse(
                    action = JarvisActionType.READ_SCHEDULE,
                    responseMessage = "Jarvis: Mana bugungi kuningiz tartibi va vazifalaringiz ro'yxati."
                )
            }
            cmd.contains("salom") || cmd.contains("assalom") -> {
                JarvisParsedResponse(
                    action = JarvisActionType.GENERAL_RESPONSE,
                    responseMessage = "Assalomu alaykum! Men Jarvisman. Bugungi kuningiz unumli o'tishini tilayman! Qanday vazifalarni rejalashtiramiz?"
                )
            }
            cmd.contains("qalaysan") || cmd.contains("qalay") || cmd.contains("yaxshimisiz") || cmd.contains("ishlar") -> {
                JarvisParsedResponse(
                    action = JarvisActionType.GENERAL_RESPONSE,
                    responseMessage = "Ajoyib, rahmat! Barcha tizimlarim tayyor va sizning buyruqlaringizni kutmoqdaman. Sizda qanday xabarlar bor?"
                )
            }
            cmd.contains("rahmat") || cmd.contains("tashakkur") || cmd.contains("barakalla") -> {
                JarvisParsedResponse(
                    action = JarvisActionType.GENERAL_RESPONSE,
                    responseMessage = "Arzimaydi! Sizga yordam berishimdan mamnunman. Yana biror vazifa bormi?"
                )
            }
            cmd.contains("kimsa") || cmd.contains("isming") || cmd.contains("nima qila olasan") -> {
                JarvisParsedResponse(
                    action = JarvisActionType.GENERAL_RESPONSE,
                    responseMessage = "Men Jarvis nomli AI yordamchingizman. Kun tartibingizni boshqarish, yangi vazifalar qo'shish, eslatmalar o'rnatish va siz bilan erkin muloqot qilish uchun xizmatidaman!"
                )
            }
            else -> {
                JarvisParsedResponse(
                    action = JarvisActionType.GENERAL_RESPONSE,
                    responseMessage = "Sizni tushundim. Men Jarvisman, siz bilan erkin muloqotdaman va har qanday vazifalaringizni bajarishga tayyorman!"
                )
            }
        }
    }
}
