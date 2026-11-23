package tech.aurikrex.falkeai.monitors

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils

data class SurvivalHealthReport(
    val isInfinixOrTranssion: Boolean,
    val manufacturerLabel: String,
    val batteryUnrestricted: Boolean,
    val notificationListenerOn: Boolean,
    val accessibilityOn: Boolean,
    val dndPolicyGranted: Boolean,
    val overlayGranted: Boolean,
    val exactAlarmsAllowed: Boolean
)

/**
 * Infinix Hot 10T (Android 11 / XOS 7.6 / 4GB RAM) Background Survival Helper.
 * Provides live checklist verification and 1-tap deep links straight into Infinix Phone Master,
 * Battery Unrestricted, Notification Access, Accessibility, DND, and Default Digital Assistant settings.
 */
object OemSurvivalHelper {
    fun isTranssionDevice(): Boolean {
        val m = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return m.contains("infinix") || m.contains("transsion") || m.contains("tecno") || m.contains("itel")
    }

    fun inspectHealth(context: Context): SurvivalHealthReport {
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

        return SurvivalHealthReport(
            isInfinixOrTranssion = isTranssionDevice(),
            manufacturerLabel = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
            batteryUnrestricted = batteryOk,
            notificationListenerOn = notifOk,
            accessibilityOn = accOk,
            dndPolicyGranted = dndOk,
            overlayGranted = overlayOk,
            exactAlarmsAllowed = exactOk
        )
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
