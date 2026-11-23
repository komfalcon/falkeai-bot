package tech.aurikrex.falkeai.assist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.voice.VoiceState

/**
 * Floating Translucent J.A.R.V.I.S. Assistant Overlay launched by:
 * - Power/Home button long-press (Default Digital Assistant)
 * - 24/7 Local Wake Word ("Hey Falke", "Jarvis", "Hey Jarvis", "FalkeAI")
 * - Post-Call 1-Tap Voice Note notification
 */
class FalkeAssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val controller = FalkeApplication.instance.voiceController
        val autoListen = intent.getBooleanExtra(EXTRA_AUTO_LISTEN, true)
        val captureScreen = intent.getBooleanExtra(EXTRA_CAPTURE_SCREEN, false)
        val wakeWord = intent.getStringExtra(EXTRA_WAKE_WORD)
        val callNotePhone = intent.getStringExtra(EXTRA_CALL_NOTE_PHONE)
        val callNoteName = intent.getStringExtra(EXTRA_CALL_NOTE_NAME)

        if (autoListen || callNotePhone != null) {
            controller.startVoiceTurn(
                captureScreen = captureScreen,
                followUpChain = callNotePhone == null,
                postCallPhone = callNotePhone,
                postCallName = callNoteName
            )
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by controller.uiState.collectAsState()

                Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        controller.stopSession()
                        finish()
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    color = Color(0xF20C1426),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (!wakeWord.isNullOrBlank()) "FalkeAI • \"$wakeWord\"" else "FalkeAI Assistant",
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                if (ui.screenVisionAttached) {
                                    Icon(
                                        Icons.Default.Visibility,
                                        contentDescription = "Screen Vision Active",
                                        tint = Color(0xFF34D399),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                controller.stopSession()
                                finish()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                            }
                        }

                        // Glowing Orb Button
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = when (ui.state) {
                                            VoiceState.LISTENING, VoiceState.FOLLOW_UP_WINDOW ->
                                                listOf(Color(0xFF38BDF8), Color(0xFF0284C7))
                                            VoiceState.THINKING ->
                                                listOf(Color(0xFFA855F7), Color(0xFF6D28D9))
                                            VoiceState.SPEAKING ->
                                                listOf(Color(0xFF10B981), Color(0xFF047857))
                                            VoiceState.IDLE ->
                                                listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                                        }
                                    )
                                )
                                .clickable {
                                    controller.startVoiceTurn(captureScreen = captureScreen, followUpChain = true)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Speak",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Text(
                            text = ui.statusLine,
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )

                        if (ui.lastTranscript.isNotBlank()) {
                            Text(
                                text = "You: \"${ui.lastTranscript}\"",
                                color = Color(0xFFE2E8F0),
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (ui.lastReply.isNotBlank()) {
                            Text(
                                text = ui.lastReply,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Feature J: 6-Second Undo Countdown Bar
                        ui.pendingUndo?.let { pending ->
                            Surface(
                                color = Color(0xFF451A03),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${pending.summary} (${ui.undoSecondsLeft}s)",
                                        color = Color(0xFFFDE68A),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { controller.cancelPendingUndo() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                                    ) {
                                        Text("UNDO", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            }
        }
    }

    companion object {
        const val EXTRA_WAKE_WORD = "wake_word"
        const val EXTRA_AUTO_LISTEN = "auto_listen"
        const val EXTRA_CAPTURE_SCREEN = "capture_screen"
        const val EXTRA_CALL_NOTE_PHONE = "call_note_phone"
        const val EXTRA_CALL_NOTE_NAME = "call_note_name"
    }
}
