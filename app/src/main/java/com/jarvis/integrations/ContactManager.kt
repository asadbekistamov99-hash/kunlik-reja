package com.jarvis.integrations

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

data class ContactHit(val name: String, val phone: String, val email: String? = null)

/** Contact lookup and calling ("Jarvis doktor bilan bog'lan"). */
class ContactManager(private val context: Context, private val launcher: ActivityLauncher) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(name: String, limit: Int = 5): List<ContactHit> {
        if (!hasPermission() || name.isBlank()) return emptyList()
        val hits = LinkedHashMap<String, ContactHit>()
        val candidates = listOf(name) + name.split(' ').filter { it.length >= 3 }
        for (term in candidates) {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$term%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                while (c.moveToNext() && hits.size < limit) {
                    val display = c.getString(0) ?: continue
                    val phone = c.getString(1) ?: continue
                    hits.putIfAbsent(display.lowercase(Locale.ROOT), ContactHit(display, phone))
                }
            }
            if (hits.isNotEmpty()) break
        }
        return hits.values.toList()
    }

    fun findEmail(name: String): String? {
        if (!hasPermission() || name.isBlank()) return null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    /** Calls directly with CALL_PHONE, otherwise opens the dialer pre-filled. */
    fun call(contact: ContactHit): ActivityLauncher.Result {
        val direct = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.phone)}"))
        return launcher.launch(intent, "${contact.name} bilan bog'lanish")
    }
}
