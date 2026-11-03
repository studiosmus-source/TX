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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.osmus.tx.remote.GallerySession
import it.osmus.tx.remote.GalleryResumeNotification
import it.osmus.tx.remote.SecureRelaySettings
import it.osmus.tx.remote.TxService

/**
 * TX is a one-touch enrollment launcher. The foreground service owns the
 * network request and approval, so finishing this Activity never cancels it.
 */
class MainActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var activationStatus: TextView
    private lateinit var actionButton: Button
    private lateinit var permissionButton: Button

    private var startAfterStoragePermission = false
    private var startAfterNotificationPermission = false
    private var resumeFromBootReminder = false
    private var launching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 17, 30)
        window.navigationBarColor = Color.rgb(11, 17, 30)
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
            setBackgroundColor(Color.rgb(11, 17, 30))
            setPadding(dp(24), dp(32), dp(24), dp(40))
        }
        ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 17, 30))
            isFillViewport = true
            addView(column)
            setContentView(this)
        }
        column.addView(text("GALLERY", 36f, Color.WHITE, true))
        column.addView(space(24))
        column.addView(space(44))
        permissionStatus = text("", 15f, SILVER, true)
        column.addView(permissionStatus)
        permissionButton = Button(this).apply {
            text = "CONSENTI ACCESSO AI FILE"
            isAllCaps = false
            setOnClickListener { startConnection() }
        }
        column.addView(permissionButton)
        column.addView(space(28))
        activationStatus = text("", 15f, SKY)
        column.addView(activationStatus)
        column.addView(space(20))
        actionButton = Button(this).apply {
            text = "INVIA RICHIESTA A RX"
            isAllCaps = false
            setOnClickListener {
                if (TxService.active) stopConnection() else startConnection()
            }
        }
        column.addView(actionButton)
        updateState()
    }

    private fun updateState() {
        val hasPermission = Environment.isExternalStorageManager()
        val approved = SecureRelaySettings.load(this) != null
        permissionStatus.text = if (hasPermission) "✓ Accesso consentito" else ""
        permissionStatus.setTextColor(if (hasPermission) GREEN else AMBER)
        permissionStatus.visibility = if (hasPermission) View.VISIBLE else View.GONE
        permissionButton.visibility = if (hasPermission) View.GONE else View.VISIBLE
        actionButton.text = when {
            TxService.active -> "INTERROMPI COLLEGAMENTO"
            approved -> "AVVIA COLLEGAMENTO"
            else -> "INVIA RICHIESTA A RX"
        }
        actionButton.isEnabled = !launching
        activationStatus.text = when {
            TxService.active -> TxService.connectionStatus
            approved -> "✓ Già autorizzato: non serve un nuovo accoppiamento"
            !hasPermission -> "Autorizza i file una sola volta, poi TX farà la richiesta"
            else -> "Tocca INVIA RICHIESTA, approva su RX: TX si avvia da solo"
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
            // Explicitly return to Android Home while the user-initiated
            // foreground service continues the RX approval in the background.
            // finishAndRemoveTask() alone can leave the old app window visible
            // on some launchers until task-switching completes.
            returnToHomeAndFinish()
        } catch (_: Exception) {
            launching = false
            activationStatus.text = "Impossibile avviare TX: controlla i permessi Android"
            actionButton.isEnabled = true
        }
    }

    private fun returnToHomeAndFinish() {
        // This is executed ONLY from the visible TX Activity after a user tap
        // or from a previously user-tapped Android permission/resume flow.
        // The relay/enrollment is owned by TxService, never by this Activity.
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(home)
        } catch (_: Exception) {
            // If this launcher cannot resolve HOME, move our own task back.
            moveTaskToBack(true)
        } finally {
            finishAndRemoveTask()
        }
    }

    private fun stopConnection() {
        GallerySession.setEnabled(this, false)
        GalleryResumeNotification.cancel(this)
        getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
            .edit().putBoolean("pending", false).apply()
        stopService(Intent(this, TxService::class.java))
        updateState()
        activationStatus.text = "Collegamento interrotto"
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

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun space(value: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(value))
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        const val EXTRA_RESUME_FROM_BOOT = "it.osmus.tx.RESUME_FROM_BOOT"
        private const val NOTIFICATION_REQUEST = 405
        private val SKY = Color.rgb(116, 192, 238)
        private val SILVER = Color.rgb(196, 204, 217)
        private val GREEN = Color.rgb(102, 224, 163)
        private val AMBER = Color.rgb(255, 193, 119)
    }
}
