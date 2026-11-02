package it.osmus.tx

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.osmus.tx.remote.RelayConfig
import it.osmus.tx.remote.RelaySettings
import it.osmus.tx.remote.TxService
import it.osmus.tx.storage.FileCatalog
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var permissionButton: Button
    private lateinit var inspectButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 17, 30)
        window.navigationBarColor = Color.rgb(11, 17, 30)
        buildScreen()
    }
    override fun onResume() { super.onResume(); updateState() }

    private fun buildScreen() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 17, 30))
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        ScrollView(this).apply { addView(column); setContentView(this) }
        column.addView(text("TX", 34f, Color.WHITE, true))
        column.addView(text("TRASMITTENTE  •  V0.2", 12f, SKY, true))
        column.addView(space(20))
        column.addView(text("Gestione della memoria", 21f, Color.WHITE, true))
        column.addView(text("Con il tuo permesso, TX legge la memoria condivisa accessibile, inclusi i file nascosti. Quando avvii il collegamento, questi file possono essere consultati dal dispositivo ricevente autorizzato.", 14f, SILVER))
        column.addView(space(12))
        status = text("Verifica…", 15f, SILVER, true)
        column.addView(status)
        permissionButton = Button(this).apply { text = "CONSENTI ACCESSO AI FILE"; isAllCaps = false; setOnClickListener { openPermissionSettings() } }
        column.addView(permissionButton)
        inspectButton = Button(this).apply { text = "CONTROLLA CARTELLE"; isAllCaps = false; setOnClickListener { inspectRoot() } }
        column.addView(inspectButton)
        column.addView(space(18))
        column.addView(text("Collegamento HTTPS", 21f, Color.WHITE, true))
        column.addView(text("I telefoni possono stare su reti diverse. Serve un relay HTTPS configurato con due chiavi separate; nessun archivio viene sincronizzato preventivamente.", 14f, SILVER))
        column.addView(space(10))
        urlField = EditText(this).apply {
            hint = "https://tuo-server.example"
            setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(GREY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        column.addView(urlField)
        tokenField = EditText(this).apply {
            hint = "Chiave TX (almeno 32 caratteri)"
            setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(GREY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column.addView(tokenField)
        RelaySettings.load(this)?.let { config -> urlField.setText(config.baseUrl); tokenField.setText(config.txToken) }
        startButton = Button(this).apply { text = "SALVA E AVVIA"; isAllCaps = false; setOnClickListener { start() } }
        stopButton = Button(this).apply { text = "INTERROMPI CONNESSIONE"; isAllCaps = false; setOnClickListener { stop() } }
        column.addView(startButton)
        column.addView(stopButton)
        column.addView(Button(this).apply { text = "AGGIORNA STATO"; isAllCaps = false; setOnClickListener { updateState() } })
        column.addView(space(12))
        info = text("", 13f, SILVER)
        column.addView(info)
        column.addView(space(16))
        column.addView(text("Nota Android: i servizi di trasferimento in background possono essere sospesi dal sistema dopo 6 ore complessive in 24 ore su Android 15/16.", 12f, GREY))
    }

    private fun updateState() {
        val allowed = Environment.isExternalStorageManager()
        status.text = if (allowed) "✓ Accesso ai file consentito" else "• Accesso ai file non consentito"
        status.setTextColor(if (allowed) Color.rgb(102, 224, 163) else Color.rgb(255, 193, 119))
        permissionButton.visibility = if (allowed) View.GONE else View.VISIBLE
        inspectButton.isEnabled = allowed
        startButton.isEnabled = allowed && !TxService.active
        stopButton.isEnabled = TxService.active
        if (TxService.active) info.text = TxService.connectionStatus + " • notifica Android attiva"
        else if (info.text.startsWith("Relay HTTPS") || info.text.startsWith("Connessione non disponibile")) info.text = "Connessione ferma"
    }

    private fun start() {
        if (!Environment.isExternalStorageManager()) return
        try {
            val config = RelayConfig(urlField.text.toString().trim(), tokenField.text.toString())
            RelaySettings.save(this, config)
            startForegroundService(Intent(this, TxService::class.java))
            info.text = "Avvio richiesto. Controlla lo stato dalla notifica Android."
        } catch (e: Exception) { info.text = "Impossibile avviare: ${e.message}" }
        updateState()
    }
    private fun stop() {
        stopService(Intent(this, TxService::class.java))
        info.text = "Connessione interrotta"
        updateState()
    }
    private fun openPermissionSettings() {
        val target = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try { startActivity(target) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
    private fun inspectRoot() {
        if (!Environment.isExternalStorageManager()) return
        inspectButton.isEnabled = false
        info.text = "Lettura in corso…"
        thread(name = "tx-local-check") {
            val message = runCatching {
                val page = FileCatalog(Environment.getExternalStorageDirectory()).listDirectory(limit = 100)
                buildString {
                    append(page.total).append(" elementi nella radice della memoria condivisa\n")
                    page.entries.forEach { append(if (it.directory) "📁 " else "📄 ").append(it.name).append(if (it.hidden) " (nascosto)" else "").append('\n') }
                    if (page.hasMore) append("… Altri elementi disponibili")
                }
            }.getOrElse { "Errore: ${it.message}" }
            runOnUiThread { if (!isDestroyed && !isFinishing) { info.text = message; updateState() } }
        }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun space(dp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, this@MainActivity.dp(dp)) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    companion object {
        private val SKY = Color.rgb(116, 192, 238)
        private val SILVER = Color.rgb(196, 204, 217)
        private val GREY = Color.rgb(152, 164, 182)
    }
}
