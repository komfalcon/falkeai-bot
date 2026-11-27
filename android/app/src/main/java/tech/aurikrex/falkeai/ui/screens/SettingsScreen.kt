package tech.aurikrex.falkeai.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.aurikrex.falkeai.data.ServerSettingsDto
import tech.aurikrex.falkeai.monitors.OemSurvivalHelper
import tech.aurikrex.falkeai.monitors.SurvivalHealthReport
import tech.aurikrex.falkeai.security.DeviceCredentialGate
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.GlassCard
import tech.aurikrex.falkeai.ui.LocalSettingsSnapshot
import tech.aurikrex.falkeai.ui.QuickRoutinePill
import tech.aurikrex.falkeai.ui.SectionHeader
import tech.aurikrex.falkeai.ui.StatusPill
import tech.aurikrex.falkeai.voice.FalkeWakeWordService

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: LocalSettingsSnapshot,
    health: SurvivalHealthReport,
    serverSettings: ServerSettingsDto?,
    serverHealthy: Boolean?,
    onRefreshLiveState: () -> Unit,
    onPingHealth: (String?) -> Unit,
    onPairDevice: (serverUrl: String, code: String, label: String) -> Unit,
    onRequestProtectedUnpair: () -> Unit,
    onToggleFloatingBubble: (Boolean) -> Unit,
    onToggleAppLock: (Boolean) -> Unit,
    onToggleSensitiveLock: (Boolean) -> Unit,
    onToggleConfirmUnknown: (Boolean) -> Unit,
    onSaveFallbackPin: (String) -> Unit,
    onToggleSpeakTyped: (Boolean) -> Unit,
    onTogglePersistChat: (Boolean) -> Unit,
    onToggleScreenVision: (Boolean) -> Unit,
    onToggleBtReadout: (Boolean) -> Unit,
    onToggleBilingual: (Boolean) -> Unit,
    onConfirmInfinixAutoStart: (Boolean) -> Unit,
    onUpdateServerSetting: (digestHour: Int?, replyMode: String?, voiceMode: String?) -> Unit,
    onClearChatHistory: () -> Unit,
    onClearNotifications: () -> Unit
) {
    val context = LocalContext.current
    val hasSystemLock = remember(health) { DeviceCredentialGate.isDeviceLockConfigured(context) }

    var showPairForm by remember(settings.isPaired) { mutableStateOf(!settings.isPaired) }
    var urlInput by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var codeInput by remember { mutableStateOf("") }
    var showFallbackPinDialog by remember { mutableStateOf(false) }
    var pinDraft by remember { mutableStateOf("") }
    var confirmUnpairDialog by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        onRefreshLiveState()
        if (grantedMap[Manifest.permission.RECORD_AUDIO] == true && settings.wakeWordEnabled) {
            FalkeWakeWordService.start(context)
        }
    }

    if (showFallbackPinDialog) {
        AlertDialog(
            onDismissRequest = { showFallbackPinDialog = false },
            containerColor = FalkePalette.CardSurface,
            titleContentColor = FalkePalette.TextPrimary,
            textContentColor = FalkePalette.TextBody,
            title = { Text("Set FalkeAI Fallback PIN", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Used whenever your phone does not have an Android system PIN/Pattern enabled.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = pinDraft,
                        onValueChange = { pinDraft = it.filter(Char::isDigit).take(8) },
                        label = { Text("4–8 Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinDraft.length >= 4) {
                            onSaveFallbackPin(pinDraft)
                            pinDraft = ""
                            showFallbackPinDialog = false
                        }
                    }
                ) { Text("Save PIN") }
            },
            dismissButton = {
                TextButton(onClick = { showFallbackPinDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmUnpairDialog) {
        AlertDialog(
            onDismissRequest = { confirmUnpairDialog = false },
            containerColor = FalkePalette.CardSurface,
            titleContentColor = FalkePalette.TextPrimary,
            textContentColor = FalkePalette.TextBody,
            title = { Text("Unpair This Phone?", fontWeight = FontWeight.Bold) },
            text = { Text("This revokes your encrypted bearer token on the server and disconnects real-time alerts until you pair again.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmUnpairDialog = false
                        onRequestProtectedUnpair()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Crimson)
                ) { Text("Unpair Device", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpairDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Keystore Reset Notice if hardware key was ever invalidated
        if (settings.keystoreResetNotice) {
            item {
                GlassCard(backgroundColor = FalkePalette.CardAmber, borderColor = FalkePalette.Amber) {
                    Text("Hardware Keystore Reset", color = FalkePalette.AmberSoft, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Android invalidated the previous encryption key, so FalkeAI wiped the old encrypted store instead of falling back to plaintext. Re-pair your phone below.", color = FalkePalette.TextBody, fontSize = 12.sp)
                }
            }
        }

        // 1) Floating Gemini/Claude Bar & Live Device Readiness (Auto-Updated on ON_RESUME!)
        item { SectionHeader("Floating Bar & Live System Status", FalkePalette.Cyan) }

        item {
            GlassCard(borderColor = FalkePalette.BorderCyan) {
                SettingToggleRow(
                    title = "Gemini / Claude Floating Bar",
                    subtitle = if (health.overlayGranted) "Show edge pill & instant floating bar over other apps"
                    else "Requires 'Display over other apps' permission below",
                    checked = settings.floatingBubbleEnabled && health.overlayGranted,
                    onChange = onToggleFloatingBubble
                )

                HorizontalDivider(color = FalkePalette.BorderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Shield, null, tint = FalkePalette.Cyan, modifier = Modifier.size(20.dp))
                        Column {
                            Text("System Readiness (Live)", color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "${health.systemReadinessCount} of ${health.systemReadinessTotal} active • ${health.manufacturerLabel}",
                                color = FalkePalette.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    StatusPill(
                        text = "${health.systemReadinessCount}/${health.systemReadinessTotal}",
                        color = if (health.systemReadinessCount == health.systemReadinessTotal) FalkePalette.EmeraldBright else FalkePalette.Cyan
                    )
                }

                LinearProgressIndicator(
                    progress = { health.systemReadinessCount.toFloat() / health.systemReadinessTotal.toFloat() },
                    color = if (health.systemReadinessCount == health.systemReadinessTotal) FalkePalette.EmeraldBright else FalkePalette.Cyan,
                    trackColor = FalkePalette.CardElevated,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp))
                )

                // Runtime Permissions Live Row + Granular Chips
                LiveChecklistRow(
                    title = "Runtime Permissions (${health.grantedRuntimeCount}/${health.totalRuntimeCount})",
                    subtitle = if (health.allRuntimeGranted) "Mic, Calls, SMS, Contacts, Calendar & Camera active" else "Tap Grant to enable missing permissions",
                    ok = health.allRuntimeGranted,
                    actionLabel = if (health.allRuntimeGranted) "Granted" else "Grant"
                ) {
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
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    health.runtimePermissionsList.forEach { (name, granted) ->
                        val c = if (granted) FalkePalette.EmeraldBright else FalkePalette.Amber
                        Surface(
                            color = c.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, c.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "${if (granted) "✓" else "!"} $name",
                                color = c,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                LiveChecklistRow(
                    title = "Battery Unrestricted",
                    subtitle = if (health.batteryUnrestricted) "Protected from Android Doze kill" else "Allow unrestricted background use",
                    ok = health.batteryUnrestricted
                ) { OemSurvivalHelper.requestIgnoreBatteryOptimizations(context) }

                LiveChecklistRow(
                    title = "All-App Notifications & Bank Alerts",
                    subtitle = if (health.notificationListenerOn) "Notification Listener connected" else "Enable Notification Access",
                    ok = health.notificationListenerOn
                ) { OemSurvivalHelper.openNotificationListenerSettings(context) }

                LiveChecklistRow(
                    title = "Screen Vision, Dictation & Social",
                    subtitle = if (health.accessibilityOn) "Accessibility Service connected" else "Enable FalkeAI in Accessibility",
                    ok = health.accessibilityOn
                ) { OemSurvivalHelper.openAccessibilitySettings(context) }

                LiveChecklistRow(
                    title = "Do Not Disturb Control",
                    subtitle = if (health.dndPolicyGranted) "DND policy access granted" else "Allow DND override for Lock In & Sleep",
                    ok = health.dndPolicyGranted
                ) { OemSurvivalHelper.openDndAccessSettings(context) }

                LiveChecklistRow(
                    title = "Display Over Other Apps",
                    subtitle = if (health.overlayGranted) "Floating bar overlay allowed" else "Required for floating Gemini/Claude bar",
                    ok = health.overlayGranted
                ) { OemSurvivalHelper.openOverlaySettings(context) }

                LiveChecklistRow(
                    title = "Default Digital Assistant",
                    subtitle = if (health.defaultAssistantActive) "FalkeAI is your system assistant" else "Optional: summon Fall-K on Power/Home hold",
                    ok = health.defaultAssistantActive,
                    actionLabel = if (health.defaultAssistantActive) "Active" else "Set"
                ) { OemSurvivalHelper.openDefaultAssistantSettings(context) }

                if (health.isInfinixOrTranssion) {
                    HorizontalDivider(color = FalkePalette.BorderSubtle)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Checkbox(
                                checked = settings.infinixAutoStartConfirmed,
                                onCheckedChange = onConfirmInfinixAutoStart,
                                colors = CheckboxDefaults.colors(checkedColor = FalkePalette.EmeraldBright)
                            )
                            Column {
                                Text("Infinix Phone Master Auto-Start", color = FalkePalette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("XOS hides this state from apps — check after enabling in Phone Master", color = FalkePalette.TextMuted, fontSize = 11.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = { OemSurvivalHelper.openInfinixAutoStart(context) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Open XOS", fontSize = 11.sp, color = FalkePalette.Cyan)
                        }
                    }
                }
            }
        }

        // 2) Security & PIN / Pattern Gate
        item { SectionHeader("Security & PIN / Pattern Lock", FalkePalette.EmeraldBright) }

        item {
            GlassCard {
                StatusPill(
                    text = if (hasSystemLock) "ANDROID SYSTEM PIN / PATTERN DETECTED"
                    else if (settings.hasFallbackPin) "FALLBACK APP PIN CONFIGURED"
                    else "NO PHONE SCREEN LOCK DETECTED",
                    color = if (hasSystemLock || settings.hasFallbackPin) FalkePalette.EmeraldBright else FalkePalette.Amber,
                    dot = true
                )

                SettingToggleRow(
                    title = "Require PIN / Pattern for Sensitive Actions",
                    subtitle = "Verify your PIN/Pattern before sending/trashing email, calling, texting, or unpairing",
                    checked = settings.requireLockForSensitive,
                    onChange = onToggleSensitiveLock
                )
                SettingToggleRow(
                    title = "Lock FalkeAI App on Launch",
                    subtitle = "Require PIN/Pattern when opening the FalkeAI app",
                    checked = settings.appLockEnabled,
                    onChange = onToggleAppLock
                )
                SettingToggleRow(
                    title = "Confirm Unsaved Phone Numbers",
                    subtitle = "Open dialer/SMS preview instead of auto-sending for numbers not in Contacts",
                    checked = settings.confirmUnknownNumbers,
                    onChange = onToggleConfirmUnknown
                )

                if (!hasSystemLock) {
                    OutlinedButton(
                        onClick = { showFallbackPinDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (settings.hasFallbackPin) "Change Fallback PIN" else "Set Fallback PIN")
                    }
                }
            }
        }

        // 3) Real Cloud Server Settings (Synced with Turso)
        if (settings.isPaired) {
            item { SectionHeader("Cloud Server Settings (Live Sync)", FalkePalette.Violet) }

            item {
                GlassCard {
                    val srv = serverSettings
                    if (srv == null) {
                        Text("Loading cloud preferences…", color = FalkePalette.TextSecondary, fontSize = 12.sp)
                    } else {
                        Text("Morning Briefing Hour (Lagos)", color = FalkePalette.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(6 to "6 AM", 7 to "7 AM", 8 to "8 AM", 9 to "9 AM").forEach { (hr, lbl) ->
                                QuickRoutinePill(lbl, Icons.Default.CloudSync, FalkePalette.Violet, active = srv.digestHourLagos == hr) {
                                    onUpdateServerSetting(hr, null, null)
                                }
                            }
                        }

                        HorizontalDivider(color = FalkePalette.BorderSubtle)
                        Text("WhatsApp Auto-Reply Scope", color = FalkePalette.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("everyone" to "Everyone", "contacts" to "Contacts", "off" to "Paused").forEach { (mode, lbl) ->
                                QuickRoutinePill(lbl, Icons.Default.CloudSync, FalkePalette.Cyan, active = srv.replyMode == mode) {
                                    onUpdateServerSetting(null, mode, null)
                                }
                            }
                        }

                        HorizontalDivider(color = FalkePalette.BorderSubtle)
                        Text("WhatsApp Self-Chat Voice Notes", color = FalkePalette.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "Auto", "on" to "Always Voice", "off" to "Text Only").forEach { (mode, lbl) ->
                                QuickRoutinePill(lbl, Icons.Default.CloudSync, FalkePalette.EmeraldBright, active = srv.selfVoiceMode == mode) {
                                    onUpdateServerSetting(null, null, mode)
                                }
                            }
                        }

                        HorizontalDivider(color = FalkePalette.BorderSubtle)
                        Text(
                            "Connected Gmails: ${srv.gmailCount}  •  ntfy Push: ${if (srv.ntfyConfigured) "Active" else "Not set"}  •  Paired Phones: ${srv.pairedDevicesCount}",
                            color = FalkePalette.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // 4) Assistant Behaviour
        item { SectionHeader("Assistant Behaviour", FalkePalette.Cyan) }

        item {
            GlassCard {
                SettingToggleRow("Auto-Speak Typed Replies", "Read chat answers aloud even when typed", settings.speakTypedReplies, onToggleSpeakTyped)
                SettingToggleRow("Save Chat History on Phone", "Keep conversation bubbles across app restarts", settings.persistChatHistory, onTogglePersistChat)
                SettingToggleRow("Silent Screen Vision", "Allow Fall-K to inspect screen when you ask", settings.screenVisionEnabled, onToggleScreenVision)
                SettingToggleRow("Bluetooth Earbuds Readout", "Read priority alerts over connected earbuds", settings.btAutoReadoutEnabled, onToggleBtReadout)
                SettingToggleRow("Bilingual Mode", "Fluent English, Yoruba, Nigerian Pidgin & French", settings.bilingualMode, onToggleBilingual)
            }
        }

        // 5) Server Connection & Data Privacy
        item { SectionHeader("Server Connection & Data", FalkePalette.Amber) }

        item {
            GlassCard(borderColor = if (settings.isPaired) FalkePalette.BorderEmerald else FalkePalette.BorderCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showPairForm = !showPairForm },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        Icon(
                            if (settings.isPaired) Icons.Default.CheckCircle else Icons.Default.Warning,
                            null,
                            tint = if (settings.isPaired) FalkePalette.EmeraldBright else FalkePalette.Amber
                        )
                        Column {
                            Text(
                                if (settings.isPaired) "Paired (${settings.deviceName})" else "Pair With FalkeAI Server",
                                color = FalkePalette.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                if (settings.isPaired) settings.serverUrl else "Send `pair android` in WhatsApp self-chat for a 5-min code",
                                color = FalkePalette.TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(if (showPairForm) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = FalkePalette.TextMuted)
                }

                if (serverHealthy != null) {
                    StatusPill(
                        text = if (serverHealthy) "SERVER /HEALTH ONLINE" else "SERVER UNREACHABLE",
                        color = if (serverHealthy) FalkePalette.EmeraldBright else FalkePalette.Crimson,
                        dot = true
                    )
                }

                AnimatedVisibility(visible = showPairForm) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(color = FalkePalette.BorderSubtle)
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Server HTTPS URL") },
                            placeholder = { Text("https://your-app.herokuapp.com") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it.uppercase() },
                            label = { Text("5-Min Pairing Code (FALKE-...)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onPairDevice(urlInput, codeInput, "${Build.MANUFACTURER} ${Build.MODEL}")
                                    codeInput = ""
                                },
                                enabled = urlInput.isNotBlank() && codeInput.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pair Phone", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onPingHealth(urlInput) },
                                enabled = urlInput.isNotBlank() || settings.serverUrl.isNotBlank(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Test /health", color = FalkePalette.Cyan)
                            }
                        }
                    }
                }

                HorizontalDivider(color = FalkePalette.BorderSubtle)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onClearChatHistory,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Chat", fontSize = 12.sp, color = FalkePalette.TextBody)
                    }
                    OutlinedButton(
                        onClick = onClearNotifications,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Feed", fontSize = 12.sp, color = FalkePalette.TextBody)
                    }
                }
                if (settings.isPaired) {
                    OutlinedButton(
                        onClick = { confirmUnpairDialog = true },
                        border = BorderStroke(1.dp, FalkePalette.Crimson.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unpair This Phone & Revoke Token", color = FalkePalette.CrimsonSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChecklistRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    actionLabel: String = if (ok) "Active" else "Fix",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (ok) FalkePalette.EmeraldBright else FalkePalette.Amber)
            )
            Column {
                Text(title, color = FalkePalette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = FalkePalette.TextMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            color = if (ok) FalkePalette.Emerald.copy(alpha = 0.14f) else FalkePalette.Cyan.copy(alpha = 0.16f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (ok) FalkePalette.BorderEmerald else FalkePalette.BorderCyan),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        ) {
            Text(
                text = actionLabel,
                color = if (ok) FalkePalette.EmeraldBright else FalkePalette.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FalkePalette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = FalkePalette.TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03111F), checkedTrackColor = FalkePalette.Cyan)
        )
    }
}
