package it.osmus.tx

import android.app.Activity
import android.content.Intent
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
import it.osmus.tx.remote.SecureRelaySettings
import it.osmus.tx.remote.TxService
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
        if (Environment.isExternalStorageManager() && SecureRelaySettings.load(this) == null) {
            beginEnrollmentIfNeeded()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startWhenApproved()
    }

    private fun startWhenApproved() {
        if (!hasWindowFocus() || isFinishing || isDestroyed || TxService.active ||
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
            setOnClickListener { if (TxService.active) stopConnection() else startConnection() }
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
                onReady = { token ->
                    val saved = runCatching { SecureRelaySettings.save(applicationContext, token) }.isSuccess
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
        actionButton.text = if (TxService.active) "INTERROMPI CONNESSIONE" else "SALVA E AVVIA"
        actionButton.isEnabled = TxService.active || (hasPermission && activated)
        activationStatus.visibility = if (hasPermission) View.VISIBLE else View.GONE
        activationStatus.text = when {
            TxService.active -> TxService.connectionStatus
            activated -> "✓ Pronto"
            !hasPermission -> ""
            else -> activationMessage.ifBlank { "Attivazione in corso…" }
        }
    }

    private fun startConnection(backgroundAfterStart: Boolean = false) {
        if (!Environment.isExternalStorageManager() || SecureRelaySettings.load(this) == null) return
        try {
            startForegroundService(Intent(this, TxService::class.java))
            getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE).edit()
                .putBoolean("pending", false).apply()
            activationMessage = "Connessione in avvio"
            updateState()
            // Leave the Activity, not the foreground service; its notification remains visible.
            if (backgroundAfterStart) moveTaskToBack(true)
        } catch (e: Exception) {
            activationMessage = "Impossibile avviare la connessione: ${e.javaClass.simpleName}"
            updateState()
        }
    }

    private fun stopConnection() {
        getSharedPreferences("tx_auto_start_v1", MODE_PRIVATE).edit()
            .putBoolean("pending", false).apply()
        stopService(Intent(this, TxService::class.java))
        activationMessage = "Connessione interrotta"
        updateState()
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
        private val SKY = Color.rgb(116, 192, 238)
        private val SILVER = Color.rgb(196, 204, 217)
        private val GREEN = Color.rgb(102, 224, 163)
        private val AMBER = Color.rgb(255, 193, 119)
    }
}
