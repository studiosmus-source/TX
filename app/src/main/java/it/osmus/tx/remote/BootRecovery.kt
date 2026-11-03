package it.osmus.tx.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import it.osmus.tx.MainActivity

/** Only a previously approved, explicitly started session may be resumed. */
object GallerySession {
    private const val PREFS = "tx_auto_start_v1"
    private const val ENABLED = "enabled_by_user"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }
}

/** BOOT_COMPLETED arrives after unlock, when Android Keystore is available. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!GallerySession.isEnabled(context)) return
        if (!Environment.isExternalStorageManager()) return
        if (SecureRelaySettings.load(context) == null) return

        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+ forbids launching a dataSync FGS directly from boot.
            GalleryResumeNotification.show(context)
        } else {
            try {
                context.startForegroundService(Intent(context, TxService::class.java))
            } catch (_: Exception) {
                GalleryResumeNotification.show(context)
            }
        }
    }
}

/** Visible, user-initiated continuation on Android 15+. No hidden startup. */
object GalleryResumeNotification {
    private const val CHANNEL_ID = "gallery_boot_resume_v1"
    private const val ID = 1403

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GALLERY - Riavvio", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_RESUME_FROM_BOOT, true)
        }
        val pending = PendingIntent.getActivity(context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GALLERY")
            .setContentText("Tocca per riprendere il collegamento")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.cancel(ID)
    }
}
