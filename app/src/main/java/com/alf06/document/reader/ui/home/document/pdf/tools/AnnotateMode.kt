package com.alf06.document.reader.ui.home.document.pdf.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.reader.pdfviewer.PDFView
import com.reader.pdfviewer.model.PdfAnnotationInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The annotation tools other than the pen: select (move, resize, delete, read a note), eraser,
 * text, note, rectangle, ellipse, line, arrow, and placing a picture (a signature, an image).
 * PDF coordinates have y up: rectangles given to the PDF view have top > bottom.
 */
internal class AnnotateMode(private val overlay: PdfOverlayView, private val host: Host) : PdfOverlayView.Mode {

    enum class Tool { PEN, SELECT, TEXT, NOTE, RECT, ELLIPSE, LINE, ARROW, PICTURE, ERASER }

    interface Host {
        val color: Int
        val widthPt: Float
        /** Asks the text of a new text box; [done] gets it with its size in points. */
        fun askText(done: (text: String, sizePt: Float) -> Unit)
        /** Asks the text of a new note. */
        fun askNote(done: (text: String) -> Unit)
        /** A tap on an annotation that is not selected: offer what can be done with it. */
        fun annotationTapped(info: PdfAnnotationInfo, movable: Boolean)
        fun changed()
        fun message(text: CharSequence)
    }

    private val pdf: PDFView get() = overlay.pdfView

    var tool = Tool.SELECT
        set(value) {
            field = value
            drag = Drag.NONE
            if (value != Tool.SELECT) selected = null
            onTool?.invoke(value)
            overlay.invalidate()
        }

    /** The tool changed (a placed picture goes back to selecting). */
    var onTool: ((Tool) -> Unit)? = null

    /** A picture waiting for a tap to be placed, with the width it gets in points. */
    private var pending: Pair<Bitmap, Float>? = null

    fun placePicture(bitmap: Bitmap, widthPt: Float) {
        tool = Tool.PICTURE
        pending = bitmap to widthPt
    }

    /** The movable annotation being worked on, see [PDFView.moveAnnotation]. */
    private var selected: String? = null
        set(value) { field = value; overlay.invalidate() }

    fun select(name: String?) { if (tool == Tool.SELECT || name == null) selected = name }

    fun clearSelection() { selected = null }

    private enum class Drag { NONE, MOVE, RESIZE, SHAPE }
    private var drag = Drag.NONE
    private var corner = -1
    private val start = PointF()
    private val now = PointF()
    private var startFrame = RectF()
    private var dragPage = -1

    // whether the selected annotation is still there, asked again only after an edit, undo or redo
    private var aliveName: String? = null
    private var aliveRevision = -1L
    private var alive = false

    private fun selectedFrame(): Pair<Int, RectF>? {
        val name = selected ?: return null
        val bounds = pdf.movableBounds(name) ?: return null
        val page = currentPageOf(name) ?: return null
        if (name != aliveName || pdf.editRevision != aliveRevision) {
            aliveName = name
            aliveRevision = pdf.editRevision
            alive = pdf.getAnnotations(page).any { it.name == name } // an undo can take it off
        }
        if (!alive) return null
        return pdf.pageRectToView(page, normalized(bounds))?.let { page to it }
    }

    // the page of a movable annotation is looked up once, pages only change on undo/redo
    private val pages = HashMap<String, Int>()
    private fun currentPageOf(name: String): Int? = pages[name]

    fun remember(name: String, page: Int) { pages[name] = page }

    override fun onDown(x: Float, y: Float): Boolean {
        start.set(x, y); now.set(x, y)
        rubbed = false
        return when (tool) {
            Tool.PEN -> false
            Tool.SELECT -> {
                val frame = selectedFrame()
                if (frame != null) {
                    val (page, r) = frame
                    corner = overlay.cornerAt(r, x, y)
                    if (corner >= 0 || RectF(r).apply { inset(-overlay.dp(6f), -overlay.dp(6f)) }.contains(x, y)) {
                        drag = if (corner >= 0) Drag.RESIZE else Drag.MOVE
                        startFrame = RectF(r); dragPage = page
                        return true
                    }
                }
                // a tap on another annotation selects it; elsewhere the page scrolls
                val hit = pdf.findAnnotationAt(x, y)
                if (hit == null) { selected = null; return false }
                drag = Drag.NONE
                true
            }
            Tool.RECT, Tool.ELLIPSE, Tool.LINE, Tool.ARROW -> {
                val page = pdf.pageAt(x, y) ?: return false
                dragPage = page; drag = Drag.SHAPE
                true
            }
            else -> pdf.pageAt(x, y) != null
        }
    }

