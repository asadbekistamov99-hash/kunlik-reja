package com.jarvis.integrations

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class CalendarEvent(
    val id: String,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val location: String = "",
    val source: Source
) {
    enum class Source { GOOGLE, DEVICE }
}

class CalendarUnavailableException(message: String) : Exception(message)

/**
 * Google Calendar integration. Uses the Calendar REST API when the Google account is connected
 * and online; otherwise falls back to the on-device CalendarContract provider (which syncs with the
 * phone's Google account on its own), so calendar commands also work offline.
 */
class CalendarManager(
    private val context: Context,
    private val api: GoogleApi,
    private val useRemote: suspend () -> Boolean,
    private val baseUrl: String = "https://www.googleapis.com/calendar/v3",
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    suspend fun events(from: ZonedDateTime, to: ZonedDateTime): List<CalendarEvent> =
        if (useRemote()) remoteEvents(from, to) else deviceEvents(from, to)

    suspend fun eventsOn(date: LocalDate): List<CalendarEvent> {
        val z = zone()
        return events(date.atStartOfDay(z), date.plusDays(1).atStartOfDay(z))
    }

    suspend fun create(title: String, start: ZonedDateTime, end: ZonedDateTime, description: String = ""): CalendarEvent =
        if (useRemote()) {
            val body = eventJson(title, start, end, description)
            parseRemote(api.call("POST", "$baseUrl/calendars/primary/events", body))
                ?: throw CalendarUnavailableException("Tadbir yaratilmadi")
        } else deviceCreate(title, start, end, description)

    suspend fun update(event: CalendarEvent, start: ZonedDateTime, end: ZonedDateTime, title: String = event.title): CalendarEvent =
        when (event.source) {
            CalendarEvent.Source.GOOGLE -> parseRemote(
                api.call("PATCH", "$baseUrl/calendars/primary/events/${enc(event.id)}", eventJson(title, start, end, null))
            ) ?: event
            CalendarEvent.Source.DEVICE -> withContext(Dispatchers.IO) {
                requireDevicePermission()
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
                }
                context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id.toLong()), values, null, null
                )
                event.copy(title = title, start = start, end = end)
            }
        }

    suspend fun delete(event: CalendarEvent) {
        when (event.source) {
            CalendarEvent.Source.GOOGLE -> api.call("DELETE", "$baseUrl/calendars/primary/events/${enc(event.id)}")
            CalendarEvent.Source.DEVICE -> withContext(Dispatchers.IO) {
                requireDevicePermission()
                context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id.toLong()), null, null
                )
            }
        }
    }

    suspend fun findByTitle(title: String, date: LocalDate?): CalendarEvent? {
        val z = zone()
        val from = (date ?: LocalDate.now(z)).atStartOfDay(z)
        val to = from.plusDays(if (date == null) 14 else 1)
        val q = title.lowercase()
        return events(from, to).firstOrNull { it.title.lowercase().contains(q) || q.contains(it.title.lowercase()) }
    }

    // ---- REST ----

    private suspend fun remoteEvents(from: ZonedDateTime, to: ZonedDateTime): List<CalendarEvent> {
        val url = "$baseUrl/calendars/primary/events?singleEvents=true&orderBy=startTime&maxResults=50" +
            "&timeMin=${enc(from.format(RFC3339))}&timeMax=${enc(to.format(RFC3339))}"
        val items = api.call("GET", url).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { parseRemote(items.getJSONObject(it)) }
    }

    private fun eventJson(title: String, start: ZonedDateTime, end: ZonedDateTime, description: String?) = JSONObject().apply {
        put("summary", title)
        if (!description.isNullOrBlank()) put("description", description)
        put("start", JSONObject().put("dateTime", start.format(RFC3339)).put("timeZone", start.zone.id))
        put("end", JSONObject().put("dateTime", end.format(RFC3339)).put("timeZone", end.zone.id))
    }

    private fun parseRemote(o: JSONObject): CalendarEvent? {
        val id = o.optString("id").ifBlank { return null }
        val z = zone()
        fun time(key: String): ZonedDateTime? {
            val t = o.optJSONObject(key) ?: return null
            t.optString("dateTime").takeIf { it.isNotBlank() }?.let { return OffsetDateTime.parse(it).atZoneSameInstant(z) }
            t.optString("date").takeIf { it.isNotBlank() }?.let { return LocalDate.parse(it).atStartOfDay(z) }
            return null
        }
        val start = time("start") ?: return null
        return CalendarEvent(id, o.optString("summary", "(nomsiz)"), start, time("end") ?: start.plusHours(1),
            o.optString("location"), CalendarEvent.Source.GOOGLE)
    }

    // ---- On-device provider ----

    private fun requireDevicePermission() {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!ok) throw CalendarUnavailableException("Taqvim uchun ruxsat yo'q va Google hisobi ulanmagan")
    }

    private suspend fun deviceEvents(from: ZonedDateTime, to: ZonedDateTime): List<CalendarEvent> = withContext(Dispatchers.IO) {
        requireDevicePermission()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toInstant().toEpochMilli())
            ContentUris.appendId(it, to.toInstant().toEpochMilli())
        }.build()
        val z = zone()
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarEvent(
                    c.getLong(0).toString(), c.getString(1) ?: "(nomsiz)",
                    Instant.ofEpochMilli(c.getLong(2)).atZone(z), Instant.ofEpochMilli(c.getLong(3)).atZone(z),
                    c.getString(4).orEmpty(), CalendarEvent.Source.DEVICE
                )
            }
        }
        out
    }

    private suspend fun deviceCreate(title: String, start: ZonedDateTime, end: ZonedDateTime, description: String): CalendarEvent =
        withContext(Dispatchers.IO) {
            requireDevicePermission()
            val calendarId = primaryDeviceCalendar() ?: throw CalendarUnavailableException("Qurilmada taqvim topilmadi")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, start.zone.id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw CalendarUnavailableException("Tadbir yaratilmadi")
            CalendarEvent(ContentUris.parseId(uri).toString(), title, start, end, "", CalendarEvent.Source.DEVICE)
        }

    private fun primaryDeviceCalendar(): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null
        )?.use { c ->
            var fallback: Long? = null
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (c.getInt(1) == 1) return c.getLong(0)
                if (fallback == null) fallback = c.getLong(0)
            }
            return fallback
        }
        return null
    }

    companion object {
        val RFC3339: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
