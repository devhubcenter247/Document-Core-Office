package com.alf06.document.reader.ui.home.document.pdf.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alf06.document.reader.R
import com.wxiwei.office.editor.ui.DialogKit
import com.alf06.document.reader.ui.home.document.savePictureToGallery
import com.reader.pdfviewer.PDFView
import com.reader.pdfviewer.model.PdfAnnotationInfo
import com.reader.pdfviewer.pdfium.PdfDocument
import com.reader.pdfviewer.pdfium.PdfFormField
import com.reader.pdfviewer.tools.PdfSource
import com.reader.pdfviewer.tools.PdfTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The PDF reader's tools beyond reading: annotating (with shapes, notes, text, signatures and
 * pictures), filling in forms, bookmarks and the outline, page colors, auto scroll, reading
 * aloud, the text view, and the tools that write a new file (OCR, compression, watermark, page
 * numbers, blacking out, password, flattening, organizing pages, pictures of the pages).
 *
 * The activity keeps the document and its saving; this class asks it through [host].
 */
internal class PdfReaderTools(
    private val activity: AppCompatActivity,
    private val pdfView: PDFView,
    private val container: ConstraintLayout,
    private val host: Host,
) {
    interface Host {
        /** The opened file and its password. */
        val file: File?
        val password: String?
        val loaded: Boolean
        /** The current pen color and width, also used by shapes and text. */
        val inkColor: Int
        val inkWidth: Float
        /** Writes the edits into the file; [done] tells if it did (false also when there was nothing to save). */
        fun saveEdits(done: (Boolean) -> Unit)
        /** The file changed on disk: open it again at the same page; [looks] false when the pages look the same. */
        fun reload(looks: Boolean = true)
        /** Edits changed: refresh undo/redo/save buttons. */
        fun editsChanged()
        /** Shows or hides the annotation toolbar (pen colors, undo, save). */
        fun showAnnotationBar(show: Boolean)
        fun jumpTo(page: Int)
    }

    private val tools: PdfTools by lazy { org.koin.java.KoinJavaComponent.get(PdfTools::class.java) }
    private val dialogs get() = DialogKit(activity)
    private val prefs = PdfReadingPrefs(activity)
    private val signatures = SignatureStore(activity)
    private val runner = PdfTaskRunner(activity)
    // without arguments the text is not formatted: "%1$d" in it stays as it is (the page numbers template)
    private fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) activity.getString(id) else activity.getString(id, *args)
    private fun toast(text: CharSequence) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    // ---- the layer over the pages ----

    val overlay = PdfOverlayView(activity, pdfView).also { v ->
        val lp = ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = pdfView.id; bottomToBottom = pdfView.id; startToStart = pdfView.id; endToEnd = pdfView.id
        }
        container.addView(v, container.indexOfChild(pdfView) + 1, lp)
    }

    private val annotate = AnnotateMode(overlay, object : AnnotateMode.Host {
        override val color get() = host.inkColor
        override val widthPt get() = host.inkWidth
        override fun askText(done: (String, Float) -> Unit) = askTextBox(done)
        override fun askNote(done: (String) -> Unit) = askNoteText("", done)
        override fun annotationTapped(info: PdfAnnotationInfo, movable: Boolean) = annotationActions(info)
        override fun changed() { host.editsChanged(); overlay.invalidate() }
        override fun message(text: CharSequence) = toast(text)
    })

    /** Annotating: the pen draws; other tools go through the overlay. */
    var annotating = false
        private set

    fun startAnnotating(tool: AnnotateMode.Tool = AnnotateMode.Tool.PEN) {
        if (!host.loaded) return
        stopModes()
        annotating = true
        host.showAnnotationBar(true)
        toolRow.visibility = View.VISIBLE
        setTool(tool)
    }

    fun stopAnnotating() {
        if (!annotating) return
        annotating = false
        annotate.clearSelection()
        pdfView.setDrawingMode(false)
        overlay.mode = null
        host.showAnnotationBar(false)
    }

    private fun setTool(tool: AnnotateMode.Tool) {
        annotate.tool = tool
        pdfView.setDrawingMode(tool == AnnotateMode.Tool.PEN)
        overlay.mode = if (tool == AnnotateMode.Tool.PEN) null else annotate
        if (tool == AnnotateMode.Tool.SELECT) toast(str(R.string.pdf_annot_select_hint))
    }

    /** [bitmap] goes where the next tap on a page is ([widthPt] wide), then can be moved. */
    private fun placeOnPage(bitmap: Bitmap, widthPt: Float) {
        if (!annotating) startAnnotating(AnnotateMode.Tool.SELECT) else setTool(AnnotateMode.Tool.SELECT)
        annotate.placePicture(bitmap, widthPt)
        toast(str(R.string.pdf_place_hint))
    }

    init {
        annotate.onTool = { styleTools() }
    }

    private val toolViews = LinkedHashMap<AnnotateMode.Tool, TextView>()

    /** The row of tools, put at the top of the annotation toolbar by [attachToolRow]. */
    val toolRow: HorizontalScrollView by lazy {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = listOf(
            AnnotateMode.Tool.PEN to R.string.pdf_annot_pen, AnnotateMode.Tool.SELECT to R.string.pdf_annot_select,
            AnnotateMode.Tool.TEXT to R.string.pdf_annot_text, AnnotateMode.Tool.NOTE to R.string.pdf_annot_note,
            AnnotateMode.Tool.RECT to R.string.pdf_annot_rect, AnnotateMode.Tool.ELLIPSE to R.string.pdf_annot_ellipse,
            AnnotateMode.Tool.LINE to R.string.pdf_annot_line, AnnotateMode.Tool.ARROW to R.string.pdf_annot_arrow,
            AnnotateMode.Tool.ERASER to R.string.pdf_annot_eraser,
        )
        for ((tool, label) in labels) {
            val chip = chip(str(label)) { setTool(tool) }
            toolViews[tool] = chip
            row.addView(chip)
        }
        row.addView(chip(str(R.string.pdf_sign)) { chooseSignature { bitmap -> placeOnPage(bitmap, SIGNATURE_WIDTH) } })
        row.addView(chip(str(R.string.pdf_annot_picture)) { pickPicture() })
        row.addView(chip(str(R.string.pdf_annot_list)) { showAnnotationList() })
        HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun styleTools() = toolViews.forEach { (t, v) -> styleChip(v, t == annotate.tool) }

    private fun chip(label: String, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        styleChip(this, false)
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, on: Boolean) {
        val accent = ContextCompat.getColor(activity, R.color.primary)
        v.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (on) accent else 0xFFF1F1F4.toInt()) }
        v.setTextColor(if (on) Color.WHITE else ContextCompat.getColor(activity, R.color.text_primary))
    }

    private fun askTextBox(done: (String, Float) -> Unit) {
        val sizes = listOf(10f, 12f, 14f, 18f, 24f, 32f)
        dialogs.show(str(R.string.pdf_annot_text)) {
            val field = input(str(R.string.pdf_annot_text_hint)).apply { isSingleLine = false; minLines = 2; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            caption(str(R.string.pdf_text_size))
            val size = choices(sizes.map { it.toInt().toString() }, 2, horizontal = true)
            positive(str(android.R.string.ok)) {
                val text = field.text.toString()
                if (text.isNotBlank()) done(text, sizes[size.picked.coerceAtLeast(0)])
            }
            negative(str(android.R.string.cancel))
        }
    }

    private fun askNoteText(value: String, done: (String) -> Unit) {
        dialogs.show(str(R.string.pdf_annot_note)) {
            val field = input(str(R.string.pdf_annot_note_hint), value).apply { isSingleLine = false; minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            positive(str(android.R.string.ok)) { done(field.text.toString()) }
            negative(str(android.R.string.cancel))
        }
    }

    /** What can be done with an annotation: read or change a note's text, delete it. */
    private fun annotationActions(info: PdfAnnotationInfo) {
        val text = pdfView.getAnnotationText(info)
        val isNote = info.subtype == SUBTYPE_TEXT
        dialogs.show(typeName(info.subtype)) {
            if (!text.isNullOrBlank()) text(text)
            if (isNote) neutral(str(R.string.pdf_edit)) {
                askNoteText(text.orEmpty()) { changed -> if (pdfView.setAnnotationText(info, changed)) host.editsChanged() }
            }
            positive(str(R.string.pdf_delete)) {
                pdfView.removeAnnotation(info) { removed -> if (removed) { annotate.clearSelection(); host.editsChanged() } }
            }
            negative(str(R.string.pdf_tool_close))
        }
    }

    private fun typeName(subtype: Int) = str(when (subtype) {
        SUBTYPE_TEXT -> R.string.pdf_annot_note
        3 -> R.string.pdf_annot_text // FreeText
        9 -> R.string.pdf_annot_highlight
        10 -> R.string.pdf_annot_underline
        12 -> R.string.pdf_annot_strike
        15 -> R.string.pdf_annot_pen // Ink
        13 -> R.string.pdf_annot_stamp
        else -> R.string.pdf_annot_other
    })

    /** Every annotation of the document: a tap goes to its page. */
    fun showAnnotationList() {
        if (!host.loaded) return
        pdfView.loadAnnotations({ it.subtype != SUBTYPE_LINK && it.subtype != SUBTYPE_WIDGET && it.subtype != SUBTYPE_POPUP }) { all ->
            if (all.isEmpty()) return@loadAnnotations toast(str(R.string.pdf_annot_none))
            dialogs.show(str(R.string.pdf_annot_list)) {
                for ((a, contents) in all) {
                    val text = contents?.replace('\n', ' ')?.take(60)
                    val label = str(R.string.pdf_page_n, a.page + 1) + " · " + typeName(a.subtype) + (text?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                    row(label, onClick = { dialog?.dismiss(); host.jumpTo(a.page) },
                        actions = listOf(DialogKit.Action("✕", str(R.string.pdf_delete)) {
                            pdfView.removeAnnotation(a) { removed -> if (removed) { host.editsChanged(); dialog?.dismiss(); showAnnotationList() } }
                        }))
                }
                negative(str(R.string.pdf_tool_close))
            }
        }
    }

    // ---- signatures and pictures ----

    /** Picks a saved signature or draws a new one (it is kept for next time). */
    fun chooseSignature(then: (Bitmap) -> Unit) {
        val saved = signatures.list()
        if (saved.isEmpty()) return drawSignature(then)
        dialogs.show(str(R.string.pdf_sign)) {
            for (f in saved) {
                val bitmap = signatures.load(f) ?: continue
                val image = android.widget.ImageView(activity).apply {
                    setImageBitmap(bitmap); adjustViewBounds = true; maxHeight = dp(70)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = GradientDrawable().apply { setStroke(dp(1), 0xFFE0E0E0.toInt()); cornerRadius = dp(6).toFloat() }
                    setOnClickListener { dialog?.dismiss(); then(bitmap) }
                    setOnLongClickListener {
                        dialog?.dismiss()
                        dialogs.confirm(str(R.string.pdf_sign), str(R.string.pdf_sign_delete), str(R.string.pdf_delete), str(android.R.string.cancel)) { signatures.delete(f) }
                        true
                    }
                }
                view(image).layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            }
            positive(str(R.string.pdf_sign_new)) { drawSignature(then) }
            negative(str(android.R.string.cancel))
        }
    }

    private fun drawSignature(then: (Bitmap) -> Unit) {
        val pad = SignaturePad(activity)
        val colors = listOf(Color.BLACK, 0xFF1E3A8A.toInt(), 0xFFB71C1C.toInt())
        dialogs.show(str(R.string.pdf_sign_new), scroll = false) {
            text(str(R.string.pdf_sign_hint))
            view(pad).layoutParams = LinearLayout.LayoutParams(-1, dp(190))
            val colorRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
            for (c in colors) colorRow.addView(View(activity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
                setOnClickListener { pad.color = c }
            })
            view(colorRow)
            keepOpenOnButtons()
            neutral(str(R.string.pdf_clear)) { pad.clear() }
            positive(str(R.string.pdf_sign_use)) {
                val bitmap = pad.signature() ?: return@positive toast(str(R.string.pdf_sign_empty))
                signatures.save(bitmap)
                dialog?.dismiss()
                then(bitmap)
            }
            negative(str(android.R.string.cancel)) { dialog?.dismiss() }
        }
    }

    private var pictureLauncher: ActivityResultLauncher<String>? = null

    private fun pickPicture() {
        pictureLauncher?.unregister()
        pictureLauncher = activity.activityResultRegistry.register("pdf-picture-" + System.nanoTime(), ActivityResultContracts.GetContent()) { uri ->
            pictureLauncher?.unregister(); pictureLauncher = null
            if (uri != null) loadPicture(uri)
        }
        pictureLauncher?.launch("image/*")
    }

    private fun loadPicture(uri: Uri) {
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE_PX) sample *= 2
                    activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                } catch (e: Exception) { null }
            } ?: return@launch toast(str(R.string.some_errors_occurred_please_try_again))
            placeOnPage(bitmap, PICTURE_WIDTH)
        }
    }

    /** Signing from the tools menu: pick the signature, then tap where it goes. */
    fun sign() {
        chooseSignature { bitmap -> placeOnPage(bitmap, SIGNATURE_WIDTH) }
    }

    // ---- a bar at the bottom for the modes (form, black out, read aloud, auto scroll) ----

    private var bar: LinearLayout? = null
    private var barClosed: (() -> Unit)? = null

    private fun showBar(title: String, onClose: () -> Unit, vararg actions: Pair<String, () -> Unit>): TextView {
        hideBar()
        val titleView = TextView(activity).apply {
            text = title; setTextColor(ContextCompat.getColor(activity, R.color.text_primary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat()
            setPadding(dp(12), dp(8), dp(6), dp(8))
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            for ((label, run) in actions) addView(TextView(activity).apply {
                text = label; setTextColor(ContextCompat.getColor(activity, R.color.primary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), dp(6), dp(10), dp(6)); gravity = Gravity.CENTER
                setOnClickListener { run() }
            })
            addView(TextView(activity).apply {
                text = "✕"; contentDescription = str(R.string.pdf_tool_close)
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { closeBar() }
            })
        }
        val lp = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID; startToStart = ConstraintLayout.LayoutParams.PARENT_ID; endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        container.addView(row, lp)
        bar = row
        barClosed = onClose
        return titleView
    }

    private fun hideBar() {
        bar?.let { container.removeView(it) }
        bar = null
        barClosed = null
    }

    /** The close button of the bar, or back: ends the mode. */
    fun closeBar(): Boolean {
        val close = barClosed ?: return false
        hideBar()
        close()
        return true
    }

    /** Ends every mode (a new one starts, or the document goes). */
    fun stopModes() {
        closeBar()
        stopAnnotating()
    }

    // ---- forms ----

    private val form = FormMode(overlay, object : FormMode.Host {
        override fun edit(field: PdfFormField) = editField(field)
        override fun changed() = host.editsChanged()
    })

    fun fillForm() {
        if (!host.loaded) return
        if (!pdfView.hasForm) return toast(str(R.string.pdf_form_none))
        stopModes()
        overlay.mode = form
        form.refresh()
        showBar(str(R.string.pdf_form_hint), { overlay.mode = null },
            str(R.string.save) to { host.saveEdits { saved -> if (saved) toast(str(R.string.saved_successfully)) } })
    }

    private fun editField(field: PdfFormField) {
        val title = field.name.ifBlank { str(R.string.pdf_form) }
        when (field.type) {
            PdfFormField.TYPE_COMBOBOX, PdfFormField.TYPE_LISTBOX -> {
                if (field.options.isEmpty()) return
                dialogs.pick(title, field.options, str(android.R.string.cancel)) { i ->
                    if (pdfView.setFormChoice(field, i)) { form.refresh(); host.editsChanged() }
                }
            }
            PdfFormField.TYPE_SIGNATURE -> chooseSignature { bitmap ->
                // the signature fills the field, keeping its proportions
                val r = field.rect
                val w = r.right - r.left; val h = r.top - r.bottom
                val scale = minOf(w / bitmap.width, h / bitmap.height)
                val bw = bitmap.width * scale; val bh = bitmap.height * scale
                val cx = (r.left + r.right) / 2; val cy = (r.top + r.bottom) / 2
                pdfView.addImage(field.page, RectF(cx - bw / 2, cy + bh / 2, cx + bw / 2, cy - bh / 2), bitmap) { name -> if (name != null) host.editsChanged() }
            }
            else -> dialogs.show(title) {
                val input = input(title, field.value).apply {
                    if (field.multiline) { isSingleLine = false; minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
                    setSelection(text.length)
                }
                positive(str(android.R.string.ok)) {
                    if (pdfView.setFormText(field, input.text.toString())) { form.refresh(); host.editsChanged() }
                    else toast(str(R.string.some_errors_occurred_please_try_again))
                }
                negative(str(android.R.string.cancel))
            }
        }
    }

    // ---- outline and bookmarks ----

    fun showOutline() {
        if (!host.loaded) return
        val flat = ArrayList<Pair<Int, PdfDocument.Bookmark>>()
        fun walk(list: List<PdfDocument.Bookmark?>, depth: Int) {
            for (b in list) { if (b == null) continue; flat += depth to b; if (depth < 6) walk(b.children, depth + 1) }
        }
        walk(pdfView.tableOfContents.orEmpty(), 0)
        if (flat.isEmpty()) return toast(str(R.string.pdf_outline_none))
        dialogs.show(str(R.string.pdf_outline)) {
            for ((depth, b) in flat) {
                row("    ".repeat(depth) + (b.title?.trim().orEmpty().ifEmpty { "—" }) + "  ·  " + (b.pageIdx + 1), bold = depth == 0,
                    onClick = { dialog?.dismiss(); host.jumpTo(b.pageIdx.toInt()) })
            }
            negative(str(R.string.pdf_tool_close))
        }
    }

    fun showBookmarks() {
        val path = host.file?.path ?: return
        val marks = prefs.bookmarks(path)
        val current = pdfView.currentPage
        dialogs.show(str(R.string.pdf_bookmarks)) {
            if (marks.none { it.page == current }) row("＋ " + str(R.string.pdf_bookmark_add, current + 1), bold = true, onClick = {
                dialog?.dismiss()
                dialogs.show(str(R.string.pdf_bookmark_add, current + 1)) {
                    val name = input(str(R.string.pdf_bookmark_name), str(R.string.pdf_page_n, current + 1))
                    positive(str(android.R.string.ok)) { prefs.setBookmarks(path, marks + PdfBookmark(current, name.text.toString().trim())); toast(str(R.string.pdf_bookmark_added)) }
                    negative(str(android.R.string.cancel))
                }
            })
            if (marks.isEmpty()) text(str(R.string.pdf_bookmarks_none))
            for (m in marks) row(str(R.string.pdf_page_n, m.page + 1) + (m.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                onClick = { dialog?.dismiss(); host.jumpTo(m.page) },
                actions = listOf(DialogKit.Action("✕", str(R.string.pdf_delete)) {
                    prefs.setBookmarks(path, marks - m); dialog?.dismiss(); showBookmarks()
                }))
            negative(str(R.string.pdf_tool_close))
        }
    }

    // ---- page colors and auto scroll ----

    fun applyTheme(theme: Int = prefs.theme) {
        pdfView.setNightMode(theme == PdfReadingPrefs.THEME_NIGHT)
        pdfView.setSepiaMode(theme == PdfReadingPrefs.THEME_SEPIA)
        pdfView.invalidate()
    }

    fun pickTheme() {
        val labels = listOf(str(R.string.pdf_theme_normal), str(R.string.pdf_theme_night), str(R.string.pdf_theme_sepia))
        dialogs.show(str(R.string.pdf_reading_colors)) {
            val c = choices(labels, prefs.theme)
            c.onChange { prefs.theme = it; applyTheme(it) }
            negative(str(R.string.pdf_tool_close))
        }
    }

    private var scrolling = false
    private var lastFrame = 0L
    private var sinceRender = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!scrolling) return
            val dt = if (lastFrame == 0L) 0L else (now - lastFrame) / 1_000_000
            lastFrame = now
            val before = pdfView.currentYOffset
            pdfView.moveRelativeTo(0f, -prefs.scrollSpeed * SCROLL_DP_PER_SECOND * activity.resources.displayMetrics.density * dt / 1000f)
            sinceRender += dt
            if (sinceRender > 250) { sinceRender = 0; pdfView.loadPageByOffset(); pdfView.loadPages() }
            if (dt > 0 && pdfView.currentYOffset == before) { stopScroll(); return }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun autoScroll() {
        if (!host.loaded) return
        stopModes()
        scrolling = true
        lastFrame = 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val title = showBar(str(R.string.pdf_auto_scroll_speed, prefs.scrollSpeed), { stopScroll() },
            "−" to { prefs.scrollSpeed = (prefs.scrollSpeed - 1).coerceAtLeast(1); updateScrollTitle() },
            "+" to { prefs.scrollSpeed = (prefs.scrollSpeed + 1).coerceAtMost(10); updateScrollTitle() })
        scrollTitle = title
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private var scrollTitle: TextView? = null
    private fun updateScrollTitle() { scrollTitle?.text = str(R.string.pdf_auto_scroll_speed, prefs.scrollSpeed) }

    private fun stopScroll() {
        if (!scrolling) return
        scrolling = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pdfView.loadPageByOffset(); pdfView.loadPages()
        if (bar != null && barClosed != null) hideBar()
    }

    // ---- reading aloud ----

    private var reader: ReadAloud? = null

    fun readAloud() {
        val file = host.file ?: return
        if (!host.loaded) return
        stopModes()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lateinit var title: TextView
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        var speed = 1
        val r = ReadAloud(activity, activity.lifecycleScope, tools, PdfSource(file, host.password), pdfView.pageCount, object : ReadAloud.Listener {
            override fun onSentence(page: Int, rects: List<RectF>) {
                pdfView.setReadingHighlight(page, rects)
                if (rects.isNotEmpty() && page != pdfView.currentPage) host.jumpTo(page)
                title.text = str(R.string.pdf_read_aloud_page, page + 1)
            }
            override fun onStateChanged(playing: Boolean) { playButton?.text = if (playing) "❚❚" else "▶" }
            override fun onFinished(error: Boolean) { if (error) toast(str(R.string.pdf_read_aloud_unavailable)) }
        })
        reader = r
        title = showBar(str(R.string.pdf_read_aloud), { stopReading() },
            "⏮" to { r.previous() },
            "❚❚" to { if (r.playing) r.pause() else r.resume() },
            "⏭" to { r.next() },
            "1×" to { speed = (speed + 1) % speeds.size; r.rate = speeds[speed]; speedButton?.text = "${speeds[speed]}×".replace(".0×", "×") })
        playButton = (bar?.getChildAt(2) as? TextView)
        speedButton = (bar?.getChildAt(4) as? TextView)
        r.start(pdfView.currentPage)
    }

    private var playButton: TextView? = null
    private var speedButton: TextView? = null

    private fun stopReading() {
        reader?.release()
        reader = null
        pdfView.setReadingHighlight(-1, emptyList())
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun openReflow() {
        val file = host.file ?: return
        activity.startActivity(PdfReflowActivity.intent(activity, file, host.password, pdfView.currentPage))
    }

    // ---- tools that write a new file ----

    /** Runs [then] once the edits are in the file (a tool reads the file, not the screen). */
    private fun withSavedEdits(then: () -> Unit) {
        if (!pdfView.hasUnsavedChanges) return then()
        dialogs.confirm(str(R.string.pdf_tools), str(R.string.pdf_save_first), str(R.string.save), str(android.R.string.cancel)) {
            host.saveEdits { saved -> if (saved) then() }
        }
    }

    private val source: PdfSource? get() = host.file?.let { PdfSource(it, host.password) }
    private val baseName: String get() = host.file?.nameWithoutExtension ?: "PDF"

    fun recognizeText() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        runner.run(str(R.string.pdf_ocr), baseName, "ocr") { output, progress ->
            val pages = tools.addTextLayer(src, output, progress)
            if (pages == 0) str(R.string.pdf_ocr_nothing) else str(R.string.pdf_ocr_done, pages)
        }
    }

    // the user agreed to OCR into this file: other scanned pages are recognized without asking
    private var ocrAgreedFor: File? = null

    /** A long press found no text: on a scanned page, offer to recognize its text into this file. */
    fun longPressed(x: Float, y: Float) {
        if (!host.loaded || annotating || pdfView.hasTextSelection()) return
        val page = pdfView.pageAt(x, y) ?: return
        if (!pdfView.isScannedPage(page)) return
        if (ocrAgreedFor != null && ocrAgreedFor == host.file) return recognizeInPlace(page)
        dialogs.confirm(str(R.string.pdf_ocr), str(R.string.pdf_ocr_scan_page), str(R.string.pdf_ocr_recognize)) {
            ocrAgreedFor = host.file
            recognizeInPlace(page)
        }
    }

    /**
     * OCR of [page] only (a whole long document takes far too long) into the opened file itself,
     * then the reader opens it again at the same page.
     */
    private fun recognizeInPlace(page: Int) {
        // writing over a protected file would drop its password: a copy instead
        if (host.password != null) return recognizeText()
        withSavedEdits {
            val src = source ?: return@withSavedEdits
            val temp = File(src.file.parentFile, ".${src.file.name}.ocr")
            runner.start(str(R.string.pdf_ocr), temp, onSuccess = { message ->
                message?.let(::toast)
                host.reload(looks = false)
            }) { output, progress ->
                val pages = tools.addTextLayer(src, output, progress, only = listOf(page))
                if (pages == 0) {
                    output.delete()
                    return@start str(R.string.pdf_ocr_scan_none)
                }
                if (!output.renameTo(src.file)) {
                    output.copyTo(src.file, overwrite = true)
                    output.delete()
                }
                str(R.string.pdf_ocr_scan_done)
            }
        }
    }

    fun compress() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        val levels = listOf(PdfTools.Compression.LOW to R.string.pdf_compress_low, PdfTools.Compression.MEDIUM to R.string.pdf_compress_medium,
            PdfTools.Compression.HIGH to R.string.pdf_compress_high)
        dialogs.show(str(R.string.pdf_compress)) {
            text(str(R.string.pdf_compress_now, PdfTaskRunner.size(src.file.length())))
            val c = choices(levels.map { str(it.second) }, 1)
            positive(str(R.string.pdf_compress)) {
                val level = levels[c.picked.coerceAtLeast(0)].first
                runner.run(str(R.string.pdf_compress), baseName, "compressed") { output, progress ->
                    val changed = tools.compress(src, output, level, progress)
                    str(R.string.pdf_compress_done, PdfTaskRunner.size(src.file.length()), PdfTaskRunner.size(output.length()), changed)
                }
            }
            negative(str(android.R.string.cancel))
        }
    }

    fun watermark() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        val colors = listOf(0xFF9E9E9E.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF000000.toInt())
        dialogs.show(str(R.string.pdf_watermark)) {
            val text = input(str(R.string.pdf_watermark_text), str(R.string.pdf_watermark_default))
            caption(str(R.string.pdf_text_size))
            val size = choices(listOf("36", "48", "64", "80"), 2, horizontal = true)
            caption(str(R.string.pdf_watermark_color))
            val color = choices(listOf(str(R.string.pdf_color_gray), str(R.string.pdf_color_red), str(R.string.pdf_color_blue), str(R.string.pdf_color_black)), 0, horizontal = true)
            caption(str(R.string.pdf_watermark_opacity))
            val opacity = choices(listOf("15%", "30%", "50%"), 1, horizontal = true)
            val diagonal = check(str(R.string.pdf_watermark_diagonal), true)
            positive(str(R.string.pdf_apply)) {
                val value = text.text.toString().trim().ifEmpty { return@positive }
                val alpha = listOf(0.15f, 0.3f, 0.5f)[opacity.picked.coerceAtLeast(0)]
                val argb = (colors[color.picked.coerceAtLeast(0)] and 0xFFFFFF) or ((alpha * 255).toInt() shl 24)
                val stamp = PdfTools.TextStamp(PdfTools.StampPosition.CENTER, listOf(36f, 48f, 64f, 80f)[size.picked.coerceAtLeast(0)], argb,
                    angle = if (diagonal.isChecked) 45f else 0f) { _, _ -> value }
                runner.run(str(R.string.pdf_watermark), baseName, "watermark") { output, progress -> tools.stamp(src, output, listOf(stamp), onProgress = progress); null }
            }
            negative(str(android.R.string.cancel))
        }
    }

    fun pageNumbers() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        val positions = listOf(PdfTools.StampPosition.BOTTOM_CENTER to R.string.pdf_pos_bottom_center, PdfTools.StampPosition.BOTTOM_RIGHT to R.string.pdf_pos_bottom_right,
            PdfTools.StampPosition.BOTTOM_LEFT to R.string.pdf_pos_bottom_left, PdfTools.StampPosition.TOP_CENTER to R.string.pdf_pos_top_center,
            PdfTools.StampPosition.TOP_RIGHT to R.string.pdf_pos_top_right)
        val formats = listOf("1", "1 / N", str(R.string.pdf_numbers_page_of))
        dialogs.show(str(R.string.pdf_page_numbers)) {
            caption(str(R.string.pdf_numbers_position))
            val pos = choices(positions.map { str(it.second) }, 0)
            caption(str(R.string.pdf_numbers_format))
            val fmt = choices(formats.map { it.replace("N", "10").replace("%1\$d", "1").replace("%2\$d", "10") }, 0)
            val skipFirst = check(str(R.string.pdf_numbers_skip_first), false)
            caption(str(R.string.pdf_header_footer))
            val header = input(str(R.string.pdf_header_text))
            val footer = input(str(R.string.pdf_footer_text))
            positive(str(R.string.pdf_apply)) {
                val format = fmt.picked.coerceAtLeast(0)
                val pageOf = str(R.string.pdf_numbers_page_of)
                val position = positions[pos.picked.coerceAtLeast(0)].first
                val stamps = ArrayList<PdfTools.TextStamp>()
                stamps += PdfTools.TextStamp(position, 10f, 0xFF333333.toInt()) { page, count ->
                    if (skipFirst.isChecked && page == 1) "" else when (format) {
                        0 -> page.toString()
                        1 -> "$page / $count"
                        else -> String.format(pageOf, page, count)
                    }
                }
                val top = header.text.toString().trim()
                val bottom = footer.text.toString().trim()
                if (top.isNotEmpty()) stamps += PdfTools.TextStamp(if (position == PdfTools.StampPosition.TOP_CENTER) PdfTools.StampPosition.TOP_LEFT else PdfTools.StampPosition.TOP_CENTER, 9f, 0xFF555555.toInt()) { _, _ -> top }
                if (bottom.isNotEmpty()) stamps += PdfTools.TextStamp(if (position == PdfTools.StampPosition.BOTTOM_CENTER) PdfTools.StampPosition.BOTTOM_LEFT else PdfTools.StampPosition.BOTTOM_CENTER, 9f, 0xFF555555.toInt()) { _, _ -> bottom }
                runner.run(str(R.string.pdf_page_numbers), baseName, "numbered") { output, progress -> tools.stamp(src, output, stamps, onProgress = progress); null }
            }
            negative(str(android.R.string.cancel))
        }
    }

    private val redact = RedactMode(overlay) { count -> redactTitle?.text = str(R.string.pdf_redact_count, count) }
    private var redactTitle: TextView? = null

    fun startRedact() = withSavedEdits {
        stopModes()
        redact.clear()
        overlay.mode = redact
        toast(str(R.string.pdf_redact_hint))
        redactTitle = showBar(str(R.string.pdf_redact_count, 0), { overlay.mode = null; redact.clear() },
            str(R.string.pdf_apply) to { applyRedact() })
    }

    private fun applyRedact() {
        val src = source ?: return
        if (redact.count == 0) return toast(str(R.string.pdf_redact_hint))
        val areas = redact.boxes.mapValues { (_, v) -> v.map { RectF(it) } }
        dialogs.confirm(str(R.string.pdf_redact), str(R.string.pdf_redact_warning), str(R.string.pdf_apply), str(android.R.string.cancel)) {
            closeBar()
            runner.run(str(R.string.pdf_redact), baseName, "redacted") { output, progress -> tools.redact(src, output, areas, progress); null }
        }
    }

    fun password() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        val protectedNow = host.password != null
        val choices = if (protectedNow) listOf(str(R.string.pdf_password_remove), str(R.string.pdf_password_change)) else listOf(str(R.string.pdf_password_set))
        dialogs.pick(str(R.string.pdf_password), choices, str(android.R.string.cancel)) { i ->
            if (protectedNow && i == 0) runner.run(str(R.string.pdf_password_remove), baseName, "unlocked") { output, _ -> tools.removePassword(src, output); null }
            else askNewPassword(src)
        }
    }

    private fun askNewPassword(src: PdfSource) {
        dialogs.show(str(R.string.pdf_password_set)) {
            fun secret(hint: String) = input(hint).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val first = secret(str(R.string.pdf_password_new))
            val again = secret(str(R.string.pdf_password_again))
            val print = check(str(R.string.pdf_password_allow_print), true)
            val copy = check(str(R.string.pdf_password_allow_copy), true)
            text(str(R.string.pdf_password_note))
            keepOpenOnButtons()
            positive(str(R.string.pdf_apply)) {
                val value = first.text.toString()
                when {
                    value.length < 4 -> toast(str(R.string.pdf_password_short))
                    value != again.text.toString() -> toast(str(R.string.pdf_password_mismatch))
                    else -> {
                        dialog?.dismiss()
                        runner.run(str(R.string.pdf_password_set), baseName, "protected") { output, _ ->
                            tools.setPassword(src, output, value, allowPrint = print.isChecked, allowCopy = copy.isChecked); null
                        }
                    }
                }
            }
            negative(str(android.R.string.cancel)) { dialog?.dismiss() }
        }
    }

    fun flatten() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        dialogs.confirm(str(R.string.pdf_flatten), str(R.string.pdf_flatten_message), str(R.string.pdf_apply), str(android.R.string.cancel)) {
            runner.run(str(R.string.pdf_flatten), baseName, "flattened") { output, _ -> tools.flatten(src, output); null }
        }
    }

    fun toWord() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        dialogs.confirm(str(R.string.pdf_to_word), str(R.string.pdf_to_word_message), str(R.string.pdf_to_word_convert), str(android.R.string.cancel)) {
            runner.run(str(R.string.pdf_to_word), baseName, "word", extension = "docx") { output, progress ->
                str(R.string.pdf_to_word_done, tools.toWord(src, output, progress))
            }
        }
    }

    /** Each page as a picture in Pictures/<name of the document>. */
    fun exportImages() = withSavedEdits {
        val src = source ?: return@withSavedEdits
        val count = pdfView.pageCount
        val widths = listOf(1080, 1600, 2400)
        dialogs.show(str(R.string.export_images)) {
            caption(str(R.string.pdf_images_quality))
            val quality = choices(listOf(str(R.string.pdf_images_normal), str(R.string.pdf_images_high), str(R.string.pdf_images_best)), 1)
            caption(str(R.string.pdf_images_format))
            val format = choices(listOf("PNG", "JPG"), 1, horizontal = true)
            val current = check(str(R.string.pdf_images_current_only, pdfView.currentPage + 1), false)
            positive(str(R.string.pdf_apply)) {
                val width = widths[quality.picked.coerceAtLeast(0)]
                val png = format.picked == 0
                val pages = if (current.isChecked) listOf(pdfView.currentPage) else (0 until count).toList()
                exportPages(src, pages, width, png)
            }
            negative(str(android.R.string.cancel))
        }
    }

    private fun exportPages(src: PdfSource, pages: List<Int>, width: Int, png: Boolean) {
        var status: TextView? = null
        lateinit var job: kotlinx.coroutines.Job
        val progress = dialogs.show(str(R.string.export_images), cancelable = false) {
            status = text("0/${pages.size}")
            keepOpenOnButtons()
            negative(str(android.R.string.cancel)) { job.cancel(); dialog?.dismiss() }
        }
        job = activity.lifecycleScope.launch {
            var saved = 0
            var done = 0
            val folder = baseName
            try {
                tools.renderPages(src, pages, width) { index, bitmap ->
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out); out.toByteArray()
                    }
                    if (activity.savePictureToGallery(folder, "$folder ${index + 1}.${if (png) "png" else "jpg"}", bytes, if (png) "image/png" else "image/jpeg")) saved++
                    done++
                    withContext(Dispatchers.Main) { status?.text = "$done/${pages.size}" }
                }
                progress.dismiss()
                Toast.makeText(activity, str(R.string.pdf_images_saved, saved, "Pictures/$folder"), Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(activity, str(R.string.pdf_tool_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- the menu ----

    fun showMenu(onOrganize: () -> Unit) {
        if (!host.loaded) return
        dialogs.show(str(R.string.pdf_tools)) {
            fun item(label: Int, run: () -> Unit) = row(str(label), onClick = { dialog?.dismiss(); run() })
            caption(str(R.string.pdf_group_read))
            item(R.string.pdf_outline) { showOutline() }
            item(R.string.pdf_bookmarks) { showBookmarks() }
            item(R.string.pdf_reading_colors) { pickTheme() }
            item(R.string.pdf_auto_scroll) { autoScroll() }
            item(R.string.pdf_read_aloud) { readAloud() }
            item(R.string.pdf_reflow) { openReflow() }
            caption(str(R.string.pdf_group_annotate))
            item(R.string.pdf_annotate) { startAnnotating(AnnotateMode.Tool.PEN) }
            item(R.string.pdf_sign) { sign() }
            item(R.string.pdf_form) { fillForm() }
            item(R.string.pdf_annot_list) { showAnnotationList() }
            caption(str(R.string.pdf_group_pages))
            item(R.string.pdf_organize) { withSavedEdits(onOrganize) }
            item(R.string.export_images) { exportImages() }
            caption(str(R.string.pdf_group_file))
            item(R.string.pdf_to_word) { toWord() }
            item(R.string.pdf_ocr) { recognizeText() }
            item(R.string.pdf_compress) { compress() }
            item(R.string.pdf_watermark) { watermark() }
            item(R.string.pdf_page_numbers) { pageNumbers() }
            item(R.string.pdf_redact) { startRedact() }
            item(R.string.pdf_password) { password() }
            item(R.string.pdf_flatten) { flatten() }
            negative(str(R.string.pdf_tool_close))
        }
    }

    fun release() {
        stopScroll()
        stopReading()
        overlay.mode = null
        pictureLauncher?.unregister()
    }

    companion object {
        private const val SUBTYPE_TEXT = 1
        private const val SUBTYPE_LINK = 2
        private const val SUBTYPE_POPUP = 16
        private const val SUBTYPE_WIDGET = 20
        private const val SIGNATURE_WIDTH = 150f
        private const val PICTURE_WIDTH = 220f
        private const val MAX_PICTURE_PX = 2000
        private const val SCROLL_DP_PER_SECOND = 12f
    }
}
