package tech.aurikrex.falkeai.executors

sealed class LocalDeviceCommand {
    data class CallContact(val target: String) : LocalDeviceCommand()
    data class SendSms(val target: String, val message: String) : LocalDeviceCommand()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : LocalDeviceCommand()
    data class DeleteAlarm(val query: String) : LocalDeviceCommand()
    data class EditAlarm(val oldQuery: String, val newHour: Int, val newMinute: Int) : LocalDeviceCommand()
    data object ListAlarms : LocalDeviceCommand()
    data class SetTimer(val seconds: Int, val label: String) : LocalDeviceCommand()
    data class SetDnd(val enabled: Boolean, val mode: String) : LocalDeviceCommand()
    data class ToggleFlashlight(val on: Boolean) : LocalDeviceCommand()
    data class SpotifyPlay(val query: String) : LocalDeviceCommand()
    data class MediaControl(val action: String) : LocalDeviceCommand()
    data object FindPhoneSiren : LocalDeviceCommand()
    data class DictateText(val text: String) : LocalDeviceCommand()
}

/**
 * <80ms Deterministic Offline Cellular Command Router (Feature M).
 * Works with 0 MB mobile data for Calls, SMS, Alarms, Timers, DND, Flashlight,
 * Spotify/Media controls, Dictation, and "Buddy you there?" Find-My-Phone.
 */
