/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.editor.docsdk

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import com.reader.pdfviewer.PDFView
import com.wxiwei.office.editor.ui.EditToolbar
import com.wxiwei.office.editor.ui.ExcelEditPanel
import com.wxiwei.office.editor.ui.OfficeEditPanel
import com.wxiwei.office.editor.ui.SlideEditPanel
import com.wxiwei.office.editor.ui.WordEditPanel
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.reader.ReaderState
import com.wxiwei.office.system.DocumentPasswords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A view that shows a PDF, Word, Excel, PowerPoint or text document, with scrolling and zoom.
 * Put it in a layout (it must live in an Activity), call [open], and [close] it when the screen
 * goes away (for example in onDestroy). Everything is called on the main thread.
 *
 * ```
 * documentView.listener = object : DocumentView.Listener {
 *     override fun onLoaded(pageCount: Int) { ... }
 *     override fun onError(error: DocumentException) { ... }
 * }
 * documentView.open(uri)
 * ```
 *
 * Word (.docx), Excel (.xlsx) and PowerPoint (.pptx) documents can also be edited in place: once
 * [canEdit], [startEditing] shows the SDK's edit bar at the bottom of the view (or none, for your
 * own buttons: see [DocumentEditor]), with the [EditFeature]s you allow; [save] writes the
 * document back (to the Uri it came from, when opened with one). Editing needs the view to live
 * in an `AppCompatActivity`.
 */
class DocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** What the view tells about the document. Every method has an empty default. */
    interface Listener {
        /** The document shows; [pageCount] pages (slides; sheets count as one page each). */
        fun onLoaded(pageCount: Int) {}

        /** The page on screen changed to [page] (0 based), or the count grew while a long document is laid out. */
        fun onPageChanged(page: Int, pageCount: Int) {}

        /**
         * The document could not be opened, or stopped being read or drawn after [onLoaded]; when
         * [DocumentException.reason] asks for a password, call [open] again with one.
         */
        fun onError(error: DocumentException) {}

        /** The edit bar was shown ([editing] true) or hidden. */
        fun onEditingChanged(editing: Boolean) {}

        /** The edits were written to the document: [file], and the Uri it was opened from, if any. */
        fun onSaved(file: File) {}
    }

    var listener: Listener? = null

    /** Type of the document opened last, null before [open]. */
    var documentType: DocumentType? = null
        private set

    /** Pages of the document (0 until it has loaded). */
    var pageCount: Int = 0
        private set

    /** Page on screen, 0 based. */
    var currentPage: Int = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var openJob: Job? = null
    private var pdfView: PDFView? = null
    private var officeView: OfficeDocumentView? = null
    private var loaded = false
    // the document open: its file, the content Uri it was copied from, whether it had a password
    private var file: File? = null
    private var source: Uri? = null
    private var hasPassword = false
    private var editPanel: OfficeEditPanel? = null
    private val editBar = FrameLayout(context)

    /** True while editing. */
    val isEditing: Boolean get() = editPanel != null

    /** The editor while editing, null otherwise. */
    var editor: DocumentEditor? = null
        private set

    /**
     * Whether [startEditing] can start: the document has loaded, is a .docx, .xlsx, .xlsm or .pptx
     * without a password, and the view lives in an `AppCompatActivity`.
     */
    val canEdit: Boolean
        get() = loaded && officeView != null && !hasPassword && activity() != null &&
            file?.extension?.lowercase() in EDITABLE_EXTENSIONS

    /** Opens [uri] (content:// or file://), copying it to the cache first when needed. */
    @MainThread
    @JvmOverloads
    fun open(uri: Uri, password: String? = null) {
        clear()
        val content = uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        openJob = scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { UriFiles.toFile(context.applicationContext, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(DocumentException.from(e))
                return@launch
            }
            open(file, password)
            source = content
        }
    }

    /** Opens [file]; its type comes from its extension (see [DocumentType]). */
    @MainThread
    @JvmOverloads
    fun open(file: File, password: String? = null) {
        clear()
        val type = DocumentType.fromFileName(file.name)
        documentType = type
        this.file = file
        hasPassword = password != null
        when {
            !file.canRead() -> fail(DocumentException(DocumentException.Reason.NOT_FOUND, file.path))
            type == null -> fail(DocumentException(DocumentException.Reason.UNSUPPORTED, file.name))
            type == DocumentType.PDF -> openPdf(file, password)
            else -> openOffice(file, password)
        }
    }

    /** Shows page [page] (0 based). */
    @MainThread
    fun goToPage(page: Int) {
        pdfView?.jumpTo(page)
        officeView?.jumpToPage(page + 1)
    }

    /**
     * Starts editing with [features] (every one by default) and returns the editor, or null when
     * the document cannot be edited (see [canEdit]). With [showToolbar] the SDK's bar shows at the
     * bottom of the view; without it, run the commands from your own buttons ([DocumentEditor]).
     */
    @MainThread
    @JvmOverloads
    fun startEditing(features: Set<EditFeature> = EditFeature.all(), showToolbar: Boolean = true): DocumentEditor? {
        editor?.let { return it }
        if (!canEdit) return null
        val activity = activity() ?: return null
        val reader = officeView ?: return null
        val file = file ?: return null
        if (reader.control == null) return null
        val panel = try {
            when (file.extension.lowercase()) {
                "docx" -> WordEditPanel(activity, reader, file, features)
                "pptx" -> SlideEditPanel(activity, reader, file, features)
                else -> ExcelEditPanel(activity, reader, file, features)
            }
        } catch (e: Exception) {
            return null
        }
        panel.afterSave = { saved -> copyBack(saved) }
        editPanel = panel
        editBar.removeAllViews()
        if (showToolbar) {
            editBar.addView(EditToolbar(panel).view)
            if (editBar.parent == null) addView(editBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            editBar.bringToFront()
        }
        val editor = DocumentEditor(panel)
        this.editor = editor
        listener?.onEditingChanged(true)
        return editor
    }

    /** True when there are edits not saved yet. */
    fun hasUnsavedChanges(): Boolean = editPanel?.hasChanges() == true

    /** Writes the edits to the document; false when it could not (the user is told why). */
    @MainThread
    fun save(): Boolean = editPanel?.save() ?: true

    /**
     * Hides the edit bar. Edits not saved are dropped: the document shows again as it is in its
     * file. Ask the user first with [hasUnsavedChanges] and [save].
     */
    @MainThread
    fun stopEditing() {
        val panel = editPanel ?: return
        val changed = panel.hasChanges()
        closeEditBar()
        if (changed) file?.let { officeView?.open(it.path) }
    }

    /** Frees the document and its memory. The view can [open] another document afterwards. */
    @MainThread
    fun close() {
        clear()
        documentType = null
    }

    private fun openPdf(file: File, password: String?) {
        val view = PDFView(context, null)
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pdfView = view
        view.fromFile(file)
            .password(password)
            .enableAnnotationRendering(true)
            .pageSeparatorSpacing(8)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .onLoad { count ->
                if (pdfView !== view) return@onLoad
                pageCount = count
                loaded()
            }
            .onPageChange { page, count ->
                if (pdfView !== view) return@onPageChange
                currentPage = page
                pageCount = count
                listener?.onPageChanged(page, count)
            }
            .onError { error -> if (pdfView === view) fail(DocumentException.from(error, hadPassword = password != null)) }
            .load()
    }

    private fun openOffice(file: File, password: String?) {
        if (password != null) DocumentPasswords.set(file.path, password)
        val view = OfficeDocumentView(context)
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        officeView = view
        // true: the SDK reports the failure itself instead of the engine's dialogs
        view.onOpenFailure = { error ->
            if (officeView === view) fail(DocumentException.from(error))
            true
        }
        openJob = scope.launch {
            view.state.collect { state ->
                if (officeView !== view) return@collect
                if (state.status != ReaderState.Status.Ready) return@collect
                val page = (state.pageNumber - 1).coerceAtLeast(0)
                val count = state.pageCount.coerceAtLeast(1)
                val changed = page != currentPage || count != pageCount
                currentPage = page
                pageCount = count
                if (!loaded) loaded() else if (changed) listener?.onPageChanged(page, count)
            }
        }
        view.open(file.path)
    }

    private fun loaded() {
        loaded = true
        listener?.onLoaded(pageCount)
    }

    private fun fail(error: DocumentException) {
        listener?.onError(error)
    }

    private fun closeEditBar() {
        val panel = editPanel ?: return
        panel.close()
        editPanel = null
        editor = null
        editBar.removeAllViews()
        removeView(editBar)
        listener?.onEditingChanged(false)
    }

    /** The saved [saved] copy of a content Uri goes back to that Uri. */
    private fun copyBack(saved: File): Boolean {
        val uri = source
        if (uri != null) {
            try {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.FileNotFoundException(uri.toString())
                out.use { o -> saved.inputStream().use { it.copyTo(o) } }
            } catch (e: Exception) {
                fail(DocumentException(DocumentException.Reason.STORAGE, e.message, e))
                return false
            }
        }
        listener?.onSaved(saved)
        return true
    }

    private fun activity(): AppCompatActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is AppCompatActivity) return c
            c = c.baseContext
        }
        return null
    }

    private fun clear() {
        closeEditBar()
        openJob?.cancel()
        openJob = null
        pdfView?.let { it.recycle(); removeView(it) }
        pdfView = null
        officeView?.let { it.release(); removeView(it) }
        officeView = null
        loaded = false
        pageCount = 0
        currentPage = 0
        file = null
        source = null
        hasPassword = false
    }

    private companion object {
        val EDITABLE_EXTENSIONS = setOf("docx", "xlsx", "xlsm", "pptx")
    }
}
