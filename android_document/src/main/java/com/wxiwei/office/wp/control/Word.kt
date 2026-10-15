/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
@file:Suppress("DEPRECATION", "REDUNDANT_CAST", "USELESS_ELVIS")

package com.wxiwei.office.wp.control

import com.wxiwei.office.constant.wp.WPModelConstant
import com.wxiwei.office.system.*

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import com.wxiwei.office.common.IOfficeToPicture
import com.wxiwei.office.common.picture.PictureKit
import com.wxiwei.office.common.shape.IShape
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.constant.wp.WPViewConstant
import com.wxiwei.office.java.awt.Rectangle
import com.wxiwei.office.pg.animate.FadeAnimation
import com.wxiwei.office.simpletext.control.Highlight
import com.wxiwei.office.simpletext.control.IHighlight
import com.wxiwei.office.simpletext.control.IWord
import com.wxiwei.office.simpletext.model.AttrManage
import com.wxiwei.office.simpletext.model.IDocument
import com.wxiwei.office.simpletext.view.IView
import com.wxiwei.office.system.IControl
import com.wxiwei.office.system.IDialogAction
import com.wxiwei.office.system.SysKit
import com.wxiwei.office.system.beans.pagelist.APageListView
import com.wxiwei.office.wp.view.LayoutKit
import com.wxiwei.office.wp.view.NormalRoot
import com.wxiwei.office.wp.view.PageRoot
import com.wxiwei.office.wp.view.PageView
import com.wxiwei.office.wp.view.WPViewKit
import kotlin.math.max
import kotlin.math.min

