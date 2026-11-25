package tech.aurikrex.falkeai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.aurikrex.falkeai.voice.VoiceState
import java.text.NumberFormat
import java.util.Locale

object FalkePalette {
    val BgDeep = Color(0xFF060A13)
    val BgTopBar = Color(0xFF0A101F)
    val BgNavBar = Color(0xFF0A1120)
    val CardSurface = Color(0xFF10192D)
    val CardElevated = Color(0xFF15223B)
    val CardCrimson = Color(0xFF280D16)
    val CardAmber = Color(0xFF291A06)

    val BorderSubtle = Color(0x1EFFFFFF)
    val BorderCyan = Color(0x5538BDF8)
    val BorderEmerald = Color(0x5510B981)
    val BorderCrimson = Color(0x66F43F5E)

    val Cyan = Color(0xFF38BDF8)
    val CyanDeep = Color(0xFF0284C7)
    val Emerald = Color(0xFF10B981)
    val EmeraldBright = Color(0xFF34D399)
    val Violet = Color(0xFFA855F7)
    val VioletDeep = Color(0xFF7C3AED)
    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFFFDE68A)
    val Crimson = Color(0xFFF43F5E)
    val CrimsonSoft = Color(0xFFFECDD3)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextBody = Color(0xFFE2E8F0)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

val FalkeDarkColorScheme = darkColorScheme(
    primary = FalkePalette.Cyan,
    onPrimary = Color(0xFF03111F),
    secondary = FalkePalette.Emerald,
    onSecondary = Color(0xFF021610),
    tertiary = FalkePalette.Violet,
    background = FalkePalette.BgDeep,
    onBackground = FalkePalette.TextPrimary,
    surface = FalkePalette.CardSurface,
    onSurface = FalkePalette.TextBody,
    surfaceVariant = FalkePalette.CardElevated,
    onSurfaceVariant = FalkePalette.TextSecondary,
    outline = Color(0xFF27354F)
)

/**
 * Pure-Kotlin Markdown tokenizer so both Compose UI rendering and JVM JUnit tests
 * cleanly render `**bold**`, `*bold*`, `` `code` ``, `_italic_`, and crisp bullets
 * with zero raw markdown symbols leaking onto screen.
 */
object MarkdownFormatter {
    enum class SpanKind { PLAIN, BOLD, ITALIC, CODE }
    data class Segment(val text: String, val kind: SpanKind)

    private val tokenRegex = Regex("(\\*\\*[^*\\n]+\\*\\*|`[^`\\n]+`|(?<!\\*)\\*[^*\\n]+\\*(?!\\*)|(?<![\\w])_[^_\\n]+_(?![\\w]))")

    fun normalizeBullets(raw: String): String {
        return raw.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("- ") -> line.substringBefore("- ") + "• " + trimmed.removePrefix("- ")
                trimmed.startsWith("* ") -> line.substringBefore("* ") + "• " + trimmed.removePrefix("* ")
                trimmed.startsWith("### ") -> "**" + trimmed.removePrefix("### ").trim() + "**"
                trimmed.startsWith("## ") -> "**" + trimmed.removePrefix("## ").trim() + "**"
                trimmed.startsWith("# ") -> "**" + trimmed.removePrefix("# ").trim() + "**"
                else -> line
            }
        }
    }

    fun tokenize(raw: String): List<Segment> {
        val normalized = normalizeBullets(raw)
        val out = mutableListOf<Segment>()
        var cursor = 0
        for (match in tokenRegex.findAll(normalized)) {
            if (match.range.first > cursor) {
                out.add(Segment(normalized.substring(cursor, match.range.first), SpanKind.PLAIN))
            }
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") && token.length > 4 ->
                    out.add(Segment(token.substring(2, token.length - 2), SpanKind.BOLD))
                token.startsWith("`") && token.endsWith("`") && token.length > 2 ->
                    out.add(Segment(token.substring(1, token.length - 1), SpanKind.CODE))
                token.startsWith("*") && token.endsWith("*") && token.length > 2 ->
                    out.add(Segment(token.substring(1, token.length - 1), SpanKind.BOLD))
                token.startsWith("_") && token.endsWith("_") && token.length > 2 ->
                    out.add(Segment(token.substring(1, token.length - 1), SpanKind.ITALIC))
                else -> out.add(Segment(token, SpanKind.PLAIN))
            }
            cursor = match.range.last + 1
        }
        if (cursor < normalized.length) {
            out.add(Segment(normalized.substring(cursor), SpanKind.PLAIN))
        }
        return out
    }

    fun toPlainText(raw: String): String = tokenize(raw).joinToString("") { it.text }
}

