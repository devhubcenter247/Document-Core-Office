/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:          Presentation.java
 *
 * 编译器:            android2.2
 * 时间:              下午3:49:28
 */
@file:Suppress("UNNECESSARY_LATEINIT", "SENSELESS_COMPARISON")

package com.wxiwei.office.pg.control

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import com.wxiwei.office.common.IOfficeToPicture
import com.wxiwei.office.common.ISlideShow
import com.wxiwei.office.common.picture.PictureKit
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.java.awt.Dimension
import com.wxiwei.office.pg.model.PGModel
import com.wxiwei.office.pg.model.PGSlide
import com.wxiwei.office.pg.view.SlideDrawKit
import com.wxiwei.office.pg.view.SlideShowView
import com.wxiwei.office.simpletext.model.IDocument
import com.wxiwei.office.system.IControl
import com.wxiwei.office.system.IFind
import com.wxiwei.office.system.OpenTrace
import com.wxiwei.office.system.SysKit
import com.wxiwei.office.system.beans.CalloutView.CalloutView
import com.wxiwei.office.system.beans.CalloutView.IExportListener

/**
 * 文件注释
 * <p>
 * <p>
 * Read版本:        Read V1.0
 * <p>
 * 作者:            ljj8494
 * <p>
 * 日期:            2011-11-2
 * <p>
 * 负责人:          ljj8494
 * <p>
 * 负责小组:
 * <p>
 * <p>
 */
open class Presentation(activity: Activity, pgModel: PGModel?, control: IControl?) : FrameLayout(activity), IFind, IExportListener {

    //
    private var isConfigurationChanged = false
    //
    private var init = false
    //
    private var preShowSlideIndex = -1
    // 当前显示的slide index值
    private var currentIndex = -1
    // 组件的宽度
    private var mWidth = 0
    // 组件的高度
    private var mHeight = 0
    //current zoom value
    private var zoom = 1f
    //
    private var pgFind: PGFind? = null
    //
    //private ViewGroup.LayoutParams layoutParams;
    //
    private val editor: PGEditor
    //
    private var control: IControl? = control
    //
    private var currentSlide: PGSlide? = null
    // PG model 后期需修改
    private var pgModel: PGModel? = pgModel
    //
    private var slideView: SlideShowView? = null
    //
    private var eventManage: PGEventManage? = null

    //
    private var slideshow = false
    private var slideIndex_SlideShow = 0
    private var fitZoom = 1f
    private var slideSize: Rect? = null

    /**
     * lateinit: may be accessed from FrameLayout's constructor (setBackground*) before assignment
     */
    private lateinit var pgPrintMode: PGPrintMode
    private var callouts: CalloutView? = null

    /**
     *
     */
    init {
        val start = android.os.SystemClock.uptimeMillis()
        OpenTrace.mark("presentation.constructor.begin slides=${pgModel?.getSlideCount()} loaded=${pgModel?.getRealSlideCount()}")
        setLongClickable(true)

        pgFind = PGFind(this)
        //
        editor = PGEditor(this)
        //
        pgPrintMode = PGPrintMode(activity, control!!, pgModel!!, editor)
        //
        addView(pgPrintMode)
        OpenTrace.mark("presentation.constructor.end", start)
    }

    fun initCalloutView() {
        if (slideshow) {
            if (callouts == null) {
                callouts = CalloutView(this.getContext(), control!!, this)
                callouts!!.setIndex(slideIndex_SlideShow)
                addView(callouts)
            }
        } else {
            pgPrintMode.getListView()!!.getCurrentPageView().initCalloutView()
        }
    }

    override fun exportImage() {
        if (slideshow) {
            createPicture()
        } else {
            pgPrintMode.exportImage(pgPrintMode.getListView()!!.getCurrentPageView(), null)
        }
    }

