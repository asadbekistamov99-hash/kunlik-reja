package com.example.jarvis.integrations

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OAuth 2.0 authorization for Google Calendar and Gmail using Google Identity Services.
 * Tokens are never persisted by Jarvis: Play services caches and refreshes them, and a silent
 * [accessToken] call returns a valid token as long as the user's grant stands.
 */
class GoogleAuth(private val context: Context, private val http: OkHttpClient) {

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES.map { Scope(it) })
        .build()

    /** Starts authorization; the caller launches [AuthorizationResult.getPendingIntent] if it has a resolution. */
    suspend fun authorize(): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request()).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /** Token without UI, or null when the user must (re)grant access. */
    suspend fun accessToken(): String? = runCatching {
        val result = authorize()
        if (result.hasResolution()) null else result.accessToken
    }.getOrNull()

    suspend fun accountEmail(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url(USERINFO_URL).header("Authorization", "Bearer $token").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                org.json.JSONObject(resp.body?.string().orEmpty()).optString("email").ifBlank { null }
            }
        }.getOrNull()
    }

    suspend fun revoke(token: String) = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url(REVOKE_URL).post(FormBody.Builder().add("token", token).build()).build()
            ).execute().close()
        }
    }

    companion object {
        const val CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
        const val EMAIL = "email"
        val SCOPES = listOf(CALENDAR_EVENTS, GMAIL_READONLY, GMAIL_COMPOSE, EMAIL)
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    }
}
