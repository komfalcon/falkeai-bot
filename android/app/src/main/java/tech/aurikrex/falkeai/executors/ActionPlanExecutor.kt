package tech.aurikrex.falkeai.executors

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tech.aurikrex.falkeai.data.FalkeDatabase
import tech.aurikrex.falkeai.data.SecurePrefs
import tech.aurikrex.falkeai.monitors.FalkeAccessibilityService
import tech.aurikrex.falkeai.monitors.FalkeNotificationListener

data class PendingUndoAction(
    val id: Long = System.currentTimeMillis(),
    val summary: String,
    val execute: suspend () -> String
)

/**
 * Executes typed `device_actions` emitted by Rust `POST /api/v1/phone/chat`
 * AND offline `LocalDeviceCommand`s from `LocalCommandRouter`.
 * Routes outgoing voice SMS through the 6-second Undo safety callback (Feature J).
 */
class ActionPlanExecutor(
    private val context: Context,
    private val prefs: SecurePrefs,
    private val db: FalkeDatabase,
    private val onStageUndoableAction: (PendingUndoAction) -> Unit
) {
    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    private fun JsonObject.intVal(vararg keys: String): Int? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.intOrNull
            if (v != null) return v
        }
        return null
    }

    suspend fun executeLocalCommand(cmd: LocalDeviceCommand): String = when (cmd) {
        is LocalDeviceCommand.CallContact ->
            PhoneMediaExecutor.callContact(context, cmd.target, prefs.confirmUnknownNumbers)
        is LocalDeviceCommand.SendSms -> {
            onStageUndoableAction(
                PendingUndoAction(
                    summary = "Sending SMS to ${cmd.target}: \"${cmd.message.take(60)}\""
                ) {
                    PhoneMediaExecutor.sendSmsNow(context, cmd.target, cmd.message, prefs.confirmUnknownNumbers)
                }
            )
            "Sending SMS to ${cmd.target} in 6 seconds. Say Cancel or tap Undo to stop."
        }
        is LocalDeviceCommand.SetAlarm -> {
            val saved = FalkeAlarmManager.createAlarm(context, db, cmd.hour, cmd.minute, cmd.label)
            "Alarm set for ${FalkeAlarmManager.formatAlarmTime(saved.hour, saved.minute)} (${saved.label})."
        }
        is LocalDeviceCommand.EditAlarm -> {
            val updated = FalkeAlarmManager.editAlarm(context, db, cmd.oldQuery, cmd.newHour, cmd.newMinute)
            if (updated != null) {
                "Moved alarm to ${FalkeAlarmManager.formatAlarmTime(updated.hour, updated.minute)}."
            } else {
                "Couldn't find an alarm matching \"${cmd.oldQuery}\"."
            }
        }
        is LocalDeviceCommand.DeleteAlarm -> {
            val removed = FalkeAlarmManager.deleteMatchingAlarm(context, db, cmd.query)
            if (removed != null) {
                "Deleted ${FalkeAlarmManager.formatAlarmTime(removed.hour, removed.minute)} alarm."
            } else {
                "No alarm found matching \"${cmd.query}\"."
            }
        }
        is LocalDeviceCommand.ListAlarms -> {
            val list = db.alarmDao().getEnabled()
            if (list.isEmpty()) "You have no active FalkeAI alarms."
            else "Active alarms: " + list.joinToString(", ") {
                "${FalkeAlarmManager.formatAlarmTime(it.hour, it.minute)} (${it.label})"
            }
        }
        is LocalDeviceCommand.SetTimer -> {
            FalkeAlarmManager.startSystemTimer(context, cmd.seconds, cmd.label)
            val mins = cmd.seconds / 60
            if (mins > 0) "Started $mins minute timer." else "Started ${cmd.seconds} second timer."
        }
        is LocalDeviceCommand.SetDnd -> PhoneMediaExecutor.setDnd(context, cmd.enabled, cmd.mode)
        is LocalDeviceCommand.ToggleFlashlight -> PhoneMediaExecutor.setFlashlight(context, cmd.on)
        is LocalDeviceCommand.SpotifyPlay -> PhoneMediaExecutor.playSpotify(context, cmd.query)
        is LocalDeviceCommand.MediaControl -> PhoneMediaExecutor.mediaControl(context, cmd.action)
        is LocalDeviceCommand.FindPhoneSiren -> {
            PhoneMediaExecutor.triggerFindMyPhoneSiren(context)
            "I'm right here, boss!"
        }
        is LocalDeviceCommand.DictateText -> {
            val ok = FalkeAccessibilityService.injectTextIntoFocusedInput(cmd.text)
            if (ok) "Typed into the active box."
            else "Enable FalkeAI Accessibility in Setup and tap a text box first."
        }
    }

    suspend fun executeServerActions(actions: List<JsonObject>): List<String> {
        val results = mutableListOf<String>()
        for (act in actions) {
            val type = act.str("type").lowercase()
            val note = when (type) {
                "call_contact" -> {
                    val target = act.str("phone", "target", "name")
                    PhoneMediaExecutor.callContact(context, target, prefs.confirmUnknownNumbers)
                }
                "send_sms" -> {
                    val target = act.str("phone", "target", "name")
                    val msg = act.str("message", "text")
                    if (target.isNotEmpty() && msg.isNotEmpty()) {
                        onStageUndoableAction(
                            PendingUndoAction(
                                summary = "SMS to $target: \"${msg.take(60)}\""
                            ) {
                                PhoneMediaExecutor.sendSmsNow(context, target, msg, prefs.confirmUnknownNumbers)
                            }
                        )
                        "Queued SMS to $target (6s Undo window)."
                    } else "Skipped empty SMS action."
                }
                "spotify_play" -> PhoneMediaExecutor.playSpotify(context, act.str("query", "track", "song"))
                "media_control" -> PhoneMediaExecutor.mediaControl(context, act.str("action", "command"))
                "alarm_set" -> {
                    val h = act.intVal("hour")
                    val m = act.intVal("minute") ?: 0
                    val parsed = if (h != null) h to m else LocalCommandRouter.parseTime24(act.str("time"))
                    if (parsed != null) {
                        val label = act.str("label").ifBlank { "FalkeAI Alarm" }
                        val saved = FalkeAlarmManager.createAlarm(context, db, parsed.first, parsed.second, label)
                        "Alarm set for ${FalkeAlarmManager.formatAlarmTime(saved.hour, saved.minute)}."
                    } else "Couldn't parse alarm time."
                }
                "alarm_edit" -> {
                    val q = act.str("match", "old_time", "label", "query")
                    val h = act.intVal("new_hour", "hour")
                    val m = act.intVal("new_minute", "minute") ?: 0
                    val parsed = if (h != null) h to m else LocalCommandRouter.parseTime24(act.str("new_time", "time"))
                    if (parsed != null) {
                        val updated = FalkeAlarmManager.editAlarm(context, db, q, parsed.first, parsed.second)
                        if (updated != null) "Updated alarm to ${FalkeAlarmManager.formatAlarmTime(updated.hour, updated.minute)}."
                        else "No matching alarm found."
                    } else "Couldn't parse new alarm time."
                }
                "alarm_delete" -> {
                    val q = act.str("match", "time", "label", "query")
                    val removed = FalkeAlarmManager.deleteMatchingAlarm(context, db, q)
                    if (removed != null) "Deleted ${FalkeAlarmManager.formatAlarmTime(removed.hour, removed.minute)} alarm."
                    else "No matching alarm found."
                }
                "alarm_list" -> {
                    val list = db.alarmDao().getEnabled()
                    if (list.isEmpty()) "No active alarms."
                    else list.joinToString(", ") { "${FalkeAlarmManager.formatAlarmTime(it.hour, it.minute)} ${it.label}" }
                }
                "set_timer" -> {
                    val secs = act.intVal("seconds") ?: ((act.intVal("minutes") ?: 15) * 60)
                    FalkeAlarmManager.startSystemTimer(context, secs, act.str("label").ifBlank { "FalkeAI Timer" })
                    "Timer started (${secs / 60} min)."
                }
                "calendar_create" -> {
                    val title = act.str("title", "summary")
                    val startMs = act["start_ms"]?.jsonPrimitive?.longOrNull
                        ?: (System.currentTimeMillis() + 3_600_000L)
                    val dur = act.intVal("duration_minutes") ?: 60
                    val loc = act.str("location")
                    CalendarBridge.createEvent(context, title, startMs, dur, act.str("description"), loc)
                        .fold(onSuccess = { "Calendar event \"$title\" created." }, onFailure = { it.message ?: "Calendar error." })
                }
                "calendar_edit" -> {
                    val match = act.str("match_title", "title")
                    val newTitle = act.str("new_title").ifBlank { null }
                    val newStart = act["new_start_ms"]?.jsonPrimitive?.longOrNull
                    CalendarBridge.editEventByTitle(context, match, newTitle, newStart, act.intVal("duration_minutes"))
                        .fold(onSuccess = { it }, onFailure = { it.message ?: "Calendar edit error." })
                }
                "calendar_delete" -> {
                    val match = act.str("match_title", "title")
                    CalendarBridge.deleteEventByTitle(context, match)
                        .fold(onSuccess = { it }, onFailure = { it.message ?: "Calendar delete error." })
                }
                "set_dnd" -> {
                    val mode = act.str("mode").ifBlank { "priority" }
                    val enabled = act["enabled"]?.jsonPrimitive?.booleanOrNull ?: !mode.equals("off", true)
                    PhoneMediaExecutor.setDnd(context, enabled, mode)
                }
                "toggle_flashlight" -> {
                    val on = act["on"]?.jsonPrimitive?.booleanOrNull ?: true
                    PhoneMediaExecutor.setFlashlight(context, on)
                }
                "dictate_text" -> {
                    val txt = act.str("text", "content")
                    if (FalkeAccessibilityService.injectTextIntoFocusedInput(txt)) "Dictated text."
                    else "Accessibility dictation not active."
                }
                "social_search_app" -> {
                    val platform = act.str("platform", "app")
                    val q = act.str("query")
                    PhoneMediaExecutor.openSocialAppSearch(context, platform, q)
                }
                "notification_reply" -> {
                    val appOrSender = act.str("sender", "target", "app")
                    val reply = act.str("message", "reply")
                    if (FalkeNotificationListener.replyToCachedNotification(appOrSender, reply)) {
                        "Replied inline to $appOrSender."
                    } else {
                        "No active replyable notification from $appOrSender."
                    }
                }
                "find_phone_siren" -> {
                    PhoneMediaExecutor.triggerFindMyPhoneSiren(context)
                    "Triggered Find-My-Phone siren & flash strobe."
                }
                "open_app" -> PhoneMediaExecutor.openAppByName(context, act.str("app", "name"))
                "open_url" -> {
                    val url = act.str("url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        "Opened link."
                    } else "Skipped invalid URL."
                }
                else -> ""
            }
            if (note.isNotBlank()) results.add(note)
        }
        return results
    }
}
