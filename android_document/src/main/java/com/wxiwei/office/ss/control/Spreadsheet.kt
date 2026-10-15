/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:          Spreadsheet.java
 *
 * 编译器:            android2.2
 * 时间:              下午3:48:56
 */

@file:Suppress("SENSELESS_COMPARISON")

package com.wxiwei.office.ss.control

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Message
import android.view.ViewGroup
import android.widget.LinearLayout
import com.wxiwei.office.common.IOfficeToPicture
import com.wxiwei.office.common.hyperlink.Hyperlink
import com.wxiwei.office.common.picture.PictureKit
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.constant.SSConstant
import com.wxiwei.office.simpletext.control.IWord
import com.wxiwei.office.ss.model.baseModel.Sheet
import com.wxiwei.office.ss.model.baseModel.Workbook
import com.wxiwei.office.ss.model.interfacePart.IReaderListener
import com.wxiwei.office.ss.util.ModelUtil
import com.wxiwei.office.ss.view.SheetView
import com.wxiwei.office.system.IControl
import com.wxiwei.office.system.IFind
import com.wxiwei.office.system.OpenTrace
import com.wxiwei.office.system.beans.AEventManage
import com.wxiwei.office.system.beans.CalloutView.CalloutView
import com.wxiwei.office.system.beans.CalloutView.IExportListener
import java.io.File

/**
 * 文件注释
 *
 * Read版本:        Read V1.0
 *
 * 作者:            ljj8494
 *
 * 日期:            2011-11-3
 *
 * 负责人:          ljj8494
 *
 * 负责小组:
 */
