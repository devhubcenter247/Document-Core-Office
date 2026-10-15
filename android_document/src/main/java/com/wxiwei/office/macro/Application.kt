/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:          Application.java
 * 编译器:            android2.2
 */
@file:Suppress("SENSELESS_COMPARISON")

package com.wxiwei.office.macro

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.Toast
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.java.awt.Rectangle
import com.wxiwei.office.system.FileKit
import com.wxiwei.office.system.MainControl
import com.wxiwei.office.system.*
import java.io.File
import com.wxiwei.office.system.beans.pagelist.IPageListViewListener
import com.wxiwei.office.system.beans.CalloutView.drawingMode
import java.util.Vector

/**
 *
 */
class Application(activity: Activity, parent: ViewGroup?) {

    //
    private var applicationType: Byte = -1

    //
    private var parent: ViewGroup? = parent

    //
    private var frame: MacroFrame? = MacroFrame(this, activity)

    //
    private var mainControl: MainControl? = MainControl(frame)

    //toast
    protected var toast: Toast? = null

    /**
     * set view background(color or drawble, not support resource id)
     */
    fun setViewBackground(bg: Any?) {
        if (frame != null && bg != null && (bg is Int || bg is Drawable)) {
            frame!!.setViewBackground(bg)
        }
    }

    /**
     * added export image listener
     *
     * @param listener OfficeToPictureListener instance
     */
    fun addOfficeToPictureListener(listener: OfficeToPictureListener?) {
        if (listener != null) {
            mainControl!!.setOffictToPicture(MacroOfficeToPicture(listener))
        }
    }

    /**
     * add dialog listener
     * @param dlgListener dialog listener
     */
    fun addDialogListener(dlgListener: DialogListener?) {
        if (dlgListener != null) {
            mainControl!!.setCustomDialog(MacroCustomDialog(dlgListener))
        }
    }

    /**
     * add slideshow listener
     * @param listener slideshow listener
     */
    fun addSlideShowListener(listener: SlideShowListener?) {
        if (listener != null) {
            mainControl!!.setSlideShow(MacroSlideShow(listener))
        }
    }

    /**
     * added open file finish listener
     *
     * @param listener OpenFileFinishListener instance
     */
    fun addOpenFileFinishListener(listener: OpenFileFinishListener?) {
        frame!!.addOpenFileFinishListener(listener)
    }

    /**
     * added touch event listener
     *
     * @param listener  TouchEventListener instance
     */
    fun addTouchEventListener(listener: TouchEventListener?) {
        frame!!.addTouchEventListener(listener)
    }

    /**
     * added update status listener
     *
     * @param listener  UpdateStatusListener instance
     */
    fun addUpdateStatusListener(listener: UpdateStatusListener?) {
        frame!!.addUpdateStatusListener(listener)
    }

    /**
     * added error listener
     *
     * @param listener ErrorListener instance
     */
    fun addErrorListener(listener: ErrorListener?) {
        frame!!.addErrorListener(listener)
    }

    /**
     * @param filePath  the open file of absolute path
     *
     * @return true   Open the file successfully
     *          false  Open the file failed
     */
    fun openFile(filePath: String): Boolean {
        val openedFile = File(filePath)
        OpenTrace.d("file click/open requested path=$filePath exists=${openedFile.exists()} isFile=${openedFile.isFile} length=${openedFile.length()}")
        // word
        val file = filePath.lowercase()
        if (file.endsWith(MainConstant.FILE_TYPE_DOC)
            || file.endsWith(MainConstant.FILE_TYPE_DOCX)
            || file.endsWith(MainConstant.FILE_TYPE_DOT)
            || file.endsWith(MainConstant.FILE_TYPE_DOTX)
            || file.endsWith(MainConstant.FILE_TYPE_DOTM)
        ) {
            applicationType = MainConstant.APPLICATION_TYPE_WP
        }
        // excel
        else if (file.endsWith(MainConstant.FILE_TYPE_XLS)
            || file.endsWith(MainConstant.FILE_TYPE_XLSX)
            || file.endsWith(MainConstant.FILE_TYPE_XLT)
            || file.endsWith(MainConstant.FILE_TYPE_XLTX)
            || file.endsWith(MainConstant.FILE_TYPE_XLTM)
            || file.endsWith(MainConstant.FILE_TYPE_XLSM)
        ) {
            applicationType = MainConstant.APPLICATION_TYPE_SS
        }
        // PowerPoint
        else if (file.endsWith(MainConstant.FILE_TYPE_PPT)
            || file.endsWith(MainConstant.FILE_TYPE_PPTX)
            || file.endsWith(MainConstant.FILE_TYPE_POT)
            || file.endsWith(MainConstant.FILE_TYPE_PPTM)
            || file.endsWith(MainConstant.FILE_TYPE_POTX)
            || file.endsWith(MainConstant.FILE_TYPE_POTM)
        ) {
            applicationType = MainConstant.APPLICATION_TYPE_PPT
        }
        else {
            // set word default view is normal view mode
            if (frame != null && frame!!.isThumbnail()) {
                setDefaultViewMode(0)
            } else {
                setDefaultViewMode(1)
            }
            applicationType = MainConstant.APPLICATION_TYPE_WP
        }
        OpenTrace.d("application type selected type=$applicationType path=$filePath")
        mainControl!!.openFile(filePath)

        return true
    }

