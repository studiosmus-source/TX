package it.osmus.tx.remote

import android.content.Context
import android.os.Environment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Presence check only: never reads, sends, or exposes file data. */
object GalleryHeartbeatSchedule {
    private const val PERIODIC = "gallery_heartbeat_daily_v1"
    private const val ONCE = "gallery_heartbeat_initial_v1"

    fun ensure(context: Context) {
        if (!GallerySession.isEnabled(context)) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<GalleryHeartbeatWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    fun checkNow(context: Context) {
        if (!GallerySession.isEnabled(context)) return
        val initial = OneTimeWorkRequestBuilder<GalleryHeartbeatWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.REPLACE, initial)
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(ONCE)
        manager.cancelUniqueWork(PERIODIC)
    }
}

class GalleryHeartbeatWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!GallerySession.isEnabled(context) || !Environment.isExternalStorageManager())
            return Result.success()
        val config = SecureRelaySettings.load(context) ?: return Result.success()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(config.endpoint("/v1/tx/heartbeat"))
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer " + config.txToken)
            conn.setRequestProperty("X-Device-Id", config.deviceId)
            val name = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                .trim().replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)
            conn.setRequestProperty("X-Device-Name", name.ifBlank { "GALLERY" })
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            if (status == 200) {
                context.getSharedPreferences("tx_daily_status_v1", Context.MODE_PRIVATE)
                    .edit().putLong("last_heartbeat", System.currentTimeMillis()).apply()
                Result.success()
            } else if (status in 400..499) Result.failure() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            conn?.disconnect()
        }
    }
}
