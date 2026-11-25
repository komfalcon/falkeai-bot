package tech.aurikrex.falkeai.assist

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.ui.FalkeDarkColorScheme
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.JarvisOrb
import tech.aurikrex.falkeai.ui.renderMarkdown
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

        val app = FalkeApplication.instance
        val controller = app.voiceController
        val isTrustedSystemAssist = intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND
        val isTrustedInternal = intent?.getStringExtra(EXTRA_INTERNAL_TOKEN) == app.internalLaunchToken
        val trustedLaunch = isTrustedSystemAssist || isTrustedInternal

        val autoListen = trustedLaunch && intent.getBooleanExtra(EXTRA_AUTO_LISTEN, true)
        val captureScreen = trustedLaunch && (isTrustedSystemAssist || intent.getBooleanExtra(EXTRA_CAPTURE_SCREEN, false))
        val wakeWord = if (trustedLaunch) intent.getStringExtra(EXTRA_WAKE_WORD) else null
        val callNotePhone = if (isTrustedInternal) intent.getStringExtra(EXTRA_CALL_NOTE_PHONE) else null
        val callNoteName = if (isTrustedInternal) intent.getStringExtra(EXTRA_CALL_NOTE_NAME) else null

        if (autoListen || callNotePhone != null) {
            controller.startVoiceTurn(
                captureScreen = captureScreen,
                followUpChain = callNotePhone == null,
                postCallPhone = callNotePhone,
                postCallName = callNoteName
            )
        }

        setContent {
            MaterialTheme(colorScheme = FalkeDarkColorScheme) {
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
                        color = Color(0xF5091020),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        border = BorderStroke(1.dp, FalkePalette.BorderCyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = false) {}
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
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
                                        color = FalkePalette.Cyan,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp
                                    )
                                    if (ui.screenVisionAttached) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = "Screen Vision Active",
                                            tint = FalkePalette.EmeraldBright,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    controller.stopSession()
                                    finish()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = FalkePalette.TextSecondary)
                                }
                            }

                            JarvisOrb(
                                state = ui.state,
                                orbSize = 76.dp,
                                onClick = {
                                    if (ui.state != VoiceState.IDLE) {
                                        controller.stopSession()
                                    } else {
                                        controller.startVoiceTurn(captureScreen = captureScreen, followUpChain = true)
                                    }
                                }
                            )

                            Text(
                                text = ui.statusLine,
                                color = FalkePalette.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )

                            if (ui.lastTranscript.isNotBlank() || ui.lastReply.isNotBlank()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (ui.lastTranscript.isNotBlank()) {
                                        Text(
                                            text = "You: \"${ui.lastTranscript}\"",
                                            color = FalkePalette.TextSecondary,
                                            fontSize = 13.sp
                                        )
                                    }
                                    if (ui.lastReply.isNotBlank()) {
                                        Text(
                                            text = remember(ui.lastReply) { renderMarkdown(ui.lastReply) },
                                            color = FalkePalette.TextPrimary,
                                            fontSize = 15.sp,
                                            lineHeight = 21.sp
                                        )
                                    }
                                }
                            }

                            // Feature J: 6-Second Undo Countdown Bar
                            ui.pendingUndo?.let { pending ->
                                Surface(
                                    color = FalkePalette.CardAmber,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, FalkePalette.Amber.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${pending.summary} (${ui.undoSecondsLeft}s)",
                                            color = FalkePalette.AmberSoft,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = { controller.cancelPendingUndo() },
                                            colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Crimson),
                                            shape = RoundedCornerShape(99.dp)
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
        const val EXTRA_INTERNAL_TOKEN = "internal_launch_token"
    }
}
