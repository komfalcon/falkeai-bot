package tech.aurikrex.falkeai.monitors

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.executors.FalkeAlarmManager

/**
 * Real Android Lockscreen Urgent Alarm (bypasses Silent, DND & Locked Screen via USAGE_ALARM +
 * STREAM_ALARM + FullScreenIntent). Includes 1-tap Acknowledge, Snooze 5m, and Quick Reply!
 */
class UrgentAlarmActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var prevAlarmVol: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockscreenWake()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "🚨 FalkeAI Urgent Alert"
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "Needs your immediate attention."
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "whatsapp"
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: ""

        startAlarmSoundAndVibration()
        FalkeApplication.instance.voiceController.speakQuickAnnouncement("$title. $summary")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                UrgentAlarmScreen(
                    title = title,
                    summary = summary,
                    showQuickReply = source == "whatsapp" && sender.isNotBlank(),
                    onAcknowledge = { quickReply ->
                        stopAlarmSoundAndVibration()
                        dismissUrgentNotification()
                        CoroutineScope(Dispatchers.IO).launch {
                            FalkeApplication.instance.api.acknowledgeAlert(
                                target = sender.ifBlank { null },
                                quickReply = quickReply?.takeIf { it.isNotBlank() }
                            )
                        }
                        finish()
                    },
                    onSnooze = {
                        stopAlarmSoundAndVibration()
                        dismissUrgentNotification()
                        CoroutineScope(Dispatchers.IO).launch {
                            val cal = java.util.Calendar.getInstance().apply {
                                add(java.util.Calendar.MINUTE, 5)
                            }
                            FalkeAlarmManager.createAlarm(
                                this@UrgentAlarmActivity,
                                FalkeApplication.instance.db,
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE),
                                "Snoozed: ${summary.take(40)}"
                            )
                        }
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        stopAlarmSoundAndVibration()
        super.onDestroy()
    }

    private fun enableLockscreenWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startAlarmSoundAndVibration() {
        val am = getSystemService(AudioManager::class.java)
        if (am != null) {
            prevAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * 0.9f).toInt().coerceAtLeast(1), 0) }
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@UrgentAlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        runCatching {
            val pattern = longArrayOf(0, 700, 350, 700, 350)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopAlarmSoundAndVibration() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }
        runCatching { vibrator?.cancel() }
        if (prevAlarmVol >= 0) {
            val am = getSystemService(AudioManager::class.java)
            runCatching { am?.setStreamVolume(AudioManager.STREAM_ALARM, prevAlarmVol, 0) }
            prevAlarmVol = -1
        }
    }

    private fun dismissUrgentNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(FalkeBridgeService.URGENT_NOTIF_ID)
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUMMARY = "summary"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SENDER = "sender"

        fun buildIntent(
            context: Context,
            title: String,
            summary: String,
            source: String,
            sender: String
        ): Intent = Intent(context, UrgentAlarmActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SUMMARY, summary)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_SENDER, sender)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}

@Composable
private fun UrgentAlarmScreen(
    title: String,
    summary: String,
    showQuickReply: Boolean,
    onAcknowledge: (String?) -> Unit,
    onSnooze: () -> Unit
) {
    var quickReply by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14070B))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "FALKEAI PRIORITY ALARM",
                color = Color(0xFFF43F5E),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = summary,
                color = Color(0xFFE2E8F0),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            if (showQuickReply) {
                OutlinedTextField(
                    value = quickReply,
                    onValueChange = { quickReply = it },
                    placeholder = { Text("Optional quick WhatsApp reply…", color = Color(0xFF94A3B8)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onAcknowledge(quickReply.ifBlank { null }) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
            ) {
                Text(
                    text = if (quickReply.isNotBlank()) "ACKNOWLEDGE & SEND REPLY" else "ACKNOWLEDGE & SILENCE",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onSnooze,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) {
                    Text("SNOOZE 5 MIN", color = Color(0xFFFDE047), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
