package it.osmus.tx.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import it.osmus.tx.MainActivity
import kotlin.concurrent.thread

/**
 * Restored HTTPS relay session from GALLERY 0.4.2.
 * This is a visible, owner-authorized foreground service, never a hidden one.
 * Android 15+ limits dataSync service duration (6h per rolling 24h).
 */
class TxService : Service() {
    private var worker: RelayWorker? = null
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "GALLERY - Accesso remoto autorizzato", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !GallerySession.isEnabled(this) ||
            !Environment.isExternalStorageManager() || !GalleryAccess.canShowRemoteDisclosure(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val config = SecureRelaySettings.load(this) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notice = Notification.Builder(this, CHANNEL)
            .setContentTitle("GALLERY • Accesso remoto attivo")
            .setContentText("Il telefono condivide file con RX autorizzato")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notice,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notice)
        }
        if (workerThread?.isAlive == true) return START_STICKY
        connectionStatus = "Connessione HTTPS in avvio"
        worker = RelayWorker(this, config) { connectionStatus = it }
        active = true
        workerThread = thread(name = "gallery-relay-foreground") {
            try {
                worker?.run()
            } finally {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        connectionStatus = "Connessione interrotta"
        worker?.stop()
        workerThread?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        connectionStatus = "Limite Android raggiunto"
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "it.osmus.tx.STOP"
        const val CHANNEL = "tx_connection"
        const val NOTIFICATION_ID = 1402
        @Volatile var active = false
            private set
        @Volatile var connectionStatus = "Connessione non avviata"
            private set
    }
}

/** The remote file channel requires an owner-visible disclosure. */
object GalleryAccess {
    fun canShowRemoteDisclosure(context: android.content.Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
