package it.osmus.tx.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Environment
import it.osmus.tx.MainActivity
import kotlin.concurrent.thread

/** Servizio esplicito: accesso remoto attivo solo dopo il comando Avvia nell'app TX. */
class TxService : Service() {
    private var worker: RelayWorker? = null
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "GALLERY - Collegamento", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Environment.isExternalStorageManager()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val config = SecureRelaySettings.load(this) ?: run { stopSelf(); return START_NOT_STICKY }
        val notificationIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("GALLERY")
            .setContentText("Connesso in sicurezza")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(notificationIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (workerThread?.isAlive == true) return START_STICKY
        connectionStatus = "Connessione HTTPS in avvio"
        worker = RelayWorker(this, config) { state -> connectionStatus = state }
        active = true
        workerThread = thread(name = "tx-https-relay") {
            try { worker?.run() } finally { stopSelf() }
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
