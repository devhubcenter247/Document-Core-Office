/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.system

import android.app.Activity
import android.app.Dialog
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import kotlin.jvm.JvmName
import com.wxiwei.office.common.ICustomDialog
import com.wxiwei.office.common.IOfficeToPicture
import com.wxiwei.office.common.ISlideShow
import com.wxiwei.office.common.picture.PictureKit
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.fc.doc.TXTKit
import com.wxiwei.office.pg.control.PGControl
import com.wxiwei.office.pg.model.PGModel
import com.wxiwei.office.simpletext.model.IDocument
import com.wxiwei.office.ss.control.SSControl
import com.wxiwei.office.ss.model.baseModel.Workbook
import com.wxiwei.office.wp.control.WPControl
import kotlinx.coroutines.cancel

open class MainControl(frameValue: IMainFrame?) : AbstractControl() {
    private var frame: IMainFrame = frameValue!!
    private var officeToPicture: IOfficeToPicture? = null
    private var customDialog: ICustomDialog? = null
    private var slideShow: ISlideShow? = null
    private var reader: IReader? = null
    private var fileReader: FileReaderThread? = null
    private var toast: Toast? = null
    private var progressDialog: ProgressDialog? = null
    private var onKeyListener: DialogInterface.OnKeyListener? = null
    private var handler: Handler
    private var appControl: IControl? = null
    @JvmField var sysKit: SysKit
    private var onOpenFileListener: OnOpenFileListener? = null
    private var isDispose = false
    private var isCancel = false
    private var autoTest = false
    private var applicationType: Byte = -1
    private var filePath: String? = null

    init {
        sysKit = SysKit(this)
        try {
            com.wxiwei.office.simpletext.font.FontTypefaceManage.instance().setAssets(frameValue!!.getActivity().applicationContext.assets)
        } catch (e: RuntimeException) {
            // no activity yet: bundled fonts stay off, system fonts are used
        }
        handler = Handler(Looper.getMainLooper()) { message ->
            if (isCancel) return@Handler true
            when (message.what) {
                MainConstant.HANDLER_MESSAGE_SUCCESS -> {
                    // Snapshot obj before returning from this callback. Android recycles Message
                    // instances after dispatch, so reading message.obj inside a later post can yield null.
                    val model = message.obj
                    OpenTrace.d("success message received model=${model?.javaClass?.name ?: "NULL"} path=$filePath")
                    handler.post {
                        try {
                            if (frame.isShowProgressBar()) dismissProgressDialog() else customDialog?.dismissDialog(ICustomDialog.DIALOGTYPE_LOADING)
                            createApplication(model)
                            onOpenFileListener?.onOpenFileSuccess(OfficeFileType.fromPath(filePath))
                        } catch (e: Throwable) {
                            OpenTrace.e("creating application/view failed", e)
                            handleOpenFailure(e)
                        }
                    }
                }
                MainConstant.HANDLER_MESSAGE_ERROR -> {
                    val error = message.obj as? Throwable
                    handler.post {
                        handleOpenFailure(error ?: IllegalStateException("Reader failed without exception"))
                    }
                }
                MainConstant.HANDLER_MESSAGE_SHOW_PROGRESS -> {
                    if (frame.isShowProgressBar()) handler.post {
                        progressDialog = ProgressDialog.show(frame.activity, frame.getAppName(), frame.getLocalString("DIALOG_LOADING"), false, false, null)
                        progressDialog?.setOnKeyListener(onKeyListener)
                    } else customDialog?.showDialog(ICustomDialog.DIALOGTYPE_LOADING)
                }
                MainConstant.HANDLER_MESSAGE_DISMISS_PROGRESS -> handler.post {
                    dismissProgressDialog()
                    customDialog?.dismissDialog(ICustomDialog.DIALOGTYPE_LOADING)
                }
                MainConstant.HANDLER_MESSAGE_SEND_READER_INSTANCE -> {
                    reader = message.obj as? IReader
                    OpenTrace.d("reader instance delivered reader=${reader?.javaClass?.name}")
                }
            }
            true
        }
        onKeyListener = DialogInterface.OnKeyListener { dialog, keyCode, _ ->
            if (keyCode != KeyEvent.KEYCODE_BACK) return@OnKeyListener false
            dialog.dismiss()
            isCancel = true
            reader?.abortReader()
            reader?.dispose()
            frame.activity.onBackPressed()
            true
        }
        toast = Toast.makeText(frame.activity.applicationContext, "", Toast.LENGTH_LONG)
        val autoTestValue = frame.activity.intent.getStringExtra("autoTest")
        autoTest = autoTestValue == "true"
    }

