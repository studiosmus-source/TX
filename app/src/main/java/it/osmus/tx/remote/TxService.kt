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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import it.osmus.tx.MainActivity
import kotlin.concurrent.thread

/**
 * User-initiated foreground service owns BOTH enrollment and the file relay.
 * Closing the TX Activity must never cancel a pending RX approval.
 */
class TxService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var enrollment: EnrollmentClient? = null
    private var enrollmentThread: Thread? = null
    private var enrolling = false
    private var lastPairCode: String? = null
    private var worker: RelayWorker? = null
    private var workerThread: Thread? = null
    @Volatile private var stopping = false

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
            GallerySession.setEnabled(this, false)
            getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
                .edit().putBoolean("pending", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Environment.isExternalStorageManager()) {
            connectionStatus = "Permesso file necessario"
            stopSelf()
            return START_NOT_STICKY
        }

        val config = SecureRelaySettings.load(this)
        val pendingApproval = getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
            .getBoolean("pending", false)
        val canEnroll = intent?.action == ACTION_ENROLL || (intent == null && pendingApproval)
        if (config == null && !canEnroll) {
            connectionStatus = "Richiesta da TX necessaria"
            stopSelf()
            return START_NOT_STICKY
        }

        // Android requires a foreground notification even before RX approves the request.
        if (!active) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("Attesa approvazione RX"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification("Attesa approvazione RX"))
            }
            active = true
        }
        stopping = false
        if (workerThread?.isAlive == true || enrolling) return START_STICKY

        if (config == null) {
            getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
                .edit().putBoolean("pending", true).apply()
            GallerySession.setEnabled(this, false)
            startEnrollment()
        } else {
            GallerySession.setEnabled(this, true)
            getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
                .edit().putBoolean("pending", false).apply()
            startRelay(config)
        }
        return START_STICKY
    }

    private fun startEnrollment() {
        if (enrolling || stopping) return
        enrolling = true
        val deviceName = (Build.MANUFACTURER + " " + Build.MODEL)
            .trim().take(60).ifEmpty { "GALLERY" }
        val client = EnrollmentClient(deviceName)
        enrollment = client
        setStatus("Attesa approvazione RX")
        enrollmentThread = thread(name = "tx-enrollment") {
            client.run(
                onCode = { code ->
                    mainHandler.post {
                        if (!stopping) {
                            lastPairCode = code
                            setStatus("Codice $code · approva su RX")
                        }
                    }
                },
                onReady = { token ->
                    mainHandler.post {
                        if (stopping) return@post
                        val saved = runCatching {
                            SecureRelaySettings.save(applicationContext, token)
                            SecureRelaySettings.load(applicationContext)
                        }.getOrNull()
                        if (saved == null) {
                            setStatus("Errore salvataggio sicuro: riapri TX")
                            stopSelf()
                            return@post
                        }
                        enrollment = null
                        enrolling = false
                        lastPairCode = null
                        getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
                            .edit().putBoolean("pending", false).apply()
                        GallerySession.setEnabled(this, true)
                        startRelay(saved)
                    }
                },
                onError = { error ->
                    mainHandler.post {
                        if (!stopping) {
                            val code = lastPairCode
                            setStatus(if (code == null) error else "Codice $code · $error")
                        }
                    }
                }
            )
        }
    }

    private fun startRelay(config: RelayConfig) {
        if (workerThread?.isAlive == true || stopping) return
        setStatus("Connessione HTTPS in avvio", "Connesso in sicurezza")
        worker = RelayWorker(this, config) { state ->
            connectionStatus = state
        }
        workerThread = thread(name = "tx-https-relay") {
            try {
                worker?.run()
            } finally {
                mainHandler.post {
                    if (!stopping) stopSelf()
                }
            }
        }
    }

    private fun setStatus(status: String, notificationText: String = status) {
        connectionStatus = status
        if (!stopping && active) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(notificationText))
        }
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("GALLERY")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    override fun onDestroy() {
        stopping = true
        active = false
        connectionStatus = "Connessione interrotta"
        enrollment?.cancel()
        enrollmentThread?.interrupt()
        enrolling = false
        enrollment = null
        lastPairCode = null
        worker?.stop()
        workerThread?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        const val ACTION_ENROLL = "it.osmus.tx.ENROLL"
        const val ACTION_START = "it.osmus.tx.START"
        const val ACTION_STOP = "it.osmus.tx.STOP"
        const val CHANNEL = "tx_connection"
        const val NOTIFICATION_ID = 1402
        @Volatile var active = false
            private set
        @Volatile var connectionStatus = "Connessione non avviata"
            private set
    }
}
