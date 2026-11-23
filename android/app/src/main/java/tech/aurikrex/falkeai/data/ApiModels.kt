package tech.aurikrex.falkeai.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PairRequest(
    val code: String,
    @SerialName("device_name") val deviceName: String
)

@Serializable
data class PairResponse(
    val ok: Boolean = false,
    @SerialName("device_id") val deviceId: Long = 0,
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("device_token") val deviceToken: String = "",
    @SerialName("server_time_ms") val serverTimeMs: Long = 0,
    val error: String? = null
)

@Serializable
data class DeviceContextPayload(
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("is_charging") val isCharging: Boolean? = null,
    @SerialName("dnd_active") val dndActive: Boolean? = null,
    @SerialName("bt_connected") val btConnected: Boolean? = null,
    @SerialName("calendar_upcoming") val calendarUpcoming: List<String> = emptyList(),
    @SerialName("recent_notifications") val recentNotifications: List<String> = emptyList(),
    @SerialName("active_alarms") val activeAlarms: List<String> = emptyList(),
    @SerialName("screen_text") val screenText: String? = null
)

@Serializable
data class PhoneChatRequest(
    val text: String? = null,
    @SerialName("audio_base64") val audioBase64: String? = null,
    @SerialName("audio_mime") val audioMime: String? = null,
    @SerialName("screenshot_base64") val screenshotBase64: String? = null,
    @SerialName("screenshot_mime") val screenshotMime: String? = null,
    val multilingual: Boolean = true,
    @SerialName("want_tts") val wantTts: Boolean = true,
    @SerialName("device_context") val deviceContext: DeviceContextPayload = DeviceContextPayload()
)

@Serializable
data class PhoneChatResponse(
    val ok: Boolean = false,
    val transcript: String = "",
    @SerialName("reply_text") val replyText: String = "",
    @SerialName("cloud_actions_done") val cloudActionsDone: List<String> = emptyList(),
    @SerialName("device_actions") val deviceActions: List<JsonObject> = emptyList(),
    @SerialName("whatsapp_outbounds_sent") val whatsappOutboundsSent: Int = 0,
    @SerialName("tts_audio_base64") val ttsAudioBase64: String? = null,
    @SerialName("tts_mime") val ttsMime: String? = null,
    @SerialName("tts_seconds") val ttsSeconds: Int? = null,
    val error: String? = null
)

@Serializable
data class BankAlertPayload(
    val source: String = "android",
    val bank: String,
    val direction: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String = "NGN",
    val counterparty: String,
    @SerialName("raw_summary") val rawSummary: String,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class MissedCallEscalation(
    val caller: String,
    val phone: String,
    val count: Int
)

@Serializable
data class TelemetryRequest(
    @SerialName("battery_pct") val batteryPct: Int,
    @SerialName("is_charging") val isCharging: Boolean,
    @SerialName("dnd_active") val dndActive: Boolean,
    @SerialName("bt_connected") val btConnected: Boolean,
    @SerialName("battery_critical") val batteryCritical: Boolean = false,
    @SerialName("calendar_upcoming") val calendarUpcoming: List<String> = emptyList(),
    @SerialName("recent_notifications") val recentNotifications: List<String> = emptyList(),
    @SerialName("bank_alerts") val bankAlerts: List<BankAlertPayload> = emptyList(),
    @SerialName("missed_call_urgent") val missedCallUrgent: MissedCallEscalation? = null
)

@Serializable
data class CallerBriefResponse(
    val phone: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val relationship: String? = null,
    val tier: String = "normal",
    @SerialName("summary_line") val summaryLine: String = "",
    @SerialName("open_followups") val openFollowups: List<String> = emptyList(),
    val bookings: List<String> = emptyList(),
    @SerialName("last_chat_snippet") val lastChatSnippet: String? = null
)

@Serializable
data class CallNoteRequest(
    val phone: String,
    @SerialName("contact_name") val contactName: String? = null,
    val text: String? = null,
    @SerialName("audio_base64") val audioBase64: String? = null,
    @SerialName("audio_mime") val audioMime: String? = null,
    @SerialName("create_followup") val createFollowup: Boolean = false
)

@Serializable
data class AckRequest(
    val target: String? = null,
    @SerialName("quick_reply") val quickReply: String? = null
)

@Serializable
data class AvailabilityDto(
    val mode: String = "available",
    @SerialName("until_ms") val untilMs: Long = 0,
    val note: String? = null
)

@Serializable
data class UrgentAlertDto(
    val id: Long = 0,
    val sender: String = "",
    @SerialName("contact_name") val contactName: String? = null,
    val summary: String = "",
    val request: String = "",
    val deadline: String = ""
)

@Serializable
data class EmailItemDto(
    @SerialName("msg_id") val msgId: String = "",
    @SerialName("account_label") val accountLabel: String = "",
    val sender: String = "",
    val subject: String = "",
    val snippet: String = "",
    val category: String = "",
    val priority: String = ""
)

@Serializable
data class FollowupDto(
    val id: Long = 0,
    val sender: String = "",
    @SerialName("contact_name") val contactName: String? = null,
    val reason: String = "",
    @SerialName("suggested_reply") val suggestedReply: String = "",
    val status: String = ""
)

@Serializable
data class FlagDto(
    val chat: String = "",
    val name: String? = null,
    val reason: String = "",
    @SerialName("last_message") val lastMessage: String = ""
)

@Serializable
data class ScheduledMsgDto(
    val id: Long = 0,
    val target: String = "",
    @SerialName("contact_name") val contactName: String? = null,
    val channel: String = "whatsapp",
    val message: String = "",
    @SerialName("due_at") val dueAt: Long = 0
)

@Serializable
data class BankAlertDto(
    val id: Long = 0,
    val source: String = "",
    val bank: String = "",
    val direction: String = "credit",
    @SerialName("amount_minor") val amountMinor: Long = 0,
    @SerialName("amount_formatted") val amountFormatted: String = "",
    val currency: String = "NGN",
    val counterparty: String = "",
    @SerialName("raw_summary") val rawSummary: String = "",
    @SerialName("created_at") val createdAt: Long = 0
)

@Serializable
data class BookingDto(
    val name: String = "",
    val subject: String = "",
    val exam: String = "",
    val slot: String = "",
    val number: String = ""
)

@Serializable
data class DashboardResponse(
    @SerialName("server_time_ms") val serverTimeMs: Long = 0,
    val availability: AvailabilityDto = AvailabilityDto(),
    @SerialName("low_battery_cover") val lowBatteryCover: Boolean = false,
    @SerialName("catch_up_summary") val catchUpSummary: String = "",
    @SerialName("urgent_alerts") val urgentAlerts: List<UrgentAlertDto> = emptyList(),
    @SerialName("priority_emails") val priorityEmails: List<EmailItemDto> = emptyList(),
    val followups: List<FollowupDto> = emptyList(),
    @SerialName("pending_flags") val pendingFlags: List<FlagDto> = emptyList(),
    @SerialName("scheduled_messages") val scheduledMessages: List<ScheduledMsgDto> = emptyList(),
    @SerialName("bank_alerts") val bankAlerts: List<BankAlertDto> = emptyList(),
    val bookings: List<BookingDto> = emptyList()
)