    override fun onMove(x: Float, y: Float) {
        when (drag) {
            Drag.SHAPE -> pdf.pageViewRect(dragPage)?.let { now.set(x.coerceIn(it.left, it.right), y.coerceIn(it.top, it.bottom)) }
            Drag.MOVE, Drag.RESIZE -> now.set(x, y)
            Drag.NONE -> if (tool == Tool.ERASER) {
                rub(now.x, now.y, x, y)
                now.set(x, y)
            }
        }
    }

    // whether the eraser took something off since the finger went down: all of it is one undo step
    private var rubbed = false

    /** The eraser rubs out the pen strokes along the way of the finger, however fast it moves. */
    private fun rub(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val radius = overlay.dp(ERASER_RADIUS)
        val steps = max(1, (Math.hypot((toX - fromX).toDouble(), (toY - fromY).toDouble()) / radius).toInt())
        var erased = false
        for (i in 0..steps) {
            if (pdf.eraseInkAt(fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps, radius, rubbed)) {
                erased = true
                rubbed = true
            }
        }
        if (erased) host.changed()
    }

    /** A tap of the eraser: the pen stroke under it is cut there, any other annotation is taken off. */
    private fun eraseAt(x: Float, y: Float) {
        if (pdf.eraseInkAt(x, y, overlay.dp(ERASER_RADIUS))) return host.changed()
        // a pen stroke is only hit on its line, not anywhere in its bounds
        pdf.findAnnotationAt(x, y)?.takeIf { it.name?.startsWith("pdfview-ink-") != true }?.let {
            pdf.removeAnnotation(it) { removed -> if (removed) host.changed() }
        }
    }

    override fun onUp(x: Float, y: Float, tap: Boolean) {
        val d = drag
        drag = Drag.NONE
        when (tool) {
            Tool.PEN -> {}
            Tool.SELECT -> when {
                d == Drag.MOVE || d == Drag.RESIZE -> if (!tap) commitFrame(d) else if (d == Drag.MOVE) tapSelected(x, y)
                tap -> pdf.findAnnotationAt(x, y)?.let { hit ->
                    val movable = pdf.canMoveAnnotation(hit.name)
                    if (movable) { remember(hit.name!!, hit.page); selected = hit.name }
                    else host.annotationTapped(hit, false)
                }
            }
            Tool.ERASER -> if (tap) eraseAt(x, y)
            Tool.TEXT -> if (tap) pointAt(x, y)?.let { (page, p) ->
                host.askText { text, size ->
                    pdf.addText(page, p.x, p.y, text, size, host.color)?.let { name -> placed(name, page) }
                }
            }
            Tool.NOTE -> if (tap) pointAt(x, y)?.let { (page, p) ->
                host.askNote { text -> pdf.addNote(page, p.x, p.y, text)?.let { host.changed() } }
            }
            Tool.RECT, Tool.ELLIPSE, Tool.LINE, Tool.ARROW -> if (d == Drag.SHAPE && !tap) addShape()
            Tool.PICTURE -> if (tap) placePending(x, y)
        }
        overlay.invalidate()
    }

    /** A tap inside the selected annotation: what can be done with it. */
    private fun tapSelected(x: Float, y: Float) {
        val name = selected ?: return
        val page = currentPageOf(name) ?: return
        pdf.getAnnotations(page).firstOrNull { it.name == name }?.let { host.annotationTapped(it, true) }
    }

    private fun placed(name: String, page: Int) {
        remember(name, page)
        tool = Tool.SELECT
        selected = name
        host.changed()
    }

    private fun pointAt(x: Float, y: Float): Pair<Int, PointF>? = pdf.viewToPagePoint(x, y)

    private fun addShape() {
        val a = pointOnPage(dragPage, start) ?: return
        val b = pointOnPage(dragPage, now) ?: return
        if (abs(start.x - now.x) < overlay.dp(4f) && abs(start.y - now.y) < overlay.dp(4f)) return
        val kind = when (tool) {
            Tool.RECT -> PDFView.SHAPE_RECT
            Tool.ELLIPSE -> PDFView.SHAPE_ELLIPSE
            Tool.LINE -> PDFView.SHAPE_LINE
            else -> PDFView.SHAPE_ARROW
        }
        val rect = if (kind == PDFView.SHAPE_LINE || kind == PDFView.SHAPE_ARROW) RectF(a.x, a.y, b.x, b.y)
            else RectF(min(a.x, b.x), max(a.y, b.y), max(a.x, b.x), min(a.y, b.y))
        pdf.addShape(dragPage, kind, rect, host.color, host.widthPt)?.let { name ->
            remember(name, dragPage)
            host.changed()
        }
    }

    private fun placePending(x: Float, y: Float) {
        val (bitmap, widthPt) = pending ?: return
        val (page, p) = pointAt(x, y) ?: return
        val height = widthPt * bitmap.height / bitmap.width
        // centered on the tap
        val rect = RectF(p.x - widthPt / 2, p.y + height / 2, p.x + widthPt / 2, p.y - height / 2)
        pending = null
        pdf.addImage(page, rect, bitmap) { name ->
            if (name == null) { pending = bitmap to widthPt; host.message("✗") } else placed(name, page)
        }
    }