    private fun handleOpenFailure(cause: Throwable) {
        if (isCancel || isDispose || OpenFileErrors.isCancellation(cause)) return
        val error = OpenFileErrors.wrap(cause, filePath)
        val handled = onOpenFileListener?.onOpenFileFailure(error) == true
        dismissProgressDialog()
        customDialog?.dismissDialog(ICustomDialog.DIALOGTYPE_LOADING)
        OpenTrace.e("open failed reason=${error.reason} path=${error.filePath}", error)
        sysKit.getErrorKit().writerLog(cause, true, !handled, askHost = false)
        if (handled) actionEvent(EventConstant.APP_ABORTREADING, true)
    }

    private fun createApplication(obj: Any?) {
        val start = android.os.SystemClock.uptimeMillis()
        OpenTrace.mark("createApplication.begin type=$applicationType model=${obj?.javaClass?.simpleName}")
        if (obj == null) {
            OpenTrace.e("read succeeded message contained NULL document type=$applicationType path=$filePath")
            throw IllegalStateException("Document with password")
        }
        OpenTrace.d("creating view for model=${obj.javaClass.name} applicationType=$applicationType path=$filePath")
        appControl = when (applicationType) {
            MainConstant.APPLICATION_TYPE_WP -> WPControl(this, obj as IDocument, filePath!!)
            MainConstant.APPLICATION_TYPE_SS -> SSControl(this, obj as Workbook, filePath!!)
            MainConstant.APPLICATION_TYPE_PPT -> PGControl(this, obj as PGModel, filePath!!)
            else -> appControl
        }
        OpenTrace.mark("createApplication.controlCreated control=${appControl?.javaClass?.simpleName}", start)
        val view = appControl!!.getView() ?: run {
            OpenTrace.e("application control returned NULL view type=$applicationType path=$filePath")
            return
        }
        OpenTrace.d("view created class=${view.javaClass.name} initialSize=${view.width}x${view.height}")
        val background = frame.getViewBackground()
        if (background is Int) view.setBackgroundColor(background)
        else if (background is Drawable) view.background = background
        frame.openFileFinish()
        OpenTrace.mark("createApplication.openFileFinish", start)
        view.post {
            OpenTrace.d("view ready class=${view.javaClass.name} size=${view.width}x${view.height} parent=${view.parent != null}")
            if (view.width == 0 || view.height == 0) {
                OpenTrace.e("view has zero size class=${view.javaClass.name} size=${view.width}x${view.height}")
            }
        }
        PictureKit.instance().isDrawPictrue = true
        // TODO(coroutine): preserve delayed hardware-layer and initialization work on the main dispatcher.
        handler.post {
            OpenTrace.mark("createApplication.initEvent.begin", start)
            actionEvent(EventConstant.SYS_SET_PROGRESS_BAR_ID, false)
            actionEvent(EventConstant.SYS_INIT_ID, null)
            frame.updateToolsbarStatus()
            getView()?.postInvalidate()
            OpenTrace.mark("createApplication.initEvent.end", start)
        }
    }

