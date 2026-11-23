package tech.aurikrex.falkeai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

object NotificationChannels {
    const val URGENT_ALARM = "falke_urgent_alarm"
    const val BRIDGE_FG = "falke_bridge_fg"
    const val WAKE_FG = "falke_wake_fg"
    const val CALL_BRIEF = "falke_call_brief"

    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val urgentChannel = NotificationChannel(
            URGENT_ALARM,
            "FalkeAI Urgent Ring-Through Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical WhatsApp, Gmail, and VIP missed-call alerts that ring through DND."
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 800)
            setSound(alarmSound, alarmAttrs)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val bridgeChannel = NotificationChannel(
            BRIDGE_FG,
            "FalkeAI Live Server Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent low-power connection to your FalkeAI Rust brain."
            setShowBadge(false)
        }

        val wakeChannel = NotificationChannel(
            WAKE_FG,
            "FalkeAI Wake-Word Mic",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Local on-device wake-word listening (Hey Falke, Jarvis, Buddy you there)."
            setShowBadge(false)
        }

        val callChannel = NotificationChannel(
            CALL_BRIEF,
            "FalkeAI Caller Brief & Call Notes",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Instant caller context before pickup and 1-tap post-call notes."
            enableVibration(true)
        }

        nm.createNotificationChannels(listOf(urgentChannel, bridgeChannel, wakeChannel, callChannel))
    }
}
