package it.osmus.tx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import it.osmus.tx.remote.GalleryResumeNotification
import it.osmus.tx.remote.GallerySession
import it.osmus.tx.remote.SecureRelaySettings
import it.osmus.tx.remote.TxService
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Minimal user-initiated TX launcher; network session is owned by TxService. */
class MainActivity : Activity() {
    private lateinit var actionButton: Button
    private lateinit var permissionButton: Button

    private var startAfterStoragePermission = false
    private var startAfterNotificationPermission = false
    private var resumeFromBootReminder = false
    private var launching = false
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        SecureRelaySettings.migrateLegacy(this)
        startAfterStoragePermission =
            savedInstanceState?.getBoolean("startAfterStoragePermission") ?: false
        startAfterNotificationPermission =
            savedInstanceState?.getBoolean("startAfterNotificationPermission") ?: false
        resumeFromBootReminder = intent?.getBooleanExtra(EXTRA_RESUME_FROM_BOOT, false) == true
        buildScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("startAfterStoragePermission", startAfterStoragePermission)
        outState.putBoolean("startAfterNotificationPermission", startAfterNotificationPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        updateState()
        if (startAfterStoragePermission && Environment.isExternalStorageManager()) {
            startAfterStoragePermission = false
            window.decorView.post { startConnection() }
        } else if (resumeFromBootReminder) {
            resumeFromBootReminder = false
            if (!TxService.active && GallerySession.isEnabled(this) &&
                SecureRelaySettings.load(this) != null &&
                Environment.isExternalStorageManager()) {
                window.decorView.post { startConnection() }
            }
        }
    }

    private fun buildScreen() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            setPadding(dp(24), dp(32), dp(24), dp(40))
        }
        setContentView(column)
        column.addView(TextView(this).apply {
            text = "GALLERY"
            textSize = 36f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        column.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(36))
        })
        permissionButton = Button(this).apply {
            text = "CONSENTI ACCESSO AI FILE"
            isAllCaps = false
            setOnClickListener { startConnection() }
        }
        column.addView(permissionButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        actionButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                if (TxService.active) testConnection() else startConnection()
            }
        }
        column.addView(actionButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        updateState()
    }

    private fun updateState() {
        val permitted = Environment.isExternalStorageManager()
        permissionButton.visibility = if (permitted) View.GONE else View.VISIBLE
        actionButton.text = when {
            TxService.active -> "TEST"
            SecureRelaySettings.load(this) != null -> "AVVIA"
            else -> "INVIA RICHIESTA"
        }
        actionButton.isEnabled = !launching && !testing
    }

    private fun testConnection() {
        if (testing || !TxService.active) return
        testing = true
        actionButton.isEnabled = false
        // Check the running TX relay status AND actual HTTPS reachability.
        // A public health check alone does not prove the TX service is connected.
        thread(name = "tx-connection-test") {
            val online = runCatching {
                val conn = URL(SecureRelaySettings.RELAY_URL + "/health")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                try { conn.responseCode == 200 }
                finally { conn.disconnect() }
            }.getOrDefault(false)
            val status = TxService.connectionStatus
            val ready = TxService.active && status == "Relay HTTPS raggiungibile"
            val response = when {
                !TxService.active || !online -> "TEST NON DISPONIBILE"
                ready -> "TEST OK"
                else -> "TEST IN ATTESA"
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    testing = false
                    Toast.makeText(this, response, Toast.LENGTH_SHORT).show()
                    updateState()
                }
            }
        }
    }

    private fun startConnection() {
        if (TxService.active || launching || isFinishing || isDestroyed) return
        if (!Environment.isExternalStorageManager()) {
            startAfterStoragePermission = true
            openPermissionSettings()
            return
        }
        val preferences = getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification_prompted", false)) {
            startAfterNotificationPermission = true
            preferences.edit().putBoolean("notification_prompted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        startServiceAndClose()
    }

    private fun startServiceAndClose() {
        if (launching || !Environment.isExternalStorageManager()) return
        launching = true
        updateState()
        try {
            val action = if (SecureRelaySettings.load(this) == null)
                TxService.ACTION_ENROLL else TxService.ACTION_START
            startForegroundService(Intent(this, TxService::class.java).apply {
                this.action = action
            })
            GalleryResumeNotification.cancel(this)
            returnToHomeAndFinish()
        } catch (_: Exception) {
            launching = false
            Toast.makeText(this, "AVVIO NON RIUSCITO", Toast.LENGTH_SHORT).show()
            updateState()
        }
    }

    private fun returnToHomeAndFinish() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(home)
        } catch (_: Exception) {
            moveTaskToBack(true)
        } finally {
            finishAndRemoveTask()
        }
    }

    @Deprecated("Android permission result callback")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST && startAfterNotificationPermission) {
            startAfterNotificationPermission = false
            startServiceAndClose()
        }
    }

    private fun openPermissionSettings() {
        val target = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"))
        try { startActivity(target) }
        catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        const val EXTRA_RESUME_FROM_BOOT = "it.osmus.tx.RESUME_FROM_BOOT"
        private const val NOTIFICATION_REQUEST = 405
        private val BG = Color.rgb(11, 17, 30)
    }
}
