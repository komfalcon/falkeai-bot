package tech.aurikrex.falkeai.executors

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ResolvedContact(
    val displayName: String,
    val phoneNumber: String,
    val isSavedContact: Boolean
)

/**
 * Direct native Android phone executors:
 * Calls, SMS (SmsManager), Contacts lookup, Spotify playback & media keys,
 * Do Not Disturb, Flashlight, and Find-My-Phone Max-Volume Siren + Camera Flash Strobe.
 */
object PhoneMediaExecutor {
    private var findPhoneJob: Job? = null
    private var sirenPlayer: MediaPlayer? = null

    fun normalizeDialNumber(raw: String): String {
        val cleaned = raw.trim().filter { it.isDigit() || it == '+' }
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.startsWith("0") && cleaned.length == 11 -> "+234${cleaned.drop(1)}"
            cleaned.startsWith("234") && cleaned.length >= 13 -> "+$cleaned"
            else -> cleaned
        }
    }

    /** Blocks shortcodes (< 7 digits, e.g. premium billing codes) from automated dialing/SMS. */
    fun isSafeDialOrSmsNumber(raw: String): Boolean {
        val digits = raw.count { it.isDigit() }
        return digits in 7..15
    }

    fun resolveContact(context: Context, queryOrPhone: String): ResolvedContact? {
        val q = queryOrPhone.trim()
        if (q.isEmpty()) return null
        val digitsOnly = q.filter { it.isDigit() }
        val looksLikeNumber = digitsOnly.length >= 7 && q.count { it.isLetter() } <= 1

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    var partialMatch: ResolvedContact? = null
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0).orEmpty().trim()
                        val num = cursor.getString(1).orEmpty().trim()
                        if (num.isBlank()) continue
                        val normNum = normalizeDialNumber(num)
                        if (looksLikeNumber && normNum.endsWith(digitsOnly.takeLast(10))) {
                            return ResolvedContact(name.ifBlank { normNum }, normNum, true)
                        }
                        if (!looksLikeNumber) {
                            if (name.equals(q, ignoreCase = true)) {
                                return ResolvedContact(name, normNum, true)
                            }
                            if (partialMatch == null && name.contains(q, ignoreCase = true)) {
                                partialMatch = ResolvedContact(name, normNum, true)
                            }
                        }
                    }
                    if (partialMatch != null) return partialMatch
                }
            }
        }

        return if (looksLikeNumber) {
            val norm = normalizeDialNumber(q)
            ResolvedContact(norm, norm, false)
        } else {
            null
        }
    }

    fun callContact(
        context: Context,
        targetOrPhone: String,
        confirmUnknownNumbers: Boolean = true
    ): String {
        val resolved = resolveContact(context, targetOrPhone)
            ?: return "Couldn't find \"$targetOrPhone\" in your phone contacts."
        if (!isSafeDialOrSmsNumber(resolved.phoneNumber)) {
            return "Blocked unsafe / shortcode number: ${resolved.phoneNumber}."
        }
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val useDirectCall = hasCallPerm && (resolved.isSavedContact || !confirmUnknownNumbers)
        val action = if (useDirectCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(resolved.phoneNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            if (useDirectCall) "Calling ${resolved.displayName}."
            else "Opened dialer for ${resolved.displayName} — tap Call to confirm."
        }.getOrElse { "Couldn't place call: ${it.message}" }
    }

    fun sendSmsNow(
        context: Context,
        phoneOrTarget: String,
        message: String,
        confirmUnknownNumbers: Boolean = true
    ): String {
        val resolved = resolveContact(context, phoneOrTarget)
            ?: return "Couldn't find \"$phoneOrTarget\" to text."
        if (!isSafeDialOrSmsNumber(resolved.phoneNumber)) {
            return "Blocked unsafe / shortcode SMS number: ${resolved.phoneNumber}."
        }
        val hasSmsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPerm || (!resolved.isSavedContact && confirmUnknownNumbers)) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${resolved.phoneNumber}")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                context.startActivity(smsIntent)
                "Opened SMS composer for ${resolved.displayName} so you can confirm."
            }.getOrElse { "Couldn't open SMS app: ${it.message}" }
        }

        return runCatching {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(resolved.phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(resolved.phoneNumber, null, message, null, null)
            }
            "Sent SMS to ${resolved.displayName}."
        }.getOrElse { "Failed to send SMS: ${it.message}" }
    }

    fun playSpotify(context: Context, query: String): String {
        val q = query.trim().ifBlank { "focus instrumental" }
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, q)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(playIntent) }.isSuccess) {
            return "Playing \"$q\" on Spotify."
        }
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(q)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(uriIntent)
            "Opened Spotify search for \"$q\"."
        }.getOrElse {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://open.spotify.com/search/${Uri.encode(q)}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(webIntent) }
            "Opened Spotify web for \"$q\"."
        }
    }

    fun mediaControl(context: Context, action: String): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "Audio service unavailable."
        val keyCode = when (action.lowercase()) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0))
        return "Media: $action."
    }

    fun setDnd(context: Context, enabled: Boolean, mode: String = "priority"): String {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return "DND service unavailable."
        if (!nm.isNotificationPolicyAccessGranted) {
            return "Grant Do Not Disturb access in FalkeAI Setup to toggle DND."
        }
        val filter = if (!enabled || mode.equals("off", ignoreCase = true)) {
            NotificationManager.INTERRUPTION_FILTER_ALL
        } else if (mode.equals("alarms_only", ignoreCase = true)) {
            NotificationManager.INTERRUPTION_FILTER_ALARMS
        } else {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        }
        nm.setInterruptionFilter(filter)
        return if (!enabled || mode.equals("off", ignoreCase = true)) "Do Not Disturb turned OFF."
        else "Do Not Disturb turned ON ($mode)."
    }

    fun setFlashlight(context: Context, on: Boolean): String {
        val cm = context.getSystemService(CameraManager::class.java) ?: return "Flashlight unavailable."
        return runCatching {
            val backCamId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return "No camera flash found."
            cm.setTorchMode(backCamId, on)
            if (on) "Flashlight turned ON." else "Flashlight turned OFF."
        }.getOrElse { "Couldn't toggle flashlight: ${it.message}" }
    }

    /**
     * Feature #3 ("Buddy, you there?" / WhatsApp `ring my phone`):
     * Rings at MAX ALARM volume bypassing Silent/DND + strobes camera LED flash for 12 seconds.
     */
    fun triggerFindMyPhoneSiren(context: Context, durationSeconds: Int = 12) {
        stopFindMyPhoneSiren(context)
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(AudioManager::class.java)
        val prevVol = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 5
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
        runCatching { am?.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0) }

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        runCatching {
            sirenPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(appCtx, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        }

        findPhoneJob = CoroutineScope(Dispatchers.Default).launch {
            var torchState = false
            val loops = (durationSeconds * 2).coerceIn(6, 40)
            try {
                repeat(loops) {
                    if (!isActive) return@launch
                    torchState = !torchState
                    setFlashlight(appCtx, torchState)
                    delay(450L)
                }
            } finally {
                setFlashlight(appCtx, false)
                runCatching {
                    sirenPlayer?.stop()
                    sirenPlayer?.release()
                    sirenPlayer = null
                    am?.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
                }
            }
        }
    }

    fun stopFindMyPhoneSiren(context: Context) {
        findPhoneJob?.cancel()
        findPhoneJob = null
        runCatching {
            sirenPlayer?.stop()
            sirenPlayer?.release()
            sirenPlayer = null
        }
        setFlashlight(context.applicationContext, false)
    }

    fun openAppByName(context: Context, name: String): String {
        val lower = name.trim().lowercase()
        val pkg = when {
            lower.contains("spotify") -> "com.spotify.music"
            lower.contains("whatsapp") -> "com.whatsapp"
            lower.contains("twitter") || lower == "x" -> "com.twitter.android"
            lower.contains("instagram") || lower == "ig" -> "com.instagram.android"
            lower.contains("linkedin") -> "com.linkedin.android"
            lower.contains("tiktok") -> "com.zhiliaoapp.musically"
            lower.contains("youtube") -> "com.google.android.youtube"
            lower.contains("reddit") -> "com.reddit.frontpage"
            lower.contains("telegram") -> "org.telegram.messenger"
            lower.contains("gmail") -> "com.google.android.gm"
            lower.contains("chrome") -> "com.android.chrome"
            else -> null
        }
        if (pkg != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return "Opened $name."
            }
        }
        return "Couldn't find installed app \"$name\"."
    }

    fun openSocialAppSearch(context: Context, platform: String, query: String): String {
        val q = Uri.encode(query.trim())
        val uri = when (platform.lowercase()) {
            "x", "twitter" -> "https://x.com/search?q=$q&f=live"
            "instagram", "ig" -> "https://www.instagram.com/explore/tags/${q.replace("%20", "")}/"
            "linkedin" -> "https://www.linkedin.com/search/results/all/?keywords=$q"
            "tiktok" -> "https://www.tiktok.com/search?q=$q"
            "youtube" -> "https://www.youtube.com/results?search_query=$q"
            "reddit" -> "https://www.reddit.com/search/?q=$q"
            else -> "https://duckduckgo.com/?q=$q"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "Opened $platform search for \"$query\"."
        }.getOrElse { "Couldn't open $platform search." }
    }
}
