package tech.aurikrex.falkeai.assist

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.NotificationChannels
import tech.aurikrex.falkeai.R
import tech.aurikrex.falkeai.ui.FalkeDarkColorScheme
import tech.aurikrex.falkeai.ui.FalkePalette
import tech.aurikrex.falkeai.ui.JarvisOrb
import tech.aurikrex.falkeai.ui.MarkdownFormatter
import tech.aurikrex.falkeai.ui.QuickRoutinePill
import tech.aurikrex.falkeai.ui.ThinkingDotsIndicator
import tech.aurikrex.falkeai.ui.MainActivity
import tech.aurikrex.falkeai.ui.renderMarkdown
import tech.aurikrex.falkeai.voice.VoiceState
import kotlin.math.roundToInt

private class OverlayComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Gemini / Claude Style Floating Assistant Pill & Expandable Bar (`SYSTEM_ALERT_WINDOW`).
 * - Collapsed: Sleek edge-snapping glowing pill over any app (draggable, translucent when idle).
 * - Expanded: Bottom-docked floating glass bar with instant voice, typed prompt, "Ask about screen",
 *   and inline Markdown reply card — without ever leaving WhatsApp, Chrome, X, or Instagram!
 */
class FalkeOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var composeOwner: OverlayComposeOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startForegroundCompat()
        attachOverlayWindow()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            FalkeApplication.instance.prefs.floatingBubbleEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        detachOverlayWindow()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, NotificationChannels.BRIDGE_FG)
            .setSmallIcon(R.drawable.ic_falke_bolt)
            .setContentTitle("FalkeAI Floating Bar Active")
            .setContentText("Tap the edge pill anytime to summon Fall-K over any app")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(OVERLAY_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(OVERLAY_NOTIF_ID, notif)
        }
    }

    private fun attachOverlayWindow() {
        if (composeView != null) return
        val app = FalkeApplication.instance
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        val owner = OverlayComposeOwner().also { it.onCreate() }
        composeOwner = owner

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = app.prefs.bubblePosX
            y = app.prefs.bubblePosY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        layoutParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                MaterialTheme(colorScheme = FalkeDarkColorScheme) {
                    val suppressed by _suppressedForForeground.collectAsState()
                    val km = remember { getSystemService(KeyguardManager::class.java) }
                    val locked = km?.isKeyguardLocked == true
                    if (!suppressed && !locked) {
                        FloatingOverlayContent(
                            onExpandChanged = { expanded -> updateWindowMode(expanded) },
                            onDragDelta = { dx, dy -> moveCollapsedBubble(dx, dy) },
                            onDragEnd = { snapBubbleToNearestEdge() },
                            onOpenMainApp = {
                                startActivity(
                                    Intent(this@FalkeOverlayService, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    }
                                )
                            },
                            onHideOverlay = {
                                app.prefs.floatingBubbleEnabled = false
                                stopSelf()
                            }
                        )
                    }
                }
            }
        }
        composeView = view
        runCatching { wm.addView(view, params) }
    }

    private fun updateWindowMode(expanded: Boolean) {
        val wm = windowManager ?: return
        val view = composeView ?: return
        val params = layoutParams ?: return
        val app = FalkeApplication.instance

        if (expanded) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = 24
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = app.prefs.bubblePosX
            params.y = app.prefs.bubblePosY
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun moveCollapsedBubble(dx: Float, dy: Float) {
        val wm = windowManager ?: return
        val view = composeView ?: return
        val params = layoutParams ?: return
        val dm = resources.displayMetrics
        params.x = (params.x + dx.roundToInt()).coerceIn(0, (dm.widthPixels - 140).coerceAtLeast(0))
        params.y = (params.y + dy.roundToInt()).coerceIn(80, (dm.heightPixels - 220).coerceAtLeast(100))
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun snapBubbleToNearestEdge() {
        val wm = windowManager ?: return
        val view = composeView ?: return
        val params = layoutParams ?: return
        val dm = resources.displayMetrics
        val mid = dm.widthPixels / 2
        params.x = if (params.x < mid) 12 else (dm.widthPixels - 156).coerceAtLeast(12)
        val prefs = FalkeApplication.instance.prefs
        prefs.bubblePosX = params.x
        prefs.bubblePosY = params.y
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun detachOverlayWindow() {
        val wm = windowManager
        composeView?.let { v -> runCatching { wm?.removeView(v) } }
        composeView = null
        composeOwner?.onDestroy()
        composeOwner = null
        windowManager = null
    }

    companion object {
        private const val OVERLAY_NOTIF_ID = 4409
        private const val ACTION_STOP = "tech.aurikrex.falkeai.STOP_OVERLAY"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _suppressedForForeground = MutableStateFlow(false)

        fun setSuppressedForAppForeground(suppressed: Boolean) {
            _suppressedForForeground.value = suppressed
        }

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FalkeOverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FalkeOverlayService::class.java)) }
        }
    }
}