class Word : LinearLayout, IWord {
    private var preShowPageIndex = -1
    private var prePageCount = -1
    private var isExportImageAfterZoom = false
    private var initFinish = false
    private var isStopDraw = false
    private var currentRootType = 0
    protected var mWidth = 0
    protected var mHeight = 0
    @JvmField
    protected var zoom = 1f
    private var normalZoom = 1f
    @JvmField
    protected var control: IControl? = null
    protected var doc: IDocument? = null
    @JvmField
    protected var status: StatusManage? = null
    @JvmField
    protected var highlight: IHighlight? = null
    @JvmField
    protected var eventManage: WPEventManage? = null
    private var filePath: String? = null
    private var dialogAction: IDialogAction? = null
    private var pageRoot: PageRoot? = null
    private var normalRoot: NormalRoot? = null
    private var printWord: PrintWord? = null
    private var paint: Paint? = null
    private var wpFind: WPFind? = null
    private var visibleRect: Rectangle? = null
    private val listViewAdd = ArrayList<View>()

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, doc: IDocument, filePath: String, control: IControl) : super(context) {
        this.control = control
        this.doc = doc
        this.filePath = filePath
        val defaultMode = control.getMainFrame().getWordDefaultView().toInt()
        setCurrentRootType(defaultMode)
        when (defaultMode) {
            WPViewConstant.NORMAL_ROOT.toInt() -> normalRoot = NormalRoot(this)
            WPViewConstant.PAGE_ROOT.toInt() -> pageRoot = PageRoot(this)
            WPViewConstant.PRINT_ROOT.toInt() -> {
                pageRoot = PageRoot(this)
                printWord = PrintWord(context, control, pageRoot!!)
                addView(printWord)
            }
        }
        dialogAction = WPDialogAction(control)
        paint = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.SANS_SERIF
            textSize = 24f
        }
        visibleRect = Rectangle()
        initManage()
        if (defaultMode == WPViewConstant.PRINT_ROOT.toInt()) {
            setOnClickListener(null)
        }
    }

    private fun initManage() {
        eventManage = WPEventManage(this, control!!)
        setOnTouchListener(eventManage)
        isLongClickable = true
        wpFind = WPFind(this)
        status = StatusManage()
        highlight = Highlight(this)
    }

    private fun updateDefaultZoomByPageMode(pageRoot: PageRoot) {
        zoom = getDefaultZoom(pageRoot.getWidth())
        // The first page was positioned at zoom 1; re-center it for the fit-width zoom, otherwise
        // documents that finish layout without the background thread stay shifted to the right.
        LayoutKit.instance().layoutAllPage(pageRoot, zoom)
    }

    fun init() {
        val normalRoot = normalRoot
        val pageRoot = pageRoot
        if (normalRoot != null) {
            normalRoot.doLayout(0, 0, mWidth, mHeight, Int.MAX_VALUE, 0)
        } else if (pageRoot != null) {
            pageRoot.doLayout(0, 0, mWidth, mHeight, Int.MAX_VALUE, 0)
            updateDefaultZoomByPageMode(pageRoot)
        }
        initFinish = true
        printWord?.init()
        if (getCurrentRootType() == WPViewConstant.PRINT_ROOT.toInt()) {
            return
        }
        post { control?.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null) }
    }

    override fun onDraw(canvas: Canvas) {
        if (!initFinish || currentRootType == WPViewConstant.PRINT_ROOT.toInt() || isStopDraw) {
            return
        }
        try {
            if (getCurrentRootType() == WPViewConstant.PAGE_ROOT.toInt()) {
                pageRoot?.draw(canvas, 0, 0, zoom)
                drawPageNubmer(canvas, zoom)
            } else if (getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()) {
                normalRoot?.draw(canvas, 0, 0, normalZoom)
            }
        } catch (e: Exception) {
            control?.getSysKit()?.getErrorKit()?.writerLog(e)
        }
    }

    fun createPicture() {
        val otp = control?.officeToPicture
        if (otp != null && otp.modeType == IOfficeToPicture.VIEW_CHANGE_END) {
            try {
                toPicture(otp)
            } catch (e: Exception) {
            }
        }
    }

    private fun toPicture(otp: IOfficeToPicture) {
        if (getCurrentRootType() == WPViewConstant.PRINT_ROOT.toInt()) {
            val item = printWord?.getListView()?.currentPageView as? WPPageListItem
            item?.addRepaintImageView(null)
            return
        }
        val b = PictureKit.instance().isDrawPictrue
        PictureKit.instance().isDrawPictrue = true
        val bitmap = otp.getBitmap(width, height) ?: return
        var paintZoom = getZoom()
        var tX = -scrollX.toFloat()
        var tY = -scrollY.toFloat()
        if (bitmap.width != width || bitmap.height != height) {
            val newZoom = min(bitmap.width.toFloat() / width, bitmap.height.toFloat() / height) * getZoom()
            val pageWidth = pageRoot?.getChildView()?.getWidth()?.times(newZoom) ?: 0f
            var x = 0f
            if (pageWidth > bitmap.width || getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()) {
                x = scrollX / paintZoom * newZoom
                x = min(x, getWordWidth() * newZoom - bitmap.width)
            }
            var y = scrollY / paintZoom * newZoom
            y = min(y, getWordHeight() * newZoom - height)
            tX = -max(0f, x)
            tY = -max(0f, y)
            paintZoom = newZoom
        }
        val canvas = Canvas(bitmap)
        canvas.translate(tX, tY)
        canvas.drawColor(Color.GRAY)
        if (getCurrentRootType() == WPViewConstant.PAGE_ROOT.toInt()) {
            pageRoot?.draw(canvas, 0, 0, paintZoom)
        } else if (getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()) {
            normalRoot?.draw(canvas, 0, 0, paintZoom)
        }
        otp.callBack(bitmap)

        PictureKit.instance().isDrawPictrue = b
    }

    fun getSnapshot(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        if (getCurrentRootType() == WPViewConstant.PRINT_ROOT.toInt() && printWord != null) {
            return printWord!!.getSnapshot(bitmap)
        }
        val b = PictureKit.instance().isDrawPictrue
        PictureKit.instance().isDrawPictrue = true
        var paintZoom = getZoom()
        var tX = -scrollX.toFloat()
        var tY = -scrollY.toFloat()
        if (bitmap.width != width || bitmap.height != height) {
            val newZoom = min(bitmap.width.toFloat() / width, bitmap.height.toFloat() / height) * getZoom()
            val pageWidth = pageRoot?.getChildView()?.getWidth()?.times(newZoom) ?: 0f
            var x = 0f
            if (pageWidth > bitmap.width || getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()) {
                x = scrollX / paintZoom * newZoom
                x = min(x, getWordWidth() * newZoom - bitmap.width)
            }
            var y = scrollY / paintZoom * newZoom
            y = min(y, getWordHeight() * newZoom - height)
            tX = -max(0f, x)
            tY = -max(0f, y)
            paintZoom = newZoom
        }
        val canvas = Canvas(bitmap)
        canvas.translate(tX, tY)
        canvas.drawColor(Color.GRAY)
        if (getCurrentRootType() == WPViewConstant.PAGE_ROOT.toInt()) {
            pageRoot?.draw(canvas, 0, 0, paintZoom)
        } else if (getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()) {
            normalRoot?.draw(canvas, 0, 0, paintZoom)
        }
        PictureKit.instance().isDrawPictrue = b
        return bitmap
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initFinish) return
        eventManage?.stopFling()
        pageRoot?.let { LayoutKit.instance().layoutAllPage(it, zoom) }
        if (currentRootType == WPViewConstant.PAGE_ROOT.toInt()) {
            val r = getVisibleRect()
            var sX = r.x
            var sY = r.y
            val wW = (getWordWidth() * zoom).toInt()
            val wH = (getWordHeight() * zoom).toInt()
            if (r.x + r.width > wW) sX = wW - r.width
            if (r.y + r.height > wH) sY = wH - r.height
            if (sX != r.x || sY != r.y) {
                scrollTo(max(0, sX), max(0, sY))
            }
        }
        if (w != oldw && control?.mainFrame?.isZoomAfterLayoutForWord() == true) {
            layoutNormal()
            setExportImageAfterZoom(true)
        }
        post { control?.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null) }
    }

    /**
     * Lays the document out again from the model after an edit, keeping the scroll position.
     * The first page is laid out now, the rest in the background like when opening.
     */
    /**
     * Lays the document out again after a live edit. With [fromOffset] (the start of the edit),
     * pages before it keep their layout, which keeps typing fast in long documents.
     */
    fun relayoutContent(fromOffset: Long = -1) {
        // a header or footer shows on every page: lay out from the first page
        if (fromOffset >= 0 && (fromOffset and WPModelConstant.AREA_MASK) != WPModelConstant.MAIN) return relayoutContent(0)
        when (currentRootType) {
            WPViewConstant.PAGE_ROOT.toInt() -> {
                val old = pageRoot ?: return
                if (fromOffset >= 0 && old.relayoutFrom(fromOffset, ((scrollY + height) / zoom).toInt() + 1, zoom)) {
                    postInvalidate()
                    return
                }
                val sx = scrollX
                val sy = scrollY
                old.dispose()
                val root = PageRoot(this)
                pageRoot = root
                root.doLayout(0, 0, mWidth, mHeight, Int.MAX_VALUE, 0)
                LayoutKit.instance().layoutAllPage(root, zoom)
                // the pages down to where the view was, or scrollTo stops at the first ones
                if (fromOffset >= 0) root.layoutDownTo(((sy + height) / zoom).toInt() + 1, zoom)
                scrollTo(sx, sy)
                postInvalidate()
            }
            WPViewConstant.NORMAL_ROOT.toInt() -> {
                val root = normalRoot ?: return
                root.stopBackLayout()
                root.layoutAll()
                postInvalidate()
            }
        }
    }

    fun layoutNormal() {
        val normalRoot = normalRoot ?: return
        normalRoot.stopBackLayout()
        post {
            if (currentRootType == WPViewConstant.NORMAL_ROOT.toInt()) {
                scrollTo(0, scrollY)
            }
            normalRoot.layoutAll()
            postInvalidate()
        }
    }

    fun layoutPrintMode() {
        post {
            if (currentRootType == WPViewConstant.PRINT_ROOT.toInt() && printWord != null) {
                val listView: APageListView = printWord!!.getListView()
                if (listView.childCount == 1) {
                    listView.requestLayout()
                }
            }
        }
    }

    override fun computeScroll() {
        if (getCurrentRootType() == WPViewConstant.PRINT_ROOT.toInt()) return
        eventManage?.computeScroll()
    }

    fun switchView(rootType: Int) {
        if (rootType == getCurrentRootType()) return
        eventManage?.stopFling()
        setCurrentRootType(rootType)
        PictureKit.instance().isDrawPictrue = true
        when (getCurrentRootType()) {
            WPViewConstant.NORMAL_ROOT.toInt() -> {
                if (normalRoot == null) {
                    normalRoot = NormalRoot(this)
                    normalRoot!!.doLayout(0, 0, mWidth, mHeight, Int.MAX_VALUE, 0)
                }
                setOnTouchListener(eventManage)
                printWord?.visibility = INVISIBLE
            }

            WPViewConstant.PAGE_ROOT.toInt() -> {
                if (pageRoot == null) {
                    pageRoot = PageRoot(this)
                    pageRoot!!.doLayout(0, 0, mWidth, mHeight, Int.MAX_VALUE, 0)
                    updateDefaultZoomByPageMode(pageRoot!!)
                } else {
                    LayoutKit.instance().layoutAllPage(pageRoot, zoom)
                }
                setOnTouchListener(eventManage)
                printWord?.visibility = INVISIBLE
            }

            WPViewConstant.PRINT_ROOT.toInt() -> {
                if (pageRoot == null) {
                    pageRoot = PageRoot(this)
                    pageRoot!!.doLayout(0, 0, mWidth, mHeight, Int.MAX_VALUE, 0)
                    updateDefaultZoomByPageMode(pageRoot!!)
                }
                if (printWord == null) {
                    printWord = PrintWord(context, control!!, pageRoot!!)
                    val bg = control!!.mainFrame.viewBackground
                    if (bg != null) {
                        if (bg is Int) {
                            printWord!!.setBackgroundColor(bg)
                        } else if (bg is Drawable) {
                            printWord!!.setBackgroundDrawable(bg)
                        }
                    }
                    addView(printWord)
                    listViewAdd.add(printWord!!)
                    Log.d("InitDocSlide", "initDocSlide: ${listViewAdd.size}")
                    post {
                        printWord?.init()
                        printWord?.postInvalidate()
                    }
                } else {
                    printWord!!.visibility = VISIBLE
                }
                scrollTo(0, 0)
                setOnClickListener(null)
                return
            }
        }
        post {
            scrollTo(0, scrollY)
            postInvalidate()
        }
    }

    fun getVisibleRect(): Rectangle {
        val rect = visibleRect ?: Rectangle().also { visibleRect = it }
        rect.x = scrollX
        rect.y = scrollY
        rect.width = width
        rect.height = height
        return rect
    }

    fun setZoom(zoom: Float, pointX: Int, pointY: Int) {
        var oldZoom = 1.0f
        if (currentRootType == WPViewConstant.PAGE_ROOT.toInt()) {
            oldZoom = this.zoom
            this.zoom = zoom
            pageRoot?.let { LayoutKit.instance().layoutAllPage(it, zoom) }
        } else if (currentRootType == WPViewConstant.PRINT_ROOT.toInt()) {
            printWord?.setZoom(zoom, pointX, pointY)
            return
        } else if (currentRootType == WPViewConstant.NORMAL_ROOT.toInt()) {
            oldZoom = normalZoom
            normalZoom = zoom
        }
        scrollToFocusXY(zoom, oldZoom, pointX, pointY)
    }

    fun setFitSize(value: Int) {
        if (currentRootType == WPViewConstant.PRINT_ROOT.toInt()) {
            printWord?.setFitSize(value)
        }
    }

    fun getFitSizeState(): Int {
        if (currentRootType == WPViewConstant.PRINT_ROOT.toInt()) {
            return printWord?.getFitSizeState() ?: 0
        }
        return 0
    }

    private fun scrollToFocusXY(newScale: Float, oldScale: Float, focusScreenX0: Int, focusScreenY0: Int) {
        var focusScreenX = focusScreenX0
        var focusScreenY = focusScreenY0
        if (focusScreenX == Int.MIN_VALUE && focusScreenY == Int.MIN_VALUE) {
            focusScreenX = width / 2
            focusScreenY = height / 2
        }
        val viewpageWidth: Float
        val viewpageHeight: Float
        if (getCurrentRootType() == WPViewConstant.PAGE_ROOT.toInt() && pageRoot != null && pageRoot!!.getChildView() != null) {
            viewpageWidth = pageRoot!!.getChildView()!!.getWidth().toFloat()
            viewpageHeight = pageRoot!!.getChildView()!!.getHeight().toFloat()
        } else {
            viewpageWidth = width.toFloat()
            viewpageHeight = height.toFloat()
        }
        val lastpageHeight = (viewpageHeight * oldScale).toInt()
        val ratioY = 1.0f * (scrollY + focusScreenY) / lastpageHeight
        val lastpageWidth = (viewpageWidth * oldScale).toInt()
        val ratioX = 1.0f * (scrollX + focusScreenX) / lastpageWidth
        val pageHeight = (viewpageHeight * newScale).toInt()
        val pageWidth = (viewpageWidth * newScale).toInt()
        scrollBy(((pageWidth - lastpageWidth) * ratioX).toInt(), ((pageHeight - lastpageHeight) * ratioY).toInt())
    }

    override fun scrollTo(x: Int, y: Int) {
        var sx = min(max(x, 0), (getWordWidth() * getZoom() - width).toInt())
        var sy = min(max(y, 0), (getWordHeight() * getZoom() - height).toInt())
        sx = max(sx, 0)
        sy = max(sy, 0)
        super.scrollTo(sx, sy)
    }

    fun getCurrentPageNumber(): Int {
        if (currentRootType == WPViewConstant.NORMAL_ROOT.toInt() || pageRoot == null) return 1
        if (getCurrentRootType() == WPViewConstant.PRINT_ROOT.toInt()) return printWord?.getCurrentPageNumber() ?: 1
        val pv = WPViewKit.instance().getPageView(pageRoot, (scrollX / zoom).toInt(), (scrollY / zoom).toInt() + height / 3)
        return pv?.getPageNumber() ?: 1
    }

    fun getPageSize(pageIndex: Int): Rectangle? {
        val pageRoot = pageRoot
        if (pageRoot == null || currentRootType == WPViewConstant.NORMAL_ROOT.toInt()) {
            return Rectangle(0, 0, width, height)
        }
        if (pageIndex < 0 || pageIndex > pageRoot.getChildCount()) {
            return null
        }
        val pv = WPViewKit.instance().getPageView(pageRoot, (scrollX / zoom).toInt(), (scrollY / zoom).toInt() + height / 5)
        if (pv == null) {
            val attr = doc!!.getSection(0)!!.getAttribute()
            val pageWidth = (AttrManage.instance().getPageWidth(attr) * MainConstant.TWIPS_TO_PIXEL).toInt()
            val pageHeight = (AttrManage.instance().getPageHeight(attr) * MainConstant.TWIPS_TO_PIXEL).toInt()
            return Rectangle(0, 0, pageWidth, pageHeight)
        }
        return Rectangle(0, 0, pv.getWidth(), pv.getHeight())
    }

    private fun drawPageNubmer(canvas: Canvas, zoom: Float) {
        val control = control ?: return
        val pageRoot = pageRoot ?: return
        val paint = paint ?: return
        val currentNumber = getCurrentPageNumber()
        if (control.getMainFrame().isDrawPageNumber()) {
            val rect: Rect = canvas.clipBounds
            if (rect.width() != width || rect.height() != height) return
            val pn = currentNumber.toString() + " / " + pageRoot.getPageCount().toString()
            Log.e("drawPageNubmer", " $pn")
            val w = paint.measureText(pn).toInt()
            val h = (paint.descent() - paint.ascent()).toInt()
            val x = (rect.right + scrollX - w) / 2
            var y = rect.bottom - h - 20
            val drawable = SysKit.getPageNubmerDrawable()
            drawable.setBounds(x - 10, y - 10, x + w + 10, y + h + 10)
            drawable.draw(canvas)
            y = (y - paint.ascent()).toInt()
            canvas.drawText(pn, x.toFloat(), y.toFloat(), paint)
        }
        val pageCount = getPageCount()
        if (preShowPageIndex != currentNumber || prePageCount != pageCount) {
            control.getMainFrame().changePage(currentNumber, pageCount)
            preShowPageIndex = currentNumber
            prePageCount = pageCount
        }
    }

    override fun viewToModel(x: Int, y: Int, isBack: Boolean): Long {
        return when (getCurrentRootType()) {
            WPViewConstant.PAGE_ROOT.toInt() -> pageRoot?.viewToModel(x, y, isBack) ?: 0
            WPViewConstant.NORMAL_ROOT.toInt() -> normalRoot?.viewToModel(x, y, isBack) ?: 0
            WPViewConstant.PRINT_ROOT.toInt() -> printWord?.viewToModel(x, y, isBack) ?: 0
            else -> 0
        }
    }

    override fun modelToView(offset: Long, rect: Rectangle, isBack: Boolean): Rectangle {
        return when (getCurrentRootType()) {
            WPViewConstant.PAGE_ROOT.toInt() -> pageRoot?.modelToView(offset, rect, isBack) ?: rect
            WPViewConstant.NORMAL_ROOT.toInt() -> normalRoot?.modelToView(offset, rect, isBack) ?: rect
            WPViewConstant.PRINT_ROOT.toInt() -> printWord?.modelToView(offset, rect, isBack) ?: rect
            else -> rect
        }
    }

    fun getRoot(rootType: Int): IView? {
        return when (rootType) {
            WPViewConstant.PAGE_ROOT.toInt() -> pageRoot
            WPViewConstant.NORMAL_ROOT.toInt() -> normalRoot
            else -> null
        }
    }

    override fun getText(start: Long, end: Long): String = doc!!.getText(start, end)

    fun getDialogAction(): IDialogAction? = dialogAction

    fun getFind(): WPFind = wpFind!!

    fun getFilePath(): String? = filePath

    override fun getHighlight(): IHighlight = highlight!!

    override fun getDocument(): IDocument = doc!!

    override fun getControl(): IControl = control!!

    fun getStatus(): StatusManage = status!!

    fun getEventManage(): WPEventManage? = eventManage

    fun setWordWidth(mWidth: Int) {
        this.mWidth = mWidth
    }

    fun setWordHeight(mHeight: Int) {
        this.mHeight = mHeight
    }

    fun setSize(w: Int, h: Int) {
        mWidth = w
        mHeight = h
    }

    fun getWordHeight(): Int {
        return when (getCurrentRootType()) {
            WPViewConstant.PAGE_ROOT.toInt() -> mHeight
            WPViewConstant.NORMAL_ROOT.toInt() -> normalRoot?.getHeight() ?: height
            else -> height
        }
    }

    /** Gap between pages and around them in page view, in px at zoom 1; see [IMainFrame.getWordPageSpacing]. */
    fun getPageSpacing(): Int =
        control?.getMainFrame()?.getWordPageSpacing()?.coerceAtLeast(0) ?: WPViewConstant.PAGE_SPACE.toInt()

    fun getWordWidth(): Int {
        return when (getCurrentRootType()) {
            WPViewConstant.PAGE_ROOT.toInt() -> mWidth
            WPViewConstant.NORMAL_ROOT.toInt() -> normalRoot?.getWidth() ?: width
            else -> width
        }
    }

    fun showPage(index: Int, direction: Int) {
        if (index < 0 || index >= getPageCount() || getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()) return
        if (getCurrentRootType() == WPViewConstant.PRINT_ROOT.toInt()) {
            when (direction) {
                EventConstant.APP_PAGE_UP_ID -> printWord?.previousPageview()
                EventConstant.APP_PAGE_DOWN_ID -> printWord?.nextPageView()
                else -> printWord?.showPDFPageForIndex(index)
            }
            return
        }
        val view: IView? = pageRoot?.getPageView(index)
        if (view != null) {
            scrollTo(scrollX, (view.getY() * zoom).toInt())
        }
    }

    fun pageToImage(pageNumber: Int): Bitmap? {
        val pageRoot = pageRoot
        if (pageNumber <= 0 || pageNumber > getPageCount() || pageRoot == null ||
            pageRoot.getChildView() == null || getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()
        ) {
            return null
        }
        val view = pageRoot.getPageView(pageNumber - 1) ?: return null
        val bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-view.getX().toFloat(), -view.getY().toFloat())
        canvas.drawColor(Color.WHITE)
        view.draw(canvas, 0, 0, 1f)
        return bitmap
    }

    /** Current layout state, handed to [IMainFrame.completeLayout]. */
    fun layoutInfo(): LayoutInfo {
        val size = getPageBounds(1)?.let { it.width() to it.height() }
            ?: getPageSize(1)?.let { it.width to it.height }
            ?: (0 to 0)
        return LayoutInfo(
            pageNumber = getCurrentPageNumber(),
            pageCount = getPageCount(),
            pageWidth = size.first,
            pageHeight = size.second,
            zoom = getZoom(),
            fitZoom = getFitZoom(),
            viewMode = getCurrentRootType(),
        )
    }

    /** Page size in layout pixels (zoom 1), or null when the page is not laid out. */
    fun getPageBounds(pageNumber: Int): Rect? {
        val pageRoot = pageRoot
        if (pageNumber <= 0 || pageNumber > getPageCount() || pageRoot == null ||
            pageRoot.getChildView() == null || getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()
        ) {
            return null
        }
        val view = pageRoot.getPageView(pageNumber - 1) ?: return null
        return Rect(0, 0, view.getWidth(), view.getHeight())
    }

    /**
     * Draws a page as vector content onto [canvas] (e.g. a PdfDocument page) at zoom 1,
     * so text stays sharp instead of being rasterized.
     */
    fun drawPage(pageNumber: Int, canvas: Canvas): Boolean {
        val pageRoot = pageRoot
        if (pageNumber <= 0 || pageNumber > getPageCount() || pageRoot == null ||
            pageRoot.getChildView() == null || getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()
        ) {
            return false
        }
        val view = pageRoot.getPageView(pageNumber - 1) ?: return false
        val forced = PictureKit.instance().setForceDrawOnCurrentThread(true)
        val saved = canvas.save()
        try {
            canvas.drawColor(Color.WHITE)
            canvas.translate(-view.getX().toFloat(), -view.getY().toFloat())
            view.draw(canvas, 0, 0, 1f)
        } finally {
            canvas.restoreToCount(saved)
            PictureKit.instance().setForceDrawOnCurrentThread(forced)
        }
        return true
    }

    fun pageAreaToImage(pageNumber: Int, srcLeft: Int, srcTop: Int, srcWidth: Int, srcHeight: Int, desWidth: Int, desHeight: Int): Bitmap? {
        val pageRoot = pageRoot
        if (pageNumber <= 0 || pageNumber > getPageCount() || pageRoot == null ||
            pageRoot.getChildView() == null || getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt()
        ) {
            return null
        }
        val view = pageRoot.getPageView(pageNumber - 1)
        if (view != null && SysKit.isValidateRect(view.getWidth(), view.getHeight(), srcLeft, srcTop, srcWidth, srcHeight)) {
            val b = PictureKit.instance().isDrawPictrue
            PictureKit.instance().isDrawPictrue = true
            val paintZoom = min(desWidth / srcWidth.toFloat(), desHeight / srcHeight.toFloat())
            val bitmap = try {
                Bitmap.createBitmap((srcWidth * paintZoom).toInt(), (srcHeight * paintZoom).toInt(), Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                return null
            } ?: return null
            val tX = -(srcLeft + view.getX()) * paintZoom
            val tY = -(srcTop + view.getY()) * paintZoom
            val canvas = Canvas(bitmap)
            canvas.translate(tX, tY)
            canvas.drawColor(Color.WHITE)
            view.draw(canvas, 0, 0, paintZoom)
            PictureKit.instance().isDrawPictrue = b
            return bitmap
        }
        return null
    }

    fun getThumbnail(zoom: Float): Bitmap? {
        val size = getPageSize(1)
        if (size != null) {
            val thumbnailWidth = Math.round(size.width * zoom)
            val thumbnailHeight = Math.round(size.height * zoom)
            return pageAreaToImage(1, 0, 0, size.width, size.height, thumbnailWidth, thumbnailHeight)
        }
        return null
    }

    fun getPageCount(): Int {
        if (currentRootType == WPViewConstant.NORMAL_ROOT.toInt() || pageRoot == null) return 1
        return pageRoot!!.getPageCount()
    }

    /** False while the pages are being laid out (on opening, or again after an edit); [getPageCount] is not final then. */
    fun isLayoutFinished(): Boolean = currentRootType == WPViewConstant.NORMAL_ROOT.toInt() || pageRoot?.isFinishLayout() ?: true

    fun getCurrentRootType(): Int = currentRootType

    fun setCurrentRootType(currentRootType: Int) {
        this.currentRootType = currentRootType
    }

    fun getZoom(): Float {
        return when (currentRootType) {
            WPViewConstant.NORMAL_ROOT.toInt() -> normalZoom
            WPViewConstant.PAGE_ROOT.toInt() -> zoom
            WPViewConstant.PRINT_ROOT.toInt() -> printWord?.getZoom() ?: zoom
            else -> zoom
        }
    }

    fun getFitZoom(): Float {
        var pageWidth = -1
        if (currentRootType == WPViewConstant.NORMAL_ROOT.toInt()) return 0.5f
        val pageRoot = pageRoot ?: return 1f
        var z = 1f
        if (currentRootType == WPViewConstant.PRINT_ROOT.toInt()) {
            return printWord!!.getFitZoom()
        } else if (currentRootType == WPViewConstant.PAGE_ROOT.toInt()) {
            val view = pageRoot.getChildView()
            pageWidth = view?.getWidth() ?: 0
            if (pageWidth == 0) {
                pageWidth = (AttrManage.instance().getPageWidth(doc!!.getSection(0)!!.getAttribute()) * MainConstant.TWIPS_TO_PIXEL).toInt()
            }
            var viewWidth = width
            if (viewWidth == 0) {
                viewWidth = (parent as View).width
            }
            // Fit the page with its side gaps, so they match the gaps between pages.
            z = viewWidth.toFloat() / (pageWidth + getPageSpacing() * 2)
        }
        return min(z, if (pageWidth == -1) 1f else getDefaultZoom(pageWidth))
    }

    private fun getDefaultZoom(pageWidth: Int): Float {
        val fullScreenWidth = Resources.getSystem().displayMetrics.widthPixels.toFloat()
        return fullScreenWidth / pageWidth
    }

    override fun getEditType(): Byte = MainConstant.APPLICATION_TYPE_PPT

    fun isExportImageAfterZoom(): Boolean = isExportImageAfterZoom

    fun setExportImageAfterZoom(isExportImageAfterZoom: Boolean) {
        this.isExportImageAfterZoom = isExportImageAfterZoom
    }

    override fun getParagraphAnimation(pargraphID: Int): FadeAnimation? = null

    override fun getTextBox(): IShape? = null

    override fun setBackgroundColor(color: Int) {
        super.setBackgroundColor(color)
        printWord?.setBackgroundColor(color)
    }

    override fun setBackgroundResource(resid: Int) {
        super.setBackgroundResource(resid)
        printWord?.setBackgroundResource(resid)
    }

    fun setStopDraw(isStopDraw: Boolean) {
        Log.e("Word", "setStopDraw = $isStopDraw")
        this.isStopDraw = isStopDraw
    }

    @Deprecated("Deprecated in Java")
    override fun setBackgroundDrawable(d: Drawable?) {
        super.setBackgroundDrawable(d)
        printWord?.setBackgroundDrawable(d)
    }

    fun getPrintWord(): PrintWord = printWord!!

    fun updateFieldText() {
        if (pageRoot != null && pageRoot!!.checkUpdateHeaderFooterFieldText()) {
            control?.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
        }
    }

    fun canBackLayout(): Boolean {
        pageRoot?.let { return it.canBackLayout() }
        normalRoot?.let { return it.canBackLayout() }
        return false
    }

    fun setLayoutThreadDied(isDied: Boolean) {
        pageRoot?.setLayoutThreadDied(isDied)
        normalRoot?.setLayoutThreadDied(isDied)
    }

    fun isEndFile(): Boolean = getCurrentPageNumber() == (pageRoot?.getPageCount() ?: 1) - 1

    fun jumpToPage(page: Int): Boolean {
        val pageRoot = pageRoot ?: return false
        if (page !in 1..getPageCount() ||
            getCurrentRootType() == WPViewConstant.NORMAL_ROOT.toInt() ||
            pageRoot.getPageView(page - 1) == null
        ) return false
        if (getCurrentRootType() == WPViewConstant.PRINT_ROOT.toInt() && printWord == null) return false
        showPage(page - 1, 0)
        return true
    }

    override fun dispose() {
        control = null
        status?.dispose()
        status = null
        highlight?.dispose()
        highlight = null
        eventManage?.dispose()
        eventManage = null
        pageRoot?.dispose()
        pageRoot = null
        normalRoot?.dispose()
        normalRoot = null
        dialogAction?.dispose()
        dialogAction = null
        wpFind?.dispose()
        wpFind = null
        doc?.dispose()
        doc = null
        printWord?.dispose()
        setOnClickListener(null)
        paint = null
        visibleRect = null
    }

    companion object {
        @JvmStatic
        fun base64ToBitmap(base64Str: String?): Bitmap? {
            var source = base64Str ?: return null
            if (source.contains(",")) {
                source = source.substring(source.indexOf(",") + 1)
            }
            val decoded = Base64.decode(source, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        }

        @JvmStatic
        fun viewToBitmap(view: View): Bitmap {
            if (view.width == 0 || view.height == 0) {
                val widthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            return bitmap
        }
    }
}
