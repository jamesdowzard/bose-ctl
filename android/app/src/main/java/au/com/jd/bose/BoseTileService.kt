package au.com.jd.bose

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile for Bose source switching.
 *
 * - Shows current active source as subtitle
 * - Tapping opens DevicePickerActivity dialog
 * - Listens for status broadcasts from BoseService
 */
class BoseTileService : TileService() {

    private var activeDevice: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BoseService.BROADCAST_STATUS) {
                val success = intent.getBooleanExtra(BoseService.EXTRA_SUCCESS, false)
                if (success) {
                    activeDevice = intent.getStringExtra(BoseService.EXTRA_ACTIVE_DEVICE)
                    updateTile()
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        registerReceiver(
            statusReceiver,
            IntentFilter(BoseService.BROADCAST_STATUS),
            Context.RECEIVER_NOT_EXPORTED,
        )
        refreshStatus()
    }

    override fun onStopListening() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, DevicePickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activeDevice?.let { putExtra("current_device", it) }
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pi)
    }

    private fun refreshStatus() {
        try {
            startForegroundService(Intent(this, BoseService::class.java).apply {
                action = BoseService.ACTION_REFRESH
            })
        } catch (_: Exception) {}
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (activeDevice != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Bose"
        tile.subtitle = activeDevice ?: "..."
        tile.icon = Icon.createWithResource(this, R.drawable.ic_headphones)
        tile.updateTile()
    }
}