    override fun openFile(filePath: String?): Boolean {
        val start = android.os.SystemClock.uptimeMillis()
        OpenTrace.mark("mainControl.openFile.begin path=$filePath")
        OpenTrace.d("MainControl.openFile path=$filePath")
        fileReader?.cancel()
        fileReader = null
        this.filePath = filePath
        try {
            OpenFileErrors.requireReadable(filePath)
        } catch (error: Throwable) {
            handler.obtainMessage(MainConstant.HANDLER_MESSAGE_ERROR, error).sendToTarget()
            return false
        }
        val name = filePath!!.lowercase()
        applicationType = when {
            name.endsWith(MainConstant.FILE_TYPE_DOC) || name.endsWith(MainConstant.FILE_TYPE_DOCX) || name.endsWith(MainConstant.FILE_TYPE_TXT) || name.endsWith(MainConstant.FILE_TYPE_DOT) || name.endsWith(MainConstant.FILE_TYPE_DOTX) || name.endsWith(MainConstant.FILE_TYPE_DOTM) -> MainConstant.APPLICATION_TYPE_WP
            name.endsWith(MainConstant.FILE_TYPE_XLS) || name.endsWith(MainConstant.FILE_TYPE_XLSX) || name.endsWith(MainConstant.FILE_TYPE_XLT) || name.endsWith(MainConstant.FILE_TYPE_XLTX) || name.endsWith(MainConstant.FILE_TYPE_XLTM) || name.endsWith(MainConstant.FILE_TYPE_XLSM) -> MainConstant.APPLICATION_TYPE_SS
            name.endsWith(MainConstant.FILE_TYPE_PPT) || name.endsWith(MainConstant.FILE_TYPE_PPTX) || name.endsWith(MainConstant.FILE_TYPE_POT) || name.endsWith(MainConstant.FILE_TYPE_PPTM) || name.endsWith(MainConstant.FILE_TYPE_POTX) || name.endsWith(MainConstant.FILE_TYPE_POTM) -> MainConstant.APPLICATION_TYPE_PPT
            else -> MainConstant.APPLICATION_TYPE_WP
        }
        OpenTrace.d("reader selection applicationType=$applicationType path=$filePath")
        if (name.endsWith(MainConstant.FILE_TYPE_TXT) || !FileKit.instance().isSupport(name)) {
            OpenTrace.d("dispatching TXTKit preflight path=$filePath")
            TXTKit.instance().readText(this, handler, filePath)
        } else {
            OpenTrace.d("dispatching FileReaderThread path=$filePath")
            fileReader = FileReaderThread(this, handler, filePath, null)
            fileReader?.start()
        }
        OpenTrace.mark("mainControl.openFile.dispatched", start)
        return true
    }

    override fun canBackLayout() = appControl?.canBackLayout() ?: false
    override fun setLayoutThreadDied(isDied: Boolean) { appControl?.setLayoutThreadDied(isDied) }
    override fun setStopDraw(isStopDraw: Boolean) { appControl?.setStopDraw(isStopDraw) }
    override fun layoutView(x: Int, y: Int, w: Int, h: Int) { }

    override fun actionEvent(actionID: Int, obj: Any?) {
        if (actionID == EventConstant.SYS_READER_FINSH_ID) {
            reader?.let { appControl?.actionEvent(actionID, obj); it.dispose(); reader = null }
        }
        if (frame.doActionEvent(actionID, obj)) return
        when (actionID) {
            MainConstant.HANDLER_MESSAGE_SUCCESS -> { val message = Message.obtain(); message.what = MainConstant.HANDLER_MESSAGE_SUCCESS; message.obj = obj; handler.handleMessage(message) }
            EventConstant.SYS_SET_PROGRESS_BAR_ID -> handler.post { if (!isDispose) frame.showProgressBar(obj as Boolean) }
            EventConstant.TEST_REPAINT_ID -> getView()?.postInvalidate()
            EventConstant.SYS_SHOW_TOOLTIP -> if (obj is String) { toast?.setText(obj); toast?.setGravity(Gravity.CENTER, 0, 0); toast?.show() }
            EventConstant.SYS_CLOSE_TOOLTIP -> toast?.cancel()
            EventConstant.APP_CONTENT_SELECTED -> { appControl?.actionEvent(actionID, obj); frame.updateToolsbarStatus() }
            EventConstant.APP_ABORTREADING -> reader?.abortReader()
            EventConstant.SYS_START_BACK_READER_ID -> handler.post { if (!isDispose) frame.showProgressBar(true) }
            EventConstant.SYS_READER_FINSH_ID -> handler.post { if (!isDispose) frame.showProgressBar(false) }
            EventConstant.TXT_DIALOG_FINISH_ID -> (obj as? String)?.let { filePath?.let { path -> TXTKit.instance().reopenFile(this, handler, path, it) } }
            EventConstant.TXT_REOPNE_ID -> { val values = obj as? Array<String>; if (values?.size == 2) { filePath = values[0]; applicationType = MainConstant.APPLICATION_TYPE_WP; TXTKit.instance().reopenFile(this, handler, values[0], values[1]) } }
            else -> appControl?.actionEvent(actionID, obj)
        }
    }

