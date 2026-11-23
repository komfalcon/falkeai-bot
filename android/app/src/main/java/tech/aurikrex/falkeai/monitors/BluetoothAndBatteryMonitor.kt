package tech.aurikrex.falkeai.monitors

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.data.TelemetryRequest

data class DeviceHardwareSnapshot(
    val batteryPct: Int,
    val isCharging: Boolean,
    val dndActive: Boolean,
    val btConnected: Boolean
)

/**
 * Monitors battery & Bluetooth headset/earbuds state:
 * - Feature C: Fires instant Dead-Battery Auto-Cover beacon to Heroku when battery <= 10% & unplugged
 *   so FalkeAI switches WhatsApp wait time to 0 mins until Falcon charges again!
 * - Feature D: Detects connected Bluetooth earbuds/headset for hands-free Commute Readout.
 */
object BluetoothAndBatteryMonitor {
    @Volatile
    private var lastCriticalBeaconAtMs: Long = 0L

    fun snapshot(context: Context): DeviceHardwareSnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = ((level * 100f) / scale.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val nm = context.getSystemService(NotificationManager::class.java)
        val dndActive = (nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) !=
            NotificationManager.INTERRUPTION_FILTER_ALL

        return DeviceHardwareSnapshot(
            batteryPct = pct,
            isCharging = isCharging,
            dndActive = dndActive,
            btConnected = isBluetoothAudioConnected(context)
        )
    }

    fun isBluetoothAudioConnected(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    fun createBatteryReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val snap = snapshot(context)
            val now = System.currentTimeMillis()
            val shouldBeacon = snap.batteryPct in 1..10 && !snap.isCharging &&
                (now - lastCriticalBeaconAtMs > 15 * 60_000L)
            if (shouldBeacon || (snap.isCharging && lastCriticalBeaconAtMs > 0L)) {
                if (shouldBeacon) lastCriticalBeaconAtMs = now else lastCriticalBeaconAtMs = 0L
                val app = FalkeApplication.instance
                if (app.prefs.isPaired()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        app.api.sendTelemetry(
                            TelemetryRequest(
                                batteryPct = snap.batteryPct,
                                isCharging = snap.isCharging,
                                dndActive = snap.dndActive,
                                btConnected = snap.btConnected,
                                batteryCritical = shouldBeacon
                            )
                        )
                    }
                }
            }
        }
    }
}
