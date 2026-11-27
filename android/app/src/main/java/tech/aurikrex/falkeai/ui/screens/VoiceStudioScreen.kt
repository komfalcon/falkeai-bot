package tech.aurikrex.falkeai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.data.FalkeAlarmEntity
import tech.aurikrex.falkeai.executors.FalkeAlarmManager
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.GlassCard
import tech.aurikrex.falkeai.ui.LocalSettingsSnapshot
import tech.aurikrex.falkeai.ui.QuickRoutinePill
import tech.aurikrex.falkeai.ui.SectionHeader
import tech.aurikrex.falkeai.ui.StatusPill
import tech.aurikrex.falkeai.voice.CustomVoiceStatus
import tech.aurikrex.falkeai.voice.DatasetRecorderManager
import tech.aurikrex.falkeai.voice.SpeakerVerificationEngine
import tech.aurikrex.falkeai.voice.SpeakerVerifyOutcome

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceStudioScreen(
    settings: LocalSettingsSnapshot,
    customVoice: CustomVoiceStatus,
    alarms: List<FalkeAlarmEntity>,
    wakeRunning: Boolean,
    onToggleVoiceLock: (Boolean) -> Unit,
    onVoiceThresholdChange: (Float) -> Unit,
    onEnrollPcmSamples: (List<ShortArray>, (String) -> Unit) -> Unit,
    onEnrollFromStudio: ((String) -> Unit) -> Unit,
    onClearVoiceprint: () -> Unit,
    onImportVoiceZip: (Uri) -> Unit,
    onToggleCustomVoice: (Boolean) -> Unit,
    onSpeechSpeedChange: (Float) -> Unit,
    onRemoveCustomVoice: () -> Unit,
    onTestSpeakSample: (String) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onAddWakePhrase: (String) -> Unit,
    onRemoveWakePhrase: (String) -> Unit,
    onCreateAlarm: (Int, Int, String) -> Unit,
    onToggleAlarm: (Long, Boolean) -> Unit,
    onDeleteAlarm: (Long) -> Unit,
    onBanner: (String) -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) } // 0 = Voice Lock, 1 = Clone My Voice, 2 = Wake & Alarms

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Segmented Sub-Navigation Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "Voice Lock" to Icons.Default.Lock,
                "Clone Voice" to Icons.Default.RecordVoiceOver,
                "Wake & Alarms" to Icons.Default.Alarm
            ).forEachIndexed { idx, (label, icon) ->
                val selected = subTab == idx
                Surface(
                    color = if (selected) FalkePalette.Cyan.copy(alpha = 0.18f) else FalkePalette.CardSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (selected) FalkePalette.Cyan else FalkePalette.BorderSubtle),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { subTab = idx }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = if (selected) FalkePalette.Cyan else FalkePalette.TextMuted, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            label,
                            color = if (selected) FalkePalette.TextPrimary else FalkePalette.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        when (subTab) {
            0 -> VoiceLockPanel(
                settings = settings,
                onToggleVoiceLock = onToggleVoiceLock,
                onVoiceThresholdChange = onVoiceThresholdChange,
                onEnrollPcmSamples = onEnrollPcmSamples,
                onEnrollFromStudio = onEnrollFromStudio,
                onClearVoiceprint = onClearVoiceprint
            )
            1 -> CloneVoiceStudioPanel(
                settings = settings,
                customVoice = customVoice,
                onImportVoiceZip = onImportVoiceZip,
                onToggleCustomVoice = onToggleCustomVoice,
                onSpeechSpeedChange = onSpeechSpeedChange,
                onRemoveCustomVoice = onRemoveCustomVoice,
                onTestSpeakSample = onTestSpeakSample,
                onBanner = onBanner
            )
            2 -> WakeAndAlarmsPanel(
                settings = settings,
                alarms = alarms,
                wakeRunning = wakeRunning,
                onToggleWakeWord = onToggleWakeWord,
                onAddWakePhrase = onAddWakePhrase,
                onRemoveWakePhrase = onRemoveWakePhrase,
                onCreateAlarm = onCreateAlarm,
                onToggleAlarm = onToggleAlarm,
                onDeleteAlarm = onDeleteAlarm
            )
        }
    }
}

