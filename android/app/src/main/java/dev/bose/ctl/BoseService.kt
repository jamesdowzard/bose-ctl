package dev.bose.ctl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Foreground service for managing Bose RFCOMM connection.
 *
 * Tracks two multipoint slots locally:
 *   Slot 1 (green)  = active audio source
 *   Slot 2 (orange) = other connected device
 *
 * Rules when tapping a device:
 *   - Already in a slot → just swap green/orange
 *   - New device → takes slot 1, old slot 1 → slot 2, old slot 2 drops out
 */
class BoseService : Service() {

    companion object {
        private const val TAG = "BoseService"
        private const val CHANNEL_ID = "bose_ctl"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "bose_ctl"

        const val ACTION_CONNECT_DEVICE = "dev.bose.ctl.CONNECT_DEVICE"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val ACTION_REFRESH = "dev.bose.ctl.REFRESH"

        const val BROADCAST_STATUS = "dev.bose.ctl.STATUS_UPDATE"
        const val EXTRA_ACTIVE_DEVICE = "active_device"
        const val EXTRA_CONNECTED_DEVICES = "connected_devices"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_ERROR = "error"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BoseService = this@BoseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        when (intent?.action) {
            ACTION_CONNECT_DEVICE -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: return START_NOT_STICKY
                executor.submit {
                    switchDevice(deviceName)
                    stopSelf(startId)
                }
            }
            ACTION_REFRESH -> {
                executor.submit {
                    refreshStatus()
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bose Control",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "RFCOMM operations"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("Bose")
            .setContentText("Switching source...")
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureConnected(): Boolean {
        if (BoseProtocol.isConnected) return true
        Log.d(TAG, "Connecting to headphones...")
        return BoseProtocol.connect()
    }

    // --- Slot state (persisted in SharedPreferences) ---

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var slot1: String?
        get() = prefs.getString("active_device", null)
        set(v) = prefs.edit().putString("active_device", v).apply()

    private var slot2: String?
        get() = prefs.getString("connected_device", null)
        set(v) {
            if (v != null) prefs.edit().putString("connected_device", v).apply()
            else prefs.edit().remove("connected_device").apply()
        }

    /**
     * Update slots when switching to a device.
     *
     * - Already in a slot → swap (no device dropped)
     * - New device → pushes: new→slot1, old slot1→slot2, old slot2→gone
     */
    private fun updateSlots(newActive: String) {
        val s1 = slot1
        val s2 = slot2

        when (newActive) {
            s1 -> { /* Already active, no change */ }
            s2 -> { slot1 = newActive; slot2 = s1 }
            else -> { slot1 = newActive; slot2 = s1 }
        }
    }

    private fun broadcastCurrentState() {
        val active = slot1 ?: return
        val connected = arrayListOf(active)
        slot2?.let { connected.add(it) }
        broadcastStatus(active, true, connected)
    }

    // --- Device switching ---

    private fun switchDevice(deviceName: String) {
        try {
            if (!ensureConnected()) {
                broadcastError("Cannot connect to headphones")
                return
            }

            val mac = BoseProtocol.DEVICES[deviceName]
            if (mac == null) {
                broadcastError("Unknown device: $deviceName")
                return
            }

            Log.i(TAG, "Switching to $deviceName (slot1=${slot1} slot2=${slot2})")
            val success = BoseProtocol.connectDevice(mac)

            if (success) {
                updateSlots(deviceName)
                broadcastCurrentState()
            } else {
                broadcastError("Failed to switch to $deviceName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Switch error", e)
            broadcastError(e.message ?: "Unknown error")
        }
    }

    private fun refreshStatus() {
        try {
            if (!ensureConnected()) {
                broadcastError("Cannot connect to headphones")
                return
            }

            // Seed from protocol on refresh (e.g. app first launch)
            val activeMac = BoseProtocol.getActiveDevice()
            if (activeMac != null) {
                val name = BoseProtocol.nameForMac(activeMac)
                if (slot1 == null) {
                    // First time — seed slots from protocol
                    slot1 = name
                    val others = BoseProtocol.getConnectedDevices().map { BoseProtocol.nameForMac(it) }
                    slot2 = others.firstOrNull { it != name }
                    // If protocol didn't report a second device and phone isn't active, assume phone
                    if (slot2 == null && name != "phone") slot2 = "phone"
                } else if (name != slot1) {
                    // Active device changed externally (e.g. from Bose app)
                    updateSlots(name)
                }
                broadcastCurrentState()
            } else {
                broadcastError("Could not get active device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh error", e)
            broadcastError(e.message ?: "Unknown error")
        }
    }

    // --- Broadcasting ---

    private fun broadcastStatus(activeDevice: String, success: Boolean, connectedDevices: ArrayList<String>? = null) {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVE_DEVICE, activeDevice)
            putExtra(EXTRA_SUCCESS, success)
            connectedDevices?.let { putStringArrayListExtra(EXTRA_CONNECTED_DEVICES, it) }
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(error: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_SUCCESS, false)
            putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        BoseProtocol.disconnect()
        super.onDestroy()
    }
}
