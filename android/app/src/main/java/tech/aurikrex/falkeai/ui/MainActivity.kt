package tech.aurikrex.falkeai.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.assist.FalkeOverlayService
import tech.aurikrex.falkeai.executors.FalkeAlarmManager
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor
import tech.aurikrex.falkeai.monitors.FalkeBridgeService
import tech.aurikrex.falkeai.security.DeviceCredentialGate
import tech.aurikrex.falkeai.ui.screens.BriefingScreen
import tech.aurikrex.falkeai.ui.screens.ChatScreen
import tech.aurikrex.falkeai.ui.screens.LedgerScreen
import tech.aurikrex.falkeai.ui.screens.SettingsScreen
import tech.aurikrex.falkeai.ui.screens.VoiceStudioScreen
import tech.aurikrex.falkeai.voice.FalkeWakeWordService
import tech.aurikrex.falkeai.voice.VoiceState

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

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
                FalkeMainShell(vm = vm)
            }
        }
    }

    override fun onStop() {
        val app = FalkeApplication.instance
        if (app.prefs.appLockEnabled) {
            DeviceCredentialGate.lockAppSession()
        }
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FalkeMainShell(vm: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = FalkeApplication.instance
    val scope = rememberCoroutineScope()

    val settings by vm.localSettings.collectAsState()
    val health by vm.health.collectAsState()
    val dashboard by vm.dashboard.collectAsState()
    val dashLoading by vm.dashLoading.collectAsState()
    val dashError by vm.dashError.collectAsState()
    val serverSettings by vm.serverSettings.collectAsState()
    val serverHealthy by vm.serverHealthy.collectAsState()
    val customVoice by vm.customVoiceStatus.collectAsState()
    val banner by vm.statusBanner.collectAsState()

    val sseConnected by app.api.sseConnected.collectAsState()
    val wakeRunning by FalkeWakeWordService.running.collectAsState()
    val voiceUi by app.voiceController.uiState.collectAsState()
    val alarms by app.db.alarmDao().observeAll().collectAsState(initial = emptyList())
    val notifs by app.db.notificationDao().observeRecent(80).collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(if (settings.isPaired) 0 else 4) }
    var appUnlocked by remember(settings.appLockEnabled) {
        mutableStateOf(DeviceCredentialGate.isAppUnlocked(app.prefs))
    }

    // Pending PIN/Pattern-protected callback
    var pendingProtectedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showFallbackPinPrompt by remember { mutableStateOf(false) }
    var fallbackPinInput by remember { mutableStateOf("") }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            DeviceCredentialGate.markVerifiedNow()
            appUnlocked = true
            val act = pendingProtectedAction
            pendingProtectedAction = null
            act?.invoke()
        } else {
            pendingProtectedAction = null
            vm.postBanner("PIN / Pattern verification cancelled")
        }
    }

    fun requireOwnerAuth(reason: String, action: () -> Unit) {
        if (DeviceCredentialGate.isSensitiveActionRecentlyVerified()) {
            action()
            return
        }
        val sysIntent = DeviceCredentialGate.buildSystemCredentialIntent(context, "Authorize FalkeAI", reason)
        if (sysIntent != null) {
            pendingProtectedAction = action
            credentialLauncher.launch(sysIntent)
        } else if (app.prefs.hasFallbackPin()) {
            pendingProtectedAction = action
            fallbackPinInput = ""
            showFallbackPinPrompt = true
        } else {
            // No lockscreen or fallback PIN configured on device yet
            action()
        }
    }

    // Live ON_RESUME refresh so returning from Android Settings immediately updates every check!
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    FalkeOverlayService.setSuppressedForAppForeground(true)
                    appUnlocked = DeviceCredentialGate.isAppUnlocked(app.prefs)
                    vm.refreshAllLiveState(fetchRemote = true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    FalkeOverlayService.setSuppressedForAppForeground(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showFallbackPinPrompt) {
        AlertDialog(
            onDismissRequest = {
                showFallbackPinPrompt = false
                pendingProtectedAction = null
            },
            containerColor = FalkePalette.CardSurface,
            titleContentColor = FalkePalette.TextPrimary,
            textContentColor = FalkePalette.TextBody,
            title = { Text("Enter FalkeAI PIN", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = fallbackPinInput,
                    onValueChange = { fallbackPinInput = it.filter(Char::isDigit).take(8) },
                    label = { Text("PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (app.prefs.verifyFallbackPin(fallbackPinInput)) {
                        DeviceCredentialGate.markVerifiedNow()
                        appUnlocked = true
                        showFallbackPinPrompt = false
                        val act = pendingProtectedAction
                        pendingProtectedAction = null
                        act?.invoke()
                    } else {
                        vm.postBanner("Incorrect PIN")
                    }
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFallbackPinPrompt = false
                    pendingProtectedAction = null
                }) { Text("Cancel") }
            }
        )
    }

    // Full-Screen App Lock Gate if App Lock is enabled
    if (settings.appLockEnabled && !appUnlocked) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FalkePalette.BgDeep)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(borderColor = FalkePalette.BorderCyan) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Lock, null, tint = FalkePalette.Cyan, modifier = Modifier.size(44.dp))
                    Text("FalkeAI Locked", color = FalkePalette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Unlock with your phone PIN or Pattern to continue.", color = FalkePalette.TextSecondary, fontSize = 13.sp)
                    Button(
                        onClick = {
                            requireOwnerAuth("Unlock FalkeAI") { appUnlocked = true }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Unlock with PIN / Pattern", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    val attentionCount = (dashboard?.urgentAlerts?.size ?: 0) + (dashboard?.pendingFlags?.size ?: 0)
    val navColors = NavigationBarItemDefaults.colors(
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
                                    .background(Brush.linearGradient(listOf(FalkePalette.Cyan, FalkePalette.Emerald))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Bolt, null, tint = Color(0xFF03111F), modifier = Modifier.size(20.dp))
                            }
                            Text("FalkeAI", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = FalkePalette.TextPrimary)
                            val badgeColor = when {
                                sseConnected -> FalkePalette.EmeraldBright
                                settings.isPaired -> FalkePalette.Amber
                                else -> FalkePalette.TextMuted
                            }
                            val badgeText = when {
                                sseConnected -> "LIVE"
                                settings.isPaired -> "SYNC"
                                else -> "UNPAIRED"
                            }
                            StatusPill(text = badgeText, color = badgeColor, dot = true)
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.setSpeakTypedReplies(!settings.speakTypedReplies) }) {
                            Icon(
                                imageVector = if (settings.speakTypedReplies) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = "Toggle Auto-Speak",
                                tint = if (settings.speakTypedReplies) FalkePalette.Cyan else FalkePalette.TextMuted
                            )
                        }
                        IconButton(onClick = { vm.refreshAllLiveState(fetchRemote = true) }, enabled = !dashLoading) {
                            if (dashLoading) {
                                CircularProgressIndicator(color = FalkePalette.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Default.Refresh, "Refresh", tint = FalkePalette.TextSecondary)
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
                NavigationBar(containerColor = FalkePalette.BgNavBar, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                        label = { Text("Chat", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            vm.refreshDashboard()
                        },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (attentionCount > 0) {
                                        Badge(containerColor = FalkePalette.Crimson) {
                                            Text(attentionCount.coerceAtMost(9).toString(), color = Color.White, fontSize = 10.sp)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Dashboard, null)
                            }
                        },
                        label = { Text("Briefing", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            vm.refreshDashboard()
                        },
                        icon = { Icon(Icons.Default.AccountBalanceWallet, null) },
                        label = { Text("Ledger", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.GraphicEq, null) },
                        label = { Text("Voice", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = {
                            selectedTab = 4
                            vm.refreshAllLiveState(fetchRemote = true)
                        },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (health.systemReadinessCount < 5) {
                                        Badge(containerColor = FalkePalette.Amber)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Settings, null)
                            }
                        },
                        label = { Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = navColors
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF081022), FalkePalette.BgDeep, FalkePalette.BgDeep)))
                .padding(padding)
        ) {
            // Non-blocking Top Feedback Banner
            AnimatedVisibility(visible = banner != null) {
                banner?.let { msg ->
                    Surface(
                        color = FalkePalette.CardElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, FalkePalette.BorderCyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(msg, color = FalkePalette.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = FalkePalette.TextMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { vm.dismissBanner() }
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> ChatScreen(
                        voiceUi = voiceUi,
                        settings = settings,
                        customVoice = customVoice,
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
                            if (settings.requireLockForSensitive && DeviceCredentialGate.isSensitiveCommand(txt)) {
                                requireOwnerAuth("Confirm sensitive command: ${txt.take(48)}") {
                                    app.voiceController.sendTextCommand(txt, captureScreen = withScreen)
                                }
                            } else {
                                app.voiceController.sendTextCommand(txt, captureScreen = withScreen)
                            }
                        },
                        onUnlockVoiceBypassWithPin = {
                            requireOwnerAuth("Override Voice Lock") {
                                app.voiceController.startVoiceTurn(captureScreen = false, followUpChain = true, bypassVoiceLockOnce = true)
                            }
                        },
                        onReplayMessage = { msg -> app.voiceController.replayMessage(msg) },
                        onClearChat = { app.voiceController.clearHistory() },
                        onStartCommuteBriefing = { app.voiceController.startCommuteBriefing() },
                        onCancelUndo = { app.voiceController.cancelPendingUndo() },
                        onTriggerFindPhone = { PhoneMediaExecutor.triggerFindMyPhoneSiren(context, 8) }
                    )
                    1 -> BriefingScreen(
                        dashboard = dashboard,
                        loading = dashLoading,
                        error = dashError,
                        onSetAvailabilityCommand = { cmd ->
                            app.voiceController.sendTextCommand(cmd, speakReply = false)
                            scope.launch {
                                kotlinx.coroutines.delay(900)
                                vm.refreshDashboard()
                            }
                        },
                        onAckUrgent = { sender ->
                            scope.launch {
                                app.api.acknowledgeAlert(sender)
                                vm.refreshDashboard()
                            }
                        },
                        onDismissFlag = { chat ->
                            scope.launch {
                                app.api.dismissFlag(chat)
                                vm.refreshDashboard()
                            }
                        },
                        onDismissAllFlags = {
                            scope.launch {
                                app.api.dismissAllFlags()
                                vm.refreshDashboard()
                            }
                        },
                        onApproveFollowup = { id ->
                            val run = {
                                app.voiceController.sendTextCommand("Send follow-up #$id", speakReply = false)
                                vm.refreshDashboard()
                            }
                            if (settings.requireLockForSensitive) requireOwnerAuth("Approve & send WhatsApp follow-up #$id", run)
                            else run()
                        },
                        onEmailAction = { cmd, sensitive ->
                            val run = {
                                selectedTab = 0
                                app.voiceController.sendTextCommand(cmd, speakReply = false)
                            }
                            if (sensitive && settings.requireLockForSensitive) {
                                requireOwnerAuth("Confirm Gmail action", run)
                            } else {
                                run()
                            }
                        },
                        onRefresh = { vm.refreshDashboard() }
                    )
                    2 -> LedgerScreen(
                        dashboard = dashboard,
                        notifications = notifs,
                        onClearNotifications = { vm.clearCapturedNotifications() }
                    )
                    3 -> VoiceStudioScreen(
                        settings = settings,
                        customVoice = customVoice,
                        alarms = alarms,
                        wakeRunning = wakeRunning,
                        onToggleVoiceLock = { vm.setVoiceLockEnabled(it) },
                        onVoiceThresholdChange = { vm.setVoiceMatchThreshold(it) },
                        onEnrollPcmSamples = { samples, cb ->
                            vm.enrollVoiceprintFromPcmList(samples) { res ->
                                res.fold(
                                    onSuccess = { s -> cb("✅ Enrolled ${s.sampleCount} samples (${(s.consistency * 100).toInt()}% consistency)!") },
                                    onFailure = { e -> cb("❌ ${e.message}") }
                                )
                            }
                        },
                        onEnrollFromStudio = { cb ->
                            vm.enrollVoiceprintFromStudioTakes { res ->
                                res.fold(
                                    onSuccess = { s -> cb("✅ Enrolled from ${s.sampleCount} studio clips (${(s.consistency * 100).toInt()}% consistency)!") },
                                    onFailure = { e -> cb("❌ ${e.message}") }
                                )
                            }
                        },
                        onClearVoiceprint = {
                            requireOwnerAuth("Reset enrolled voiceprint") { vm.clearVoiceprint() }
                        },
                        onImportVoiceZip = { uri -> vm.importCustomVoiceZip(uri) },
                        onToggleCustomVoice = { vm.setCustomVoiceEnabled(it) },
                        onSpeechSpeedChange = { vm.setSpeechSpeed(it) },
                        onRemoveCustomVoice = { vm.removeCustomVoice() },
                        onTestSpeakSample = { sample -> app.voiceController.replayMessage(sample) },
                        onToggleWakeWord = { vm.setWakeWordEnabled(it) },
                        onAddWakePhrase = { vm.addWakePhrase(it) },
                        onRemoveWakePhrase = { vm.removeWakePhrase(it) },
                        onCreateAlarm = { h, m, lbl ->
                            scope.launch { FalkeAlarmManager.createAlarm(context, app.db, h, m, lbl) }
                        },
                        onToggleAlarm = { id, en ->
                            scope.launch { FalkeAlarmManager.toggleAlarm(context, app.db, id, en) }
                        },
                        onDeleteAlarm = { id ->
                            scope.launch { FalkeAlarmManager.deleteAlarmById(context, app.db, id) }
                        },
                        onBanner = { vm.postBanner(it) }
                    )
                    4 -> SettingsScreen(
                        settings = settings,
                        health = health,
                        serverSettings = serverSettings,
                        serverHealthy = serverHealthy,
                        onRefreshLiveState = { vm.refreshAllLiveState(fetchRemote = true) },
                        onPingHealth = { url -> vm.pingServerHealth(url) },
                        onPairDevice = { url, code, lbl ->
                            vm.pairDevice(url, code, lbl) { selectedTab = 0 }
                        },
                        onRequestProtectedUnpair = {
                            requireOwnerAuth("Unpair phone from FalkeAI") { vm.unpairThisPhone(revokeAll = false) }
                        },
                        onToggleFloatingBubble = { vm.setFloatingBubble(it) },
                        onToggleAppLock = { vm.setAppLock(it) },
                        onToggleSensitiveLock = { vm.setRequireSensitiveLock(it) },
                        onToggleConfirmUnknown = { vm.setConfirmUnknownNumbers(it) },
                        onSaveFallbackPin = { vm.setFallbackPin(it) },
                        onToggleSpeakTyped = { vm.setSpeakTypedReplies(it) },
                        onTogglePersistChat = { vm.setPersistChatHistory(it) },
                        onToggleScreenVision = { vm.setScreenVision(it) },
                        onToggleBtReadout = { vm.setBtReadout(it) },
                        onToggleBilingual = { vm.setBilingualMode(it) },
                        onConfirmInfinixAutoStart = { vm.setInfinixAutoStartConfirmed(it) },
                        onUpdateServerSetting = { hr, rm, vmMode -> vm.updateServerSetting(hr, rm, vmMode) },
                        onClearChatHistory = {
                            requireOwnerAuth("Clear chat history") {
                                app.voiceController.clearHistory()
                                vm.postBanner("Cleared chat history")
                            }
                        },
                        onClearNotifications = { vm.clearCapturedNotifications() }
                    )
                }
            }
        }
    }
}
