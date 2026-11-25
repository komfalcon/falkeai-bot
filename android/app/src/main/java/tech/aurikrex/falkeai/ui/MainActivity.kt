package tech.aurikrex.falkeai.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.data.CapturedNotificationEntity
import tech.aurikrex.falkeai.data.DashboardResponse
import tech.aurikrex.falkeai.data.FalkeAlarmEntity
import tech.aurikrex.falkeai.executors.FalkeAlarmManager
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor
import tech.aurikrex.falkeai.monitors.FalkeBridgeService
import tech.aurikrex.falkeai.monitors.OemSurvivalHelper
import tech.aurikrex.falkeai.voice.ChatMessage
import tech.aurikrex.falkeai.voice.FalkeWakeWordService
import tech.aurikrex.falkeai.voice.VoiceState
import tech.aurikrex.falkeai.voice.VoiceUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = FalkeApplication.instance
        if (app.prefs.isPaired()) {
            FalkeBridgeService.start(this)
            if (app.prefs.wakeWordEnabled) {
                FalkeWakeWordService.start(this)
            }
        }

        setContent {
            MaterialTheme(colorScheme = FalkeDarkColorScheme) {
                FalkeCommandCenterRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FalkeCommandCenterRoot() {
    val context = LocalContext.current
    val app = FalkeApplication.instance
    val scope = rememberCoroutineScope()

    val sseConnected by app.api.sseConnected.collectAsState()
    val wakeRunning by FalkeWakeWordService.running.collectAsState()
    val voiceUi by app.voiceController.uiState.collectAsState()
    val alarms by app.db.alarmDao().observeAll().collectAsState(initial = emptyList())
    val notifs by app.db.notificationDao().observeRecent(80).collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(if (app.prefs.isPaired()) 0 else 4) }
    var dashboard by remember { mutableStateOf<DashboardResponse?>(null) }
    var dashLoading by remember { mutableStateOf(false) }
    var dashError by remember { mutableStateOf<String?>(null) }
    var speakTyped by remember { mutableStateOf(app.prefs.speakTypedReplies) }

    fun refreshDashboard() {
        if (!app.prefs.isPaired()) return
        dashLoading = true
        dashError = null
        scope.launch {
            app.api.fetchDashboard()
                .onSuccess {
                    dashboard = it
                    dashError = null
                }
                .onFailure {
                    dashError = it.message ?: "Could not sync briefing right now"
                }
            dashLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (app.prefs.isPaired()) refreshDashboard()
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab in listOf(1, 2) && app.prefs.isPaired()) {
            refreshDashboard()
        }
    }

    val attentionCount = (dashboard?.urgentAlerts?.size ?: 0) + (dashboard?.pendingFlags?.size ?: 0)

    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = FalkePalette.Cyan,
        selectedTextColor = FalkePalette.Cyan,
        indicatorColor = FalkePalette.Cyan.copy(alpha = 0.14f),
        unselectedIconColor = FalkePalette.TextMuted,
        unselectedTextColor = FalkePalette.TextMuted
    )

    Scaffold(
        containerColor = FalkePalette.BgDeep,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = FalkePalette.BgTopBar),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(FalkePalette.Cyan, FalkePalette.Emerald)
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = Color(0xFF03111F),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    "FalkeAI",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = FalkePalette.TextPrimary,
                                    letterSpacing = (-0.3).sp
                                )
                            }
                            val badgeColor = when {
                                sseConnected -> FalkePalette.EmeraldBright
                                app.prefs.isPaired() -> FalkePalette.Amber
                                else -> FalkePalette.TextMuted
                            }
                            val badgeLabel = when {
                                sseConnected -> "LIVE"
                                app.prefs.isPaired() -> "SYNC"
                                else -> "UNPAIRED"
                            }
                            StatusPill(text = badgeLabel, color = badgeColor, dot = true)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            speakTyped = !speakTyped
                            app.prefs.speakTypedReplies = speakTyped
                        }) {
                            Icon(
                                imageVector = if (speakTyped) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = if (speakTyped) "Auto-speak typed replies ON" else "Auto-speak typed replies OFF",
                                tint = if (speakTyped) FalkePalette.Cyan else FalkePalette.TextMuted
                            )
                        }
                        IconButton(onClick = { refreshDashboard() }, enabled = !dashLoading) {
                            if (dashLoading) {
                                CircularProgressIndicator(
                                    color = FalkePalette.Cyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = FalkePalette.TextSecondary
                                )
                            }
                        }
                    }
                )
                HorizontalDivider(color = FalkePalette.BorderSubtle, thickness = 1.dp)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = FalkePalette.BorderSubtle, thickness = 1.dp)
                NavigationBar(
                    containerColor = FalkePalette.BgNavBar,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Mic, null) },
                        label = { Text("Voice", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (attentionCount > 0) {
                                        Badge(containerColor = FalkePalette.Crimson) {
                                            Text(
                                                attentionCount.coerceAtMost(9).toString(),
                                                color = Color.White,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Dashboard, null)
                            }
                        },
                        label = { Text("Briefing", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.AccountBalanceWallet, null) },
                        label = { Text("Ledger", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Alarm, null) },
                        label = { Text("Alarms", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text("Setup", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navItemColors
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF081022), FalkePalette.BgDeep, FalkePalette.BgDeep)
                    )
                )
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> VoiceHomeTab(
                    voiceUi = voiceUi,
                    wakeRunning = wakeRunning,
                    onTapOrb = { withScreen ->
                        if (voiceUi.state != VoiceState.IDLE) {
                            app.voiceController.stopSession()
                        } else {
                            app.voiceController.startVoiceTurn(captureScreen = withScreen, followUpChain = true)
                        }
                    },
                    onStopSession = { app.voiceController.stopSession() },
                    onSendText = { txt, withScreen ->
                        app.voiceController.sendTextCommand(txt, captureScreen = withScreen)
                    },
                    onReplayMessage = { msg -> app.voiceController.replayMessage(msg) },
                    onClearChat = { app.voiceController.clearHistory() },
                    onStartCommuteBriefing = { app.voiceController.startCommuteBriefing() },
                    onCancelUndo = { app.voiceController.cancelPendingUndo() },
                    onTriggerFindPhone = { PhoneMediaExecutor.triggerFindMyPhoneSiren(context, 8) }
                )
                1 -> BriefingTab(
                    dashboard = dashboard,
                    loading = dashLoading,
                    error = dashError,
                    onAckUrgent = { sender ->
                        scope.launch {
                            app.api.acknowledgeAlert(sender)
                            refreshDashboard()
                        }
                    },
                    onDismissFlag = { chat ->
                        scope.launch {
                            app.api.dismissFlag(chat)
                            refreshDashboard()
                        }
                    },
                    onDismissAllFlags = {
                        scope.launch {
                            app.api.dismissAllFlags()
                            refreshDashboard()
                        }
                    },
                    onApproveFollowup = { id ->
                        app.voiceController.sendTextCommand("Send follow-up #$id", speakReply = false)
                        refreshDashboard()
                    },
                    onQuickCommand = { cmd ->
                        selectedTab = 0
                        app.voiceController.sendTextCommand(cmd, speakReply = false)
                    },
                    onRefresh = { refreshDashboard() }
                )
                2 -> LedgerAndInboxTab(dashboard = dashboard, notifications = notifs)
                3 -> AlarmsAndWakeWordsTab(
                    alarms = alarms,
                    wakeRunning = wakeRunning,
                    onCreateAlarm = { h, m, lbl ->
                        scope.launch { FalkeAlarmManager.createAlarm(context, app.db, h, m, lbl) }
                    },
                    onToggleAlarm = { id, en ->
                        scope.launch { FalkeAlarmManager.toggleAlarm(context, app.db, id, en) }
                    },
                    onDeleteAlarm = { id ->
                        scope.launch { FalkeAlarmManager.deleteAlarmById(context, app.db, id) }
                    }
                )
                4 -> SetupAndInfinixTab(
                    speakTyped = speakTyped,
                    onSpeakTypedChanged = {
                        speakTyped = it
                        app.prefs.speakTypedReplies = it
                    },
                    onPaired = {
                        FalkeBridgeService.start(context)
                        if (app.prefs.wakeWordEnabled) FalkeWakeWordService.start(context)
                        refreshDashboard()
                        selectedTab = 0
                    }
                )
            }
        }
    }
}

