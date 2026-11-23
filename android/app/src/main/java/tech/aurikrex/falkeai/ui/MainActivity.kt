package tech.aurikrex.falkeai.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.data.DashboardResponse
import tech.aurikrex.falkeai.data.FalkeAlarmEntity
import tech.aurikrex.falkeai.executors.FalkeAlarmManager
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor
import tech.aurikrex.falkeai.monitors.FalkeBridgeService
import tech.aurikrex.falkeai.monitors.OemSurvivalHelper
import tech.aurikrex.falkeai.voice.FalkeWakeWordService
import tech.aurikrex.falkeai.voice.VoiceState

private val FalkeDarkColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    secondary = Color(0xFF10B981),
    background = Color(0xFF090D16),
    surface = Color(0xFF111827),
    onPrimary = Color(0xFF020617),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFE2E8F0)
)

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
            MaterialTheme(colorScheme = FalkeDarkColors) {
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
    val notifs by app.db.notificationDao().observeRecent(60).collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(if (app.prefs.isPaired()) 0 else 4) }
    var dashboard by remember { mutableStateOf<DashboardResponse?>(null) }
    var dashLoading by remember { mutableStateOf(false) }

    fun refreshDashboard() {
        if (!app.prefs.isPaired()) return
        dashLoading = true
        scope.launch {
            app.api.fetchDashboard().onSuccess { dashboard = it }
            dashLoading = false
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab in listOf(1, 2) && app.prefs.isPaired()) {
            refreshDashboard()
        }
    }

    Scaffold(
        containerColor = Color(0xFF090D16),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D1424)),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡ FalkeAI", fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Spacer(Modifier.width(10.dp))
                        val badgeColor = if (sseConnected) Color(0xFF10B981) else Color(0xFFF59E0B)
                        Surface(color = badgeColor.copy(alpha = 0.2f), shape = RoundedCornerShape(99.dp)) {
                            Text(
                                text = if (sseConnected) "LIVE" else if (app.prefs.isPaired()) "SYNCING" else "UNPAIRED",
                                color = badgeColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshDashboard() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF94A3B8))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1424)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Mic, null) },
                    label = { Text("Voice") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Dashboard, null) },
                    label = { Text("Briefing") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, null) },
                    label = { Text("Ledger") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Alarm, null) },
                    label = { Text("Alarms") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Setup") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            when (selectedTab) {
                0 -> VoiceHomeTab(
                    voiceUi = voiceUi,
                    wakeRunning = wakeRunning,
                    onTapOrb = { withScreen ->
                        app.voiceController.startVoiceTurn(captureScreen = withScreen, followUpChain = true)
                    },
                    onSendText = { txt -> app.voiceController.sendTextCommand(txt) },
                    onStartCommuteBriefing = { app.voiceController.startCommuteBriefing() },
                    onCancelUndo = { app.voiceController.cancelPendingUndo() },
                    onTriggerFindPhone = { PhoneMediaExecutor.triggerFindMyPhoneSiren(context, 8) }
                )
                1 -> BriefingTab(
                    dashboard = dashboard,
                    loading = dashLoading,
                    onAckUrgent = { sender ->
                        scope.launch {
                            app.api.acknowledgeAlert(sender)
                            refreshDashboard()
                        }
                    },
                    onApproveFollowup = { id ->
                        app.voiceController.sendTextCommand("Send follow-up #$id", speakReply = false)
                        refreshDashboard()
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
    voiceUi: tech.aurikrex.falkeai.voice.VoiceUiState,
    wakeRunning: Boolean,
    onTapOrb: (Boolean) -> Unit,
    onSendText: (String) -> Unit,
    onStartCommuteBriefing: () -> Unit,
    onCancelUndo: () -> Unit,
    onTriggerFindPhone: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var attachScreen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (wakeRunning) "24/7 Mic Active • Say \"Hey Falke\", \"Jarvis\", or \"Buddy you there\""
            else "Tap the orb or enable 24/7 Wake Word in Alarms tab",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )

        Spacer(Modifier.height(6.dp))

        // Main Interactive J.A.R.V.I.S. Orb
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = when (voiceUi.state) {
                            VoiceState.LISTENING, VoiceState.FOLLOW_UP_WINDOW ->
                                listOf(Color(0xFF38BDF8), Color(0xFF0369A1))
                            VoiceState.THINKING ->
                                listOf(Color(0xFFA855F7), Color(0xFF6D28D9))
                            VoiceState.SPEAKING ->
                                listOf(Color(0xFF10B981), Color(0xFF047857))
                            VoiceState.IDLE ->
                                listOf(Color(0xFF1E3A8A), Color(0xFF0F172A))
                        }
                    )
                )
                .clickable { onTapOrb(attachScreen) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Talk to FalkeAI",
                tint = Color.White,
                modifier = Modifier.size(54.dp)
            )
        }

        Text(
            text = voiceUi.statusLine,
            color = Color(0xFFCBD5E1),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        // One-Phrase J.A.R.V.I.S. Routines Row (Features I, #8, #3, #1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(onClick = { onSendText("Catch me up") }, label = { Text("⚡ Catch Me Up") })
            AssistChip(onClick = { onSendText("Lock in for 90 minutes") }, label = { Text("🔒 Lock In") })
            AssistChip(onClick = { onSendText("Goodnight") }, label = { Text("🌙 Goodnight") })
            AssistChip(onClick = onStartCommuteBriefing, label = { Text("🎧 Commute Brief") })
            AssistChip(
                onClick = { attachScreen = !attachScreen },
                leadingIcon = { Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp)) },
                label = { Text(if (attachScreen) "Vision ON" else "Vision OFF") }
            )
            AssistChip(onClick = onTriggerFindPhone, label = { Text("🔦 Test Siren") })
        }

        // Feature J: 6-Second Cancel/Undo Countdown Banner
        voiceUi.pendingUndo?.let { pending ->
            Surface(
                color = Color(0xFF451A03),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${pending.summary} (${voiceUi.undoSecondsLeft}s)",
                        color = Color(0xFFFDE68A),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onCancelUndo,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("UNDO", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Live Conversation Output Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (voiceUi.lastTranscript.isNotBlank()) {
                    item {
                        Text("YOU", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(voiceUi.lastTranscript, color = Color(0xFFE2E8F0), fontSize = 14.sp)
                    }
                }
                if (voiceUi.lastReply.isNotBlank()) {
                    item {
                        Text("FALKEAI", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(voiceUi.lastReply, color = Color.White, fontSize = 15.sp)
                    }
                }
                if (voiceUi.lastActions.isNotEmpty()) {
                    item {
                        Text(
                            "Actions: ${voiceUi.lastActions.joinToString(" • ")}",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
                if (voiceUi.lastTranscript.isBlank() && voiceUi.lastReply.isBlank()) {
                    item {
                        Text(
                            "Try saying or typing:\n" +
                                "• \"Call Chidi\" or \"Send SMS to Mom: I'm on my way\"\n" +
                                "• \"Play Burna Boy on Spotify\"\n" +
                                "• \"Check my Gmail for Stripe or interview emails\"\n" +
                                "• \"Set an alarm for 6:30am\" / \"Edit my 6:30 alarm to 7am\"\n" +
                                "• \"Message Tunde on WhatsApp tomorrow at 9am about Physics\"\n" +
                                "• \"Translate to Yoruba: I'll arrive in 20 minutes\"\n" +
                                "• \"Type this: Good morning team!\"",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Fast Text Command Bar (also works 100% offline for Cellular LocalCommandRouter!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask FalkeAI or type offline command…", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val cmd = inputText.trim()
                    if (cmd.isNotEmpty()) {
                        inputText = ""
                        onSendText(cmd)
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8))
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color(0xFF020617))
            }
        }
    }
}

@Composable
private fun BriefingTab(
    dashboard: DashboardResponse?,
    loading: Boolean,
    onAckUrgent: (String) -> Unit,
    onApproveFollowup: (Long) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (dashboard == null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (loading) "Loading unified briefing…" else "Pair your phone in Setup to sync live WhatsApp + Gmail briefings.",
                            color = Color(0xFFCBD5E1)
                        )
                        OutlinedButton(onClick = onRefresh) { Text("Refresh Now") }
                    }
                }
            }
            return@LazyColumn
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Status: ${dashboard.availability.mode.uppercase()}" +
                            if (dashboard.lowBatteryCover) " • DEAD-BATTERY COVER ACTIVE" else "",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(dashboard.catchUpSummary, color = Color.White, fontSize = 14.sp)
                }
            }
        }

        if (dashboard.urgentAlerts.isNotEmpty()) {
            item { Text("🚨 URGENT ALERTS", color = Color(0xFFF43F5E), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            items(dashboard.urgentAlerts) { u ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2B0E17)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(u.contactName ?: u.sender, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(u.summary, color = Color(0xFFFECDD3), fontSize = 13.sp)
                        Button(onClick = { onAckUrgent(u.sender) }) { Text("Acknowledge") }
                    }
                }
            }
        }

        if (dashboard.priorityEmails.isNotEmpty()) {
            item { Text("📧 PRIORITY GMAIL (ALL ACCOUNTS)", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            items(dashboard.priorityEmails.take(8)) { em ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("[${em.accountLabel}] ${em.subject}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("From: ${em.sender}", color = Color(0xFF38BDF8), fontSize = 12.sp)
                        Text(em.snippet, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        }

        if (dashboard.followups.isNotEmpty()) {
            item { Text("📬 SMART FOLLOW-UPS (APPROVAL-FIRST)", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            items(dashboard.followups.take(6)) { f ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("#${f.id} • ${f.contactName ?: f.sender} (${f.status})", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(f.reason, color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Text("Draft: \"${f.suggestedReply}\"", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                        Button(onClick = { onApproveFollowup(f.id) }) { Text("Approve & Send") }
                    }
                }
            }
        }

        if (dashboard.bookings.isNotEmpty()) {
            item { Text("🎓 AURIKREX BOOKINGS", color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            items(dashboard.bookings.take(6)) { b ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${b.name} — ${b.subject} (${b.exam}) • ${b.slot}",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerAndInboxTab(
    dashboard: DashboardResponse?,
    notifications: List<tech.aurikrex.falkeai.data.CapturedNotificationEntity>
) {
    var search by remember { mutableStateOf("") }
    val filteredNotifs = remember(search, notifications) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) notifications
        else notifications.filter {
            it.appLabel.lowercase().contains(q) ||
                it.title.lowercase().contains(q) ||
                it.bodyRedacted.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("💰 BANK & PAYOUT LEDGER (14 DAYS)", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        val alerts = dashboard?.bankAlerts.orEmpty()
        if (alerts.isEmpty()) {
            item {
                Text("No bank/Paystack alerts captured yet. Enable Notification Access in Setup.", color = Color(0xFF94A3B8), fontSize = 13.sp)
            }
        } else {
            items(alerts.take(12)) { b ->
                val isCredit = b.direction == "credit"
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${b.bank} • ${b.counterparty}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(b.rawSummary, color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        Text(
                            text = "${if (isCredit) "+" else "-"}${b.amountFormatted}",
                            color = if (isCredit) Color(0xFF34D399) else Color(0xFFF87171),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text("🔔 ALL-APP & SOCIAL INBOX (OTPs STRIPPED LOCALLY)", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search notifications, X, Instagram, WhatsApp…", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(filteredNotifs.take(40)) { n ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${n.appLabel} • ${n.title}", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(n.bodyRedacted, color = Color(0xFFE2E8F0), fontSize = 12.sp)
                }
            }
        }
    }
}

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

    var alarmHour by remember { mutableStateOf("6") }
    var alarmMinute by remember { mutableStateOf("30") }
    var alarmLabel by remember { mutableStateOf("Morning Wake Up") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Multi-Wake-Word Editor
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("🎙️ 24/7 Multi-Wake-Word Mic", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                if (wakeRunning) "Active (~35 MB RAM on-device)" else "Paused / Standby",
                                color = Color(0xFF94A3B8),
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
                            }
                        )
                    }

                    wakeWords.forEach { phrase ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("• \"$phrase\"", color = Color(0xFF38BDF8), fontSize = 14.sp)
                            if (wakeWords.size > 1) {
                                IconButton(onClick = {
                                    val updated = wakeWords.filterNot { it == phrase }
                                    wakeWords = updated
                                    prefs.wakeWords = updated
                                    FalkeWakeWordService.reloadKeywords(context)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFEF4444))
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newWakeWord,
                            onValueChange = { newWakeWord = it },
                            placeholder = { Text("Add wake phrase (e.g. Yo Falke)…", fontSize = 13.sp) },
                            singleLine = true,
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
                                .clip(CircleShape)
                                .background(Color(0xFF38BDF8))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Black)
                        }
                    }
                }
            }
        }

        // Exact Alarm Creator (Full Set / Edit / Delete CRUD)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⏰ Add Exact FalkeAI Alarm", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = alarmHour,
                            onValueChange = { alarmHour = it.filter(Char::isDigit).take(2) },
                            label = { Text("Hour (0-23)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = alarmMinute,
                            onValueChange = { alarmMinute = it.filter(Char::isDigit).take(2) },
                            label = { Text("Min (0-59)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = alarmLabel,
                        onValueChange = { alarmLabel = it },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val h = alarmHour.toIntOrNull()?.coerceIn(0, 23) ?: 6
                            val m = alarmMinute.toIntOrNull()?.coerceIn(0, 59) ?: 30
                            onCreateAlarm(h, m, alarmLabel)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set Exact Alarm")
                    }
                }
            }
        }

        items(alarms) { alarm ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            FalkeAlarmManager.formatAlarmTime(alarm.hour, alarm.minute),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(alarm.label, color = Color(0xFF94A3B8), fontSize = 13.sp)
                    }
                    Switch(checked = alarm.enabled, onCheckedChange = { onToggleAlarm(alarm.id, it) })
                    IconButton(onClick = { onDeleteAlarm(alarm.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupAndInfinixTab(onPaired: () -> Unit) {
    val context = LocalContext.current
    val app = FalkeApplication.instance
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(app.prefs.serverUrl.ifBlank { "https://your-app.herokuapp.com" }) }
    var pairCode by remember { mutableStateOf("") }
    var pairStatus by remember {
        mutableStateOf(
            if (app.prefs.isPaired()) "✅ Paired with ${app.prefs.serverUrl}" else "Send `pair android` in WhatsApp self-chat to get a 5-min FALKE-XXXXXX code."
        )
    }
    var health by remember { mutableStateOf(OemSurvivalHelper.inspectHealth(context)) }
    var btReadout by remember { mutableStateOf(app.prefs.btAutoReadoutEnabled) }
    var bilingual by remember { mutableStateOf(app.prefs.bilingualMode) }
    var screenVision by remember { mutableStateOf(app.prefs.screenVisionEnabled) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        health = OemSurvivalHelper.inspectHealth(context)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1) Pairing Card
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🔗 Pair With FalkeAI Server", color = Color.White, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Heroku HTTPS URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it.uppercase() },
                        label = { Text("5-Min Pairing Code (FALKE-XXXXXX)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(pairStatus, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Button(
                        onClick = {
                            pairStatus = "Pairing…"
                            scope.launch {
                                app.api.pair(serverUrl, pairCode, "${Build.MANUFACTURER} ${Build.MODEL}")
                                    .onSuccess {
                                        pairStatus = "✅ Paired! Token saved in Android Keystore."
                                        onPaired()
                                    }
                                    .onFailure { pairStatus = "❌ ${it.message}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pair Phone Now")
                    }
                }
            }
        }

        // 2) Core Permissions Batch Button
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🔐 Runtime Permissions (Mic, Calls, SMS, Contacts, Calendar)", color = Color.White, fontWeight = FontWeight.Bold)
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Phone & Voice Permissions")
                    }
                }
            }
        }

        // 3) Infinix Hot 10T / XOS 7.6 Survival Health Checklist
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("🛡️ Infinix Hot 10T / XOS Survival Pack", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { health = OemSurvivalHelper.inspectHealth(context) }) {
                            Text("Recheck", fontSize = 12.sp)
                        }
                    }
                    Text("Device: ${health.manufacturerLabel}", color = Color(0xFF94A3B8), fontSize = 12.sp)

                    SurvivalRow("Battery Unrestricted (No XOS Doze Kill)", health.batteryUnrestricted) {
                        OemSurvivalHelper.requestIgnoreBatteryOptimizations(context)
                    }
                    SurvivalRow("Phone Master Auto-Start & Protected App", false, "Open XOS") {
                        OemSurvivalHelper.openInfinixAutoStart(context)
                    }
                    SurvivalRow("All-App Notifications & Bank Alerts", health.notificationListenerOn) {
                        OemSurvivalHelper.openNotificationListenerSettings(context)
                    }
                    SurvivalRow("Screen Vision, Dictation & Social Search", health.accessibilityOn) {
                        OemSurvivalHelper.openAccessibilitySettings(context)
                    }
                    SurvivalRow("Do Not Disturb Override & Control", health.dndPolicyGranted) {
                        OemSurvivalHelper.openDndAccessSettings(context)
                    }
                    SurvivalRow("Default Digital Assistant (Replace Gemini)", false, "Set Default") {
                        OemSurvivalHelper.openDefaultAssistantSettings(context)
                    }
                    SurvivalRow("Draw Over Apps (Floating Orb)", health.overlayGranted) {
                        OemSurvivalHelper.openOverlaySettings(context)
                    }
                    Text(
                        "Tip for Infinix XOS 7.6: Open Recent Apps -> pull DOWN on FalkeAI card -> tap the Padlock 🔒 icon so XOS never clears it from RAM!",
                        color = Color(0xFFFDE68A),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 4) Superpower Toggles
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚡ Assistant Superpowers", color = Color.White, fontWeight = FontWeight.Bold)
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
}

@Composable
private fun SurvivalRow(
    title: String,
    ok: Boolean,
    buttonLabel: String = if (ok) "Done" else "Fix",
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${if (ok) "✅" else "⚠️"} $title",
            color = if (ok) Color(0xFF34D399) else Color(0xFFE2E8F0),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = onClick) { Text(buttonLabel, fontSize = 12.sp) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFE2E8F0), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
