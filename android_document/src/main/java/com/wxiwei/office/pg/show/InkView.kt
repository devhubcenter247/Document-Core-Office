/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.pg.show

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Pen, eraser and laser pointer over the slide during the show. Strokes are kept per slide in
 * slide coordinates (0..1), so they stay on their slide when the show comes back to it.
 */
internal class InkView(context: Context) : View(context) {
    enum class Mode { NONE, PEN, ERASER, LASER }

    class Stroke(val color: Int, val width: Float, val points: MutableList<PointF> = ArrayList())

    var mode = Mode.NONE
        set(value) { field = value; laser.clear(); invalidate() }
    var color = Color.RED
    /** Pen width as a part of the slide width. */
    var width = 0.004f
    /** The slide the strokes go to. */
    var slide = -1
        set(value) { field = value; invalidate() }
    /** Where the slide is in this view. */
    val area = RectF()

    private val strokes = HashMap<Int, MutableList<Stroke>>()
    private var drawing: Stroke? = null
    private val laser = ArrayList<Pair<PointF, Long>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun strokesOf(slide: Int): List<Stroke> = strokes[slide].orEmpty()
    fun clear() { strokes.remove(slide); invalidate() }

    private fun toSlide(x: Float, y: Float) = PointF((x - area.left) / area.width(), (y - area.top) / area.height())
    private fun toView(p: PointF) = PointF(area.left + p.x * area.width(), area.top + p.y * area.height())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.NONE || area.isEmpty) return false
        val p = toSlide(event.x, event.y)
        when (mode) {
            Mode.PEN -> when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { drawing = Stroke(color, width).also { it.points.add(p); strokes.getOrPut(slide) { ArrayList() }.add(it) } }
                MotionEvent.ACTION_MOVE -> { for (i in 0 until event.historySize) drawing?.points?.add(toSlide(event.getHistoricalX(i), event.getHistoricalY(i))); drawing?.points?.add(p) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawing = null
            }
            Mode.ERASER -> {
                // strokes passing near the finger go
                val reach = 0.02f
                strokes[slide]?.removeAll { s -> s.points.any { Math.hypot((it.x - p.x).toDouble(), ((it.y - p.y) * area.height() / area.width()).toDouble()) < reach } }
            }
            Mode.LASER -> {
                laser.add(p to SystemClock.uptimeMillis())
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) laser.add(PointF(Float.NaN, Float.NaN) to SystemClock.uptimeMillis())
            }
            Mode.NONE -> Unit
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (area.isEmpty) return
        canvas.save()
        canvas.clipRect(area)
        for (s in strokes[slide].orEmpty()) {
            paint.color = s.color
            paint.strokeWidth = s.width * area.width()
            val path = Path()
            s.points.forEachIndexed { i, pt -> val v = toView(pt); if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y) }
            if (s.points.size == 1) { val v = toView(s.points[0]); canvas.drawPoint(v.x, v.y, paint) } else canvas.drawPath(path, paint)
        }
        if (mode == Mode.LASER) {
            val now = SystemClock.uptimeMillis()
            laser.removeAll { now - it.second > TRAIL_MS }
            var last: PointF? = null
            for ((pt, t) in laser) {
                if (pt.x.isNaN()) { last = null; continue }
                val v = toView(pt)
                val fade = 1f - (now - t).toFloat() / TRAIL_MS
                paint.color = Color.argb((160 * fade).toInt(), 255, 30, 30)
                paint.strokeWidth = area.width() * 0.008f * fade
                last?.let { canvas.drawLine(it.x, it.y, v.x, v.y, paint) }
                last = v
            }
            laser.lastOrNull()?.first?.takeIf { !it.x.isNaN() }?.let { tip ->
                val v = toView(tip)
                dot.color = Color.argb(90, 255, 0, 0); canvas.drawCircle(v.x, v.y, area.width() * 0.018f, dot)
                dot.color = Color.rgb(255, 20, 20); canvas.drawCircle(v.x, v.y, area.width() * 0.008f, dot)
            }
            if (laser.isNotEmpty()) postInvalidateOnAnimation()
        }
        canvas.restore()
    }

    companion object { private const val TRAIL_MS = 350L }
}
