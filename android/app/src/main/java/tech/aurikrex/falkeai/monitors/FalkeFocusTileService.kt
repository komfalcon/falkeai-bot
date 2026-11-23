package tech.aurikrex.falkeai.monitors

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.aurikrex.falkeai.FalkeApplication
import tech.aurikrex.falkeai.data.PhoneChatRequest
import tech.aurikrex.falkeai.executors.PhoneMediaExecutor

/**
 * Android Quick Settings Tile ("FalkeAI Lock In"):
 * 1 tap in the notification shade toggles Deep Work + DND + Spotify focus music!
 */
class FalkeFocusTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val hw = BluetoothAndBatteryMonitor.snapshot(this)
        qsTile?.apply {
            state = if (hw.dndActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (hw.dndActive) "Locked In" else "FalkeAI Lock In"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val currentlyActive = qsTile?.state == Tile.STATE_ACTIVE
        val enableLockIn = !currentlyActive
        qsTile?.apply {
            state = if (enableLockIn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (enableLockIn) "Locked In" else "FalkeAI Lock In"
            updateTile()
        }
        PhoneMediaExecutor.setDnd(this, enableLockIn, if (enableLockIn) "priority" else "off")
        if (enableLockIn) {
            PhoneMediaExecutor.playSpotify(this, "deep focus instrumental beats")
        }
        val app = FalkeApplication.instance
        if (app.prefs.isPaired()) {
            CoroutineScope(Dispatchers.IO).launch {
                val cmd = if (enableLockIn) "Lock in for 90 minutes" else "I'm available now"
                app.api.chat(PhoneChatRequest(text = cmd, wantTts = false))
            }
        }
    }
}
