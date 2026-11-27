package tech.aurikrex.falkeai.monitors

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat

data class SurvivalHealthReport(
    val isInfinixOrTranssion: Boolean,
    val manufacturerLabel: String,
    val batteryUnrestricted: Boolean,
    val notificationListenerOn: Boolean,
    val accessibilityOn: Boolean,
    val dndPolicyGranted: Boolean,
    val overlayGranted: Boolean,
    val exactAlarmsAllowed: Boolean,
    val defaultAssistantActive: Boolean,
    val autoStartConfirmed: Boolean,
    val micPermission: Boolean,
    val phonePermission: Boolean,
    val smsPermission: Boolean,
    val contactsPermission: Boolean,
    val calendarPermission: Boolean,
    val cameraPermission: Boolean,
    val postNotificationsPermission: Boolean
) {
    val runtimePermissionsList: List<Pair<String, Boolean>>
        get() = listOf(
            "Microphone" to micPermission,
            "Phone & Calls" to phonePermission,
            "SMS" to smsPermission,
            "Contacts" to contactsPermission,
            "Calendar" to calendarPermission,
            "Camera / Torch" to cameraPermission,
            "Notifications" to postNotificationsPermission
        )

    val grantedRuntimeCount: Int
        get() = runtimePermissionsList.count { it.second }

    val totalRuntimeCount: Int
        get() = runtimePermissionsList.size

    val allRuntimeGranted: Boolean
        get() = grantedRuntimeCount == totalRuntimeCount

    /** Live readiness score across ALL real system capabilities (0..7) */
    val systemReadinessCount: Int
        get() = listOf(
            allRuntimeGranted,
            batteryUnrestricted,
            notificationListenerOn,
            accessibilityOn,
            dndPolicyGranted,
            overlayGranted,
            defaultAssistantActive
        ).count { it }

    val systemReadinessTotal: Int = 7
}

/**
 * Infinix Hot 10T (Android 11 / XOS 7.6 / 4GB RAM) Live Background Survival & Permission Inspector.
 * Every field queries real Android OS state (nothing hardcoded!) so `ON_RESUME` always reflects
 * live changes immediately after returning from Android Settings.
 */
object OemSurvivalHelper {
    fun isTranssionDevice(): Boolean {
        val m = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return m.contains("infinix") || m.contains("transsion") || m.contains("tecno") || m.contains("itel")
    }

    private fun hasPerm(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun inspectHealth(context: Context, autoStartConfirmed: Boolean = false): SurvivalHealthReport {
        val pm = context.getSystemService(PowerManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AlarmManager::class.java)

        val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val notifOk = isNotificationListenerEnabled(context)
        val accOk = isAccessibilityEnabled(context)
        val dndOk = nm?.isNotificationPolicyAccessGranted == true
        val overlayOk = Settings.canDrawOverlays(context)
        val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am?.canScheduleExactAlarms() == true
        } else {
            true
        }
        val assistOk = isDefaultDigitalAssistant(context)

        val micOk = hasPerm(context, Manifest.permission.RECORD_AUDIO)
        val phoneOk = hasPerm(context, Manifest.permission.CALL_PHONE) &&
            hasPerm(context, Manifest.permission.READ_PHONE_STATE)
        val smsOk = hasPerm(context, Manifest.permission.SEND_SMS)
        val contactsOk = hasPerm(context, Manifest.permission.READ_CONTACTS)
        val calOk = hasPerm(context, Manifest.permission.READ_CALENDAR) &&
            hasPerm(context, Manifest.permission.WRITE_CALENDAR)
        val camOk = hasPerm(context, Manifest.permission.CAMERA)
        val postNotifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        return SurvivalHealthReport(
            isInfinixOrTranssion = isTranssionDevice(),
            manufacturerLabel = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
            batteryUnrestricted = batteryOk,
            notificationListenerOn = notifOk,
            accessibilityOn = accOk,
            dndPolicyGranted = dndOk,
            overlayGranted = overlayOk,
            exactAlarmsAllowed = exactOk,
            defaultAssistantActive = assistOk,
            autoStartConfirmed = autoStartConfirmed,
            micPermission = micOk,
            phonePermission = phoneOk,
            smsPermission = smsOk,
            contactsPermission = contactsOk,
            calendarPermission = calOk,
            cameraPermission = camOk,
            postNotificationsPermission = postNotifOk
        )
    }

    /** Real OS check for Default Digital Assistant (`assistant` / `voice_interaction_service` in Settings.Secure). */
    fun isDefaultDigitalAssistant(context: Context): Boolean {
        val pkg = context.packageName.lowercase()
        val assist = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull()?.lowercase().orEmpty()
        val voiceSvc = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull()?.lowercase().orEmpty()
        return assist.contains(pkg) || voiceSvc.contains(pkg)
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(context.packageName)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(context, FalkeAccessibilityService::class.java).flattenToString()
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val s = splitter.next()
            if (s.equals(expected, ignoreCase = true) || s.contains(context.packageName)) {
                return true
            }
        }
        return false
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            openAppDetails(context)
        }
    }

    /** Opens Infinix XOS Phone Master Auto-Start / Protected Apps page (with safe fallback). */
    fun openInfinixAutoStart(context: Context) {
        val candidates = listOf(
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartActivity"),
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.MainActivity")
        )
        for (cn in candidates) {
            val intent = Intent().apply {
                component = cn
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        openAppDetails(context)
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openDefaultAssistantSettings(context: Context) {
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun openDndAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
