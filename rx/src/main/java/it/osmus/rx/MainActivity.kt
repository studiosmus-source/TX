package it.osmus.rx

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
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
    // Keep pairing responsive even when a gallery request is waiting on TX.
    private val pairWorker = Executors.newSingleThreadExecutor()
    private val ioWorker = Executors.newSingleThreadExecutor()
    private val thumbs = Executors.newFixedThreadPool(2)
    private val previewWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var status: TextView
    private var mode = 0 // 0 = setup, 1 = requests, 2 = files, 3 = gallery
    private var folder = ""
    private var galleryOffset = 0
    private var generation = 0
    private var offset = 0
    private var hasMore = false
    private var loading = false
    private var downloading = false
    @Volatile private var cancelled = false
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
            if ((mode == 2 || mode == 3) && hasMore && !loading && !downloading &&
                child != null && v.scrollY + v.height >= child.height - dp(230)) {
                if (mode == 3) nextGalleryPage() else nextPage()
            }
        }
        if (RxSecrets.load(this) == null) setup()
        else if (getSharedPreferences("rx_pair_state_v1", MODE_PRIVATE)
                .getBoolean("approved", false)) gallery()
        else requests()
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
        pairWorker.shutdownNow()
        ioWorker.shutdownNow()
        thumbs.shutdownNow()
        previewWorker.shutdownNow()
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
            pairWorker.execute {
                try {
                    RxApi(c).pending()
                    RxSecrets.save(applicationContext, c)
                    getSharedPreferences("rx_pair_state_v1", MODE_PRIVATE).edit()
                        .putBoolean("approved", false).apply()
                    ui { requests() }
                } catch (e: Exception) { ui { status.text = safeError(e) } }
            }
        })
    }

    private fun requests() {
        mode = 1
        generation++
        content.removeAllViews()
        status.text = "Attivazioni"
        val nav = horizontal()
        nav.addView(button("GALLERIA") { gallery() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        nav.addView(spaceWidth(8))
        nav.addView(button("FILE") { files("") },
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
                    getSharedPreferences("rx_pair_state_v1", MODE_PRIVATE).edit()
                        .remove("approved").apply()
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
        pairWorker.execute {
            try {
                val pending = RxApi(c).pending()
                ui {
                    if (mode != 1) return@ui
                    val list = content.findViewWithTag<LinearLayout>("pairs") ?: return@ui
                    list.removeAllViews()
                    status.text = when {
                        pending.isNotEmpty() -> "Richieste: " + pending.size
                        getSharedPreferences("rx_pair_state_v1", MODE_PRIVATE)
                            .getBoolean("approved", false) -> "✓ Approvazione inviata"
                        else -> "In attesa della richiesta GALLERY"
                    }
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
                pairWorker.execute {
                    try {
                        RxApi(c).approve(item.id)
                        getSharedPreferences("rx_pair_state_v1", MODE_PRIVATE).edit()
                            .putBoolean("approved", true).apply()
                        ui { if (mode == 1) { status.text = "✓ Approvazione inviata"; refreshRequests() } }
                    } catch (e: Exception) { ui { status.text = safeError(e) } }
                }
            }.show()
    }


    /** Primary RX home: a 3-column paged gallery sourced solely from TX. */
    private fun gallery() {
        mode = 3
        generation++
        galleryOffset = 0
        hasMore = true
        loading = false
        handler.removeCallbacks(autoRefresh)
        content.removeAllViews()
        status.text = "Galleria TX"
        val nav = horizontal()
        nav.addView(button("ATTIVAZIONI", true) { requests() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(spaceWidth(8))
        nav.addView(button("FILE", true) { files("") },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(nav)
        content.addView(space(16))
        val grid = GridLayout(this).apply {
            columnCount = 3
            tag = "galleryGrid"
        }
        content.addView(grid)
        scroller.post { scroller.scrollTo(0, 0) }
        nextGalleryPage()
    }

    private fun nextGalleryPage() {
        if (mode != 3 || loading || !hasMore || downloading) return
        val credentials = RxSecrets.load(this) ?: return setup()
        val currentGen = generation
        val start = galleryOffset
        loading = true
        status.text = "Galleria · Caricamento…"
        ioWorker.execute {
            try {
                val page = RxApi(credentials).gallery(start)
                ui {
                    if (mode != 3 || currentGen != generation) return@ui
                    val grid = content.findViewWithTag<GridLayout>("galleryGrid") ?: return@ui
                    page.entries.forEach { addGalleryTile(grid, it, credentials, currentGen) }
                    galleryOffset = page.nextOffset
                    hasMore = page.hasMore && page.nextOffset > start
                    loading = false
                    status.text = if (grid.childCount == 0 && !hasMore) "Nessuna foto o video" else
                        "Galleria TX"
                    if (hasMore && grid.height < scroller.height)
                        scroller.post { nextGalleryPage() }
                }
            } catch (e: Exception) {
                ui {
                    if (mode == 3 && currentGen == generation) {
                        loading = false
                        hasMore = false
                        status.text = safeError(e)
                    }
                }
            }
        }
    }

    private fun addGalleryTile(
        grid: GridLayout,
        entry: RxApi.GalleryItem,
        credentials: Credentials,
        currentGen: Int
    ) {
        val side = (resources.displayMetrics.widthPixels - dp(58)) / 3
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
        }
        val photo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(PANEL)
            setImageResource(android.R.drawable.ic_menu_gallery)
            layoutParams = LinearLayout.LayoutParams(side, side)
        }
        tile.addView(photo)
        val marker = label(if (entry.type == "video") "▶ VIDEO" else "", 10f, GREY)
        marker.setPadding(dp(3), 0, 0, 0)
        tile.addView(marker)
        tile.setOnClickListener { if (!downloading) showGalleryPreview(entry, credentials) }
        grid.addView(tile, GridLayout.LayoutParams().apply {
            width = side
            height = side + dp(17)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        })
        thumbs.execute {
            val result = runCatching {
                val bytes = RxApi(credentials).thumbnail(entry.path)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (result != null) ui {
                if (mode == 3 && currentGen == generation && tile.parent != null)
                    photo.setImageBitmap(result)
                else result.recycle()
            }
        }
    }

    /** Full-screen overlay: closing preserves gallery scroll and loaded entries. */
    private fun showGalleryPreview(item: RxApi.GalleryItem, credentials: Credentials) {
        if (downloading) return
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(20), dp(14), dp(20))
            setBackgroundColor(BG)
        }
        val header = horizontal()
        header.addView(label(item.name, 16f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("✕", true) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(60), dp(52)))
        shell.addView(header)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setImageResource(android.R.drawable.ic_menu_gallery)
            contentDescription = "Anteprima di " + item.name
        }
        shell.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val message = label("Caricamento anteprima…", 14f, GREY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        shell.addView(message)
        lateinit var save: Button
        val remote = RemoteFile(item.path, item.name, false, false,
            item.size, item.modified)
        save = button("SALVA") { if (!downloading) download(remote, message, save) }
        shell.addView(save, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        dialog.setContentView(shell)
        var displayed: Bitmap? = null
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            displayed?.recycle()
            displayed = null
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT)
        previewWorker.execute {
            val result = runCatching {
                val bytes = RxApi(credentials).preview(item.path)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Immagine non decodificabile")
            }.recoverCatching {
                val bytes = RxApi(credentials).thumbnail(item.path)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Anteprima non decodificabile")
            }
            ui {
                if (dialog.isShowing) {
                    result.onSuccess { bitmap ->
                        displayed?.recycle()
                        displayed = bitmap
                        image.setImageBitmap(bitmap)
                        message.text = if (item.type == "video") "Anteprima video" else ""
                    }.onFailure { error ->
                        message.text = "Anteprima non disponibile: " +
                            (error.message ?: "errore sconosciuto") +
                            ". Puoi comunque premere SALVA."
                    }
                } else result.getOrNull()?.recycle()
            }
        }
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
        nav.addView(button("GALLERIA", true) { gallery() },
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
        ioWorker.execute {
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
            if (file.directory) {
                files(file.path)
            } else {
                val mime = RemoteNames.mimeType(file.name)
                val credentials = RxSecrets.load(this)
                if (credentials != null &&
                    (mime.startsWith("image/") || mime.startsWith("video/"))) {
                    showGalleryPreview(RxApi.GalleryItem(file.path, file.name,
                        if (mime.startsWith("video/")) "video" else "image",
                        file.size, file.modified), credentials)
                } else chooseDownload(file)
            }
        }
        list.addView(row)
        list.addView(space(5))
    }

    private fun chooseDownload(file: RemoteFile) {
        AlertDialog.Builder(this).setTitle(file.name)
            .setMessage("Salvare " + prettySize(file.size) + " sul telefono RX?")
            .setNegativeButton("ANNULLA", null)
            .setPositiveButton("SALVA") { _, _ -> download(file) }
            .show()
    }

    /**
     * Download the original file, not the JPEG preview. Read a verified TX block
     * before creating any MediaStore item (avoids broken empty photo placeholders).
     * The file stays pending until its byte checksum AND image decoder both pass.
     */
    private fun download(file: RemoteFile, previewMessage: TextView? = null,
                         saveButton: Button? = null) {
        if (downloading) return
        val credentials = RxSecrets.load(this) ?: return setup()
        downloading = true
        cancelled = false
        saveButton?.isEnabled = false
        status.text = "Download: " + file.name
        previewMessage?.text = "Connessione al file originale…"
        val cancel = button("ANNULLA DOWNLOAD", true) { cancelled = true }
        content.addView(cancel)
        val store = RxMediaStore(applicationContext.contentResolver)
        ioWorker.execute {
            var destination: RxMediaStore.Destination? = null
            var published = false
            try {
                val api = RxApi(credentials)
                // If the relay is busy or TX is unreachable, nothing is saved yet.
                var chunk = api.read(file.path, 0L)
                if (cancelled || Thread.currentThread().isInterrupted)
                    throw IOException("Download annullato")
                val sourceBytes = chunk.total
                val actualName = if (RemoteNames.mimeType(file.name).startsWith("image/")) {
                    ImageSignature.saveName(file.name, chunk.bytes)
                } else file.name
                // Renaming the extension does not change the image data.
                destination = store.create(actualName)
                val target = destination
                val writtenHash = java.security.MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgress = 0L
                val downloadStartedMs = android.os.SystemClock.elapsedRealtime()
                contentResolver.openOutputStream(target.uri, "wt").use { output ->
                    if (output == null) throw IOException("Destinazione non scrivibile")
                    while (true) {
                        if (cancelled || Thread.currentThread().isInterrupted)
                            throw IOException("Download annullato")
                        if (chunk.total != sourceBytes)
                            throw IOException("Il file originale è cambiato durante il download")
                        if (chunk.nextOffset != received + chunk.bytes.size)
                            throw IOException("Sequenza blocchi non valida")
                        output.write(chunk.bytes)
                        writtenHash.update(chunk.bytes)
                        received = chunk.nextOffset
                        val now = System.currentTimeMillis()
                        if (now - lastProgress > 400 || chunk.complete) {
                            lastProgress = now
                            val pct = if (sourceBytes == 0L) 100L else received * 100L / sourceBytes
                            val elapsedMs = (android.os.SystemClock.elapsedRealtime() -
                                downloadStartedMs).coerceAtLeast(1L)
                            val transferSpeed = received * 1000L / elapsedMs
                            val progress = "Download · " + pct + "% · " +
                                prettySize(transferSpeed) + "/s"
                            ui {
                                status.text = progress
                                previewMessage?.text = progress
                            }
                        }
                        if (chunk.complete) {
                            if (received != sourceBytes) throw IOException("File incompleto")
                            break
                        }
                        // The next block is requested only after the previous one
                        // has been written; retries never append duplicate bytes.
                        chunk = api.read(file.path, received)
                    }
                    output.flush()
                }
                if (cancelled || Thread.currentThread().isInterrupted)
                    throw IOException("Download annullato")
                ui {
                    status.text = "Verifica integrità…"
                    previewMessage?.text = "Verifica integrità…"
                }
                val saved = contentResolver.openInputStream(target.uri)
                    ?: throw IOException("File salvato non leggibile")
                TransferIntegrity.verifySaved(saved, sourceBytes, writtenHash.digest())
                if (RemoteNames.mimeType(actualName).startsWith("image/")) {
                    // Do not publish an image just because its byte count matched.
                    PhotoVerifier.verifySavedImage(contentResolver, target.uri)
                }
                store.publish(target)
                published = true
                ui {
                    status.text = "Salvato e verificato: " + actualName
                    previewMessage?.text = "Salvato: " + target.relativePath + actualName
                    AlertDialog.Builder(this)
                        .setTitle("File originale salvato")
                        .setMessage(actualName + "\n" + prettySize(sourceBytes) +
                            "\n\nCartella: " + target.relativePath)
                        .setNegativeButton("CHIUDI", null)
                        .setPositiveButton("APRI") { _, _ ->
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(target.uri, RemoteNames.mimeType(actualName))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }.onFailure {
                                status.text = "File salvato, ma nessuna app lo può aprire"
                            }
                        }.show()
                }
            } catch (error: Exception) {
                // Avoid leaving a broken/empty photo visible in the gallery.
                if (!published) destination?.let { runCatching { store.discard(it) } }
                ui {
                    val detail = error.message ?: "errore sconosciuto"
                    status.text = "Download non riuscito: " + detail
                    previewMessage?.text = "Non salvato: " + detail
                    AlertDialog.Builder(this)
                        .setTitle("File non salvato")
                        .setMessage(detail + "\n\nNon è stato pubblicato un file incompleto.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                ui {
                    downloading = false
                    saveButton?.isEnabled = true
                    content.removeView(cancel)
                    if ((mode == 2 || mode == 3) && hasMore && !loading) {
                        if (mode == 3) nextGalleryPage() else nextPage()
                    }
                }
            }
        }
    }

    private fun goBack() {
        if (downloading) return
        if (folder.isBlank()) gallery() else files(folder.substringBeforeLast('/', ""))
    }

    @Deprecated("Folder navigation")
    override fun onBackPressed() {
        if (mode == 2) goBack() else if (mode == 1) gallery() else super.onBackPressed()
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
        private val BG = Color.rgb(11, 17, 30)
        private val PANEL = Color.rgb(25, 37, 57)
        private val BLUE = Color.rgb(116, 192, 238)
        private val GREY = Color.rgb(190, 203, 218)
        private val AMBER = Color.rgb(248, 191, 105)
    }
}