    override fun getFind(): IFind = appControl!!.getFind()
    override fun getActionValue(actionID: Int, obj: Any?): Any? { if (actionID == EventConstant.SYS_FILEPAHT_ID) return filePath; return appControl?.getActionValue(actionID, obj) }
    override fun getView(): View = appControl!!.getView()
    override fun getDialog(activity: Activity?, id: Int): Dialog? = null
    override fun isAutoTest() = autoTest
    override fun getMainFrame(): IMainFrame = frame
    override fun getActivity(): Activity = frame.getActivity()
    override fun getOfficeToPicture() = officeToPicture
    override fun getCustomDialog() = customDialog
    override fun isSlideShow() = slideShow != null
    override fun getSlideShow() = slideShow
    override fun getReader(): IReader = reader!!
    override fun getApplicationType() = applicationType
    override fun getSysKit() = sysKit
    override fun getCurrentViewIndex() = appControl?.getCurrentViewIndex() ?: 0
    /** Navigate to a 1-based page or slide. */
    fun jumpToPage(page: Int): Boolean = when (val control = appControl) {
        is WPControl -> control.jumpToPage(page)
        is PGControl -> {
            if (page in 1..control.getTotalSlide() && !control.isSlideShow()) {
                control.actionEvent(EventConstant.PG_SHOW_SLIDE_ID, page - 1)
                true
            } else false
        }
        else -> false
    }
    fun getPageCount() = when (val control = appControl) { is WPControl -> control.getPageCount(); is PGControl -> control.getTotalSlide(); else -> 0 }
    fun getTotalPageCount() = when (val control = appControl) { is WPControl -> control.getPageCount(); is PGControl -> control.getTotalSlideCount(); else -> 0 }
    fun dismissProgressDialog() { progressDialog?.dismiss(); progressDialog = null; handler.removeCallbacksAndMessages(null) }
    fun setOffictToPicture(value: IOfficeToPicture?) { officeToPicture = value }
    fun setCustomDialog(value: ICustomDialog?) { customDialog = value }
    fun setSlideShow(value: ISlideShow?) { slideShow = value }
    fun switchViewMode() { appControl?.actionEvent(EventConstant.WP_SWITCH_VIEW, null) }
    fun switchViewMode(control: IControl?, viewModeValue: Int) { if (control != null) control.actionEvent(EventConstant.WP_SWITCH_VIEW, viewModeValue.coerceIn(0, 2)) }
    fun setOpenFileListener(listener: OnOpenFileListener?) { onOpenFileListener = listener }
    /** Main thread. True when the host shows [cause] itself, in place of the library dialog. */
    internal fun hostShowsError(cause: Throwable) = !isDispose && onOpenFileListener?.onOpenFileFailure(OpenFileErrors.wrap(cause, filePath)) == true
    override fun isEndFile() = appControl?.isEndFile() ?: false

    override fun dispose() {
        isDispose = true
        // Stop the background work first (reading, layout, conversions, timers, thumbnails),
        // so none of it keeps running on the model disposed below.
        sysKit.coroutineScope.cancel()
        fileReader?.dispose()
        fileReader = null
        appControl?.dispose()
        appControl = null
        reader?.dispose()
        reader = null
        officeToPicture?.dispose()
        officeToPicture = null
        dismissProgressDialog()
        handler.removeCallbacksAndMessages(null)
        toast?.cancel()
        toast = null
        sysKit.dispose()
    }
}
