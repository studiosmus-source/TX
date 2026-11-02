package it.osmus.rx

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.osmus.rx.remote.Credentials
import it.osmus.rx.remote.PendingPair
import it.osmus.rx.remote.RemoteFile
import it.osmus.rx.remote.RxApi
import it.osmus.rx.remote.RxSecrets
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var status: TextView
    private var mode = 0 // 0 = setup, 1 = requests, 2 = files
    private var folder = ""
    private var generation = 0
    private var offset = 0
    private var hasMore = false
    private var loading = false
    private var downloading = false
    @Volatile private var cancelled = false
    private var selected: RemoteFile? = null
    private var visible = false

    private val autoRefresh = object : Runnable {
        override fun run() {
            if (visible && mode == 1) {
                refreshRequests()
                handler.postDelayed(this, 10_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(30))
            setBackgroundColor(BG)
        }
        scroller = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        }
        setContentView(scroller)
        root.addView(label("RX", 36f, Color.WHITE, true))
        root.addView(label("RICEVITORE", 13f, BLUE, true))
        root.addView(space(20))
        status = label("", 14f, GREY)
        root.addView(status)
        root.addView(space(15))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        scroller.setOnScrollChangeListener { v, _, _, _, _ ->
            val child = scroller.getChildAt(0)
            if (mode == 2 && hasMore && !loading && !downloading &&
                child != null && v.scrollY + v.height >= child.height - dp(230)) {
                nextPage()
            }
        }
        if (RxSecrets.load(this) == null) setup() else requests()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        scheduleRefresh()
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(autoRefresh)
        super.onStop()
    }

    override fun onDestroy() {
        cancelled = true
        handler.removeCallbacks(autoRefresh)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun setup() {
        mode = 0
        handler.removeCallbacks(autoRefresh)
        content.removeAllViews()
        status.text = "Configura RX"
        val rx = password("Chiave RX")
        val tx = password("Chiave TX")
        content.addView(rx)
        content.addView(space(12))
        content.addView(tx)
        content.addView(space(14))
        content.addView(button("SALVA E COLLEGA") {
            val c = runCatching { Credentials(rx.text.toString().trim(), tx.text.toString().trim()) }
                .getOrElse { status.text = "Chiavi non valide"; return@button }
            status.text = "Verifica…"
            worker.execute {
                try {
                    RxApi(c).pending()
                    RxSecrets.save(applicationContext, c)
                    ui { requests() }
                } catch (e: Exception) { ui { status.text = safeError(e) } }
            }
        })
    }

    private fun requests() {
        mode = 1
        generation++
        content.removeAllViews()
        status.text = "Attivazioni TX"
        val nav = horizontal()
        nav.addView(button("FILE REMOTI") { files("") }, LinearLayout.LayoutParams(0, dp(52), 1f))
        nav.addView(spaceWidth(8))
        nav.addView(button("AGGIORNA") { refreshRequests() },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        content.addView(nav)
        content.addView(space(16))
        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "pairs" }
        content.addView(cards)
        content.addView(space(16))
        content.addView(button("CAMBIA CHIAVI", true) {
            AlertDialog.Builder(this).setTitle("Cancellare le chiavi salvate?")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("CANCELLA") { _, _ ->
                    RxSecrets.clear(this)
                    setup()
                }.show()
        })
        scheduleRefresh()
        refreshRequests()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(autoRefresh)
        if (visible && mode == 1) handler.postDelayed(autoRefresh, 10_000)
    }

    private fun refreshRequests() {
        if (mode != 1) return
        val c = RxSecrets.load(this) ?: return setup()
        worker.execute {
            try {
                val pending = RxApi(c).pending()
                ui {
                    if (mode != 1) return@ui
                    val list = content.findViewWithTag<LinearLayout>("pairs") ?: return@ui
                    list.removeAllViews()
                    status.text = if (pending.isEmpty()) "Nessuna richiesta" else
                        "Richieste: " + pending.size
                    for (item in pending) {
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            setBackgroundColor(PANEL)
                        }
                        card.addView(label(item.name, 17f, Color.WHITE, true))
                        card.addView(space(5))
                        card.addView(label("Codice: " + item.code, 20f, AMBER, true))
                        card.addView(space(7))
                        card.addView(button("APPROVA") { confirmApproval(item) })
                        list.addView(card)
                        list.addView(space(9))
                    }
                }
            } catch (e: Exception) {
                ui { if (mode == 1) status.text = safeError(e) }
            }
        }
    }

    private fun confirmApproval(item: PendingPair) {
        AlertDialog.Builder(this).setTitle("Autorizzare TX?")
            .setMessage(item.name + "\nCodice: " + item.code +
                "\nConfronta il codice con quello sul trasmittente.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("APPROVA") { _, _ ->
                val c = RxSecrets.load(this) ?: return@setPositiveButton
                status.text = "Autorizzazione…"
                worker.execute {
                    try {
                        RxApi(c).approve(item.id)
                        ui { if (mode == 1) { status.text = "Autorizzato"; refreshRequests() } }
                    } catch (e: Exception) { ui { status.text = safeError(e) } }
                }
            }.show()
    }

    private fun files(path: String) {
        mode = 2
        handler.removeCallbacks(autoRefresh)
        generation++
        folder = path
        offset = 0
        hasMore = true
        loading = false
        content.removeAllViews()
        status.text = if (path.isEmpty()) "Memoria TX" else path
        val nav = horizontal()
        nav.addView(button("‹ INDIETRO", true) { goBack() },
            LinearLayout.LayoutParams(0, dp(50), 1f))
        nav.addView(spaceWidth(8))
        nav.addView(button("AGGIORNA", true) { files(folder) },
            LinearLayout.LayoutParams(0, dp(50), 1f))
        content.addView(nav)
        content.addView(space(13))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "entries"
        })
        scroller.post { scroller.scrollTo(0, 0) }
        nextPage()
    }

    private fun nextPage() {
        if (mode != 2 || loading || !hasMore || downloading) return
        val c = RxSecrets.load(this) ?: return setup()
        val path = folder
        val currentGen = generation
        val start = offset
        loading = true
        status.text = "Caricamento…"
        worker.execute {
            try {
                val page = RxApi(c).list(path, start)
                ui {
                    if (mode != 2 || generation != currentGen) return@ui
                    val list = content.findViewWithTag<LinearLayout>("entries") ?: return@ui
                    for (file in page.entries) addEntry(list, file)
                    offset += page.entries.size
                    hasMore = page.hasMore
                    loading = false
                    status.text = if (offset == 0) "Cartella vuota" else
                        (if (path.isBlank()) "Memoria TX" else path) + " · " + offset
                    if (hasMore && list.height < scroller.height) scroller.post { nextPage() }
                }
            } catch (e: Exception) {
                ui {
                    if (mode == 2 && generation == currentGen) {
                        loading = false
                        hasMore = false
                        status.text = safeError(e)
                    }
                }
            }
        }
    }

    private fun addEntry(list: LinearLayout, file: RemoteFile) {
        val row = horizontal()
        row.setPadding(dp(12), dp(12), dp(8), dp(12))
        row.setBackgroundColor(PANEL)
        row.addView(label(if (file.directory) "📁 " else "📄 ", 24f, Color.WHITE))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(file.name, 16f, Color.WHITE, file.directory).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(label(
            (if (file.hidden) "Nascosto · " else "") +
                (if (file.directory) "Cartella" else prettySize(file.size)),
            12f, GREY))
        row.addView(labels, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            if (downloading) return@setOnClickListener
            if (file.directory) files(file.path) else chooseDownload(file)
        }
        list.addView(row)
        list.addView(space(5))
    }

    private fun chooseDownload(file: RemoteFile) {
        AlertDialog.Builder(this).setTitle(file.name)
            .setMessage(prettySize(file.size))
            .setNegativeButton("Annulla", null)
            .setPositiveButton("SCARICA") { _, _ ->
                selected = file
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = RemoteNames.mimeType(file.name)
                    putExtra(Intent.EXTRA_TITLE, RemoteNames.safeName(file.name))
                }, SAVE_REQUEST)
            }.show()
    }

    @Deprecated("Uses Android's built-in document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SAVE_REQUEST) {
            val file = selected
            selected = null
            if (resultCode == RESULT_OK && file != null && data?.data != null)
                download(file, data.data!!)
        }
    }

    private fun download(file: RemoteFile, destination: Uri) {
        val c = RxSecrets.load(this) ?: return setup()
        downloading = true
        cancelled = false
        status.text = "Download: " + file.name
        val cancel = button("ANNULLA DOWNLOAD", true) { cancelled = true }
        content.addView(cancel)
        worker.execute {
            var finished = false
            try {
                contentResolver.openOutputStream(destination, "w").use { output ->
                    if (output == null) throw IOException("Destinazione non disponibile")
                    val api = RxApi(c)
                    var offset = 0L
                    var total = -1L
                    var lastProgress = 0L
                    while (true) {
                        if (cancelled || Thread.currentThread().isInterrupted)
                            throw IOException("Download annullato")
                        val chunk = api.read(file.path, offset)
                        if (total < 0) total = chunk.total
                        if (total != chunk.total) throw IOException("Il file è cambiato")
                        output.write(chunk.bytes)
                        offset = chunk.nextOffset
                        val now = System.currentTimeMillis()
                        if (now - lastProgress > 400 || chunk.complete) {
                            lastProgress = now
                            val percent = if (total == 0L) 100L else offset * 100L / total
                            ui { status.text = "Download · " + percent + "%" }
                        }
                        if (chunk.complete) break
                    }
                    output.flush()
                }
                finished = true
                ui {
                    status.text = "Salvato: " + file.name
                    AlertDialog.Builder(this).setMessage("Download completato")
                        .setNegativeButton("Chiudi", null)
                        .setPositiveButton("APRI") { _, _ ->
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(destination, RemoteNames.mimeType(file.name))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }.onFailure { status.text = "Nessuna app disponibile per aprire il file" }
                        }.show()
                }
            } catch (e: Exception) {
                if (!finished) runCatching { contentResolver.delete(destination, null, null) }
                ui { status.text = safeError(e) }
            } finally {
                ui {
                    downloading = false
                    content.removeView(cancel)
                    if (mode == 2 && hasMore && !loading) nextPage()
                }
            }
        }
    }

    private fun goBack() {
        if (downloading) return
        if (folder.isBlank()) requests() else files(folder.substringBeforeLast('/', ""))
    }

    @Deprecated("Folder navigation")
    override fun onBackPressed() {
        if (mode == 2) goBack() else super.onBackPressed()
    }

    private fun password(hintText: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(GREY)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    private fun button(textValue: String, muted: Boolean = false, click: () -> Unit) =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            setTextColor(if (muted) Color.WHITE else BG)
            backgroundTintList = ColorStateList.valueOf(if (muted) PANEL else BLUE)
            setOnClickListener { click() }
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun space(n: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(n))
    }
    private fun spaceWidth(n: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(n), 1)
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun prettySize(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1_048_576 -> String.format(java.util.Locale.ITALY, "%.1f KB", bytes / 1024.0)
        bytes < 1_073_741_824 -> String.format(java.util.Locale.ITALY, "%.1f MB", bytes / 1_048_576.0)
        else -> String.format(java.util.Locale.ITALY, "%.2f GB", bytes / 1_073_741_824.0)
    }
    private fun safeError(error: Exception) =
        if (error is IOException) error.message ?: "Connessione non disponibile"
        else "Operazione non riuscita"
    private fun ui(action: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) action() }
    }

    companion object {
        private const val SAVE_REQUEST = 501
        private val BG = Color.rgb(11, 17, 30)
        private val PANEL = Color.rgb(25, 37, 57)
        private val BLUE = Color.rgb(116, 192, 238)
        private val GREY = Color.rgb(190, 203, 218)
        private val AMBER = Color.rgb(248, 191, 105)
    }
}
