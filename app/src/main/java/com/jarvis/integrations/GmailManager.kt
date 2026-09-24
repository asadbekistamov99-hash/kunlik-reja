package com.jarvis.integrations

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

data class EmailSummary(val id: String, val from: String, val subject: String, val snippet: String, val date: String)

/** Gmail REST integration: read unread mail, create drafts, send. */
class GmailManager(
    private val api: GoogleApi,
    private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me"
) {

    suspend fun unread(max: Int = 5): List<EmailSummary> {
        val q = URLEncoder.encode("is:unread in:inbox", "UTF-8")
        val list = api.call("GET", "$baseUrl/messages?q=$q&maxResults=$max")
        val ids = list.optJSONArray("messages") ?: return emptyList()
        return (0 until ids.length()).map { i ->
            val id = ids.getJSONObject(i).getString("id")
            val msg = api.call("GET", "$baseUrl/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
            val headers = msg.optJSONObject("payload")?.optJSONArray("headers")
            fun header(name: String): String {
                if (headers == null) return ""
                for (h in 0 until headers.length()) {
                    val o = headers.getJSONObject(h)
                    if (o.optString("name").equals(name, ignoreCase = true)) return o.optString("value")
                }
                return ""
            }
            EmailSummary(id, displayName(header("From")), header("Subject").ifBlank { "(mavzusiz)" }, msg.optString("snippet"), header("Date"))
        }
    }

    suspend fun createDraft(to: String, subject: String, body: String): String {
        val raw = MimeMessage.build(to, subject, body)
        val result = api.call("POST", "$baseUrl/drafts", JSONObject().put("message", JSONObject().put("raw", raw)))
        return result.optString("id")
    }

    suspend fun send(to: String, subject: String, body: String): String {
        val raw = MimeMessage.build(to, subject, body)
        return api.call("POST", "$baseUrl/messages/send", JSONObject().put("raw", raw)).optString("id")
    }

    companion object {
        fun displayName(from: String): String =
            from.substringBefore('<').trim().trim('"').ifBlank { from.substringAfter('<').substringBefore('>') }

        fun describe(mails: List<EmailSummary>): String {
            if (mails.isEmpty()) return "O'qilmagan xatlar yo'q."
            val sb = StringBuilder("${mails.size} ta o'qilmagan xat bor. ")
            mails.take(5).forEach { sb.append("${it.from}dan: ${it.subject}. ") }
            return sb.toString().trim()
        }
    }
}

/** RFC 2822 message encoded as base64url, as the Gmail API expects in "raw". */
object MimeMessage {
    fun build(to: String, subject: String, body: String): String {
        val encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.toByteArray(Charsets.UTF_8)) + "?="
        val encodedBody = Base64.getMimeEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val message = buildString {
            append("To: ").append(to.replace("\r", "").replace("\n", "")).append("\r\n")
            append("Subject: ").append(encodedSubject).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(encodedBody)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray(Charsets.UTF_8))
    }
}
