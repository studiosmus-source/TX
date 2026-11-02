package it.osmus.tx.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment

/** Set only after the owner authorizes and enables GALLERY on the device. */
object GallerySession {
    private const val PREFS = "tx_auto_start_v1"
    private const val ENABLED = "enabled_by_user"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }
}

/** WorkManager already persists tasks through reboot; this is recovery only. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!GallerySession.isEnabled(context)) return
        if (!Environment.isExternalStorageManager()) return
        if (SecureRelaySettings.load(context) == null) return
        GalleryDailySchedule.ensure(context)
    }
}
