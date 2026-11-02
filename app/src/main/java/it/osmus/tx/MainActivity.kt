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
import it.osmus.tx.remote.EnrollmentClient
import it.osmus.tx.remote.GallerySession
import it.osmus.tx.remote.GalleryDailySchedule
import it.osmus.tx.remote.SecureRelaySettings
import kotlin.concurrent.thread

/** The transmitter has no server address or key fields. */
class MainActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var activationStatus: TextView
    private lateinit var actionButton: Button
    private lateinit var permissionButton: Button
    private var enrollment: EnrollmentClient? = null
    private var enrollmentThread: Thread? = null
    private var activationMessage = ""
    private var pendingStartAfterNotification = false
    private var pendingBackgroundAfterNotification = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 17, 30)
        window.navigationBarColor = Color.rgb(11, 17, 30)
        SecureRelaySettings.migrateLegacy(this)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        updateState()
        if (GallerySession.isEnabled(this)) GalleryDailySchedule.ensure(this)
        if (Environment.isExternalStorageManager() && SecureRelaySettings.load(this) == null) {
            beginEnrollmentIfNeeded()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startWhenApproved()
    }

    private fun startWhenApproved() {
        if (!hasWindowFocus() || isFinishing || isDestroyed ||
            !Environment.isExternalStorageManager()) return
        val prefs = getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
        if (prefs.getBoolean("pending", false) && SecureRelaySettings.load(this) != null)
            startConnection(backgroundAfterStart = true)
    }

    override fun onDestroy() {
        enrollment?.cancel()
        enrollmentThread?.interrupt()
        super.onDestroy()
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
            setOnClickListener { openPermissionSettings() }
        }
        column.addView(permissionButton)
        column.addView(space(28))
        activationStatus = text("", 15f, SKY)
        column.addView(activationStatus)
        column.addView(space(20))
        actionButton = Button(this).apply {
            text = "SALVA E AVVIA"
            isAllCaps = false
            setOnClickListener { if (GallerySession.isEnabled(this@MainActivity)) stopConnection()
                else startConnection() }
        }
        column.addView(actionButton)
    }

    private fun beginEnrollmentIfNeeded() {
        if (enrollmentThread?.isAlive == true || isFinishing || isDestroyed) return
        val name = (Build.MANUFACTURER + " " + Build.MODEL).trim().take(60).ifEmpty { "GALLERY" }
        val client = EnrollmentClient(name)
        enrollment = client
        activationMessage = "Richiesta di attivazione…"
        updateState()
        enrollmentThread = thread(name = "tx-enrollment") {
            client.run(
                onCode = { code -> showMessage("Codice: $code\nIn attesa di approvazione") },
                onReady = { deviceId, token ->
                    val saved = runCatching {
                        SecureRelaySettings.save(applicationContext, deviceId, token)
                    }.isSuccess
                    if (saved) {
                        getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE).edit()
                            .putBoolean("pending", true).apply()
                        showMessage("✓ Attivato")
                        runOnUiThread { startWhenApproved() }
                    } else showMessage("Salvataggio sicuro non riuscito.")
                },
                onError = { showMessage(it) }
            )
        }
    }

    private fun showMessage(value: String) {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                activationMessage = value
                updateState()
            }
        }
    }

    private fun updateState() {
        val hasPermission = Environment.isExternalStorageManager()
        permissionStatus.text = if (hasPermission) "✓ Accesso consentito" else ""
        permissionStatus.setTextColor(if (hasPermission) GREEN else AMBER)
        permissionStatus.visibility = if (hasPermission) View.VISIBLE else View.GONE
        permissionButton.visibility = if (hasPermission) View.GONE else View.VISIBLE
        val activated = SecureRelaySettings.load(this) != null
        actionButton.text = if (GallerySession.isEnabled(this)) "DISATTIVA COLLEGAMENTO"
            else "ATTIVA COLLEGAMENTO"
        actionButton.isEnabled = GallerySession.isEnabled(this) || (hasPermission && activated)
        activationStatus.visibility = if (hasPermission) View.VISIBLE else View.GONE
        activationStatus.text = when {
            GallerySession.isEnabled(this) -> {
                val last = getSharedPreferences("tx_daily_status_v1", MODE_PRIVATE)
                    .getLong("last_online", 0L)
                if (last > 0L) "✓ Collegamento automatico giornaliero attivo"
                else "✓ Programmazione giornaliera attiva"
            }
            activated -> "✓ Pronto"
            !hasPermission -> ""
            else -> activationMessage.ifBlank { "Attivazione in corso…" }
        }
    }

    private fun startConnection(backgroundAfterStart: Boolean = false) {
        if (!Environment.isExternalStorageManager() || SecureRelaySettings.load(this) == null ||
            pendingStartAfterNotification) return
        val preferences = getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification_prompted", false)) {
            pendingStartAfterNotification = true
            pendingBackgroundAfterNotification = backgroundAfterStart
            preferences.edit().putBoolean("notification_prompted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        startConnectionNow(backgroundAfterStart)
    }

    private fun startConnectionNow(backgroundAfterStart: Boolean) {
        if (!Environment.isExternalStorageManager() || SecureRelaySettings.load(this) == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            activationMessage = "Consenti le notifiche per attivare GALLERY"
            updateState()
            return
        }
        try {
            GallerySession.setEnabled(this, true)
            GalleryDailySchedule.activate(this)
            getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE).edit()
                .putBoolean("pending", false).apply()
            activationMessage = "Connessione in avvio"
            updateState()
            if (backgroundAfterStart) moveTaskToBack(true)
        } catch (_: Exception) {
            GallerySession.setEnabled(this, false)
            activationMessage = "Impossibile programmare il collegamento"
            updateState()
        }
    }

    private fun stopConnection() {
        GallerySession.setEnabled(this, false)
        GalleryDailySchedule.cancel(this)
        getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE).edit()
            .putBoolean("pending", false).apply()
        activationMessage = "Collegamento periodico disattivato"
        updateState()
    }

    @Deprecated("Android permission result callback")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST && pendingStartAfterNotification) {
            val background = pendingBackgroundAfterNotification
            pendingStartAfterNotification = false
            pendingBackgroundAfterNotification = false
            startConnectionNow(background)
        }
    }

    private fun openPermissionSettings() {
        val target = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try { startActivity(target) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun space(value: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(value))
    }
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
    companion object {
        private const val NOTIFICATION_REQUEST = 405
        private val SKY = Color.rgb(116, 192, 238)
        private val SILVER = Color.rgb(196, 204, 217)
        private val GREEN = Color.rgb(102, 224, 163)
        private val AMBER = Color.rgb(255, 193, 119)
    }
}
