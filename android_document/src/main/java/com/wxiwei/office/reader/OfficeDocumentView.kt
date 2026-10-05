/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.wxiwei.office.R
import com.wxiwei.office.system.MainControl
import com.wxiwei.office.system.OpenFileException
import com.wxiwei.office.system.search.DocumentSearch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * XML-friendly office reader hosted by a [ComponentActivity], including wrapped activity contexts.
 * Options from XML or [config] apply on the next [open]. Public operations run on the main thread.
 * No engine is created in the layout editor.
 *
 * The reader releases on the activity's ON_DESTROY; pending page restoration is cancelled too.
 * Detaching from the window does not release it, since RecyclerView and ViewPager may detach
 * views temporarily. Call [release] when permanently discarding a view before activity destruction.
 * A view with an id saves its path and page; restoration waits until that page is laid out, and
 * is dropped if the user touches the document, or the host jumps to a page or starts a search first.
 */
class OfficeDocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Options for the next [open]; changing these does not alter the current document. */
    var config: ReaderConfig = readConfig(attrs, defStyleAttr)

    private val activity = if (isInEditMode) null else findActivity(context)
    private val reader = activity?.let { OfficeReader(it, this, config) }
    private var openedPath: String? = null
    private var restoreJob: Job? = null

    /** Current document status, page counts and layout; stable across [open] calls. */
    val state: StateFlow<ReaderState> = reader?.state ?: MutableStateFlow(ReaderState()).asStateFlow()

    /** Thumbnails for the current document, or null when unavailable. */
    val thumbnails: PageThumbnails?
        get() = reader?.thumbnails

    /** Page numbers whose thumbnails should be rebound. */
    val thumbnailInvalidated: SharedFlow<Int> =
        reader?.thumbnailInvalidated ?: MutableSharedFlow<Int>().asSharedFlow()

    /** The open Word, Presentation or ExcelView, or null until ready. */
    val documentView: View?
        get() = reader?.documentView

    /** Underlying engine control, or null before [open] and after [release]. */
    val control: MainControl?
        get() = reader?.control

    /** Main-thread failure callback, see [OfficeReader.onOpenFailure]; return true to replace the library's error dialog. */
    var onOpenFailure: ((OpenFileException) -> Boolean)? = null
        set(value) {
            field = value
            reader?.onOpenFailure = value
        }

    /** Touch gestures on the document, see [OfficeReader.onDocumentGesture]. */
    var onDocumentGesture: ((type: Byte, event: MotionEvent) -> Boolean)? = null
        set(value) {
            field = value
            reader?.onDocumentGesture = value
        }

    /** Redraws the thumbnail of [pageNumber] (1-based) after an edit changed that page. */
    fun invalidateThumbnail(pageNumber: Int) {
        reader?.invalidateThumbnail(pageNumber)
    }

    /** Action hook; return true to consume the action. May be called off the main thread. */
    var onAction: ((actionID: Int, obj: Any?) -> Boolean)? = null
        set(value) {
            field = value
            reader?.onAction = value
        }

    init {
        activity?.lifecycle?.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) release()
        })
    }

    /** Opens [path] using [config], replacing the document and cancelling pending restoration. */
    fun open(path: String) {
        cancelRestore()
        if (reader == null) return
        openedPath = path
        reader.open(path, config)
    }

    /**
     * Scrolls to the 1-based [pageNumber], returning whether navigation succeeded. Drops a pending
     * saved-page restoration: the host's choice of page wins.
     */
    fun jumpToPage(pageNumber: Int): Boolean {
        cancelRestore()
        return reader?.jumpToPage(pageNumber) ?: false
    }

    /**
     * A touch on the document means the user took over before the saved page could be restored
     * (a large Word file lays that page out late): restoring now would yank them away.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelRestore()
        return super.dispatchTouchEvent(event)
    }

    /**
     * Takes over the touch in progress: the document gets a cancel, so it neither scrolls nor
     * flings, and the rest of the gesture comes here (a picture dragged after a long press).
     * Cleared when the finger is lifted.
     */
    var touchCapture: ((MotionEvent) -> Unit)? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val capture = touchCapture ?: return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            // a new touch never belongs to an old one
            MotionEvent.ACTION_DOWN -> { touchCapture = null; return super.onInterceptTouchEvent(event) }
            // the event taken over with is not passed to onTouchEvent: the lift would be lost
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { capture(event); touchCapture = null }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val capture = touchCapture ?: return super.onTouchEvent(event)
        capture(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touchCapture = null
        return true
    }

    private fun cancelRestore() {
        restoreJob?.cancel()
        restoreJob = null
    }

    /** Stops a background read, for example when backing out of a loading dialog. */
    fun abort() {
        reader?.abort()
    }

    /** Releases the document and cancels restoration; [open] may be called again. */
    fun release() {
        cancelRestore()
        openedPath = null
        reader?.release()
    }

    /**
     * Creates a single-query search for the current document, or null when unavailable. Drops a
     * pending saved-page restoration, which would otherwise scroll away from the results.
     */
    fun newSearch(): DocumentSearch? {
        cancelRestore()
        return DocumentSearch.of(documentView)
    }

    /** Saves the opened path and the currently reported page. */
    override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState()).also {
        it.path = openedPath
        it.page = state.value.pageNumber
    }

    /** Restores the page without reopening a document the host has already opened. */
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        val path = state.path ?: return
        if (openedPath == null) open(path)
        if (openedPath != path) return
        val status = this.state.value.status
        if (status != ReaderState.Status.Opening && status != ReaderState.Status.Ready) return
        cancelRestore()
        val page = state.page
        if (page <= 0) return
        val job = activity?.lifecycleScope?.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            val ready = this@OfficeDocumentView.state.first {
                it.status == ReaderState.Status.Failed ||
                    (it.status == ReaderState.Status.Ready && it.pageCount >= page)
            }
            if (ready.status != ReaderState.Status.Ready) return@launch
            // Ready comes before the document view is laid out, and Word applies its fit zoom on
            // the first layout; jumping earlier scrolls by the pre-layout zoom to the wrong page.
            val view = documentView ?: return@launch
            view.awaitLayout()
            view.post {
                // Still wanted: no touch, host jump, open() or release() since it was scheduled.
                if (restoreJob === self) {
                    restoreJob = null
                    reader?.jumpToPage(page)
                }
            }
        }
        restoreJob = job
        job?.start()
    }

    private suspend fun View.awaitLayout() {
        if (isLaidOut && width > 0 && height > 0) return
        suspendCancellableCoroutine { continuation ->
            val listener = object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    if (right - left <= 0 || bottom - top <= 0) return
                    removeOnLayoutChangeListener(this)
                    continuation.resume(Unit)
                }
            }
            addOnLayoutChangeListener(listener)
            continuation.invokeOnCancellation { post { removeOnLayoutChangeListener(listener) } }
        }
    }

    private fun readConfig(attrs: AttributeSet?, defStyleAttr: Int): ReaderConfig {
        val defaults = ReaderConfig()
        val values = context.obtainStyledAttributes(attrs, R.styleable.OfficeDocumentView, defStyleAttr, 0)
        return try {
            defaults.copy(
                backgroundColor = if (values.hasValue(R.styleable.OfficeDocumentView_odv_pageBackground))
                    values.getColor(R.styleable.OfficeDocumentView_odv_pageBackground, 0)
                else defaults.backgroundColor,
                wordDefaultView = values.getInt(
                    R.styleable.OfficeDocumentView_odv_wordViewMode, defaults.wordDefaultView.toInt()
                ).toByte(),
                wordPageSpacing = if (values.hasValue(R.styleable.OfficeDocumentView_odv_wordPageSpacing))
                    values.getDimensionPixelSize(R.styleable.OfficeDocumentView_odv_wordPageSpacing, 0).coerceAtLeast(0)
                else defaults.wordPageSpacing,
                thumbnailScale = values.getFloat(
                    R.styleable.OfficeDocumentView_odv_thumbnailScale, defaults.thumbnailScale
                ),
                thumbnailCacheBytes = if (values.hasValue(R.styleable.OfficeDocumentView_odv_thumbnailCacheMb))
                    values.getInt(R.styleable.OfficeDocumentView_odv_thumbnailCacheMb, 0).let { mb ->
                        require(mb > 0) { "odv_thumbnailCacheMb must be positive" }
                        (mb.toLong() * 1024 * 1024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                else defaults.thumbnailCacheBytes,
                showTxtEncodeDialog = values.getBoolean(
                    R.styleable.OfficeDocumentView_odv_showTxtEncodeDialog, defaults.showTxtEncodeDialog
                ),
                txtDefaultEncode = if (values.hasValue(R.styleable.OfficeDocumentView_odv_txtDefaultEncoding))
                    values.getString(R.styleable.OfficeDocumentView_odv_txtDefaultEncoding)
                else defaults.txtDefaultEncode,
                touchZoom = values.getBoolean(R.styleable.OfficeDocumentView_odv_touchZoom, defaults.touchZoom),
            )
        } finally {
            values.recycle()
        }
    }

    private fun findActivity(context: Context): ComponentActivity {
        var current = context
        val visited = mutableSetOf<Context>()
        while (visited.add(current)) {
            if (current is ComponentActivity) return current
            current = (current as? ContextWrapper)?.baseContext ?: break
        }
        throw IllegalStateException("OfficeDocumentView requires a ComponentActivity context (or a ContextWrapper around one).")
    }

    /** Parcelable state; JVM-visible so Android can read its CREATOR after process recreation. */
    internal class SavedState : BaseSavedState {
        var path: String? = null
        var page: Int = 0

        constructor(superState: Parcelable?) : super(superState)

        private constructor(source: Parcel) : super(source) {
            path = source.readString()
            page = source.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(path)
            out.writeInt(page)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }
}
