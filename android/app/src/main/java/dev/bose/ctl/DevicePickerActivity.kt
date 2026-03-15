package dev.bose.ctl

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

/**
 * Transparent activity that shows a device picker dialog.
 * Used from the Quick Settings tile.
 * Shows status dots: green = active, orange = connected, no dot = disconnected.
 */
class DevicePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDevice = intent.getStringExtra("current_device")
        val connectedDevices = intent.getStringArrayListExtra("connected_devices") ?: arrayListOf()
        val deviceNames = BoseProtocol.DEVICES.keys.toTypedArray()

        // Unicode circles for status dots
        val labels = deviceNames.map { name ->
            when {
                name == currentDevice -> "\uD83D\uDFE2  $name" // green circle
                name in connectedDevices -> "\uD83D\uDFE0  $name" // orange circle
                else -> "\u26AB  $name" // black circle
            }
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Switch Bose Source")
            .setItems(labels) { _, which ->
                val selected = deviceNames[which]
                val intent = Intent(this, BoseService::class.java).apply {
                    action = BoseService.ACTION_CONNECT_DEVICE
                    putExtra(BoseService.EXTRA_DEVICE_NAME, selected)
                }
                startService(intent)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