    /** A view point of [page] in PDF coordinates, kept inside the page. */
    private fun pointOnPage(page: Int, point: PointF): PointF? {
        val r = pdf.pageViewRect(page) ?: return null
        val x = point.x.coerceIn(r.left + 1, r.right - 1)
        val y = point.y.coerceIn(r.top + 1, r.bottom - 1)
        return pdf.viewToPagePoint(x, y)?.takeIf { it.first == page }?.second
    }

    /** The frame the selected annotation is dragged to, in view pixels, moved or resized ([mode]). */
    private fun draggedFrame(mode: Drag): RectF {
        val r = RectF(startFrame)
        val dx = now.x - start.x
        val dy = now.y - start.y
        if (mode == Drag.MOVE) { r.offset(dx, dy); return r }
        // the opposite corner stays; pictures and text keep their proportions
        val keepRatio = selected?.let { it.startsWith("pdfview-image-") || it.startsWith("pdfview-text-") } == true
        val ax = if (corner == 0 || corner == 2) r.right else r.left
        val ay = if (corner == 0 || corner == 1) r.bottom else r.top
        var w = abs((if (corner == 0 || corner == 2) r.left + dx else r.right + dx) - ax).coerceAtLeast(overlay.dp(12f))
        var h = abs((if (corner == 0 || corner == 1) r.top + dy else r.bottom + dy) - ay).coerceAtLeast(overlay.dp(12f))
        if (keepRatio && startFrame.width() > 0 && startFrame.height() > 0) {
            val s = max(w / startFrame.width(), h / startFrame.height())
            w = startFrame.width() * s; h = startFrame.height() * s
        }
        val left = if (corner == 0 || corner == 2) ax - w else ax
        val top = if (corner == 0 || corner == 1) ay - h else ay
        return RectF(left, top, left + w, top + h)
    }

    private fun commitFrame(mode: Drag) {
        val name = selected ?: return
        val old = pdf.movableBounds(name) ?: return
        val frame = draggedFrame(mode)
        val a = pointOnPage(dragPage, PointF(frame.left, frame.top)) ?: return
        val b = pointOnPage(dragPage, PointF(frame.right, frame.bottom)) ?: return
        val to = RectF(min(a.x, b.x), max(a.y, b.y), max(a.x, b.x), min(a.y, b.y))
        if (pdf.moveAnnotation(name, remap(old, normalized(old), to))) host.changed()
    }

    override fun onCancel() { drag = Drag.NONE }

    override fun draw(canvas: Canvas) {
        when (drag) {
            Drag.SHAPE -> drawShapePreview(canvas)
            Drag.MOVE, Drag.RESIZE -> overlay.drawFrame(canvas, draggedFrame(drag))
            Drag.NONE -> selectedFrame()?.let { overlay.drawFrame(canvas, it.second) }
        }
    }

    private val preview = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun drawShapePreview(canvas: Canvas) {
        preview.color = host.color
        // pixels per point on this page at this zoom
        val scale = pdf.pageRectToView(dragPage, RectF(0f, 100f, 100f, 0f))?.width()?.div(100f) ?: 1f
        preview.strokeWidth = max(overlay.dp(1f), host.widthPt * scale)
        when (tool) {
            Tool.RECT -> canvas.drawRect(min(start.x, now.x), min(start.y, now.y), max(start.x, now.x), max(start.y, now.y), preview)
            Tool.ELLIPSE -> canvas.drawOval(min(start.x, now.x), min(start.y, now.y), max(start.x, now.x), max(start.y, now.y), preview)
            else -> canvas.drawLine(start.x, start.y, now.x, now.y, preview)
        }
    }

    companion object {
        private const val ERASER_RADIUS = 10f

        /** [r] with top > bottom and left < right (a line keeps its direction otherwise). */
        fun normalized(r: RectF) = RectF(min(r.left, r.right), max(r.top, r.bottom), max(r.left, r.right), min(r.top, r.bottom))

        /** [raw] (two points: left, top and right, bottom) moved from frame [from] to frame [to]; y up. */
        fun remap(raw: RectF, from: RectF, to: RectF): RectF {
            fun x(v: Float) = to.left + (v - from.left) * (if (from.width() > 0.01f) to.width() / from.width() else 1f)
            fun y(v: Float) = to.bottom + (v - from.bottom) * (if (from.top - from.bottom > 0.01f) (to.top - to.bottom) / (from.top - from.bottom) else 1f)
            return RectF(x(raw.left), y(raw.top), x(raw.right), y(raw.bottom))
        }
    }
}