@Composable
private fun FloatingOverlayContent(
    onExpandChanged: (Boolean) -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onOpenMainApp: () -> Unit,
    onHideOverlay: () -> Unit
) {
    val app = FalkeApplication.instance
    val controller = app.voiceController
    val ui by controller.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current

    var expanded by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(expanded) {
        onExpandChanged(expanded)
    }

    if (!expanded) {
        // Collapsed Draggable Edge Pill
        val isActive = ui.state != VoiceState.IDLE
        val ringColor = when (ui.state) {
            VoiceState.LISTENING, VoiceState.FOLLOW_UP_WINDOW -> FalkePalette.Cyan
            VoiceState.THINKING -> FalkePalette.Violet
            VoiceState.SPEAKING -> FalkePalette.EmeraldBright
            VoiceState.IDLE -> FalkePalette.Cyan.copy(alpha = 0.7f)
        }
        Box(
            modifier = Modifier
                .padding(4.dp)
                .size(50.dp)
                .alpha(if (isActive) 1f else 0.86f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF132342), Color(0xFF081020))
                    )
                )
                .border(2.dp, ringColor, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    ) { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (ui.state == VoiceState.SPEAKING) Icons.Default.Stop else Icons.Default.Bolt,
                contentDescription = "Open FalkeAI floating bar",
                tint = ringColor,
                modifier = Modifier.size(24.dp)
            )
        }
    } else {
        // Expanded Gemini / Claude Floating Bar
        Surface(
            color = Color(0xF50A1224),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(
                1.5.dp,
                Brush.horizontalGradient(
                    listOf(FalkePalette.Cyan, FalkePalette.Violet, FalkePalette.EmeraldBright)
                )
            ),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Row
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
                        Text("FalkeAI", color = FalkePalette.TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        Text(
                            text = "• ${ui.statusLine}",
                            color = FalkePalette.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = {
                                expanded = false
                                onOpenMainApp()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.OpenInFull, "Open full app", tint = FalkePalette.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = {
                                controller.stopSession()
                                expanded = false
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, "Minimize bar", tint = FalkePalette.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Quick Context Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickRoutinePill("Ask about screen", Icons.Default.Visibility, FalkePalette.EmeraldBright) {
                        controller.sendTextCommand("Summarize what is on my screen right now and highlight anything actionable", captureScreen = true)
                    }
                    QuickRoutinePill("Catch me up", Icons.Default.Bolt, FalkePalette.Cyan) {
                        controller.sendTextCommand("Catch me up", captureScreen = false)
                    }
                    QuickRoutinePill("Hide pill", Icons.Default.Close, FalkePalette.TextMuted) {
                        controller.stopSession()
                        onHideOverlay()
                    }
                }

                // Thinking or Latest Reply Preview
                if (ui.state == VoiceState.THINKING) {
                    ThinkingDotsIndicator()
                } else if (ui.lastReply.isNotBlank()) {
                    Surface(
                        color = FalkePalette.CardSurface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, FalkePalette.BorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 170.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = remember(ui.lastReply) { renderMarkdown(ui.lastReply) },
                                color = FalkePalette.TextBody,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = { controller.replayMessage(ui.lastReply) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Default.VolumeUp, "Speak", tint = FalkePalette.TextMuted, modifier = Modifier.size(15.dp))
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(MarkdownFormatter.toPlainText(ui.lastReply)))
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, "Copy", tint = FalkePalette.TextMuted, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }

                // Input Pill + Mini Orb
                val sendFloating = {
                    val clean = textInput.trim()
                    if (clean.isNotEmpty()) {
                        textInput = ""
                        controller.sendTextCommand(clean, captureScreen = true)
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        JarvisOrb(
                            state = ui.state,
                            orbSize = 38.dp,
                            onClick = {
                                if (ui.state != VoiceState.IDLE) controller.stopSession()
                                else controller.startVoiceTurn(captureScreen = true, followUpChain = true)
                            }
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (textInput.isEmpty()) {
                                Text("Ask Fall-K about anything…", color = FalkePalette.TextMuted, fontSize = 13.sp)
                            }
                            BasicTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                textStyle = TextStyle(color = FalkePalette.TextPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(FalkePalette.Cyan),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendFloating() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        val hasText = textInput.trim().isNotEmpty()
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (hasText) FalkePalette.Cyan else FalkePalette.CardSurface)
                                .clickable {
                                    if (hasText) sendFloating()
                                    else if (ui.state != VoiceState.IDLE) controller.stopSession()
                                    else controller.startVoiceTurn(captureScreen = true, followUpChain = true)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                                contentDescription = "Send or Speak",
                                tint = if (hasText) Color(0xFF03111F) else FalkePalette.Cyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
