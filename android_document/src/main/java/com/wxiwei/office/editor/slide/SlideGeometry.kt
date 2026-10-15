package com.wxiwei.office.editor.slide

import android.graphics.RectF
import com.wxiwei.office.editor.pptx.Point
import com.wxiwei.office.editor.pptx.Rect
import com.wxiwei.office.editor.pptx.PptxShapeInfo
import com.wxiwei.office.pg.control.Presentation
import kotlin.math.roundToLong

/** Coordinates are local to Presentation, not screen coordinates. Normal page drawing uses
 * 96 dpi (9525 EMU/pixel) times zoom, with an integer page origin. Page sizes in the viewer
 * are truncated to pixels, so dividing by the original EMU slide width would drift.
 * Slideshow mapping is for the settled slide, not an animated transition.
 */
object SlideGeometry {
    private data class Mapping(val left: Float, val top: Float, val width: Float, val height: Float, val scale: Double)
    private fun mapping(p: Presentation): Mapping? {
        if (p.getCurrentIndex() < 0 || p.width <= 0 || p.height <= 0) return null
        val zoom = p.getZoom(); if (!zoom.isFinite() || zoom <= 0f) return null
        val size = p.getPageSize() ?: return null
        val width = (size.width * zoom).toInt(); val height = (size.height * zoom).toInt()
        if (width <= 0 || height <= 0) return null
        val origin = if (p.isSlideShow()) {
            // getSlideDrawingRect spans the entire view height; SlideShowView centers actual content.
            (p.getSlideDrawingRect()?.left ?: ((p.width - width) / 2)).toFloat() to ((p.height - height) / 2).toFloat()
        } else {
            val item = p.getPrintMode().getListView()?.currentPageViewOrNull() ?: return null
            val a = IntArray(2); val b = IntArray(2)
            p.getLocationOnScreen(a); item.getLocationOnScreen(b)
            (b[0] - a[0]).toFloat() to (b[1] - a[1]).toFloat()
        }
        return Mapping(origin.first, origin.second, width.toFloat(), height.toFloat(), zoom.toDouble() / 9525.0)
    }
    fun viewToEmu(presentation: Presentation, viewX: Float, viewY: Float): Point? {
        val m = mapping(presentation) ?: return null
        if (!viewX.isFinite() || !viewY.isFinite() || viewX < m.left || viewY < m.top || viewX >= m.left + m.width || viewY >= m.top + m.height) return null
        return Point(((viewX - m.left) / m.scale).roundToLong(), ((viewY - m.top) / m.scale).roundToLong())
    }
    fun emuToView(presentation: Presentation, rectEmu: Rect): RectF? {
        val m = mapping(presentation) ?: return null
        return RectF((m.left + rectEmu.x * m.scale).toFloat(), (m.top + rectEmu.y * m.scale).toFloat(),
            (m.left + (rectEmu.x.toDouble() + rectEmu.width) * m.scale).toFloat(), (m.top + (rectEmu.y.toDouble() + rectEmu.height) * m.scale).toFloat())
    }
    /** Slide EMU covered by one view pixel at the current zoom, or null before the slide is laid out. */
    fun emuPerPixel(presentation: Presentation): Double? = mapping(presentation)?.let { 1.0 / it.scale }

    /**
     * The shape a finger means at [emuPoint]: among the shapes under it the smallest wins (the topmost
     * on a tie), so a text box stays reachable under a full-slide picture, a transparent cover or its
     * group. With none under it, the nearest within [slopEmu] of the point, for small boxes.
     */
    fun pick(shapes: List<PptxShapeInfo>, emuPoint: Point, slopEmu: Long = 0): PptxShapeInfo? {
        val boxes = shapes.withIndex().filter { it.value.rectEmu.width > 0 && it.value.rectEmu.height > 0 }
        val under = boxes.filter { distance(it.value.rectEmu, emuPoint) == 0.0 }
        if (under.isNotEmpty()) return under.minWith(compareBy<IndexedValue<PptxShapeInfo>> { it.value.rectEmu.width.toDouble() * it.value.rectEmu.height }
            .thenByDescending { it.index }).value
        return boxes.map { it to distance(it.value.rectEmu, emuPoint) }.filter { it.second <= slopEmu }
            .minWithOrNull(compareBy<Pair<IndexedValue<PptxShapeInfo>, Double>> { it.second }.thenByDescending { it.first.index })?.first?.value
    }

    /** Distance from [p] to the rectangle [r], 0 inside it. */
    private fun distance(r: Rect, p: Point): Double {
        val dx = maxOf(r.x - p.x, 0L, p.x - (r.x + r.width)).toDouble()
        val dy = maxOf(r.y - p.y, 0L, p.y - (r.y + r.height)).toDouble()
        return Math.hypot(dx, dy)
    }

    fun hitTest(shapes: List<PptxShapeInfo>, emuPoint: Point): PptxShapeInfo? = shapes.lastOrNull {
        val r = it.rectEmu
        r.width > 0 && r.height > 0 && emuPoint.x >= r.x && emuPoint.y >= r.y && emuPoint.x.toDouble() < r.x.toDouble() + r.width && emuPoint.y.toDouble() < r.y.toDouble() + r.height
    }
}