    /**
     * reader file finish call this method
     */
    internal fun openFileFinish() {
        //
        val app = getView()
        OpenTrace.d("read succeeded; creating application view=${app?.javaClass?.name} type=$applicationType")
        @Suppress("DEPRECATION")
        parent?.addView(app, LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT))
        app?.post {
            OpenTrace.d("application view added class=${app.javaClass.name} size=${app.width}x${app.height} parent=${app.parent != null}")
        }
    }

    /**
     * get application type
     *
     * @return  = 0  word application
     *           = 1  excel application
     *           = 2  PowerPoint application
     *           = 4  TXT application
     *           = -1 error
     */
    fun getApplicationType(): Byte {
        return applicationType
    }

    /**
     * get office engine component
     */
    fun getView(): View? {
        return mainControl?.getView()
    }

    // ============ public engine method =======
    /**
     *  page down action
     */
    fun pageDown() {
        mainControl?.actionEvent(EventConstant.APP_PAGE_DOWN_ID, null)
    }

    /**
     * page up action
     */
    fun pageUp() {
        mainControl?.actionEvent(EventConstant.APP_PAGE_UP_ID, null)
    }

    /**
     * find
     *
     * @param str   find of assign content
     *
     * @return  true, found
     *           false  no found
     */
    fun find(str: String?): Boolean {
        if (mainControl == null
            || str == null
            || str.trim().isEmpty()
            || isSlideShowMode()
        ) {
            return false
        }
        val finded = mainControl!!.getFind().find(str)
        if (!finded && mainControl!!.getMainFrame().isShowFindDlg) {
            if (toast == null) {
                toast = Toast.makeText(mainControl!!.getView().context, "", Toast.LENGTH_SHORT)
            }
            toast!!.setText(mainControl!!.getMainFrame().getLocalString("DIALOG_FIND_NOT_FOUND"))
            toast!!.show()
        }

        return finded
    }

    /**
     * find of backward
     *
     * @return  true, found
     *           false  no found
     */
    fun findBackward(): Boolean {
        if (mainControl == null || isSlideShowMode()) {
            return false
        }
        val finded = mainControl!!.getFind().findBackward()
        if (!finded && mainControl!!.getMainFrame().isShowFindDlg) {
            if (toast == null) {
                toast = Toast.makeText(mainControl!!.getView().context, "", Toast.LENGTH_SHORT)
            }
            toast!!.setText(mainControl!!.getMainFrame().getLocalString("DIALOG_FIND_TO_BEGIN"))
            toast!!.show()
        }
        return finded
    }

    /**
     * find of forward
     *
     * @return  true, found
     *           false  no found
     */
    fun findForward(): Boolean {
        if (mainControl == null || isSlideShowMode()) {
            return false
        }
        val finded = mainControl!!.getFind().findForward()
        if (!finded && mainControl!!.getMainFrame().isShowFindDlg) {
            if (toast == null) {
                toast = Toast.makeText(mainControl!!.getView().context, "", Toast.LENGTH_SHORT)
            }
            toast!!.setText(mainControl!!.getMainFrame().getLocalString("DIALOG_FIND_TO_END"))
            toast!!.show()
        }
        return finded
    }

    /**
     * set is support zoom in / zoom out?
     *
     * @param value    true  support zoom in / zoom out
     *                  false don't support zoom in / zoom out
     */
    fun setTouchZoom(value: Boolean) {
        frame?.setTouchZoom(value)
    }

    /**
     * set is support draw page number?
     *
     * @param value    true  draw page number
     *                  false don't draw page number
     */
    fun setDrawPageNumber(value: Boolean) {
        frame?.setDrawPageNumber(value)
    }

    /**
     * show or hide zooming message
     */
    fun setShowZoomingMsg(showZoomingMsg: Boolean) {
        frame?.setShowZoomingMsg(showZoomingMsg)
    }

    /**
     * pop up error dialog or not
     */
    fun setPopUpErrorDlg(popupErrorDlg: Boolean) {
        frame?.setPopUpErrorDlg(popupErrorDlg)
    }

    /**
     * set flag showing password dialog or not when parsing document with password
     * @param showPasswordDlg show password dialog or not
     */
    fun setShowPasswordDlg(showPasswordDlg: Boolean) {
        frame?.setShowPasswordDlg(showPasswordDlg)
    }

    /**
     * set flag showing find dialog
     * @param b show find dialog flag
     */
    fun setShowFindDlg(b: Boolean) {
        frame?.setShowFindDlg(b)
    }

    fun setShowProgressBar(showProgressbarDlg: Boolean) {
        frame?.setShowProgressBar(showProgressbarDlg)
    }

    fun setShowTXTEncodeDlg(showTXTEncodeDlg: Boolean) {
        frame?.setShowTXTEncodeDlg(showTXTEncodeDlg)
    }

    /**
     * set TXT default encode
     */
    fun setTXTDefaultEncode(enode: String?) {
        frame?.setTXTDefaultEncode(enode)
    }

    /**
     * set application name
     */
    fun setAppName(name: String?) {
        frame?.setAppName(name)
    }

    /**
     * set top bar height
     */
    fun setTopBarHeight(value: Int) {
        if (frame == null || parent == null) {
            return
        }
        if (value >= parent!!.context.resources.displayMetrics.heightPixels / 2) {
            return
        }
        frame!!.setTopBarHeight(value)
    }

    /**
     * set bottom bar height
     */
    fun setBottomBarHeight(value: Int) {
        if (frame == null || parent == null) {
            return
        }
        if (value >= parent!!.context.resources.displayMetrics.heightPixels / 2) {
            return
        }
        frame!!.setBottomBarHeight(value)
    }

    /**
     *  set change page flag, Only when effectively the PageSize greater than ViewSize.
     *  (for PPT, word print mode)
     */
    fun setChangePage(b: Boolean) {
        frame?.setChangePage(b)
    }

    /**
     * get zoom value(in fact, the standard ratio of zoom is value/STANDARD_RATE)
     * @see #STANDARD_RATE
     * @return  percentage value
     */
    fun getZoom(): Int {
        if (mainControl == null) {
            return STANDARD_RATE
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_ZOOM_ID, null)
        return if (obj == null) STANDARD_RATE else Math.round((obj as Float) * STANDARD_RATE)
    }

    /**
     * get The value of the fit(in fact, the standard ratio of zoom is value/STANDARD_RATE)
     */
    fun getFitZoom(): Int {
        if (mainControl == null) {
            return STANDARD_RATE
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_FIT_ZOOM_ID, null)
        return if (obj == null) STANDARD_RATE else Math.round((obj as Float) * STANDARD_RATE)
    }

    /**
     * @see #MAXZOOM
     */
    fun setZoom(value: Int, pointX: Int, pointY: Int) {
        var pointX = pointX
        var pointY = pointY
        if (mainControl == null || getView() == null || value > MAXZOOM
            || value < getFitZoom() || value == getZoom()
            || isSlideShowMode()
        ) {
            return
        }
        if (pointX < 0 || pointY < 0
            || pointX > getView()!!.width
            || pointY > getView()!!.height
        ) {
            pointX = Int.MIN_VALUE
            pointY = Int.MIN_VALUE
        }

        mainControl!!.actionEvent(EventConstant.APP_ZOOM_ID, intArrayOf(value, pointX, pointY))
        getView()!!.postInvalidate()
        if (parent != null) {
            parent!!.post {
                if (mainControl != null) {
                    try {
                        if (applicationType == APPLICATION_TYPE_SS) {
                            mainControl!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                        } else if (applicationType == APPLICATION_TYPE_WP && getViewMode() != 2) {
                            mainControl!!.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                        }
                    } catch (e: Exception) {
                        OpenTrace.e("post-view image generation failed", e)
                    }
                }
            }
            if (applicationType == APPLICATION_TYPE_WP && frame!!.isZoomAfterLayoutForWord()) {
                mainControl!!.actionEvent(EventConstant.WP_LAYOUT_NORMAL_VIEW, null)
            }
        }
    }

    /**
     * set fit size for PPT，Word view mode, PDf
     *
     * @param  value  fit size mode
     *          = 0, fit size of minimum value of pageWidth / viewWidth, pageHeight / viewHeight and 1.0, this is default mode
     *          = 1, fit size of pageWidth
     *          = 2, fit size of PageHeight
     */
    fun setFitSize(value: Int) {
        mainControl?.actionEvent(EventConstant.APP_SET_FIT_SIZE_ID, value)
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
        if (mainControl == null || applicationType == APPLICATION_TYPE_SS
            || (applicationType == APPLICATION_TYPE_WP && getViewMode() != 2)
        ) {
            return 0
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_GET_FIT_SIZE_STATE_ID, null)
        return if (obj == null) 3 else obj as Int
    }

    /**
     * get the snapshot
     */
    fun getSnapshot(destBitmap: Bitmap?): Bitmap? {
        if (mainControl == null || destBitmap == null) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_GET_SNAPSHOT_ID, destBitmap)
        return if (obj == null) null else obj as Bitmap
    }

    // ============ Word engine method ============
    /**
     * get pages count
     */
    fun getPagesCount(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_COUNT_PAGES_ID, null)
        return if (obj == null) -1 else obj as Int
    }

    /**
     * get current page number (base 1)
     */
    fun getCurrentPageNumber(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_CURRENT_PAGE_NUMBER_ID, null)
        return if (obj == null) -1 else obj as Int
    }

    /**
     * get page image raw data
     *
     * @param pageNumber    page number (base 1)
     * @return Bitmap image raw data
     */
    fun getPageToImage(pageNumber: Int): Bitmap? {
        if (mainControl == null || pageNumber < 1) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.WP_PAGE_TO_IMAGE, pageNumber)
        return if (obj == null) null else obj as Bitmap
    }

    /**
     * get specific area of whole page to image with specified size.
     * if the specific area is not completely contained in the entire page area, return null
     */
    private fun getAreaToImage(
        pageNumber: Int, srcLeft: Int, srcTop: Int, srcWidth: Int, srcHeight: Int,
        desWidth: Int, desHeight: Int
    ): Bitmap? {
        if (mainControl == null || pageNumber < 1) {
            return null
        }

        val obj = mainControl!!.getActionValue(
            EventConstant.APP_PAGEAREA_TO_IMAGE,
            intArrayOf(pageNumber, srcLeft, srcTop, srcWidth, srcHeight, desWidth, desHeight)
        )
        return if (obj == null) null else obj as Bitmap
    }

    /**
     * get specific area of whole page to image with specified size.
     * if the specific area is not completely contained in the entire page area, return null
     */
    fun getPageAreaToImage(
        pageNumber: Int, srcLeft: Int, srcTop: Int, srcWidth: Int, srcHeight: Int,
        desWidth: Int, desHeight: Int
    ): Bitmap? {
        if (applicationType != MainConstant.APPLICATION_TYPE_WP) {
            return null
        }

        return getAreaToImage(pageNumber, srcLeft, srcTop, srcWidth, srcHeight, desWidth, desHeight)
    }

    /**
     * get page size
     *
     * @param pageNumber page number (base 1)
     */
    fun getPageSize(pageNumber: Int): Rectangle? {
        if (mainControl == null) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.WP_GET_PAGE_SIZE, pageNumber)
        return if (obj == null) null else obj as Rectangle
    }

    /**
     * get current view mode of word application (Normal view mode or Page view mode or Print view mode)
     *
     * @return value   0, page view mode
     *                  1，normal view mode
     *                  2, print view mode
     *                  -1, error
     */
    fun getViewMode(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.WP_GET_VIEW_MODE, null)
        return if (obj == null) 0 else obj as Int
    }

    /**
     * set word application default view (Normal view mode or Page view mode or Print view mode)
     *
     * @param  viewMode 0, page view mode
     *                   1，normal view mode
     *                   2, print view mode
     */
    fun setDefaultViewMode(viewMode: Int) {
        var viewMode = viewMode
        if (frame == null) {
            return
        }
        if (viewMode < 0 || viewMode > 2) {
            viewMode = 0
        }
        frame!!.setWordDefaultView(viewMode.toByte())
    }

    /**
     * set normal view, changed after zoom bend, you need to re-layout
     */
    fun setZoomAfterLayoutForNormalView(value: Boolean) {
        frame?.setZoomAfterLayoutForWord(value)
    }

    /**
     * switch page for page index (base 0)
     *
     * @param index     page index
     */
    fun showPage(index: Int) {
        if (mainControl == null || index < 0) {
            return
        }
        mainControl!!.actionEvent(EventConstant.WP_SHOW_PAGE, index)
    }

    /**
     * set word application default view (Normal view or Page view)
     *
     * @param  viewMode 0, page view mode
     *                   1，normal view mode
     *                   2, print view mode
     */
    fun switchViewMode(viewMode: Int) {
        var viewMode = viewMode
        if (mainControl == null) {
            return
        }
        if (viewMode < 0 || viewMode > 2) {
            viewMode = 0
        }
        mainControl!!.actionEvent(EventConstant.WP_SWITCH_VIEW, viewMode)
    }

    // ============  Excel engine method ==============
    /**
     * get all sheet name
     *
     * return vector instance，
     *      ex. There are 3 worksheet (Sheet1, Sheet2, Sheet3), then,
     *      vector.get(0) = "sheet1"
     *      vector.get(1) = "sheet2"
     *      vector.get(2) = "sheet3"
     */
    @Suppress("UNCHECKED_CAST")
    fun getAllSheetName(): Vector<String>? {
        if (mainControl == null) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.SS_GET_ALL_SHEET_NAME, null)
        return if (obj == null) null else obj as Vector<String>
    }

    /**
     * get sheet name
     * @param   sheetNumber  sheet number  (base 1)
     */
    fun getSheetName(sheetNumber: Int): String? {
        if (mainControl == null || sheetNumber < 1) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.SS_GET_SHEET_NAME, sheetNumber)
        return if (obj == null) null else obj as String
    }

    /**
     * get current sheet number (base 1)
     */
    fun getCurrentSheetNumber(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_CURRENT_PAGE_NUMBER_ID, null)
        return if (obj == null) -1 else obj as Int
    }

    /**
     * get sheet count
     */
    fun getSheetsCount(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_COUNT_PAGES_ID, null)
        return if (obj == null) -1 else obj as Int
    }

    /**
     * show sheet for index (base 0)
     *
     * @param index     sheet index
     */
    fun showSheet(index: Int) {
        if (mainControl == null || index < 0) {
            return
        }
        mainControl!!.actionEvent(EventConstant.SS_SHOW_SHEET, index)
    }

    /**
     * remove excel application default sheet bar
     */
    fun removeDefaultSheetBarForExcel() {
        mainControl!!.actionEvent(EventConstant.SS_REMOVE_SHEET_BAR, null)
    }

    // ============= PowerPoint engine method ===============
    /**
     *  show previous slide
     */
    fun previousSlide() {
        mainControl?.actionEvent(EventConstant.APP_PAGE_UP_ID, null)
    }

    /**
     * show next slide
     */
    fun nextSlide() {
        mainControl?.actionEvent(EventConstant.APP_PAGE_DOWN_ID, null)
    }

    /**
     * get slide count
     */
    fun getSlidesCount(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_COUNT_PAGES_ID, null)
        return if (obj == null) -1 else obj as Int
    }

    /**
     * The actual loaded into memory the Slide Count
     */
    fun getLoadSlidesCount(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_GET_REAL_PAGE_COUNT_ID, null)
        return if (obj == null) -1 else obj as Int
    }

    /**
     * get current slide number (base 1)
     */
    fun getCurrentSlideNumber(): Int {
        if (mainControl == null) {
            return -1
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_CURRENT_PAGE_NUMBER_ID, null)
        return if (obj == null) -1 else obj as Int
    }

    /**
     * get slide node for slide number (base 1)
     *
     * @param slideNumber slide number
     */
    fun getSlideNote(slideNumber: Int): String? {
        if (mainControl == null || slideNumber < 1) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.PG_GET_SLIDE_NOTE, slideNumber)
        return if (obj == null) null else obj as String
    }

    /**
     * get slide size
     *
     * @param slideNumber slide number(base 1)
     */
    fun getSlideSize(slideNumber: Int): Rectangle? {
        if (mainControl == null) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.PG_GET_SLIDE_SIZE, slideNumber)
        return if (obj == null) null else obj as Rectangle
    }

    /**
     * show slide of index (base 0)
     *
     * @param index slide index
     */
    fun showSlide(index: Int) {
        if (mainControl == null || index < 0) {
            return
        }
        mainControl!!.actionEvent(EventConstant.PG_SHOW_SLIDE_ID, index)
    }

    /**
     * get slide image raw data
     *
     * @param slideNumber    slide number (base 1)
     */
    fun getSlideToImage(slideNumber: Int): Bitmap? {
        if (mainControl == null || slideNumber < 1) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.PG_SLIDE_TO_IMAGE, slideNumber)
        return if (obj == null) null else obj as Bitmap
    }

    /**
     * get specific area of whole slide to image with specified size.
     * if the specific area is not completely contained in the entire page area, return null
     */
    fun getSlideAreaToImage(
        slideNumber: Int, srcLeft: Int, srcTop: Int, srcWidth: Int, srcHeight: Int,
        desWidth: Int, desHeight: Int
    ): Bitmap? {
        if (applicationType != MainConstant.APPLICATION_TYPE_PPT) {
            return null
        }
        return getAreaToImage(slideNumber, srcLeft, srcTop, srcWidth, srcHeight, desWidth, desHeight)
    }

    /**
     * get slide thumbnail raw data
     * @see #MAXZOOM_THUMBNAIL
     * @param slideNumber   slide number (base 1)
     * @param zoomValue     0 < thumbnail zoom value <=  MAXZOOM_THUMBNAIL
     */
    fun getSlideThumbnail(slideNumber: Int, zoomValue: Int): Bitmap? {
        if (applicationType != APPLICATION_TYPE_PPT
            || mainControl == null
            || slideNumber < 1
            || zoomValue <= 0
            || zoomValue > MAXZOOM_THUMBNAIL
        ) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_THUMBNAIL_ID, intArrayOf(slideNumber, zoomValue))
        return if (obj == null) null else obj as Bitmap
    }

    /**
     * added resource ID for internationalization
     *
     * @param   resName   resource name, The value must be the same as field name in the resource file
     * @param   resID     The value in the "R.java"
     */
    fun addI18NResID(resName: String, resID: Int) {
        frame!!.addI18NResID(resName, resID)
    }

    /**
     * @param password password of document
     * @return true: succeed authenticate False: fail authenticate
     */
    fun authenticatePassword(password: String?): Boolean {
        if (mainControl == null
            || password == null
            || password.trim().isEmpty()
        ) {
            return false
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_AUTHENTICATE_PASSWORD, password)
        return if (obj == null) false else obj as Boolean
    }

    /**
     * called after authenticated the password
     * @param password password of document
     */
    fun passwordVerified(password: String?) {
        if (mainControl == null) {
            return
        }
        if (!authenticatePassword(password)) {
            mainControl!!.sysKit.errorKit.writerLog(Throwable("Password is incorrect"))
        } else {
            mainControl!!.actionEvent(EventConstant.APP_PASSWORD_OK_INIT, password)
        }
    }

    /**
     * @param encode txt encode
     */
    fun txtEncodeDialogFinished(encode: String?) {
        if (mainControl == null || encode == null) {
            return
        }
        mainControl!!.actionEvent(EventConstant.TXT_DIALOG_FINISH_ID, encode)
    }

    /**
     * Re-open the TXT document
     * @param filePath  document path
     * @param encode    TXT encoding
     */
    fun reopenTXT(filePath: String?, encode: String?) {
        if (mainControl == null || filePath == null || encode == null) {
            return
        }
        if (filePath.lowercase().endsWith(MainConstant.FILE_TYPE_TXT)) {
            setDefaultViewMode(1)
            applicationType = MainConstant.APPLICATION_TYPE_WP
            mainControl!!.actionEvent(EventConstant.TXT_REOPNE_ID, arrayOf(filePath, encode))
        }
    }

    /**
     * Whether the file support?
     *
     * @param fileName  file name
     *
     * @return  true     support
     *           false    don't support
     */
    fun isSupport(fileName: String?): Boolean {
        return FileKit.instance().isSupport(fileName)
    }

    /**
     * set animation duration(ms), should be called before begin slideshow
     * @param duration larger than 100ms and less than 1200ms
     */
    fun setAnimationDuration(duration: Int) {
        if (mainControl != null && !isSlideShowMode()) {
            val d = Math.min(1200, Math.max(duration, 100))
            mainControl!!.actionEvent(EventConstant.PG_SLIDESHOW_DURATION, d)
        }
    }

    /**
     * begin slideshow from the slideIndex-th slide
     * @param slideIndex (base 1)
     */
    fun beginSlideShow(slideIndex: Int) {
        var slideIndex = slideIndex
        if (mainControl != null) {
            if (slideIndex < 1 || slideIndex > getSlidesCount()) {
                slideIndex = 1
            }
            mainControl!!.actionEvent(EventConstant.PG_SLIDESHOW_GEGIN, if (slideIndex >= 1) slideIndex else 1)
        }
    }

    /**
     * exit slideshow
     */
    fun exitSlideShow() {
        mainControl?.actionEvent(EventConstant.PG_SLIDESHOW_END, null)
    }

    /**
     * has next slide or not, be called only when it's playing
     */
    fun hasNextSlide_Slideshow(): Boolean {
        if (mainControl == null) {
            return false
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_PAGE_DOWN_ID, null)
        return if (obj == null) false else obj as Boolean
    }

    /**
     * has previous slide or not, be called only when it's playing
     */
    fun hasPreviousSlide_Slideshow(): Boolean {
        if (mainControl == null) {
            return false
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_PAGE_UP_ID, null)
        return if (obj == null) false else obj as Boolean
    }

    /**
     * has next Action or not, be called only when it's playing
     */
    fun hasNextAction_Slideshow(): Boolean {
        if (mainControl == null) {
            return false
        }
        val obj = mainControl!!.getActionValue(EventConstant.PG_SLIDESHOW_HASNEXTACTION, null)
        return if (obj == null) false else obj as Boolean
    }

    /**
     * go to next action of slideshow, be called only when it's playing
     */
    fun nextAction_Slideshow() {
        mainControl?.actionEvent(EventConstant.PG_SLIDESHOW_NEXT, null)
    }

    /**
     * has previous action(slide) or not, be called only when it's playing
     */
    fun hasPreviousAction_Slideshow(): Boolean {
        if (mainControl == null) {
            return false
        }
        val obj = mainControl!!.getActionValue(EventConstant.PG_SLIDESHOW_HASPREVIOUSACTION, null)
        return if (obj == null) false else obj as Boolean
    }

    /**
     * go to previous action of slideshow, be called only when it's playing
     */
    fun previousAction_Slideshow() {
        mainControl?.actionEvent(EventConstant.PG_SLIDESHOW_PREVIOUS, null)
    }

    /**
     *
     */
    private fun isSlideShowMode(): Boolean {
        if (mainControl == null || applicationType != APPLICATION_TYPE_PPT) {
            return false
        }
        val obj = mainControl!!.getActionValue(EventConstant.PG_SLIDESHOW, null)
        return if (obj == null) false else obj as Boolean
    }

    /**
     * current slide exist or not
     * @param slideIndex (based 1)
     */
    fun isSlideExist(slideIndex: Int): Boolean {
        if (mainControl != null
            && applicationType == APPLICATION_TYPE_PPT
            && slideIndex > 0
        ) {
            val obj = mainControl!!.getActionValue(EventConstant.PG_SLIDESHOW_SLIDEEXIST, slideIndex)
            return if (obj == null) false else obj as Boolean
        }

        return false
    }

    /**
     * animation steps of current slide
     * @param slideIndex (based 1)
     */
    fun getSlideAnimationSteps(slideIndex: Int): Int {
        if (isSlideExist(slideIndex)) {
            val obj = mainControl!!.getActionValue(EventConstant.PG_SLIDESHOW_ANIMATIONSTEPS, slideIndex)
            return if (obj == null) -1 else obj as Int
        }

        return -1
    }

    /**
     * slideshow to image
     * @param slideIndex slide index(base 1)
     * @param step animation index(base 1)
     */
    fun getSlideshowToImage(slideIndex: Int, step: Int): Bitmap? {
        if (!isSlideShowMode()
            && step > 0
            && step <= getSlideAnimationSteps(slideIndex)
        ) {
            val obj = mainControl!!.getActionValue(EventConstant.PG_SLIDESHOW_SLIDESHOWTOIMAGE, intArrayOf(slideIndex, step))
            return if (obj == null) null else obj as Bitmap
        }
        return null
    }

    /**
     * get temporary directory path
     */
    fun getTemporaryDirectoryPath(): String? {
        if (mainControl == null) {
            return null
        }
        return mainControl!!.sysKit.pictureManage.getPicTempPath()
    }

    /**
     * set flag: whether write log or  not
     */
    fun setWriteLog(writeLog: Boolean) {
        frame?.setWriteLog(writeLog)
    }

    fun setThumbnail(isThumbnail: Boolean) {
        frame?.setThumbnail(isThumbnail)
    }

    /**
     * get doc thumbnail
     * @see #MAXZOOM_THUMBNAIL
     * @param zoomValue     0 < thumbnail zoom value <=  MAXZOOM_THUMBNAIL
     */
    fun getDocThumbnail(zoomValue: Int): Bitmap? {
        if (applicationType != APPLICATION_TYPE_WP
            || mainControl == null
            || zoomValue <= 0
            || zoomValue > MAXZOOM_THUMBNAIL
        ) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_THUMBNAIL_ID, zoomValue)
        return if (obj == null) null else obj as Bitmap
    }

    /**
     * get txt thumbnail, just for print mode
     * @see #MAXZOOM_THUMBNAIL
     * @param zoomValue     0 < thumbnail zoom value <=  MAXZOOM_THUMBNAIL
     */
    fun getTxtThumbnail(zoomValue: Int): Bitmap? {
        if (applicationType != APPLICATION_TYPE_WP
            || mainControl == null
            || zoomValue <= 0
            || zoomValue > MAXZOOM_THUMBNAIL
        ) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_THUMBNAIL_ID, zoomValue)
        return if (obj == null) null else obj as Bitmap
    }

    /**
     * get workbook thumbnail
     * @see #STANDARD_RATE
     * @see #THUMBNAILSIZE
     * @see #MAXZOOM_THUMBNAIL
     * @param width         thumbnail width when zoomValue is STANDARD_RATE(0 < width <= THUMBNAILSIZE)
     * @param height        thumbnail height when zoomValue is STANDARD_RATE(0 < height <= THUMBNAILSIZE)
     * @param zoomValue     zoom value(0 < thumbnail zoom value <=  MAXZOOM_THUMBNAIL)
     * @return return null if the specified sheet is null or not Accomplished parse
     */
    fun getXlsThumbnail(width: Int, height: Int, zoomValue: Int): Bitmap? {
        if (applicationType != APPLICATION_TYPE_SS
            || mainControl == null
            || width < 1 || width > THUMBNAILSIZE
            || height < 1 || height > THUMBNAILSIZE
            || zoomValue <= 0
            || zoomValue > MAXZOOM_THUMBNAIL
        ) {
            return null
        }
        val obj = mainControl!!.getActionValue(EventConstant.APP_THUMBNAIL_ID, intArrayOf(width, height, zoomValue))
        return if (obj == null) null else obj as Bitmap
    }

    /**
     * dispose memory, must be called, otherwise the memory can not be freed,
     * It will affect the next open file performance
     */
    fun dispose() {
        if (parent != null) {
            if (getView() != null) {
                parent!!.removeView(getView())
            }
        }
        if (mainControl!!.getReader() != null) {
            mainControl!!.getReader().abortReader()
        }
        if (frame != null) {
            frame!!.dispose()
            frame = null
        }
        if (mainControl != null) {
            mainControl!!.dispose()
            mainControl = null
        }
        parent = null
    }

    /**
     * get line width of callout
     * width range: (1 ~ 10)
     */
    fun getCalloutLineWidth(): Int {
        if (mainControl == null) {
            return 1
        }
        return mainControl!!.sysKit.calloutManager.width
    }

    /**
     * set line width of callout
     * width range: (1 ~ 10)
     */
    fun setCalloutLineWidth(width: Int) {
        if (width < 1 || width > 10) {
            return
        }
        mainControl?.sysKit?.calloutManager?.width = width
    }

    /**
     * get paint color of callout
     */
    fun getCalloutColor(): Int {
        if (mainControl == null) {
            return Color.RED
        }
        return mainControl!!.sysKit.calloutManager.color
    }

    /**
     * set paint color of callout
     * @param alpha
     * @param red
     * @param green
     * @param blue
     */
    fun setCalloutColor(alpha: Byte, red: Byte, green: Byte, blue: Byte) {
        if (mainControl != null) {
            val color = (alpha.toInt() shl 24) or (red.toInt() shl 16 and 0xFF0000) or
                    (green.toInt() shl 8 and 0xFF00) or (blue.toInt() and 0xFF)
            mainControl!!.sysKit.calloutManager.color = color
        }
    }

    /**
     * get drawing mode of callout
     * @see #DRAWMODE_NORMAL
     * @see #DRAWMODE_CALLOUTDRAW
     * @see #DRAWMODE_CALLOUTERASE
     */
    fun getDrawingMode(): Int {
        if (mainControl == null) {
            return MainConstant.DRAWMODE_NORMAL
        }

        return mainControl!!.sysKit.calloutManager.drawingMode
    }

    /**
     * set drawing mode of callout
     * @see #DRAWMODE_NORMAL
     * @see #DRAWMODE_CALLOUTDRAW
     * @see #DRAWMODE_CALLOUTERASE
     */
    fun setDrawingMode(mode: Int) {
        if (mainControl == null || mode < DRAWMODE_NORMAL || mode > DRAWMODE_CALLOUTERASE) {
            return
        }
        mainControl!!.sysKit.calloutManager.drawingMode = mode

        if (mode == DRAWMODE_CALLOUTDRAW) {
            parent!!.post {
                mainControl!!.actionEvent(EventConstant.APP_INIT_CALLOUTVIEW_ID, null)
            }
        }
    }

    /**
     * all vector graphs contained in viewIndex view have been converted or not
     */
    fun hasConvertingVectorgraph(viewIndex: Int): Boolean {
        return mainControl!!.sysKit.pictureManage.hasConvertingVectorgraph(viewIndex)
    }

    /**
     * set flag whether fitzoom can be larger than 100%, but be smaller than the max zoom
     */
    fun setIgnoreOriginalSize(ignoreOriginalSize: Boolean) {
        frame?.setIgnoreOriginalSize(ignoreOriginalSize)
    }

    /**
     * @return
     * true fitzoom may be larger than 100%, but be smaller than the max zoom
     * false fitzoom can not larger than 100%
     */
    fun isIgnoreOriginalSize(): Boolean {
        return frame?.isIgnoreOriginalSize() ?: false
    }

    /**
     * must be called before openFile function, and just for ppt, pdf and word print mode
     * @see #MOVING_HORIZONTAL
     * @see #MOVING_VERTICAL
     * @param position MOVING_HORIZONTAL and MOVING_VERTICAL
     */
    fun setPageListViewMovingPosition(position: Byte) {
        frame?.setPageListViewMovingPosition(position)
    }

    /**
     * just for ppt, pdf and word print mode
     * @see #MOVING_HORIZONTAL
     * @see #MOVING_VERTICAL
     * @return MOVING_HORIZONTAL and MOVING_VERTICAL
     */
    fun getPageListViewMovingPosition(): Byte {
        return frame?.getPageListViewMovingPosition() ?: MOVING_HORIZONTAL
    }

    companion object {
        // word application
        const val APPLICATION_TYPE_WP = MainConstant.APPLICATION_TYPE_WP
        // excel application
        const val APPLICATION_TYPE_SS = MainConstant.APPLICATION_TYPE_SS
        // PowerPoint application
        const val APPLICATION_TYPE_PPT = MainConstant.APPLICATION_TYPE_PPT
        // text application
        const val APPLICATION_TYPE_TXT = MainConstant.APPLICATION_TYPE_WP

        //zoom
        const val STANDARD_RATE = MainConstant.STANDARD_RATE
        const val MAXZOOM = MainConstant.MAXZOOM
        const val MAXZOOM_THUMBNAIL = MainConstant.MAXZOOM_THUMBNAIL

        // Drawing mode
        //not callout mode
        const val DRAWMODE_NORMAL = MainConstant.DRAWMODE_NORMAL
        //draw callout
        const val DRAWMODE_CALLOUTDRAW = MainConstant.DRAWMODE_CALLOUTDRAW
        //erase callout
        const val DRAWMODE_CALLOUTERASE = MainConstant.DRAWMODE_CALLOUTERASE

        //page list view moving position
        const val MOVING_HORIZONTAL = IPageListViewListener.Moving_Horizontal
        const val MOVING_VERTICAL = IPageListViewListener.Moving_Vertical

        //the max size of thumbnail
        const val THUMBNAILSIZE = 1000
    }
}
