package com.jarvis.integrations

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jarvis.core.DeviceResult

/** A frozen call-log row keeps spoken list indices stable until the next explicit refresh. */
data class CallRecord(val id: Long, val name: String, val number: String, val timeMillis: Long,
    val kind: String, val callable: Boolean = true)

class PhoneCommunications(private val context: Context, private val launcher: ActivityLauncher) {
    fun historyAvailable() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun recentCalls(limit: Int): List<CallRecord> {
        check(historyAvailable()) { "Qo'ng'iroqlar tarixi ruxsati kerak" }
        val rows = mutableListOf<CallRecord>()
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit", limit.coerceIn(1, 50).toString()).build()
        context.contentResolver.query(uri, arrayOf(CallLog.Calls._ID, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
            CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.NUMBER_PRESENTATION), null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit.coerceIn(1, 50)) {
                val number = cursor.getString(2).orEmpty()
                val allowed = cursor.getInt(5) == CallLog.Calls.PRESENTATION_ALLOWED && normalizeNumber(number) != null
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: if (allowed) number else "Yopiq yoki noma'lum raqam"
                val kind = when (cursor.getInt(4)) {
                    CallLog.Calls.INCOMING_TYPE -> "kiruvchi"
                    CallLog.Calls.OUTGOING_TYPE -> "chiquvchi"
                    CallLog.Calls.MISSED_TYPE -> "o'tkazib yuborilgan"
                    CallLog.Calls.REJECTED_TYPE -> "rad etilgan"
                    else -> "boshqa"
                }
                rows += CallRecord(cursor.getLong(0), name, if (allowed) number else "", cursor.getLong(3), kind, allowed)
            }
        }
        return rows
    }

    fun sms(number: String, text: String): DeviceResult {
        val recipient = normalizeNumber(number) ?: return DeviceResult("Telefon raqami noto'g'ri.", false)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$recipient")).putExtra("sms_body", text)
        return draftResult(launcher.launch(intent, "SMS tayyor"), "SMS matni tayyor. Yuborishni SMS ilovasida tasdiqlang.")
    }

    fun addContact(name: String, number: String): DeviceResult {
        val phone = normalizeNumber(number) ?: return DeviceResult("Telefon raqami noto'g'ri.", false)
        val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            .putExtra(ContactsContract.Intents.Insert.NAME, name).putExtra(ContactsContract.Intents.Insert.PHONE, phone)
        return draftResult(launcher.launch(intent, "Yangi kontakt"), "Kontakt oynasi tayyor. Saqlashni kontaktlar ilovasida tasdiqlang.")
    }

    fun socialDraft(app: String, text: String): DeviceResult {
        val packageName = when (app.lowercase()) { "telegram" -> "org.telegram.messenger"; "instagram" -> "com.instagram.android"; else -> return DeviceResult("Telegram yoki Instagram'ni tanlang.", false) }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(packageName).putExtra(Intent.EXTRA_TEXT, text)
        return draftResult(launcher.launch(intent, "$app xabari tayyor"), "$app oynasida qabul qiluvchini tanlang va yuborishni tasdiqlang.")
    }

    private fun draftResult(result: ActivityLauncher.Result, ready: String): DeviceResult = when (result) {
        ActivityLauncher.Result.STARTED -> DeviceResult(ready, false)
        ActivityLauncher.Result.NOTIFIED -> DeviceResult("Matn tayyor. Davom etish uchun bildirishnomani bosing.", false)
        else -> DeviceResult("Bu amal uchun mos ilova yoki matn ulashish imkoniyati topilmadi.", false)
    }

    companion object {
        fun normalizeNumber(value: String): String? = value.replace(Regex("[\\s()\\-]"), "")
            .takeIf { Regex("\\+?[0-9]{3,15}").matches(it) }
    }
}
