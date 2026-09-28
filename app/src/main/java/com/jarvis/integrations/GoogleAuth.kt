package com.jarvis.integrations

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
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
 *
 * SETUP REQUIRED:
 * 1. Go to https://console.cloud.google.com and create an OAuth 2.0 Client ID
 * 2. For Android, provide: package name (com.aistudio.kuntartibi.xqpzly) and SHA-1 fingerprint
 * 3. Update CLIENT_ID below with your actual OAuth 2.0 Client ID from Google Cloud Console
 * 4. Enable Calendar and Gmail APIs in your Google Cloud project
 */
class GoogleAuth(private val context: Context, private val http: OkHttpClient) {

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES.map { Scope(it) })
        .setClientId(CLIENT_ID)  // Add your OAuth 2.0 Client ID from Google Cloud Console
        .build()

    /** Starts authorization; the caller launches [AuthorizationResult.getPendingIntent] if it has a resolution. */
    suspend fun authorize(): AuthorizationResult {
        Log.d(TAG, "Starting Google authorization with Client ID: $CLIENT_ID")
        return try {
            val request = request()
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            Log.d(TAG, "Authorization result: hasResolution=${result.hasResolution()}, hasAccessToken=${result.accessToken != null}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Authorization failed", e)
            throw e
        }
    }

    fun resultFromIntent(data: Intent?): AuthorizationResult {
        Log.d(TAG, "Processing authorization intent result")
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            Log.d(TAG, "Intent result: hasAccessToken=${result.accessToken != null}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process authorization result", e)
            throw e
        }
    }

    /** Token without UI, or null when the user must (re)grant access. */
    suspend fun accessToken(): String? = runCatching {
        Log.d(TAG, "Requesting silent access token")
        val result = authorize()
        if (result.hasResolution()) {
            Log.w(TAG, "User interaction required for token")
            null
        } else {
            Log.d(TAG, "Got silent access token")
            result.accessToken
        }
    }.onFailure {
        Log.e(TAG, "Failed to get access token", it)
    }.getOrNull()

    suspend fun accountEmail(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Fetching account email")
            http.newCall(
                Request.Builder().url(USERINFO_URL).header("Authorization", "Bearer $token").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "userinfo request failed: HTTP ${resp.code}")
                    return@use null
                }
                val email = org.json.JSONObject(resp.body?.string().orEmpty()).optString("email").ifBlank { null }
                if (email != null) Log.d(TAG, "Got account email: ${email.take(5)}...")
                email
            }
        }.onFailure {
            Log.e(TAG, "Failed to fetch account email", it)
        }.getOrNull()
    }

    suspend fun revoke(token: String) = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Revoking access token")
            http.newCall(
                Request.Builder().url(REVOKE_URL).post(FormBody.Builder().add("token", token).build()).build()
            ).execute().close()
            Log.d(TAG, "Access token revoked")
        }.onFailure {
            Log.e(TAG, "Failed to revoke token", it)
        }
    }

    companion object {
        private const val TAG = "GoogleAuth"
        const val CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
        const val EMAIL = "email"
        val SCOPES = listOf(CALENDAR_EVENTS, GMAIL_READONLY, GMAIL_COMPOSE, EMAIL)
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"

        // OAuth 2.0 Client ID from local.properties or BuildConfig
        // Setup: https://console.cloud.google.com/apis/credentials
        // See SETUP_GOOGLE_AUTH.md for complete instructions
        private val CLIENT_ID: String
            get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.ifBlank {
                "REPLACE_WITH_YOUR_OAUTH_CLIENT_ID.apps.googleusercontent.com"
            }
    }
}
