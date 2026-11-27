package tech.aurikrex.falkeai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.JarvisOrb
import tech.aurikrex.falkeai.ui.LocalSettingsSnapshot
import tech.aurikrex.falkeai.ui.MarkdownFormatter
import tech.aurikrex.falkeai.ui.QuickRoutinePill
import tech.aurikrex.falkeai.ui.StatusPill
import tech.aurikrex.falkeai.ui.ThinkingDotsIndicator
import tech.aurikrex.falkeai.ui.renderMarkdown
import tech.aurikrex.falkeai.voice.ChatMessage
import tech.aurikrex.falkeai.voice.CustomVoiceStatus
import tech.aurikrex.falkeai.voice.VoiceState
import tech.aurikrex.falkeai.voice.VoiceUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    voiceUi: VoiceUiState,
    settings: LocalSettingsSnapshot,
    customVoice: CustomVoiceStatus,
    wakeRunning: Boolean,
    onTapOrb: (Boolean) -> Unit,
    onStopSession: () -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onUnlockVoiceBypassWithPin: () -> Unit,
    onReplayMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    onStartCommuteBriefing: () -> Unit,
    onCancelUndo: () -> Unit,
    onTriggerFindPhone: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var attachScreen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val hasMessages = voiceUi.chatHistory.isNotEmpty()
    val isActive = voiceUi.state != VoiceState.IDLE

    LaunchedEffect(voiceUi.chatHistory.size, voiceUi.state) {
        val total = voiceUi.chatHistory.size + if (voiceUi.state == VoiceState.THINKING) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compact Top Voice Bar when chatting
        if (hasMessages) {
            Surface(
                color = FalkePalette.CardSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, if (isActive) FalkePalette.BorderCyan else FalkePalette.BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    JarvisOrb(
                        state = voiceUi.state,
                        orbSize = 44.dp,
                        onClick = { onTapOrb(attachScreen) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (voiceUi.state) {
                                VoiceState.LISTENING -> "Listening…"
                                VoiceState.FOLLOW_UP_WINDOW -> "Listening for follow-up…"
                                VoiceState.THINKING -> "Thinking…"
                                VoiceState.SPEAKING -> "Speaking…"
                                VoiceState.IDLE -> if (customVoice.active) "Fall-K • Your Voice Active" else "Fall-K Ready"
                            },
                            color = FalkePalette.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isActive) "Tap orb to stop" else if (settings.voiceLockEnabled) "Locked to your voiceprint" else "Tap orb to talk hands-free",
                            color = FalkePalette.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (isActive) {
                        Surface(
                            color = FalkePalette.Crimson.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(99.dp),
                            border = BorderStroke(1.dp, FalkePalette.Crimson.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .clickable(onClick = onStopSession)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Stop, null, tint = FalkePalette.Crimson, modifier = Modifier.size(14.dp))
                                Text("Stop", color = FalkePalette.CrimsonSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        IconButton(onClick = onClearChat, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.DeleteOutline, "Clear chat", tint = FalkePalette.TextMuted, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
        }

        // Quick Routine Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickRoutinePill("Catch Me Up", Icons.Default.Bolt, FalkePalette.Cyan) {
                onSendText("Catch me up", false)
            }
            QuickRoutinePill("6 Gmails", Icons.Default.Email, FalkePalette.EmeraldBright) {
                onSendText("List all my connected Gmail accounts", false)
            }
            QuickRoutinePill("Lock In", Icons.Default.Lock, FalkePalette.Violet) {
                onSendText("Lock in for 90 minutes", false)
            }
            QuickRoutinePill("Goodnight", Icons.Default.NightsStay, FalkePalette.Amber) {
                onSendText("Goodnight", false)
            }
            QuickRoutinePill("Commute", Icons.Default.Headphones, FalkePalette.Cyan) {
                onStartCommuteBriefing()
            }
            QuickRoutinePill("Find Phone", Icons.Default.PhonelinkRing, FalkePalette.Crimson) {
                onTriggerFindPhone()
            }
        }

        // PIN/Pattern Override Banner if Speaker Verification rejected an utterance
        AnimatedVisibility(visible = voiceUi.voiceRejectedNeedsPin) {
            Surface(
                color = FalkePalette.CardCrimson,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, FalkePalette.BorderCrimson),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice Lock Blocked Speaker", color = FalkePalette.CrimsonSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Noisy room or sore throat? Verify with PIN/Pattern to talk.", color = FalkePalette.TextBody, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onUnlockVoiceBypassWithPin,
                        colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Cyan),
                        shape = RoundedCornerShape(99.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, null, tint = Color(0xFF03111F), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Unlock", color = Color(0xFF03111F), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // 6-Second Cancel/Undo Countdown Banner
        AnimatedVisibility(visible = voiceUi.pendingUndo != null) {
            voiceUi.pendingUndo?.let { pending ->
                Surface(
                    color = FalkePalette.CardAmber,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, FalkePalette.Amber.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${pending.summary} (${voiceUi.undoSecondsLeft}s)",
                            color = FalkePalette.AmberSoft,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onCancelUndo,
                            colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Crimson),
                            shape = RoundedCornerShape(99.dp)
                        ) {
                            Text("UNDO", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Welcome Hero OR Clean Multi-Turn Chat Stream
        if (!hasMessages) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (settings.voiceLockEnabled && settings.hasVoiceprint) {
                        StatusPill("VOICE LOCK ON", FalkePalette.EmeraldBright, dot = true)
                    }
                    if (customVoice.active) {
                        StatusPill("YOUR VOICE ACTIVE", FalkePalette.Cyan, dot = true)
                    }
                    if (wakeRunning) {
                        StatusPill("\"HEY FALL-K\" 24/7", FalkePalette.Violet, dot = true)
                    }
                }
                Spacer(Modifier.height(16.dp))

                JarvisOrb(
                    state = voiceUi.state,
                    orbSize = 124.dp,
                    onClick = { onTapOrb(attachScreen) }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = voiceUi.statusLine,
                    color = FalkePalette.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Say \"Hey Fall-K\" or pick a quick command below",
                    color = FalkePalette.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(24.dp))

                val prompts = listOf(
                    "Verify all 6 Gmail accounts" to "Live inbox health check",
                    "Check unread priority emails" to "Interviews, finance & alerts",
                    "Set wake alarm for 6:30 AM" to "Exact DND-bypass alarm",
                    "Play focus beats on Spotify" to "Start deep work session"
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    prompts.chunked(2).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (title, sub) ->
                                Surface(
                                    color = FalkePalette.CardSurface,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, FalkePalette.BorderSubtle),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { onSendText(title, attachScreen) }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(title, color = FalkePalette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(3.dp))
                                        Text(sub, color = FalkePalette.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(voiceUi.chatHistory, key = { "${it.id}_${it.timestampMs}" }) { msg ->
                    ConversationBubble(message = msg, onReplay = { onReplayMessage(msg.text) })
                }
                if (voiceUi.state == VoiceState.THINKING) {
                    item(key = "thinking") {
                        ThinkingDotsIndicator()
                    }
                }
            }
        }

        // Sleek Pill Composer Bar
        val sendNow = {
            val clean = inputText.trim()
            if (clean.isNotEmpty()) {
                inputText = ""
                onSendText(clean, attachScreen)
            }
        }
        Surface(
            color = FalkePalette.CardSurface,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(
                1.dp,
                if (inputText.isNotBlank()) FalkePalette.BorderCyan else FalkePalette.BorderSubtle
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = { attachScreen = !attachScreen },
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = if (attachScreen) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Screen Vision",
                        tint = if (attachScreen) FalkePalette.EmeraldBright else FalkePalette.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = if (attachScreen) "Ask Fall-K about your screen…" else "Message Fall-K…",
                            color = FalkePalette.TextMuted,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = TextStyle(color = FalkePalette.TextPrimary, fontSize = 14.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(FalkePalette.Cyan),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendNow() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val hasText = inputText.trim().isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                if (hasText) listOf(FalkePalette.Cyan, FalkePalette.Emerald)
                                else listOf(FalkePalette.CardElevated, FalkePalette.CardElevated)
                            )
                        )
                        .border(1.dp, if (hasText) Color.Transparent else FalkePalette.BorderCyan, CircleShape)
                        .clickable {
                            if (hasText) sendNow() else onTapOrb(attachScreen)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                        contentDescription = if (hasText) "Send" else "Speak",
                        tint = if (hasText) Color(0xFF03111F) else FalkePalette.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationBubble(
    message: ChatMessage,
    onReplay: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (message.isUser) {
            Surface(
                color = Color(0xFF0F2B4C),
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                border = BorderStroke(1.dp, FalkePalette.Cyan.copy(alpha = 0.32f)),
                modifier = Modifier.widthIn(max = 296.dp)
            ) {
                Text(
                    text = message.text,
                    color = FalkePalette.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        } else {
            val rendered = remember(message.text) { renderMarkdown(message.text) }
            val border = if (message.isError) FalkePalette.BorderCrimson else FalkePalette.BorderSubtle
            val bg = if (message.isError) FalkePalette.CardCrimson else FalkePalette.CardSurface

            Surface(
                color = bg,
                shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                border = BorderStroke(1.dp, border),
                modifier = Modifier.fillMaxWidth(0.96f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = if (message.isError) FalkePalette.Crimson else FalkePalette.EmeraldBright,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (message.isError) "ALERT" else "FALL-K",
                                color = if (message.isError) FalkePalette.Crimson else FalkePalette.EmeraldBright,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = onReplay, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.VolumeUp, "Speak", tint = FalkePalette.TextMuted, modifier = Modifier.size(15.dp))
                            }
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(MarkdownFormatter.toPlainText(message.text)))
                                    copied = true
                                    scope.launch {
                                        delay(1400)
                                        copied = false
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = if (copied) FalkePalette.EmeraldBright else FalkePalette.TextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = rendered,
                        color = FalkePalette.TextBody,
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )

                    if (message.actions.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            message.actions.take(5).forEach { act ->
                                Surface(
                                    color = FalkePalette.CardElevated,
                                    shape = RoundedCornerShape(99.dp),
                                    border = BorderStroke(1.dp, FalkePalette.BorderSubtle)
                                ) {
                                    Text(
                                        text = act,
                                        color = FalkePalette.TextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