@Composable
private fun VoiceLockPanel(
    settings: LocalSettingsSnapshot,
    onToggleVoiceLock: (Boolean) -> Unit,
    onVoiceThresholdChange: (Float) -> Unit,
    onEnrollPcmSamples: (List<ShortArray>, (String) -> Unit) -> Unit,
    onEnrollFromStudio: ((String) -> Unit) -> Unit,
    onClearVoiceprint: () -> Unit
) {
    val app = FalkeApplication.instance
    val scope = rememberCoroutineScope()
    val modelReady = remember { app.speakerVerifier.isModelAvailable() }

    val recordedPhrases = remember { mutableStateListOf<ShortArray?>(null, null, null, null, null) }
    var activeRecIdx by remember { mutableIntStateOf(-1) }
    var feedback by remember { mutableStateOf("") }
    var testingVoice by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            GlassCard(
                borderColor = if (settings.voiceLockEnabled && settings.hasVoiceprint) FalkePalette.BorderEmerald else FalkePalette.BorderCyan
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Only My Voice Works", color = FalkePalette.TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text(
                            if (settings.hasVoiceprint) "Enrolled (${settings.enrolledSampleCount} samples • 192-D CAM++ on-device)"
                            else "Record 5 quick phrases below to lock Fall-K exclusively to your voice.",
                            color = FalkePalette.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.voiceLockEnabled && settings.hasVoiceprint,
                        onCheckedChange = onToggleVoiceLock,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03111F), checkedTrackColor = FalkePalette.EmeraldBright)
                    )
                }

                StatusPill(
                    text = if (modelReady) "ON-DEVICE CAM++ MODEL READY (\$0)" else "MODEL PENDING CI BUNDLE",
                    color = if (modelReady) FalkePalette.EmeraldBright else FalkePalette.Amber,
                    dot = true
                )

                if (settings.hasVoiceprint) {
                    HorizontalDivider(color = FalkePalette.BorderSubtle)
                    val pct = (settings.voiceMatchThreshold * 100).toInt()
                    Text("Strictness Threshold: $pct% similarity", color = FalkePalette.TextBody, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = settings.voiceMatchThreshold,
                        onValueChange = onVoiceThresholdChange,
                        valueRange = 0.45f..0.75f,
                        colors = SliderDefaults.colors(thumbColor = FalkePalette.Cyan, activeTrackColor = FalkePalette.Cyan)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (testingVoice) return@Button
                                testingVoice = true
                                feedback = "Speak any sentence for 3 seconds…"
                                scope.launch {
                                    val pcm = app.voiceController.recordEnrollmentPhrasePcm16()
                                    testingVoice = false
                                    if (pcm == null) {
                                        feedback = "Didn't catch speech — try again."
                                    } else {
                                        val res = withContext(Dispatchers.IO) { app.speakerVerifier.verifyUtterance(pcm) }
                                        feedback = when (res) {
                                            is SpeakerVerifyOutcome.Verified -> "✅ Matched Falcon (${(res.similarity * 100).toInt()}% >= ${(res.threshold * 100).toInt()}%)"
                                            is SpeakerVerifyOutcome.Rejected -> "❌ Rejected (${(res.similarity * 100).toInt()}% < ${(res.threshold * 100).toInt()}%)"
                                            else -> "Enable Voice Lock above to test."
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (testingVoice) "Listening…" else "Test My Voice", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onClearVoiceprint,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Reset", color = FalkePalette.CrimsonSoft, fontSize = 12.sp)
                        }
                    }
                }
                if (feedback.isNotBlank()) {
                    Text(feedback, color = FalkePalette.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        item {
            SectionHeader("5-Phrase Voiceprint Enrollment", FalkePalette.Cyan)
        }

        items(SpeakerVerificationEngine.ENROLLMENT_PHRASES.indices.toList()) { idx ->
            val phrase = SpeakerVerificationEngine.ENROLLMENT_PHRASES[idx]
            val done = recordedPhrases[idx] != null
            val recNow = activeRecIdx == idx

            GlassCard(
                borderColor = if (done) FalkePalette.BorderEmerald else if (recNow) FalkePalette.BorderCyan else FalkePalette.BorderSubtle,
                contentPadding = 12.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Phrase ${idx + 1} of 5",
                            color = if (done) FalkePalette.EmeraldBright else FalkePalette.Cyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("\"$phrase\"", color = FalkePalette.TextPrimary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            if (activeRecIdx != -1) return@Button
                            activeRecIdx = idx
                            feedback = "Recording phrase ${idx + 1}… speak naturally"
                            scope.launch {
                                val pcm = app.voiceController.recordEnrollmentPhrasePcm16()
                                activeRecIdx = -1
                                if (pcm != null) {
                                    recordedPhrases[idx] = pcm
                                    feedback = "Captured phrase ${idx + 1}!"
                                } else {
                                    feedback = "Too quiet — tap Record again."
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (recNow) FalkePalette.Crimson else if (done) FalkePalette.Emerald else FalkePalette.Cyan
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            if (done && !recNow) Icons.Default.CheckCircle else Icons.Default.Mic,
                            null,
                            tint = Color(0xFF03111F),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (recNow) "Listening" else if (done) "Redo" else "Record",
                            color = Color(0xFF03111F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        item {
            val readySamples = recordedPhrases.filterNotNull()
            GlassCard {
                Button(
                    onClick = {
                        onEnrollPcmSamples(readySamples) { msg -> feedback = msg }
                    },
                    enabled = readySamples.size >= 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Encrypted Voiceprint (${readySamples.size}/5 ready)", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        onEnrollFromStudio { msg -> feedback = msg }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Or Use My Recorded Clone-Studio Takes", fontSize = 12.sp, color = FalkePalette.Cyan)
                }
            }
        }
    }
}

@Composable
private fun CloneVoiceStudioPanel(
    settings: LocalSettingsSnapshot,
    customVoice: CustomVoiceStatus,
    onImportVoiceZip: (Uri) -> Unit,
    onToggleCustomVoice: (Boolean) -> Unit,
    onSpeechSpeedChange: (Float) -> Unit,
    onRemoveCustomVoice: () -> Unit,
    onTestSpeakSample: (String) -> Unit,
    onBanner: (String) -> Unit
) {
    val app = FalkeApplication.instance
    val recorder = app.datasetRecorder
    val scope = rememberCoroutineScope()

    val isRecording by recorder.isRecording.collectAsState()
    val liveLevel by recorder.liveLevel.collectAsState()
    val recordedIds by recorder.recordedIds.collectAsState()
    val lastQuality by recorder.lastQuality.collectAsState()

    val prompts = DatasetRecorderManager.STUDIO_PROMPTS
    var currentIdx by remember {
        val firstUnrecorded = prompts.indices.firstOrNull { (it + 1) !in recordedIds } ?: 0
        mutableIntStateOf(firstUnrecorded)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                recorder.exportDatasetZipToUri(uri).fold(
                    onSuccess = { cnt -> onBanner("Exported $cnt WAV clips to falke_voice_dataset.zip!") },
                    onFailure = { e -> onBanner("Export failed: ${e.message}") }
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportVoiceZip(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 1) Active Own-Voice Model Card
        item {
            GlassCard(borderColor = if (customVoice.active) FalkePalette.BorderEmerald else FalkePalette.BorderCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Your Own Cloned Voice (\$0)", color = FalkePalette.TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text(
                            if (customVoice.installed) "Installed (${customVoice.modelSizeKb / 1024} MB ONNX • 100% offline on phone)"
                            else "Record script below -> train free on Kaggle/Colab -> import ZIP here.",
                            color = FalkePalette.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    if (customVoice.installed) {
                        Switch(
                            checked = customVoice.active,
                            onCheckedChange = onToggleCustomVoice,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03111F), checkedTrackColor = FalkePalette.EmeraldBright)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import Voice ZIP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onTestSpeakSample("Hey boss, it's Fall-K speaking in your voice right on your phone!") },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Test \"Fall-K\"", color = FalkePalette.Cyan, fontSize = 12.sp)
                    }
                }

                val speedPct = (settings.speechSpeed * 100).toInt()
                Text("Speaking Pace: $speedPct%", color = FalkePalette.TextSecondary, fontSize = 11.sp)
                Slider(
                    value = settings.speechSpeed,
                    onValueChange = onSpeechSpeedChange,
                    valueRange = 0.80f..1.25f,
                    colors = SliderDefaults.colors(thumbColor = FalkePalette.Cyan, activeTrackColor = FalkePalette.Cyan)
                )

                if (customVoice.installed) {
                    OutlinedButton(onClick = onRemoveCustomVoice, shape = RoundedCornerShape(10.dp)) {
                        Text("Remove Imported Model", color = FalkePalette.CrimsonSoft, fontSize = 11.sp)
                    }
                }
            }
        }

        // 2) 120-Sentence Guided Recording Studio
        item {
            SectionHeader("Guided Recording Studio (${recordedIds.size} / ${prompts.size})", FalkePalette.EmeraldBright)
        }

        item {
            val chapter = (currentIdx / 15) + 1
            val isThisRecorded = (currentIdx + 1) in recordedIds

            GlassCard(borderColor = if (isRecording) FalkePalette.BorderCrimson else FalkePalette.BorderCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill("CHAPTER $chapter / 8 • #${currentIdx + 1}", FalkePalette.Cyan)
                    if (isThisRecorded) {
                        StatusPill("SAVED", FalkePalette.EmeraldBright, dot = true)
                    }
                }

                LinearProgressIndicator(
                    progress = { recordedIds.size.toFloat() / prompts.size.toFloat() },
                    color = FalkePalette.EmeraldBright,
                    trackColor = FalkePalette.CardElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                )

                // Teleprompter Box
                Surface(
                    color = FalkePalette.CardElevated,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, FalkePalette.BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = prompts[currentIdx],
                        color = FalkePalette.TextPrimary,
                        fontSize = 17.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                // Live Audio Level Meter
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (isRecording) "Recording at 22.05 kHz… tap Stop when finished" else "Hold phone ~18 cm away in a quiet room",
                            color = if (isRecording) FalkePalette.CrimsonSoft else FalkePalette.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (isRecording) liveLevel else 0f },
                        color = if (liveLevel > 0.9f) FalkePalette.Crimson else FalkePalette.Cyan,
                        trackColor = FalkePalette.CardElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                    )
                }

                lastQuality?.let { q ->
                    val warn = q.warning
                    Text(
                        text = warn ?: "✅ Clean take (${"%.1f".format(q.durationSec)}s) — tap Next!",
                        color = if (warn != null) FalkePalette.AmberSoft else FalkePalette.EmeraldBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Studio Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (!isRecording && currentIdx > 0) currentIdx-- },
                        enabled = !isRecording && currentIdx > 0
                    ) {
                        Icon(Icons.Default.NavigateBefore, "Previous", tint = FalkePalette.TextBody)
                    }

                    Button(
                        onClick = {
                            if (isRecording) recorder.stopRecordingPrompt()
                            else recorder.startRecordingPrompt(currentIdx)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) FalkePalette.Crimson else FalkePalette.Cyan
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            null,
                            tint = if (isRecording) Color.White else Color(0xFF03111F)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isRecording) "Stop Take" else if (isThisRecorded) "Re-record" else "Record Prompt",
                            color = if (isRecording) Color.White else Color(0xFF03111F),
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    if (isThisRecorded && !isRecording) {
                        IconButton(onClick = { recorder.playPromptTake(currentIdx) }) {
                            Icon(Icons.Default.PlayArrow, "Play take", tint = FalkePalette.EmeraldBright)
                        }
                    }

                    IconButton(
                        onClick = { if (!isRecording && currentIdx < prompts.lastIndex) currentIdx++ },
                        enabled = !isRecording && currentIdx < prompts.lastIndex
                    ) {
                        Icon(Icons.Default.NavigateNext, "Next", tint = FalkePalette.TextBody)
                    }
                }

                // Chapter Jump Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (0 until 8).forEach { ch ->
                        val start = ch * 15
                        QuickRoutinePill(
                            label = "Ch ${ch + 1}",
                            icon = Icons.Default.GraphicEq,
                            accent = FalkePalette.Cyan,
                            active = currentIdx in start until (start + 15)
                        ) {
                            if (!isRecording) currentIdx = start
                        }
                    }
                }

                HorizontalDivider(color = FalkePalette.BorderSubtle)
                Button(
                    onClick = { exportLauncher.launch("falke_voice_dataset.zip") },
                    enabled = recordedIds.isNotEmpty() && !isRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Emerald),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, null, tint = Color(0xFF021610), modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Export Dataset ZIP (${recordedIds.size} clips) for Free Colab/Kaggle",
                        color = Color(0xFF021610),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WakeAndAlarmsPanel(
    settings: LocalSettingsSnapshot,
    alarms: List<FalkeAlarmEntity>,
    wakeRunning: Boolean,
    onToggleWakeWord: (Boolean) -> Unit,
    onAddWakePhrase: (String) -> Unit,
    onRemoveWakePhrase: (String) -> Unit,
    onCreateAlarm: (Int, Int, String) -> Unit,
    onToggleAlarm: (Long, Boolean) -> Unit,
    onDeleteAlarm: (Long) -> Unit
) {
    var newPhrase by remember { mutableStateOf("") }
    var alarmHour by remember { mutableStateOf("06") }
    var alarmMinute by remember { mutableStateOf("30") }
    var alarmLabel by remember { mutableStateOf("Morning Wake Up") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            GlassCard(borderColor = if (wakeRunning) FalkePalette.BorderCyan else FalkePalette.BorderSubtle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("24/7 Multi-Wake-Word Mic", color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (wakeRunning) "Listening locally (~35 MB RAM) for \"Hey Fall-K\" & more"
                            else "Paused — toggle on for hands-free wake",
                            color = if (wakeRunning) FalkePalette.EmeraldBright else FalkePalette.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.wakeWordEnabled,
                        onCheckedChange = onToggleWakeWord,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03111F), checkedTrackColor = FalkePalette.Cyan)
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.wakeWords.forEach { phrase ->
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
                                if (settings.wakeWords.size > 1) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = FalkePalette.TextMuted,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .clickable { onRemoveWakePhrase(phrase) }
                                    )
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPhrase,
                        onValueChange = { newPhrase = it },
                        placeholder = { Text("Add wake phrase (e.g. Yo Fall Kay)…", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (newPhrase.isNotBlank()) {
                                onAddWakePhrase(newPhrase)
                                newPhrase = ""
                            }
                        },
                        modifier = Modifier.size(46.dp).clip(CircleShape).background(FalkePalette.Cyan)
                    ) {
                        Icon(Icons.Default.Add, "Add", tint = Color(0xFF03111F))
                    }
                }
            }
        }

        item { SectionHeader("Exact DND-Bypass Alarms", FalkePalette.Amber) }

        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                    modifier = Modifier.fillMaxWidth().height(46.dp)
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
                        Icon(Icons.Default.Delete, "Delete", tint = FalkePalette.Crimson)
                    }
                }
            }
        }
    }
}