    /**
     *
     */
    fun init() {
        val start = android.os.SystemClock.uptimeMillis()
        OpenTrace.mark("presentation.init.begin")
        //layoutParams = getLayoutParams();
        init = true
        OpenTrace.mark("presentation.init.afterFlag")
        initSlidebar()
        OpenTrace.mark("presentation.init.afterSlidebar")
        pgPrintMode.init()
        OpenTrace.mark("presentation.init.end", start)
    }

    /**
     *
     */
    fun initSlidebar() {
        /*if (layoutParams == null)
        {
            return;
        }
        // 非指定高度才需要重高度
        if (layoutParams.height == LayoutParams.MATCH_PARENT
            || layoutParams.height == LayoutParams.FILL_PARENT)
        {
            mHeight = ((View)getParent()).getHeight() - getTop();
            int bHeight = control.getMainFrame().getBottomBarHeight();
            if (bHeight > 0 || isSlideShow())
            {
                mHeight -= bHeight;
                setLayoutParams(new LinearLayout.LayoutParams(layoutParams.width, mHeight));
            }
        }*/
    }

    /**
     *
     */
    override fun setBackgroundColor(color: Int) {
        super.setBackgroundColor(color)
        if (this::pgPrintMode.isInitialized) {
            pgPrintMode.setBackgroundColor(color)
        }
    }

    /**
     *
     *
     */
    override fun setBackgroundResource(resid: Int) {
        super.setBackgroundResource(resid)
        if (this::pgPrintMode.isInitialized) {
            pgPrintMode.setBackgroundResource(resid)
        }
    }

    /**
     *
     *
     */
    @Deprecated("Deprecated in Java")
    override fun setBackgroundDrawable(d: Drawable?) {
        @Suppress("DEPRECATION")
        super.setBackgroundDrawable(d)
        if (this::pgPrintMode.isInitialized) {
            @Suppress("DEPRECATION")
            pgPrintMode.setBackgroundDrawable(d)
        }
    }

    fun setViewVisible(visible: Boolean) {
        pgPrintMode.setVisible(visible)
    }

    fun showLoadingSlide(): Boolean {
        if (currentIndex < getRealSlideCount()) {
            // TODO(coroutine): posts a UI-thread task that makes the print-mode view visible; Dispatchers.Main
            post(object : Runnable {
                /**
                 *
                 */
                override fun run() {
                    setViewVisible(true)
                }
            })

            pgPrintMode.showSlideForIndex(currentIndex)
            return true
        }
        return false
    }

    /**
     * 显示指定的slide
     *
     */
    fun showSlide(index: Int, find: Boolean) {
        if (!find) {
            control!!.getMainFrame().setFindBackForwardState(false)
        }
        if (index >= pgModel!!.getSlideCount()) {
            return
        }
        if (!slideshow) {
            currentIndex = index
            if (index < getRealSlideCount()) {
                pgPrintMode.showSlideForIndex(index)
            } else {
                setViewVisible(false)
            }
        } else {
            val old = currentIndex
            currentIndex = index
            currentSlide = pgModel!!.getSlide(index)
            if (slideView == null) {
                slideView = SlideShowView(this, currentSlide)
            }
            if (slideView != null) {
                slideView!!.changeSlide(currentSlide)
            }
            if (old != currentIndex) {
                control!!.actionEvent(EventConstant.SYS_UPDATE_TOOLSBAR_BUTTON_STATUS, null)
                // 显示的slide，需要dispose不显示slide的
                //disposeOldSlideView(pgModel.getSlide(old));
                SlideDrawKit.instance().disposeOldSlideView(pgModel, pgModel!!.getSlide(old))
            }
            postInvalidate()
            // to picture
            // TODO(coroutine): posts a UI-thread task that notifies APP_GENERATED_PICTURE_ID; Dispatchers.Main
            post(object : Runnable {
                override fun run() {
                    if (control != null) {
                        control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                    }
                }
            })
        }
    }

