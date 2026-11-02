package it.osmus.tx

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.osmus.tx.storage.FileCatalog
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var permissionButton: Button
    private lateinit var inspectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 17, 30)
        window.navigationBarColor = Color.rgb(11, 17, 30)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    private fun hasFilesAccess(): Boolean = Environment.isExternalStorageManager()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(11, 17, 30)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(28))
        }
        scroll.addView(column)

        column.addView(label("TX", 34f, Color.WHITE, bold = true))
        column.addView(label("TRASMITTENTE  •  VERSIONE 0.1", 12f, Color.rgb(116, 192, 238)))
        column.addView(space(24))
        column.addView(label("Accesso alla memoria", 23f, Color.WHITE, bold = true))
        column.addView(space(12))
        column.addView(label(
            "Questa prima versione controlla l'accesso ai file del dispositivo, inclusi i file " +
                "nascosti accessibili. Nessun file viene ancora trasferito via Internet.",
            15f, Color.rgb(196, 204, 217)
        ))
        column.addView(space(22))

        status = label("Verifica permesso…", 16f, Color.WHITE, bold = true)
        column.addView(status)
        column.addView(space(12))
        permissionButton = Button(this).apply {
            text = "CONSENTI ACCESSO AI FILE"
            isAllCaps = false
            setOnClickListener { openPermissionSettings() }
        }
        column.addView(permissionButton)
        inspectButton = Button(this).apply {
            text = "VERIFICA CARTELLE ACCESSIBILI"
            isAllCaps = false
            setOnClickListener { inspectRoot() }
        }
        column.addView(inspectButton)
        column.addView(space(20))
        result = label("", 13f, Color.rgb(198, 210, 223)).apply {
            typeface = Typeface.MONOSPACE
        }
        column.addView(result)
        column.addView(space(28))
        column.addView(label("COLLEGAMENTO REMOTO", 12f, Color.rgb(116, 192, 238), bold = true))
        column.addView(space(8))
        column.addView(label(
            "Non configurato. La connessione cifrata alla ricevente verrà sviluppata " +
                "separatamente; non è attiva in questa versione.",
            14f, Color.rgb(196, 204, 217)
        ))
        setContentView(scroll)
    }

    private fun updatePermissionState() {
        val allowed = hasFilesAccess()
        status.text = if (allowed) "✓ Permesso ai file attivo" else "• Permesso ai file non concesso"
        status.setTextColor(if (allowed) Color.rgb(102, 224, 163) else Color.rgb(255, 193, 119))
        permissionButton.visibility = if (allowed) View.GONE else View.VISIBLE
        inspectButton.isEnabled = allowed
        if (!allowed) result.text = ""
    }

    private fun openPermissionSettings() {
        val appUri = Uri.parse("package:$packageName")
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri)
        try {
            startActivity(direct)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun inspectRoot() {
        if (!hasFilesAccess()) {
            updatePermissionState()
            return
        }
        inspectButton.isEnabled = false
        result.text = "Lettura cartelle in corso…"
        thread(name = "tx-file-catalog") {
            val message = runCatching {
                val catalog = FileCatalog(Environment.getExternalStorageDirectory())
                val page = catalog.listDirectory(limit = 100)
                buildString {
                    append("Memoria condivisa • ").append(page.total).append(" elementi nella radice\n\n")
                    page.entries.forEach { item ->
                        append(if (item.directory) "[DIR] " else "[FILE] ")
                        append(item.name)
                        if (item.hidden) append("  (nascosto)")
                        append('\n')
                    }
                    if (page.hasMore) append("\nElenco parziale (primi 100 elementi).")
                }
            }.getOrElse { exception -> "Errore di lettura: ${exception.message ?: "sconosciuto"}" }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    result.text = message
                    inspectButton.isEnabled = hasFilesAccess()
                }
            }
        }
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.START
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun space(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
