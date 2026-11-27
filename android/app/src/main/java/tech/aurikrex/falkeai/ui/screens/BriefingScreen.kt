package tech.aurikrex.falkeai.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.aurikrex.falkeai.data.DashboardResponse
import tech.aurikrex.falkeai.data.EmailItemDto
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.GlassCard
import tech.aurikrex.falkeai.ui.QuickRoutinePill
import tech.aurikrex.falkeai.ui.SectionHeader
import tech.aurikrex.falkeai.ui.StatusPill
import tech.aurikrex.falkeai.ui.renderMarkdown

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BriefingScreen(
    dashboard: DashboardResponse?,
    loading: Boolean,
    error: String?,
    onSetAvailabilityCommand: (String) -> Unit,
    onAckUrgent: (String) -> Unit,
    onDismissFlag: (String) -> Unit,
    onDismissAllFlags: () -> Unit,
    onApproveFollowup: (Long) -> Unit,
    onEmailAction: (command: String, sensitive: Boolean) -> Unit,
    onRefresh: () -> Unit
) {
    var confirmTrashEmail by remember { mutableStateOf<EmailItemDto?>(null) }

    confirmTrashEmail?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTrashEmail = null },
            containerColor = FalkePalette.CardSurface,
            titleContentColor = FalkePalette.TextPrimary,
            textContentColor = FalkePalette.TextBody,
            title = { Text("Move Email to Trash?", fontWeight = FontWeight.Bold) },
            text = {
                Text("\"${target.subject}\" from ${target.sender} will be moved to Gmail Trash (recoverable for 30 days).")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.msgId
                        confirmTrashEmail = null
                        onEmailAction("Trash email $id", true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FalkePalette.Crimson)
                ) {
                    Text("Move to Trash", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrashEmail = null }) {
                    Text("Cancel", color = FalkePalette.TextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
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
                            Text("Briefing Sync Issue", color = FalkePalette.CrimsonSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(error, color = FalkePalette.TextBody, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, FalkePalette.Crimson)) {
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
                        if (loading) "Syncing live WhatsApp + 6-Gmail briefing…"
                        else "Connect your server in Settings to sync live WhatsApp & Gmail briefings.",
                        color = FalkePalette.TextBody,
                        fontSize = 14.sp
                    )
                    Button(onClick = onRefresh, shape = RoundedCornerShape(12.dp)) {
                        Text("Sync Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@LazyColumn
        }

        // 1) Executive Availability & KPI Hero
        item {
            val mode = dashboard.availability.mode.ifBlank { "available" }.lowercase()
            val modeColor = when (mode) {
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
                        Text("CURRENT MODE", color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(
                            mode.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            color = FalkePalette.TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    StatusPill(
                        text = if (dashboard.lowBatteryCover) "BATTERY COVER" else "LIVE SYNC",
                        color = if (dashboard.lowBatteryCover) FalkePalette.Amber else modeColor,
                        dot = true
                    )
                }

                // 1-Tap Availability Mode Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickRoutinePill("Available", Icons.Default.CheckCircle, FalkePalette.EmeraldBright, active = mode == "available") {
                        onSetAvailabilityCommand("Set my availability to available")
                    }
                    QuickRoutinePill("Busy 2h", Icons.Default.Schedule, FalkePalette.Cyan, active = mode == "busy") {
                        onSetAvailabilityCommand("Set my availability to busy for 120 minutes")
                    }
                    QuickRoutinePill("Deep Work", Icons.Default.Lock, FalkePalette.Violet, active = mode == "deep_work") {
                        onSetAvailabilityCommand("Set my availability to deep work for 90 minutes")
                    }
                    QuickRoutinePill("Sleeping", Icons.Default.NightsStay, FalkePalette.Amber, active = mode == "sleeping") {
                        onSetAvailabilityCommand("Set my availability to sleeping for 7 hours")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBox("Urgent", dashboard.urgentAlerts.size.toString(), FalkePalette.Crimson, Modifier.weight(1f))
                    MetricBox("Gmail", dashboard.priorityEmails.size.toString(), FalkePalette.Cyan, Modifier.weight(1f))
                    MetricBox("Follow-ups", dashboard.followups.size.toString(), FalkePalette.EmeraldBright, Modifier.weight(1f))
                    MetricBox("WA Review", dashboard.pendingFlags.size.toString(), FalkePalette.Amber, Modifier.weight(1f))
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

        // 2) Connected Gmail Inboxes (All 6 Real Addresses + Auth Health)
        if (dashboard.gmailAccounts.isNotEmpty()) {
            item {
                SectionHeader("Connected Gmail Inboxes (${dashboard.gmailAccounts.size})", FalkePalette.Cyan)
            }
            item {
                GlassCard(contentPadding = 12.dp) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dashboard.gmailAccounts.forEach { acc ->
                            val ok = acc.status == "connected"
                            val color = if (ok) FalkePalette.EmeraldBright else FalkePalette.Amber
                            val addr = acc.email?.takeIf { it.isNotBlank() } ?: acc.label
                            Surface(
                                color = color.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
                                    Text("#${acc.index} $addr", color = FalkePalette.TextBody, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
            items(dashboard.urgentAlerts, key = { it.id }) { u ->
                GlassCard(backgroundColor = FalkePalette.CardCrimson, borderColor = FalkePalette.BorderCrimson) {
                    Text(u.contactName ?: u.sender, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(u.summary, color = FalkePalette.CrimsonSoft, fontSize = 13.sp)
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

        // 4) Pending WhatsApp Reviews
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
            items(dashboard.pendingFlags, key = { "${it.chat}_${it.reason}" }) { fl ->
                val cleanReason = if (fl.reason.lowercase().contains("could not parse")) "Needs your quick reply" else fl.reason
                GlassCard(borderColor = FalkePalette.Amber.copy(alpha = 0.35f), contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(fl.name?.takeIf { it.isNotBlank() } ?: "+${fl.chat}", color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(cleanReason, color = FalkePalette.AmberSoft, fontSize = 12.sp)
                            if (fl.lastMessage.isNotBlank()) {
                                Text("\"${fl.lastMessage}\"", color = FalkePalette.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

        // 5) Priority Gmail Cards
        if (dashboard.priorityEmails.isNotEmpty()) {
            item { SectionHeader("Priority Gmail", FalkePalette.Cyan) }
            items(dashboard.priorityEmails.take(10), key = { it.msgId }) { em ->
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
                        StatusPill(em.accountLabel.ifBlank { "Gmail" }, FalkePalette.Cyan)
                        StatusPill(em.category.replace('_', ' ').uppercase().ifBlank { "EMAIL" }, catColor)
                    }
                    Text(em.subject, color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(em.sender, color = FalkePalette.Cyan, fontSize = 12.sp)
                    if (em.snippet.isNotBlank()) {
                        Text(em.snippet, color = FalkePalette.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniActionChip("Draft Reply", Icons.AutoMirrored.Filled.Reply, FalkePalette.Cyan) {
                            onEmailAction("Draft a reply to email ${em.msgId} from ${em.sender} about ${em.subject}", false)
                        }
                        MiniActionChip("Archive", Icons.Default.Archive, FalkePalette.EmeraldBright) {
                            onEmailAction("Archive email ${em.msgId}", false)
                        }
                        MiniActionChip("Trash", Icons.Default.DeleteOutline, FalkePalette.Crimson) {
                            confirmTrashEmail = em
                        }
                    }
                }
            }
        }

        // 6) Smart Follow-Ups
        if (dashboard.followups.isNotEmpty()) {
            item { SectionHeader("Smart Follow-Ups", FalkePalette.EmeraldBright) }
            items(dashboard.followups.take(6), key = { it.id }) { f ->
                GlassCard(borderColor = FalkePalette.BorderEmerald, contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(f.contactName ?: f.sender, color = FalkePalette.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        StatusPill(f.status.uppercase(), FalkePalette.EmeraldBright)
                    }
                    Text(f.reason, color = FalkePalette.TextSecondary, fontSize = 12.sp)
                    Text("\"${f.suggestedReply}\"", color = FalkePalette.TextBody, fontSize = 13.sp)
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

        // 7) Bookings
        if (dashboard.bookings.isNotEmpty()) {
            item { SectionHeader("Aurikrex Tutoring Classes", FalkePalette.Violet) }
            items(dashboard.bookings.take(6), key = { "${it.name}_${it.slot}" }) { b ->
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
                        StatusPill(b.exam.ifBlank { "CLASS" }, FalkePalette.Violet)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = FalkePalette.CardElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MiniActionChip(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
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
