package tech.aurikrex.falkeai.assist

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.ui.FalkeDarkColorScheme
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.JarvisOrb
import tech.aurikrex.falkeai.ui.QuickRoutinePill
import tech.aurikrex.falkeai.ui.ThinkingDotsIndicator
import tech.aurikrex.falkeai.ui.renderMarkdown
import tech.aurikrex.falkeai.voice.VoiceState

/**
 * Gemini / Claude Style Bottom Assistant Sheet launched by:
 * - Power/Home button long-press (Default Digital Assistant)
 * - 24/7 Local Wake Word ("Hey Fall-K", "Jarvis", "Hey Falke")
 * - Post-Call 1-Tap Voice Note notification
 */
class FalkeAssistActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        FalkeOverlayService.setSuppressedForAppForeground(true)
    }

    override fun onPause() {
        FalkeOverlayService.setSuppressedForAppForeground(false)
        super.onPause()
    }

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
                var typedText by remember { mutableStateOf("") }

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
                        color = Color(0xF7080F1E),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        border = BorderStroke(
                            1.5.dp,
                            Brush.horizontalGradient(
                                listOf(FalkePalette.Cyan, FalkePalette.Violet, FalkePalette.EmeraldBright)
                            )
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = false) {}
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
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
                                    Icon(Icons.Default.Bolt, null, tint = FalkePalette.Cyan, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = if (!wakeWord.isNullOrBlank()) "Fall-K • \"$wakeWord\"" else "FalkeAI Assistant",
                                        color = FalkePalette.TextPrimary,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp
                                    )
                                    if (ui.screenVisionAttached) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = "Screen Vision Active",
                                            tint = FalkePalette.EmeraldBright,
                                            modifier = Modifier.size(17.dp)
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

                            // Quick Action Chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickRoutinePill("Ask about screen", Icons.Default.Visibility, FalkePalette.EmeraldBright) {
                                    controller.sendTextCommand("What is on my screen right now?", captureScreen = true)
                                }
                                QuickRoutinePill("Catch me up", Icons.Default.Bolt, FalkePalette.Cyan) {
                                    controller.sendTextCommand("Catch me up", captureScreen = false)
                                }
                                QuickRoutinePill("Lock in", Icons.Default.Lock, FalkePalette.Violet) {
                                    controller.sendTextCommand("Lock in for 90 minutes", captureScreen = false)
                                }
                            }

                            if (ui.state == VoiceState.THINKING) {
                                ThinkingDotsIndicator()
                            } else if (ui.lastTranscript.isNotBlank() || ui.lastReply.isNotBlank()) {
                                Surface(
                                    color = FalkePalette.CardSurface,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, FalkePalette.BorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (ui.lastTranscript.isNotBlank()) {
                                            Text(
                                                text = "You: \"${ui.lastTranscript}\"",
                                                color = FalkePalette.TextSecondary,
                                                fontSize = 12.sp
                                            )
                                        }
                                        if (ui.lastReply.isNotBlank()) {
                                            Text(
                                                text = remember(ui.lastReply) { renderMarkdown(ui.lastReply) },
                                                color = FalkePalette.TextPrimary,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // 6-Second Undo Countdown Bar
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

                            // Unified Gemini-style Input Bar + Orb
                            val submitAssist = {
                                val clean = typedText.trim()
                                if (clean.isNotEmpty()) {
                                    typedText = ""
                                    controller.sendTextCommand(clean, captureScreen = captureScreen)
                                }
                            }
                            Surface(
                                color = FalkePalette.CardElevated,
                                shape = RoundedCornerShape(99.dp),
                                border = BorderStroke(1.dp, FalkePalette.BorderCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    JarvisOrb(
                                        state = ui.state,
                                        orbSize = 44.dp,
                                        onClick = {
                                            if (ui.state != VoiceState.IDLE) {
                                                controller.stopSession()
                                            } else {
                                                controller.startVoiceTurn(captureScreen = captureScreen, followUpChain = true)
                                            }
                                        }
                                    )
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (typedText.isEmpty()) {
                                            Text(
                                                text = ui.statusLine.ifBlank { "Speak or type to Fall-K…" },
                                                color = FalkePalette.TextMuted,
                                                fontSize = 13.sp,
                                                maxLines = 1
                                            )
                                        }
                                        BasicTextField(
                                            value = typedText,
                                            onValueChange = { typedText = it },
                                            textStyle = TextStyle(color = FalkePalette.TextPrimary, fontSize = 14.sp),
                                            cursorBrush = SolidColor(FalkePalette.Cyan),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                            keyboardActions = KeyboardActions(onSend = { submitAssist() }),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    val hasText = typedText.trim().isNotEmpty()
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(if (hasText) FalkePalette.Cyan else FalkePalette.CardSurface)
                                            .clickable {
                                                if (hasText) submitAssist()
                                                else if (ui.state != VoiceState.IDLE) controller.stopSession()
                                                else controller.startVoiceTurn(captureScreen = captureScreen, followUpChain = true)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                                            contentDescription = "Send",
                                            tint = if (hasText) Color(0xFF03111F) else FalkePalette.Cyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
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
