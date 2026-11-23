package tech.aurikrex.falkeai.monitors

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.NotificationChannels
import tech.aurikrex.falkeai.R
import tech.aurikrex.falkeai.assist.FalkeAssistActivity
import tech.aurikrex.falkeai.data.CachedCallerEntity
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor

/**
 * Features A & B:
 * - Feature A (Caller Context Pre-Brief): On `CALL_STATE_RINGING`, queries local Room cache +
 *   fast `/api/v1/phone/caller-brief` and surfaces a heads-up context card (plus whispers into
 *   Bluetooth earbuds if worn!).
 * - Feature B (Post-Call 1-Tap Voice Note): When an active call ends (`OFFHOOK -> IDLE`),
 *   shows a 1-tap notification to log a quick voice note into Turso `contact_memory` & Follow-Up Queue.
 */
class PhoneCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty().trim()

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (rawNumber.isNotEmpty()) {
                    lastActivePhone = PhoneMediaExecutor.normalizeDialNumber(rawNumber)
                    handleIncomingRing(context, lastActivePhone)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                wasInCall = true
                if (rawNumber.isNotEmpty()) {
                    lastActivePhone = PhoneMediaExecutor.normalizeDialNumber(rawNumber)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (wasInCall && lastActivePhone.isNotEmpty()) {
                    showPostCallNoteNotification(context, lastActivePhone, lastCallerName)
                }
                wasInCall = false
            }
        }
    }

    private fun handleIncomingRing(context: Context, phone: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = FalkeApplication.instance
                val localContact = PhoneMediaExecutor.resolveContact(context, phone)
                var name = localContact?.displayName ?: phone
                var summary = app.db.callerDao().getByPhone(phone)?.summaryLine.orEmpty()

                if (app.prefs.isPaired()) {
                    app.api.fetchCallerBrief(phone).onSuccess { brief ->
                        name = brief.displayName ?: name
                        summary = brief.summaryLine
                        app.db.callerDao().upsert(
                            CachedCallerEntity(
                                phone = phone,
                                displayName = brief.displayName,
                                relationship = brief.relationship,
                                tier = brief.tier,
                                summaryLine = brief.summaryLine
                            )
                        )
                    }
                }
                lastCallerName = name
                if (summary.isNotBlank()) {
                    showCallerBriefNotification(context, name, summary)
                    if (app.prefs.btAutoReadoutEnabled &&
                        BluetoothAndBatteryMonitor.isBluetoothAudioConnected(context)
                    ) {
                        app.voiceController.speakQuickAnnouncement("Incoming call from $name. $summary")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showCallerBriefNotification(context: Context, callerName: String, summary: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(context, NotificationChannels.CALL_BRIEF)
            .setSmallIcon(R.drawable.ic_falke_bolt)
            .setContentTitle("📞 $callerName")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_CALLER_BRIEF, notif)
    }

    private fun showPostCallNoteNotification(context: Context, phone: String, name: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val noteIntent = Intent(context, FalkeAssistActivity::class.java).apply {
            putExtra(FalkeAssistActivity.EXTRA_CALL_NOTE_PHONE, phone)
            putExtra(FalkeAssistActivity.EXTRA_CALL_NOTE_NAME, name.ifBlank { phone })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIF_POST_CALL,
            noteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, NotificationChannels.CALL_BRIEF)
            .setSmallIcon(R.drawable.ic_falke_bolt)
            .setContentTitle("🎙️ Log voice note for ${name.ifBlank { phone }}?")
            .setContentText("Tap to speak a 5-second summary into FalkeAI memory & follow-ups.")
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_POST_CALL, notif)
    }

    companion object {
        @Volatile
        private var wasInCall = false

        @Volatile
        private var lastActivePhone = ""

        @Volatile
        private var lastCallerName = ""

        private const val NOTIF_CALLER_BRIEF = 4101
        private const val NOTIF_POST_CALL = 4102
    }
}
