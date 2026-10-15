package com.wxiwei.office.editor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.editor.docsdk.EditStyle

/**
 * Two drag handles under the ends of the Word selection, drops like Android's: the start one hangs
 * left of the first character, the end one right of the last, their point on the text. Dragging one moves that end to the text
 * under the finger. Touches away from the handles pass through to the document.
 *
 * [range] is the selection (end exclusive), [caret] the caret rectangle of an offset in the
 * coordinates of [source], [offsetAt] the offset at a screen point, [onChange] the new selection.
 * What to do with the selection is on the panel's context bar.
 */
@SuppressLint("ViewConstructor")
class WordSelectionHandles(
    context: Context,
    private val source: () -> View?,
    private val range: () -> LongRange?,
    private val caret: (Long) -> Rect?,
    private val offsetAt: (rawX: Float, rawY: Float) -> Long,
    private val onChange: (start: Long, end: Long) -> Unit,
    /** A touch on a handle that did not drag it: a tap on the document there. */
    private val onTap: (rawX: Float, rawY: Float) -> Unit = { _, _ -> },
) : View(context) {
    private val editStyle = EditStyle.of(context)
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private val density = context.resources.displayMetrics.density
    private val radius = 13 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = editStyle.selection }
    // handle tips (the text line bottom) in this view's coordinates, or null when not shown
    private var startTip: Pair<Float, Float>? = null
    private var endTip: Pair<Float, Float>? = null
    private var lineHeight = 0f
    private var dragging = 0 // 0 none, 1 start, 2 end
    private val preDraw = ViewTreeObserver.OnPreDrawListener { refresh(); true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    /** Recomputes the handle positions; call after the selection changed. */
    fun refresh() {
        val view = source()
        val r = range()
        var s: Pair<Float, Float>? = null
        var e: Pair<Float, Float>? = null
        if (view != null && r != null && !r.isEmpty()) {
            val a = IntArray(2); val b = IntArray(2)
            view.getLocationOnScreen(a); getLocationOnScreen(b)
            val dx = (a[0] - b[0]).toFloat(); val dy = (a[1] - b[1]).toFloat()
            caret(r.first)?.let { s = it.left + dx to it.bottom + dy; lineHeight = it.height().toFloat() }
            caret(r.last + 1)?.let { e = it.left + dx to it.bottom + dy }
        }
        if (s != startTip || e != endTip) {
            startTip = s; endTip = e
            invalidate()
        }
    }

    private fun near(tip: Pair<Float, Float>?, start: Boolean, x: Float, y: Float): Boolean {
        tip ?: return false
        // the grab area is the drop below the tip, generously sized for a finger
        val cx = if (start) tip.first - radius else tip.first + radius
        val cy = tip.second + radius
        return Math.hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius * 2.5
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    near(endTip, false, event.x, event.y) -> 2
                    near(startTip, true, event.x, event.y) -> 1
                    else -> 0
                }
                if (dragging != 0) parent?.requestDisallowInterceptTouchEvent(true)
                moved = false
                downX = event.x; downY = event.y
                return dragging != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 0) return false
                if (!moved && Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) < 8 * density) return true
                moved = true
                val r = range() ?: return true
                // aim at the text line above the knob, not under the finger
                val offset = offsetAt(event.rawX, event.rawY - radius - lineHeight / 2)
                if (offset < 0) return true
                if (dragging == 1) {
                    if (offset < r.last + 1) onChange(offset, r.last + 1)
                } else if (offset > r.first) onChange(r.first, offset)
                refresh()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging != 0
                dragging = 0
                if (was && !moved && event.actionMasked == MotionEvent.ACTION_UP) onTap(event.rawX, event.rawY)
                if (was) refresh()
                return was
            }
        }
        return false
    }

    private val drop = Path()
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        for ((tip, start) in listOf(startTip to true, endTip to false)) {
            tip ?: continue
            // a circle whose corner at the tip is square: the point of the drop
            val left = if (start) tip.first - 2 * radius else tip.first
            box.set(left, tip.second, left + 2 * radius, tip.second + 2 * radius)
            val r = radius
            val radii = if (start) floatArrayOf(r, r, 0f, 0f, r, r, r, r) else floatArrayOf(0f, 0f, r, r, r, r, r, r)
            drop.reset()
            drop.addRoundRect(box, radii, Path.Direction.CW)
            canvas.drawPath(drop, paint)
        }
    }
}
