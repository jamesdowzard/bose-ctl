package au.com.jd.bose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts BoseService on device boot.
 * Companion device association grants background FGS start privileges.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, BoseService::class.java))
        }
    }
}
