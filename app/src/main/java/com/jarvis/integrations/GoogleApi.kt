package com.jarvis.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GoogleApiException(val code: Int, message: String) : Exception(message)

/** Minimal authenticated JSON client for Google REST APIs. */
class GoogleApi(
    private val http: OkHttpClient,
    private val token: suspend () -> String?
) {
    suspend fun call(method: String, url: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val bearer = token() ?: throw GoogleApiException(401, "Google hisobi ulanmagan")
        val requestBody = body?.toString()?.toRequestBody(JSON)
            ?: if (method == "POST" || method == "PATCH" || method == "PUT") "{}".toRequestBody(JSON) else null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .method(method, requestBody)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw GoogleApiException(resp.code, msg ?: "HTTP ${resp.code}")
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
