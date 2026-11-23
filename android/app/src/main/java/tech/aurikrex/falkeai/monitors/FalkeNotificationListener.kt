package tech.aurikrex.falkeai.monitors

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.data.BankAlertPayload
import tech.aurikrex.falkeai.data.CapturedNotificationEntity
import tech.aurikrex.falkeai.data.MissedCallEscalation
import tech.aurikrex.falkeai.data.TelemetryRequest
import java.util.concurrent.ConcurrentHashMap

data class ReplyableNotifEntry(
    val senderKey: String,
    val appLabel: String,
    val action: Notification.Action,
    val remoteInput: RemoteInput,
    val postedAtMs: Long
)

/**
 * All-App Notification Reader + Local PrivacyRedactor + Nigerian Bank Alert Parser +
 * Inline RemoteInput Voice Reply + Bluetooth Earbuds Hands-Free Readout (Feature D) +
 * Repeated Missed-Call Escalation.
 */
class FalkeNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val shortText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val rawBody = bigText.ifBlank { shortText }
        if (title.isBlank() && rawBody.isBlank()) return

        // Strictly scrub OTPs, PINs, BVN, CVV, passwords ON-DEVICE first!
        val redactedTitle = PrivacyRedactor.redact(title)
        val redactedBody = PrivacyRedactor.redact(rawBody)
        val appLabel = resolveAppLabel(pkg)
        val isSocial = pkg in SOCIAL_PACKAGES
        val nowMs = System.currentTimeMillis()

        // Cache RemoteInput inline-reply action if present (for voice "Reply to Chidi...")
        notif.actions?.forEach { act ->
            val ri = act.remoteInputs?.firstOrNull()
            if (ri != null && act.actionIntent != null) {
                replyableCache["${appLabel.lowercase()}:${redactedTitle.lowercase()}"] =
                    ReplyableNotifEntry(redactedTitle, appLabel, act, ri, nowMs)
            }
        }

        val app = FalkeApplication.instance
        scope.launch {
            runCatching {
                app.db.notificationDao().insert(
                    CapturedNotificationEntity(
                        packageName = pkg,
                        appLabel = appLabel,
                        title = redactedTitle.take(120),
                        bodyRedacted = redactedBody.take(360),
                        isSocial = isSocial,
                        postedAtMs = nowMs
                    )
                )
                app.db.notificationDao().pruneOld()
            }

            // Parse bank / Paystack / Stripe financial alerts (Feature #4)
            val bankAlert: BankAlertPayload? = BankAlertParser.parse(pkg, appLabel, title, rawBody, nowMs)

            // Detect repeated missed calls (2+ within 10 minutes -> escalate to full-screen alarm)
            val missedEscalation = detectRepeatedMissedCall(pkg, redactedTitle, redactedBody, nowMs)

            if (bankAlert != null || missedEscalation != null) {
                val state = BluetoothAndBatteryMonitor.snapshot(this@FalkeNotificationListener)
                val recent = runCatching {
                    app.db.notificationDao().getRecent(10).map { "${it.appLabel}: ${it.title} — ${it.bodyRedacted.take(80)}" }
                }.getOrDefault(emptyList())
                app.api.sendTelemetry(
                    TelemetryRequest(
                        batteryPct = state.batteryPct,
                        isCharging = state.isCharging,
                        dndActive = state.dndActive,
                        btConnected = state.btConnected,
                        recentNotifications = recent,
                        bankAlerts = listOfNotNull(bankAlert),
                        missedCallUrgent = missedEscalation
                    )
                )
            }

            // Feature D: Bluetooth Earbuds Hands-Free Commute Readout
            if (app.prefs.btAutoReadoutEnabled &&
                BluetoothAndBatteryMonitor.isBluetoothAudioConnected(this@FalkeNotificationListener) &&
                !PrivacyRedactor.containsSensitiveOtpOrPin(rawBody) &&
                (bankAlert != null || isSocial || pkg == "com.whatsapp" || pkg == "com.google.android.gm")
            ) {
                val spoken = if (bankAlert != null) {
                    "Bank alert: ${bankAlert.rawSummary}"
                } else {
                    "New $appLabel from $redactedTitle: ${redactedBody.take(110)}"
                }
                app.voiceController.speakQuickAnnouncement(spoken)
            }
        }
    }

    private fun detectRepeatedMissedCall(
        pkg: String,
        title: String,
        body: String,
        nowMs: Long
    ): MissedCallEscalation? {
        val lower = "$title $body".lowercase()
        if (!lower.contains("missed call") && !pkg.contains("dialer") && !pkg.contains("telecom")) {
            return null
        }
        if (!lower.contains("missed")) return null
        val key = title.ifBlank { body }.take(40)
        val stamps = missedCallTracker.getOrPut(key) { mutableListOf() }
        synchronized(stamps) {
            stamps.removeAll { nowMs - it > 10 * 60_000L }
            stamps.add(nowMs)
            if (stamps.size >= 2) {
                val cnt = stamps.size
                stamps.clear()
                return MissedCallEscalation(caller = key, phone = key, count = cnt)
            }
        }
        return null
    }

    private fun resolveAppLabel(pkg: String): String = runCatching {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    }.getOrElse { pkg.substringAfterLast('.') }

    companion object {
        private val SOCIAL_PACKAGES = setOf(
            "com.twitter.android",
            "com.instagram.android",
            "com.linkedin.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.reddit.frontpage",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.whatsapp.w4b"
        )

        private val replyableCache = ConcurrentHashMap<String, ReplyableNotifEntry>()
        private val missedCallTracker = ConcurrentHashMap<String, MutableList<Long>>()

        fun replyToCachedNotification(senderOrAppQuery: String, message: String): Boolean {
            val q = senderOrAppQuery.trim().lowercase()
            val entry = replyableCache.values
                .sortedByDescending { it.postedAtMs }
                .firstOrNull {
                    it.senderKey.lowercase().contains(q) || it.appLabel.lowercase().contains(q)
                } ?: return false

            return runCatching {
                val fill = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(entry.remoteInput.resultKey, message)
                }
                RemoteInput.addResultsToIntent(arrayOf(entry.remoteInput), fill, bundle)
                entry.action.actionIntent.send(FalkeApplication.instance, 0, fill)
                true
            }.getOrDefault(false)
        }
    }
}