    /**
     *
     */
    override fun onDraw(canvas: Canvas) {
        if (!init || !slideshow) {
            return
        }
        try {
            slideView!!.drawSlide(canvas, fitZoom, callouts)
            // auto test code
            if (control!!.isAutoTest()) {
                if (currentIndex < getRealSlideCount() - 1) {
                    try {
                        Thread.sleep(500)
                    } catch (e: Exception) {
                    }
                    showSlide(currentIndex + 1, false)
                } else {
                    control!!.actionEvent(EventConstant.SYS_AUTO_TEST_FINISH_ID, true)
                }
            }
            if (preShowSlideIndex != currentIndex) {
                control!!.getMainFrame().changePage(currentIndex + 1, getRealSlideCount())
                preShowSlideIndex = currentIndex
            }
        } catch (ex: NullPointerException) {
            control!!.getSysKit().getErrorKit().writerLog(ex)
        }
    }

    /**
     *
     */
    fun createPicture() {
        val otp = control!!.getOfficeToPicture()
        if (otp != null && otp.modeType == IOfficeToPicture.VIEW_CHANGE_END) {
            try {
                toPicture(otp)
            } catch (e: Exception) {
            }
        }
    }

    /**
     *
     */
    private fun toPicture(otp: IOfficeToPicture) {
        if (!init || !slideshow) {
            val item = pgPrintMode.getListView()!!.getCurrentPageView() as PGPageListItem
            item.addRepaintImageView(null)
        } else if (slideView!!.animationStoped()) {
            val b = PictureKit.instance().isDrawPictrue
            PictureKit.instance().isDrawPictrue = true
            //
            val paintZoom = if (slideshow) fitZoom else zoom
            val d = getPageSize()!!
            val originBitmapW = Math.min((d.width * paintZoom).toInt(), getWidth())
            val originbitmapH = Math.min((d.height * paintZoom).toInt(), getHeight())

            val bitmap = otp.getBitmap(originBitmapW, originbitmapH)
            if (bitmap == null) {
                return
            }
            val picCanvas = Canvas(bitmap)
            picCanvas.drawColor(Color.BLACK)

            slideView!!.drawSlideForToPicture(picCanvas, paintZoom, originBitmapW, originbitmapH)
            control!!.getSysKit().getCalloutManager().drawPath(picCanvas, getCurrentIndex(), paintZoom)
            otp.callBack(bitmap)
            PictureKit.instance().isDrawPictrue = b
        }
    }

    /**
     *
     * @param destBitmap
     * @return
     */
    fun getSnapshot(destBitmap: Bitmap?): Bitmap? {
        if (destBitmap == null) {
            return null
        }

        if (!init || !slideshow) {
            return pgPrintMode.getSnapshot(destBitmap)
        } else {
            //
            var paintZoom = if (slideshow) fitZoom else zoom
            val d = getPageSize()!!
            val originBitmapW = Math.min((d.width * paintZoom).toInt(), getWidth())
            val originbitmapH = Math.min((d.height * paintZoom).toInt(), getHeight())

            paintZoom *= Math.min(destBitmap.getWidth() / originBitmapW.toFloat(), destBitmap.getHeight() / originbitmapH.toFloat())
            val picCanvas = Canvas(destBitmap)
            picCanvas.drawColor(Color.BLACK)

            slideView!!.drawSlideForToPicture(picCanvas, paintZoom, destBitmap.getWidth(), destBitmap.getHeight())
        }

        return destBitmap
    }

    /**
     * page to image for page number (base 1)
     *
     * @return bitmap raw data
     */
    fun slideToImage(slideNumber: Int): Bitmap? {
        if (slideNumber <= 0 || slideNumber > getRealSlideCount()) {
            return null
        }
        return SlideDrawKit.instance().slideToImage(pgModel!!, editor, pgModel!!.getSlide(slideNumber - 1))
    }

