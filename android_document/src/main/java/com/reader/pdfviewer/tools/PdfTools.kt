/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.reader.pdfviewer.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import com.reader.pdfviewer.convert.Box
import com.reader.pdfviewer.convert.DocxWriter
import com.reader.pdfviewer.convert.FontNames
import com.reader.pdfviewer.convert.LayoutPage
import com.reader.pdfviewer.convert.PageLayout
import com.reader.pdfviewer.convert.PictureBox
import com.reader.pdfviewer.convert.TextLine
import com.reader.pdfviewer.convert.TextRows
import com.reader.pdfviewer.convert.TextStyle
import com.reader.pdfviewer.pdfium.PdfDocument
import com.reader.pdfviewer.pdfium.PdfPageText
import com.reader.pdfviewer.pdfium.PdfiumCore
import com.reader.pdfviewer.search.DocumentTextIndex
import com.reader.pdfviewer.search.PageOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** A PDF on disk, with its password when it is encrypted. */
data class PdfSource(val file: File, val password: String? = null)

/** Thrown when [source] cannot be opened; [cause] is a PdfPasswordException when it needs a password. */
class PdfSourceException(val source: PdfSource, cause: Throwable) :
    IOException("Cannot open ${source.file.name}", cause)

/**
 * Split, merge, image to PDF and text extraction (with OCR for scans) on top of pdfium.
 *
 * Threading: every call runs on one background thread shared by all instances ([dispatcher]),
 * never on the caller's thread. pdfium is not thread safe, so each native step takes the global
 * PdfiumCore lock; the steps are kept short (open one file, import one file, add one page, save)
 * so the reader can still render between them. Calls are cancellable between steps, and outputs
 * are written to a temp file first, so a failed or cancelled call never leaves a broken file.
 */
class PdfTools(context: Context) {

    private val appContext = context.applicationContext
    private val pdfium = PdfiumCore(appContext)

    /** Only used on [dispatcher]; the ML Kit client is created on first OCR and kept for the app's life. */
    private val ocr = PageOcr()

    /** Pages [pages] (zero based, in that order) of [source] into a new PDF at [output]. */
    suspend fun split(source: PdfSource, pages: IntArray, output: File) = withContext(dispatcher) {
        require(pages.isNotEmpty()) { "No pages to split" }
        val src = open(source)
        try {
            extract(src, pages, output)
        } finally {
            pdfium.closeDocument(src)
        }
    }

