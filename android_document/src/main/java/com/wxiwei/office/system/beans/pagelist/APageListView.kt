package com.wxiwei.office.system.beans.pagelist

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.Adapter
import android.widget.AdapterView
import java.util.LinkedList
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.system.IMainFrame
import com.wxiwei.office.system.OpenTrace

open class APageListView : AdapterView<Adapter> {
    companion object { private const val GAP = 20 }

    constructor(context: Context) : super(context)

    constructor(context: Context, listener: IPageListViewListener) : super(context) {
        pageListViewListener = listener
        eventManage = APageListEventManage(this)
        pageAdapter = APageListAdapter(this)
        setLongClickable(true)
        post {
            if (pageListViewListener != null && pageListViewListener!!.isInit()) init()
        }
    }

    fun init() { isInit = true; requestLayout() }

    override fun requestLayout() { if (isDoRequestLayout) super.requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        OpenTrace.mark("pageList.onMeasure.begin childCount=$childCount init=$isInit")
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            val view = getChildAt(i)
            if (view is APageListItem) view.measure(MeasureSpec.EXACTLY or (view.getPageWidth() * zoom).toInt(), MeasureSpec.EXACTLY or (view.getPageHeight() * zoom).toInt())
        }
        OpenTrace.mark("pageList.onMeasure.end size=${measuredWidth}x$measuredHeight")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isConfigurationChanged) {
            val fitZoom = getFitZoom()
            if (zoom < fitZoom) {
                setZoom(fitZoom, false)
                isInit = false
                postDelayed({ isInit = true; isResetLayout = true; requestLayout() }, 1)
                pageListViewListener!!.changeZoom()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pageView = getCurrentPageView()
        if (pageView != null && pageView.control.getSysKit().getCalloutManager().getDrawingMode() != MainConstant.DRAWMODE_NORMAL) return false
        eventManage!!.processOnTouch(event)
        pageListViewListener!!.onEventMethod(this, event, null, -1f, -1f, IMainFrame.ON_TOUCH)
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        OpenTrace.mark("pageList.onLayout.begin changed=$changed bounds=$left,$top,$right,$bottom init=$isInit childCount=$childCount")
        super.onLayout(changed, left, top, right, bottom)
        if (!isInit) {
            OpenTrace.mark("pageList.onLayout.skipNotInit")
            return
        }
        boundsChanged = changed
        if (pageListViewListener!!.getPageListViewMovingPosition() == IPageListViewListener.Moving_Horizontal) layoutHorizontal() else layoutVertical()
        invalidate()
        if (isConfigurationChanged) {
            isConfigurationChanged = false
            getCurrentPageView()?.let { postRepaint(it) }
        }
        OpenTrace.mark("pageList.onLayout.end current=$currentIndex childCount=$childCount")
    }

    // The list itself was moved or resized (the keyboard came up, say): the pages are still where
    // the old bounds put them, which is not the user scrolling to another page
    private var boundsChanged = false

    private fun removeUnusedViews() {
        val indexes = IntArray(childViewsCache.size()) { childViewsCache.keyAt(it) }
        indexes.forEach { index ->
            if (index < currentIndex - 1 || index > currentIndex + 1) {
                val view = childViewsCache.get(index)
                view.releaseResources()
                pageViewCache.add(view)
                removeViewInLayout(view)
                childViewsCache.remove(index)
            }
        }
    }

    private fun layoutHorizontal() {
        var currentView = childViewsCache.get(currentIndex)
        if (!isResetLayout) {
            if (currentView != null && !boundsChanged && kotlin.math.abs(currentView.left) < currentView.width) {
            val offset = getScreenSizeOffset(currentView)
            if (currentView.left + currentView.measuredWidth + offset.x + GAP / 2 + eventManage!!.getScrollX() < width / 2 && currentIndex + 1 < pageAdapter!!.count && !eventManage!!.isOnFling()) { postUnRepaint(currentView); post(eventManage); currentIndex++ }
            else if (currentView.left - offset.x - GAP / 2 + eventManage!!.getScrollX() >= width / 2 && currentIndex > 0 && !eventManage!!.isOnFling()) { postUnRepaint(currentView); post(eventManage); currentIndex-- }
            }
            removeUnusedViews()
        } else {
            isResetLayout = false
            var repaint = false
            eventManage!!.setScrollAxisValue(0, 0)
            val indexes = IntArray(childViewsCache.size()) { childViewsCache.keyAt(it) }
            indexes.forEach { index -> if (index < currentIndex - 1 || index > currentIndex + 1) { val view = childViewsCache.get(index); view.releaseResources(); pageViewCache.add(view); removeViewInLayout(view); childViewsCache.remove(index); repaint = index == currentIndex } }
            if ((zoom * 100).toInt() != 100 || !repaint) post(eventManage)
        }
        val notPresent = currentView == null
        currentView = createPageView(currentIndex)
        val offset = getScreenSizeOffset(currentView)
        val left = if (notPresent) offset.x else currentView.left + eventManage!!.getScrollX()
        val top = if (notPresent) offset.y else currentView.top + eventManage!!.getScrollY()
        eventManage!!.setScrollAxisValue(0, 0)
        var l = left
        var t = top
        var r = l + currentView.measuredWidth
        var b = t + currentView.measuredHeight
        val horizontalIdle = !eventManage!!.isTouchEventIn() && eventManage!!.isScrollerFinished()
        val horizontalCorrection = getCorrection(getScrollBounds(l, t, r, b))
        // Keep the paging axis free while dragging or animating.
        if (horizontalIdle) { l += horizontalCorrection.x; r += horizontalCorrection.x }
        if (horizontalIdle || currentView.measuredHeight <= height) {
            t += horizontalCorrection.y; b += horizontalCorrection.y
        }
        currentView.layout(l, t, r, b)
        if (currentIndex > 0) { val previous = createPageView(currentIndex - 1); val o = getScreenSizeOffset(previous); val gap = o.x + GAP + offset.x; previous.layout(l - previous.measuredWidth - gap, (b + t - previous.measuredHeight) / 2, l - gap, (b + t + previous.measuredHeight) / 2) }
        if (currentIndex + 1 < pageAdapter!!.count) { val next = createPageView(currentIndex + 1); val o = getScreenSizeOffset(next); val gap = offset.x + GAP + o.x; next.layout(r + gap, (b + t - next.measuredHeight) / 2, r + next.measuredWidth + gap, (b + t + next.measuredHeight) / 2) }
    }

    private fun layoutVertical() {
        var currentView = childViewsCache.get(currentIndex)
        if (!isResetLayout) {
            if (currentView != null && !boundsChanged) {
            val offset = getScreenSizeOffset(currentView)
            if (currentView.top + currentView.measuredHeight + offset.y + GAP / 2 + eventManage!!.getScrollY() < height / 2 && currentIndex + 1 < pageAdapter!!.count && !eventManage!!.isOnFling()) { postUnRepaint(currentView); post(eventManage); currentIndex++; Log.e("current ++", currentIndex.toString()) }
            else if (currentView.top - offset.y - GAP / 2 + eventManage!!.getScrollY() >= height / 2 && currentIndex > 0 && !eventManage!!.isOnFling()) { postUnRepaint(currentView); post(eventManage); currentIndex--; Log.e("current --", currentIndex.toString()) }
            }
            removeUnusedViews()
        } else {
            isResetLayout = false
            var repaint = false
            eventManage!!.setScrollAxisValue(0, 0)
            val indexes = IntArray(childViewsCache.size()) { childViewsCache.keyAt(it) }
            indexes.forEach { index -> if (index < currentIndex - 1 || index > currentIndex + 1) { val view = childViewsCache.get(index); view.releaseResources(); pageViewCache.add(view); removeViewInLayout(view); childViewsCache.remove(index); repaint = index == currentIndex } }
            if ((zoom * 100).toInt() != 100 || !repaint) post(eventManage)
        }
        val notPresent = currentView == null
        currentView = createPageView(currentIndex)
        val offset = getScreenSizeOffset(currentView)
        var l = if (notPresent) offset.x else currentView.left + eventManage!!.getScrollX()
        var t = if (notPresent) offset.y else currentView.top + eventManage!!.getScrollY()
        eventManage!!.setScrollAxisValue(0, 0)
        var r = l + currentView.measuredWidth
        var b = t + currentView.measuredHeight
        val verticalIdle = !eventManage!!.isTouchEventIn() && eventManage!!.isScrollerFinished()
        val verticalCorrection = getCorrection(getScrollBounds(l, t, r, b))
        if (verticalIdle || currentView.measuredWidth <= width) {
            l += verticalCorrection.x; r += verticalCorrection.x
        }
        if (verticalIdle) { t += verticalCorrection.y; b += verticalCorrection.y }
        currentView.layout(l, t, r, b)
        if (currentIndex > 0) { val previous = createPageView(currentIndex - 1); val o = getScreenSizeOffset(previous); val gap = o.y + GAP + offset.y; previous.layout(l, t - gap - previous.measuredHeight, r, b - gap - previous.measuredHeight) }
        if (currentIndex + 1 < pageAdapter!!.count) { val next = createPageView(currentIndex + 1); val o = getScreenSizeOffset(next); val gap = offset.y + GAP + o.y; next.layout(l, t + gap + next.measuredHeight, r, b + gap + next.measuredHeight) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); isConfigurationChanged = true }
    fun showPDFPageForIndex(index: Int) { if (index < 0 || index >= pageAdapter!!.count) return; currentIndex = index; postDelayed({ if (index == currentIndex) { isResetLayout = true; requestLayout() } }, 1); pageListViewListener!!.updateStutus(null) }
    fun nextPageView() { if (currentIndex + 1 >= pageAdapter!!.count) return; val view = childViewsCache.get(currentIndex + 1); if (view != null) { currentIndex++; eventManage!!.slideViewOntoScreen(view) } else { postDelayed({ isResetLayout = true; requestLayout() }, 1); pageListViewListener!!.updateStutus(null) } }
    fun previousPageview() { if (currentIndex == 0) return; val view = childViewsCache.get(currentIndex - 1); if (view != null) { currentIndex--; eventManage!!.slideViewOntoScreen(view) } }
    fun exportImage(view: APageListItem, srcBitmap: Bitmap?) { if (view.pageIndex == currentIndex && !eventManage!!.isTouchEventIn() && eventManage!!.isScrollerFinished()) pageListViewListener!!.exportImage(view, srcBitmap) }
    fun isPointVisibleOnScreen(xValue: Int, yValue: Int): Boolean { val x = (xValue * zoom).toInt(); val y = (yValue * zoom).toInt(); val item = getCurrentPageView() ?: return false; val left = maxOf(item.left, 0) - item.left; val top = maxOf(item.top, 0) - item.top; return x >= left && x < left + width && y >= top && y < top + height }
    fun setItemPointVisibleOnScreen(xValue: Int, yValue: Int) {
        if (xValue < 0 && yValue < 0) return
        val item = getCurrentPageView() ?: return
        if (isPointVisibleOnScreen(xValue, yValue)) return
        val x = (xValue * zoom).toInt()
        val y = (yValue * zoom).toInt()
        val l = if (x + width > item.measuredWidth) -(item.measuredWidth - width) else -x
        // Leave the point a third down the screen rather than at the very top edge, where it is easily hidden
        val ty = maxOf(0, y - height / 3)
        val t = if (ty + height > item.measuredHeight) -(item.measuredHeight - height) else -ty
        val r = l + item.measuredWidth
        val b = t + item.measuredHeight
        item.layout(l, t, r, b)
        postRepaint(item)
    }
    @get:JvmName("getModelProperty")
    val model: Any? get() = pageListViewListener!!.getModel()
    fun getModel(): Any? = pageListViewListener!!.getModel()
    fun getDisplayedPageIndex() = currentIndex
    fun setZoom(zoom: Float, pointX: Int, pointY: Int) {
        OpenTrace.mark("pageList.setZoom.begin old=${this.zoom} new=$zoom")
        setZoom(zoom, pointX, pointY, true)
        OpenTrace.mark("pageList.setZoom.end")
    }
    fun setZoom(zoomValue: Float, isRepaint: Boolean) { setZoom(zoomValue, Int.MIN_VALUE, Int.MIN_VALUE, isRepaint) }
    fun setZoom(zoomValue: Float, pointXValue: Int, pointYValue: Int, isRepaint: Boolean) {
        OpenTrace.mark("pageList.setZoom.internal.begin old=${this.zoom} new=$zoomValue childCount=$childCount")
        if ((zoomValue * MainConstant.ZOOM_ROUND).toInt() == (zoom * MainConstant.ZOOM_ROUND).toInt()) return
        isInitZoom = true
        val pointX = if (pointXValue == Int.MIN_VALUE) width / 2 else pointXValue
        val pointY = if (pointYValue == Int.MIN_VALUE) height / 2 else pointYValue
        val oldZoom = zoom
        zoom = zoomValue
        OpenTrace.mark("pageList.setZoom.internal.afterValue zoom=$zoom")
        pageListViewListener!!.changeZoom()
        OpenTrace.mark("pageList.setZoom.internal.afterChangeZoom")
        post { if (isRepaint) childViewsCache.get(currentIndex)?.let { postRepaint(it) } }
        if (isRepaint) {
            val view = childViewsCache.get(currentIndex)
            val left = view?.left ?: 0
            val top = view?.top ?: 0
            val factor = zoom / oldZoom
            val focusX = pointX - (left + eventManage!!.getScrollX())
            val focusY = pointY - (top + eventManage!!.getScrollY())
            eventManage!!.setScrollAxisValue((focusX - focusX * factor).toInt(), (focusY - focusY * factor).toInt())
            requestLayout()
        }
        OpenTrace.mark("pageList.setZoom.internal.end")
    }
    fun setFitSize(value: Int) { setZoom(getFitZoom(value), true); postInvalidate() }
    @get:JvmName("getFitSizeStateProperty")
    val fitSizeState: Int get() { val item = getCurrentPageView() ?: return 0; val w = kotlin.math.abs(item.width - width); val h = kotlin.math.abs(item.height - height); return if (w < 2 && h < 2) 3 else if (w < 2) 2 else if (h < 2) 1 else 0 }
    fun getFitSizeState(): Int = fitSizeState
    @get:JvmName("getZoomProperty")
    val zoomValue: Float get() = zoom
    fun getZoom() = zoom
    @get:JvmName("getFitZoomProperty")
    val fitZoom: Float get() = getFitZoom(0)
    fun getFitZoom() = getFitZoom(0)
    fun getFitZoom(value: Int): Float {
        OpenTrace.mark("pageList.getFitZoom.begin value=$value index=$currentIndex count=${pageListViewListener!!.getPageCount()} size=${width}x$height")
        if (currentIndex < 0 || currentIndex >= pageListViewListener!!.getPageCount()) return 1f
        val rect = pageListViewListener!!.getPageSize(currentIndex) ?: return 1f
        var viewWidth = width
        var viewHeight = height
        var parent = parent
        while (viewWidth == 0 && parent != null) { if (parent !is View) break; viewWidth = parent.width; viewHeight = parent.height; parent = parent.parent }
        if (viewWidth == 0 || viewHeight == 0) {
            OpenTrace.mark("pageList.getFitZoom.zeroParentSize")
            return 1f
        }
        val maxZoom = MainConstant.MAXZOOM.toFloat() / MainConstant.STANDARD_RATE.toFloat()
        val result = when (value) { 0 -> minOf(viewWidth / rect.width().toFloat(), viewHeight / rect.height().toFloat(), if (pageListViewListener!!.isIgnoreOriginalSize()) maxZoom else 1f); 1 -> minOf(viewWidth / rect.width().toFloat(), maxZoom); 2 -> minOf(viewHeight / rect.height().toFloat(), maxZoom); else -> 1f }
        OpenTrace.mark("pageList.getFitZoom.end rect=${rect.width()}x${rect.height()} parent=${viewWidth}x$viewHeight result=$result")
        return result
    }
    @get:JvmName("getCurrentPageNumberProperty")
    val currentPageNumber: Int get() = currentIndex + 1
    fun getCurrentPageNumber() = currentPageNumber

    override fun getAdapter(): Adapter? = pageAdapter
    override fun setAdapter(adapter: Adapter?) { pageAdapter = adapter }
    override fun getSelectedView(): View? = null
    override fun setSelection(position: Int) { }
    protected fun postUnRepaint(view: APageListItem?) { view?.let { post { it.removeRepaintImageView() } } }
    fun postRepaint(view: APageListItem?) { view?.let { post { it.addRepaintImageView(null) } } }
    @get:JvmName("getCurrentPageViewProperty")
    val currentPageView: APageListItem get() = childViewsCache.get(currentIndex)!!
    fun getCurrentPageView(): APageListItem = currentPageView
    /** The view of the current page, or null while it is not made yet (a document just opened). */
    fun currentPageViewOrNull(): APageListItem? = childViewsCache.get(currentIndex)
    fun getPageListViewListener(): IPageListViewListener = pageListViewListener!!
    private fun createPageView(pageIndex: Int): APageListItem { var view = childViewsCache.get(pageIndex); if (view == null) { view = pageAdapter!!.getView(pageIndex, if (pageViewCache.isEmpty()) null else pageViewCache.removeFirst(), this) as APageListItem; if (view.layoutParams == null) view.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); addViewInLayout(view, 0, view.layoutParams, true); childViewsCache.append(pageIndex, view); view.measure(MeasureSpec.EXACTLY or (view.getPageWidth() * zoom).toInt(), MeasureSpec.EXACTLY or (view.getPageHeight() * zoom).toInt()) }; return view }
    fun getScrollBounds(left: Int, top: Int, right: Int, bottom: Int): Rect { var xmin = width - right; var xmax = -left; var ymin = height - bottom; var ymax = -top; if (xmin > xmax) { xmin = (xmin + xmax) / 2; xmax = xmin }; if (ymin > ymax) { ymin = (ymin + ymax) / 2; ymax = ymin }; return Rect(xmin, ymin, xmax, ymax) }
    fun getScrollBounds(view: View): Rect = getScrollBounds(view.left + eventManage!!.getScrollX(), view.top + eventManage!!.getScrollY(), view.left + view.measuredWidth + eventManage!!.getScrollX(), view.top + view.measuredHeight + eventManage!!.getScrollY())
    fun getCorrection(bounds: Rect) = Point(minOf(maxOf(0, bounds.left), bounds.right), minOf(maxOf(0, bounds.top), bounds.bottom))
    protected fun getScreenSizeOffset(view: View) = Point(maxOf((width - view.measuredWidth) / 2, 0), maxOf((height - view.measuredHeight) / 2, 0))
    @get:JvmName("getPageCountProperty")
    val pageCount: Int get() = pageListViewListener!!.getPageCount()
    fun getPageCount() = pageListViewListener!!.getPageCount()
    fun getPageListItem(position: Int, convertView: View?, parent: ViewGroup?) = pageListViewListener!!.getPageListItem(position, convertView, parent)
    fun isInit() = isInit
    fun setDoRequstLayout(value: Boolean) { isDoRequestLayout = value }
    fun isInitZoom() = isInitZoom
    fun setInitZoom(value: Boolean) { isInitZoom = value }
    fun dispose() { eventManage?.dispose(); eventManage = null; (pageAdapter as? APageListAdapter)?.dispose(); pageAdapter = null; for (i in 0 until childViewsCache.size()) childViewsCache.valueAt(i).dispose(); childViewsCache.clear(); for (page in pageViewCache) page.dispose(); pageViewCache.clear() }

    private var isDoRequestLayout = true
    private var isConfigurationChanged = false
    @get:JvmName("isInitProperty")
    internal var isInit = false
    @get:JvmName("isInitZoomProperty")
    internal var isInitZoom = false
    private var isResetLayout = false
    internal var zoom = 1f
    private var currentIndex = 0
    internal lateinit var pageListViewListener: IPageListViewListener
    private var pageAdapter: Adapter? = null
    private var eventManage: APageListEventManage? = null
    private val childViewsCache = SparseArray<APageListItem>(3)
    private val pageViewCache = LinkedList<APageListItem>()
}
