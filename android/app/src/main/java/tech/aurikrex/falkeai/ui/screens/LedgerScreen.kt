package tech.aurikrex.falkeai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.aurikrex.falkeai.data.CapturedNotificationEntity
import tech.aurikrex.falkeai.data.DashboardResponse
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.GlassCard
import tech.aurikrex.falkeai.ui.QuickRoutinePill
import tech.aurikrex.falkeai.ui.SectionHeader
import tech.aurikrex.falkeai.ui.formatMoneyMinor
import tech.aurikrex.falkeai.ui.formatRelativeTime

@Composable
fun LedgerScreen(
    dashboard: DashboardResponse?,
    notifications: List<CapturedNotificationEntity>,
    onClearNotifications: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var filterMode by remember { mutableIntStateOf(0) }

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
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SectionHeader("14-Day Cashflow Summary", FalkePalette.EmeraldBright) }

        item {
            GlassCard(borderColor = FalkePalette.BorderEmerald) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("INFLOW (NGN)", color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(formatMoneyMinor(ngnIn, "NGN"), color = FalkePalette.EmeraldBright, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("OUTFLOW (NGN)", color = FalkePalette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(formatMoneyMinor(ngnOut, "NGN"), color = FalkePalette.Crimson, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
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
                        "No bank or Paystack alerts logged in the last 14 days. Enable Notification Access in Settings to auto-capture credit & debit alerts.",
                        color = FalkePalette.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(alerts.take(16), key = { "${it.id}_${it.createdAt}" }) { b ->
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
                            Text(b.bank.take(2).uppercase(), color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${b.bank} • ${b.counterparty.ifBlank { "Transfer" }}", color = FalkePalette.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(b.rawSummary, color = FalkePalette.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${if (isCredit) "+" else "-"}${b.amountFormatted}", color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            Text(formatRelativeTime(b.createdAt), color = FalkePalette.TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            SectionHeader(
                title = "All-App & Social Feed (OTPs Stripped)",
                accent = FalkePalette.Cyan,
                trailing = if (notifications.isNotEmpty()) {
                    {
                        Text(
                            "Clear",
                            color = FalkePalette.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onClearNotifications)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else null
            )
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
                    QuickRoutinePill(label, Icons.Default.Notifications, FalkePalette.Cyan, active = filterMode == idx) {
                        filterMode = idx
                    }
                }
            }
        }

        if (filteredNotifs.isEmpty()) {
            item {
                Text("No matching notifications.", color = FalkePalette.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
        } else {
            items(filteredNotifs.take(40), key = { it.id }) { n ->
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