    /**
     * All pages of [sources], in order, into one PDF at [output].
     * [onProgress] is called on the tools thread after each source with (done, total).
     */
    suspend fun merge(
        sources: List<PdfSource>,
        output: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(dispatcher) {
        require(sources.isNotEmpty()) { "No files to merge" }
        val dest = pdfium.newEmptyDocument()
        try {
            sources.forEachIndexed { index, source ->
                currentCoroutineContext().ensureActive()
                val src = open(source)
                try {
                    if (!pdfium.importPages(dest, src)) throw IOException("Cannot copy pages of ${source.file.name}")
                } finally {
                    // Imported pages are copies, so each source is released right away.
                    pdfium.closeDocument(src)
                }
                onProgress(index + 1, sources.size)
            }
            save(dest, output)
        } finally {
            pdfium.closeDocument(dest)
        }
    }

    /**
     * A new PDF at [output] with one page per image added by [block], which runs on the tools
     * thread, so it may decode bitmaps synchronously. At least one page must be added.
     * No OCR is done here: pages stay plain images, the reader recognizes them when searching.
     */
    suspend fun createFromImages(output: File, block: suspend ImagePageWriter.() -> Unit) = withContext(dispatcher) {
        val doc = pdfium.newEmptyDocument()
        try {
            val writer = ImagePageWriter(pdfium, doc)
            writer.block()
            if (writer.pageCount == 0) throw IOException("No pages added")
            save(doc, output)
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    /**
     * Text with character boxes of one page of [source]; null when the page cannot be read.
     * With [allowOcr], a page without a usable text layer (a scan) is recognized with OCR instead.
     */
    suspend fun pageText(source: PdfSource, pageIndex: Int, allowOcr: Boolean = true): PdfPageText? =
        withContext(dispatcher) {
            val src = open(source)
            try {
                textOf(src, pageIndex, allowOcr)
            } finally {
                pdfium.closeDocument(src)
            }
        }

    /**
     * Text with character boxes of every page of [source], handed to [onPage] one page at a time
     * so large documents are never held in memory at once. Stops early when cancelled.
     * With [allowOcr], pages without a usable text layer are recognized with OCR.
     */
    suspend fun readText(
        source: PdfSource,
        allowOcr: Boolean = true,
        onPage: suspend (PdfPageText) -> Unit,
    ) = withContext(dispatcher) {
        val src = open(source)
        try {
            for (index in 0 until pdfium.getPageCount(src)) {
                currentCoroutineContext().ensureActive()
                textOf(src, index, allowOcr)?.let { onPage(it) }
            }
        } finally {
            pdfium.closeDocument(src)
        }
    }

    /** The text layer, or OCR of the rendered page when the layer has no usable text. */
    private suspend fun textOf(doc: PdfDocument, pageIndex: Int, allowOcr: Boolean): PdfPageText? {
        val layer = pdfium.getPageTextLayout(doc, pageIndex) ?: return null
        return if (allowOcr) withOcr(doc, pageIndex, layer) else layer
    }

    /** [layer], or OCR of the rendered page when [layer] has no usable text. */
    private suspend fun withOcr(doc: PdfDocument, pageIndex: Int, layer: PdfPageText): PdfPageText {
        if (!DocumentTextIndex.needsOcr(layer.text)) return layer
        val bitmap = renderForOcr(doc, pageIndex, layer.pageWidth, layer.pageHeight) ?: return layer
        return try {
            // Recognition runs on ML Kit's own threads, without holding the pdfium lock.
            PdfPageText.fromOcr(pageIndex, layer.pageWidth, layer.pageHeight, ocr.recognize(bitmap))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed on page $pageIndex", e)
            layer
        } finally {
            bitmap.recycle()
        }
    }

    /** Same size as the reader's search OCR, sharp enough for body text. */
    private fun renderForOcr(doc: PdfDocument, pageIndex: Int, pageWidth: Float, pageHeight: Float): Bitmap? {
        if (pageWidth <= 0f || pageHeight <= 0f) return null
        var width = OCR_RENDER_WIDTH
        var height = (width * pageHeight / pageWidth).toInt()
        if (height > OCR_RENDER_MAX_HEIGHT) {
            height = OCR_RENDER_MAX_HEIGHT
            width = (height * pageWidth / pageHeight).toInt()
        }
        if (width <= 0 || height <= 0) return null
        val bitmap = try {
            createBitmap(width, height)
        } catch (e: OutOfMemoryError) {
            return null
        }
        if (!pdfium.renderPageBitmapOnce(doc, bitmap, pageIndex)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun open(source: PdfSource): PdfDocument {
        val fd = try {
            ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw PdfSourceException(source, e)
        }
        return try {
            pdfium.newDocument(fd, source.password)
        } catch (e: Throwable) {
            fd.close()
            throw PdfSourceException(source, e)
        }
    }

    private suspend fun extract(src: PdfDocument, pages: IntArray, output: File) {
        val dest = pdfium.newEmptyDocument()
        try {
            if (!pdfium.importPages(dest, src, pages)) throw IOException("Cannot copy pages")
            save(dest, output)
        } finally {
            pdfium.closeDocument(dest)
        }
    }

    private suspend fun save(doc: PdfDocument, output: File) {
        currentCoroutineContext().ensureActive()
        val temp = File(output.parentFile, "${output.name}.tmp")
        try {
            if (!pdfium.saveAsCopy(doc, temp.path)) throw IOException("Cannot write ${output.name}")
            if (!temp.renameTo(output)) throw IOException("Cannot move ${temp.name} to ${output.name}")
        } finally {
            temp.delete()
        }
    }

    // ---- whole document edits: each writes a new file at output, the source is not changed ----

    /**
     * One page of an organized document: page [page] of source [source] turned [quarterTurns]
     * more clockwise, or, with a negative [source], an empty page of [blankWidth] x [blankHeight].
     */
    data class PageRef(
        val source: Int,
        val page: Int,
        val quarterTurns: Int = 0,
        val blankWidth: Float = A4_WIDTH,
        val blankHeight: Float = A4_HEIGHT,
    )

    /** A document made of [pages], in that order, taken from [sources] (see [PageRef]). */
    suspend fun organize(sources: List<PdfSource>, pages: List<PageRef>, output: File) = withContext(dispatcher) {
        require(pages.isNotEmpty()) { "No pages" }
        val opened = ArrayList<PdfDocument>()
        val dest = pdfium.newEmptyDocument()
        try {
            for (source in sources) opened += open(source)
            for (ref in pages) {
                currentCoroutineContext().ensureActive()
                val ok = if (ref.source < 0) pdfium.insertBlankPage(dest, -1, ref.blankWidth, ref.blankHeight)
                else pdfium.importPages(dest, opened[ref.source], intArrayOf(ref.page))
                if (!ok) throw IOException("Cannot add page ${ref.page + 1}")
                if (ref.quarterTurns % 4 != 0) {
                    val index = pdfium.getPageCount(dest) - 1
                    pdfium.setPageRotation(dest, index, pdfium.getPageRotation(dest, index) + ref.quarterTurns)
                }
            }
            save(dest, output)
        } finally {
            pdfium.closeDocument(dest)
            opened.forEach { pdfium.closeDocument(it) }
        }
    }

    /**
     * A copy of [source] whose scanned pages (pages without a text layer) get their text, found
     * by OCR, as invisible words over the picture: the text can then be found, selected and
     * copied in any PDF reader. Only [only] (page indexes) are looked at when given: OCR of a long
     * document takes long. Returns the number of pages recognized.
     */
    suspend fun addTextLayer(source: PdfSource, output: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
                             only: Collection<Int>? = null): Int =
        withContext(dispatcher) {
            val doc = open(source)
            try {
                val count = pdfium.getPageCount(doc)
                val indexes = only?.filter { it in 0 until count }?.distinct()?.sorted() ?: (0 until count).toList()
                val pages = ArrayList<PageWords>()
                for ((done, index) in indexes.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val layer = pdfium.getPageTextLayout(doc, index)
                    if (layer != null && DocumentTextIndex.needsOcr(layer.text)) {
                        val bitmap = renderForOcr(doc, index, layer.pageWidth, layer.pageHeight)
                        if (bitmap != null) {
                            val lines = try { ocr.recognize(bitmap) } finally { bitmap.recycle() }
                            pages += PageWords(index, lines, null)
                        }
                    }
                    onProgress(done + 1, indexes.size)
                }
                val recognized = addWords(doc, pages)
                save(doc, output)
                recognized
            } finally {
                pdfium.closeDocument(doc)
            }
        }

    /** The OCR lines of page [index] to put in as invisible text, leaving out the words in [skip]. */
    private class PageWords(val index: Int, val lines: List<com.reader.pdfviewer.search.OcrLine>, val skip: List<android.graphics.RectF>?) {
        fun kept(word: com.reader.pdfviewer.search.OcrWord) = skip?.any { android.graphics.RectF.intersects(it, word.box) } != true
    }

    /**
     * Puts the words of [pages] in as invisible text, all with one font that has just their
     * characters (those of skipped words stay out of the file). Returns the number of pages that got text.
     */
    private fun addWords(doc: PdfDocument, pages: List<PageWords>): Int {
        if (pages.isEmpty()) return 0
        val text = StringBuilder()
        for (page in pages) for (line in page.lines) for (word in line.words) if (page.kept(word)) text.append(line.text, word.start, word.end)
        // the spaces between the words
        text.append(' ')
        val font = unicodeFont(text.toString())
        return pages.count { addWords(doc, it, font) }
    }

    /** OCR words of a page (boxes relative to the page as shown) put in as invisible text. */
    private fun addWords(doc: PdfDocument, page: PageWords, font: String?): Boolean {
        val index = page.index
        val lines = page.lines
        val words = ArrayList<String>()
        val boxes = ArrayList<Float>()
        val size = pdfium.getPagePointSize(doc, index) ?: return false
        // a grid 100 times the page size keeps 0.01 point precision through the int mapping
        val gw = size.width * 100
        val gh = size.height * 100
        pdfium.openPage(doc, index)
        try {
            for (line in lines) {
                val kept = line.words.filter { page.kept(it) }
                for ((i, word) in kept.withIndex()) {
                val box = word.box
                val a = pdfium.deviceToPageCoords(doc, index, 0, 0, gw, gh, 0, (box.left * gw).toInt(), (box.top * gh).toInt()) ?: continue
                val b = pdfium.deviceToPageCoords(doc, index, 0, 0, gw, gh, 0, (box.right * gw).toInt(), (box.bottom * gh).toInt()) ?: continue
                // a space after each word but the last of its line: readers take the words apart
                // (search, copy, read aloud); the word itself still fills its box
                words += line.text.substring(word.start, word.end) + if (i < kept.lastIndex) " " else ""
                boxes += minOf(a.x, b.x); boxes += minOf(a.y, b.y); boxes += maxOf(a.x, b.x); boxes += maxOf(a.y, b.y)
                }
            }
        } finally {
            pdfium.closePage(doc, index)
        }
        if (words.isEmpty()) return false
        return pdfium.addInvisibleWords(doc, index, words.toTypedArray(), boxes.toFloatArray(), font, pdfium.getPageRotation(doc, index))
    }

    /** How much [compress] shrinks pictures: those shown at more than [dpi] are scaled down, JPEG [quality]. */
    enum class Compression(val dpi: Int, val quality: Int) { LOW(200, 85), MEDIUM(144, 72), HIGH(96, 55) }

    /**
     * A copy of [source] with its pictures stored smaller: scaled down to [level]'s resolution
     * where they are shown and saved as JPEG, only when that is smaller than what they were.
     * Transparent pictures are kept as they are. Returns the number of pictures changed.
     */
    suspend fun compress(source: PdfSource, output: File, level: Compression,
                         onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int = withContext(dispatcher) {
        val doc = open(source)
        try {
            val count = pdfium.getPageCount(doc)
            var changed = 0
            for (index in 0 until count) {
                for (image in pdfium.getPageImages(doc, index)) {
                    currentCoroutineContext().ensureActive()
                    if (image.pixelWidth.toLong() * image.pixelHeight > MAX_COMPRESS_PIXELS) continue
                    val scale = minOf(1f,
                        level.dpi * image.shownWidth / 72f / image.pixelWidth,
                        level.dpi * image.shownHeight / 72f / image.pixelHeight).coerceAtLeast(0.05f)
                    val jpeg = recompress(doc, index, image, scale, level.quality) ?: continue
                    if (jpeg.size < image.storedBytes * 0.9 && pdfium.replaceImage(doc, index, image, jpeg)) changed++
                }
                onProgress(index + 1, count)
            }
            save(doc, output)
            changed
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    private fun recompress(doc: PdfDocument, page: Int, image: PdfiumCore.PageImage, scale: Float, quality: Int): ByteArray? {
        val full = try { createBitmap(image.pixelWidth, image.pixelHeight) } catch (e: OutOfMemoryError) { return null }
        try {
            if (!pdfium.getImagePixels(doc, page, image, full)) return null
            val w = maxOf(1, (image.pixelWidth * scale).toInt())
            val h = maxOf(1, (image.pixelHeight * scale).toInt())
            val scaled = if (scale < 0.95f) Bitmap.createScaledBitmap(full, w, h, true) else full
            try {
                return ByteArrayOutputStream().use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                    out.toByteArray()
                }
            } finally {
                if (scaled !== full) scaled.recycle()
            }
        } finally {
            full.recycle()
        }
    }

    /** Where a [TextStamp] goes on the page as it is shown. */
    enum class StampPosition { CENTER, TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

    /**
     * Text put on pages: a watermark (CENTER, turned [angle] degrees), a page number or a
     * header / footer. [text] gets the page number (1 based) and the page count.
     */
    class TextStamp(
        val position: StampPosition,
        val size: Float,
        val color: Int,
        val angle: Float = 0f,
        val margin: Float = 28f,
        val text: (page: Int, count: Int) -> String,
    )

    /** A copy of [source] with [stamps] on each page of [pages] (zero based; null for all). */
    suspend fun stamp(source: PdfSource, output: File, stamps: List<TextStamp>, pages: Set<Int>? = null,
                      onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) = withContext(dispatcher) {
        require(stamps.isNotEmpty()) { "Nothing to add" }
        val doc = open(source)
        try {
            val count = pdfium.getPageCount(doc)
            // one font with just the characters of every text put on the pages
            val all = StringBuilder()
            for (index in 0 until count) if (pages == null || index in pages) for (stamp in stamps) all.append(stamp.text(index + 1, count))
            val font = unicodeFont(all.toString())
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                if (pages != null && index !in pages) continue
                val size = pdfium.getPagePointSize(doc, index) ?: continue
                val turns = pdfium.getPageRotation(doc, index)
                for (stamp in stamps) {
                    val text = stamp.text(index + 1, count).takeIf { it.isNotBlank() } ?: continue
                    val w = size.width.toFloat(); val h = size.height.toFloat(); val m = stamp.margin
                    // the anchor on the page as shown (origin top left), then into page space
                    val (x, y, anchor) = when (stamp.position) {
                        StampPosition.CENTER -> Triple(w / 2, h / 2, 1)
                        StampPosition.TOP_LEFT -> Triple(m, m + stamp.size * 0.8f, 0)
                        StampPosition.TOP_CENTER -> Triple(w / 2, m + stamp.size * 0.8f, 1)
                        StampPosition.TOP_RIGHT -> Triple(w - m, m + stamp.size * 0.8f, 2)
                        StampPosition.BOTTOM_LEFT -> Triple(m, h - m, 0)
                        StampPosition.BOTTOM_CENTER -> Triple(w / 2, h - m, 1)
                        StampPosition.BOTTOM_RIGHT -> Triple(w - m, h - m, 2)
                    }
                    pdfium.openPage(doc, index)
                    val at = try {
                        pdfium.deviceToPageCoords(doc, index, 0, 0, size.width * 10, size.height * 10, 0, (x * 10).toInt(), (y * 10).toInt())
                    } finally {
                        pdfium.closePage(doc, index)
                    } ?: continue
                    // a page turned clockwise shows its content turned: turn the text the other way
                    val angle = stamp.angle + turns * 90f
                    val centered = if (stamp.position == StampPosition.CENTER) 1 else anchor
                    if (pdfium.addPageText(doc, index, text, stamp.size, stamp.color, at.x, at.y, angle, centered, font) == null)
                        throw IOException("Cannot add text to page ${index + 1}")
                }
                onProgress(index + 1, count)
            }
            save(doc, output)
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    /**
     * A copy of [source] where [areas] (per page, boxes relative to the page as shown, 0..1) are
     * blacked out for good: those pages become pictures with the boxes painted over, so nothing
     * of what was under them is left in the file; the rest of their text is recognized again
     * (OCR) and kept, invisible, so it can still be found. Other pages are copied as they are.
     */
    suspend fun redact(source: PdfSource, output: File, areas: Map<Int, List<android.graphics.RectF>>,
                       onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) = withContext(dispatcher) {
        require(areas.values.any { it.isNotEmpty() }) { "Nothing to black out" }
        val src = open(source)
        val dest = pdfium.newEmptyDocument()
        try {
            val count = pdfium.getPageCount(src)
            val words = ArrayList<PageWords>()
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                val boxes = areas[index].orEmpty()
                if (boxes.isEmpty()) {
                    if (!pdfium.importPages(dest, src, intArrayOf(index))) throw IOException("Cannot copy page ${index + 1}")
                } else {
                    val size = pdfium.getPagePointSize(src, index) ?: throw IOException("Cannot read page ${index + 1}")
                    val scale = REDACT_DPI / 72f
                    val bitmap = createBitmap(maxOf(1, (size.width * scale).toInt()), maxOf(1, (size.height * scale).toInt()))
                    try {
                        if (!pdfium.renderPageBitmapOnce(src, bitmap, index, renderAnnot = true)) throw IOException("Cannot draw page ${index + 1}")
                        val canvas = Canvas(bitmap)
                        val black = android.graphics.Paint().apply { color = Color.BLACK }
                        for (b in boxes) canvas.drawRect(b.left * bitmap.width, b.top * bitmap.height, b.right * bitmap.width, b.bottom * bitmap.height, black)
                        val jpeg = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out); out.toByteArray() }
                        if (!pdfium.addJpegPage(dest, jpeg, size.width.toFloat(), size.height.toFloat())) throw IOException("Cannot add page ${index + 1}")
                        val lines = try { ocr.recognize(bitmap) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
                        words += PageWords(pdfium.getPageCount(dest) - 1, lines, boxes)
                    } finally {
                        bitmap.recycle()
                    }
                }
                onProgress(index + 1, count)
            }
            addWords(dest, words)
            save(dest, output)
        } finally {
            pdfium.closeDocument(dest)
            pdfium.closeDocument(src)
        }
    }

    /** A copy of [source] (opened with its password) that opens without one. */
    suspend fun removePassword(source: PdfSource, output: File) = withContext(dispatcher) {
        val doc = open(source)
        try {
            currentCoroutineContext().ensureActive()
            val temp = File(output.parentFile, "${output.name}.tmp")
            try {
                if (!pdfium.saveWithoutSecurity(doc, temp.path)) throw IOException("Cannot write ${output.name}")
                if (!temp.renameTo(output)) throw IOException("Cannot move ${temp.name} to ${output.name}")
            } finally {
                temp.delete()
            }
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    /**
     * A copy of [source] that asks for [userPassword] to open (AES 256). [ownerPassword] unlocks
     * the rights left out (printing, copying text); when null a random one is used, so those
     * limits hold for whoever opens the file with the user password.
     */
    suspend fun setPassword(source: PdfSource, output: File, userPassword: String, ownerPassword: String? = null,
                            allowPrint: Boolean = true, allowCopy: Boolean = true) = withContext(dispatcher) {
        require(userPassword.isNotEmpty()) { "Empty password" }
        val plain = File(output.parentFile, "${output.name}.plain")
        val temp = File(output.parentFile, "${output.name}.tmp")
        try {
            // pdfium writes the document without its old protection, PdfEncryptor encrypts it
            val doc = open(source)
            try {
                if (!pdfium.saveWithoutSecurity(doc, plain.path)) throw IOException("Cannot write ${output.name}")
            } finally {
                pdfium.closeDocument(doc)
            }
            val job = currentCoroutineContext().job
            job.ensureActive()
            var rights = PdfEncryptor.ALL
            if (!allowPrint) rights = rights and (PdfEncryptor.PRINT or PdfEncryptor.PRINT_HIGH).inv()
            if (!allowCopy) rights = rights and PdfEncryptor.COPY.inv()
            val owner = ownerPassword ?: java.util.UUID.randomUUID().toString()
            PdfEncryptor.encrypt(plain, temp, userPassword, owner, rights) { !job.isActive }
            if (!temp.renameTo(output)) throw IOException("Cannot move ${temp.name} to ${output.name}")
        } finally {
            plain.delete()
            temp.delete()
        }
    }

    /** A copy of [source] whose annotations and form fields are part of the pages (no longer editable). */
    suspend fun flatten(source: PdfSource, output: File) = withContext(dispatcher) {
        val doc = open(source)
        try {
            for (index in 0 until pdfium.getPageCount(doc)) {
                currentCoroutineContext().ensureActive()
                pdfium.flattenPage(doc, index)
            }
            save(doc, output)
        } finally {
            pdfium.closeDocument(doc)
        }
    }

    /**
     * [source] as a Word document at [output]: the text of each page (recognized with OCR on
     * scans) as paragraphs with their size, alignment, indents, weight, slant, color and font, its
     * pictures in between, drawings with small labels as pictures, each PDF page on a new page. A
     * page with neither text nor pictures is added as a picture of the whole page. Tables are not
     * rebuilt. Pages go to disk one at a time, so any number of pages fits in memory.
     */
    suspend fun toWord(source: PdfSource, output: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) =
        withContext(dispatcher) {
            val doc = open(source)
            try {
                DocxWriter(appContext.cacheDir).use { writer ->
                    val count = pdfium.getPageCount(doc)
                    for (index in 0 until count) {
                        currentCoroutineContext().ensureActive()
                        val (page, images) = wordPage(doc, index)
                        writer.addPage(page, images)
                        onProgress(index + 1, count)
                    }
                    currentCoroutineContext().ensureActive()
                    writer.save(output)
                    count
                }
            } finally {
                pdfium.closeDocument(doc)
            }
        }

    /** Page [index] rebuilt for Word, with the pictures its blocks point to. */
    private suspend fun wordPage(doc: PdfDocument, index: Int): Pair<LayoutPage, Map<Int, DocxWriter.Image>> {
        val page = pdfium.getPageForWord(doc, index)
        val text = page?.let { withOcr(doc, index, it.text) }
        val size = if (text == null) pdfium.getPagePointSize(doc, index) else null
        val width = text?.pageWidth ?: size?.width?.toFloat() ?: A4_WIDTH
        val height = text?.pageHeight ?: size?.height?.toFloat() ?: A4_HEIGHT
        val heightToSize = if (text?.isOcr == true) PageLayout.OCR_LINE_HEIGHT else PageLayout.PDF_LINE_HEIGHT
        var lines = if (text == null) emptyList() else wordLines(text, page.styles.takeIf { !text.isOcr })
        val images = HashMap<Int, DocxWriter.Image>()
        var boxes = ArrayList<PictureBox>()
        for (image in page?.images.orEmpty()) {
            currentCoroutineContext().ensureActive()
            val b = image.bounds
            val picture = pictureForWord(doc, index, image) ?: continue
            images[boxes.size] = picture
            boxes += PictureBox(boxes.size, b.left, b.top, b.right, b.bottom)
        }
        // drawings with small labels (screenshots, diagrams) become one picture each
        val figures = PageLayout.figureAreas(width, height, lines, boxes, heightToSize)
        if (figures.isNotEmpty()) {
            val drawn = figurePictures(doc, index, width, height, figures)
            lines = lines.filter { l -> figures.none { it.contains((l.left + l.right) / 2, (l.top + l.bottom) / 2) } }
            boxes = ArrayList(boxes.filter { p -> figures.none { it.intersects(Box(p.left, p.top, p.right, p.bottom)) } })
            drawn.forEach { (area, picture) ->
                val key = FIGURE_KEYS + boxes.size
                images[key] = picture
                boxes += PictureBox(key, area.left, area.top, area.right, area.bottom)
            }
        }
        if (lines.isEmpty() && boxes.isEmpty()) {
            wholePage(doc, index, width, height)?.let {
                images[0] = DocxWriter.Image(it, "jpeg")
                boxes += PictureBox(0, 0f, 0f, width, height)
            }
        }
        return PageLayout.build(width, height, lines, boxes, heightToSize) to images
    }

    /** Lines of a page for Word, from its characters, with the size, weight, slant, color and font of each. */
    private fun wordLines(text: PdfPageText, info: PdfiumCore.TextStyles?): List<TextLine> {
        val n = text.charCount
        val codePoints = IntArray(n) { text.charCodePoint(it) }
        val boxes = FloatArray(n * 4)
        for (i in 0 until n) text.charBox(i)?.let {
            boxes[i * 4] = it.left; boxes[i * 4 + 1] = it.top; boxes[i * 4 + 2] = it.right; boxes[i * 4 + 3] = it.bottom
        }
        val styles = info?.takeIf { it.sizes.size == n }?.let { s ->
            val cache = HashMap<TextStyle, TextStyle>()
            // Some PDFs scale all text with the page matrix, which pdfium's size leaves out: then
            // every size is off from its box by one factor, found as the median of the page.
            val ratios = (0 until n).mapNotNull { i ->
                val h = boxes[i * 4 + 3] - boxes[i * 4 + 1]
                if (h > 0f && s.sizes[i] > 0f) s.sizes[i] / h else null
            }.sorted()
            val ratio = ratios.getOrNull(ratios.size / 2)
            val factor = if (ratio == null || ratio in 0.6f..1.1f) 1f else ratio / (1f / PageLayout.PDF_LINE_HEIGHT)
            Array<TextStyle?>(n) { i ->
                val raw = s.sizes[i] / factor
                val size = if (raw > 0f) TextStyle.roundSize(raw) else 0f
                val font = s.font(i)
                val color = s.colors[i].let { if (it == 0) -1 else it and 0xFFFFFF }
                val style = TextStyle(size, s.weight(i) >= 600 || s.forceBold(i) || FontNames.isBold(font),
                    s.italic(i) || FontNames.isItalic(font), color, FontNames.family(font))
                cache.getOrPut(style) { style }
            }
        }
        return TextRows.build(codePoints, boxes, styles)
    }

    /**
     * A picture of a page for Word, at most [WORD_PICTURE_SIDE] pixels on its long side: JPEG when
     * it is opaque, PNG to keep its transparency. pdfium draws a picture as shown (mask applied)
     * only at its size on the page, so that drawing just tells whether it is transparent and gives
     * the mask; the colors come from the picture's own pixels, at their full resolution.
     */
    private fun pictureForWord(doc: PdfDocument, page: Int, image: PdfiumCore.PageImage): DocxWriter.Image? {
        if (image.pixelWidth.toLong() * image.pixelHeight > MAX_COMPRESS_PIXELS) return null
        val shown = pdfium.getRenderedImage(doc, page, image, MAX_COMPRESS_PIXELS.toInt())
        try {
            val transparent = shown != null && !isOpaque(shown)
            val full = try { createBitmap(image.pixelWidth, image.pixelHeight) } catch (e: OutOfMemoryError) { null }
            val own = full?.takeIf { pdfium.getImagePixels(doc, page, image, it) }
            if (own == null) full?.recycle()
            // the shown picture is turned or cut: only it is right
            val sameShape = shown != null && own != null &&
                kotlin.math.abs(shown.width.toFloat() / shown.height - own.width.toFloat() / own.height) < 0.02f * own.width / own.height
            val picture = when {
                own != null && !transparent -> own
                own != null && sameShape -> own.also { withAlphaOf(it, shown!!) }
                else -> { own?.recycle(); shown ?: return null }
            }
            try {
                val side = maxOf(picture.width, picture.height)
                val scaled = if (side > WORD_PICTURE_SIDE) Bitmap.createScaledBitmap(picture, maxOf(1, picture.width * WORD_PICTURE_SIDE / side),
                    maxOf(1, picture.height * WORD_PICTURE_SIDE / side), true) else picture
                try {
                    return ByteArrayOutputStream().use { out ->
                        val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        if (!scaled.compress(format, if (transparent) 100 else WORD_JPEG_QUALITY, out)) return null
                        DocxWriter.Image(out.toByteArray(), if (transparent) "png" else "jpeg")
                    }
                } finally {
                    if (scaled !== picture) scaled.recycle()
                }
            } finally {
                if (picture !== shown) picture.recycle()
            }
        } finally {
            shown?.recycle()
        }
    }

    private fun isOpaque(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr 24 != 0xFF }) return false
        }
        return true
    }

    /** Gives [target] the transparency of [mask], stretched to its size. */
    private fun withAlphaOf(target: Bitmap, mask: Bitmap) {
        val stretched = Bitmap.createScaledBitmap(mask, target.width, target.height, true)
        try {
            val row = IntArray(target.width)
            val alpha = IntArray(target.width)
            for (y in 0 until target.height) {
                target.getPixels(row, 0, target.width, 0, y, target.width, 1)
                stretched.getPixels(alpha, 0, target.width, 0, y, target.width, 1)
                for (x in row.indices) row[x] = (alpha[x] and 0xFF000000.toInt()) or (row[x] and 0xFFFFFF)
                target.setPixels(row, 0, target.width, 0, y, target.width, 1)
            }
        } finally {
            if (stretched !== mask) stretched.recycle()
        }
    }

    /** [areas] of a page drawn as JPEG pictures, from one rendering of the page. */
    private fun figurePictures(doc: PdfDocument, page: Int, width: Float, height: Float, areas: List<Box>): List<Pair<Box, DocxWriter.Image>> {
        if (width <= 0f || height <= 0f) return emptyList()
        val scale = FIGURE_DPI / 72f
        val w = minOf((width * scale).toInt(), FIGURE_MAX_WIDTH)
        val s = w / width
        val h = maxOf(1, (height * s).toInt())
        val bitmap = try { createBitmap(w, h) } catch (e: OutOfMemoryError) { return emptyList() }
        try {
            bitmap.eraseColor(Color.WHITE)
            if (!pdfium.renderPageBitmapOnce(doc, bitmap, page, renderAnnot = true)) return emptyList()
            return areas.mapNotNull { a ->
                val x = (a.left * s).toInt().coerceIn(0, w - 1)
                val y = (a.top * s).toInt().coerceIn(0, h - 1)
                val cw = ((a.right * s).toInt() - x).coerceIn(1, w - x)
                val ch = ((a.bottom * s).toInt() - y).coerceIn(1, h - y)
                val crop = Bitmap.createBitmap(bitmap, x, y, cw, ch)
                try {
                    ByteArrayOutputStream().use { out ->
                        if (!crop.compress(Bitmap.CompressFormat.JPEG, WORD_JPEG_QUALITY, out)) null
                        else a to DocxWriter.Image(out.toByteArray(), "jpeg")
                    }
                } finally {
                    if (crop !== bitmap) crop.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** The whole page drawn as a JPEG picture, for pages made only of drawings. */
    private fun wholePage(doc: PdfDocument, page: Int, width: Float, height: Float): ByteArray? {
        if (width <= 0f || height <= 0f) return null
        val w = WORD_PICTURE_SIDE
        val h = maxOf(1, (w * height / width).toInt())
        val bitmap = try { createBitmap(w, h) } catch (e: OutOfMemoryError) { return null }
        try {
            bitmap.eraseColor(Color.WHITE)
            if (!pdfium.renderPageBitmapOnce(doc, bitmap, page, renderAnnot = true)) return null
            return ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, WORD_JPEG_QUALITY, out)) return null
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Number of pages of [source]. */
    suspend fun pageCount(source: PdfSource): Int = withContext(dispatcher) {
        val doc = open(source)
        try { pdfium.getPageCount(doc) } finally { pdfium.closeDocument(doc) }
    }

    /**
     * Pages [pages] of [source] drawn [width] pixels wide (annotations included), handed one at a
     * time to [onPage] with their index; the bitmap is recycled when it returns.
     */
    suspend fun renderPages(source: PdfSource, pages: List<Int>, width: Int, onPage: suspend (index: Int, bitmap: Bitmap) -> Unit) =
        withContext(dispatcher) {
            val doc = open(source)
            try {
                for (index in pages) {
                    currentCoroutineContext().ensureActive()
                    val size = pdfium.getPagePointSize(doc, index) ?: continue
                    if (size.width <= 0 || size.height <= 0) continue
                    val bitmap = createBitmap(width, maxOf(1, (width.toLong() * size.height / size.width).toInt()))
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        if (pdfium.renderPageBitmapOnce(doc, bitmap, index, renderAnnot = true)) onPage(index, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                pdfium.closeDocument(doc)
            }
        }

    private fun unicodeFont(text: String): String? = com.reader.pdfviewer.util.SystemFonts.unicodeFor(appContext, text)

    /** Adds image pages to a PDF being created by [createFromImages]. */
    class ImagePageWriter internal constructor(private val pdfium: PdfiumCore, private val doc: PdfDocument) {
        var pageCount = 0
            private set

        /**
         * Adds [bitmap] as a page [pageWidth] points wide (A4 by default) whose height follows the
         * image's aspect ratio, so there are no blank bands. The caller still owns [bitmap].
         */
        fun addImage(bitmap: Bitmap, pageWidth: Float = A4_WIDTH, jpegQuality: Int = 90) {
            require(bitmap.width > 0 && bitmap.height > 0) { "Empty bitmap" }
            // Encoding happens outside the pdfium lock; only adding the page takes it.
            val jpeg = encodeJpeg(bitmap, jpegQuality)
            val pageHeight = pageWidth * bitmap.height / bitmap.width
            if (!pdfium.addJpegPage(doc, jpeg, pageWidth, pageHeight)) {
                throw IOException("Cannot add page ${pageCount + 1}")
            }
            pageCount++
        }

        private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
            // JPEG has no alpha; flatten on white as a PDF viewer would show it.
            val opaque = if (bitmap.hasAlpha()) {
                createBitmap(bitmap.width, bitmap.height).also {
                    Canvas(it).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
            } else bitmap
            try {
                return ByteArrayOutputStream().use { stream ->
                    if (!opaque.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        throw IOException("Cannot encode page ${pageCount + 1}")
                    }
                    stream.toByteArray()
                }
            } finally {
                if (opaque !== bitmap) opaque.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "PdfTools"
        private const val OCR_RENDER_WIDTH = 1600
        private const val OCR_RENDER_MAX_HEIGHT = 2800

        const val A4_WIDTH = 595f
        const val A4_HEIGHT = 842f
        // 24 million pixels are ~96 MB decoded: bigger pictures are left as they are
        private const val MAX_COMPRESS_PIXELS = 24_000_000L
        private const val REDACT_DPI = 200f
        // pictures in a converted Word document: sharp enough to print, small enough to keep in memory
        private const val WORD_PICTURE_SIDE = 1600
        private const val WORD_JPEG_QUALITY = 85
        // drawings turned into pictures: 200 dpi, the page at most this many pixels wide
        private const val FIGURE_DPI = 200f
        private const val FIGURE_MAX_WIDTH = 2400
        // keys of figure pictures, apart from the page's own pictures
        private const val FIGURE_KEYS = 1000

        /**
         * The one thread all tool work runs on, so tools never take more than one IO thread.
         * Calls only interleave where one suspends; each works on its own documents, so that is safe.
         */
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
