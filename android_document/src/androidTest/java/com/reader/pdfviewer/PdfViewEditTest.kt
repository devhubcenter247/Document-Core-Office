/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.reader.pdfviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.pdfviewer.pdfium.PdfFormField
import com.reader.pdfviewer.pdfium.PdfiumCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Edits through the viewer: shapes moved and resized with undo, notes, form fields, page geometry, then saved. */
@RunWith(AndroidJUnit4::class)
class PdfViewEditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun textPdf(name: String, pages: Int): File {
        val out = File(context.cacheDir, name).also { it.delete() }
        val doc = android.graphics.pdf.PdfDocument()
        val paint = Paint().apply { textSize = 24f }
        for (i in 0 until pages) {
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawText("Page ${i + 1}", 80f, 120f, paint)
            doc.finishPage(page)
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** Opens [file] in a PDFView filling an activity, then runs [block] (off the main thread). */
    private fun withView(file: File, block: (PDFView) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var view: PDFView
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                val container = FrameLayout(activity)
                activity.setContentView(container)
                view = PDFView(activity, null)
                container.addView(view, FrameLayout.LayoutParams(-1, -1))
                view.fromFile(file).enableAnnotationRendering(true).onLoad { loaded.countDown() }.load()
            }
            assertTrue("loaded", loaded.await(30, TimeUnit.SECONDS))
            // shown once laid out after the load
            val until = System.currentTimeMillis() + 10_000
            while (onMain { view.pageViewRect(0) } == null && System.currentTimeMillis() < until) Thread.sleep(50)
            instrumentation.waitForIdleSync()
            block(view)
        }
    }

    private fun annotations(file: File): List<Pair<String?, RectF>> {
        val pdfium = PdfiumCore(context)
        val doc = pdfium.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            pdfium.openPage(doc, 0)
            return pdfium.getAnnotations(doc, 0).map { it.name to it.rect }
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    @Test
    fun moveResizeUndoAndSave() {
        val file = textPdf("view_edit.pdf", 2)
        val saved = File(context.cacheDir, "view_edit_saved.pdf").also { it.delete() }
        withView(file) { view ->
            // page geometry: the first page is on screen, a point inside it maps to page 0
            val rect = onMain { view.pageViewRect(0) }!!
            assertTrue(rect.width() > 100)
            assertEquals(0, onMain { view.pageAt(rect.centerX(), rect.top + 10) })

            val name = onMain { view.addShape(0, PDFView.SHAPE_RECT, RectF(100f, 700f, 200f, 600f), Color.RED, 2f) }!!
            assertTrue(onMain { view.canMoveAnnotation(name) })
            assertTrue(onMain { view.moveAnnotation(name, RectF(300f, 400f, 450f, 300f)) })
            assertEquals(RectF(300f, 400f, 450f, 300f), onMain { view.movableBounds(name) })
            assertTrue(onMain { view.undoEdit() })
            assertEquals("back where it was", RectF(100f, 700f, 200f, 600f), onMain { view.movableBounds(name) })
            assertTrue(onMain { view.redoEdit() })

            val picture = Bitmap.createBitmap(30, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val image = onMain { view.addImage(0, RectF(50f, 200f, 110f, 180f), picture) }!!
            assertTrue(onMain { view.moveAnnotation(image, RectF(60f, 250f, 180f, 210f)) })

            val note = onMain { view.addNote(0, 400f, 800f, "Kiểm tra") }!!
            val info = onMain { view.getAnnotations(0).first { it.name == note } }
            assertEquals("Kiểm tra", onMain { view.getAnnotationText(info) })
            assertTrue(onMain { view.setAnnotationText(info, "Đã xem") })
            assertTrue(onMain { view.undoEdit() })
            assertEquals("Kiểm tra", onMain { view.getAnnotationText(info) })
            assertTrue(onMain { view.redoEdit() })

            // the sentence highlight and the paper colors draw without trouble
            onMain { view.setReadingHighlight(0, listOf(RectF(0.1f, 0.1f, 0.5f, 0.15f))); view.setSepiaMode(true); view.setNightMode(true); view.setSepiaMode(false) }
            instrumentation.waitForIdleSync()

            assertTrue(onMain { view.hasUnsavedChanges })
            assertTrue(view.saveDocument(saved))
        }
        val annots = annotations(saved).toMap()
        assertEquals(3, annots.size)
        val shape = annots.entries.first { it.key!!.startsWith("pdfview-shape-") }.value
        assertTrue("shape moved: $shape", shape.left > 290 && shape.left < 305 && shape.top > 395 && shape.top < 405)
        val image = annots.entries.first { it.key!!.startsWith("pdfview-image-") }.value
        assertEquals(60f, image.left, 0.5f)
        assertEquals(180f, image.right, 0.5f)
    }

    /**
     * Points of a page turned a quarter map between the screen and the PDF as pdfium maps them,
     * and without waiting for pdfium while another thread keeps it busy (a page being rendered).
     */
    @Test
    fun pagePointsMapLikePdfiumWithoutWaitingForIt() {
        val plain = textPdf("view_map.pdf", 1)
        val file = File(context.cacheDir, "view_map_turned.pdf").also { it.delete() }
        val pdfium = PdfiumCore(context)
        pdfium.newDocument(ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY)).let { doc ->
            try {
                assertTrue(pdfium.setPageRotation(doc, 0, 1))
                assertTrue(pdfium.saveAsCopy(doc, file.path))
            } finally {
                pdfium.closeDocument(doc)
            }
        }
        withView(file) { view ->
            val shown = onMain { view.pageViewRect(0) }!!
            val box = RectF(100f, 700f, 220f, 640f)
            val doc = pdfium.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            try {
                pdfium.openPage(doc, 0)
                val expected = pdfium.mapRectToDevice(doc, 0, 0, 0, shown.width().toInt(), shown.height().toInt(), 0, box)
                expected.sort()
                expected.offset(shown.left, shown.top)
                val mapped = onMain { view.pageRectToView(0, box) }!!
                for ((a, b) in listOf(expected.left to mapped.left, expected.top to mapped.top, expected.right to mapped.right, expected.bottom to mapped.bottom))
                    assertEquals("as pdfium maps it: $expected / $mapped", a, b, 2f)
                val (page, point) = onMain { view.viewToPagePoint(mapped.centerX(), mapped.centerY()) }!!
                assertEquals(0, page)
                assertEquals(box.centerX(), point.x, 0.5f)
                assertEquals(box.centerY(), point.y, 0.5f)

                // pdfium kept busy by large renderings, as when a page is drawn again after an edit
                val busy = java.util.concurrent.atomic.AtomicBoolean(true)
                val big = Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888)
                val renderer = Thread { while (busy.get()) pdfium.renderPageBitmapOnce(doc, big, 0) }.apply { start() }
                try {
                    Thread.sleep(100)
                    val ms = onMain {
                        val started = System.nanoTime()
                        repeat(300) { view.pageRectToView(0, box); view.viewToPagePoint(mapped.centerX(), mapped.centerY()) }
                        (System.nanoTime() - started) / 1e6
                    }
                    assertTrue("600 mappings took ${ms}ms", ms < 50)
                } finally {
                    busy.set(false)
                    renderer.join()
                    big.recycle()
                }
            } finally {
                pdfium.closeDocument(doc)
            }
        }
    }

    /** An annotation that came with the file: listed and deleted off the main thread, and the deletion can be undone. */
    @Test
    fun deleteAnAnnotationOfTheFileAndUndo() {
        val file = File(context.cacheDir, "view_imported.pdf").also { it.delete() }
        // snapshots left by a run that was stopped
        File(context.cacheDir, "pdfview-undo").deleteRecursively()
        withView(textPdf("view_import_source.pdf", 3)) { view ->
            assertNotNull(onMain { view.addShape(1, PDFView.SHAPE_RECT, RectF(100f, 700f, 200f, 600f), Color.RED, 2f) })
            assertTrue(view.saveDocument(file))
        }
        withView(file) { view ->
            val listed = CountDownLatch(1)
            var all = emptyList<Pair<com.reader.pdfviewer.model.PdfAnnotationInfo, String?>>()
            onMain { view.loadAnnotations({ true }) { all = it; listed.countDown() } }
            assertTrue(listed.await(10, TimeUnit.SECONDS))
            val info = all.single().first
            assertEquals(1, info.page)
            assertFalse("it came with the file", onMain { view.canMoveAnnotation(info.name) })

            val deleted = CountDownLatch(1)
            var removed = false
            onMain { view.removeAnnotation(info) { removed = it; deleted.countDown() } }
            assertTrue(deleted.await(10, TimeUnit.SECONDS))
            assertTrue(removed)
            assertTrue(onMain { view.getAnnotations(1) }.isEmpty())
            val snapshots = File(context.cacheDir, "pdfview-undo").listFiles().orEmpty()
            assertEquals("its copy of the document is on disk", 1, snapshots.size)

            val undone = CountDownLatch(1)
            var back = false
            onMain { view.undoEdit { back = it; undone.countDown() } }
            assertTrue(undone.await(10, TimeUnit.SECONDS))
            assertTrue(back)
            assertEquals(info.name, onMain { view.getAnnotations(1) }.single().name)
            assertTrue(onMain { view.redoEdit() })
            assertTrue(onMain { view.getAnnotations(1) }.isEmpty())
            onMain { view.recycle() }
            assertTrue("removed with the document", File(context.cacheDir, "pdfview-undo").listFiles().orEmpty().isEmpty())
        }
    }

    private fun renderPage(file: File, width: Int): Bitmap {
        val pdfium = PdfiumCore(context)
        val doc = pdfium.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            val size = pdfium.getPagePointSize(doc, 0)!!
            val bitmap = Bitmap.createBitmap(width, width * size.height / size.width, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            assertTrue(pdfium.renderPageBitmapOnce(doc, bitmap, 0, renderAnnot = true))
            return bitmap
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    /**
     * A picture added off the main thread, then moved and made larger: it is drawn at its new
     * place, no longer at the old one. Text added with it embeds only its own glyphs.
     */
    @Test
    fun pictureMovedAndTextWithItsGlyphsOnly() {
        val file = textPdf("view_picture.pdf", 1)
        val saved = File(context.cacheDir, "view_picture_saved.pdf").also { it.delete() }
        withView(file) { view ->
            // two halves, so a picture cut or stretched the wrong way shows
            fun halves(width: Int, height: Int, left: Int, right: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until width) for (y in 0 until height) setPixel(x, y, if (x < width / 2) left else right)
            }
            val picture = halves(40, 20, Color.BLUE, Color.RED)
            val added = CountDownLatch(1)
            var name: String? = null
            onMain { view.addImage(0, RectF(50f, 700f, 150f, 650f), picture) { name = it; added.countDown() } }
            assertTrue(added.await(10, TimeUnit.SECONDS))
            assertNotNull(name)
            assertTrue(onMain { view.moveAnnotation(name!!, RectF(300f, 300f, 500f, 200f)) })
            assertTrue(onMain { view.undoEdit() })
            assertEquals(RectF(50f, 700f, 150f, 650f), onMain { view.movableBounds(name!!) })
            assertTrue(onMain { view.redoEdit() })
            // made larger around where it is
            val grown = onMain { view.addImage(0, RectF(50f, 150f, 100f, 100f), halves(20, 20, Color.GREEN, Color.BLACK)) }!!
            assertTrue(onMain { view.moveAnnotation(grown, RectF(30f, 180f, 160f, 80f)) })
            assertNotNull(onMain { view.addText(0, 60f, 500f, "Xin chào Việt Nam", 18f, Color.RED) })
            assertTrue(view.saveDocument(saved))
        }
        // PDF y goes up: the picture is now at 300..500 x 200..300 of a 595 x 842 page
        val page = renderPage(saved, 595)
        assertEquals("at its new place", Color.BLUE, page.getPixel(350, 842 - 250))
        assertEquals("all of it", Color.RED, page.getPixel(450, 842 - 250))
        assertEquals("gone from the old one", Color.WHITE, page.getPixel(100, 842 - 675))
        assertEquals("fills its larger bounds", Color.GREEN, page.getPixel(40, 842 - 90))
        assertEquals(Color.BLACK, page.getPixel(150, 842 - 170))
        assertEquals("and nothing more", Color.WHITE, page.getPixel(170, 842 - 90))
        // the saved appearance is at the bounds itself: viewers that do not fit it into the rectangle show it right too
        val bytes = saved.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("appearance box of the moved picture", Regex("/BBox\\s*\\[\\s*300 200 500 300\\s*]").containsMatchIn(bytes))
        assertTrue("appearance box of the enlarged picture", Regex("/BBox\\s*\\[\\s*30 80 160 180\\s*]").containsMatchIn(bytes))
        var red = 0
        for (y in 842 - 505 until 842 - 475) for (x in 60 until 260) if (Color.red(page.getPixel(x, y)) > 180 && Color.blue(page.getPixel(x, y)) < 100) red++
        assertTrue("the text is drawn ($red red pixels)", red > 100)
        assertTrue("grew by ${saved.length() - file.length()} bytes", saved.length() - file.length() < 150_000)
    }

    /**
     * Edits are drawn by rendering again only the parts of the page they touch: each shows once
     * its part is redrawn, and undoing one leaves the other as it was drawn.
     */
    @Test
    fun editIsShownAfterRedrawingItsPart() {
        withView(textPdf("view_partial.pdf", 1)) { view ->
            fun shown(): Bitmap = onMain {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }
            fun colorAt(pageRect: RectF): Int {
                val r = onMain { view.pageRectToView(0, pageRect) }!!
                return shown().getPixel(r.centerX().toInt(), r.centerY().toInt())
            }
            val first = RectF(100f, 700f, 200f, 600f)
            val second = RectF(350f, 300f, 450f, 200f)
            assertNotNull(onMain { view.addShape(0, PDFView.SHAPE_RECT, first, Color.RED, 2f, Color.RED) })
            assertNotNull(onMain { view.addShape(0, PDFView.SHAPE_RECT, second, Color.BLUE, 2f, Color.BLUE) })
            val until = System.currentTimeMillis() + 10_000
            while ((colorAt(first) != Color.RED || colorAt(second) != Color.BLUE) && System.currentTimeMillis() < until) Thread.sleep(100)
            assertEquals(Color.RED, colorAt(first))
            assertEquals(Color.BLUE, colorAt(second))
            // undoing the second leaves the first as it is drawn
            assertTrue(onMain { view.undoEdit() })
            val again = System.currentTimeMillis() + 10_000
            while (colorAt(second) == Color.BLUE && System.currentTimeMillis() < again) Thread.sleep(100)
            assertEquals(Color.WHITE, colorAt(second))
            assertEquals(Color.RED, colorAt(first))
        }
    }

    /**
     * A document of 300 pages: every edit returns to the main thread quickly, also right after
     * another one, when the page is being drawn again. The times are logged (tag PdfEditPerf).
     */
    @Test
    fun editsStayFastOnALargeDocument() {
        val file = textPdf("view_large.pdf", 300)
        val saved = File(context.cacheDir, "view_large_saved.pdf").also { it.delete() }
        withView(file) { view ->
            val times = LinkedHashMap<String, Double>()
            /** Longest time [block] keeps the main thread, over [count] runs. */
            fun <T> onMainTimed(label: String, count: Int = 1, block: (Int) -> T): T {
                var result: T? = null
                var longest = 0.0
                repeat(count) { i ->
                    val ms = onMain { val started = System.nanoTime(); result = block(i); (System.nanoTime() - started) / 1e6 }
                    longest = maxOf(longest, ms)
                }
                times[label] = longest
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            val shape = onMainTimed("add shape (x10)", 10) { i ->
                view.addShape(0, PDFView.SHAPE_RECT, RectF(60f + i * 40, 780f, 90f + i * 40, 750f), Color.RED, 2f)
            }!!
            onMainTimed("move shape (x10)", 10) { i -> view.moveAnnotation(shape, RectF(100f + i * 10, 600f, 200f + i * 10, 500f)) }
            val page = onMain { view.pageViewRect(0) }!!
            onMainTimed("ink stroke of 300 points (x5)", 5) { stroke ->
                view.startInkStroke(page.left + 40, page.top + 200 + stroke * 30)
                for (i in 1..300) view.addInkPoint(page.left + 40 + i * (page.width() - 80) / 300, page.top + 200 + stroke * 30 + (i % 20))
                view.finishInkStroke()
            }
            val onShape = onMain { view.pageRectToView(0, view.movableBounds(shape)!!) }!!
            assertNotNull(onMainTimed("find the annotation under a tap (x20)", 20) { view.findAnnotationAt(onShape.centerX(), onShape.centerY()) })
            onMainTimed("undo, redo (x10)", 10) { view.undoEdit(); view.redoEdit() }

            val photo = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
            val added = CountDownLatch(1)
            var picture: String? = null
            val addStarted = System.nanoTime()
            onMainTimed("add a 2000 x 1500 picture: main thread") { view.addImage(0, RectF(100f, 400f, 300f, 250f), photo) { picture = it; added.countDown() } }
            assertTrue(added.await(30, TimeUnit.SECONDS))
            times["add a 2000 x 1500 picture: until it is there"] = (System.nanoTime() - addStarted) / 1e6
            onMainTimed("move the picture (x10)", 10) { i -> view.moveAnnotation(picture!!, RectF(100f + i * 5, 400f, 300f + i * 5, 250f)) }

            val listed = CountDownLatch(1)
            var count = 0
            val listStarted = System.nanoTime()
            onMainTimed("list the annotations: main thread") { view.loadAnnotations({ true }) { count = it.size; listed.countDown() } }
            assertTrue(listed.await(60, TimeUnit.SECONDS))
            times["list the annotations of 300 pages: until listed"] = (System.nanoTime() - listStarted) / 1e6
            assertEquals("10 shapes, 5 strokes, 1 picture", 16, count)

            val saveStarted = System.nanoTime()
            assertTrue(view.saveDocument(saved))
            times["save (off the main thread)"] = (System.nanoTime() - saveStarted) / 1e6

            for ((label, ms) in times) android.util.Log.i("PdfEditPerf", "%-50s %8.1f ms".format(label, ms))
            for ((label, ms) in times) if (!label.contains("until") && !label.startsWith("save"))
                assertTrue("$label kept the main thread ${ms}ms", ms < 100)
        }
    }

    @Test
    fun documentWithoutFormHasNoFields() {
        withView(textPdf("view_noform.pdf", 1)) { view ->
            assertFalse(onMain { view.hasForm })
            assertTrue(onMain { view.getFormFields(0) }.isEmpty())
        }
    }

    @Test
    fun fillFormInTheViewer() {
        // the same form as PdfToolsTest, made with PdfBox
        val file = File(context.cacheDir, "view_form.pdf").also { it.delete() }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { pd ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
            pd.addPage(page)
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(pd)
            pd.documentCatalog.acroForm = form
            form.defaultResources = com.tom_roush.pdfbox.pdmodel.PDResources().apply {
                put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"), com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA)
            }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            val text = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form).apply { partialName = "email"; defaultAppearance = "/Helv 12 Tf 0 g" }
            text.widgets[0].apply { rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(50f, 700f, 300f, 24f); this.page = page; isPrinted = true }
            page.annotations.add(text.widgets[0])
            form.fields.add(text)
            pd.save(file)
        }
        val saved = File(context.cacheDir, "view_form_saved.pdf").also { it.delete() }
        withView(file) { view ->
            assertTrue(onMain { view.hasForm })
            val field = onMain { view.getFormFields(0) }.single()
            assertEquals(PdfFormField.TYPE_TEXT, field.type)
            assertEquals("email", field.name)
            // the field is found under its middle on screen
            val onScreen = onMain { view.pageRectToView(0, field.rect) }!!
            assertNotNull(onMain { view.findFormFieldAt(onScreen.centerX(), onScreen.centerY()) })
            assertTrue(onMain { view.setFormText(field, "a@b.vn") })
            assertEquals("a@b.vn", onMain { view.getFormFields(0) }.single().value)
            instrumentation.waitForIdleSync()
            assertTrue(view.saveDocument(saved))
        }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(saved).use { pd ->
            assertEquals("a@b.vn", pd.documentCatalog.acroForm.getField("email").valueAsString)
        }
    }

    /** The frame of added text hugs its glyphs: no more than a few points around them on each side. */
    @Test
    fun textFrameFitsItsText() {
        val file = textPdf("view_text_frame.pdf", 1)
        val saved = File(context.cacheDir, "view_text_frame_saved.pdf").also { it.delete() }
        val frames = ArrayList<RectF>()
        withView(file) { view ->
            for ((y, text) in listOf(600f to "Xin chào Việt Nam", 400f to "Dòng một\nDòng hai dài hơn")) {
                val name = onMain { view.addText(0, 60f, y, text, 13f, Color.RED) }!!
                frames += onMain { view.movableBounds(name) }!!
            }
            // a frame dragged wider (same height), and one made taller: the frame stays on the text
            val wide = onMain { view.addText(0, 60f, 250f, "Kéo rộng khung", 13f, Color.RED) }!!
            val w = onMain { view.movableBounds(wide) }!!
            assertTrue(onMain { view.moveAnnotation(wide, RectF(w.left, w.top, w.right + 120f, w.bottom)) })
            frames += onMain { view.movableBounds(wide) }!!
            val tall = onMain { view.addText(0, 60f, 150f, "Phóng to chữ", 13f, Color.RED) }!!
            val t = onMain { view.movableBounds(tall) }!!
            assertTrue(onMain { view.moveAnnotation(tall, RectF(t.left, t.top, t.right, t.top - (t.top - t.bottom) * 1.5f)) })
            frames += onMain { view.movableBounds(tall) }!!
            assertTrue(view.saveDocument(saved))
        }
        // 1 px = 1 pt; PDF y goes up
        val page = renderPage(saved, 595)
        for (frame in frames) {
            var l = Int.MAX_VALUE; var r = -1; var t = Int.MAX_VALUE; var b = -1
            for (y in (842 - frame.top - 20).toInt().coerceAtLeast(0) until (842 - frame.bottom + 20).toInt().coerceAtMost(841))
                for (x in (frame.left - 20).toInt().coerceAtLeast(0) until (frame.right + 20).toInt().coerceAtMost(594)) {
                    val c = page.getPixel(x, y)
                    if (Color.red(c) > 150 && Color.green(c) < 120) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
                }
            val ink = RectF(l.toFloat(), (842 - t).toFloat(), (r + 1).toFloat(), (842 - b - 1).toFloat())
            val margins = listOf(ink.left - frame.left, frame.right - ink.right, frame.top - ink.top, ink.bottom - frame.bottom)
            android.util.Log.i("TEXTFRAME", "frame $frame ink $ink margins $margins")
            assertTrue("text inside its frame: $margins", margins.all { it >= -1f })
            assertTrue("frame hugs the text: $margins (left, right, top, bottom)", margins.all { it <= 5f })
        }
    }

    /** A long press on a word selects the whole word (Vietnamese marks included), not one letter. */
    @Test
    fun longPressSelectsTheWord() {
        val file = File(context.cacheDir, "view_long_press.pdf").also { it.delete() }
        android.graphics.pdf.PdfDocument().also { doc ->
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f }
            page.canvas.drawText("Hello wonderful world", 60f, 200f, paint)
            page.canvas.drawText("Xin chào các bạn", 60f, 300f, paint)
            doc.finishPage(page)
            file.outputStream().use { doc.writeTo(it) }
            doc.close()
        }
        val bounds = PdfiumCore(context).let { core ->
            val doc = core.newDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            try { core.getPageTextLayout(doc, 0)!!.let { l -> listOf("wonderful", "chào").associateWith { w -> l.words.first { it.text == w }.bounds to l.pageHeight } } }
            finally { core.closeDocument(doc) }
        }
        withView(file) { view ->
            onMain { view.enableTextSelection(true) }
            for ((word, place) in bounds) {
                val (b, height) = place
                // shown bounds (y down) to PDF bounds (y up), then to the view, then to the screen
                val v = onMain { view.pageRectToView(0, RectF(b.left, height - b.top, b.right, height - b.bottom)) }!!
                val at = IntArray(2).also { a -> onMain { view.getLocationOnScreen(a) } }
                val x = at[0] + v.left + v.width() * 0.4f; val y = at[1] + v.centerY()
                val automation = instrumentation.uiAutomation
                val t0 = android.os.SystemClock.uptimeMillis()
                for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to t0, android.view.MotionEvent.ACTION_UP to t0 + 900)) {
                    if (action == android.view.MotionEvent.ACTION_UP) Thread.sleep(900)
                    android.view.MotionEvent.obtain(t0, time, action, x, y, 0).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle() }
                }
                Thread.sleep(600)
                assertEquals("long press on '$word'", word, onMain { view.selectedText })
                onMain { view.clearTextSelection() }
            }
        }
    }
}