    /**
     * Draws a slide (base 1) as vector content onto [canvas] (e.g. a PdfDocument page)
     * at zoom 1, so text stays sharp instead of being rasterized.
     */
    fun drawSlide(slideNumber: Int, canvas: Canvas): Boolean {
        if (slideNumber <= 0 || slideNumber > getRealSlideCount()) {
            return false
        }
        val slide = pgModel!!.getSlide(slideNumber - 1) ?: return false
        val forced = PictureKit.instance().setForceDrawOnCurrentThread(true)
        val saved = canvas.save()
        try {
            SlideDrawKit.instance().drawSlide(canvas, pgModel!!, editor, slide, 1f)
        } finally {
            canvas.restoreToCount(saved)
            PictureKit.instance().setForceDrawOnCurrentThread(forced)
        }
        return true
    }

    /**
     * page to image for page number (base 1)
     *
     * @return bitmap raw data
     */
    fun slideAreaToImage(slideNumber: Int, srcLeft: Int, srcTop: Int, srcWidth: Int, srcHeight: Int, desWidth: Int, desHeight: Int): Bitmap? {
        if (slideNumber <= 0 || slideNumber > getRealSlideCount()
            || !SysKit.isValidateRect(getPageSize()!!.getWidth().toInt(), getPageSize()!!.getHeight().toInt(), srcLeft, srcTop, srcWidth, srcHeight)
        ) {
            return null
        }
        return SlideDrawKit.instance().slideAreaToImage(
            pgModel!!, editor, pgModel!!.getSlide(slideNumber - 1),
            srcLeft, srcTop, srcWidth, srcHeight, desWidth, desHeight
        )
    }

    /**
     * page to image for page number (base 1)
     *
     * @return bitmap raw data
     */
    fun getThumbnail(slideNumber: Int, zoom: Float): Bitmap? {
        if (slideNumber <= 0 || slideNumber > getRealSlideCount()) {
            return null
        }
        return SlideDrawKit.instance().getThumbnail(pgModel!!, editor, pgModel!!.getSlide(slideNumber - 1), zoom)
    }

    /**
     * get slide node for slide number (base 1)
     *
     * @param slideNumber slide number
     *
     * @return slide note
     */
    fun getSldieNote(slideNumber: Int): String? {
        if (slideNumber <= 0 || slideNumber > getSlideCount()) {
            return null
        }
        val note = pgModel!!.getSlide(slideNumber - 1)!!.getNotes()
        return if (note == null) "" else note.getNotes()
    }

