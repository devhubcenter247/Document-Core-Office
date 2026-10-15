package com.alf06.document.reader.ui.home.document.office

import com.editor.docsdk.SlideShowView
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.alf06.document.reader.R
import com.alf06.document.reader.base.shareFile
import com.alf06.document.reader.databinding.ActivityReadDocumentBinding
import com.alf06.document.reader.model.DocumentPage
import com.alf06.document.reader.model.DocumentType
import com.alf06.document.reader.model.PageViewType
import com.alf06.document.reader.model.RecentDocument
import com.alf06.document.reader.model.RecentUi
import com.alf06.document.reader.ui.adapter.DocumentPreviewAdapter
import com.alf06.document.reader.ui.dialog.DeleteFileDialog
import com.alf06.document.reader.ui.dialog.DialogProcess
import com.alf06.document.reader.ui.dialog.DocumentPasswordDialog
import com.alf06.document.reader.ui.dialog.FileOptionsBottomSheet
import com.alf06.document.reader.ui.dialog.OpenFileErrorDialog
import com.alf06.document.reader.ui.dialog.RenameFileDialog
import com.alf06.document.reader.ui.home.document.layoutThumbnailStrip
import com.wxiwei.office.editor.ui.DialogKit
import com.alf06.document.reader.ui.home.document.savePictureToGallery
import com.wxiwei.office.editor.ui.EditDrafts
import com.wxiwei.office.editor.ui.ExcelEditPanel
import com.wxiwei.office.editor.ui.OfficeEditPanel
import com.wxiwei.office.editor.ui.SlideEditPanel
import com.wxiwei.office.editor.ui.WordEditPanel
import com.alf06.document.reader.viewmodel.DocumentViewModel
import com.ui.baselib.api.parcelable
import com.ui.baselib.base.BaseActivity
import com.ui.baselib.extensions.click
import com.ui.baselib.extensions.gone
import com.ui.baselib.extensions.invisible
import com.ui.baselib.extensions.visible
import com.ui.baselib.lifecycle.collectFlow
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.reader.ReaderState
import com.wxiwei.office.system.DocumentPasswords
import com.wxiwei.office.system.OfficeFileType
import com.wxiwei.office.system.OpenFileException
import com.wxiwei.office.system.OpenTrace
import com.wxiwei.office.system.search.DocumentSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import kotlin.math.ceil

