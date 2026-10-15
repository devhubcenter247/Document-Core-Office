package com.alf06.document.reader.ui.home.document.pdf

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaScannerConnection
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.alf06.document.reader.R
import com.alf06.document.reader.base.shareFile
import com.alf06.document.reader.base.toastShort
import com.alf06.document.reader.databinding.ActivityReadPdfBinding
import com.alf06.document.reader.model.PageViewType
import com.alf06.document.reader.model.RecentDocument
import com.alf06.document.reader.model.RecentUi
import com.alf06.document.reader.ui.adapter.DocumentPreviewAdapter
import com.alf06.document.reader.ui.dialog.DeleteFileDialog
import com.alf06.document.reader.ui.dialog.DialogProcess
import com.alf06.document.reader.ui.dialog.DiscardPdfChangesDialog
import com.alf06.document.reader.ui.dialog.DocumentPasswordDialog
import com.alf06.document.reader.ui.dialog.FileOptionsBottomSheet
import com.alf06.document.reader.ui.dialog.RenameFileDialog
import com.alf06.document.reader.ui.home.document.layoutThumbnailStrip
import com.alf06.document.reader.ui.home.document.pdf.tools.PdfOrganizeActivity
import com.alf06.document.reader.ui.home.document.pdf.tools.PdfReaderTools
import com.alf06.document.reader.ui.home.document.pdf.tools.PdfReadingPrefs
import com.alf06.document.reader.viewmodel.DocumentViewModel
import com.reader.pdfviewer.listener.OnLongPressListener
import com.reader.pdfviewer.pdfium.PdfPasswordException
import com.ui.baselib.api.parcelable
import com.ui.baselib.base.BaseActivity
import com.ui.baselib.extensions.click
import com.ui.baselib.extensions.gone
import com.ui.baselib.extensions.invisible
import com.ui.baselib.extensions.visible
import com.ui.baselib.lifecycle.collectFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class ReadPdfActivity : BaseActivity<ActivityReadPdfBinding>(ActivityReadPdfBinding::inflate) {
    override val hideKeyboardWhenTouch: Boolean
        get() = false
    private val viewModel: ReadPdfViewModel by viewModel()
    private val documentViewModel: DocumentViewModel by viewModel()

    private var pdfLoaded = false
    private var readerDisposed = false
    private var pendingPage: Int? = null
    private var pendingSearchPage: Int? = null
    private var lastSearchQuery: String? = null
    private var pdfPassword: String? = null
    private var passwordDialog: DocumentPasswordDialog? = null
    private var discardChangesDialog: DiscardPdfChangesDialog? = null
    private var selectedInkColor = 0
    private var selectedInkWidth = 1

    private val loadingDialog by lazy { DialogProcess(this) }
    private val readingPrefs by lazy { PdfReadingPrefs(this) }
    private lateinit var pdfTools: PdfReaderTools
    private val organizeLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) reloadDocument()
    }
    private val thumbnailAdapter by lazy {
        DocumentPreviewAdapter(lifecycleScope, viewModel::thumbnail) { jumpToPageWhenReady(it.index) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.let {
            viewModel.page = it.getInt(STATE_PAGE, 1)
            lastSearchQuery = it.getString(STATE_SEARCH_QUERY)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, viewModel.page)
        outState.putString(STATE_SEARCH_QUERY, lastSearchQuery)
        super.onSaveInstanceState(outState)
    }

    override fun initialize() {
        val document = intent.parcelable<RecentDocument>(ARG_DOCUMENT) ?: run {
            showReadError()
            return
        }
        binding.fileName.text = File(document.path).name
        viewModel.setDocument(document)
        // back where the reader left off, unless a page was asked for
        if (viewModel.page <= 1) readingPrefs.lastPage(document.path).takeIf { it > 0 }?.let { viewModel.page = it + 1 }
        pdfTools = PdfReaderTools(this, binding.pdfRead, binding.lnPdfRead, readerHost)
        binding.lnInkToolbar.addView(pdfTools.toolRow, 0)
        pdfTools.applyTheme()
        binding.rcvFrameData.adapter = thumbnailAdapter
        layoutThumbnailStrip(resources.configuration)
        loadPdf()
    }

    // Rotation is handled here (configChanges), so the strip has to be moved by hand.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutThumbnailStrip(newConfig)
    }

    private fun layoutThumbnailStrip(config: Configuration) {
        binding.main.layoutThumbnailStrip(
            header = binding.lnHeader,
            content = binding.lnPdfRead,
            strip = binding.rcvFrameData,
            landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
        scrollThumbnailTo(viewModel.page - 1)
    }

    override fun ActivityReadPdfBinding.setData() {
        collectFlow(
            viewModel.pageViewState.map { it == PageViewType.Thumbnail }.distinctUntilChanged()
        ) { show ->
            TransitionManager.beginDelayedTransition(
                main,
                AutoTransition().excludeTarget(R.id.lnPdfRead, true).excludeChildren(R.id.lnPdfRead, true)
            )
            rcvFrameData.isVisible = show
            if (show) scrollThumbnailTo(viewModel.page - 1)
        }
        collectFlow(viewModel.pages) { pages ->
            val shown = thumbnailAdapter.itemCount
            thumbnailAdapter.submitList(pages) {

                val index = viewModel.page - 1
                if (index >= shown) scrollThumbnailTo(index)
            }
        }
    }
    private fun scrollThumbnailTo(index: Int) {
        if (index in 0 until thumbnailAdapter.itemCount) binding.rcvFrameData.scrollToPosition(index)
    }

    override fun ActivityReadPdfBinding.onClick() {
        setupInkToolbar()
        icBackApp.click { backPressed() }
        icSearchApp.click {
            lnSearchData.visible()
            lnHeaderDef.gone()
            edtSearchData.requestFocus()
            edtSearchData.post { showKeyboard(edtSearchData) }
        }
        icAppRotate.click {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        icAppEdit.click {
            pdfTools.startAnnotating()
        }
        icPdfTools.click { pdfTools.showMenu(::openOrganizer) }
        icBackSearch.click { closeSearch() }
        icSearchData.click { startSearch(edtSearchData.text.toString().trim()) }
        icOpenTools.click { showFileOptions() }
        edtSearchData.onActionSearch(::startSearch)
    }

    override fun backPressed() {
        when {
            binding.lnSearchData.isVisible -> closeSearch()
            ::pdfTools.isInitialized && pdfTools.closeBar() -> Unit
            binding.pdfRead.hasUnsavedChanges -> showDiscardChangesDialog()
            ::pdfTools.isInitialized && pdfTools.annotating -> pdfTools.stopAnnotating()
            else -> finish()
        }
    }

    /** What the reader's tools need from the activity. */
    private val readerHost = object : PdfReaderTools.Host {
        override val file: File? get() = viewModel.pdfPath?.let(::File)
        override val password: String? get() = pdfPassword
        override val loaded: Boolean get() = pdfLoaded && !readerDisposed
        override val inkColor: Int get() = INK_COLORS[selectedInkColor]
        override val inkWidth: Float get() = INK_WIDTHS[selectedInkWidth]
        override fun saveEdits(done: (Boolean) -> Unit) = saveMarkups { saved -> updateInkButtons(); done(saved) }
        override fun reload(looks: Boolean) = reloadDocument(looks)
        override fun editsChanged() = updateInkButtons()
        override fun showAnnotationBar(show: Boolean) = setInkMode(show)
        override fun jumpTo(page: Int) = jumpToPageWhenReady(page)
    }

    private fun openOrganizer() {
        val file = viewModel.pdfPath?.let(::File) ?: return
        organizeLauncher.launch(PdfOrganizeActivity.intent(this, file, pdfPassword))
    }

    /** The file changed on disk (pages organized): open it again near the same page; [looks] false keeps the thumbnails. */
    private fun reloadDocument(looks: Boolean = true) {
        if (readerDisposed) return
        pdfTools.stopModes()
        pdfLoaded = false
        lifecycleScope.launch {
            if (looks) {
                viewModel.invalidateThumbnails()
                thumbnailAdapter.refreshThumbnails()
            }
            loadPdf()
        }
    }

    private fun showDiscardChangesDialog() {
        if (readerDisposed || isFinishing || discardChangesDialog?.isShowing == true) return
        discardChangesDialog = DiscardPdfChangesDialog(this) { finish() }.also { dialog ->
            dialog.setOnDismissListener { discardChangesDialog = null }
            dialog.show()
        }
    }

    private fun showFileOptions() {
        val document = viewModel.document ?: return
        lifecycleScope.launch {
            val item = RecentUi(document, documentViewModel.isFavorite(document))
            if (readerDisposed || isFinishing) return@launch
            val pageState = viewModel.pageViewState.value
            FileOptionsBottomSheet(
                activity = this@ReadPdfActivity,
                item = item,
                onRename = { renameDocument(document) },
                onFavorite = { documentViewModel.toggleFavoriteRecent(document) },
                onShare = {
                    if (!shareFile(document.path)) toast(R.string.file_share_failed)
                },
                onDelete = {
                    DeleteFileDialog(this@ReadPdfActivity) {
                        documentViewModel.deleteFile(document) { deleted ->
                            if (deleted) finish() else toast(R.string.file_delete_failed)
                        }
                    }.show()
                },
                onThumbnail = { viewModel.setPageState(PageViewType.Thumbnail) }
                    .takeIf { pageState != PageViewType.Thumbnail },
                onPageByPage = { viewModel.setPageState(PageViewType.PageByPage) }
                    .takeIf { pageState != PageViewType.PageByPage },
                onExportImages = { pdfTools.exportImages() },
            ).show()
        }
    }

    private fun renameDocument(document: RecentDocument) {
        RenameFileDialog(this, File(document.path).nameWithoutExtension) { newName ->
            documentViewModel.renameFile(document, newName) { renamed ->
                if (renamed == null) {
                    toast(R.string.file_rename_failed)
                    return@renameFile
                }
                if (readerDisposed || isFinishing) return@renameFile
                readingPrefs.moved(document.path, renamed.path)
                viewModel.setDocument(renamed)
                binding.fileName.text = File(renamed.path).name
            }
        }.show()
    }

    @SuppressLint("SetTextI18n")
    private fun loadPdf() {
        val file = viewModel.pdfPath?.let(::File)
        if (file == null || !file.isFile || !file.canRead()) {
            showReadError()
            return
        }
        val accent = ContextCompat.getColor(this, R.color.primary)
        binding.pdfRead.fromFile(file)
            .password(pdfPassword)
            .enableAnnotationRendering(true)
            .defaultPage(viewModel.page - 1)
            .pageSeparatorSpacing(8)
            .pageFling(true)
            .pageFlingByVelocity(true)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(false)
            .enableTextSelection(true)
            .enableMagnifier(true)
            .enableTextMarkup(true)
            .setInkColor(INK_COLORS[selectedInkColor])
            .setInkWidth(INK_WIDTHS[selectedInkWidth])
            .onInkChange { _, _ -> updateInkButtons() }
            .onLongPress(object : OnLongPressListener {
                override fun onLongPress(e: MotionEvent?) { e?.let { pdfTools.longPressed(it.x, it.y) } }
            })
            .selectionHandleColor(accent)
            .selectionHighlightColor(ColorUtils.setAlphaComponent(accent, 0x40))
            .searchHighlightColor(SEARCH_HIGHLIGHT_COLOR)
            .searchHighlightCornerRadius(resources.displayMetrics.density * 2)
            .onLoad { pageCount ->
                if (readerDisposed || isFinishing) return@onLoad
                pdfLoaded = true
                // loading resets the page colors
                pdfTools.applyTheme()
                binding.txtNumberPage.text = "${viewModel.page}/$pageCount"
                pendingPage?.let(::jumpToPageWhenReady)
                showPendingSearchResult()
                viewModel.renderThumbnails(applicationContext, file, pdfPassword)
                viewModel.applyDefaultPageState(PageViewType.Thumbnail)
            }
            .onPageChange { page, pageCount ->
                if (readerDisposed || isFinishing) return@onPageChange
                viewModel.page = page + 1
                viewModel.document?.path?.let { readingPrefs.setLastPage(it, page) }
                thumbnailAdapter.setCurrentPage(page)
                binding.txtNumberPage.text = "${page + 1}/$pageCount"
                scrollThumbnailTo(page)
            }
            .onError { error ->
                Log.d(TAG, "pdf.error $error", error)
                if (isPasswordError(error)) askPdfPassword() else showReadError()
            }
            .load()
    }

    private fun isPasswordError(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is PdfPasswordException }
    private fun askPdfPassword() {
        if (isFinishing || readerDisposed || passwordDialog?.isShowing == true) return
        loadingDialog.dismiss()
        var submitted = false
        val message = getString(
            if (pdfPassword == null) R.string.error_file_password else R.string.error_file_password_incorrect
        )
        passwordDialog = DocumentPasswordDialog(this, message) { password ->
            submitted = true
            pdfPassword = password
            passwordDialog?.dismiss()
            loadPdf()
        }.apply {
            setOnDismissListener {
                passwordDialog = null
                if (!submitted && !isFinishing) finish()
            }
            show()
        }
    }

    private fun jumpToPageWhenReady(page: Int) {
        pendingPage = page
        if (!pdfLoaded || readerDisposed) return
        val count = binding.pdfRead.pageCount
        if (count <= 0) return
        pendingPage = null
        binding.pdfRead.jumpTo(page.coerceIn(0, count - 1), true)
    }

    private fun showReadError() {
        if (isFinishing || readerDisposed) return
        loadingDialog.dismiss()
        toast(R.string.some_errors_occurred_please_try_again)
        finish()
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
    private fun startSearch(query: String) {
        if (!pdfLoaded || readerDisposed) return
        if (query.isEmpty()) {
            toastShort(getString(R.string.please_enter_a_search))
            return
        }
        hideKeyboard()
        loadingDialog.show()
        lastSearchQuery = query
        lifecycleScope.launch {
            val results = runCatching { binding.pdfRead.searchDocument(query) }
                .onFailure { Log.e("ReadPdfActivity", "Search failed", it) }
            loadingDialog.dismiss()
            val matches = results.getOrElse {
                toastShort(getString(R.string.search_error_occurred))
                return@launch
            }
            if (matches.isEmpty()) {
                toastShort(getString(R.string.not_found_search))
                return@launch
            }

            binding.pdfRead.setSearchQuery(query)
        }
    }

    private fun showPendingSearchResult() {
        val page = pendingSearchPage ?: return
        val query = lastSearchQuery ?: return
        if (!pdfLoaded || readerDisposed) return
        pendingSearchPage = null
        binding.pdfRead.jumpToSearchResult(page, query)
    }

    private fun closeSearch() {
        hideKeyboard()
        binding.edtSearchData.setText("")
        lastSearchQuery = null
        binding.pdfRead.clearSearch()
        binding.lnSearchData.invisible()
        binding.lnHeaderDef.visible()
    }

    private fun saveMarkups(onResult: (Boolean) -> Unit) {
        val pdfView = binding.pdfRead
        val file = viewModel.pdfPath?.let(::File)
        if (file == null || !pdfLoaded || readerDisposed || !pdfView.hasUnsavedChanges) {
            onResult(false)
            return
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { pdfView.saveDocument(file) }
            if (readerDisposed || isFinishing) return@launch
            if (saved) {
                MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), null, null)
                viewModel.invalidateThumbnails()
                thumbnailAdapter.refreshThumbnails()
            } else {
                toast(R.string.some_errors_occurred_please_try_again)
            }
            onResult(saved)
        }
    }

    private fun setupInkToolbar() {
        val size = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._24sdp)
        val margin = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._6sdp)
        binding.lnInkColors.removeAllViews()
        INK_COLORS.indices.forEach { index ->
            binding.lnInkColors.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                setOnClickListener {
                    selectedInkColor = index
                    binding.pdfRead.setInkColor(INK_COLORS[index])
                    updateInkOptions()
                }
            })
        }
        binding.lnInkWidths.removeAllViews()
        INK_WIDTHS.indices.forEach { index ->
            binding.lnInkWidths.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = margin }
                setOnClickListener {
                    selectedInkWidth = index
                    binding.pdfRead.setInkWidth(INK_WIDTHS[index])
                    updateInkOptions()
                }
            })
        }
        binding.icInkClose.click { pdfTools.stopAnnotating() }
        binding.icInkUndo.setOnClickListener { binding.pdfRead.undoEdit { } }
        binding.icInkRedo.setOnClickListener { binding.pdfRead.redoInk() }
        binding.icInkSave.setOnClickListener {
            binding.icInkSave.isEnabled = false
            saveMarkups { saved ->
                if (saved) toast(R.string.saved_successfully)
                updateInkButtons()
            }
        }
        updateInkOptions()
    }

    /** Shows the annotation toolbar; the tools turn the pen on or off. */
    private fun setInkMode(enabled: Boolean) {
        if (!pdfLoaded || readerDisposed) return
        binding.lnInkToolbar.isVisible = enabled
        binding.txtNumberPage.isVisible = !enabled
        if (enabled) {
            binding.pdfRead.setInkColor(INK_COLORS[selectedInkColor])
            binding.pdfRead.setInkWidth(INK_WIDTHS[selectedInkWidth])
        }
        updateInkButtons()
    }

    private fun updateInkOptions() {
        val ring = ContextCompat.getColor(this, R.color.primary)
        val density = resources.displayMetrics.density
        val ringWidth = (density * 2).toInt()
        INK_COLORS.forEachIndexed { index, color ->
            binding.lnInkColors.getChildAt(index)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(color, 0xFF))
                setStroke(ringWidth, if (index == selectedInkColor) ring else Color.LTGRAY)
            }
        }
        val dotColor = ContextCompat.getColor(this, R.color.black)
        INK_WIDTHS.indices.forEach { index ->
            val view = binding.lnInkWidths.getChildAt(index) ?: return@forEach

            val inset = ((INK_WIDTHS.size - index) * density * 2.5f).toInt()
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
            val ringDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(ringWidth, if (index == selectedInkWidth) ring else Color.TRANSPARENT)
            }
            view.background = LayerDrawable(arrayOf(ringDrawable, InsetDrawable(dot, inset + ringWidth * 2)))
        }
    }

    private fun updateInkButtons() {
        if (readerDisposed) return
        val pdfView = binding.pdfRead
        binding.icInkUndo.setIconEnabled(pdfView.canUndoInk())
        binding.icInkRedo.setIconEnabled(pdfView.canRedoInk())
        binding.icInkSave.setIconEnabled(pdfView.hasUnsavedChanges)
    }

    private fun ImageView.setIconEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    override fun onDestroy() {
        discardChangesDialog?.dismiss()
        discardChangesDialog = null
        passwordDialog?.setOnDismissListener(null)
        passwordDialog?.dismiss()
        passwordDialog = null
        if (::pdfTools.isInitialized) pdfTools.release()
        readerDisposed = true
        pdfLoaded = false
        pendingPage = null
        loadingDialog.dismiss()
        binding.pdfRead.recycle()
        super.onDestroy()
    }

    companion object {
        const val ARG_DOCUMENT = "arg_document"
        private const val TAG = "ReadPdfActivity"
        private const val STATE_PAGE = "pdf_page"
        private const val STATE_SEARCH_QUERY = "pdf_search_query"
        private const val DISABLED_ALPHA = 0.3f
        private const val SEARCH_HIGHLIGHT_COLOR = 0x80FFD600.toInt()
        const val ARG_SEARCH_WITH_PAGE = "ARG_SEARCH_WITH_PAGE"
        private val INK_COLORS = intArrayOf(
            0xFF000000.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(),
            0xFF43A047.toInt(), 0x80FFEB3B.toInt()
        )

        private val INK_WIDTHS = floatArrayOf(1.5f, 3f, 6f)
    }
}