    /**
     *
     *
     */
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        isConfigurationChanged = true
    }

    /**
     * This is called during layout when the size of this view has changed. If
     * you were just added to the view hierarchy, you're called with the old
     * values of 0.
     *
     * @param w Current width of this view.
     * @param h Current height of this view.
     * @param oldw Old width of this view.
     * @param oldh Old height of this view.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        processPageSize(w, h)
    }

    private fun processPageSize(width: Int, height: Int) {
        mWidth = width
        mHeight = height
        /*if (layoutParams == null)
        {
            return;
        }*/
        if (isConfigurationChanged || slideshow) {
            if (isConfigurationChanged) {
                isConfigurationChanged = false
            }

            /*if (layoutParams.height == LayoutParams.MATCH_PARENT
                || layoutParams.height == LayoutParams.FILL_PARENT)
            {
                ViewGroup parent = (ViewGroup)getParent();
                if (parent != null && parent.getChildCount() > 1)
                {
                    mWidth = parent.getWidth();
                    mHeight = parent.getHeight();
                    mHeight -= getTop();
                    mHeight -= control.getMainFrame().getBottomBarHeight();
                    setLayoutParams(new LinearLayout.LayoutParams(layoutParams.width, mHeight));
                    layout(getLeft(), getTop(), getLeft() + mWidth, getTop() + mHeight);

                    final View view = parent.getChildAt(parent.getChildCount() - 1);
                    if (view != null && view != this)
                    {
                        view.setVisibility(View.INVISIBLE);
                        post(new Runnable()
                        {
                            @ Override
                            public void run()
                            {
                                view.setVisibility(View.VISIBLE);
                                requestLayout();
                            }
                        });
                    }
                }
            }*/

            fitZoom = getFitZoom()

            if (slideshow) {
                // to picture
                // TODO(coroutine): posts a UI-thread task that notifies APP_GENERATED_PICTURE_ID; Dispatchers.Main
                post(object : Runnable {
                    override fun run() {
                        control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                    }
                })
            }
        }
    }

    /**
     *
     */
    fun getFitZoom(): Float {
        if (slideshow) {
            val pageSize = getPageSize()!!
            return Math.min(
                mWidth.toFloat() / pageSize.width,
                mHeight.toFloat() / pageSize.height
            )
        }
        return pgPrintMode.getFitZoom()
    }

    /**
     * 返回当前显示slide的index
     *
     */
    fun getCurrentIndex(): Int {
        return if (slideshow) slideIndex_SlideShow else pgPrintMode.getCurrentPageNumber() - 1
    }

    /**
     *
     */
    fun getSlideCount(): Int {
        return pgModel!!.getSlideCount()
    }

    /**
     *
     */
    fun getRealSlideCount(): Int {
        return pgModel!!.getRealSlideCount()
    }

    /**
     *
     */
    fun getSlide(index: Int): PGSlide? {
        return pgModel!!.getSlide(index)
    }

    /**
     *
     */
    fun getControl(): IControl? {
        return control
    }

    /**
     * @return Returns the mWidth.
     */
    fun getmWidth(): Int {
        return mWidth
    }

    /**
     * @param mWidth The mWidth to set.
     */
    fun setmWidth(mWidth: Int) {
        this.mWidth = mWidth
    }

    /**
     * @return Returns the mHeight.
     */
    fun getmHeight(): Int {
        return mHeight
    }

    /**
     * @param mHeight The mHeight to set.
     */
    fun setmHeight(mHeight: Int) {
        this.mHeight = mHeight
    }

    /**
     *
     * @param w
     * @param h
     */
    fun setSize(w: Int, h: Int) {
        this.mWidth = w
        this.mHeight = h
    }

    /**
     * @return Returns the zoom.
     */
    fun getZoom(): Float {
        return if (slideshow) fitZoom else pgPrintMode.getZoom()
    }

    /**
     * @param zoom The zoom to set.
     */
    fun setZoom(zoom: Float, pointX: Int, pointY: Int) {
        if (slideshow) {
            return
        }
        pgPrintMode.setZoom(zoom, pointX, pointY)
    }

    /**
     * set fit size for PPT，Word view mode, PDf
     *
     * @param  value  fit size mode
     *          = 0, fit size of get minimum value of pageWidth / viewWidth and pageHeight / viewHeight;
     *          = 1, fit size of pageWidth
     *          = 2, fit size of PageHeight
     */
    fun setFitSize(value: Int) {
        if (slideshow) {
            return
        }
        pgPrintMode.setFitSize(value)
    }

    /**
     * get fit size statue
     *
     * @return fit size statue
     *          = 0, left/right and top/bottom don't alignment
     *          = 1, top/bottom alignment
     *          = 2, left/right alignment
     *          = 3, left/right and top/bottom alignment
     */
    fun getFitSizeState(): Int {
        if (slideshow) {
            return 0
        }
        return pgPrintMode.getFitSizeState()
    }

    /**
     * @return Returns the pageSize.
     */
    fun getPageSize(): Dimension? {
        return pgModel!!.getPageSize()
    }

    /**
     * @return Returns the doc.
     */
    fun getRenderersDoc(): IDocument? {
        return pgModel!!.getRenderersDoc()
    }

    /**
     *
     */
    fun getCurrentSlide(): PGSlide? {
        if (slideshow) {
            return pgModel!!.getSlide(slideIndex_SlideShow)
        } else {
            return pgPrintMode.getCurrentPGSlide()
        }
    }

    /**
     *
     * @param value
     * @return true: finded  false: not finded
     */
    override fun find(value: String?): Boolean {
        if (!slideshow) {
            return pgFind!!.find(value)
        }
        return false
    }

    /**
     * need call function find first and finded
     * @return
     */
    override fun findBackward(): Boolean {
        if (!slideshow) {
            return pgFind!!.findBackward()
        }
        return false
    }

    /**
     * need call function find first and finded
     * @return
     */
    override fun findForward(): Boolean {
        if (!slideshow) {
            return pgFind!!.findForward()
        }
        return false
    }

    /**
     *
     */
    override fun resetSearchResult() {
    }

    /**
     *
     */
    override fun getPageIndex(): Int {
        return -1
    }

    /**
     * get selected text
     * @return
     */
    fun getSelectedText(): String? {
        return editor.getHighlight()!!.getSelectText()
    }

    /**
     *
     */
    fun getSlideMaster(index: Int): PGSlide? {
        return pgModel!!.getSlideMaster(index)
    }

    /**
     * @return Returns the pgEditor.
     */
    fun getEditor(): PGEditor {
        return editor
    }

    /**
     * set animation duration(ms), should be called before begin slideshow
     * @param duration
     */
    fun setAnimationDuration(duration: Int) {
        if (slideView == null) {
            slideView = SlideShowView(this, currentSlide)
        }

        if (slideView != null) {
            slideView!!.setAnimationDuration(duration)
        }
    }

    /**
     * begin slideshow
     * @param slideIndex(base 1)
     */
    fun beginSlideShow(slideIndex: Int) {
        synchronized(this) {
            if (slideIndex <= 0 || slideIndex > pgModel!!.getSlideCount()) {
                return
            }

            if (eventManage == null) {
                eventManage = PGEventManage(this, control!!)
            }

            var isChangedSlide = false
            if (getCurrentIndex() + 1 != slideIndex) {
                isChangedSlide = true
            }

            setOnTouchListener(eventManage)

            control!!.getSysKit().getCalloutManager().setDrawingMode(MainConstant.DRAWMODE_NORMAL)
            pgPrintMode.setVisibility(View.GONE)

            slideshow = true
            processPageSize(getWidth(), getHeight())

            slideIndex_SlideShow = slideIndex - 1
            currentSlide = pgModel!!.getSlide(slideIndex_SlideShow)
            if (slideView == null) {
                slideView = SlideShowView(this, currentSlide)
            }

            slideView!!.initSlideShow(currentSlide, true)

            setBackgroundColor(Color.BLACK)

            if (callouts == null) {
                if (!control!!.getSysKit().getCalloutManager().isPathEmpty()) {
                    initCalloutView()
                }
            } else {
                callouts!!.setIndex(slideIndex_SlideShow)
            }

            postInvalidate()

            if (isChangedSlide && getControl()!!.getMainFrame() != null) {
                getControl()!!.getMainFrame().changePage(slideIndex_SlideShow + 1, getRealSlideCount())
            }

            // to picture
            // TODO(coroutine): posts a UI-thread task that re-inits the slide bar and notifies APP_GENERATED_PICTURE_ID; Dispatchers.Main
            post(object : Runnable {
                override fun run() {
                    initSlidebar()
                    control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                }
            })
        }
    }

    /**
     *
     * @return
     */
    fun hasNextSlide_Slideshow(): Boolean {
        synchronized(this) {
            if (slideshow) {
                return slideIndex_SlideShow < pgModel!!.getSlideCount() - 1
            }

            return false
        }
    }

    /**
     *
     * @return
     */
    fun hasPreviousSlide_Slideshow(): Boolean {
        synchronized(this) {
            if (slideshow) {
                return slideIndex_SlideShow >= 1
            }

            return false
        }
    }

    /**
     * has next action or not
     * @return
     */
    fun hasNextAction_Slideshow(): Boolean {
        synchronized(this) {
            if (slideshow) {
                if (!slideView!!.gotoNextSlide()                                   //has next action
                    || slideIndex_SlideShow < pgModel!!.getSlideCount() - 1      //has next slide
                ) {
                    return true
                }
            }

            return false
        }
    }

    /**
     * has previous action or not
     * @return
     */
    fun hasPreviousAction_Slideshow(): Boolean {
        synchronized(this) {
            if (slideshow &&
                (slideIndex_SlideShow >= 1            //has previous slide
                    || !slideView!!.previousSlide())  //has previous action
            ) {
                return true
            }

            return false
        }
    }

    /**
     * show or hide shape one by one
     */
    fun slideShow(type: Byte) {
        synchronized(this) {
            if (!slideshow || !slideView!!.animationStoped()
                || control!!.getSysKit().getCalloutManager().getDrawingMode() != MainConstant.DRAWMODE_NORMAL
            ) {
                return
            }

            if (type == ISlideShow.SlideShow_PreviousSlide && hasPreviousSlide_Slideshow()) {
                slideIndex_SlideShow = slideIndex_SlideShow - 1
                if (slideIndex_SlideShow >= 0) {
                    slideView!!.initSlideShow(pgModel!!.getSlide(slideIndex_SlideShow), true)
                    if (getControl()!!.getMainFrame() != null) {
                        getControl()!!.getMainFrame().changePage(slideIndex_SlideShow + 1, getRealSlideCount())
                    }
                }
//                else
//                {
//                    slideIndex_SlideShow = 0;
//                    showTips("It's first slide now.");
//                    return;
//                }
            } else {
                if (slideView!!.isExitSlideShow()) {
                    control!!.getMainFrame().fullScreen(false)
                    endSlideShow()
                    return
                }
                when (type) {
                    ISlideShow.SlideShow_PreviousStep -> if (hasPreviousAction_Slideshow()) {
                        if (slideView!!.previousSlide()) {
                            val slide = pgModel!!.getSlide(--slideIndex_SlideShow)
                            if (slide != null) {
                                slideView!!.initSlideShow(slide, true)
                                slideView!!.gotoLastAction()
                            }
//                                else
//                                {
//                                    slideIndex_SlideShow = 0;
//                                    showTips("It's first action of first slide now.");
//                                    return;
//                                }

                            if (getControl()!!.getMainFrame() != null) {
                                getControl()!!.getMainFrame().changePage(slideIndex_SlideShow + 1, getRealSlideCount())
                            }
                        } else {
                            slideView!!.previousActionSlideShow()
                        }
                    }

                    ISlideShow.SlideShow_NextStep -> if (hasNextAction_Slideshow()) {
                        if (slideView!!.gotoNextSlide()) {
                            slideView!!.initSlideShow(pgModel!!.getSlide(++slideIndex_SlideShow), true)
                            if (getControl()!!.getMainFrame() != null) {
                                getControl()!!.getMainFrame().changePage(slideIndex_SlideShow + 1, getRealSlideCount())
                            }
                        } else {
                            slideView!!.nextActionSlideShow()
                        }
                    }

                    ISlideShow.SlideShow_NextSlide -> if (hasNextSlide_Slideshow()) {
                        slideView!!.initSlideShow(pgModel!!.getSlide(++slideIndex_SlideShow), true)
                        if (getControl()!!.getMainFrame() != null) {
                            getControl()!!.getMainFrame().changePage(slideIndex_SlideShow + 1, getRealSlideCount())
                        }
                    }
                }
            }
            if (callouts != null) {
                callouts!!.setIndex(slideIndex_SlideShow)
            }
            postInvalidate()

            // to picture
            // TODO(coroutine): posts a UI-thread task that notifies APP_GENERATED_PICTURE_ID; Dispatchers.Main
            post(object : Runnable {
                override fun run() {
                    if (control != null) {
                        control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                    }
                }
            })
        }
    }

    /**
     * slideshow end
     */
    fun endSlideShow() {
        synchronized(this) {
            if (slideshow) {
                control!!.getSysKit().getCalloutManager().setDrawingMode(MainConstant.DRAWMODE_NORMAL)
                setOnTouchListener(null)
                pgPrintMode.setVisibility(View.VISIBLE)
                val bg = control!!.getMainFrame().getViewBackground()
                if (bg != null) {
                    if (bg is Int) {
                        setBackgroundColor(bg)
                    } else if (bg is Drawable) {
                        @Suppress("DEPRECATION")
                        setBackgroundDrawable(bg)
                    }
                }

                currentIndex = slideIndex_SlideShow

                slideshow = false
                slideView!!.endSlideShow()

                showSlide(currentIndex, false)
                if (callouts != null) {
                    callouts!!.setVisibility(View.INVISIBLE)
                }

                // to picture
                // TODO(coroutine): posts a UI-thread task that exits the slideshow UI and re-inits the slide bar; Dispatchers.Main
                post(object : Runnable {
                    override fun run() {
                        val iSlideshow = control!!.getSlideShow()
                        if (iSlideshow != null) {
                            iSlideshow.exit()
                        }
                        initSlidebar()
                    }
                })
            }
        }
    }

    /**
     * it's slideshowing or not.
     * @return
     */
    fun isSlideShow(): Boolean {
        return slideshow
    }

    /**
     *
     */
    fun getFind(): PGFind? {
        return this.pgFind
    }

    /**
     *
     * @return
     */
    fun getPrintMode(): PGPrintMode {
        return this.pgPrintMode
    }

    fun getSlideDrawingRect(): Rect? {
        if (slideshow) {
            if (slideSize == null) {
                slideSize = Rect(slideView!!.getDrawingRect())
            } else {
                slideSize!!.set(slideView!!.getDrawingRect())
            }

            val w = slideSize!!.width()
            slideSize!!.set((mWidth - w) / 2, 0, (mWidth + w) / 2, mHeight)

            return slideSize
        }

        return null
    }

    fun getPGModel(): PGModel? {
        return pgModel
    }

    /**
     * animation steps for current slide
     * @param slideIndex(based 1)
     * @return
     */
    fun getSlideAnimationSteps(slideIndex: Int): Int {
        synchronized(this) {
            val shapeAnimLst = pgModel!!.getSlide(slideIndex - 1)!!.getSlideShowAnimation()
            if (shapeAnimLst != null) {
                return shapeAnimLst.size + 1
            } else {
                return 1
            }
        }
    }

    /** Slide [index] (0-based) for the app's slideshow, [width] px wide, see [SlideDrawKit.layers]. */
    fun slideLayers(index: Int, width: Int, animated: Set<Int>): SlideDrawKit.Layers? {
        val model = pgModel ?: return null
        val slide = model.getSlide(index) ?: return null
        return SlideDrawKit.instance().layers(model, getEditor(), slide, width, animated)
    }

    /**
     * slideshow to image
     * @param slideIndex slide index(base 1)
     * @param step animation index(base 1)
     * @return
     */
    fun getSlideshowToImage(slideIndex: Int, step: Int): Bitmap? {
        synchronized(this) {
            if (slideView == null) {
                slideView = SlideShowView(this, pgModel!!.getSlide(slideIndex - 1))
            }

            return slideView!!.getSlideshowToImage(pgModel!!.getSlide(slideIndex - 1), step)
        }
    }

    /**
     *
     */
    override fun dispose() {
        control = null
        currentSlide = null
        if (slideView != null) {
            slideView!!.dispose()
            slideView = null
        }
        if (eventManage != null) {
            eventManage!!.dispose()
            eventManage = null
        }
        pgModel!!.dispose()
        pgModel = null
        //layoutParams = null;
        if (pgFind != null) {
            pgFind!!.dispose()
            pgFind = null
        }
    }
}
