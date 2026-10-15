/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.reader.pdfviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.pdfviewer.pdfium.PdfDocument
import com.reader.pdfviewer.pdfium.PdfFormField
import com.reader.pdfviewer.pdfium.PdfPasswordException
import com.reader.pdfviewer.pdfium.PdfiumCore
import com.reader.pdfviewer.tools.PdfSource
import com.reader.pdfviewer.tools.PdfTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/** The PDF tools on generated files: pages, text, OCR, compression, black out, passwords, forms, notes and shapes. */
@RunWith(AndroidJUnit4::class)
class PdfToolsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tools = PdfTools(context)
    private val pdfium = PdfiumCore(context)

    private fun file(name: String) = File(context.cacheDir, name).also { it.delete() }

    /** A PDF with real text: one page per entry of [pages], each line of it drawn from the top. */
    private fun textPdf(name: String, pages: List<List<String>>): File {
        val out = file(name)
        val doc = android.graphics.pdf.PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = Color.BLACK }
        pages.forEachIndexed { i, lines ->
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            lines.forEachIndexed { l, text -> page.canvas.drawText(text, 60f, 100f + l * 60f, paint) }
            doc.finishPage(page)
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** A "scan": the text drawn into a picture, the PDF page is only that picture. */
    private fun scannedPdf(name: String, text: String): File {
        val out = file(name)
        val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(text, 120f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 70f; color = Color.BLACK })
        }
        runBlocking { tools.createFromImages(out) { addImage(bitmap) } }
        bitmap.recycle()
        return out
    }

    private fun open(f: File, password: String? = null): PdfDocument =
        pdfium.newDocument(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), password)

    private fun <T> withDoc(f: File, password: String? = null, block: (PdfDocument) -> T): T {
        val doc = open(f, password)
        try { return block(doc) } finally { pdfium.closeDocument(doc) }
    }

    private fun text(f: File, page: Int, password: String? = null) = withDoc(f, password) { pdfium.getPageTextLayout(it, page)!!.text }

    @Test
    fun organizeReordersTurnsAndAddsBlank() = runBlocking {
        val src = textPdf("org_src.pdf", listOf(listOf("Page A"), listOf("Page B"), listOf("Page C")))
        val out = file("org_out.pdf")
        tools.organize(listOf(PdfSource(src)), listOf(
            PdfTools.PageRef(0, 2, quarterTurns = 1),
            PdfTools.PageRef(-1, 0),
            PdfTools.PageRef(0, 0),
            PdfTools.PageRef(0, 0, quarterTurns = 2),
        ), out)
        withDoc(out) { doc ->
            assertEquals(4, pdfium.getPageCount(doc))
            assertEquals("turned a quarter", 1, pdfium.getPageRotation(doc, 0))
            assertEquals("turned half", 2, pdfium.getPageRotation(doc, 3))
            assertTrue(pdfium.getPageTextLayout(doc, 0)!!.text.contains("Page C"))
            assertTrue("blank page", pdfium.getPageTextLayout(doc, 1)!!.text.isBlank())
            assertTrue(pdfium.getPageTextLayout(doc, 2)!!.text.contains("Page A"))
            // a turned page is shown landscape
            val size = pdfium.getPagePointSize(doc, 0)!!
            assertTrue("landscape $size", size.width > size.height)
        }
    }

    @Test
    fun stampAddsWatermarkNumbersAndFooter() = runBlocking {
        val src = textPdf("stamp_src.pdf", listOf(listOf("Body one"), listOf("Body two"), listOf("Body three")))
        val out = file("stamp_out.pdf")
        tools.stamp(PdfSource(src), out, listOf(
            PdfTools.TextStamp(PdfTools.StampPosition.CENTER, 60f, 0x4DFF0000, angle = 45f) { _, _ -> "BẢN NHÁP" },
            PdfTools.TextStamp(PdfTools.StampPosition.BOTTOM_CENTER, 10f, Color.BLACK) { p, n -> "$p / $n" },
            PdfTools.TextStamp(PdfTools.StampPosition.TOP_LEFT, 9f, Color.DKGRAY) { _, _ -> "Header Co" },
        ))
        for (p in 0..2) {
            val t = text(out, p)
            assertTrue("watermark on $p: $t", t.contains("BẢN NHÁP"))
            assertTrue("number on $p: $t", t.contains("${p + 1} / 3"))
            assertTrue("header on $p: $t", t.contains("Header Co"))
            assertTrue("body kept on $p", t.contains("Body"))
        }
        // only the glyphs of these texts are embedded, not the whole system font
        assertTrue("grew by ${out.length() - src.length()} bytes", out.length() - src.length() < 150_000)
        // the number is at the bottom of the page, the header at the top
        withDoc(out) { doc ->
            val layout = pdfium.getPageTextLayout(doc, 0)!!
            val number = layout.words.first { it.text == "1" }
            val header = layout.words.first { it.text == "Header" }
            assertTrue("number near the bottom ${number.bounds}", number.bounds.top > layout.pageHeight * 0.9f)
            assertTrue("header near the top ${header.bounds}", header.bounds.bottom < layout.pageHeight * 0.1f)
        }
    }

    @Test
    fun ocrMakesScansSearchable() = runBlocking {
        val src = scannedPdf("ocr_src.pdf", "HELLO SCANNED WORLD")
        assertFalse("no text before", text(src, 0).contains("HELLO"))
        val out = file("ocr_out.pdf")
        val pages = tools.addTextLayer(PdfSource(src), out)
        assertEquals(1, pages)
        val t = text(out, 0)
        assertTrue("recognized: $t", t.contains("HELLO") && t.contains("WORLD"))
        assertTrue("the words stay apart: $t", t.contains("HELLO SCANNED WORLD"))
        // the words sit where the picture shows them (upper part of the page)
        withDoc(out) { doc ->
            val layout = pdfium.getPageTextLayout(doc, 0)!!
            val hello = layout.words.first { it.text.contains("HELLO") }
            assertTrue("placed ${hello.bounds}", hello.bounds.top < layout.pageHeight * 0.3f && hello.bounds.left < layout.pageWidth * 0.3f)
        }
    }

    /** OCR text keeps a space between words (search, copy, read aloud), none at a line's end, and each word on its own box. */
    @Test
    fun ocrWordsAreSeparated() = runBlocking {
        val page = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 56f; color = Color.BLACK }
            drawText("We will deliver the goods", 120f, 300f, paint)
            drawText("Account number paid today", 120f, 420f, paint)
        }
        val src = file("ocr_words_src.pdf")
        tools.createFromImages(src) { addImage(page) }
        val out = file("ocr_words_out.pdf")
        assertEquals(1, tools.addTextLayer(PdfSource(src), out))
        val t = text(out, 0)
        assertTrue("words apart: $t", t.contains("will deliver") && t.contains("the goods") && !t.contains("willdeliver"))
        assertTrue("second line: $t", t.contains("number paid"))
        withDoc(out) { doc ->
            val layout = pdfium.getPageTextLayout(doc, 0)!!
            val will = layout.words.first { it.text == "will" }
            val deliver = layout.words.first { it.text == "deliver" }
            assertTrue("each word on its own box: ${will.bounds} ${deliver.bounds}", will.bounds.right <= deliver.bounds.left + 2f)
            assertTrue("no space at a line's end: '${layout.text}'", !layout.text.contains("goods \n") && !layout.text.contains("goods \r"))
        }
    }

    @Test
    fun ocrOnATurnedPageReadsTheWayItIsShown() = runBlocking {
        // the scan is stored sideways and the page turned a quarter, as scanners often do
        val upright = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        Canvas(upright).apply {
            drawColor(Color.WHITE)
            drawText("TURNED PAGE TEXT", 120f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 70f; color = Color.BLACK })
        }
        val sideways = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, android.graphics.Matrix().apply { postRotate(-90f) }, true)
        val stored = file("turn_stored.pdf")
        tools.createFromImages(stored) { addImage(sideways) }
        val src = file("turn_src.pdf")
        tools.organize(listOf(PdfSource(stored)), listOf(PdfTools.PageRef(0, 0, quarterTurns = 1)), src)
        val out = file("turn_out.pdf")
        assertEquals(1, tools.addTextLayer(PdfSource(src), out))
        withDoc(out) { doc ->
            val layout = pdfium.getPageTextLayout(doc, 0)!!
            assertTrue("shown upright: ${layout.pageWidth}x${layout.pageHeight}", layout.pageHeight > layout.pageWidth)
            assertTrue("recognized: ${layout.text}", layout.text.contains("TURNED"))
            val word = layout.words.first { it.text.contains("TURNED") }
            assertTrue("reads across the page as shown: ${word.bounds}", word.bounds.width() > word.bounds.height() * 2)
            assertTrue("near the top left as shown: ${word.bounds}", word.bounds.top < layout.pageHeight * 0.3f && word.bounds.left < layout.pageWidth * 0.3f)
        }
    }

    @Test
    fun compressShrinksBigPictures() = runBlocking {
        // a large, detailed picture stored with little compression
        val bitmap = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        val pixels = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            for (x in pixels.indices) pixels[x] = Color.rgb((x * 255 / bitmap.width + random.nextInt(40)) % 256, (y * 255 / bitmap.height) % 256, random.nextInt(256))
            bitmap.setPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        val src = file("compress_src.pdf")
        tools.createFromImages(src) { addImage(bitmap, jpegQuality = 100) }
        bitmap.recycle()
        val out = file("compress_out.pdf")
        val changed = tools.compress(PdfSource(src), out, PdfTools.Compression.HIGH)
        assertEquals(1, changed)
        assertTrue("smaller: ${src.length()} -> ${out.length()}", out.length() < src.length() / 3)
        withDoc(out) { doc ->
            val image = pdfium.getPageImages(doc, 0).single()
            assertTrue("fewer pixels: ${image.pixelWidth}", image.pixelWidth < 2400)
            // still fills the page
            assertEquals(595f, image.shownWidth, 1f)
        }
    }

    @Test
    fun redactRemovesTextUnderTheBox() = runBlocking {
        val src = textPdf("redact_src.pdf", listOf(listOf("SECRET 12345", "", "", "", "Public words stay"), listOf("Second page untouched")))
        val out = file("redact_out.pdf")
        // the first line: y 100 of 842 is about 0.1 of the page
        tools.redact(PdfSource(src), out, mapOf(0 to listOf(RectF(0.05f, 0.08f, 0.8f, 0.14f))))
        val first = text(out, 0)
        assertFalse("secret gone: $first", first.contains("SECRET") || first.contains("12345"))
        assertTrue("other text found again: $first", first.contains("Public"))
        assertTrue("other pages copied", text(out, 1).contains("Second page"))
        withDoc(out) { assertEquals(2, pdfium.getPageCount(it)) }
    }

    @Test
    fun setAndRemovePassword() = runBlocking {
        val src = textPdf("pw_src.pdf", listOf(listOf("Private")))
        val locked = file("pw_locked.pdf")
        tools.setPassword(PdfSource(src), locked, "1234", allowPrint = false, allowCopy = false)
        val failed = runCatching { open(locked) }.exceptionOrNull()
        assertTrue("asks a password: $failed", failed is PdfPasswordException)
        assertTrue(text(locked, 0, "1234").contains("Private"))
        val open = file("pw_open.pdf")
        tools.removePassword(PdfSource(locked, "1234"), open)
        assertTrue(text(open, 0).contains("Private"))
    }

    /** A form made with PdfBox: a text field, a check box and a combo box. */
    private fun formPdf(name: String): File {
        val out = file(name)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { pd ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
            pd.addPage(page)
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(pd)
            pd.documentCatalog.acroForm = form
            val resources = com.tom_roush.pdfbox.pdmodel.PDResources()
            resources.put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"), com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA)
            form.defaultResources = resources
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            fun widget(field: com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField, rect: com.tom_roush.pdfbox.pdmodel.common.PDRectangle) {
                val w = field.widgets[0]
                w.rectangle = rect
                w.page = page
                w.isPrinted = true
                page.annotations.add(w)
                form.fields.add(field)
            }
            val text = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form).apply { partialName = "name"; defaultAppearance = "/Helv 12 Tf 0 g" }
            widget(text, com.tom_roush.pdfbox.pdmodel.common.PDRectangle(50f, 700f, 250f, 24f))
            val check = com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox(form).apply { partialName = "agree" }
            widget(check, com.tom_roush.pdfbox.pdmodel.common.PDRectangle(50f, 650f, 18f, 18f))
            // an appearance for "on" and "off", as viewers need them
            val ap = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary()
            val onOff = com.tom_roush.pdfbox.cos.COSDictionary()
            for (state in listOf("Yes", "Off")) {
                val stream = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(pd)
                stream.bBox = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(18f, 18f)
                stream.resources = com.tom_roush.pdfbox.pdmodel.PDResources()
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pd, stream).use { cs ->
                    cs.addRect(1f, 1f, 16f, 16f); cs.stroke()
                    if (state == "Yes") { cs.moveTo(3f, 9f); cs.lineTo(8f, 3f); cs.lineTo(15f, 15f); cs.stroke() }
                }
                onOff.setItem(state, stream)
            }
            ap.cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.N, onOff)
            check.widgets[0].appearance = ap
            check.widgets[0].cosObject.setName(com.tom_roush.pdfbox.cos.COSName.AS, "Off")
            val combo = com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox(form).apply {
                partialName = "city"; defaultAppearance = "/Helv 12 Tf 0 g"; options = listOf("Hanoi", "Saigon", "Hue")
            }
            widget(combo, com.tom_roush.pdfbox.pdmodel.common.PDRectangle(50f, 600f, 150f, 24f))
            pd.save(out)
        }
        return out
    }

    @Test
    fun fillFormSaveAndFlatten() = runBlocking {
        val src = formPdf("form_src.pdf")
        val filled = file("form_filled.pdf")
        withDoc(src) { doc ->
            assertTrue("has a form", pdfium.initForms(doc))
            pdfium.openPage(doc, 0)
            val fields = pdfium.getFormFields(doc, 0)
            assertEquals(fields.toString(), setOf(PdfFormField.TYPE_TEXT, PdfFormField.TYPE_CHECKBOX, PdfFormField.TYPE_COMBOBOX), fields.map { it.type }.toSet())
            val name = fields.first { it.type == PdfFormField.TYPE_TEXT }
            val agree = fields.first { it.type == PdfFormField.TYPE_CHECKBOX }
            val city = fields.first { it.type == PdfFormField.TYPE_COMBOBOX }
            assertEquals(listOf("Hanoi", "Saigon", "Hue"), city.options)
            assertTrue(pdfium.setFormText(doc, 0, name, "Nguyễn Văn A"))
            assertTrue(pdfium.clickFormField(doc, 0, agree))
            assertTrue(pdfium.setFormChoice(doc, 0, city, 1))
            val after = pdfium.getFormFields(doc, 0)
            assertEquals("Nguyễn Văn A", after.first { it.type == PdfFormField.TYPE_TEXT }.value)
            assertTrue("checked", after.first { it.type == PdfFormField.TYPE_CHECKBOX }.checked)
            assertEquals("Saigon", after.first { it.type == PdfFormField.TYPE_COMBOBOX }.value)
            assertTrue(pdfium.saveAsCopy(doc, filled.path))
        }
        // read back by PdfBox, another reader
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(filled).use { pd ->
            val form = pd.documentCatalog.acroForm
            assertEquals("Nguyễn Văn A", form.getField("name").valueAsString)
            assertEquals("Yes", form.getField("agree").valueAsString)
            assertEquals(listOf("Saigon"), (form.getField("city") as com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox).value)
        }
        // flattened: the values are drawn in the page, no field is left
        val flat = file("form_flat.pdf")
        tools.flatten(PdfSource(filled), flat)
        withDoc(flat) { doc ->
            pdfium.openPage(doc, 0)
            assertTrue("no annotations", pdfium.getAnnotations(doc, 0).isEmpty())
            assertTrue("value in the page: " + pdfium.getPageTextLayout(doc, 0)!!.text, pdfium.getPageTextLayout(doc, 0)!!.text.contains("Saigon"))
        }
    }

    @Test
    fun formFieldsReadAnyCharacter() {
        // names, values and options come as separate strings: tabs, separators and emoji stay
        val parts = arrayOf("3 6 0 0 1 2 3 4 0", "na\tme", "a\tb 😀\u001f", "5 4 0 0 1 2 3 4 2", "city", "Huế", "Hà Nội", "x\ty")
        val fields = PdfFormField.parse(0, parts)
        assertEquals(2, fields.size)
        assertEquals("na\tme", fields[0].name)
        assertEquals("a\tb 😀\u001f", fields[0].value)
        assertEquals(listOf("Hà Nội", "x\ty"), fields[1].options)
        assertEquals(RectF(1f, 2f, 3f, 4f), fields[1].rect)
    }

    @Test
    fun notesAndShapesAreSaved() = runBlocking {
        val src = textPdf("annot_src.pdf", listOf(listOf("Annotate me")))
        val out = file("annot_out.pdf")
        withDoc(src) { doc ->
            pdfium.openPage(doc, 0)
            assertTrue(pdfium.addNote(doc, 0, 100f, 700f, 0xFFFFC107.toInt(), "Xem lại đoạn này", "n1"))
            for ((kind, name) in listOf(0 to "s0", 1 to "s1")) {
                assertTrue(name, pdfium.addShape(doc, 0, kind, floatArrayOf(100f, 500f, 300f, 400f), Color.RED, 2f, 0x3300FF00, name))
            }
            // flat line and arrow, left to right
            assertTrue(pdfium.addShape(doc, 0, 2, floatArrayOf(100f, 200f, 300f, 200f), Color.RED, 2f, 0, "s2"))
            assertTrue(pdfium.addShape(doc, 0, 3, floatArrayOf(100f, 150f, 300f, 150f), Color.RED, 2f, 0, "s3"))
            val picture = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            assertTrue(pdfium.addImage(doc, 0, RectF(300f, 300f, 380f, 260f), picture, "p1"))
            assertTrue(pdfium.saveAsCopy(doc, out.path))
        }
        withDoc(out) { doc ->
            pdfium.openPage(doc, 0)
            val annots = pdfium.getAnnotations(doc, 0)
            assertEquals(listOf("n1", "s0", "s1", "s2", "s3", "p1"), annots.map { it.name })
            val note = annots.first { it.name == "n1" }
            assertEquals("a Text annotation", 1, note.subtype)
            assertEquals("Xem lại đoạn này", pdfium.getAnnotContents(doc, 0, note.index))
            assertTrue(pdfium.setAnnotContents(doc, 0, note.index, "Đã sửa"))
            assertEquals("Đã sửa", pdfium.getAnnotContents(doc, 0, note.index))
            // the arrow head widens the bounds past the line's end
            val arrow = annots.first { it.name == "s3" }.rect
            val line = annots.first { it.name == "s2" }.rect
            assertTrue("arrow head $arrow vs $line", arrow.top - arrow.bottom > (line.top - line.bottom) * 2)
        }
        // the shapes are drawn: red pixels where the rectangle's edge is
        val page = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        withDoc(out) { doc -> assertTrue(pdfium.renderPageBitmapOnce(doc, page, 0, renderAnnot = true)) }
        val edge = page.getPixel(100, 842 - 450)
        assertTrue("rectangle edge drawn: ${Integer.toHexString(edge)}", Color.red(edge) > 180 && Color.green(edge) < 120)
    }

    @Test
    fun renderPagesForPictures() = runBlocking {
        val src = textPdf("render_src.pdf", listOf(listOf("One"), listOf("Two")))
        val sizes = ArrayList<Pair<Int, Int>>()
        tools.renderPages(PdfSource(src), listOf(0, 1), 1000) { index, bitmap ->
            sizes += bitmap.width to bitmap.height
            assertNotNull(index)
        }
        assertEquals(listOf(1000 to 1415, 1000 to 1415), sizes)
    }
}
