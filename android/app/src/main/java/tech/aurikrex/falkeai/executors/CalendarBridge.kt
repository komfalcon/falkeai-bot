package tech.aurikrex.falkeai.executors

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CalendarEventInfo(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val location: String
)

/**
 * Full on-device Android CalendarContract CRUD (List / Create / Edit / Delete).
 */
object CalendarBridge {
    private fun hasRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun hasWrite(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun findPrimaryCalendarId(context: Context): Long? {
        if (!hasRead(context)) return null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null
        )?.use { cursor ->
            var fallbackId: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val isPrimary = cursor.getInt(1) == 1
                val access = cursor.getInt(2)
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    if (isPrimary) return id
                    if (fallbackId == null) fallbackId = id
                }
            }
            return fallbackId
        }
        return null
    }

    fun listUpcoming(context: Context, daysAhead: Int = 7, limit: Int = 10): List<CalendarEventInfo> {
        if (!hasRead(context)) return emptyList()
        val now = System.currentTimeMillis()
        val end = now + daysAhead * 86_400_000L
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, now)
        ContentUris.appendId(uriBuilder, end)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val results = mutableListOf<CalendarEventInfo>()
        runCatching {
            context.contentResolver.query(
                uriBuilder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < limit) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1).orEmpty().ifBlank { "(Untitled)" }
                    val begin = cursor.getLong(2)
                    val endT = cursor.getLong(3)
                    val loc = cursor.getString(4).orEmpty()
                    results.add(CalendarEventInfo(id, title, begin, endT, loc))
                }
            }
        }
        return results
    }

    fun formatUpcomingStrings(context: Context): List<String> {
        val fmt = SimpleDateFormat("EEE d MMM h:mm a", Locale.US)
        return listUpcoming(context, 7, 8).map { ev ->
            "${fmt.format(Date(ev.startMs))} — ${ev.title}" +
                if (ev.location.isNotBlank()) " (${ev.location})" else ""
        }
    }

    fun createEvent(
        context: Context,
        title: String,
        startMs: Long,
        durationMinutes: Int = 60,
        description: String = "",
        location: String = ""
    ): Result<Long> {
        if (!hasWrite(context)) return Result.failure(SecurityException("Calendar write permission missing"))
        val calId = findPrimaryCalendarId(context)
            ?: return Result.failure(IllegalStateException("No writable Android calendar found"))
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title.ifBlank { "FalkeAI Event" })
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, startMs + durationMinutes.coerceAtLeast(15) * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return Result.failure(IllegalStateException("Failed to insert calendar event"))
        return Result.success(ContentUris.parseId(uri))
    }

    fun editEventByTitle(
        context: Context,
        matchTitle: String,
        newTitle: String? = null,
        newStartMs: Long? = null,
        newDurationMinutes: Int? = null
    ): Result<String> {
        if (!hasWrite(context)) return Result.failure(SecurityException("Calendar write permission missing"))
        val match = listUpcoming(context, 30, 40)
            .firstOrNull { it.title.contains(matchTitle, ignoreCase = true) }
            ?: return Result.failure(IllegalArgumentException("No upcoming event matching \"$matchTitle\""))

        val values = ContentValues()
        if (!newTitle.isNullOrBlank()) values.put(CalendarContract.Events.TITLE, newTitle)
        if (newStartMs != null && newStartMs > 0) {
            val dur = (newDurationMinutes?.toLong()?.times(60_000L))
                ?: (match.endMs - match.startMs).coerceAtLeast(30 * 60_000L)
            values.put(CalendarContract.Events.DTSTART, newStartMs)
            values.put(CalendarContract.Events.DTEND, newStartMs + dur)
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, match.id)
        val rows = context.contentResolver.update(uri, values, null, null)
        return if (rows > 0) Result.success("Updated calendar event \"${match.title}\"")
        else Result.failure(IllegalStateException("Could not update event"))
    }

    fun deleteEventByTitle(context: Context, matchTitle: String): Result<String> {
        if (!hasWrite(context)) return Result.failure(SecurityException("Calendar write permission missing"))
        val match = listUpcoming(context, 30, 40)
            .firstOrNull { it.title.contains(matchTitle, ignoreCase = true) }
            ?: return Result.failure(IllegalArgumentException("No upcoming event matching \"$matchTitle\""))
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, match.id)
        val rows = context.contentResolver.delete(uri, null, null)
        return if (rows > 0) Result.success("Deleted calendar event \"${match.title}\"")
        else Result.failure(IllegalStateException("Could not delete event"))
    }
}
