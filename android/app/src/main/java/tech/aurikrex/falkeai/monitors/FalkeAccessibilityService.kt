package tech.aurikrex.falkeai.monitors

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Opt-In Accessibility Helper powering 3 flagship features on Android 11 (Infinix Hot 10T):
 * 1) Silent On-Demand Screen Vision (`takeScreenshot()`, API 30+) — zero MediaProjection popup!
 * 2) Cross-App Smart Groq-Whisper Dictation (`"Type this: ..."` -> `ACTION_SET_TEXT` into focused input).
 * 3) On-Demand Active Social Screen Text Reader (reads visible search feed text inside logged-in X/IG/LinkedIn/TikTok).
 */
class FalkeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Strictly on-demand: we do NOT keylog or stream continuous UI events!
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    fun captureVisibleText(maxChars: Int = 1800): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 14 || sb.length >= maxChars) return
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val piece = when {
                txt.isNotEmpty() -> txt
                desc.isNotEmpty() -> desc
                else -> ""
            }
            if (piece.length >= 2) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(piece.take(160))
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }
        runCatching { walk(root, 0) }
        return PrivacyRedactor.redact(sb.toString()).take(maxChars)
    }

    fun setFocusedInputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val existing = focused.text?.toString().orEmpty()
        val combined = if (existing.isBlank()) text else "$existing $text"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun takeSilentScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val encoded = runCatching {
                                val hw = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                screenshot.hardwareBuffer.close()
                                if (hw == null) return@runCatching null
                                val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                                hw.recycle()
                                val scaled = if (sw.width > 720) {
                                    val h = (sw.height * (720f / sw.width)).toInt().coerceAtLeast(1)
                                    Bitmap.createScaledBitmap(sw, 720, h, true).also { sw.recycle() }
                                } else {
                                    sw
                                }
                                val out = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
                                scaled.recycle()
                                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            }.getOrNull()
                            if (cont.isActive) cont.resume(encoded)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    companion object {
        @Volatile
        private var activeInstance: FalkeAccessibilityService? = null

        fun isConnected(): Boolean = activeInstance != null

        fun readCurrentScreenText(): String? = activeInstance?.captureVisibleText()?.ifBlank { null }

        fun injectTextIntoFocusedInput(text: String): Boolean =
            activeInstance?.setFocusedInputText(text) ?: false

        suspend fun captureScreenJpegBase64(): String? =
            activeInstance?.takeSilentScreenshotBase64()
    }
}
