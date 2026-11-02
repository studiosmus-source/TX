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
        ScrollView(this).apply { addView(column); setContentView(this) }
        column.addView(text("TX", 36f, Color.WHITE, true))
        column.addView(text("TRASMITTENTE  •  V0.3", 12f, SKY, true))
        column.addView(space(24))
        column.addView(text("Accesso ai file", 23f, Color.WHITE, true))
        column.addView(space(8))
        column.addView(text("TX permette al dispositivo RX autorizzato di consultare da remoto i file accessibili, inclusi quelli nascosti.", 15f, SILVER))
        column.addView(space(16))
        permissionStatus = text("", 15f, SILVER, true)
        column.addView(permissionStatus)
        permissionButton = Button(this).apply {
            text = "CONSENTI ACCESSO AI FILE"
            isAllCaps = false
            setOnClickListener { openPermissionSettings() }
        }
        column.addView(permissionButton)
        column.addView(space(24))
        column.addView(text("Attivazione remota", 23f, Color.WHITE, true))
        column.addView(space(8))
        column.addView(text("Dopo il permesso, TX invia una richiesta di attivazione. La chiave viene inserita e approvata dal tuo dispositivo RX, senza digitarla qui.", 14f, SILVER))
        column.addView(space(16))
        activationStatus = text("", 15f, SKY)
        column.addView(activationStatus)
        column.addView(space(20))
        actionButton = Button(this).apply {
            text = "SALVA E AVVIA"
            isAllCaps = false
            setOnClickListener { if (TxService.active) stopConnection() else startConnection() }
        }
        column.addView(actionButton)
        column.addView(space(20))
        column.addView(text("La condivisione è segnalata da Android e può essere interrotta. Android limita i servizi di trasferimento in background.", 12f, GREY))
    }

    private fun beginEnrollmentIfNeeded() {
        if (enrollmentThread?.isAlive == true || isFinishing || isDestroyed) return
        val name = (Build.MANUFACTURER + " " + Build.MODEL).trim().take(60).ifEmpty { "Telefono TX" }
        val client = EnrollmentClient(name)
        enrollment = client
        activationMessage = "Invio richiesta di attivazione…"
        updateState()
        enrollmentThread = thread(name = "tx-enrollment") {
            client.run(
                onCode = { code -> showMessage("Richiesta inviata · codice: $code\nIn attesa della tua approvazione da RX…") },
                onReady = { token ->
                    val saved = runCatching { SecureRelaySettings.save(applicationContext, token) }.isSuccess
                    showMessage(if (saved) "✓ Attivazione ricevuta. Premi SALVA E AVVIA." else "Salvataggio sicuro non riuscito.")
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
        permissionStatus.text = if (hasPermission) "✓ Accesso ai file consentito" else "• Permesso Android necessario"
        permissionStatus.setTextColor(if (hasPermission) GREEN else AMBER)
        permissionButton.visibility = if (hasPermission) View.GONE else View.VISIBLE
        val activated = SecureRelaySettings.load(this) != null
        actionButton.text = if (TxService.active) "INTERROMPI CONNESSIONE" else "SALVA E AVVIA"
        actionButton.isEnabled = TxService.active || (hasPermission && activated)
        activationStatus.text = when {
            TxService.active -> TxService.connectionStatus
            activated -> "✓ Attivazione completata. Premi SALVA E AVVIA."
            !hasPermission -> "In attesa del permesso per iniziare l'attivazione."
            else -> activationMessage.ifBlank { "Richiesta in preparazione…" }
        }
    }

    private fun startConnection() {
        if (!Environment.isExternalStorageManager() || SecureRelaySettings.load(this) == null) return
        try {
            startForegroundService(Intent(this, TxService::class.java))
            activationMessage = "Avvio richiesto…"
            updateState()
        } catch (e: Exception) {
            activationMessage = "Impossibile avviare la connessione: ${e.javaClass.simpleName}"
            updateState()
        }
    }

    private fun stopConnection() {
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
        private val GREY = Color.rgb(152, 164, 182)
        private val GREEN = Color.rgb(102, 224, 163)
        private val AMBER = Color.rgb(255, 193, 119)
    }
}