@Composable
private fun VoiceHomeTab(
    voiceUi: VoiceUiState,
    wakeRunning: Boolean,
    onTapOrb: (Boolean) -> Unit,
    onStopSession: () -> Unit,
    onSendText: (String, Boolean) -> Unit,
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Active compact voice bar when chatting, or full hero orb when starting fresh
        if (hasMessages) {
            Surface(
                color = FalkePalette.CardSurface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    if (isActive) FalkePalette.BorderCyan else FalkePalette.BorderSubtle
                ),
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
                        orbSize = 48.dp,
                        onClick = { onTapOrb(attachScreen) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (voiceUi.state) {
                                VoiceState.LISTENING -> "Listening…"
                                VoiceState.FOLLOW_UP_WINDOW -> "Listening for follow-up…"
                                VoiceState.THINKING -> "Thinking…"
                                VoiceState.SPEAKING -> "Speaking…"
                                VoiceState.IDLE -> if (wakeRunning) "24/7 Wake Mic Active" else "Ready for voice or text"
                            },
                            color = FalkePalette.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isActive) "Tap orb to stop" else "Tap orb to speak hands-free",
                            color = FalkePalette.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (isActive) {
                        Surface(
                            color = FalkePalette.Crimson.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(99.dp),
                            border = BorderStroke(1.dp, FalkePalette.Crimson.copy(alpha = 0.5f)),
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
                        IconButton(onClick = onClearChat, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Clear chat",
                                tint = FalkePalette.TextMuted,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }

        // Quick One-Tap Routine Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickRoutinePill("Catch Me Up", Icons.Default.Bolt, FalkePalette.Cyan) {
                onSendText("Catch me up", false)
            }
            QuickRoutinePill("My 6 Gmails", Icons.Default.Email, FalkePalette.EmeraldBright) {
                onSendText("List all my connected Gmail accounts", false)
            }
            QuickRoutinePill("Lock In", Icons.Default.Lock, FalkePalette.Violet) {
                onSendText("Lock in for 90 minutes", false)
            }
            QuickRoutinePill("Goodnight", Icons.Default.NightsStay, FalkePalette.Amber) {
                onSendText("Goodnight", false)
            }
            QuickRoutinePill("Commute Brief", Icons.Default.Headphones, FalkePalette.Cyan) {
                onStartCommuteBriefing()
            }
            QuickRoutinePill("Find Phone", Icons.Default.PhonelinkRing, FalkePalette.Crimson) {
                onTriggerFindPhone()
            }
        }

        // 6-Second Cancel/Undo Countdown Banner (Feature J)
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
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(99.dp)
                        ) {
                            Text("UNDO", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Main Body: Hero Welcome State OR Scrollable Multi-Turn Chat Bubbles
        if (!hasMessages) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                JarvisOrb(
                    state = voiceUi.state,
                    orbSize = 128.dp,
                    onClick = { onTapOrb(attachScreen) }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = voiceUi.statusLine,
                    color = FalkePalette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (wakeRunning) "Say \"Hey Falke\" or \"Jarvis\" anytime"
                    else "Tap the orb to speak, or pick a prompt below",
                    color = FalkePalette.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(22.dp))

                // Quick Starter Prompts (2x2 clean grid)
                val starters = listOf(
                    "Identify all my Gmail accounts" to "Verify all 6 inboxes",
                    "Check my unread priority emails" to "Across all Gmail accounts",
                    "Set an alarm for 6:30am" to "Exact local wake alarm",
                    "Play focus music on Spotify" to "Deep work playlist"
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    starters.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { (prompt, subtitle) ->
                                Surface(
                                    color = FalkePalette.CardSurface,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, FalkePalette.BorderSubtle),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { onSendText(prompt, attachScreen) }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            prompt,
                                            color = FalkePalette.TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            subtitle,
                                            color = FalkePalette.TextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(voiceUi.chatHistory, key = { it.id }) { msg ->
                    ChatBubbleRow(
                        message = msg,
                        onReplay = { onReplayMessage(msg.text) }
                    )
                }
                if (voiceUi.state == VoiceState.THINKING) {
                    item(key = "thinking_bubble") {
                        ThinkingDotsIndicator()
                    }
                }
            }
        }

        // Sleek Rounded Pill Composer Bar
        val sendNow = {
            val cmd = inputText.trim()
            if (cmd.isNotEmpty()) {
                inputText = ""
                onSendText(cmd, attachScreen)
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
                    .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = { attachScreen = !attachScreen },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (attachScreen) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (attachScreen) "Screen Vision ON" else "Screen Vision OFF",
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
                            text = if (attachScreen) "Ask about your screen…" else "Ask FalkeAI anything…",
                            color = FalkePalette.TextMuted,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = TextStyle(
                            color = FalkePalette.TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
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
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                if (hasText) listOf(FalkePalette.Cyan, FalkePalette.Emerald)
                                else listOf(FalkePalette.CardElevated, FalkePalette.CardElevated)
                            )
                        )
                        .border(
                            1.dp,
                            if (hasText) Color.Transparent else FalkePalette.BorderCyan,
                            CircleShape
                        )
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
private fun ChatBubbleRow(
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
                color = Color(0xFF0E2948),
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                border = BorderStroke(1.dp, FalkePalette.Cyan.copy(alpha = 0.35f)),
                modifier = Modifier.widthIn(max = 290.dp)
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
            val borderColor = if (message.isError) FalkePalette.BorderCrimson else FalkePalette.BorderSubtle
            val bgColor = if (message.isError) FalkePalette.CardCrimson else FalkePalette.CardSurface
            val rendered = remember(message.text) { renderMarkdown(message.text) }

            Surface(
                color = bgColor,
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth(0.95f)
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
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (message.isError) FalkePalette.Crimson else FalkePalette.EmeraldBright)
                            )
                            Text(
                                text = if (message.isError) "CONNECTION" else "FALKEAI",
                                color = if (message.isError) FalkePalette.Crimson else FalkePalette.EmeraldBright,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(
                                onClick = onReplay,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.VolumeUp,
                                    contentDescription = "Read aloud",
                                    tint = FalkePalette.TextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
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
                                modifier = Modifier.size(26.dp)
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
                            message.actions.take(4).forEach { act ->
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BriefingTab(
    dashboard: DashboardResponse?,
    loading: Boolean,
    error: String?,
    onAckUrgent: (String) -> Unit,
    onDismissFlag: (String) -> Unit,
    onDismissAllFlags: () -> Unit,
    onApproveFollowup: (Long) -> Unit,
    onQuickCommand: (String) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (error != null) {
            item {
                GlassCard(
                    backgroundColor = FalkePalette.CardCrimson,
                    borderColor = FalkePalette.BorderCrimson
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync issue", color = FalkePalette.CrimsonSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(error, color = FalkePalette.TextBody, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            border = BorderStroke(1.dp, FalkePalette.Crimson)
                        ) {
                            Text("Retry", color = FalkePalette.CrimsonSoft, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (dashboard == null) {
            item {
                GlassCard {
                    Text(
                        if (loading) "Syncing live WhatsApp + Gmail briefing…"
                        else "Pair your phone in Setup to sync live WhatsApp + Gmail briefings.",
                        color = FalkePalette.TextBody,
                        fontSize = 14.sp
                    )
                    Button(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Refresh Briefing")
                    }
                }
            }
            return@LazyColumn
        }

        // 1) Status + Quick Availability Mode Switcher
        item {
            val mode = dashboard.availability.mode.ifBlank { "available" }
            val modeColor = when (mode.lowercase()) {
                "available" -> FalkePalette.EmeraldBright
                "deep_work" -> FalkePalette.Violet
                "sleeping" -> FalkePalette.Amber
                else -> FalkePalette.Cyan
            }
            GlassCard(borderColor = modeColor.copy(alpha = 0.35f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("AVAILABILITY MODE", color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            mode.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            color = FalkePalette.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    StatusPill(
                        text = if (dashboard.lowBatteryCover) "BATTERY COVER" else mode.uppercase(),
                        color = if (dashboard.lowBatteryCover) FalkePalette.Amber else modeColor,
                        dot = true
                    )
                }

                // 4 Live KPI Stat Tiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiMiniTile("Urgent", dashboard.urgentAlerts.size.toString(), FalkePalette.Crimson, Modifier.weight(1f))
                    KpiMiniTile("Emails", dashboard.priorityEmails.size.toString(), FalkePalette.Cyan, Modifier.weight(1f))
                    KpiMiniTile("Follow-ups", dashboard.followups.size.toString(), FalkePalette.EmeraldBright, Modifier.weight(1f))
                    KpiMiniTile("WA Holds", dashboard.pendingFlags.size.toString(), FalkePalette.Amber, Modifier.weight(1f))
                }

                if (dashboard.catchUpSummary.isNotBlank()) {
                    HorizontalDivider(color = FalkePalette.BorderSubtle)
                    Text(
                        text = remember(dashboard.catchUpSummary) { renderMarkdown(dashboard.catchUpSummary) },
                        color = FalkePalette.TextBody,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        // 2) Connected Gmail Accounts Health Card (All 6 Real Addresses!)
        if (dashboard.gmailAccounts.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Connected Gmail Inboxes (${dashboard.gmailAccounts.size})",
                    accent = FalkePalette.Cyan
                )
            }
            item {
                GlassCard(contentPadding = 12.dp) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dashboard.gmailAccounts.forEach { acc ->
                            val isOk = acc.status == "connected"
                            val pillColor = if (isOk) FalkePalette.EmeraldBright else FalkePalette.Amber
                            val display = acc.email?.takeIf { it.isNotBlank() } ?: acc.label
                            Surface(
                                color = pillColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, pillColor.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(pillColor)
                                    )
                                    Text(
                                        text = "#${acc.index} $display",
                                        color = FalkePalette.TextBody,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3) Urgent Alerts
        if (dashboard.urgentAlerts.isNotEmpty()) {
            item { SectionHeader("Urgent Alerts", FalkePalette.Crimson) }
            items(dashboard.urgentAlerts) { u ->
                GlassCard(
                    backgroundColor = FalkePalette.CardCrimson,
                    borderColor = FalkePalette.BorderCrimson
                ) {
                    Text(u.contactName ?: u.sender, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(u.summary, color = FalkePalette.CrimsonSoft, fontSize = 13.sp, lineHeight = 19.sp)
                    Button(
                        onClick = { onAckUrgent(u.sender) },
                        colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Crimson),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Acknowledge", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // 4) Pending WhatsApp Handoffs (Dismissible 1-Tap!)
        if (dashboard.pendingFlags.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "WhatsApp Chats For Review (${dashboard.pendingFlags.size})",
                    accent = FalkePalette.Amber,
                    trailing = {
                        Text(
                            "Clear All",
                            color = FalkePalette.Amber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onDismissAllFlags)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                )
            }
            items(dashboard.pendingFlags) { fl ->
                val cleanReason = if (fl.reason.lowercase().contains("could not parse")) {
                    "Needs your quick reply"
                } else {
                    fl.reason
                }
                GlassCard(borderColor = FalkePalette.Amber.copy(alpha = 0.35f), contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                fl.name?.takeIf { it.isNotBlank() } ?: "+${fl.chat}",
                                color = FalkePalette.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(cleanReason, color = FalkePalette.AmberSoft, fontSize = 12.sp)
                            if (fl.lastMessage.isNotBlank()) {
                                Text(
                                    "\"${fl.lastMessage}\"",
                                    color = FalkePalette.TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onDismissFlag(fl.chat) },
                            border = BorderStroke(1.dp, FalkePalette.Amber.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Dismiss", color = FalkePalette.AmberSoft, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 5) Priority Gmail Cards with 1-Tap Reply / Archive / Trash
        if (dashboard.priorityEmails.isNotEmpty()) {
            item { SectionHeader("Priority Gmail", FalkePalette.Cyan) }
            items(dashboard.priorityEmails.take(8)) { em ->
                val catColor = when (em.category.lowercase()) {
                    "interview_career" -> FalkePalette.Violet
                    "payments_finance" -> FalkePalette.EmeraldBright
                    "infra_alerts" -> FalkePalette.Crimson
                    else -> FalkePalette.Cyan
                }
                GlassCard(contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(text = em.accountLabel.ifBlank { "Gmail" }, color = FalkePalette.Cyan)
                        StatusPill(
                            text = em.category.replace('_', ' ').uppercase().ifBlank { "EMAIL" },
                            color = catColor
                        )
                    }
                    Text(em.subject, color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(em.sender, color = FalkePalette.Cyan, fontSize = 12.sp)
                    if (em.snippet.isNotBlank()) {
                        Text(
                            em.snippet,
                            color = FalkePalette.TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 17.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EmailQuickAction("Reply", Icons.AutoMirrored.Filled.Reply, FalkePalette.Cyan) {
                            onQuickCommand("Draft a reply to email ${em.msgId} from ${em.sender} about ${em.subject}")
                        }
                        EmailQuickAction("Archive", Icons.Default.Archive, FalkePalette.EmeraldBright) {
                            onQuickCommand("Archive email ${em.msgId}")
                        }
                        EmailQuickAction("Trash", Icons.Default.DeleteOutline, FalkePalette.Crimson) {
                            onQuickCommand("Trash email ${em.msgId}")
                        }
                    }
                }
            }
        }

        // 6) Smart Follow-Ups
        if (dashboard.followups.isNotEmpty()) {
            item { SectionHeader("Smart Follow-Ups", FalkePalette.EmeraldBright) }
            items(dashboard.followups.take(6)) { f ->
                GlassCard(borderColor = FalkePalette.BorderEmerald, contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(f.contactName ?: f.sender, color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        StatusPill(text = f.status.uppercase(), color = FalkePalette.EmeraldBright)
                    }
                    Text(f.reason, color = FalkePalette.TextSecondary, fontSize = 12.sp)
                    Surface(
                        color = FalkePalette.CardElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "\"${f.suggestedReply}\"",
                            color = FalkePalette.TextBody,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Button(
                        onClick = { onApproveFollowup(f.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Emerald),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Approve & Send", color = Color(0xFF021610), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // 7) Aurikrex Bookings
        if (dashboard.bookings.isNotEmpty()) {
            item { SectionHeader("Aurikrex Tutoring Classes", FalkePalette.Violet) }
            items(dashboard.bookings.take(6)) { b ->
                GlassCard(contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(b.name, color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${b.subject} (${b.exam}) • ${b.slot}", color = FalkePalette.TextSecondary, fontSize = 12.sp)
                        }
                        StatusPill(text = b.exam.ifBlank { "CLASS" }, color = FalkePalette.Violet)
                    }
                }
            }
        }
    }
}

@Composable
private fun KpiMiniTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = FalkePalette.CardElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun EmailQuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LedgerAndInboxTab(
    dashboard: DashboardResponse?,
    notifications: List<CapturedNotificationEntity>
) {
    var search by remember { mutableStateOf("") }
    var filterMode by remember { mutableIntStateOf(0) } // 0=All, 1=Social, 2=Other Apps

    val alerts = dashboard?.bankAlerts.orEmpty()
    val ngnIn = remember(alerts) { alerts.filter { it.currency.equals("NGN", true) && it.direction == "credit" }.sumOf { it.amountMinor } }
    val ngnOut = remember(alerts) { alerts.filter { it.currency.equals("NGN", true) && it.direction == "debit" }.sumOf { it.amountMinor } }
    val usdIn = remember(alerts) { alerts.filter { it.currency.equals("USD", true) && it.direction == "credit" }.sumOf { it.amountMinor } }
    val usdOut = remember(alerts) { alerts.filter { it.currency.equals("USD", true) && it.direction == "debit" }.sumOf { it.amountMinor } }

    val filteredNotifs = remember(search, filterMode, notifications) {
        val q = search.trim().lowercase()
        notifications.filter { n ->
            val passFilter = when (filterMode) {
                1 -> n.isSocial
                2 -> !n.isSocial
                else -> true
            }
            passFilter && (q.isEmpty() ||
                n.appLabel.lowercase().contains(q) ||
                n.title.lowercase().contains(q) ||
                n.bodyRedacted.lowercase().contains(q))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SectionHeader("14-Day Financial Ledger", FalkePalette.EmeraldBright) }

        item {
            GlassCard(borderColor = FalkePalette.BorderEmerald) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("INFLOW (NGN)", color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(
                            formatMoneyMinor(ngnIn, "NGN"),
                            color = FalkePalette.EmeraldBright,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("OUTFLOW (NGN)", color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(
                            formatMoneyMinor(ngnOut, "NGN"),
                            color = FalkePalette.Crimson,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                if (usdIn > 0L || usdOut > 0L) {
                    HorizontalDivider(color = FalkePalette.BorderSubtle)
                    Text(
                        "USD: +${formatMoneyMinor(usdIn, "USD")}  •  -${formatMoneyMinor(usdOut, "USD")}",
                        color = FalkePalette.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (alerts.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        "No bank or Paystack alerts captured yet. Enable Notification Access in Setup and connect your Gmail accounts to auto-track inflows & outflows.",
                        color = FalkePalette.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        } else {
            items(alerts.take(15)) { b ->
                val isCredit = b.direction == "credit"
                val accent = if (isCredit) FalkePalette.EmeraldBright else FalkePalette.Crimson
                GlassCard(contentPadding = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                b.bank.take(2).uppercase(),
                                color = accent,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${b.bank} • ${b.counterparty.ifBlank { "Transfer" }}",
                                color = FalkePalette.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                b.rawSummary,
                                color = FalkePalette.TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${if (isCredit) "+" else "-"}${b.amountFormatted}",
                                color = accent,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                            Text(
                                formatRelativeTime(b.createdAt),
                                color = FalkePalette.TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            SectionHeader("All-App & Social Inbox (OTPs Redacted)", FalkePalette.Cyan)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = FalkePalette.TextMuted, modifier = Modifier.size(18.dp)) },
                placeholder = { Text("Search WhatsApp, X, Instagram, banks…", fontSize = 13.sp, color = FalkePalette.TextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FalkePalette.Cyan,
                    unfocusedBorderColor = FalkePalette.BorderSubtle,
                    focusedContainerColor = FalkePalette.CardSurface,
                    unfocusedContainerColor = FalkePalette.CardSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Social", "Other Apps").forEachIndexed { idx, label ->
                    QuickRoutinePill(
                        label = label,
                        icon = Icons.Default.Notifications,
                        accent = FalkePalette.Cyan,
                        active = filterMode == idx
                    ) { filterMode = idx }
                }
            }
        }

        if (filteredNotifs.isEmpty()) {
            item {
                Text(
                    "No captured notifications match your filter yet.",
                    color = FalkePalette.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(filteredNotifs.take(40)) { n ->
                GlassCard(contentPadding = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(n.appLabel, color = FalkePalette.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(formatRelativeTime(n.postedAtMs), color = FalkePalette.TextMuted, fontSize = 10.sp)
                    }
                    if (n.title.isNotBlank()) {
                        Text(n.title, color = FalkePalette.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Text(n.bodyRedacted, color = FalkePalette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmsAndWakeWordsTab(
    alarms: List<FalkeAlarmEntity>,
    wakeRunning: Boolean,
    onCreateAlarm: (Int, Int, String) -> Unit,
    onToggleAlarm: (Long, Boolean) -> Unit,
    onDeleteAlarm: (Long) -> Unit
) {
    val context = LocalContext.current
    val prefs = FalkeApplication.instance.prefs

    var wakeEnabled by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var wakeWords by remember { mutableStateOf(prefs.wakeWords) }
    var newWakeWord by remember { mutableStateOf("") }

    var alarmHour by remember { mutableStateOf("06") }
    var alarmMinute by remember { mutableStateOf("30") }
    var alarmLabel by remember { mutableStateOf("Morning Wake Up") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionHeader("24/7 On-Device Wake Words", FalkePalette.Cyan) }

        item {
            GlassCard(borderColor = if (wakeRunning) FalkePalette.BorderCyan else FalkePalette.BorderSubtle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Always-On Wake Mic", color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (wakeRunning) "Active on-device (~35 MB RAM)" else "Paused — toggle on for hands-free wake",
                            color = if (wakeRunning) FalkePalette.EmeraldBright else FalkePalette.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = wakeEnabled,
                        onCheckedChange = { checked ->
                            wakeEnabled = checked
                            prefs.wakeWordEnabled = checked
                            if (checked) FalkeWakeWordService.start(context)
                            else FalkeWakeWordService.stop(context)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03111F), checkedTrackColor = FalkePalette.Cyan)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    wakeWords.forEach { phrase ->
                        Surface(
                            color = FalkePalette.CardElevated,
                            shape = RoundedCornerShape(99.dp),
                            border = BorderStroke(1.dp, FalkePalette.BorderCyan)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("\"$phrase\"", color = FalkePalette.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if (wakeWords.size > 1) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = FalkePalette.TextMuted,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                val updated = wakeWords.filterNot { it == phrase }
                                                wakeWords = updated
                                                prefs.wakeWords = updated
                                                FalkeWakeWordService.reloadKeywords(context)
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newWakeWord,
                        onValueChange = { newWakeWord = it },
                        placeholder = { Text("Add phrase (e.g. Yo Falke)…", fontSize = 13.sp, color = FalkePalette.TextMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val clean = newWakeWord.trim()
                            if (clean.isNotEmpty() && !wakeWords.contains(clean)) {
                                val updated = wakeWords + clean
                                wakeWords = updated
                                prefs.wakeWords = updated
                                newWakeWord = ""
                                FalkeWakeWordService.reloadKeywords(context)
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(FalkePalette.Cyan)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFF03111F))
                    }
                }
            }
        }

        item { SectionHeader("Exact FalkeAI Alarms", FalkePalette.Amber) }

        item {
            GlassCard {
                Text("Quick Presets", color = FalkePalette.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(Triple(6, 0, "6:00 AM"), Triple(6, 30, "6:30 AM"), Triple(7, 0, "7:00 AM"), Triple(8, 0, "8:00 AM")).forEach { (h, m, lbl) ->
                        QuickRoutinePill(lbl, Icons.Default.Alarm, FalkePalette.Amber) {
                            alarmHour = h.toString().padStart(2, '0')
                            alarmMinute = m.toString().padStart(2, '0')
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = alarmHour,
                        onValueChange = { alarmHour = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hour (0-23)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = alarmMinute,
                        onValueChange = { alarmMinute = it.filter(Char::isDigit).take(2) },
                        label = { Text("Min (0-59)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = alarmLabel,
                    onValueChange = { alarmLabel = it },
                    label = { Text("Alarm Label") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val h = alarmHour.toIntOrNull()?.coerceIn(0, 23) ?: 6
                        val m = alarmMinute.toIntOrNull()?.coerceIn(0, 59) ?: 30
                        onCreateAlarm(h, m, alarmLabel)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Set Exact Alarm", fontWeight = FontWeight.Bold)
                }
            }
        }

        items(alarms, key = { it.id }) { alarm ->
            GlassCard(contentPadding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            FalkeAlarmManager.formatAlarmTime(alarm.hour, alarm.minute),
                            color = if (alarm.enabled) FalkePalette.TextPrimary else FalkePalette.TextMuted,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(alarm.label, color = FalkePalette.TextSecondary, fontSize = 13.sp)
                    }
                    Switch(
                        checked = alarm.enabled,
                        onCheckedChange = { onToggleAlarm(alarm.id, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03111F), checkedTrackColor = FalkePalette.Cyan)
                    )
                    IconButton(onClick = { onDeleteAlarm(alarm.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = FalkePalette.Crimson)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupAndInfinixTab(
    speakTyped: Boolean,
    onSpeakTypedChanged: (Boolean) -> Unit,
    onPaired: () -> Unit
) {
    val context = LocalContext.current
    val app = FalkeApplication.instance
    val scope = rememberCoroutineScope()

    val isPaired = app.prefs.isPaired()
    var showRepairForm by remember { mutableStateOf(!isPaired) }
    var serverUrl by remember { mutableStateOf(app.prefs.serverUrl.ifBlank { "https://falke-bot-3cae37c5dc27.herokuapp.com" }) }
    var pairCode by remember { mutableStateOf("") }
    var pairStatus by remember {
        mutableStateOf(
            if (isPaired) "Connected to ${app.prefs.serverUrl}"
            else "Send `pair android` in WhatsApp self-chat to get a 5-min FALKE code."
        )
    }
    var health by remember { mutableStateOf(OemSurvivalHelper.inspectHealth(context)) }
    var btReadout by remember { mutableStateOf(app.prefs.btAutoReadoutEnabled) }
    var bilingual by remember { mutableStateOf(app.prefs.bilingualMode) }
    var screenVision by remember { mutableStateOf(app.prefs.screenVisionEnabled) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        health = OemSurvivalHelper.inspectHealth(context)
        if (grantedMap[Manifest.permission.RECORD_AUDIO] == true && app.prefs.wakeWordEnabled) {
            FalkeWakeWordService.start(context)
        }
    }

    val coreChecks = listOf(
        health.batteryUnrestricted,
        health.notificationListenerOn,
        health.accessibilityOn,
        health.dndPolicyGranted,
        health.overlayGranted
    )
    val readyCount = coreChecks.count { it }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1) Server Connection Card (Clean & Collapsible once paired)
        item {
            GlassCard(borderColor = if (isPaired) FalkePalette.BorderEmerald else FalkePalette.BorderCyan) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRepairForm = !showRepairForm },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isPaired) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isPaired) FalkePalette.EmeraldBright else FalkePalette.Amber,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                if (isPaired) "Phone Paired & Encrypted" else "Pair With FalkeAI Server",
                                color = FalkePalette.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                pairStatus,
                                color = FalkePalette.TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = if (showRepairForm) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = FalkePalette.TextMuted
                    )
                }

                AnimatedVisibility(visible = showRepairForm) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(color = FalkePalette.BorderSubtle)
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("Heroku HTTPS URL") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pairCode,
                            onValueChange = { pairCode = it.uppercase() },
                            label = { Text("5-Min Code (FALKE-XXXX-XXXX)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                pairStatus = "Pairing…"
                                scope.launch {
                                    app.api.pair(serverUrl, pairCode, "${Build.MANUFACTURER} ${Build.MODEL}")
                                        .onSuccess {
                                            pairStatus = "Paired with ${app.prefs.serverUrl}"
                                            showRepairForm = false
                                            onPaired()
                                        }
                                        .onFailure { pairStatus = "Error: ${it.message}" }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text("Pair Phone Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2) Runtime Permissions
        item {
            GlassCard {
                Text("Core Phone Permissions", color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "Grant Microphone, Calls, SMS, Contacts, Calendar, and Camera in one tap.",
                    color = FalkePalette.TextSecondary,
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        val perms = mutableListOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                            Manifest.permission.CAMERA
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        permLauncher.launch(perms.toTypedArray())
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Grant Phone & Voice Permissions", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3) Infinix Hot 10T / XOS Survival Checklist with Readiness Progress
        item {
            GlassCard(borderColor = FalkePalette.BorderCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Shield, null, tint = FalkePalette.Cyan, modifier = Modifier.size(20.dp))
                        Column {
                            Text("Infinix XOS Survival Pack", color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${health.manufacturerLabel} • $readyCount / ${coreChecks.size} Ready", color = FalkePalette.TextSecondary, fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { health = OemSurvivalHelper.inspectHealth(context) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Recheck", fontSize = 11.sp)
                    }
                }

                LinearProgressIndicator(
                    progress = { readyCount.toFloat() / coreChecks.size.coerceAtLeast(1).toFloat() },
                    color = if (readyCount == coreChecks.size) FalkePalette.EmeraldBright else FalkePalette.Cyan,
                    trackColor = FalkePalette.CardElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                )

                SurvivalRow("Battery Unrestricted (No XOS Kill)", health.batteryUnrestricted) {
                    OemSurvivalHelper.requestIgnoreBatteryOptimizations(context)
                }
                SurvivalRow("Phone Master Auto-Start & Protect", false, "Open XOS") {
                    OemSurvivalHelper.openInfinixAutoStart(context)
                }
                SurvivalRow("All-App Notifications & Bank Alerts", health.notificationListenerOn) {
                    OemSurvivalHelper.openNotificationListenerSettings(context)
                }
                SurvivalRow("Screen Vision, Dictation & Social", health.accessibilityOn) {
                    OemSurvivalHelper.openAccessibilitySettings(context)
                }
                SurvivalRow("Do Not Disturb Override & Control", health.dndPolicyGranted) {
                    OemSurvivalHelper.openDndAccessSettings(context)
                }
                SurvivalRow("Default Digital Assistant", false, "Set Default") {
                    OemSurvivalHelper.openDefaultAssistantSettings(context)
                }
                SurvivalRow("Draw Over Apps (Floating Orb)", health.overlayGranted) {
                    OemSurvivalHelper.openOverlaySettings(context)
                }

                Surface(
                    color = FalkePalette.CardAmber,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "XOS 7.6 Tip: Open Recent Apps -> pull DOWN on FalkeAI -> tap the Padlock icon so XOS never clears it from RAM.",
                        color = FalkePalette.AmberSoft,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        // 4) Assistant Preferences
        item {
            GlassCard {
                Text("Assistant Preferences", color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                ToggleRow("Auto-Speak Typed Replies", speakTyped, onSpeakTypedChanged)
                ToggleRow("Silent Screen Vision (Android 11)", screenVision) {
                    screenVision = it; app.prefs.screenVisionEnabled = it
                }
                ToggleRow("Bluetooth Earbuds Auto-Readout", btReadout) {
                    btReadout = it; app.prefs.btAutoReadoutEnabled = it
                }
                ToggleRow("Bilingual Mode (EN / Yoruba / Pidgin / FR)", bilingual) {
                    bilingual = it; app.prefs.bilingualMode = it
                }
            }
        }
    }
}

@Composable
private fun SurvivalRow(
    title: String,
    ok: Boolean,
    buttonLabel: String = if (ok) "Done" else "Fix",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (ok) FalkePalette.EmeraldBright else FalkePalette.Amber)
            )
            Text(
                text = title,
                color = if (ok) FalkePalette.TextBody else FalkePalette.TextPrimary,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            color = if (ok) FalkePalette.Emerald.copy(alpha = 0.14f) else FalkePalette.Cyan.copy(alpha = 0.15f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (ok) FalkePalette.BorderEmerald else FalkePalette.BorderCyan),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
        ) {
            Text(
                text = buttonLabel,
                color = if (ok) FalkePalette.EmeraldBright else FalkePalette.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = FalkePalette.TextBody, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03111F), checkedTrackColor = FalkePalette.Cyan)
        )
    }
}