class ReadDocumentActivity :
    BaseActivity<ActivityReadDocumentBinding>(ActivityReadDocumentBinding::inflate) {
    private val viewModel: ReadDocumentViewModel by viewModel()
    private val documentViewModel: DocumentViewModel by viewModel()
    override val hideKeyboardWhenTouch: Boolean
        get() = false

    private var document: RecentDocument? = null
    private val processDialog by lazy { DialogProcess(this) }

    private val loadingDialogLazy = lazy {
        DialogProcess(this).apply {
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    reader?.abort()
                    finish()
                }
                true
            }
        }
    }
    private val loadingDialog by loadingDialogLazy
    private var openErrorDialog: OpenFileErrorDialog? = null
    private var passwordDialog: DocumentPasswordDialog? = null

    private var reader: OfficeDocumentView? = null
    private var editPanel: OfficeEditPanel? = null
    private var draftChecked = false

    private var search: DocumentSearch? = null
    private var searchJob: Job? = null
    private var searchCount = 0
    private var searchIndex = 0

    private val thumbnailAdapter by lazy {
        DocumentPreviewAdapter(lifecycleScope, { index -> reader?.thumbnails?.get(index + 1) }) { page ->
            try {
                if (reader?.jumpToPage(page.index + 1) != true) toast(R.string.error_go_to_page)
            } catch (e: Exception) {
                toast(R.string.error_go_to_page)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        document = savedInstanceState?.parcelable(STATE_DOCUMENT) ?: intent.parcelable(ARG_DOCUMENT)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        document?.let { outState.putParcelable(STATE_DOCUMENT, it) }
        super.onSaveInstanceState(outState)
    }

    override fun initialize() {
        val type = document?.type ?: DocumentType.Doc
        binding.fileName.setText(type.titleRes())
    }

    /** Keeps the current page's thumbnail on screen; pages without a thumbnail yet are skipped. */
    private fun scrollThumbnailTo(index: Int) {
        if (index in 0 until thumbnailAdapter.itemCount) binding.rcvFrameData.scrollToPosition(index)
    }

    override fun ActivityReadDocumentBinding.setData() {
        val document = document ?: run {
            toast(R.string.error_file_not_found)
            finish()
            return
        }
        fileName.text = File(document.path).name
        txtNumberPage.isVisible = document.type != DocumentType.Excel
        rcvFrameData.adapter = thumbnailAdapter
        main.layoutThumbnailStrip(
            header = lnHeader,
            content = lnDocRead,
            strip = rcvFrameData,
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
        val reader = initReader(document.path)

        collectFlow(reader.state.map { it.isLoading }.distinctUntilChanged()) { loading ->
            if (loading && !isFinishing) loadingDialog.show()
            else if (loadingDialogLazy.isInitialized()) loadingDialog.dismiss()
        }
        collectFlow(reader.state.map { it.pageNumber to it.pageCount }.distinctUntilChanged()) { (page, count) ->
            if (count == 0) return@collectFlow
            txtNumberPage.fixWidthFor(count)
            val index = (page - 1).coerceAtLeast(0)
            thumbnailAdapter.setCurrentPage(index)
            scrollThumbnailTo(index)
        }
        collectFlow(reader.state.map { it.thumbnailCount }.distinctUntilChanged()) { count ->
            val shown = thumbnailAdapter.itemCount
            thumbnailAdapter.submitList(List(count) { DocumentPage(it) }) {
                // Thumbnails arrive after the page number, so follow the current page once its thumbnail exists.
                val index = reader.state.value.pageNumber - 1
                if (index >= shown) scrollThumbnailTo(index)
            }
        }

        lifecycleScope.launch {
            reader.thumbnailInvalidated.collect { page ->
                val index = page - 1
                if (index in 0 until thumbnailAdapter.itemCount) thumbnailAdapter.notifyItemChanged(index)
            }
        }

        lifecycleScope.launch {
            val opened = reader.state.first {
                it.status == ReaderState.Status.Failed ||
                    (it.status == ReaderState.Status.Ready && it.thumbnailCount > 0)
            }
            if (opened.status == ReaderState.Status.Ready && opened.fileType != OfficeFileType.OTHER) {
                viewModel.applyDefaultPageState(PageViewType.Thumbnail)
            }
        }

        collectFlow(
            viewModel.pageViewState.map { it == PageViewType.Thumbnail }.distinctUntilChanged()
        ) { show ->

            TransitionManager.beginDelayedTransition(
                main,
                AutoTransition().excludeTarget(R.id.lnDocRead, true).excludeChildren(R.id.lnDocRead, true)
            )
            rcvFrameData.isVisible = show
        }
        collectFlow(
            combine(
                viewModel.pageViewState,
                reader.state.map { it.status == ReaderState.Status.Opening },
                ::Pair
            )
        ) { (state, opening) ->
            progressLoad.isVisible = state == PageViewType.Thumbnail && opening
        }
        renderSearchState()
        // editing: .docx / .xlsx / .pptx once the document is open
        collectFlow(reader.state.map { it.status == ReaderState.Status.Ready }.distinctUntilChanged()) { ready ->
            val editable = File(document.path).extension.lowercase() in EDITABLE_EXTENSIONS
            icEditApp.isVisible = ready && editable
            // an editor reopening its working copy keeps its panel
            if (this@ReadDocumentActivity.editPanel?.reopening == true) return@collectFlow
            if (!ready) closeEditPanel()
            if (ready) readSlideLinks()
            if (ready && editable && this@ReadDocumentActivity.editPanel == null) {
                if (intent.getBooleanExtra(ARG_START_EDITING, false)) {
                    intent.removeExtra(ARG_START_EDITING)
                    toggleEditPanel()
                } else {
                    offerDraft(File(document.path))
                }
            }
        }
    }

    override fun ActivityReadDocumentBinding.onClick() {
        icEditApp.click { toggleEditPanel() }
        icBackApp.click { backPressed() }
        icSearchApp.click {
            lnHeaderDef.gone()
            lnSearchData.visible()
            edtSearchData.requestFocus()
            edtSearchData.post { showKeyboard(edtSearchData) }
        }
        icBackSearch.click { closeSearch() }
        icAppRotate.click { toggleOrientation() }
        icOpenTools.click { showFileOptions() }
        icSearchData.setOnClickListener { performSearch(edtSearchData.text.toString().trim()) }
        icViewNext.setOnClickListener { focusSearchResult(searchIndex + 1) }
        icViewPrev.setOnClickListener { focusSearchResult(searchIndex - 1) }
        edtSearchData.onActionSearch(::performSearch)
    }

    override fun backPressed() {
        if (editPanel != null) {
            requestCloseEditPanel()
            return
        }
        if (binding.lnSearchData.isVisible) {
            closeSearch()
            return
        }
        finish()
    }

    private fun toggleEditPanel() {
        if (editPanel != null) {
            requestCloseEditPanel()
            return
        }
        val reader = reader ?: return
        val file = File(document?.path ?: return)
        if (reader.state.value.status != ReaderState.Status.Ready || reader.control == null) {
            toast(R.string.edit_not_ready)
            return
        }
        val panel = try {
            when (file.extension.lowercase()) {
                "xlsx", "xlsm" -> ExcelEditPanel(this, reader, file)
                "pptx" -> SlideEditPanel(this, reader, file)
                "docx" -> WordEditPanel(this, reader, file)
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Edit error: ${e.message}")
            null
        } ?: run {
            toast(R.string.edit_not_supported)
            return
        }
        editPanel = panel
        binding.editPanel.removeAllViews()
        binding.editPanel.addView(com.wxiwei.office.editor.ui.EditToolbar(panel).view)
        binding.editPanel.visible()
        binding.icEditApp.alpha = 0.5f
    }

    /** Closes the edit toolbar, asking first when there are unsaved edits. */
    private fun requestCloseEditPanel() {
        val panel = editPanel ?: return
        if (!panel.hasChanges()) return closeEditPanel()
        val file = File(document?.path ?: return closeEditPanel())
        DialogKit(this).show(getString(R.string.edit_unsaved_title)) {
            text(getString(R.string.edit_unsaved_message, file.name))
            positive(getString(R.string.edit_save)) { if (panel.save()) closeEditPanel() }
            negative(getString(R.string.edit_discard)) {
                // the view shows the edits: read the file again
                closeEditPanel()
                EditDrafts.delete(this@ReadDocumentActivity, file)
                reader?.open(file.absolutePath)
            }
            neutral(getString(R.string.edit_keep_editing))
        }
    }

    /** Offers the edits kept when the app was closed before saving them. */
    private fun offerDraft(file: File) {
        if (draftChecked) return
        draftChecked = true
        val draft = EditDrafts.pending(this, file) ?: return
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(draft.lastModified()))
        DialogKit(this).show(getString(R.string.edit_draft_title)) {
            text(getString(R.string.edit_draft_message, file.name, time))
            positive(getString(R.string.edit_draft_restore)) {
                if (EditDrafts.restore(this@ReadDocumentActivity, file)) reader?.open(file.absolutePath)
                else toast(R.string.edit_draft_restore_failed)
            }
            negative(getString(R.string.edit_discard)) { EditDrafts.delete(this@ReadDocumentActivity, file) }
        }
    }

    override fun onStop() {
        super.onStop()
        editPanel?.saveDraft()
    }

    private fun closeEditPanel() {
        val panel = editPanel ?: return
        panel.close()
        editPanel = null
        hideKeyboard()
        binding.editPanel.removeAllViews()
        binding.editPanel.gone()
        binding.icEditApp.alpha = 1f
        // the panel had the taps; links work again (edits may have changed them)
        readSlideLinks()
    }

    // links of the slides, for taps while reading (the edit panels take the taps while open)
    private var slideLinks: List<com.wxiwei.office.editor.pptx.SlideScript> = emptyList()

    private fun readSlideLinks() {
        val document = document ?: return
        if (!document.path.endsWith(".pptx", true)) return
        lifecycleScope.launch {
            slideLinks = slideScript()
            val reader = reader ?: return@launch
            if (editPanel != null || slideLinks.none { it.links.isNotEmpty() }) return@launch
            reader.onDocumentGesture = { type, event ->
                type == com.wxiwei.office.system.IMainFrame.ON_SINGLE_TAP_CONFIRMED && editPanel == null && linkTap(event.rawX, event.rawY)
            }
        }
    }

    /** A tap on a linked shape of the slide shown: go to the slide it names, or (asked first) open the web page. */
    private fun linkTap(rawX: Float, rawY: Float): Boolean {
        val reader = reader ?: return false
        val presentation = reader.control?.getView() as? com.wxiwei.office.pg.control.Presentation ?: return false
        val list = presentation.getPrintMode().getListView() ?: return false
        val item = try { list.getCurrentPageView() } catch (e: Exception) { return false }
        val loc = IntArray(2); item.getLocationOnScreen(loc)
        val zoom = list.getZoom().takeIf { it > 0f } ?: return false
        val index = item.getPageIndex()
        val emuX = ((rawX - loc[0]) / zoom * 9525).toLong()
        val emuY = ((rawY - loc[1]) / zoom * 9525).toLong()
        val link = slideLinks.getOrNull(index)?.links?.lastOrNull { l ->
            emuX in l.rectEmu.x..(l.rectEmu.x + l.rectEmu.width) && emuY in l.rectEmu.y..(l.rectEmu.y + l.rectEmu.height)
        } ?: return false
        val count = reader.state.value.pageCount
        when {
            link.url != null -> DialogKit(this).confirm("Mở liên kết?", link.url!!, getString(android.R.string.ok), getString(android.R.string.cancel)) {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url))) } catch (e: Exception) { android.widget.Toast.makeText(this, "Không mở được liên kết", android.widget.Toast.LENGTH_SHORT).show() }
            }
            link.slideIndex != null -> reader.jumpToPage(link.slideIndex!! + 1)
            link.jump == "next" -> reader.jumpToPage(minOf(count, index + 2))
            link.jump == "previous" -> reader.jumpToPage(maxOf(1, index))
            link.jump == "first" -> reader.jumpToPage(1)
            link.jump == "last" -> reader.jumpToPage(count)
            else -> return false
        }
        return true
    }

    private fun showFileOptions() {
        val document = document ?: return
        lifecycleScope.launch {
            val item = RecentUi(document, documentViewModel.isFavorite(document))
            if (isFinishing || isDestroyed) return@launch
            val pageState = viewModel.pageViewState.value
            val pagedViews = document.type != DocumentType.Excel
            FileOptionsBottomSheet(
                activity = this@ReadDocumentActivity,
                item = item,
                onRename = { renameDocument(document) },
                onFavorite = { documentViewModel.toggleFavoriteRecent(document) },
                onShare = {
                    if (!shareFile(document.path)) toast(R.string.file_share_failed)
                },
                onDelete = {
                    DeleteFileDialog(this@ReadDocumentActivity) {
                        documentViewModel.deleteFile(document) { deleted ->
                            if (deleted) finish() else toast(R.string.file_delete_failed)
                        }
                    }.show()
                },
                onThumbnail = { viewModel.setPageState(PageViewType.Thumbnail) }
                    .takeIf { pagedViews && pageState != PageViewType.Thumbnail },
                onPageByPage = { viewModel.setPageState(PageViewType.PageByPage) }
                    .takeIf { pagedViews && pageState != PageViewType.PageByPage },
                onSlideShow = ::startSlideShow.takeIf { document.type == DocumentType.Ppt },
                onSlideList = ::showSlideList.takeIf { document.type == DocumentType.Ppt },
                onExportPdf = when (document.type) {
                    DocumentType.Ppt -> { { exportSlides(pdf = true) } }
                    DocumentType.Doc, DocumentType.Excel -> { { exportDocumentPdf() } }
                    else -> null
                },
                onExportImages = { exportSlides(pdf = false) }.takeIf { document.type == DocumentType.Ppt },
            ).show()
        }
    }

    /** Titles of the slides (edits in progress included), or null for a legacy .ppt. */
    private suspend fun slideScript(): List<com.wxiwei.office.editor.pptx.SlideScript> {
        (editPanel as? com.wxiwei.office.editor.ui.SlideEditPanel)?.let { return it.showScript() }
        val document = document ?: return emptyList()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = java.io.File(document.path)
            if (file.extension.equals("pptx", true)) com.wxiwei.office.editor.pptx.PptxEditor(file).showScript() else emptyList()
        }
    }

    /** The slides by title: a tap shows that slide. */
    private fun showSlideList() {
        val reader = reader ?: return
        val count = reader.state.value.pageCount
        if (count <= 0) return toast(R.string.can_slide_show_now)
        lifecycleScope.launch {
            val script = slideScript()
            if (isFinishing || isDestroyed) return@launch
            val labels = (0 until count).map { i ->
                val s = script.getOrNull(i)
                "${i + 1}. " + (s?.title?.takeIf { it.isNotBlank() } ?: "Slide ${i + 1}") + if (s?.hidden == true) " (ẩn)" else ""
            }
            DialogKit(this@ReadDocumentActivity).pick(getString(R.string.slide_list), labels, getString(android.R.string.cancel)) { i -> reader.jumpToPage(i + 1) }
        }
    }

    /**
     * Every slide: one vector PDF to a file the user picks, or PNG pictures (1920 px wide) in
     * Pictures/<name of the deck>. Drawn on the page drawing thread, with a cancellable progress.
     */
    private fun exportSlides(pdf: Boolean) {
        val reader = reader ?: return
        val slides = reader.thumbnails ?: return
        val presentation = reader.control?.getView() as? com.wxiwei.office.pg.control.Presentation ?: return
        val count = reader.state.value.pageCount
        val document = document ?: return
        if (count <= 0) return toast(R.string.can_slide_show_now)
        val name = File(document.path).nameWithoutExtension
        fun run(write: suspend ((Int) -> Unit) -> String) {
            lateinit var job: kotlinx.coroutines.Job
            var status: android.widget.TextView? = null
            val progress = DialogKit(this).show(getString(if (pdf) R.string.export_pdf else R.string.export_images), cancelable = false) {
                status = text("0/$count")
                keepOpenOnButtons()
                negative(getString(android.R.string.cancel)) { job.cancel(); dialog?.dismiss() }
            }
            job = lifecycleScope.launch {
                val message = try {
                    write { done -> runOnUiThread { status?.text = "$done/$count" } }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Không xuất được: " + (e.message ?: e.javaClass.simpleName)
                }
                progress.dismiss()
                android.widget.Toast.makeText(this@ReadDocumentActivity, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        if (pdf) {
            var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
            launcher = activityResultRegistry.register("deck-pdf-" + System.nanoTime(), androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
                launcher?.unregister()
                if (uri == null) return@register
                run { step ->
                    val written = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                            slides.onDrawingThread { com.wxiwei.office.editor.ui.writeDeckPdf(presentation, count, out, step) } ?: 0
                        }
                    }
                    if (written == count) "Đã xuất $count slide ra PDF" else "Đã xuất PDF ($written/$count slide đã mở xong)"
                }
            }
            launcher.launch("$name.pdf")
        } else run { step ->
            var saved = 0
            for (i in 0 until count) {
                val bytes = slides.onDrawingThread {
                    java.io.ByteArrayOutputStream().also { out -> com.wxiwei.office.editor.ui.writeSlide(presentation, i, false, out) }.toByteArray()
                } ?: continue
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (savePicture(name, "Slide ${i + 1}.png", bytes)) saved++ }
                step(i + 1)
            }
            "Đã lưu $saved ảnh vào Pictures/$name"
        }
    }

    /**
     * A Word document (every page, as vectors) or a workbook (the used part of each sheet on A4
     * landscape pages) into a PDF the user picks. Sheets not read yet are opened first.
     */
    private fun exportDocumentPdf() {
        val reader = reader ?: return
        val document = document ?: return
        val view = reader.control?.getView()
        val word = view as? com.wxiwei.office.wp.control.Word
        val excel = view as? com.wxiwei.office.ss.control.ExcelView
        if (word == null && excel == null) return toast(R.string.some_errors_occurred_please_try_again)
        val name = java.io.File(document.path).nameWithoutExtension
        var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
        launcher = activityResultRegistry.register("doc-pdf-" + System.nanoTime(), androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            launcher?.unregister()
            if (uri == null) return@register
            var status: android.widget.TextView? = null
            lateinit var job: kotlinx.coroutines.Job
            val progress = DialogKit(this).show(getString(R.string.export_pdf), cancelable = false) {
                status = text(getString(R.string.pdf_tool_working))
                keepOpenOnButtons()
                negative(getString(android.R.string.cancel)) { job.cancel(); dialog?.dismiss() }
            }
            job = lifecycleScope.launch {
                val message = try {
                    val written = if (word != null) {
                        val slides = reader.thumbnails
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                                val draw = { writeWordPdf(word, out) { n -> runOnUiThread { status?.text = "$n/${word.getPageCount()}" } } }
                                slides?.onDrawingThread(draw) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { draw() }
                            }
                        }
                    } else {
                        loadAllSheets(excel!!)
                        val pages = sheetPages(excel)
                        if (pages.isEmpty()) 0 else {
                            val bytes = java.io.ByteArrayOutputStream().also { out ->
                                writeSheetPdf(excel, pages, out) { n -> status?.text = "$n/${pages.size}" }
                            }.toByteArray()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
                            pages.size
                        }
                    }
                    if (written > 0) getString(R.string.pdf_export_done, written) else {
                        // nothing was written: do not leave an empty file
                        runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                        getString(R.string.pdf_export_empty)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    getString(R.string.pdf_tool_failed, e.message ?: e.javaClass.simpleName)
                }
                progress.dismiss()
                android.widget.Toast.makeText(this@ReadDocumentActivity, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        launcher.launch("$name.pdf")
    }

    /** Sheets are read when first shown: show each unread one until it is read, then go back to the sheet that was shown. */
    private suspend fun loadAllSheets(excel: com.wxiwei.office.ss.control.ExcelView) {
        val book = excel.getSpreadsheet()?.getWorkbook() ?: return
        val shown = excel.getSpreadsheet()?.getSheetView()?.getCurrentSheet()?.let { book.getSheetIndex(it) } ?: 0
        var switched = false
        for (i in 0 until book.getSheetCount()) {
            val sheet = book.getSheet(i) ?: continue
            if (sheet.getState() == com.wxiwei.office.ss.model.baseModel.Sheet.State_Accomplished) continue
            excel.showSheet(i)
            switched = true
            var waited = 0
            while (sheet.getState() != com.wxiwei.office.ss.model.baseModel.Sheet.State_Accomplished && waited < 15_000) {
                kotlinx.coroutines.delay(100); waited += 100
            }
        }
        if (switched) excel.showSheet(shown)
    }

    /** A PNG into Pictures/[folder]. */
    private fun savePicture(folder: String, fileName: String, png: ByteArray): Boolean =
        savePictureToGallery(folder, fileName, png, "image/png")

    private fun startSlideShow() {
        val reader = reader
        val document = document
        if (reader == null || document == null) return toast(R.string.can_slide_show_now)
        val startAt = reader.state.value.pageNumber - 1
        // animations, transitions, links and titles: from the edits in progress, or the file
        SlideShowView.prepare(reader, File(document.path), editPanel as? com.wxiwei.office.editor.ui.SlideEditPanel, startAt) { source ->
            if (isFinishing || isDestroyed) return@prepare
            if (source == null) return@prepare toast(R.string.can_slide_show_now)
            SlideShowActivity.source = source
            launchActivity<SlideShowActivity>()
        }
    }

    private fun renameDocument(document: RecentDocument) {
        RenameFileDialog(this, File(document.path).nameWithoutExtension) { newName ->
            documentViewModel.renameFile(document, newName) { renamed ->
                if (renamed == null) {
                    toast(R.string.file_rename_failed)
                    return@renameFile
                }
                if (isFinishing || isDestroyed) return@renameFile
                // The activity reopens the file on rotation, so later opens must use the new path.
                DocumentPasswords.get(document.path)?.let { password ->
                    DocumentPasswords.set(renamed.path, password)
                    DocumentPasswords.clear(document.path)
                }
                this.document = renamed
                intent.putExtra(ARG_DOCUMENT, renamed)
                binding.fileName.text = File(renamed.path).name
            }
        }.show()
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun TextView.fixWidthFor(pageCount: Int) {
        val digits = "8".repeat(pageCount.toString().length)
        val width = ceil(paint.measureText("$digits/$digits")).toInt() + totalPaddingLeft + totalPaddingRight
        if (minWidth != width || maxWidth != width) setWidth(width)
    }

    private fun EditText.onActionSearch(onSearch: (String) -> Unit) {
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val query = text.toString().trim()
            if (query.isNotEmpty()) {
                val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
            }
            onSearch(query)
            true
        }
    }

    private fun performSearch(keyword: String) {
        if (keyword.isEmpty()) {
            toast(R.string.please_enter_a_search)
            return
        }
        hideKeyboard()
        searchJob?.cancel()
        clearSearchResults()
        val documentSearch = binding.officeViewer.newSearch() ?: return
        processDialog.show()
        searchJob = lifecycleScope.launch {
            val count = try {
                withContext(Dispatchers.Default) { documentSearch.search(keyword) { isActive } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Search error: ${e.message}")
                toast(R.string.search_error_occurred)
                0
            } finally {
                if (searchJob === coroutineContext[Job]) processDialog.dismiss()
            }
            if (isFinishing || isDestroyed) return@launch
            search = documentSearch
            searchCount = count
            focusSearchResult(0)
            binding.lnResultSearch.visible()
        }
    }

    private fun focusSearchResult(index: Int) {
        if (index in 0 until searchCount) {
            try {
                search?.focus(index)
            } catch (e: Exception) {
                Log.d(TAG, "Focus error: ${e.message}")
            }
            searchIndex = index
        }
        renderSearchState()
    }

    private fun renderSearchState() {
        binding.tvCountSearch.text = if (searchCount == 0) {
            getString(R.string.no_result_found)
        } else {
            "${getString(R.string.result)} ${searchIndex + 1}/$searchCount"
        }
        val canPrev = searchIndex > 0
        val canNext = searchIndex < searchCount - 1
        binding.icViewPrev.isEnabled = canPrev
        binding.icViewPrev.alpha = if (canPrev) 1f else DISABLED_ALPHA
        binding.icViewNext.isEnabled = canNext
        binding.icViewNext.alpha = if (canNext) 1f else DISABLED_ALPHA
    }

    private fun clearSearchResults() {
        search?.clear()
        search = null
        searchCount = 0
        searchIndex = 0
        renderSearchState()
    }

    private fun closeSearch() {
        hideKeyboard()
        binding.lnHeaderDef.visible()
        binding.lnSearchData.invisible()
        binding.lnResultSearch.gone()
        binding.edtSearchData.setText("")
        searchJob?.cancel()
        clearSearchResults()
    }

    private fun onOpenFailure(error: OpenFileException): Boolean {
        OpenTrace.e("host open failed reason=${error.reason} path=${error.filePath}", error)
        if (isFinishing || isDestroyed) return true
        processDialog.dismiss()
        reader?.control?.dismissProgressDialog()
        if (openErrorDialog?.isShowing == true || passwordDialog?.isShowing == true) return true
        if (showPasswordDialog(error)) return true
        openErrorDialog = OpenFileErrorDialog(this, getString(openErrorMessage(error))).apply {
            setOnDismissListener {
                openErrorDialog = null
                if (!isFinishing && !isDestroyed) finish()
            }
            show()
        }
        return true
    }

    private fun showPasswordDialog(error: OpenFileException): Boolean {
        val incorrect = error.reason == OpenFileException.Reason.PASSWORD_INCORRECT
        if (!incorrect && error.reason != OpenFileException.Reason.PASSWORD_REQUIRED) return false
        val path = document?.path ?: return false
        if (File(path).extension.lowercase() !in PASSWORD_EXTENSIONS) return false
        var submitted = false
        val message = getString(
            if (incorrect) R.string.error_file_password_incorrect else R.string.error_file_password
        )
        passwordDialog = DocumentPasswordDialog(this, message) { password ->
            submitted = true
            DocumentPasswords.set(path, password)
            passwordDialog?.dismiss()

            recreate()
        }.apply {
            setOnDismissListener {
                passwordDialog = null
                if (!submitted && !isFinishing && !isDestroyed) finish()
            }
            show()
        }
        return true
    }

    private fun openErrorMessage(error: OpenFileException): Int = when (error.reason) {
        OpenFileException.Reason.BAD_FILE -> R.string.error_file_damaged
        OpenFileException.Reason.RTF_DOCUMENT -> R.string.error_file_rtf
        OpenFileException.Reason.OLD_DOCUMENT -> R.string.error_file_old_format
        OpenFileException.Reason.PASSWORD_REQUIRED -> R.string.error_file_password
        OpenFileException.Reason.PASSWORD_INCORRECT -> R.string.error_file_password_incorrect
        OpenFileException.Reason.OUT_OF_MEMORY -> R.string.error_file_too_large_memory
        OpenFileException.Reason.FILE_NOT_FOUND -> R.string.error_file_not_found
        OpenFileException.Reason.STORAGE -> R.string.error_file_storage
        OpenFileException.Reason.UNKNOWN -> R.string.error_file_open_generic
    }

    private fun initReader(filePath: String): OfficeDocumentView {
        val file = File(filePath)
        OpenTrace.d("file clicked path=${file.absolutePath} exists=${file.exists()} length=${file.length()}")
        return binding.officeViewer.also {
            it.onOpenFailure = ::onOpenFailure
            it.open(file.absolutePath)
            reader = it
        }
    }

    override fun onDestroy() {
        editPanel?.close()
        editPanel = null
        if (loadingDialogLazy.isInitialized()) loadingDialog.dismiss()
        passwordDialog?.setOnDismissListener(null)
        passwordDialog?.dismiss()
        passwordDialog = null
        openErrorDialog?.setOnDismissListener(null)
        openErrorDialog?.dismiss()
        openErrorDialog = null
        if (isFinishing) {
            DocumentPasswords.clear(document?.path)
            File(cacheDir, "decrypted").deleteRecursively()
        }
        processDialog.dismiss()

        SlideShowActivity.source = null
        super.onDestroy()
    }

    companion object {
        const val ARG_DOCUMENT = "arg_document"
        const val ARG_START_EDITING = "arg_start_editing"
        private const val TAG = "ReadDocumentActivity"
        private const val STATE_DOCUMENT = "state_document"
        private const val DISABLED_ALPHA = 0.3f
        private val EDITABLE_EXTENSIONS = setOf("docx", "xlsx", "xlsm", "pptx")
        private val PASSWORD_EXTENSIONS = setOf(
            "docx", "dotx", "dotm", "xlsx", "xltx", "xltm", "xlsm", "xls", "xlt",
            "pptx", "pptm", "potx", "potm"
        )

        private fun DocumentType.titleRes(): Int = when (this) {
            DocumentType.Excel -> R.string.xls
            DocumentType.Ppt -> R.string.pptx
            DocumentType.Txt -> R.string.txt
            else -> R.string.doc
        }
    }
}