class Spreadsheet(context: Context, filepath: String?, book: Workbook?, control: IControl?, parent: ExcelView?) :
    LinearLayout(context), IFind, IReaderListener, IExportListener {

    private var parent: ExcelView? = null

    //
    private var isConfigurationChanged = false

    //
    private var isDefaultSheetBar = true

    //abort current sheet drawing or not
    private var abortDrawing = false

    //
    private var initFinish = false

    //
    private var preShowSheetIndex = -1

    //
    private var currentSheetIndex = 0
    private var currentSheetName: String? = null

    //
    private var sheetbarHeight = 0

    //file name
    private var fileName: String? = null

    //
    private var control: IControl? = null

    // excel model 后期需修改
    private var workbook: Workbook? = null

    // 当前Sheet
    private var sheetview: SheetView? = null

    // 事件管理器
    private var eventManage: SSEventManage? = null

    //
    private var editor: SSEditor? = null

    //
    private var callouts: CalloutView? = null
    //private ViewGroup.LayoutParams layoutParams;
    //
    //private SheetBar bar;

    /**
     *
     * @param context
     */
    init {
        this.parent = parent

        fileName = filepath
        setBackgroundColor(Color.WHITE)
        this.workbook = book
        this.control = control
        eventManage = SSEventManage(this, control!!)
        this.editor = SSEditor(this)
        setOnTouchListener(eventManage)
        isLongClickable = true
    }

    fun getCalloutView(): CalloutView? {
        return callouts
    }

    fun initCalloutView() {
        if (callouts == null) {
            callouts = CalloutView(this.context, control!!, this)
            callouts!!.setIndex(currentSheetIndex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            params.leftMargin = SSConstant.DEFAULT_ROW_HEADER_WIDTH
            params.topMargin = SSConstant.DEFAULT_COLUMN_HEADER_HEIGHT
            addView(callouts, params)
        }
    }

    override fun exportImage() {
        control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
    }

    /**
     * 初始化显示的sheet，默认第一第sheet
     */
    fun init() {
        val workbook = this.workbook!!
        val control = this.control!!
        workbook.debugDump("ui-init")
        //layoutParams = getLayoutParams();

        //file name
        val index = fileName!!.lastIndexOf(File.separator)
        if (index > 0) {
            fileName = fileName!!.substring(index + 1)
        }

        //set title name
        control.actionEvent(
            EventConstant.SS_SHEET_CHANGE,
            fileName + " : " + workbook.getSheet(0)!!.getSheetName()
        )

        if (sheetview == null) {
            sheetview = SheetView(this, workbook.getSheet(0))
        }

        //initSheetbar();

        initFinish = true
        val state = workbook.getSheet(0)!!.getState()
        if (state != Sheet.State_Accomplished) {
            workbook.getSheet(0)!!.setReaderListener(this)

            control.actionEvent(EventConstant.SYS_SET_PROGRESS_BAR_ID, true)
        }

        //if (state == Sheet.State_Accomplished)
        run {
            // to picture
            // TODO(coroutine): posts a UI-thread callback that fires APP_GENERATED_PICTURE_ID after init; suggested Dispatchers.Main
            post(object : Runnable {
                override fun run() {
                    this@Spreadsheet.control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                }
            })
        }

        // init() can run before this view receives its final measured size.
        // Ensure the first real layout schedules a frame without requiring a touch.
        postInvalidate()
    }

    /**
     *
     */
    private fun initSheetbar() {
        /*if (!isDefaultSheetBar)
        {
            this.sheetbarHeight = control.getMainFrame().getBottomBarHeight();
            int mHeight = ((View)getParent()).getHeight() - getTop();
            // 非指定高度才需要重高度
            if (layoutParams.height == LayoutParams.MATCH_PARENT
                || layoutParams.height == LayoutParams.FILL_PARENT)
            {
                mHeight -= sheetbarHeight;
                setLayoutParams(new LinearLayout.LayoutParams(layoutParams.width, mHeight));
            }
            return;
        }
        int maxWidth = layoutParams.width == LayoutParams.MATCH_PARENT
            || layoutParams.width == LayoutParams.FILL_PARENT ? getResources().getDisplayMetrics().widthPixels
                : layoutParams.width;
        bar = new SheetBar(getContext(), control, maxWidth);
        sheetbarHeight = bar.getSheetbarHeight();
        int mHeight = ((View)getParent()).getHeight() - getTop();
        // 非指定高度才需要重高度
        if (layoutParams.height == LayoutParams.MATCH_PARENT
            || layoutParams.height == LayoutParams.FILL_PARENT)
        {
            mHeight -= sheetbarHeight;
            setLayoutParams(new LinearLayout.LayoutParams(layoutParams.width, mHeight));
        }

        ((ViewGroup)getParent()).addView(bar, new LayoutParams(layoutParams.width == LayoutParams.MATCH_PARENT
            || layoutParams.width == LayoutParams.FILL_PARENT ? LayoutParams.WRAP_CONTENT : layoutParams.width, LayoutParams.WRAP_CONTENT));*/
    }

    /**
     *
     */
    fun removeSheetBar() {
        isDefaultSheetBar = false
        //((ViewGroup)getParent()).removeView(bar);
    }

    /**
     * 得到sheet的个数
     */
    fun getSheetCount(): Int {
        return workbook!!.getSheetCount()
    }

    /**
     * 显示指定的sheet
     *
     * @param sheetName 要显示的sheet名称
     */
    fun showSheet(sheetName: String?) {
        if (currentSheetName != null && currentSheetName == sheetName) {
            return
        }

        val sheet = workbook!!.getSheet(sheetName)
        if (sheet == null) {
            return
        }
        currentSheetName = sheetName
        currentSheetIndex = workbook!!.getSheetIndex(sheet)
        //change focused button
        /*if(isDefaultSheetBar)
        {
            //bar.setFocusSheetButton(currentSheetIndex);
        }
        else
        {
            control.getMainFrame().doActionEvent(EventConstant.SS_CHANGE_SHEET, currentSheetIndex);
        }*/

        showSheet(sheet)
    }

    /**
     * 显示指定的sheet
     *
     * @param sheetIndex 要显示的sheet名称
     */
    fun showSheet(sheetIndex: Int) {
        if (currentSheetIndex == sheetIndex
            || sheetIndex >= getSheetCount()
        ) {
            return
        }

        val sheet = workbook!!.getSheet(sheetIndex)
        currentSheetIndex = sheetIndex
        currentSheetName = sheet!!.getSheetName()

        //change focused button
        /*if(isDefaultSheetBar)
        {
            //bar.setFocusSheetButton(currentSheetIndex);
        }
        else
        {
            control.getMainFrame().doActionEvent(EventConstant.SS_CHANGE_SHEET, currentSheetIndex);
        }*/
        control!!.actionEvent(EventConstant.SYS_UPDATE_TOOLSBAR_BUTTON_STATUS, null)
        if (callouts != null) {
            callouts!!.setIndex(currentSheetIndex)
        }
        showSheet(sheet)
    }

    /**
     *
     * @param sheet
     */
    private fun showSheet(sheet: Sheet) {
        try {
            eventManage!!.stopFling()
            control!!.getMainFrame().setFindBackForwardState(false)

            control!!.actionEvent(
                EventConstant.SS_SHEET_CHANGE,
                fileName + " : " + sheet.getSheetName()
            )
            sheetview!!.changeSheet(sheet)

            postInvalidate()

            if (sheet.getState() != Sheet.State_Accomplished) {
                //current sheet has not finished parsing;
                sheet.setReaderListener(this)
                control!!.actionEvent(EventConstant.SYS_SET_PROGRESS_BAR_ID, true)
                control!!.actionEvent(EventConstant.APP_ABORTREADING, null)
            } else {
                control!!.actionEvent(EventConstant.SYS_SET_PROGRESS_BAR_ID, false)
            }

            //read not accomplished sheet in slide window
            val readerHandler = workbook!!.getReaderHandler()
            if (readerHandler != null) {
                val msg = Message()
                msg.what = MainConstant.HANDLER_MESSAGE_SUCCESS
                msg.obj = currentSheetIndex
                readerHandler.handleMessage(msg)
            }
        } catch (e: Exception) {
            OpenTrace.d(
                "excel.render.exception type=${e.javaClass.name} message=${e.message} " +
                    "sheet=${sheetview?.getCurrentSheet()?.getSheetName()}"
            )
            control!!.getSysKit().getErrorKit().writerLog(e)
        }
    }

    /**
     *
     */
    // pinch-zoom preview: the frame at pinch start, with the zoom, scroll and header sizes it was
    // drawn at; its cells are drawn where the sheet view now puts them until the gesture ends
    private var pinchFrame: Bitmap? = null
    private var pinchZoom = 1f
    private var pinchScrollX = 0f
    private var pinchScrollY = 0f
    private var pinchHeaderWidth = 0
    private var pinchHeaderHeight = 0

    /** Captures the current frame for a pinch; false when it cannot. */
    fun beginPinchPreview(): Boolean {
        endPinchPreview()
        val view = sheetview
        if (width <= 0 || height <= 0 || view == null) return false
        return try {
            val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.drawColor(Color.WHITE)
            view.drawSheet(canvas, true)
            pinchFrame = frame
            pinchZoom = view.getZoom()
            pinchScrollX = view.getScrollX()
            pinchScrollY = view.getScrollY()
            pinchHeaderWidth = view.getRowHeaderWidth()
            pinchHeaderHeight = view.getColumnHeaderHeight()
            true
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    fun endPinchPreview() {
        pinchFrame?.recycle()
        pinchFrame = null
    }

    /** The cells of the pinch frame moved and scaled to the zoom and scroll of now, under the headers of now. */
    private fun drawPinchPreview(canvas: Canvas, frame: Bitmap, view: SheetView) {
        val zoom = view.getZoom()
        val headerWidth = view.getRowHeaderWidth().toFloat()
        val headerHeight = view.getColumnHeaderHeight().toFloat()
        val scale = zoom / pinchZoom
        val left = headerWidth + (pinchScrollX - view.getScrollX()) * zoom
        val top = headerHeight + (pinchScrollY - view.getScrollY()) * zoom
        // white where the frame has no cells to show (zoomed out, or dragged past its edge)
        if (left > headerWidth || top > headerHeight || left + (frame.width - pinchHeaderWidth) * scale < width ||
            top + (frame.height - pinchHeaderHeight) * scale < height) canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.clipRect(headerWidth, headerHeight, width.toFloat(), height.toFloat())
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.translate(-pinchHeaderWidth.toFloat(), -pinchHeaderHeight.toFloat())
        // the headers of the frame are out of date: only its cells
        canvas.clipRect(pinchHeaderWidth.toFloat(), pinchHeaderHeight.toFloat(), frame.width.toFloat(), frame.height.toFloat())
        canvas.drawBitmap(frame, 0f, 0f, null)
        canvas.restore()
        view.drawHeaders(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        if (!initFinish) {
            return
        }
        pinchFrame?.let { frame ->
            // cheap while the fingers move: no cell layout or text drawing per frame
            drawPinchPreview(canvas, frame, sheetview!!)
            return
        }
        try {
            sheetview!!.drawSheet(canvas, true)

            // auto test code
            if (control!!.isAutoTest()/* && sheetbar != null*/) {
                if (currentSheetIndex < workbook!!.getSheetCount() - 1) {
                    try {
                        // TODO(coroutine): busy-waits on the UI thread until the sheet reader finishes (auto test only); suggested suspend + Dispatchers.Default polling
                        while (sheetview!!.getCurrentSheet()!!.getState() != Sheet.State_Accomplished) {
                            Thread.sleep(50)
                        }
                    } catch (e: Exception) {
                    }
                    showSheet(currentSheetIndex + 1)
                } else {
                    control!!.actionEvent(EventConstant.SYS_AUTO_TEST_FINISH_ID, true)
                }
            } else {
                val otp = control!!.getOfficeToPicture()
                if (otp != null && otp.modeType == IOfficeToPicture.VIEW_CHANGING) {
                    toPicture(otp)
                }
            }

            if (sheetview!!.getCurrentSheet()!!.getState() != Sheet.State_Accomplished) {
                invalidate()
            }
            if (preShowSheetIndex != currentSheetIndex) {
                control!!.getMainFrame().changePage(currentSheetIndex + 1, workbook?.getSheetCount() ?: 0)
                preShowSheetIndex = currentSheetIndex
            }
        } catch (e: Exception) {
            control!!.getSysKit().getErrorKit().writerLog(e)
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
        val sheetview = this.sheetview!!
        val b = PictureKit.instance().isDrawPictrue
        PictureKit.instance().isDrawPictrue = true
        //
        val bitmap = otp.getBitmap(width, height)
        if (bitmap == null) {
            return
        }

        val picCanvas = Canvas(bitmap)
        val oldPaintZoom = sheetview.getZoom()
        if (bitmap.width != width || bitmap.height != height) {
            val zoom = Math.min(bitmap.width.toFloat() / width, bitmap.height.toFloat() / height) * oldPaintZoom
            sheetview.setZoom(zoom, true)
        }
        picCanvas.drawColor(Color.WHITE)
        sheetview.drawSheet(picCanvas)
        control!!.getSysKit().getCalloutManager().drawPath(picCanvas, currentSheetIndex, oldPaintZoom)
        otp.callBack(bitmap)
        sheetview.setZoom(oldPaintZoom, true)
        //
        PictureKit.instance().isDrawPictrue = b
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

        val sheetview = this.sheetview!!
        synchronized(sheetview) {
            val picCanvas = Canvas(destBitmap)
            val oldPaintZoom = sheetview.getZoom()
            if (destBitmap.width != width || destBitmap.height != height) {
                val zoom = Math.min(destBitmap.width.toFloat() / width, destBitmap.height.toFloat() / height) * oldPaintZoom
                sheetview.setZoom(zoom, true)
            }
            picCanvas.drawColor(Color.WHITE)
            sheetview.drawSheet(picCanvas)
            sheetview.setZoom(oldPaintZoom, true)
            return destBitmap
        }
    }

    /**
     *
     */
    /**
     * get xls thumbnail
     * @param width         thumbnail width when zoomValue is 100
     * @param height        thumbnail height when zoomValue is 100
     * @param zoomValue     zoom value
     * @return
     */
    fun getThumbnail(width: Int, height: Int, zoomValue: Float): Bitmap? {
        val sheet = workbook!!.getSheet(0)
        if (sheet == null || sheet.getState() != Sheet.State_Accomplished) {
            return null
        }

        if (sheetview == null) {
            sheetview = SheetView(this, workbook!!.getSheet(0))
        }

        return sheetview!!.getThumbnail(sheet, width, height, zoomValue)
    }

    /**
     *
     *
     */
    public override fun onConfigurationChanged(newConfig: Configuration) {
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
        if (w > 0 && h > 0 && initFinish) {
            postInvalidate()
        }
        if (isConfigurationChanged) {
            isConfigurationChanged = false
            // to picture
            // TODO(coroutine): posts a UI-thread callback that fires APP_GENERATED_PICTURE_ID after a configuration change; suggested Dispatchers.Main
            post(object : Runnable {
                override fun run() {
                    control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                }
            })
        }
    }

    /**
     * 计算流动的位置
     *
     */
    override fun computeScroll() {
        eventManage!!.computeScroll()
    }

    /**
     *
     */
    fun getControl(): IControl {
        return control!!
    }

    /**
     * 得到sheetView视图
     */
    fun getSheetView(): SheetView? {
        return sheetview
    }

    /**
     * @return Returns the workbook.
     */
    fun getWorkbook(): Workbook? {
        return workbook
    }

    /**
     *
     * @return
     */
    fun getActiveCellContent(): String? {
        if (sheetview!!.getCurrentSheet()!!.getActiveCell() != null) {
            return ModelUtil.instance().getFormatContents(
                workbook!!, sheetview!!.getCurrentSheet()!!.getActiveCell()!!
            )
        }

        return ""
    }

    /**
     * active cell hyperlink address
     * @return
     */
    fun getActiveCellHyperlink(): Hyperlink? {
        val cell = sheetview!!.getCurrentSheet()!!.getActiveCell()
        if (cell != null && cell.getHyperLink() != null) {
            return cell.getHyperLink()
        }

        return null
    }

    /**
     *
     * @param findValue
     * @return  true: finded   false: not finded
     */
    override fun find(value: String?): Boolean {
        return sheetview!!.find(value)
    }

    override fun findBackward(): Boolean {
        return sheetview!!.findBackward()
    }

    override fun findForward(): Boolean {
        return sheetview!!.findForward()
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
     *
     */
    fun getZoom(): Float {
        if (sheetview == null) {
            sheetview = SheetView(this, workbook!!.getSheet(0))
        }
        return sheetview!!.getZoom()
    }

    /**
     *
     */
    fun setZoom(zoom: Float) {
        if (sheetview == null) {
            sheetview = SheetView(this, workbook!!.getSheet(0))
        }
        sheetview!!.setZoom(zoom) //zoom
    }

//    /**
//     *
//     * @param zoom
//     * @param pointX
//     * @param pointY
//     */
//    public void setZoom(float zoom, float pointX, float pointY)
//    {
//    	if (sheetview == null)
//        {
//            sheetview = new SheetView(this, workbook.getSheet(0));
//        }
//        sheetview.setZoom(zoom, pointX, pointY); //zoom
//    }

    /**
     *
     */
    fun getFitZoom(): Float {
        return 0.5f
    }

    /**
     *
     */
    fun getEventManage(): AEventManage? {
        return this.eventManage
    }

    /**
     * this function be callde by sheet reader thread,
     * so we need to post to main thread to update UI
     * (non-Javadoc)
     * @see IReaderListener.OnReadingFinished
     */
    override fun OnReadingFinished() {
        if (control != null && control!!.getMainFrame().getActivity() != null) {
            // TODO(coroutine): reader thread hands the "sheet finished" UI update to the main thread; suggested withContext(Dispatchers.Main)
            post(object : Runnable {
                override fun run() {
                    val sheet = workbook!!.getSheet(currentSheetIndex)

                    // The reader may finish after the first view frame.  If
                    // the user has not scrolled yet, position the view at the
                    // first populated cell now that rows/cells are available.
                    if (sheetview!!.getScrollX() == 0f && sheetview!!.getScrollY() == 0f) {
                        sheetview!!.positionAtFirstDataCell()
                    }

                    control!!.actionEvent(
                        EventConstant.SS_SHEET_CHANGE,
                        fileName + " : " + sheet!!.getSheetName()
                    )

                    control!!.actionEvent(EventConstant.SYS_SET_PROGRESS_BAR_ID, false)

                    control!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)

                    postInvalidate()
                }
            })
        }
    }

    override fun OnReadingProgress() {
        if (parent != null) postInvalidateOnAnimation()
    }

    fun getFileName(): String? {
        return fileName
    }

    /**
     * abort current sheet drawing
     */
    fun abortDrawing() {
        abortDrawing = true
    }

    /**
     *
     */
    fun startDrawing() {
        abortDrawing = false
    }

    /**
     *
     * @return
     */
    fun isAbortDrawing(): Boolean {
        return abortDrawing
    }

    /**
     *
     */
    fun getCurrentSheetNumber(): Int {
        return this.currentSheetIndex + 1
    }

    /**
     * get sheet bar height
     * @return
     */
    fun getBottomBarHeight(): Int {
        return parent!!.getBottomBarHeight()
    }

    /**
     *
     */
    fun getEditor(): IWord? {
        return this.editor
    }

    /**
     *
     */
    override fun dispose() {
        parent = null
        fileName = null
        control = null
        workbook = null

        if (sheetview != null) {
            sheetview!!.dispose()
            sheetview = null
        }

        if (eventManage != null) {
            eventManage!!.dispose()
            eventManage = null
        }
        if (editor != null) {
            editor!!.dispose()
            editor = null
        }
        /*if (bar != null)
        {
            bar.dispose();
            bar = null;
        }*/
    }

    fun isConfigurationChanged(): Boolean {
        return isConfigurationChanged
    }

    fun isDefaultSheetBar(): Boolean {
        return isDefaultSheetBar
    }

    fun isInitFinish(): Boolean {
        return initFinish
    }

    fun getPreShowSheetIndex(): Int {
        return preShowSheetIndex
    }

    fun getCurrentSheetIndex(): Int {
        return currentSheetIndex
    }

    fun getCurrentSheetName(): String? {
        return currentSheetName
    }

    fun getSheetbarHeight(): Int {
        return sheetbarHeight
    }

    fun getSheetview(): SheetView? {
        return sheetview
    }

    fun getCallouts(): CalloutView? {
        return callouts
    }
}