fun renderMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    for (seg in MarkdownFormatter.tokenize(raw)) {
        val start = length
        append(seg.text)
        when (seg.kind) {
            MarkdownFormatter.SpanKind.BOLD -> addStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = FalkePalette.TextPrimary),
                start,
                length
            )
            MarkdownFormatter.SpanKind.ITALIC -> addStyle(
                SpanStyle(fontStyle = FontStyle.Italic, color = FalkePalette.TextSecondary),
                start,
                length
            )
            MarkdownFormatter.SpanKind.CODE -> addStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = FalkePalette.Cyan,
                    background = Color(0xFF0A192F),
                    fontSize = 13.sp
                ),
                start,
                length
            )
            MarkdownFormatter.SpanKind.PLAIN -> Unit
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = FalkePalette.CardSurface,
    borderColor: Color = FalkePalette.BorderSubtle,
    cornerRadius: Dp = 18.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = false
) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (dot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    accent: Color = FalkePalette.Cyan,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(accent)
            )
            Text(
                text = title.uppercase(),
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun QuickRoutinePill(
    label: String,
    icon: ImageVector,
    accent: Color,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (active) accent.copy(alpha = 0.22f) else FalkePalette.CardSurface
    val border = if (active) accent.copy(alpha = 0.7f) else FalkePalette.BorderSubtle
    Surface(
        color = bg,
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Text(
                text = label,
                color = if (active) FalkePalette.TextPrimary else FalkePalette.TextBody,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Animated Stark-Tech J.A.R.V.I.S. Orb with concentric breathing halo rings,
 * state-driven colors, and clear Stop icon when speaking/active.
 */
@Composable
fun JarvisOrb(
    state: VoiceState,
    orbSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state != VoiceState.IDLE
    val primaryTarget = when (state) {
        VoiceState.LISTENING, VoiceState.FOLLOW_UP_WINDOW -> FalkePalette.Cyan
        VoiceState.THINKING -> FalkePalette.Violet
        VoiceState.SPEAKING -> FalkePalette.Emerald
        VoiceState.IDLE -> Color(0xFF1D4ED8)
    }
    val secondaryTarget = when (state) {
        VoiceState.LISTENING, VoiceState.FOLLOW_UP_WINDOW -> FalkePalette.CyanDeep
        VoiceState.THINKING -> FalkePalette.VioletDeep
        VoiceState.SPEAKING -> Color(0xFF047857)
        VoiceState.IDLE -> Color(0xFF0F1D3A)
    }
    val primaryColor by animateColorAsState(primaryTarget, animationSpec = tween(350), label = "orbPrimary")
    val secondaryColor by animateColorAsState(secondaryTarget, animationSpec = tween(350), label = "orbSecondary")

    val infinite = rememberInfiniteTransition(label = "orbPulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isActive) 1.24f else 1.09f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isActive) 900 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infinite.animateFloat(
        initialValue = if (isActive) 0.42f else 0.18f,
        targetValue = 0.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isActive) 900 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val haloSize = orbSize * 1.28f
    Box(
        modifier = modifier.size(haloSize),
        contentAlignment = Alignment.Center
    ) {
        // Outer animated pulse ring
        Box(
            modifier = Modifier
                .size(orbSize)
                .scale(pulseScale)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(primaryColor.copy(alpha = 0.75f), Color.Transparent)
                    )
                )
        )
        // Subtle crisp arc rim
        Box(
            modifier = Modifier
                .size(orbSize + 8.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.75f),
                            Color.Transparent,
                            primaryColor.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        // Core interactive orb
        Box(
            modifier = Modifier
                .size(orbSize)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(primaryColor, secondaryColor, Color(0xFF071124))
                    )
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val icon = when (state) {
                VoiceState.SPEAKING -> Icons.Default.Stop
                VoiceState.THINKING -> Icons.Default.AutoAwesome
                VoiceState.LISTENING, VoiceState.FOLLOW_UP_WINDOW -> Icons.Default.GraphicEq
                VoiceState.IDLE -> Icons.Default.Mic
            }
            Icon(
                imageVector = icon,
                contentDescription = if (state == VoiceState.SPEAKING) "Stop speaking" else "Talk to FalkeAI",
                tint = Color.White,
                modifier = Modifier.size(orbSize * 0.42f)
            )
        }
    }
}

@Composable
fun ThinkingDotsIndicator() {
    val infinite = rememberInfiniteTransition(label = "thinkingDots")
    val a1 by infinite.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "d1"
    )
    val a2 by infinite.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(550, delayMillis = 160), RepeatMode.Reverse),
        label = "d2"
    )
    val a3 by infinite.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(550, delayMillis = 320), RepeatMode.Reverse),
        label = "d3"
    )
    Surface(
        color = FalkePalette.CardSurface,
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        border = BorderStroke(1.dp, FalkePalette.Violet.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "FalkeAI is thinking",
                color = FalkePalette.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(2.dp))
            listOf(a1, a2, a3).forEach { alpha ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(FalkePalette.Violet)
                )
            }
        }
    }
}

fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val diffSec = ((System.currentTimeMillis() - timestampMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60 -> "Just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86400}d ago"
    }
}

fun formatMoneyMinor(minor: Long, currency: String): String {
    val major = minor / 100.0
    val nf = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = if (minor % 100L == 0L) 0 else 2
        maximumFractionDigits = 2
    }
    val prefix = when (currency.uppercase()) {
        "NGN" -> "₦"
        "USD" -> "$"
        "GBP" -> "£"
        "EUR" -> "€"
        else -> "$currency "
    }
    return "$prefix${nf.format(major)}"
}
