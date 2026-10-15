package com.alf06.document.reader.edit

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alf06.document.reader.R
import com.alf06.document.reader.model.DocumentType
import com.alf06.document.reader.model.RecentDocument
import com.alf06.document.reader.ui.home.document.office.ReadDocumentActivity
import com.alf06.document.reader.ui.home.document.office.sheetPages
import com.alf06.document.reader.ui.home.document.office.writeSheetPdf
import com.alf06.document.reader.ui.home.document.office.writeWordPdf
import com.alf06.document.reader.ui.home.document.pdf.ReadPdfActivity
import com.alf06.document.reader.ui.home.document.pdf.tools.PdfOrganizeActivity
import com.alf06.document.reader.ui.home.document.pdf.tools.PdfOverlayView
import com.alf06.document.reader.ui.home.document.pdf.tools.PdfReadingPrefs
import com.alf06.document.reader.ui.home.document.pdf.tools.PdfReflowActivity
import com.reader.pdfviewer.PDFView
import com.reader.pdfviewer.pdfium.PdfiumCore
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.reader.ReaderState
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The PDF reader's tools used through the screen: menu, shapes drawn and moved by touch, notes, bookmarks, colors, auto scroll, organizer, text view, Office to PDF. */
@RunWith(AndroidJUnit4::class)
class PdfReaderToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    /** Strings as the screen shows them: the app may use another language than the device. */
    private var ui: android.content.Context? = null
    private fun s(id: Int, vararg args: Any) = (ui ?: context).getString(id, *args)
    private val shots = File(context.getExternalFilesDir(null), "pdf-tools-ui").apply { mkdirs() }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { b -> File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }

    private fun <T : View> find(root: View, match: (View) -> Boolean): T? {
        if (match(root)) @Suppress("UNCHECKED_CAST") return root as T
        if (root is ViewGroup) for (i in 0 until root.childCount) find<T>(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun pdf(name: String, pages: Int): File {
        val out = File(context.filesDir, name).also { it.delete() }
        val doc = android.graphics.pdf.PdfDocument()
        val paint = Paint().apply { textSize = 22f }
        for (i in 0 until pages) {
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            for (l in 0 until 12) page.canvas.drawText("Page ${i + 1}. Line ${l + 1} of a paragraph that reads well.", 50f, 90f + l * 40f, paint)
            doc.finishPage(page)
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun launchReader(file: File, block: (ActivityScenario<ReadPdfActivity>, PDFView) -> Unit) {
        context.getSharedPreferences("pdf_reading", 0).edit().clear().commit()
        val intent = Intent(context, ReadPdfActivity::class.java)
            .putExtra(ReadPdfActivity.ARG_DOCUMENT, RecentDocument(path = file.absolutePath, size = file.length(), type = DocumentType.Pdf))
        ActivityScenario.launch<ReadPdfActivity>(intent).use { scenario ->
            lateinit var view: PDFView
            scenario.onActivity { view = it.findViewById(R.id.pdfRead); ui = it }
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end) {
                var ready = false
                scenario.onActivity { ready = view.pageCount > 0 && view.pageViewRect(0) != null }
                if (ready) break
                Thread.sleep(200)
            }
            Thread.sleep(1000)
            block(scenario, view)
        }
    }

    private fun menu(scenario: ActivityScenario<ReadPdfActivity>, item: Int) {
        scenario.onActivity { it.findViewById<View>(R.id.icPdfTools).performClick() }
        Thread.sleep(500)
        // the lower items of the menu are below the fold
        onView(withText(s(item))).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        Thread.sleep(500)
    }

    /** A finger on the overlay from (x1, y1) to (x2, y2), in its own coordinates. */
    private fun drag(scenario: ActivityScenario<ReadPdfActivity>, x1: Float, y1: Float, x2: Float, y2: Float, steps: Int = 8) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float, t: Long) = scenario.onActivity { a ->
            val overlay = find<PdfOverlayView>(a.window.decorView) { it is PdfOverlayView }!!
            val loc = IntArray(2); overlay.getLocationInWindow(loc)
            val e = MotionEvent.obtain(down, t, action, x + loc[0], y + loc[1], 0)
            a.window.decorView.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x1, y1, down)
        for (i in 1..steps) send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, down + i * 16L)
        send(MotionEvent.ACTION_UP, x2, y2, down + steps * 16L + 16)
        Thread.sleep(300)
    }

    private fun tap(scenario: ActivityScenario<ReadPdfActivity>, x: Float, y: Float) = drag(scenario, x, y, x, y, steps = 0)

    private fun chip(scenario: ActivityScenario<ReadPdfActivity>, label: Int) = scenario.onActivity { a ->
        find<TextView>(a.findViewById(R.id.lnInkToolbar)) { it is TextView && it.text.toString() == s(label) }!!.performClick()
    }

    @Test
    fun shapesNotesBookmarksColorsAndScroll() {
        val file = pdf("tools-ui.pdf", 3)
        launchReader(file) { scenario, view ->
            // page colors: sepia, remembered
            menu(scenario, R.string.pdf_reading_colors)
            onView(withText(s(R.string.pdf_theme_sepia))).inRoot(isDialog()).perform(click())
            onView(withText(s(R.string.pdf_tool_close))).inRoot(isDialog()).perform(click())
            assertEquals(PdfReadingPrefs.THEME_SEPIA, PdfReadingPrefs(context).theme)
            screenshot("1_sepia")
            scenario.onActivity { PdfReadingPrefs(context).theme = 0 }

            // annotate: a rectangle dragged on the first page
            scenario.onActivity { it.findViewById<View>(R.id.icAppEdit).performClick() }
            Thread.sleep(400)
            chip(scenario, R.string.pdf_annot_rect)
            var page = android.graphics.RectF()
            scenario.onActivity { page = view.pageViewRect(0)!! }
            val x1 = page.left + page.width() * 0.2f; val y1 = page.top + page.height() * 0.3f
            drag(scenario, x1, y1, x1 + page.width() * 0.3f, y1 + page.height() * 0.1f)
            var names = emptyList<String?>()
            scenario.onActivity { names = view.getAnnotations(0).map { it.name } }
            assertEquals("a shape drawn: $names", 1, names.count { it?.startsWith("pdfview-shape-") == true })
            val shape = names.first()!!
            screenshot("2_rectangle")

            // select it and drag it down
            chip(scenario, R.string.pdf_annot_select)
            Thread.sleep(200)
            tap(scenario, x1 + page.width() * 0.3f, y1 + 2) // on its top edge
            var before = android.graphics.RectF()
            scenario.onActivity { before = view.movableBounds(shape)!! }
            drag(scenario, x1 + page.width() * 0.15f, y1 + page.height() * 0.05f, x1 + page.width() * 0.15f, y1 + page.height() * 0.25f, steps = 12)
            var after = android.graphics.RectF()
            scenario.onActivity { after = view.movableBounds(shape)!! }
            assertTrue("moved down the page: $before -> $after", after.top < before.top - 50)
            screenshot("3_moved")

            // a note
            chip(scenario, R.string.pdf_annot_note)
            tap(scenario, page.left + page.width() * 0.7f, page.top + page.height() * 0.1f)
            Thread.sleep(400)
            onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(replaceText("Ghi chú thử"))
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
            Thread.sleep(300)
            var note: String? = null
            scenario.onActivity { a -> view.getAnnotations(0).firstOrNull { it.subtype == 1 }?.let { note = view.getAnnotationText(it) } }
            assertEquals("Ghi chú thử", note)
            screenshot("4_note")

            // save, the file holds both
            scenario.onActivity { it.findViewById<View>(R.id.icInkSave).performClick() }
            Thread.sleep(1500)
            scenario.onActivity { assertTrue("saved", !view.hasUnsavedChanges) }
            scenario.onActivity { it.findViewById<View>(R.id.icInkClose).performClick() }

            // bookmark the page shown
            menu(scenario, R.string.pdf_bookmarks)
            onView(withText("＋ " + s(R.string.pdf_bookmark_add, 1))).inRoot(isDialog()).perform(click())
            Thread.sleep(300)
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
            assertEquals(listOf(0), PdfReadingPrefs(context).bookmarks(file.absolutePath).map { it.page })

            // auto scroll moves the pages
            var y0 = 0f
            scenario.onActivity { y0 = view.currentYOffset }
            menu(scenario, R.string.pdf_auto_scroll)
            Thread.sleep(2000)
            var y1s = 0f
            scenario.onActivity { y1s = view.currentYOffset }
            assertNotEquals("scrolled", y0, y1s)
            screenshot("5_autoscroll")
            scenario.onActivity { a -> find<TextView>(a.window.decorView) { it is TextView && it.text == "✕" }!!.performClick() }

            // read aloud starts and stops without trouble
            menu(scenario, R.string.pdf_read_aloud)
            Thread.sleep(3000)
            screenshot("6_read_aloud")
            scenario.onActivity { a -> find<TextView>(a.window.decorView) { it is TextView && it.text == "✕" }!!.performClick() }
        }
        val pdfium = PdfiumCore(context)
        val doc = pdfium.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            pdfium.openPage(doc, 0)
            val subtypes = pdfium.getAnnotations(doc, 0).map { it.subtype }
            assertTrue("rectangle and note in the file: $subtypes", subtypes.contains(1) && subtypes.contains(13))
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    /** Every dialog of the tools menu opens (no text of it is broken), then is closed. */
    @Test
    fun eraserRubsOutPartOfAPenStroke() {
        val file = pdf("tools-eraser.pdf", 3)
        launchReader(file) { scenario, view ->
            scenario.onActivity { it.findViewById<View>(R.id.icAppEdit).performClick() } // the pen
            Thread.sleep(400)
            var page = android.graphics.RectF()
            scenario.onActivity { page = view.pageViewRect(0)!! }
            val x1 = page.left + page.width() * 0.2f; val x2 = page.left + page.width() * 0.7f
            val y = page.top + page.height() * 0.4f
            drag(scenario, x1, y, x2, y, steps = 12)
            Thread.sleep(500)
            var count = 0
            scenario.onActivity { count = view.getAnnotations(0).size }
            assertEquals("a stroke drawn", 1, count)

            chip(scenario, R.string.pdf_annot_eraser)
            var offset = 0f
            scenario.onActivity { offset = view.currentYOffset }
            // rubbing across the stroke, not a tap
            drag(scenario, (x1 + x2) / 2, y - page.height() * 0.1f, (x1 + x2) / 2, y + page.height() * 0.1f, steps = 12)
            Thread.sleep(1000)
            var strokes = emptyList<android.graphics.RectF>()
            scenario.onActivity { strokes = view.getAnnotations(0).map { view.pageRectToView(0, it.rect)!! } }
            assertEquals("only the part rubbed is gone, a piece is left on each side: $strokes", 2, strokes.size)
            val middle = (x1 + x2) / 2
            assertTrue("pieces left and right of the rub: $strokes", strokes.minOf { it.right } < middle && strokes.maxOf { it.left } > middle)
            assertTrue("the ends of the stroke stay: $strokes", strokes.minOf { it.left } < x1 + 20 && strokes.maxOf { it.right } > x2 - 20)
            scenario.onActivity { assertTrue(view.undoEdit()); count = view.getAnnotations(0).size }
            assertEquals("undo brings the whole stroke back", 1, count)
            var now = 0f
            scenario.onActivity { now = view.currentYOffset }
            assertEquals("the page stays", offset, now, 1f)
        }
    }

    @Test
    fun everyToolDialogOpens() {
        launchReader(pdf("tools-dialogs.pdf", 2)) { scenario, _ ->
            val dialogs = listOf(R.string.pdf_page_numbers, R.string.pdf_watermark, R.string.pdf_compress, R.string.pdf_password,
                R.string.pdf_flatten, R.string.export_images, R.string.pdf_bookmarks, R.string.pdf_reading_colors, R.string.pdf_to_word)
            for (item in dialogs) {
                menu(scenario, item)
                screenshot("dialog_" + context.resources.getResourceEntryName(item))
                androidx.test.espresso.Espresso.pressBack()
                Thread.sleep(300)
            }
        }
    }

    @Test
    fun selectionHandlesShow() {
        launchReader(pdf("tools-select.pdf", 1)) { scenario, view ->
            var page = android.graphics.RectF()
            scenario.onActivity { page = view.pageViewRect(0)!! }
            // a long press on a word of the second line
            val x = page.left + page.width() * 0.3f; val y = page.top + page.height() * (130f / 842f)
            val down = SystemClock.uptimeMillis()
            scenario.onActivity { a ->
                val loc = IntArray(2); view.getLocationInWindow(loc)
                a.window.decorView.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x + loc[0], y + loc[1], 0))
            }
            Thread.sleep(900)
            scenario.onActivity { a ->
                val loc = IntArray(2); view.getLocationInWindow(loc)
                a.window.decorView.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x + loc[0], y + loc[1], 0))
            }
            Thread.sleep(600)
            scenario.onActivity { assertTrue("text selected", view.hasTextSelection()) }
            screenshot("11_selection_handles")
        }
    }

    @Test
    fun organizeTurnsAPageAndSavesOver() {
        val file = pdf("tools-organize.pdf", 3)
        ActivityScenario.launchActivityForResult<PdfOrganizeActivity>(PdfOrganizeActivity.intent(context, file, null)).use { scenario ->
            scenario.onActivity { ui = it }
            Thread.sleep(2500)
            scenario.onActivity { a ->
                val grid = a.findViewById<RecyclerView>(R.id.rcvPages)
                assertEquals(3, grid.adapter!!.itemCount)
                // the first page's turn button
                find<TextView>(grid.getChildAt(0)) { it is TextView && it.text == "⟳" }!!.performClick()
                // the last page's delete button
                find<TextView>(grid.getChildAt(2)) { it is TextView && it.text == "✕" }!!.performClick()
            }
            Thread.sleep(500)
            screenshot("7_organize")
            scenario.onActivity { it.findViewById<View>(R.id.btnSave).performClick() }
            Thread.sleep(400)
            onView(withText(s(R.string.pdf_organize_save_over))).inRoot(isDialog()).perform(click())
            val end = System.currentTimeMillis() + 15_000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED && System.currentTimeMillis() < end) Thread.sleep(200)
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        }
        val pdfium = PdfiumCore(context)
        val doc = pdfium.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            assertEquals(2, pdfium.getPageCount(doc))
            assertEquals(1, pdfium.getPageRotation(doc, 0))
            assertTrue(pdfium.getPageTextLayout(doc, 1)!!.text.contains("Page 2"))
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    @Test
    fun textViewShowsParagraphs() {
        val file = pdf("tools-reflow.pdf", 2)
        ActivityScenario.launch<PdfReflowActivity>(PdfReflowActivity.intent(context, file, null, 0)).use { scenario ->
            Thread.sleep(3000)
            scenario.onActivity { a ->
                val list = a.findViewById<RecyclerView>(R.id.rcvText)
                assertTrue("page titles and paragraphs: ${list.adapter!!.itemCount}", list.adapter!!.itemCount >= 4)
                val texts = (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() }
                assertTrue(texts.toString(), texts.any { it.contains("Line 1 of a paragraph") && it.contains("Line 2") })
            }
            screenshot("8_reflow")
        }
    }

    private fun openOffice(asset: String, type: DocumentType, block: (ActivityScenario<ReadDocumentActivity>, OfficeDocumentView) -> Unit) {
        val file = File(context.filesDir, "tools-$asset")
        instrumentation.context.assets.open("samples/$asset").use { i -> file.outputStream().use { i.copyTo(it) } }
        val intent = Intent(context, ReadDocumentActivity::class.java)
            .putExtra(ReadDocumentActivity.ARG_DOCUMENT, RecentDocument(path = file.absolutePath, size = file.length(), type = type))
        ActivityScenario.launch<ReadDocumentActivity>(intent).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            val end = System.currentTimeMillis() + 60_000
            while (viewer.state.value.status != ReaderState.Status.Ready && System.currentTimeMillis() < end) Thread.sleep(200)
            Thread.sleep(4000)
            block(scenario, viewer)
        }
    }

    /** Share of pixels that are not white. */
    private fun inked(b: Bitmap): Float {
        var n = 0; var total = 0
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) { total++; if (b.getPixel(x, y) != Color.WHITE) n++ }
        return n.toFloat() / total
    }

    private fun checkPdf(pdf: File, pages: Int, name: String) {
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            assertEquals(pages, r.pageCount)
            r.openPage(0).use { page ->
                val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
                assertTrue("page 1 has content: ${inked(b)}", inked(b) > 0.01f)
            }
        }
    }

    @Test
    fun wordToPdf() {
        val pdf = File(shots, "word.pdf")
        var pages = 0
        openOffice("sample.docx", DocumentType.Doc) { _, viewer ->
            val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
            pages = kotlinx.coroutines.runBlocking {
                viewer.thumbnails!!.onDrawingThread { pdf.outputStream().use { writeWordPdf(word, it) } }!!
            }
        }
        assertTrue("pages written: $pages", pages > 0)
        checkPdf(pdf, pages, "9_word_pdf")
    }

    @Test
    fun excelToPdf() {
        val pdf = File(shots, "excel.pdf")
        var pages = 0
        openOffice("sample.xlsx", DocumentType.Excel) { scenario, viewer ->
            scenario.onActivity {
                val excel = viewer.control!!.getView() as com.wxiwei.office.ss.control.ExcelView
                val list = sheetPages(excel)
                pages = kotlinx.coroutines.runBlocking { pdf.outputStream().use { out -> writeSheetPdf(excel, list, out) } }
            }
        }
        assertTrue("pages written: $pages", pages > 0)
        checkPdf(pdf, pages, "10_excel_pdf")
    }

    /** A PDF of [pages] pages whose text is only a picture, as a scanner makes it: no text layer to select. */
    private fun scannedPdf(name: String, pages: Int): File {
        val out = File(context.filesDir, name).also { it.delete() }
        val doc = android.graphics.pdf.PdfDocument()
        for (p in 0 until pages) {
            val scan = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(scan)
            c.drawColor(Color.WHITE)
            val paint = Paint().apply { textSize = 48f; isAntiAlias = true }
            for (l in 0 until 10) c.drawText("Scanned page ${p + 1} line ${l + 1} words", 100f, 200f + l * 90f, paint)
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, p + 1).create())
            page.canvas.drawBitmap(scan, null, android.graphics.Rect(0, 0, 595, 842), null)
            doc.finishPage(page)
            scan.recycle()
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** A finger held on the screen at a point of [view], long enough for a long press. */
    private fun longPress(view: View, x: Float, y: Float) {
        val loc = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(loc) }
        val down = SystemClock.uptimeMillis()
        val automation = instrumentation.uiAutomation
        for ((action, t) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 1000)) {
            if (action == MotionEvent.ACTION_UP) Thread.sleep(1000)
            MotionEvent.obtain(down, t, action, loc[0] + x, loc[1] + y, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle()
            }
        }
        Thread.sleep(500)
    }

    /** A point on the third line of the picture of [page], in [view]; null while the page is not shown. */
    private fun onThirdLine(scenario: ActivityScenario<ReadPdfActivity>, view: PDFView, page: Int): Pair<Float, Float>? {
        var at: Pair<Float, Float>? = null
        scenario.onActivity {
            val r = view.pageViewRect(page) ?: return@onActivity
            val y = r.top + r.height() * (380f - 15f) / 1684f
            if (y in 0f..view.height.toFloat()) at = (r.left + r.width() * 0.25f) to y
        }
        return at
    }

    /** Waits until [page] has text (the reader opened the file again after OCR). */
    private fun waitForText(scenario: ActivityScenario<ReadPdfActivity>, view: PDFView, page: Int): Boolean {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500)
            var scanned = true
            scenario.onActivity { scanned = view.pageCount == 0 || view.pageViewRect(page) == null || view.isScannedPage(page) }
            if (!scanned) return true
        }
        return false
    }

    private fun fileText(file: File, page: Int): String = PdfiumCore(context).let { core ->
        val doc = core.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        try { core.readPageText(doc, page).orEmpty() } finally { core.closeDocument(doc) }
    }

    @Test
    fun longPressOnScanRecognizesThePageInPlace() {
        val file = scannedPdf("scan-select.pdf", 2)
        launchReader(file) { scenario, view ->
            scenario.onActivity { assertTrue("a scan before OCR", view.isScannedPage(0)) }
            var (x, y) = onThirdLine(scenario, view, 0)!!
            longPress(view, x, y)
            scenario.onActivity { assertTrue("nothing to select on the scan", !view.hasTextSelection()) }
            screenshot("11_scan_long_press")
            onView(withText(s(R.string.pdf_ocr_recognize))).inRoot(isDialog()).perform(click())
            // OCR of this page only, the file written over, the reader opened again
            assertTrue("page 1 has text after OCR", waitForText(scenario, view, 0))
            assertTrue("written into the same file", fileText(file, 0).contains("Scanned"))
            assertTrue("page 2 left for later", fileText(file, 1).isBlank())
            Thread.sleep(1000)
            // now a long press selects a word on the page
            onThirdLine(scenario, view, 0)!!.let { x = it.first; y = it.second }
            longPress(view, x, y)
            var selected = ""
            scenario.onActivity { selected = if (view.hasTextSelection()) view.selectedText else ""; view.clearTextSelection() }
            screenshot("12_scan_selected")
            assertTrue("a word selected: '$selected'", selected.isNotBlank())

            // page 2: a long press recognizes it without asking again
            scenario.onActivity { view.jumpTo(1) }
            Thread.sleep(1500)
            onThirdLine(scenario, view, 1)!!.let { x = it.first; y = it.second }
            longPress(view, x, y)
            assertTrue("page 2 has text after OCR", waitForText(scenario, view, 1))
            assertTrue(fileText(file, 1).contains("Scanned"))
        }
    }
}