object LocalCommandRouter {
    private val TIME_RE = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)
    private val TIMER_RE = Regex(
        """(?i)(?:set|start)\s+(?:a\s+)?timer\s+(?:for\s+)?(\d+)\s*(hour|hr|hrs|hours|minute|min|mins|minutes|second|sec|secs|seconds)""",
    )
    private val CALL_RE = Regex("""(?i)^(?:please\s+)?(?:call|dial|phone|ring)\s+(.+?)$""")
    private val SMS_RE = Regex("""(?i)^(?:please\s+)?(?:send\s+(?:an?\s+)?sms\s+to|text|sms)\s+([a-zA-Z0-9+\s'-]{2,32}?)\s*(?:saying|that|:|-)\s*(.+)$""")
    private val SET_ALARM_RE = Regex("""(?i)(?:set|create|add|wake\s+me\s+up)\s+(?:an?\s+)?(?:alarm\s+)?(?:for|at)\s+(.+)""")
    private val DEL_ALARM_RE = Regex("""(?i)(?:delete|cancel|remove|turn\s+off)\s+(?:my\s+|the\s+)?(.+?)\s*alarm|(?i)(?:delete|cancel|remove)\s+alarm\s*(.*)""")
    private val EDIT_ALARM_RE = Regex("""(?i)(?:change|edit|move|reschedule)\s+(?:my\s+|the\s+)?(.+?)\s+alarm\s+to\s+(.+)""")
    private val PLAY_SPOTIFY_RE = Regex("""(?i)^(?:play)\s+(.+?)(?:\s+on\s+spotify)?$""")
    private val TYPE_THIS_RE = Regex("""(?i)^(?:type\s+this|dictate)\s*[:,-]?\s+(.+)$""")

    fun parseTime24(raw: String): Pair<Int, Int>? {
        val m = TIME_RE.find(raw.trim()) ?: return null
        val hRaw = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val meridiem = m.groupValues[3].lowercase()
        if (min !in 0..59) return null
        val hour = when (meridiem) {
            "am" -> {
                if (hRaw !in 1..12) return null
                if (hRaw == 12) 0 else hRaw
            }
            "pm" -> {
                if (hRaw !in 1..12) return null
                if (hRaw == 12) 12 else hRaw + 12
            }
            else -> {
                if (hRaw !in 0..23) return null
                hRaw
            }
        }
        return hour to min
    }

    fun route(rawTranscript: String): LocalDeviceCommand? {
        val text = rawTranscript.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()

        // Find-My-Phone ("Buddy you there?", "where is my phone", "ring my phone")
        if (lower.contains("buddy you there") || lower.contains("buddy, you there") ||
            lower.contains("where is my phone") || lower.contains("ring my phone")
        ) {
            return LocalDeviceCommand.FindPhoneSiren
        }

        // Cross-app dictation ("Type this: ...")
        TYPE_THIS_RE.find(text)?.let { m ->
            val payload = m.groupValues[1].trim()
            if (payload.isNotEmpty()) return LocalDeviceCommand.DictateText(payload)
        }

        // Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop")
            return LocalDeviceCommand.ToggleFlashlight(on = !turnOff)
        }

        // Do Not Disturb
        if (lower.contains("do not disturb") || lower.contains(" dnd") || lower.startsWith("dnd")) {
            val turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("turn off")
            return LocalDeviceCommand.SetDnd(enabled = !turnOff, mode = if (turnOff) "off" else "priority")
        }

        // Media transport controls
        if (lower in setOf("pause music", "stop music", "pause spotify", "pause")) {
            return LocalDeviceCommand.MediaControl("pause")
        }
        if (lower in setOf("resume music", "resume spotify", "continue music")) {
            return LocalDeviceCommand.MediaControl("play")
        }
        if (lower in setOf("next song", "next track", "skip song", "skip track")) {
            return LocalDeviceCommand.MediaControl("next")
        }
        if (lower in setOf("previous song", "previous track", "last song")) {
            return LocalDeviceCommand.MediaControl("previous")
        }

        // Timers
        TIMER_RE.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2].lowercase()
            val secs = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            if (secs > 0) return LocalDeviceCommand.SetTimer(secs, "FalkeAI Timer")
        }

        // Edit Alarm
        EDIT_ALARM_RE.find(text)?.let { m ->
            val oldQ = m.groupValues[1].trim()
            val newTime = parseTime24(m.groupValues[2])
            if (newTime != null) {
                return LocalDeviceCommand.EditAlarm(oldQ, newTime.first, newTime.second)
            }
        }

        // Delete Alarm
        if (lower.contains("alarm") && (lower.contains("delete") || lower.contains("cancel") || lower.contains("remove"))) {
            DEL_ALARM_RE.find(text)?.let { m ->
                val q = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                return LocalDeviceCommand.DeleteAlarm(q.ifBlank { "next" })
            }
        }

        // List Alarms
        if (lower.contains("list alarms") || lower.contains("my alarms") || lower.contains("what alarms")) {
            return LocalDeviceCommand.ListAlarms
        }

        // Set Alarm
        if (lower.contains("alarm") || lower.startsWith("wake me up")) {
            SET_ALARM_RE.find(text)?.let { m ->
                val rest = m.groupValues[1]
                val parsed = parseTime24(rest)
                if (parsed != null) {
                    val label = rest.replace(TIME_RE, "")
                        .replace(Regex("(?i)\\b(for|to|called|labeled|named|alarm)\\b"), "")
                        .trim()
                        .ifBlank { "FalkeAI Alarm" }
                    return LocalDeviceCommand.SetAlarm(parsed.first, parsed.second, label)
                }
            }
        }

        // Send SMS (offline cellular SmsManager)
        SMS_RE.find(text)?.let { m ->
            val target = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
            if (target.isNotEmpty() && body.isNotEmpty() && !lower.contains("whatsapp")) {
                return LocalDeviceCommand.SendSms(target, body)
            }
        }

        // Direct Call
        CALL_RE.find(text)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty() && !target.lowercase().contains("me back")) {
                return LocalDeviceCommand.CallContact(target)
            }
        }

        // Play on Spotify
        PLAY_SPOTIFY_RE.find(text)?.let { m ->
            val query = m.groupValues[1].trim()
            if (query.isNotEmpty() && (lower.contains("spotify") || lower.contains("music") || lower.contains("song"))) {
                return LocalDeviceCommand.SpotifyPlay(query)
            }
        }

        return null
    }
}
