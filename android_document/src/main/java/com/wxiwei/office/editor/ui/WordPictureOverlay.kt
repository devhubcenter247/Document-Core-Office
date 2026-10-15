package com.wxiwei.office.editor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.editor.docsdk.EditStyle

/**
 * Frame over the selected picture of a Word document, with a handle at each corner. Dragging the
 * picture moves it ([onMove] with the finger's screen point and the shift in view pixels on
 * release); dragging a corner resizes it keeping its proportions ([onResize] with the new size in
 * view pixels). [frame] gives the picture's box in the coordinates of [source] before every frame.
 * Touches outside the frame pass through. A long press on the document can start a move with
 * [grab] and [follow], and a table gets a frame that only moves ([resizable] off).
 */
@SuppressLint("ViewConstructor")
class WordPictureOverlay(
    context: Context,
    private val source: () -> View?,
    private val frame: () -> Rect?,
    private val onMove: (rawX: Float, rawY: Float, dx: Float, dy: Float) -> Unit,
    private val onResize: (width: Float, height: Float) -> Unit,
    private val dropAt: (rawX: Float, rawY: Float, dy: Float) -> Rect? = { _, _, _ -> null },
    // a selected table: its column borders and rows, in [source] coordinates
    private val guides: () -> com.wxiwei.office.editor.word.WordSelection.TableGuides? = { null },
    private val onColumn: (index: Int, dx: Float) -> Unit = { _, _ -> },
    private val onRow: (start: Long, height: Float) -> Unit = { _, _ -> },
) : View(context) {
    private val editStyle = EditStyle.of(context)
    /** Shows the frame (a picture is selected) or hides it. */
    var active = false
        set(value) {
            field = value
            refresh()
            invalidate()
        }

    /** Corner handles that resize (a picture); off for a frame that only moves (a table). */
    var resizable = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val box = RectF()
    private val drag = RectF()
    private var visible = false
    private var mode = 0 // 0 none, 1 move, 2..5 corner (tl, tr, bl, br), 6 column border, 7 row border
    // the table border being dragged (overlay coordinates): where it was, where it is, its limits
    private var guideIndex = 0
    private var guideRow = 0L
    private var guideFrom = 0f
    private var guideAt = 0f
    private var guideMin = 0f
    private var guideMax = Float.MAX_VALUE
    private var guideSpan = RectF()
    private var downX = 0f
    private var downY = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = editStyle.selection
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = editStyle.selection }
    // where a move would put it (a caret, in this view's coordinates), shown while dragging
    private val drop = RectF()
    private var dropShown = false
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = editStyle.dropTarget }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = editStyle.handleFill }
    private val preDraw = ViewTreeObserver.OnPreDrawListener { if (mode == 0) refresh(); true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val view = source()
        val r = if (active && view != null) frame() else null
        if (r == null) {
            if (visible) { visible = false; invalidate() }
            return
        }
        val a = IntArray(2); val b = IntArray(2)
        view!!.getLocationOnScreen(a); getLocationOnScreen(b)
        val next = RectF(r).apply { offset((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat()) }
        if (!visible || next != box) {
            box.set(next)
            visible = true
            invalidate()
        }
    }

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

    /**
     * Starts moving the frame from a long press at a screen point, in a touch the document holds:
     * the rest of that touch comes through [follow]. False when the point is not on the frame.
     */
    fun grab(rawX: Float, rawY: Float): Boolean {
        refresh()
        if (!visible) return false
        val (x, y) = local(rawX, rawY)
        if (!box.contains(x, y)) return false
        press(x, y, move = true)
        return true
    }

    /** An event of the touch started by [grab], in any view's coordinates (its raw point is used). */
    fun follow(event: MotionEvent) {
        if (mode == 0) return
        val (x, y) = local(event.rawX, event.rawY)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> dragTo(x, y)
            MotionEvent.ACTION_UP -> release(x, y, event.rawX, event.rawY)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    private fun local(rawX: Float, rawY: Float): Pair<Float, Float> {
        val at = IntArray(2)
        getLocationOnScreen(at)
        return (rawX - at[0]) to (rawY - at[1])
    }

    /** [source]'s offset in this view. */
    private fun sourceShift(): Pair<Float, Float> {
        val view = source() ?: return 0f to 0f
        val a = IntArray(2); val b = IntArray(2)
        view.getLocationOnScreen(a); getLocationOnScreen(b)
        return (a[0] - b[0]).toFloat() to (a[1] - b[1]).toFloat()
    }

    /**
     * The round handle of a table border under ([x], [y]) (a column's at the table's top edge, a
     * row's at its left edge): mode 6 or 7 set up for dragging it; false when there is none. Only
     * the handles: rows of a small table are thinner than a finger, a tap in a cell must reach it.
     */
    private fun pressBorder(x: Float, y: Float): Boolean {
        val g = guides() ?: return false
        val (sx, sy) = sourceShift()
        val reach = 16 * density
        val min = 12 * density
        val knobOut = 12 * density
        val left = g.left + sx; val right = g.right + sx; val top = g.top + sy; val bottom = g.bottom + sy
        fun near(hx: Float, hy: Float) = Math.hypot((x - hx).toDouble(), (y - hy).toDouble()) <= reach
        val cols = g.columns
        cols.withIndex().filter { near(it.value.x + sx, top - knobOut) }.minByOrNull { Math.abs(it.value.x + sx - x) }?.let { (i, c) ->
            mode = 6; guideIndex = c.index
            guideFrom = c.x + sx; guideAt = guideFrom
            guideMin = (if (i > 0) cols[i - 1].x + sx else left) + min
            guideMax = if (i < cols.lastIndex) cols[i + 1].x + sx - min else Float.MAX_VALUE
            guideSpan.set(guideFrom, top, guideFrom, bottom)
            return true
        }
        g.rows.filter { near(left - knobOut, it.bottom + sy) }.minByOrNull { Math.abs(it.bottom + sy - y) }?.let { r ->
            mode = 7; guideRow = r.start
            guideFrom = r.bottom + sy; guideAt = guideFrom
            guideMin = r.top + sy + min; guideMax = Float.MAX_VALUE
            guideSpan.set(left, r.top + sy, right, guideFrom)
            return true
        }
        return false
    }

    /** Where the move handle of a selected table is drawn: outside its top-left corner. */
    private fun moveKnob(): Pair<Float, Float>? {
        val g = guides() ?: return null
        val (sx, sy) = sourceShift()
        return (g.left + sx - 20 * density) to (g.top + sy - 20 * density)
    }

    private fun press(x: Float, y: Float, move: Boolean): Boolean {
        val reach = 24 * density
        if (!move && !resizable) {
            // a table: only its handles take the touch, the text in its cells gets the rest
            if (pressBorder(x, y)) { downX = x; downY = y; invalidate(); return true }
            val knob = moveKnob() ?: return false
            if (Math.hypot((x - knob.first).toDouble(), (y - knob.second).toDouble()) > 18 * density) return false
            mode = 1
            downX = x; downY = y
            drag.set(box)
            invalidate()
            return true
        }
        val corner = if (move || !resizable) -1 else corners(box).indexOfFirst { (cx, cy) -> Math.hypot((x - cx).toDouble(), (y - cy).toDouble()) <= reach }
        mode = when {
            corner >= 0 -> 2 + corner
            move || box.contains(x, y) -> 1
            else -> return false
        }
        downX = x; downY = y
        drag.set(box)
        invalidate()
        return true
    }

    private fun dragTo(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        if (mode == 6) guideAt = (guideFrom + dx).coerceIn(guideMin, maxOf(guideMin, guideMax))
        else if (mode == 7) guideAt = maxOf(guideMin, guideFrom + dy)
        else if (mode == 1) {
            lastX = x; lastY = y
            if (!scrolling) { scrolling = true; postOnAnimation(edgeScroll) }
            drag.set(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
            val at = IntArray(2); getLocationOnScreen(at)
            val caret = dropAt(x + at[0], y + at[1], dy)
            val view = source()
            dropShown = caret != null && view != null
            if (caret != null && view != null) {
                val v = IntArray(2); view.getLocationOnScreen(v)
                drop.set(RectF(caret).apply { offset((v[0] - at[0]).toFloat(), (v[1] - at[1]).toFloat()) })
            }
        }
        else if (mode >= 2) {
            // proportional: the larger of the two moves decides
            val ratio = box.height() / box.width()
            val sx = if (mode == 2 || mode == 4) -dx else dx
            val sy = if (mode == 2 || mode == 3) -dy else dy
            val w = maxOf(16 * density, box.width() + maxOf(sx, sy / ratio))
            val h = w * ratio
            val left = if (mode == 2 || mode == 4) box.right - w else box.left
            val top = if (mode == 2 || mode == 3) box.bottom - h else box.top
            drag.set(left, top, left + w, top + h)
        }
        invalidate()
    }

    // A move held near the top or bottom edge of the document scrolls it, so the frame can be
    // taken to another page: the place it was picked up from scrolls away with the document.
    private var lastX = 0f
    private var lastY = 0f
    private var scrolling = false
    private val edgeScroll = object : Runnable {
        override fun run() {
            val view = source()
            val top = sourceShift().second
            val zone = 48 * density
            val into = maxOf(top + zone - lastY, lastY - (top + (view?.height ?: 0) - zone))
            if (mode != 1 || view == null || into <= 0) { scrolling = false; return }
            // faster the closer to the edge
            val step = maxOf(1, (minOf(into / zone, 1f) * 16 * density).toInt()) * (if (lastY < top + zone) -1 else 1)
            val before = view.scrollY
            view.scrollBy(0, step)
            val moved = view.scrollY - before
            if (moved == 0) { scrolling = false; return }
            downY -= moved
            box.offset(0f, -moved.toFloat())
            dragTo(lastX, lastY)
            postOnAnimation(this)
        }
    }

    private fun release(x: Float, y: Float, rawX: Float, rawY: Float) {
        val m = mode
        mode = 0
        dropShown = false
        val dx = x - downX
        val dy = y - downY
        if (m == 6 && Math.abs(guideAt - guideFrom) > 2) onColumn(guideIndex, guideAt - guideFrom)
        else if (m == 7 && Math.abs(guideAt - guideFrom) > 2) onRow(guideRow, guideAt - guideSpan.top)
        else if (m == 1 && Math.hypot(dx.toDouble(), dy.toDouble()) > 8 * density) onMove(rawX, rawY, dx, dy)
        else if (m >= 2 && (drag.width() != box.width())) onResize(drag.width(), drag.height())
        invalidate()
    }

    private fun cancel() {
        mode = 0
        dropShown = false
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!visible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!press(event.x, event.y, move = false)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> { dragTo(event.x, event.y); return true }
            MotionEvent.ACTION_UP -> { release(event.x, event.y, event.rawX, event.rawY); return true }
            MotionEvent.ACTION_CANCEL -> { cancel(); return true }
        }
        return mode != 0
    }

    /** A handle on each column border (top) and row border (left) of a selected table; the dragged one as a line. */
    private fun drawTableHandles(canvas: Canvas) {
        if (mode == 6) { canvas.drawRect(guideAt - 1.5f * density, guideSpan.top, guideAt + 1.5f * density, guideSpan.bottom, dropPaint); return }
        if (mode == 7) { canvas.drawRect(guideSpan.left, guideAt - 1.5f * density, guideSpan.right, guideAt + 1.5f * density, dropPaint); return }
        if (mode == 1) return
        val g = guides() ?: return
        val (sx, sy) = sourceShift()
        // the move handle: a round knob with a cross
        moveKnob()?.let { (kx, ky) ->
            canvas.drawCircle(kx, ky, 11 * density, handle)
            canvas.drawCircle(kx, ky, 8.5f * density, handleFill)
            canvas.drawRect(kx - 5 * density, ky - 1 * density, kx + 5 * density, ky + 1 * density, handle)
            canvas.drawRect(kx - 1 * density, ky - 5 * density, kx + 1 * density, ky + 5 * density, handle)
        }
        // outside the table: they never sit on its text or on the selection handles
        val out = 12 * density
        for (c in g.columns) {
            canvas.drawCircle(c.x + sx, g.top + sy - out, 6 * density, handle)
            canvas.drawCircle(c.x + sx, g.top + sy - out, 3.5f * density, handleFill)
        }
        for (row in g.rows) {
            canvas.drawCircle(g.left + sx - out, row.bottom + sy, 6 * density, handle)
            canvas.drawCircle(g.left + sx - out, row.bottom + sy, 3.5f * density, handleFill)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        val r = if (mode in 1..5) drag else box
        // a caret (in-line picture) or a line across (table)
        if (dropShown) {
            if (drop.width() > drop.height()) canvas.drawRect(drop, dropPaint)
            else canvas.drawRect(drop.left - 1.5f * density, drop.top, drop.left + 1.5f * density, drop.bottom, dropPaint)
        }
        canvas.drawRect(r, line)
        if (!resizable) drawTableHandles(canvas)
        if (resizable) for ((x, y) in corners(r)) {
            canvas.drawCircle(x, y, 7 * density, handle)
            canvas.drawCircle(x, y, 4.5f * density, handleFill)
        }
    }
}
