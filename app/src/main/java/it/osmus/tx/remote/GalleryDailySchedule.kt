package it.osmus.tx.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.util.concurrent.atomic.AtomicBoolean
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import it.osmus.tx.MainActivity
import java.util.concurrent.TimeUnit

/**
 * User-approved daily availability window. WorkManager persists the job over
 * phone reboots, but Android decides its actual execution time.
 */
object GalleryDailySchedule {
    private const val DAILY = "gallery_daily_v1"
    private const val FIRST = "gallery_initial_v1"

    fun ensure(context: Context) {
        if (!GallerySession.isEnabled(context)) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<GalleryDailyWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
    }

    fun activate(context: Context) {
        ensure(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        val initial = OneTimeWorkRequestBuilder<GalleryDailyWorker>()
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FIRST, ExistingWorkPolicy.REPLACE, initial
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(DAILY)
        manager.cancelUniqueWork(FIRST)
    }
}

/** A short, visible, outbound HTTPS session. No foreground-service boot bypass. */
class GalleryDailyWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {

    companion object {
        private val inProgress = AtomicBoolean(false)
    }
    @Volatile private var relay: RelayWorker? = null

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!GallerySession.isEnabled(ctx)) return Result.success()
        if (!Environment.isExternalStorageManager()) return Result.success()
        val config = SecureRelaySettings.load(ctx) ?: return Result.success()
        if (!inProgress.compareAndSet(false, true)) return Result.success()

        // Do not access remote files in an unattended session without visible disclosure.
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            inProgress.set(false)
            return Result.success()
        }

        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        val channelId = "gallery_daily_access_v1"
        val notificationId = 1410
        notificationManager.createNotificationChannel(
            NotificationChannel(channelId, "GALLERY - Collegamento periodico",
                NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pending = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(ctx, channelId)
            .setContentTitle("GALLERY • Collegamento autorizzato")
            .setContentText("Accesso remoto disponibile per alcuni minuti")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()

        try {
            notificationManager.notify(notificationId, notification)
            if (isStopped || !GallerySession.isEnabled(ctx)) return Result.success()
            val current = RelayWorker(ctx, config) { state ->
                if (state == "Relay HTTPS raggiungibile") {
                    ctx.getSharedPreferences("tx_daily_status_v1", Context.MODE_PRIVATE)
                        .edit().putLong("last_online", System.currentTimeMillis()).apply()
                }
            }
            relay = current
            // 4-minute online window. WorkManager jobs have a limited runtime quota.
            current.run(maxDurationMs = 4 * 60_000L)
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            relay?.stop()
            relay = null
            notificationManager.cancel(notificationId)
            inProgress.set(false)
        }
    }

    override fun onStopped() {
        relay?.stop()
        super.onStopped()
    }
}
