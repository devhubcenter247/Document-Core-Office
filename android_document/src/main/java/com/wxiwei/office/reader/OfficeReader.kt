/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.reader

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wxiwei.office.common.ICustomDialog
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.constant.wp.WPViewConstant
import com.wxiwei.office.officereader.AppFrame
import com.wxiwei.office.pg.control.Presentation
import com.wxiwei.office.system.DocumentCoroutines
import com.wxiwei.office.system.IMainFrame
import com.wxiwei.office.system.LayoutInfo
import com.wxiwei.office.system.MainControl
import com.wxiwei.office.system.OfficeFileType
import com.wxiwei.office.system.OnOpenFileListener
import com.wxiwei.office.system.OpenFileException
import com.wxiwei.office.wp.control.Word
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Embeds the office reader into [container]: builds the [MainControl] and its frame, answers
 * the [IMainFrame] callbacks and publishes everything as [state]. Released automatically when
 * [activity] is destroyed.
 *
 * ```
 * val reader = OfficeReader(this, binding.officeViewer, ReaderConfig(backgroundColor = bg))
 * reader.onOpenFailure = { error -> showError(error); true }
 * reader.open(path)
 * collectFlow(reader.state) { state -> ... }
 * ```
 */
class OfficeReader(
    private val activity: ComponentActivity,
    private val container: ViewGroup,
    private var config: ReaderConfig = ReaderConfig(),
) : IMainFrame {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /**
     * Main thread. The document could not be opened, or failed after it opened (it may come after
     * the state was Ready). Return true when the host shows the error itself, false for the library dialog.
     */
    var onOpenFailure: ((OpenFileException) -> Boolean)? = null

    /**
     * Host hook in front of the built-in action handling; return true to consume [actionID].
     * May be called off the main thread.
     */
    var onAction: ((actionID: Int, obj: Any?) -> Boolean)? = null

    /**
     * Touch gestures on the document ([IMainFrame.ON_SINGLE_TAP_CONFIRMED], [IMainFrame.ON_LONG_PRESS]...),
     * e.g. for an editor's selection; return true to consume. Main thread.
     */
    var onDocumentGesture: ((type: Byte, event: android.view.MotionEvent) -> Boolean)? = null

    /** The underlying control, for APIs this class does not wrap; null before [open] or after release. */
    var control: MainControl? = null
        private set

    /** Thumbnails of the open Word/TXT or PowerPoint document; null for Excel and before it shows. */
    @Volatile
    var thumbnails: PageThumbnails? = null
        private set

    /** The document view (Word, Presentation or ExcelView) once the file is open. */
    val documentView: View?
        get() = if (state.value.status == ReaderState.Status.Ready) control?.getView() else null

    private val _thumbnailInvalidated = MutableSharedFlow<Int>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Page numbers whose image changed after it was handed out (a Word page finished laying out,
     * a WMF/EMF picture finished converting); rebind those items.
     */
    val thumbnailInvalidated: SharedFlow<Int> = _thumbnailInvalidated.asSharedFlow()

    /**
     * Main-thread work of the open document (callbacks arriving from background threads);
     * made by [open], cancelled by [release].
     */
    @Volatile
    private var mainScope: CoroutineScope? = null
    private var frame: AppFrame? = null
    private var observedView: View? = null
    private val drawListener = ViewTreeObserver.OnDrawListener { syncPageCount() }

    /** Main thread: a throttled page count waiting to be published, see [syncPageCount]. */
    private var pendingCountSync: Job? = null
    private var lastCountPublish = 0L

    private val openListener = object : OnOpenFileListener {
        override fun onOpenFileSuccess(fileType: OfficeFileType) {
            _state.update { it.copy(status = ReaderState.Status.Ready, fileType = fileType, error = null) }
            syncPageCount()
        }

        override fun onOpenFileFailure(error: OpenFileException): Boolean {
            _state.update { it.copy(status = ReaderState.Status.Failed, isLoading = false, error = error) }
            return onOpenFailure?.invoke(error) ?: false
        }
    }

    private val loadingDialog = object : ICustomDialog {
        override fun showDialog(type: Byte) {
            if (type == ICustomDialog.DIALOGTYPE_LOADING) _state.update { it.copy(isLoading = true) }
        }

        override fun dismissDialog(type: Byte) {
            if (type == ICustomDialog.DIALOGTYPE_LOADING) _state.update { it.copy(isLoading = false) }
        }
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) release()
        })
    }

    /** Opens [path], replacing any document already shown. */
    fun open(path: String) = open(path, config)

    /** Applies options when replacing the document, keeping the observable flows stable. */
    internal fun open(path: String, config: ReaderConfig) {
        release()
        this.config = config
        mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val control = MainControl(this).also {
            it.setOpenFileListener(openListener)
            it.setCustomDialog(loadingDialog)
            control = it
        }
        _state.value = ReaderState(
            status = ReaderState.Status.Opening,
            fileType = OfficeFileType.fromPath(path),
        )
        frame = AppFrame(activity).also { frame ->
            container.addView(frame, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            frame.post { if (this.control === control) control.openFile(path) }
        }
    }

    /** Scrolls to [pageNumber] (1-based page or slide). */
    fun jumpToPage(pageNumber: Int): Boolean = control?.jumpToPage(pageNumber) ?: false

    /** Stops a background read, e.g. when the user backs out of the loading indicator. */
    fun abort() {
        control?.actionEvent(EventConstant.APP_ABORTREADING, true)
    }

    /** Disposes the document and removes it from [container]; [open] can be called again. */
    fun release() {
        observedView?.let { view ->
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(drawListener)
        }
        observedView = null
        mainScope?.cancel()
        pendingCountSync = null
        mainScope = null
        thumbnails?.dispose()
        thumbnails = null
        control?.dispose()
        control = null
        frame?.let { container.removeView(it) }
        frame = null
        _state.value = ReaderState()
    }

    /** Reads the page count from the control; it changes without [changePage] while documents load. */
    private fun syncPageCount(reloaded: Boolean = false) {
        val count = try {
            control?.getPageCount() ?: return
        } catch (e: Exception) {
            return
        }
        val thumbnailCount = if (canDrawPages()) count else 0
        val current = state.value
        val previous = current.pageCount
        if (count == previous && thumbnailCount == current.thumbnailCount && !reloaded) return
        // an edit lays the pages after it out again: the count drops, then grows back with the
        // background layout. The last count stays until that layout is done
        if (!reloaded && count < previous && (observedView as? Word)?.isLayoutFinished() == false) return
        // Word adds pages one at a time while it lays out in the background, noticed on every
        // frame: publishing each one makes the host rebuild its page list and redraw the last
        // thumbnail over and over while the user scrolls. Growth is published at most every
        // COUNT_INTERVAL_MS; the first count, a reload and the finished layout go out at once.
        val now = SystemClock.uptimeMillis()
        val wait = lastCountPublish + COUNT_INTERVAL_MS - now
        if (!reloaded && previous > 0 && wait > 0) {
            if (pendingCountSync?.isActive != true) {
                pendingCountSync = mainScope?.launch {
                    delay(wait)
                    pendingCountSync = null
                    syncPageCount()
                }
            }
            return
        }
        pendingCountSync?.cancel()
        pendingCountSync = null
        lastCountPublish = now
        // Word lays out in the background, so its last page may have been drawn while partial.
        if (observedView is Word && previous > 0) {
            thumbnails?.invalidate(previous)
            _thumbnailInvalidated.tryEmit(previous)
        }
        _state.update { it.copy(pageCount = count, thumbnailCount = thumbnailCount) }
    }

    /** Redraws the thumbnail of [pageNumber] (1-based) after the page content was edited. */
    fun invalidateThumbnail(pageNumber: Int) {
        thumbnails?.invalidate(pageNumber) ?: return
        _thumbnailInvalidated.tryEmit(pageNumber)
    }

    private fun canDrawPages(): Boolean = when (val view = observedView) {
        is Word -> view.getCurrentRootType() != WPViewConstant.NORMAL_ROOT.toInt()
        is Presentation -> true
        else -> false
    }

    // region IMainFrame

    override fun getActivity(): Activity = activity

    override fun openFileFinish() {
        val control = control ?: return
        val frame = frame ?: return
        val view = control.getView()
        frame.removeAllViews()
        frame.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        observedView?.let { old ->
            val observer = old.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(drawListener)
        }
        observedView = view
        view.viewTreeObserver.addOnDrawListener(drawListener)

        thumbnails?.dispose()
        // A TXT re-opened with another encoding is a new document: empty the page list so the
        // host drops the old thumbnails even when the page count comes out the same.
        _state.update { it.copy(pageNumber = 0, pageCount = 0, thumbnailCount = 0, layout = null) }
        thumbnails = if (view is Word || view is Presentation) {
            PageThumbnails(
                view,
                config.thumbnailScale,
                config.thumbnailCacheBytes,
                DocumentCoroutines.childScope(control),
            ) { page ->
                _thumbnailInvalidated.tryEmit(page)
            }
        } else null
    }

    override fun doActionEvent(actionID: Int, obj: Any?): Boolean {
        // May arrive on the reader thread.
        if (actionID == EventConstant.SYS_READER_FINSH_ID) mainScope?.launch { syncPageCount(reloaded = true) }
        // Background Word layout grows even when the document view is not drawing.
        // Publish progress through the same throttle, and always flush the final count.
        if (actionID == EventConstant.SYS_UPDATE_TOOLSBAR_BUTTON_STATUS) {
            mainScope?.launch { syncPageCount() }
        }
        if (actionID == EventConstant.WP_LAYOUT_COMPLETED) {
            mainScope?.launch { syncPageCount(reloaded = true) }
        }
        // A WMF/EMF picture finished converting, fired from the converter thread: TEST_REPAINT_ID
        // on success, SYS_VECTORGRAPH_PROGRESS whether or not it worked (a failed picture is then
        // skipped, so the page can be drawn complete without it).
        if (actionID == EventConstant.TEST_REPAINT_ID || actionID == EventConstant.SYS_VECTORGRAPH_PROGRESS) {
            thumbnails?.onPictureConverted()
        }
        if (onAction?.invoke(actionID, obj) == true) return true
        val calloutManager = control?.getSysKit()?.getCalloutManager()
        try {
            when (actionID) {
                EventConstant.SYS_RESET_TITLE_ID,
                EventConstant.SYS_ONBACK_ID,
                EventConstant.SYS_UPDATE_TOOLSBAR_BUTTON_STATUS,
                EventConstant.SYS_HELP_ID,
                EventConstant.APP_FIND_ID,
                EventConstant.APP_SHARE_ID,
                EventConstant.FILE_MARK_STAR_ID,
                EventConstant.APP_FINDING,
                EventConstant.APP_FIND_BACKWARD,
                EventConstant.APP_FIND_FORWARD,
                EventConstant.SS_CHANGE_SHEET,
                EventConstant.APP_COLOR_ID -> Unit

                EventConstant.APP_DRAW_ID -> startCalloutDraw()
                EventConstant.APP_BACK_ID -> calloutManager?.setDrawingMode(MainConstant.DRAWMODE_NORMAL)
                EventConstant.APP_PEN_ID ->
                    if (obj as Boolean) startCalloutDraw()
                    else calloutManager?.setDrawingMode(MainConstant.DRAWMODE_NORMAL)

                EventConstant.APP_ERASER_ID -> calloutManager?.setDrawingMode(
                    if (obj as Boolean) MainConstant.DRAWMODE_CALLOUTERASE else MainConstant.DRAWMODE_NORMAL
                )

                else -> return false
            }
        } catch (e: Exception) {
            control?.getSysKit()?.getErrorKit()?.writerLog(e)
        }
        return true
    }

    private fun startCalloutDraw() {
        control?.getSysKit()?.getCalloutManager()?.setDrawingMode(MainConstant.DRAWMODE_CALLOUTDRAW)
        frame?.post { control?.actionEvent(EventConstant.APP_INIT_CALLOUTVIEW_ID, null) }
    }

    override fun changePage(pageNumber: Int, pageCount: Int) {
        _state.update { it.copy(pageNumber = pageNumber) }
        syncPageCount()
    }

    override fun completeLayout(info: LayoutInfo) {
        _state.update { it.copy(layout = info, pageNumber = info.pageNumber) }
        syncPageCount(reloaded = true)
    }

    override fun onEventMethod(v: View?, e1: android.view.MotionEvent?, e2: android.view.MotionEvent?,
                               xValue: Float, yValue: Float, eventMethodType: Byte): Boolean {
        val event = e1 ?: return false
        return onDocumentGesture?.invoke(eventMethodType, event) ?: false
    }

    override fun isShowTXTEncodeDlg(): Boolean = config.showTxtEncodeDialog
    override fun getTXTDefaultEncode(): String? = config.txtDefaultEncode
    override fun isTouchZoom(): Boolean = config.touchZoom
    override fun getWordDefaultView(): Byte = config.wordDefaultView
    override fun getWordPageSpacing(): Int = config.wordPageSpacing ?: super.getWordPageSpacing()
    override fun getViewBackground(): Any? = config.backgroundColor

    override fun dispose() = release()

    // endregion

    private companion object {
        const val COUNT_INTERVAL_MS = 500L
    }
}
