/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.reader.pdfviewer

import com.wxiwei.office.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnScrollChangedListener
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import com.reader.pdfviewer.exception.PageRenderingException
import com.reader.pdfviewer.link.DefaultLinkHandler
import com.reader.pdfviewer.link.LinkHandler
import com.reader.pdfviewer.listener.*
import com.reader.pdfviewer.model.PdfAnnotationInfo
import com.reader.pdfviewer.model.PagePart
import com.reader.pdfviewer.model.SearchResult
import com.reader.pdfviewer.search.DetachedPageSource
import com.reader.pdfviewer.search.DisplayedPageSource
import com.reader.pdfviewer.search.DocumentTextIndex
import com.reader.pdfviewer.search.OcrCache
import com.reader.pdfviewer.search.OcrLine
import com.reader.pdfviewer.scroll.ScrollHandle
import com.reader.pdfviewer.source.*
import com.reader.pdfviewer.util.*
import com.reader.pdfviewer.pdfium.PdfDocument
import com.reader.pdfviewer.pdfium.PdfiumCore
import com.reader.pdfviewer.pdfium.util.Size
import com.reader.pdfviewer.pdfium.util.SizeF
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * It supports animations, zoom, cache, and swipe.
 * 
 * 
 * To fully understand this class you must know its principles :
 * - The PDF document is seen as if we always want to draw all the pages.
 * - The thing is that we only draw the visible parts.
 * - All parts are the same size, this is because we can't interrupt a native page rendering,
 * so we need these renderings to be as fast as possible, and be able to interrupt them
 * as soon as we can.
 * - The parts are loaded when the current offset or the current zoom level changes
 * 
 * 
 * Important :
 * - DocumentPage = A page of the PDF document.
 * - UserPage = A page as defined by the user.
 * By default, they're the same. But the user can change the pages order
 * using [.load]. In this
 * particular case, a userPage of 5 can refer to a documentPage of 17.
 */
class PDFView(context: Context, set: AttributeSet?) : RelativeLayout(context, set) {
    /**
     * Sets the requested minimum zoom level.
     * 
     * 
     * Pinch zoom is constrained to the `[0.3f, 100f]` range. Values outside this
     * range are clamped while processing a pinch gesture. If the resulting minimum is greater
     * than the resulting maximum, the effective maximum is raised to the effective minimum.
     * 
     * @param minZoom requested minimum zoom level
     */
    var minZoom: Float = DEFAULT_MIN_SCALE
    var midZoom: Float = DEFAULT_MID_SCALE
    /**
     * Sets the requested maximum zoom level.
     * 
     * 
     * Pinch zoom is constrained to the `[0.3f, 100f]` range. Values outside this
     * range are clamped while processing a pinch gesture. If the resulting maximum is less
     * than the resulting minimum, the effective maximum is raised to the effective minimum.
     * 
     * @param maxZoom requested maximum zoom level
     */
    var maxZoom: Float = DEFAULT_MAX_SCALE

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    internal enum class ScrollDir {
        NONE, START, END
    }

    private var scrollDir = ScrollDir.NONE

    /**
     * Rendered parts go to the cache manager
     */
    var cacheManager: CacheManager

    /**
     * Animation manager manage all offset and zoom animation
     */
    private val animationManager: AnimationManager

    /**
     * Drag manager manage all touch events
     */
    private val dragPinchManager: DragPinchManager

    var pdfFile: PdfFile? = null

    /**
     * The index of the current sequence
     */
    var currentPage: Int = 0
        private set

    /**
     * True while an animated [jumpTo] is running. The current page stays on the target
     * page so the pages passed on the way do not fire page changes.
     */
    internal var isJumping = false
        private set

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    var currentXOffset: Float = 0f
        private set

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    var currentYOffset: Float = 0f
        private set

    /**
     * The zoom level, always >= DEFAULT_MIN_SCALE
     */
    var zoom: Float = minZoom
        private set

    /**
     * True if the PDFView has been recycled
     */
    var isRecycled: Boolean = true
        private set

    /**
     * Current state of the view
     */
    private var state = State.DEFAULT

    /**
     * Scope of the decoding and rendering coroutines, their jobs are cancelled in [recycle]
     */
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Decodes the PDF document during the loading phase
     */
    private var documentDecoder: DocumentDecoder? = null

    /**
     * Renders page parts in the background once the document is loaded
     */
    internal var pageRenderer: PageRenderer? = null

    private val pagesLoader: PagesLoader

    var callbacks: Callbacks = Callbacks()

    /**
     * Paint object for drawing
     */
    private val paint: Paint

    private val textSelectionPaint: Paint

    private val searchHighlightPaint: Paint

    /**
     * Current search query, its matches are highlighted on the pages being drawn
     */
    var searchQuery: String? = null
        private set

    /**
     * Search match rectangles of each page, in page-relative coordinates (0..1), computed lazily
     */
    private val searchHighlights = HashMap<Int, List<RectF>>()
    private val searchPendingPages = HashSet<Int>()
    private var searchGeneration = 0
    private var searchFocusPage = -1
    private var searchIgnoreCase = true
    private var searchHighlightCornerRadius = 0f

    /** Text of the pages of the loaded document, for [searchDocument] */
    private var textIndex: DocumentTextIndex? = null
    private var textIndexJob: Job? = null

    /** Source of the loaded document, the text index opens its own instance from it */
    private var loadedDocSource: DocumentSource? = null
    private var loadedPassword: String? = null

    /**
     * Whether the text of every page is read in the background once the document is loaded,
     * so the first [searchDocument] doesn't wait for it. Only done for documents of at most
     * [PRELOAD_SEARCH_TEXT_MAX_PAGES] pages, larger ones are read when searched.
     * Pages needing OCR are only recognized when searched.
     */
    var preloadSearchText = true

    private val selectionHandlePaint: Paint
    private val selectionHandlePath = Path()

    /**
     * Paint object for drawing debug stuff
     */
    private val debugPaint: Paint

    /**
     * Policy for fitting pages to screen
     */
    var pageFitPolicy: FitPolicy? = FitPolicy.WIDTH
        private set

    var isFitEachPage: Boolean = false
        private set

    var isTextSelectionEnabled: Boolean = false
        private set
    private var selectionPopupEnabled = true
    private var selectionHandleColor = -0xde690d
    private var selectionHighlightColor = 0x6633B5E5
    private var selectionPopupBackgroundColor = -0xcdcdce
    private var selectionPopupTextColor = Color.WHITE
    private var selectionPopupText: CharSequence? = null
    private var selectionPage = -1
    private var selectionStart: Int = INVALID_CHAR_INDEX
    private var selectionEnd: Int = INVALID_CHAR_INDEX
    private var selectionStartHandleBounds: RectF? = null
    private var selectionEndHandleBounds: RectF? = null

    /**
     * Drawables of the selection handles, the Android text selection handles of the theme by default
     */
    private var selectionStartHandleDrawable: Drawable? = null
    private var selectionEndHandleDrawable: Drawable? = null
    private var tintSelectionHandles = true

    /**
     * Offset between the finger and the line the dragged handle points to, so the finger does not hide the text
     */
    private var handleDragOffsetX = 0f
    private var handleDragOffsetY = 0f
    private var selectionStartAnchor: PointF? = null
    private var selectionEndAnchor: PointF? = null

    /**
     * Loupe shown on long press where there is no text to select
     */
    var isMagnifierEnabled: Boolean = false
        private set

    /**
     * Whether the selection popup offers underline and strikethrough actions
     */
    var isTextMarkupEnabled: Boolean = false
        private set
    private var highlightColor = 0x80FFEB3B.toInt()
    private var underlineColor = DEFAULT_UNDERLINE_COLOR
    private var strikethroughColor = DEFAULT_STRIKETHROUGH_COLOR

    /**
     * True when markups were added since the document was loaded or last saved
     */
    @Volatile
    var hasUnsavedChanges: Boolean = false
        private set
    private val saveLock = Any()
    private var magnifierFocus: PointF? = null
    private var magnifierZoom = DEFAULT_MAGNIFIER_ZOOM
    private var magnifierWidth = 0f
    private var magnifierHeight = 0f
    private var magnifierCornerRadius = 0f
    private var magnifierVerticalOffset = 0f
    private val magnifierRect = RectF()
    private val magnifierClip = Path()
    private val magnifierShadowPaint: Paint
    private val magnifierBorderPaint: Paint
    var selectedText: String = ""
        private set
    private var selectionActionPopup: PopupWindow? = null
    private var allowSelectionActionPopupAutoShow = false
    private val popupVisibleRect = Rect()
    private val selectionPopupScrollChangedListener =
        OnScrollChangedListener { this.syncSelectionActionPopupWithViewVisibility() }
    private val selectionPopupGlobalLayoutListener = OnGlobalLayoutListener { this.syncSelectionActionPopupWithViewVisibility() }

    private var defaultPage = 0

    /**
     * True if should scroll through pages vertically instead of horizontally
     */
    var isSwipeVertical: Boolean = true
        private set

    var isSwipeEnabled: Boolean = true

    var isDoubleTapEnabled: Boolean = true
        private set

    private var nightMode = false

    var isPageSnap: Boolean = true

    /**
     * Pdfium core for loading and rendering PDFs
     */
    private val pdfiumCore: PdfiumCore?

    var scrollHandle: ScrollHandle? = null
        private set

    private var isScrollHandleInit = false

    /**
     * True if bitmap should use ARGB_8888 format and take more memory
     * False if bitmap should be compressed by using RGB_565 format and take less memory
     */
    var isBestQuality: Boolean = false
        private set

    /**
     * Thumbnail ratio (subpart of the PDF)
     * Between 0 and 1 where 1 is the best quality possible but it'll take more memory to render the PDF
     * Throw an exception if the value is 0
     */
    var thumbnailRatio: Float = Constants.THUMBNAIL_RATIO
        set(thumbnailRatio) {
            require(thumbnailRatio != 0f) { "thumbnailRatio must be greater than 0" }
            field = thumbnailRatio
        }

    /**
     * Horizontal border in pixels. This value represent how far you can scroll after an horizontal border of the PDF.
     */
    private var horizontalBorder = 0

    /**
     * Vertical border in pixels. This value represent how far you can scroll after an vertical border of the PDF.
     */
    private var verticalBorder = 0

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    var isAnnotationRendering: Boolean = false
        private set

    /**
     * True if the view should render during scaling<br></br>
     * Can not be forced on older API versions (< Build.VERSION_CODES.KITKAT) as the GestureDetector does
     * not detect scrolling while scaling.<br></br>
     * False otherwise
     */
    private var renderDuringScale = false

    /**
     * Antialiasing and bitmap filtering
     */
    var isAntialiasing: Boolean = true
        private set
    private val antialiasFilter = PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Spacing between pages, in px
     */
    var pageSeparatorSpacing: Int = 0
        private set

    /**
     * Start spacing, in px
     */
    var startSpacing: Int = 0
        private set

    /**
     * End spacing, in px
     */
    var endSpacing: Int = 0
        private set

    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */
    var isAutoSpacingEnabled: Boolean = false
        private set

    /**
     * Fling a single page at a time
     */
    var isPageFlingEnabled: Boolean = true
        private set

    /**
     * With page fling, a strong fling goes on over several pages, to the page starting nearest to
     * where a free fling would stop; when false every fling moves one page. Can change at any time.
     */
    var isPageFlingByVelocity: Boolean = false

    /**
     * Pages numbers used when calling onDrawAllListener
     */
    private val onDrawPagesNums: MutableList<Int> = ArrayList<Int>(10)

    /**
     * Holds info whether view has been added to layout and has width and height
     */
    private var hasSize = false

    /**
     * Holds last used Configurator that should be loaded when view has size
     */
    private var waitingDocumentConfigurator: Configurator? = null

    /**
     * Construct the initial view
     */
    init {
        cacheManager = CacheManager()
        animationManager = AnimationManager(this)
        dragPinchManager = DragPinchManager(this, animationManager)
        pagesLoader = PagesLoader(this)

        paint = Paint()
        textSelectionPaint = Paint()
        textSelectionPaint.style = Paint.Style.FILL
        textSelectionPaint.color = selectionHighlightColor
        searchHighlightPaint = Paint()
        searchHighlightPaint.style = Paint.Style.FILL
        searchHighlightPaint.color = DEFAULT_SEARCH_HIGHLIGHT_COLOR
        magnifierWidth = Util.getDP(context, DEFAULT_MAGNIFIER_WIDTH_DP).toFloat()
        magnifierHeight = Util.getDP(context, DEFAULT_MAGNIFIER_HEIGHT_DP).toFloat()
        magnifierCornerRadius = Util.getDP(context, DEFAULT_MAGNIFIER_CORNER_RADIUS_DP).toFloat()
        magnifierVerticalOffset = Util.getDP(context, DEFAULT_MAGNIFIER_OFFSET_DP).toFloat()
        magnifierShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        magnifierShadowPaint.style = Paint.Style.FILL
        magnifierShadowPaint.color = Color.WHITE
        magnifierShadowPaint.setShadowLayer(
            Util.getDP(context, MAGNIFIER_SHADOW_RADIUS_DP).toFloat(),
            0f,
            Util.getDP(context, MAGNIFIER_SHADOW_DY_DP).toFloat(),
            MAGNIFIER_SHADOW_COLOR
        )
        magnifierBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        magnifierBorderPaint.style = Paint.Style.STROKE
        magnifierBorderPaint.strokeWidth = Util.getDP(context, 1).toFloat()
        magnifierBorderPaint.color = DEFAULT_MAGNIFIER_BORDER_COLOR
        selectionHandlePaint = Paint()
        selectionHandlePaint.style = Paint.Style.FILL
        selectionHandlePaint.isAntiAlias = true
        selectionHandlePaint.color = selectionHandleColor
        debugPaint = Paint()
        debugPaint.style = Paint.Style.STROKE

        // PdfiumCore loads native libraries, which are unavailable in the layout editor preview
        pdfiumCore = if (isInEditMode) null else PdfiumCore(context)
        isClickable = true
        isLongClickable = true
        setWillNotDraw(false)
    }

    val pagesAsBitmaps: MutableList<Bitmap?>
        get() {
            val bitmaps = ArrayList<Bitmap?>()
            val pageParts = cacheManager.getThumbnails()
            for (i in pageParts.indices) {
                bitmaps.add(pageParts.get(i).renderedBitmap)
            }
            return bitmaps
        }

    private fun load(docSource: DocumentSource?, password: String?, userPages: IntArray? = null) {
        check(this.isRecycled) { "Don't call load on a PDF View without recycling it first." }

        this.isRecycled = false
        loadedDocSource = docSource
        loadedPassword = password
        // Start decoding document
        documentDecoder = DocumentDecoder(
            requireNotNull(docSource) { "docSource == null" },
            password,
            userPages,
            this,
            pdfiumCore,
            viewScope
        ).also { it.execute() }
    }

    override fun isShown(): Boolean {
        return state == State.SHOWN
    }

    /**
     * Go to the given page.
     * 
     * @param page Page index.
     */
    @JvmOverloads
    fun jumpTo(page: Int, withAnimation: Boolean = false) {
        var page = page
        if (pdfFile == null) {
            return
        }

        page = pdfFile!!.determineValidPageNumberFrom(page)
        val offset = -pdfFile!!.getPageOffset(page, zoom) + pageSeparatorSpacing + startSpacing
        if (this.isSwipeVertical) {
            if (withAnimation) {
                animationManager.startYAnimation(currentYOffset, offset)
            } else {
                moveTo(currentXOffset, offset, false)
            }
        } else {
            if (withAnimation) {
                animationManager.startXAnimation(currentXOffset, offset)
            } else {
                moveTo(offset, currentYOffset, false)
            }
        }
        // Set after starting: starting cancels the previous animation, which ends any earlier jump.
        isJumping = withAnimation
        showPage(page)
    }

    internal fun onScrollAnimationFinished() {
        isJumping = false
    }

    fun showPage(pageNb: Int) {
        var pageNb = pageNb
        if (this.isRecycled) {
            return
        }

        // Check the page number and makes the
        // difference between UserPages and DocumentPages
        pageNb = pdfFile!!.determineValidPageNumberFrom(pageNb)
        currentPage = pageNb

        loadPages()

        if (scrollHandle != null && !documentFitsView()) {
            scrollHandle!!.setPageNum(currentPage + 1)
        }

        callbacks.callOnPageChange(currentPage, pdfFile!!.pagesCount)
    }

    var positionOffset: Float
        /**
         * Get current position as ratio of document length to visible area.
         * 0 means that document start is visible, 1 that document end is visible
         * 
         * @return offset between 0 and 1
         */
        get() {
            val offset: Float
            if (this.isSwipeVertical) {
                offset = -currentYOffset / (scrollLength - height)
            } else {
                offset = -currentXOffset / (scrollLength - width)
            }
            return MathUtils.limit(offset, 0f, 1f)
        }
        set(progress) {
            setPositionOffset(progress, true)
        }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see PDFView.getPositionOffset
     */
    fun setPositionOffset(progress: Float, moveHandle: Boolean) {
        if (this.isSwipeVertical) {
            moveTo(currentXOffset, (-scrollLength + height) * progress, moveHandle)
        } else {
            moveTo((-scrollLength + width) * progress, currentYOffset, moveHandle)
        }
        loadPageByOffset()
    }

    fun stopFling() {
        animationManager.stopFling()
    }

    val pageCount: Int
        get() {
            if (pdfFile == null) {
                return 0
            }
            return pdfFile!!.pagesCount
        }

    /** Pages in warm, paper-like colors (a sepia tint); turns night mode off. */
    fun setSepiaMode(sepia: Boolean) {
        if (sepia) {
            nightMode = false
            sepiaMode = true
            // white paper becomes beige (#F4ECD8), black ink dark brown
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0.878f, 0f, 0f, 0f, 20f,
                0f, 0.871f, 0f, 0f, 14f,
                0f, 0f, 0.8f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f,
            )))
        } else if (sepiaMode) {
            sepiaMode = false
            paint.colorFilter = null
        }
        invalidate()
    }

    private var sepiaMode = false

    fun setNightMode(nightMode: Boolean) {
        this.nightMode = nightMode
        if (nightMode) sepiaMode = false
        if (nightMode) {
            val colorMatrixInverted =
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )

            val filter = ColorMatrixColorFilter(colorMatrixInverted)
            paint.colorFilter = filter
        } else {
            paint.colorFilter = null
        }
    }

    fun enableDoubleTap(enableDoubleTap: Boolean) {
        this.isDoubleTapEnabled = enableDoubleTap
    }

    fun onPageError(ex: PageRenderingException) {
        if (!callbacks.callOnPageError(ex.page, ex.cause)) {
            Log.e(TAG, "Cannot open page " + ex.page, ex.cause)
        }
    }

    fun recycle() {
        cancelInkStroke()
        editUndo.clear()
        editRedo.clear()
        sessionAnnotations.clear()
        // their add functions hold the old document
        placed.clear()
        sessionInk.clear()
        pendingInk.clear()
        inkRevisions.clear()
        pageWidthPoints.clear()
        pageMappings.clear()
        annotationCache.clear()
        snapshotFiles.forEach { it.delete() }
        snapshotFiles.clear()
        movedImages.clear()
        restoring = false
        isDrawingMode = false
        waitingDocumentConfigurator = null
        dismissSelectionActionPopup()

        animationManager.stopAll()
        dragPinchManager.disable()

        // Stop tasks
        pageRenderer?.stop()
        pageRenderer = null
        documentDecoder?.cancel()
        documentDecoder = null

        // Clear caches
        cacheManager.recycle()

        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle!!.destroyLayout()
        }

        // Close the index before the document, it stops reading pages
        textIndexJob?.cancel()
        textIndexJob = null
        textIndex?.close()
        textIndex = null

        if (pdfFile != null) {
            pdfFile!!.dispose()
            pdfFile = null
        }

        scrollHandle = null
        isScrollHandleInit = false
        currentYOffset = 0f
        currentXOffset = currentYOffset
        zoom = DEFAULT_MIN_SCALE
        clearTextSelectionInternal(false)
        resetSearch()
        magnifierFocus = null
        hasUnsavedChanges = false
        this.isRecycled = true
        callbacks.clear()
        state = State.DEFAULT
    }

    /**
     * Handle fling animation
     */
    override fun computeScroll() {
        super.computeScroll()
        if (isInEditMode) {
            return
        }
        animationManager.computeFling()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        callbacks.callOnAttachComplete()
        val observer = viewTreeObserver
        if (observer.isAlive) {
            observer.addOnScrollChangedListener(selectionPopupScrollChangedListener)
            observer.addOnGlobalLayoutListener(selectionPopupGlobalLayoutListener)
        }
        syncSelectionActionPopupWithViewVisibility()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncSelectionActionPopupWithViewVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncSelectionActionPopupWithViewVisibility()
    }

    override fun onDetachedFromWindow() {
        callbacks.callOnDetachComplete()
        val observer = viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnScrollChangedListener(selectionPopupScrollChangedListener)
            observer.removeOnGlobalLayoutListener(selectionPopupGlobalLayoutListener)
        }
        recycle()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        hasSize = true
        if (waitingDocumentConfigurator != null) {
            waitingDocumentConfigurator!!.load()
        }
        if (isInEditMode || state != State.SHOWN) {
            return
        }

        // calculates the position of the point which in the center of view relative to big strip
        val centerPointInStripXOffset = -currentXOffset + oldw * 0.5f
        val centerPointInStripYOffset = -currentYOffset + oldh * 0.5f

        val relativeCenterPointInStripXOffset: Float
        val relativeCenterPointInStripYOffset: Float

        if (this.isSwipeVertical) {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile!!.maxPageWidth
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile!!.getDocLen(zoom)
        } else {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile!!.getDocLen(zoom)
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile!!.maxPageHeight
        }

        animationManager.stopAll()
        pdfFile!!.recalculatePageSizes(Size(w, h))

        if (this.isSwipeVertical) {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile!!.maxPageWidth + w * 0.5f
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile!!.getDocLen(zoom) + h * 0.5f
        } else {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile!!.getDocLen(zoom) + w * 0.5f
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile!!.maxPageHeight + h * 0.5f
        }
        moveTo(currentXOffset, currentYOffset)
        loadPageByOffset()
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        if (pdfFile == null) {
            return true
        }

        if (this.isSwipeVertical) {
            if (direction < 0 && currentXOffset < 0) {
                return true
            } else if (direction > 0 && currentXOffset + toCurrentScale(pdfFile!!.maxPageWidth) > width) {
                return true
            }
        } else {
            if (direction < 0 && currentXOffset < 0) {
                return true
            } else if (direction > 0 && currentXOffset + scrollLength > width) {
                return true
            }
        }
        return false
    }

    override fun canScrollVertically(direction: Int): Boolean {
        if (pdfFile == null) {
            return true
        }

        if (this.isSwipeVertical) {
            if (direction < 0 && currentYOffset < 0) {
                return true
            } else if (direction > 0 && currentYOffset + scrollLength > height) {
                return true
            }
        } else {
            if (direction < 0 && currentYOffset < 0) {
                return true
            } else if (direction > 0 && currentYOffset + toCurrentScale(pdfFile!!.maxPageHeight) > height) {
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        if (isInEditMode) {
            return
        }

        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background
        if (this.isAntialiasing) {
            canvas.drawFilter = antialiasFilter
        }

        val bg = background
        if (bg == null) {
            canvas.drawColor(if (nightMode) Color.BLACK else if (sepiaMode) SEPIA_BACKGROUND else Color.WHITE)
        } else {
            bg.draw(canvas)
        }

        if (this.isRecycled) {
            return
        }

        if (state != State.SHOWN) {
            return
        }

        // Moves the canvas before drawing any element
        canvas.translate(currentXOffset, currentYOffset)

        // Draws thumbnails
        for (part in cacheManager.getThumbnails()) {
            drawPart(canvas, part)
        }

        // Draws parts
        for (part in cacheManager.pageParts) {
            drawPart(canvas, part)
            if (callbacks.onDrawAll != null
                && !onDrawPagesNums.contains(part.page)
            ) {
                onDrawPagesNums.add(part.page)
            }
        }

        for (page in onDrawPagesNums) {
            drawWithListener(canvas, page, callbacks.onDrawAll)
        }
        onDrawPagesNums.clear()

        drawInk(canvas)
        drawReadingHighlight(canvas)
        drawSearchHighlights(canvas)
        drawTextSelection(canvas)
        drawSelectionHandles(canvas)

        drawWithListener(canvas, currentPage, callbacks.onDraw)

        // Restores the canvas position
        canvas.translate(-currentXOffset, -currentYOffset)

        drawMagnifier(canvas)
    }

    /**
     * Draw the loupe above [magnifierFocus], redrawing the rendered parts scaled around the focus point
     */
    private fun drawMagnifier(canvas: Canvas) {
        val focus = magnifierFocus ?: return
        if (state != State.SHOWN) {
            return
        }
        val margin = magnifierCornerRadius / 2
        val left = MathUtils.limit(focus.x - magnifierWidth / 2, margin, max(margin, width - magnifierWidth - margin))
        var top = focus.y - magnifierVerticalOffset - magnifierHeight
        if (top < margin) {
            // No room above the finger, show it below
            top = focus.y + magnifierVerticalOffset
        }
        magnifierRect.set(left, top, left + magnifierWidth, top + magnifierHeight)

        magnifierShadowPaint.color = if (nightMode) Color.BLACK else Color.WHITE
        canvas.drawRoundRect(magnifierRect, magnifierCornerRadius, magnifierCornerRadius, magnifierShadowPaint)

        canvas.save()
        magnifierClip.reset()
        magnifierClip.addRoundRect(magnifierRect, magnifierCornerRadius, magnifierCornerRadius, Path.Direction.CW)
        canvas.clipPath(magnifierClip)
        canvas.translate(magnifierRect.centerX(), magnifierRect.centerY())
        canvas.scale(magnifierZoom, magnifierZoom)
        canvas.translate(-focus.x + currentXOffset, -focus.y + currentYOffset)
        for (part in cacheManager.getThumbnails()) {
            drawPart(canvas, part)
        }
        for (part in cacheManager.pageParts) {
            drawPart(canvas, part)
        }
        drawSearchHighlights(canvas)
        canvas.restore()

        canvas.drawRoundRect(magnifierRect, magnifierCornerRadius, magnifierCornerRadius, magnifierBorderPaint)
    }

    private fun drawWithListener(canvas: Canvas, page: Int, listener: OnDrawListener?) {
        if (listener != null) {
            val translateX: Float
            val translateY: Float
            if (this.isSwipeVertical) {
                translateX = 0f
                translateY = pdfFile!!.getPageOffset(page, zoom)
            } else {
                translateY = 0f
                translateX = pdfFile!!.getPageOffset(page, zoom)
            }

            canvas.translate(translateX, translateY)
            val size = pdfFile!!.getPageSize(page)
            listener.onLayerDrawn(
                canvas,
                toCurrentScale(size.width),
                toCurrentScale(size.height),
                page
            )

            canvas.translate(-translateX, -translateY)
        }
    }

    /**
     * Draw a given PagePart on the canvas
     */
    private fun drawPart(canvas: Canvas, part: PagePart) {
        // Can seem strange, but avoid lot of calls
        val pageRelativeBounds = part.pageRelativeBounds ?: return
        val renderedBitmap = part.renderedBitmap ?: return

        if (renderedBitmap.isRecycled) {
            return
        }

        // Move to the target page
        var localTranslationX = 0f
        var localTranslationY = 0f
        val size = pdfFile!!.getPageSize(part.page)

        if (this.isSwipeVertical) {
            localTranslationY = pdfFile!!.getPageOffset(part.page, zoom)
            val maxWidth = pdfFile!!.maxPageWidth
            localTranslationX = toCurrentScale(maxWidth - size.width) / 2
        } else {
            localTranslationX = pdfFile!!.getPageOffset(part.page, zoom)
            val maxHeight = pdfFile!!.maxPageHeight
            localTranslationY = toCurrentScale(maxHeight - size.height) / 2
        }
        canvas.translate(localTranslationX, localTranslationY)

        val srcRect = Rect(
            0, 0, renderedBitmap.width,
            renderedBitmap.height
        )

        val offsetX = toCurrentScale(pageRelativeBounds.left * size.width)
        val offsetY = toCurrentScale(pageRelativeBounds.top * size.height)
        val partWidth = toCurrentScale(pageRelativeBounds.width() * size.width)
        val partHeight = toCurrentScale(pageRelativeBounds.height() * size.height)

        // If we use float values for this rectangle, there will be
        // a possible gap between page parts, especially when
        // the zoom level is high.
        val dstRect = RectF(
            offsetX.toInt().toFloat(), offsetY.toInt().toFloat(),
            (offsetX + partWidth).toInt().toFloat(),
            (offsetY + partHeight).toInt().toFloat()
        )

        // Check if bitmap is in the screen
        val translationX = currentXOffset + localTranslationX
        val translationY = currentYOffset + localTranslationY
        if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 || translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
            canvas.translate(-localTranslationX, -localTranslationY)
            return
        }

        canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint)

        if (Constants.DEBUG_MODE) {
            debugPaint.color = if (part.page % 2 == 0) Color.RED else Color.BLUE
            canvas.drawRect(dstRect, debugPaint)
        }

        // Restore the canvas position
        canvas.translate(-localTranslationX, -localTranslationY)
    }

    private fun drawTextSelection(canvas: Canvas) {
        val setup = validateAndSetupSelection()
        if (setup == null) {
            return
        }

        val start = min(selectionStart, selectionEnd)
        val end = max(selectionStart, selectionEnd)

        val lineRects = buildLineSelectionRects(
            pdfFile!!,
            setup.page,
            setup.pageX,
            setup.pageY,
            setup.pageSize.width.toInt(),
            setup.pageSize.height.toInt(),
            start,
            end
        )
        for (lineRect in lineRects) {
            canvas.drawRect(lineRect, textSelectionPaint)
        }
    }

    private fun buildLineSelectionRects(
        file: PdfFile,
        page: Int,
        pageX: Int,
        pageY: Int,
        pageWidth: Int,
        pageHeight: Int,
        start: Int,
        end: Int
    ): MutableList<RectF> {
        val lineRects: MutableList<RectF> = ArrayList<RectF>()
        if (start > end || start < 0) {
            return lineRects
        }

        // Collect all mapped rectangles for each character, read and mapped in one native call each
        val mappedBoxes: MutableList<RectF> = ArrayList<RectF>()
        val charBoxes = file.getCharBoxes(page) ?: return lineRects
        val last = min(end, charBoxes.size / 4 - 1)
        if (start > last) {
            return lineRects
        }
        val mapped = file.mapRectsToDevice(page, pageX, pageY, pageWidth, pageHeight, charBoxes, start, last + 1)
            ?: return lineRects
        for (i in 0 until mapped.size / 4) {
            val mappedRect = RectF(mapped[i * 4], mapped[i * 4 + 1], mapped[i * 4 + 2], mapped[i * 4 + 3])
            mappedRect.sort()
            mappedBoxes.add(mappedRect)
        }

        if (mappedBoxes.isEmpty()) {
            return lineRects
        }

        // Sort by X to process characters in reading order
        mappedBoxes.sortBy { it.centerX() }

        // Estimer le seuil de distance Y
        val lineGapThreshold = estimateLineGapThreshold(mappedBoxes)

        // Group by line using Y overlap/proximity
        val lineGroups: MutableList<MutableList<RectF>> = ArrayList<MutableList<RectF>>()
        for (box in mappedBoxes) {
            var added = false
            for (group in lineGroups) {
                if (isOnSameLine(group, box, lineGapThreshold)) {
                    group.add(box)
                    added = true
                    break
                }
            }
            if (!added) {
                val newGroup: MutableList<RectF> = ArrayList<RectF>()
                newGroup.add(box)
                lineGroups.add(newGroup)
            }
        }

        // Create a continuous rectangle per line
        for (group in lineGroups) {
            var minLeft = Float.MAX_VALUE
            var maxRight = Float.MIN_VALUE
            var minTop = Float.MAX_VALUE
            var maxBottom = Float.MIN_VALUE

            for (box in group) {
                minLeft = min(minLeft, box.left)
                maxRight = max(maxRight, box.right)
                minTop = min(minTop, box.top)
                maxBottom = max(maxBottom, box.bottom)
            }

            lineRects.add(RectF(minLeft, minTop, maxRight, maxBottom))
        }

        return lineRects
    }

    private fun isOnSameLine(group: MutableList<RectF>, newBox: RectF, threshold: Float): Boolean {
        for (existing in group) {
            // Check Y overlap or proximity
            val gap = computeYGap(existing, newBox)
            if (gap <= threshold) {
                return true
            }
        }
        return false
    }

    private fun computeYGap(box1: RectF, box2: RectF): Float {
        // If boxes overlap in Y, no gap
        if (box1.bottom >= box2.top && box1.top <= box2.bottom) {
            return 0f
        }
        // Otherwise, distance between boxes
        if (box1.bottom < box2.top) {
            return box2.top - box1.bottom
        } else {
            return box1.top - box2.bottom
        }
    }

    private fun estimateLineGapThreshold(boxes: MutableList<RectF>): Float {
        if (boxes.size < 2) {
            return 5f
        }
        // Estimate average character height
        var totalHeight = 0f
        for (box in boxes) {
            totalHeight += box.height()
        }
        val avgHeight = totalHeight / boxes.size
        return avgHeight * 0.3f
    }

    private fun drawSelectionHandles(canvas: Canvas) {
        selectionStartHandleBounds = null
        selectionEndHandleBounds = null
        selectionStartAnchor = null
        selectionEndAnchor = null
        val setup = validateAndSetupSelection()
        if (setup == null) {
            return
        }

        val start = min(selectionStart, selectionEnd)
        val end = max(selectionStart, selectionEnd)

        val mappedStart = mapSelectionCharBox(setup, start)
        if (mappedStart != null) {
            selectionStartAnchor = PointF(mappedStart.left + currentXOffset, mappedStart.centerY() + currentYOffset)
            selectionStartHandleBounds = drawSelectionHandle(canvas, mappedStart.left, mappedStart.bottom, true)
        }

        val mappedEnd = mapSelectionCharBox(setup, end)
        if (mappedEnd != null) {
            selectionEndAnchor = PointF(mappedEnd.right + currentXOffset, mappedEnd.centerY() + currentYOffset)
            selectionEndHandleBounds = drawSelectionHandle(canvas, mappedEnd.right, mappedEnd.bottom, false)
        }
    }

    private fun mapSelectionCharBox(setup: SelectionSetup, charIndex: Int): RectF? {
        val box = pdfFile!!.getCharBox(setup.page, charIndex) ?: return null
        val mapped = pdfFile!!.mapRectToDevice(
            setup.page,
            setup.pageX,
            setup.pageY,
            setup.pageSize.width.toInt(),
            setup.pageSize.height.toInt(),
            box
        ) ?: return null
        mapped.sort()
        return mapped
    }

    /**
     * Draw a handle hanging below the text at ([x], [bottom]) in document coordinates, like Android text selection:
     * a drop whose point touches the text, up-right to the first character for the start handle, up-left to the
     * last one for the end handle. Drawables given with [setSelectionHandleDrawables] are used instead.
     *
     * @return the touch area of the handle in view coordinates
     */
    private fun drawSelectionHandle(canvas: Canvas, x: Float, bottom: Float, isStart: Boolean): RectF {
        val drawable = if (isStart) selectionStartHandleDrawable else selectionEndHandleDrawable
        val minTouchSize = Util.getDP(context, SELECTION_HANDLE_MIN_TOUCH_DP).toFloat()
        val bounds: RectF
        if (drawable != null) {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: minTouchSize.toInt()
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: minTouchSize.toInt()
            // Same hotspots as android.widget.Editor: 3/4 of the width for the start handle, 1/4 for the end one
            val hotspotX = if (isStart) width * 3 / 4 else width / 4
            val left = (x - hotspotX).toInt()
            val top = bottom.toInt()
            drawable.setBounds(left, top, left + width, top + height)
            drawable.draw(canvas)
            bounds = RectF(drawable.bounds)
        } else {
            // a circle whose corner toward the text is square: the point of the drop
            val r = Util.getDP(context, SELECTION_HANDLE_RADIUS_DP).toFloat()
            val left = if (isStart) x - 2 * r else x
            bounds = RectF(left, bottom, left + 2 * r, bottom + 2 * r)
            val radii = if (isStart) floatArrayOf(r, r, 0f, 0f, r, r, r, r) else floatArrayOf(0f, 0f, r, r, r, r, r, r)
            selectionHandlePath.reset()
            selectionHandlePath.addRoundRect(bounds, radii, Path.Direction.CW)
            canvas.drawPath(selectionHandlePath, selectionHandlePaint)
        }
        bounds.offset(currentXOffset, currentYOffset)
        // Keep a comfortable touch target even for small drawables
        val extraX = max(0f, (minTouchSize - bounds.width()) / 2)
        val extraY = max(0f, (minTouchSize - bounds.height()) / 2)
        bounds.inset(-extraX, -extraY)
        return bounds
    }

    private fun applySelectionHandleTint() {
        if (!tintSelectionHandles) {
            return
        }
        for (drawable in listOf(selectionStartHandleDrawable, selectionEndHandleDrawable)) {
            drawable?.setTint(selectionHandleColor)
        }
    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    fun loadPages() {
        val renderer = pageRenderer
        if (pdfFile == null || renderer == null) {
            return
        }

        // Drop queued tasks but allow the task currently rendering to complete for the new viewport.
        renderer.cancelPendingTasks()
        cacheManager.makeANewSet()

        pagesLoader.loadPages()
        redraw()
    }

    /**
     * Force the generation of bitmaps for all pages.
     * Implement [OnReadyForPrintingListener] to retrieve the bitmaps.
     */
    fun loadPagesForPrinting() {
        val renderer = pageRenderer
        if (pdfFile == null || renderer == null) {
            return
        }

        // Cancel all current tasks
        renderer.cancelAllTasks()
        cacheManager.makeANewSet()

        pagesLoader.loadPagesForPrinting(this.pageCount)
    }

    /**
     * Called when the PDF is loaded
     */
    fun loadComplete(pdfFile: PdfFile) {
        state = State.LOADED

        this.pdfFile = pdfFile

        // Rendering only runs while attached, detaching recycles the view
        if (!isAttachedToWindow) {
            return
        }

        pageRenderer = PageRenderer(this, viewScope).also { it.start() }
        startTextIndex(pdfFile)

        if (scrollHandle != null) {
            scrollHandle!!.setupLayout(this)
            isScrollHandleInit = true
        }

        dragPinchManager.enable()

        callbacks.callOnLoadComplete(pdfFile.pagesCount)

        jumpTo(defaultPage, false)
    }

    fun loadError(t: Throwable?) {
        state = State.ERROR
        // store reference, because callbacks will be cleared in recycle() method
        val onErrorListener = callbacks.onError
        recycle()
        invalidate()
        if (onErrorListener != null) {
            onErrorListener.onError(t)
        } else {
            Log.e("PDFView", "load pdf error", t)
        }
    }

    fun redraw() {
        invalidate()
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     * 
     * @param part The created PagePart.
     */
    fun onBitmapRendered(part: PagePart, isForPrinting: Boolean) {
        part.inkRevision = inkRevisions[part.page] ?: 0
        // when it is first rendered part
        if (state == State.LOADED) {
            state = State.SHOWN
            callbacks.callOnRender(pdfFile!!.pagesCount)
        }

        if (part.isThumbnail) {
            cacheManager.cacheThumbnail(part, isForPrinting)
            if (isForPrinting && pdfFile!!.pagesCount - 1 == part.page) {
                callbacks.callsOnReadyForPrinting(this.pagesAsBitmaps)
            }
        } else {
            cacheManager.cachePart(part)
        }
        pendingInk.removeAll { stroke ->
            cacheManager.getThumbnails().any { it.page == stroke.page && it.inkRevision >= stroke.revision } &&
                cacheManager.pageParts.none { it.page == stroke.page && it.inkRevision < stroke.revision }
        }
        redraw()
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the big strip.
     * 
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    @JvmOverloads
    fun moveTo(offsetX: Float, offsetY: Float, moveHandle: Boolean = true) {
        var offsetX = offsetX
        var offsetY = offsetY
        val offsetsChanged = offsetX != currentXOffset || offsetY != currentYOffset
        if (this.isSwipeVertical) {
            // Check X offset
            val scaledPageWidth = toCurrentScale(pdfFile!!.maxPageWidth)
            if (scaledPageWidth < width) {
                offsetX = width / 2f - scaledPageWidth / 2f
            } else {
                if (offsetX > horizontalBorder) {
                    offsetX = horizontalBorder.toFloat()
                } else if (offsetX + scaledPageWidth + horizontalBorder < width) {
                    offsetX = width - scaledPageWidth - horizontalBorder
                }
            }

            // Check Y offset
            val contentHeight = pdfFile!!.getDocLen(zoom)
            if (contentHeight < height) { // whole document height visible on screen
                offsetY = (height - contentHeight) / 2
            } else {
                val maxOffsetY = toCurrentScale(verticalBorder * 2f)
                if (offsetY > maxOffsetY) { // top visible
                    offsetY = maxOffsetY
                } else if (offsetY < this.minOffsetY) { // bottom visible
                    offsetY = this.minOffsetY
                }
            }

            if (offsetY < currentYOffset) {
                scrollDir = ScrollDir.END
            } else if (offsetY > currentYOffset) {
                scrollDir = ScrollDir.START
            } else {
                scrollDir = ScrollDir.NONE
            }
        } else {
            // Check Y offset
            val scaledPageHeight = toCurrentScale(pdfFile!!.maxPageHeight)
            if (scaledPageHeight < height) {
                offsetY = height / 2f - scaledPageHeight / 2f
            } else {
                if (offsetY > horizontalBorder) {
                    offsetY = horizontalBorder.toFloat()
                } else if (offsetY + scaledPageHeight + horizontalBorder < height) {
                    offsetY = height - scaledPageHeight - horizontalBorder
                }
            }

            // Check X offset
            val contentWidth = pdfFile!!.getDocLen(zoom)
            if (contentWidth < width) { // whole document width visible on screen
                offsetX = (width - contentWidth) / 2f
            } else {
                val maxOffsetX = toCurrentScale(horizontalBorder * 2f)
                if (offsetX > maxOffsetX) { // left visible
                    offsetX = maxOffsetX
                } else if (offsetX < this.minOffsetX) { // right visible
                    offsetX = this.minOffsetX
                }
            }

            if (offsetX < currentXOffset) {
                scrollDir = ScrollDir.END
            } else if (offsetX > currentXOffset) {
                scrollDir = ScrollDir.START
            } else {
                scrollDir = ScrollDir.NONE
            }
        }

        currentXOffset = offsetX
        currentYOffset = offsetY
        val positionOffset = this.positionOffset

        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle!!.setScroll(positionOffset)
        }

        callbacks.callOnPageScroll(this.currentPage, positionOffset)

        if (offsetsChanged) {
            syncSelectionActionPopupWithViewVisibility()
        }

        redraw()
    }

    fun loadPageByOffset() {
        if (0 == pdfFile!!.pagesCount) {
            return
        }
        if (isJumping) {
            loadPages()
            return
        }

        val offset: Float
        val viewLength: Float
        val minOffset: Float
        if (this.isSwipeVertical) {
            offset = currentYOffset
            viewLength = height.toFloat()
            minOffset = minOffsetY
        } else {
            offset = currentXOffset
            viewLength = width.toFloat()
            minOffset = minOffsetX
        }
        // The page at the screen center is the current one, but short pages at the end never reach the
        // center: over the last half screen of scrolling the focus slides to the screen end instead.
        val focus = max(viewLength / 2, viewLength - (offset - minOffset)).coerceAtMost(viewLength - 1)

        val page = pdfFile!!.getPageAtOffset(-(offset - focus), zoom)

        if (page >= 0 && page <= pdfFile!!.pagesCount - 1 && page != this.currentPage) {
            showPage(page)
        } else {
            loadPages()
        }
    }

    private val minOffsetX: Float
        get() = width - toCurrentScale(endSpacing.toFloat()) - toCurrentScale(horizontalBorder * 2f) - scrollLength

    private val minOffsetY: Float
        get() = height - toCurrentScale(endSpacing.toFloat()) - toCurrentScale(verticalBorder * 2f) - scrollLength

    /**
     * With page fling a last page shorter than the screen would stay pinned to the screen end; this
     * much room after the document lets it scroll up to the middle of the screen.
     */
    private val lastPageRoom: Float
        get() {
            val file = pdfFile ?: return 0f
            if (!isPageFlingEnabled || file.pagesCount < 2) return 0f
            val viewLength = if (isSwipeVertical) height else width
            val last = file.pagesCount - 1
            val lastPageStart = file.getPageOffset(last, zoom)
            val centered = (viewLength + file.getPageLength(last, zoom)) / 2
            return max(0f, centered - (file.getDocLen(zoom) - lastPageStart))
        }

    /** Length the document scrolls over along the swipe axis, [lastPageRoom] included. */
    private val scrollLength: Float
        get() = pdfFile!!.getDocLen(zoom) + lastPageRoom

    val documentLength: Int
        get() {
            if (this.isSwipeVertical) {
                return (height - pdfFile!!.getDocLen(zoom)).toInt()
            } else {
                return (width - pdfFile!!.getDocLen(zoom)).toInt()
            }
        }

    /**
     * Animate to the nearest snapping position for the current SnapPolicy
     */
    fun performPageSnap() {
        if (!this.isPageSnap || pdfFile == null || pdfFile!!.pagesCount == 0) {
            return
        }
        val centerPage = findFocusPage(currentXOffset, currentYOffset)
        val edge = findSnapEdge(centerPage)
        if (edge == SnapEdge.NONE) {
            return
        }

        val offset = snapOffsetForPage(centerPage, edge)
        if (this.isSwipeVertical) {
            animationManager.startYAnimation(currentYOffset, -offset)
        } else {
            animationManager.startXAnimation(currentXOffset, -offset)
        }
    }

    /**
     * Find the edge to snap to when showing the specified page
     */
    fun findSnapEdge(page: Int): SnapEdge {
        if (!this.isPageSnap || page < 0) {
            return SnapEdge.NONE
        }
        val currentOffset = if (this.isSwipeVertical) currentYOffset else currentXOffset
        val offset = -pdfFile!!.getPageOffset(page, zoom)
        val length = if (this.isSwipeVertical) height else width
        val pageLength = pdfFile!!.getPageLength(page, zoom)

        if (length >= pageLength) {
            return SnapEdge.CENTER
        } else if (currentOffset >= offset) {
            return SnapEdge.START
        } else if (offset - pageLength > currentOffset - length) {
            return SnapEdge.END
        } else {
            return SnapEdge.NONE
        }
    }

    /**
     * Get the offset to move to in order to snap to the page
     */
    fun snapOffsetForPage(pageIndex: Int, edge: SnapEdge?): Float {
        var offset = pdfFile!!.getPageOffset(pageIndex, zoom)

        val length = (if (this.isSwipeVertical) height else width).toFloat()
        val pageLength = pdfFile!!.getPageLength(pageIndex, zoom)

        if (edge == SnapEdge.CENTER) {
            offset = offset - length / 2f + pageLength / 2f
        } else if (edge == SnapEdge.END) {
            offset = offset - length + pageLength
        }
        return offset
    }

    fun findFocusPage(xOffset: Float, yOffset: Float): Int {
        val currOffset = if (this.isSwipeVertical) yOffset else xOffset
        val length = (if (this.isSwipeVertical) height else width).toFloat()
        // make sure first and last page can be found
        if (currOffset > -1) {
            return 0
        } else if (currOffset < -scrollLength + length + 1) {
            return pdfFile!!.pagesCount - 1
        }
        // else find page in center
        val center = currOffset - length / 2f
        return pdfFile!!.getPageAtOffset(-center, zoom)
    }

    /**
     * Set touch priority to the PDFView. Use this method if you use the PDFView
     * in a ViewPager, RecyclerView, etc. to avoid any problems when dragging while zoomed in.
     * 
     * @param hasPriority true if you want the PDFView to disable touch capabilities of the first parent RecyclerView
     */
    fun setTouchPriority(hasPriority: Boolean) {
        dragPinchManager.setHasTouchPriority(hasPriority)
    }

    /**
     * @return true if single page fills the entire screen in the scrolling direction
     */
    fun pageFillsScreen(): Boolean {
        val start = -pdfFile!!.getPageOffset(currentPage, zoom)
        val end = start - pdfFile!!.getPageLength(currentPage, zoom)
        if (this.isSwipeVertical) {
            return start > currentYOffset && end < currentYOffset - height
        } else {
            return start > currentXOffset && end < currentXOffset - width
        }
    }

    /**
     * Move relatively to the current position.
     * 
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see .moveTo
     */
    fun moveRelativeTo(dx: Float, dy: Float) {
        moveTo(currentXOffset + dx, currentYOffset + dy)
    }

    /**
     * Change the zoom level
     */
    fun zoomTo(zoom: Float) {
        this.zoom = zoom
    }

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     * 
     * @param zoom  The zoom level.
     * @param pivot The point on the screen that should stays.
     */
    fun zoomCenteredTo(zoom: Float, pivot: PointF) {
        val dzoom = zoom / this.zoom
        zoomTo(zoom)
        var baseX = currentXOffset * dzoom
        var baseY = currentYOffset * dzoom
        baseX += (pivot.x - pivot.x * dzoom)
        baseY += (pivot.y - pivot.y * dzoom)
        moveTo(baseX, baseY)
    }

    /**
     * @see .zoomCenteredTo
     */
    fun zoomCenteredRelativeTo(dzoom: Float, pivot: PointF) {
        zoomCenteredTo(zoom * dzoom, pivot)
    }

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     * 
     * @return true if whole document can displayed at once, false otherwise
     */
    fun documentFitsView(): Boolean {
        val len = pdfFile!!.getDocLen(1f)
        if (this.isSwipeVertical) {
            return len < height
        } else {
            return len < width
        }
    }

    fun fitToWidth(page: Int) {
        if (state != State.SHOWN) {
            Log.e(TAG, "Cannot fit, document not rendered yet")
            return
        }
        zoomTo(width / pdfFile!!.getPageSize(page).width)
        jumpTo(page)
    }

    fun getPageSize(pageIndex: Int): SizeF {
        if (pdfFile == null) {
            return SizeF(0f, 0f)
        }
        return pdfFile!!.getPageSize(pageIndex)
    }

    fun toRealScale(size: Float): Float {
        return size / zoom
    }

    fun toCurrentScale(size: Float): Float {
        return size * zoom
    }

    val isZooming: Boolean
        get() = zoom != minZoom

    private fun setDefaultPage(defaultPage: Int) {
        this.defaultPage = defaultPage
    }

    fun resetZoom() {
        zoomTo(minZoom)
    }

    fun resetZoomWithAnimation() {
        zoomWithAnimation(minZoom)
    }

    fun zoomWithAnimation(centerX: Float, centerY: Float, scale: Float) {
        animationManager.startZoomAnimation(centerX, centerY, zoom, scale)
    }

    fun zoomWithAnimation(scale: Float) {
        animationManager.startZoomAnimation((width / 2).toFloat(), (height / 2).toFloat(), zoom, scale)
    }

    /**
     * Get page number at given offset
     * 
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    fun getPageAtPositionOffset(positionOffset: Float): Int {
        return pdfFile!!.getPageAtOffset(pdfFile!!.getDocLen(zoom) * positionOffset, zoom)
    }

    fun useBestQuality(bestQuality: Boolean) {
        this.isBestQuality = bestQuality
    }

    fun setHorizontalBorder(horizontalBorderDP: Int) {
        this.horizontalBorder = Util.getDP(context, horizontalBorderDP)
    }

    fun setVerticalBorder(verticalBorderDp: Int) {
        this.verticalBorder = Util.getDP(context, verticalBorderDp)
    }

    fun enableAnnotationRendering(annotationRendering: Boolean) {
        this.isAnnotationRendering = annotationRendering
    }

    fun enableRenderDuringScale(renderDuringScale: Boolean) {
        this.renderDuringScale = renderDuringScale
    }

    fun enableAntialiasing(enableAntialiasing: Boolean) {
        this.isAntialiasing = enableAntialiasing
    }

    fun setPageFling(pageFling: Boolean) {
        this.isPageFlingEnabled = pageFling
    }

    private fun setAutoSpacing(autoSpacing: Boolean) {
        this.isAutoSpacingEnabled = autoSpacing
    }

    fun doRenderDuringScale(): Boolean {
        return renderDuringScale
    }

    val documentMeta: PdfDocument.Meta?
        /**
         * Returns null if document is not loaded
         */
        get() {
            if (pdfFile == null) {
                return null
            }
            return pdfFile!!.metaData
        }

    val tableOfContents: MutableList<PdfDocument.Bookmark?>?
        /**
         * Will be empty until document is loaded
         */
        get() {
            if (pdfFile == null) {
                return mutableListOf<PdfDocument.Bookmark?>()
            }
            return pdfFile!!.bookmarks
        }

    /**
     * Will be empty until document is loaded
     */
    fun getLinks(page: Int): MutableList<PdfDocument.Link?>? {
        if (pdfFile == null) {
            return mutableListOf<PdfDocument.Link?>()
        }
        return pdfFile!!.getPageLinks(page)
    }

    fun enableTextSelection(enabled: Boolean) {
        this.isTextSelectionEnabled = enabled
        if (!enabled) {
            clearTextSelectionInternal(true)
        }
    }

    fun setSelectionHandleColor(@ColorInt color: Int) {
        selectionHandleColor = color
        selectionHandlePaint.color = color
        applySelectionHandleTint()
        redraw()
    }

    /**
     * Use custom drawables for the selection handles. They hang below the text like Android handles:
     * the start drawable points to the first character from its top at 3/4 of its width,
     * the end drawable from its top at 1/4 of its width.
     * A null drawable draws the default drop handle for that side.
     *
     * @param tint whether to tint the drawables with the selection handle color
     */
    fun setSelectionHandleDrawables(start: Drawable?, end: Drawable?, tint: Boolean = false) {
        selectionStartHandleDrawable = start?.mutate()
        selectionEndHandleDrawable = end?.mutate()
        tintSelectionHandles = tint
        redraw()
    }

    /**
     * Called when a handle starts being dragged, so the finger keeps its distance to the text it points to
     */
    fun startSelectionHandleDrag(isStart: Boolean, x: Float, y: Float) {
        pressedWord = null
        val anchor = if (isStart) selectionStartAnchor else selectionEndAnchor
        handleDragOffsetX = if (anchor != null) anchor.x - x else 0f
        handleDragOffsetY = if (anchor != null) anchor.y - y else 0f
        // The start handle must move the lower index, whatever the drag direction was
        if (selectionStart > selectionEnd) {
            val tmp = selectionStart
            selectionStart = selectionEnd
            selectionEnd = tmp
        }
    }

    fun setSelectionHighlightColor(@ColorInt color: Int) {
        selectionHighlightColor = color
        textSelectionPaint.color = color
    }

    fun setSelectionPopupBackgroundColor(@ColorInt color: Int) {
        selectionPopupBackgroundColor = color
    }

    fun setSelectionPopupTextColor(@ColorInt color: Int) {
        selectionPopupTextColor = color
    }

    fun setSelectionPopupText(text: CharSequence?) {
        selectionPopupText = text
    }

    fun setSelectionPopupEnabled(enabled: Boolean) {
        selectionPopupEnabled = enabled
        if (!enabled) {
            dismissSelectionActionPopup()
        }
    }

    /**
     * Programmatically triggers the selection action (equivalent to tapping the popup button).
     * Does nothing if there is no active text selection.
     * Can be used when [.setSelectionPopupEnabled] is false and the host app
     * provides its own copy/paste UI.
     */
    fun copySelection() {
        if (hasTextSelection() && !selectedText.isEmpty()) {
            if (callbacks.hasSelectionActionListener()) {
                callbacks.callOnCopySelection(selectedText)
            } else {
                copySelectedTextToClipboard(selectedText)
            }
        }
    }

    fun hasTextSelection(): Boolean {
        return selectionPage >= 0 && selectionStart >= 0 && selectionEnd >= 0
    }

    fun clearTextSelection() {
        clearTextSelectionInternal(true)
    }

    /** Whether one finger draws ink instead of navigating or selecting text. */
    var isDrawingMode: Boolean = false
        private set
    private var inkColor: Int = Color.BLACK
    private var inkWidth: Float = 2f
    private data class InkStroke(val page: Int, val points: FloatArray, val normalized: List<PointF>,
        val color: Int, val width: Float, val name: String, val revision: Long)
    private val sessionInk = HashMap<String, InkStroke>()
    private val pendingInk = ArrayList<InkStroke>()
    private val inkRevisions = HashMap<Int, Long>()
    // Page widths in PDF points, cached so onDraw never waits for the pdfium lock
    private val pageWidthPoints = HashMap<Int, Int>()

    /** [toPage] maps a point of the page as shown (0..1 from its top left) to PDF coordinates, [toShown] back. */
    private class PageMapping(val toPage: Matrix, val toShown: Matrix)
    // Asked from pdfium once per page: mapping a point then takes no pdfium call, so the main
    // thread never waits for its lock, which another thread holds while it renders or saves
    private val pageMappings = HashMap<Int, PageMapping>()

    private fun pageMapping(page: Int): PageMapping? {
        pageMappings[page]?.let { return it }
        val file = pdfFile ?: return null
        val side = 16384
        val origin = file.deviceToPageCoords(page, side, side, 0, 0) ?: return null
        val right = file.deviceToPageCoords(page, side, side, side, 0) ?: return null
        val bottom = file.deviceToPageCoords(page, side, side, 0, side) ?: return null
        val toPage = Matrix()
        val toShown = Matrix()
        if (!toPage.setPolyToPoly(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), 0,
                floatArrayOf(origin.x, origin.y, right.x, right.y, bottom.x, bottom.y), 0, 3) || !toPage.invert(toShown)) return null
        return PageMapping(toPage, toShown).also { pageMappings[page] = it }
    }
    private var inkPage = -1
    private val inkPoints = ArrayList<PointF>()
    private var strokeColor = Color.BLACK
    private var strokeWidth = 2f
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Enable freehand ink, clearing selection and hiding the magnifier. Ink is saved by [saveDocument]. */
    fun setDrawingMode(enabled: Boolean) {
        cancelInkStroke()
        isDrawingMode = enabled
        if (enabled) {
            clearTextSelection()
            hideMagnifier()
            // Ink must be included in subsequent page renders, even after leaving drawing mode.
            if (!isAnnotationRendering) {
                enableAnnotationRendering(true)
                pageRenderer?.cancelAllTasks()
                for (page in 0 until pageCount) cacheManager.markPageStale(page, zoom)
                if (pdfFile != null) loadPages()
            }
        }
    }

    /** Set the color, including alpha, for subsequent strokes. */
    fun setInkColor(@ColorInt color: Int) { inkColor = color }

    /** Set the positive, finite width of subsequent strokes in PDF points (default 2). */
    fun setInkWidth(widthPt: Float) {
        require(widthPt.isFinite() && widthPt > 0f) { "Ink width must be positive and finite" }
        inkWidth = widthPt
    }

    /** Receive undo/redo availability after ink changes. */
    fun setOnInkChangeListener(listener: OnInkChangeListener?) {
        callbacks.onInkChangeListener = listener
    }

    /** Compatibility alias for [canUndoEdit]. */
    fun canUndoInk(): Boolean = canUndoEdit()

    /** Compatibility alias for [canRedoEdit]. */
    fun canRedoInk(): Boolean = canRedoEdit()

    /** Compatibility alias for [undoEdit]. */
    fun undoInk(): Boolean = undoEdit()

    /** Compatibility alias for [redoEdit]. */
    fun redoInk(): Boolean = redoEdit()

    /** [snapshot]: undoing it restores a copy of the whole document (it would also undo later form values). */
    /**
     * [area]: the part of the page (0..1 as shown) that changes, null for the whole page.
     * [restore] and [restored]: the two halves of [revert] of a snapshot, the first may run on any thread.
     */
    private data class EditRecord(val page: Int, val name: String, val apply: () -> Boolean, val revert: () -> Boolean,
                                  val snapshot: Boolean = false, val area: RectF? = null,
                                  val restore: (() -> Boolean)? = null, val restored: (() -> Unit)? = null)
    private val editUndo = ArrayList<EditRecord>()
    private val editRedo = ArrayList<EditRecord>()
    private val sessionAnnotations = HashMap<String, EditRecord>()

    private fun recordAddition(page: Int, name: String, area: RectF? = null, apply: () -> Boolean) {
        val record = EditRecord(page, name, apply, revert = { pdfFile?.removeAnnotByName(page, name) == true }, area = area)
        sessionAnnotations[name] = record
        editUndo.add(record)
        editRedo.clear()
    }

    private fun requireEditThread() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { "PDF edits require the main thread" }
    }

    /** Receive unified edit history availability on the main thread. */
    fun setOnEditChangeListener(listener: OnEditChangeListener?) {
        requireEditThread()
        callbacks.onEditChangeListener = listener
    }

    /** Whether the latest recorded edit can be undone. Call on the main thread. */
    fun canUndoEdit(): Boolean = editUndo.isNotEmpty()

    /** Whether an undone edit can be reapplied. Call on the main thread. */
    fun canRedoEdit(): Boolean = editRedo.isNotEmpty()

    /** Undo the latest recorded edit. Saving does not clear this history. */
    fun undoEdit(): Boolean {
        requireEditThread()
        if (restoring) return false
        val record = editUndo.lastOrNull() ?: return false
        if (!record.revert()) return false
        editUndo.removeAt(editUndo.lastIndex)
        editRedo.add(record)
        finishHistoryChange(record)
        return true
    }

    /** Reapply the latest undone edit. Call on the main thread. */
    fun redoEdit(): Boolean {
        requireEditThread()
        if (restoring) return false
        val record = editRedo.lastOrNull() ?: return false
        if (!record.apply()) return false
        editRedo.removeAt(editRedo.lastIndex)
        editUndo.add(record)
        finishHistoryChange(record)
        return true
    }

    /**
     * [undoEdit] for the main thread that never blocks it for long: bringing back a deleted
     * annotation of the file reloads a snapshot of the document, done here off the main
     * thread (no edit is taken meanwhile). [done] runs on the main thread.
     */
    fun undoEdit(done: (Boolean) -> Unit) {
        requireEditThread()
        val record = editUndo.lastOrNull()
        val restore = record?.restore
        val file = pdfFile
        if (record == null || restore == null || file == null || restoring) return done(undoEdit())
        restoring = true
        viewScope.launch {
            val ok = withContext(Dispatchers.IO) { restore() }
            restoring = false
            if (isRecycled || pdfFile !== file) return@launch
            if (ok) {
                record.restored?.invoke()
                editUndo.remove(record)
                editRedo.add(record)
                finishHistoryChange(record)
            }
            done(ok)
        }
    }

    // a snapshot is being loaded off the main thread: the document takes no edit until it is
    private var restoring = false

    private fun finishHistoryChange(record: EditRecord) {
        annotationCache.clear()
        cancelInkStroke()
        pendingInk.removeAll { it.name == record.name }
        val revision = (inkRevisions[record.page] ?: 0) + 1
        inkRevisions[record.page] = revision
        sessionInk[record.name]?.let { stroke ->
            if (getAnnotations(record.page).any { it.name == record.name }) {
                pendingInk.add(stroke.copy(revision = revision))
            }
        }
        inkChanged(record.page, record.area)
    }

    /**
     * The part of [page] (0..1 as shown) covered by PDF bounds [rect] (two opposite corners)
     * with [pad] points around: what an edit there needs drawn again. Null: the whole page.
     */
    private fun shownArea(page: Int, rect: RectF, pad: Float = AREA_PAD): RectF? {
        val mapping = pageMapping(page) ?: return null
        val corners = floatArrayOf(min(rect.left, rect.right) - pad, max(rect.top, rect.bottom) + pad,
            max(rect.left, rect.right) + pad, min(rect.top, rect.bottom) - pad)
        mapping.toShown.mapPoints(corners)
        return RectF(corners[0], corners[1], corners[2], corners[3]).apply { sort() }
    }

    private fun union(a: RectF?, b: RectF?): RectF? = if (a == null || b == null) null else RectF(a).apply { union(b) }

    /** Map a view point to a viewer page and PDF coordinates, or null in page gaps. Main thread only. */
    fun viewToPagePoint(viewX: Float, viewY: Float): Pair<Int, PointF>? {
        requireEditThread()
        val file = pdfFile ?: return null
        if (state != State.SHOWN || file.pagesCount == 0 || !viewX.isFinite() || !viewY.isFinite()) return null
        val page = file.getPageAtOffset(if (isSwipeVertical) viewY - currentYOffset else viewX - currentXOffset, zoom)
        val size = file.getScaledPageSize(page, zoom)
        val offset = computePageOffsets(page)
        val x = viewX - currentXOffset - offset.x
        val y = viewY - currentYOffset - offset.y
        if (size.width <= 0 || size.height <= 0 || x < 0 || y < 0 || x > size.width || y > size.height) return null
        val mapping = pageMapping(page) ?: return null
        val point = floatArrayOf(x / size.width, y / size.height)
        mapping.toPage.mapPoints(point)
        return page to PointF(point[0], point[1])
    }

    /** Where [page] is drawn, in view pixels (it may be partly off screen), or null. */
    fun pageViewRect(page: Int): RectF? {
        val file = pdfFile ?: return null
        if (state != State.SHOWN || page !in 0 until file.pagesCount) return null
        val size = file.getScaledPageSize(page, zoom)
        val offset = computePageOffsets(page)
        val left = currentXOffset + offset.x
        val top = currentYOffset + offset.y
        return RectF(left, top, left + size.width, top + size.height)
    }

    /** The page under a view point, or null in the gaps between pages. */
    fun pageAt(viewX: Float, viewY: Float): Int? {
        val file = pdfFile ?: return null
        if (state != State.SHOWN || file.pagesCount == 0) return null
        val page = file.getPageAtOffset(if (isSwipeVertical) viewY - currentYOffset else viewX - currentXOffset, zoom)
        return page.takeIf { pageViewRect(it)?.contains(viewX, viewY) == true }
    }

    /** True when [page] has no usable text to select (a scanned page): it needs OCR first. */
    fun isScannedPage(page: Int): Boolean {
        val file = pdfFile ?: return false
        if (page !in 0 until file.pagesCount) return false
        return DocumentTextIndex.needsOcr(file.getPageText(page).orEmpty())
    }

    /** Map PDF bounds (top > bottom) to normalized view bounds, respecting rotation and zoom. */
    fun pageRectToView(page: Int, pageRect: RectF): RectF? {
        requireEditThread()
        val file = pdfFile ?: return null
        if (page !in 0 until file.pagesCount) return null
        val size = file.getScaledPageSize(page, zoom)
        if (size.width <= 0 || size.height <= 0) return null
        val offset = computePageOffsets(page)
        val mapping = pageMapping(page) ?: return null
        val corners = floatArrayOf(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom)
        mapping.toShown.mapPoints(corners)
        val mapped = RectF(corners[0] * size.width, corners[1] * size.height, corners[2] * size.width, corners[3] * size.height)
        mapped.sort()
        mapped.offset(currentXOffset + offset.x, currentYOffset + offset.y)
        return mapped
    }

    /** Add multiline text at a PDF top-left point. Returns its NM name, or null on failure.
     * Uses a system Unicode font by default; call on the main thread.
     */
    @JvmOverloads
    fun addText(page: Int, pageX: Float, pageY: Float, text: String, sizePt: Float = 14f,
        @ColorInt color: Int = Color.BLACK, fontPath: String? = null): String? {
        requireEditThread()
        val file = pdfFile ?: return null
        if (restoring || text.isBlank() || !sizePt.isFinite() || sizePt <= 0 || !pageX.isFinite() || !pageY.isFinite()) return null
        val font = fontPath ?: com.reader.pdfviewer.util.SystemFonts.unicodeFor(context, text)
        val name = "pdfview-text-" + java.util.UUID.randomUUID()
        val first = file.addFreeText(page, text, font, sizePt, pageX, pageY, color, name) ?: return null
        // moved or resized: the text keeps its lines, its size follows the height of the bounds; its
        // frame is then where the text really is (a frame made wider or narrower does not stretch it)
        val origin = RectF(first)
        var landed: RectF? = null
        val place = { r: RectF ->
            val size = sizePt * (r.top - r.bottom) / (origin.top - origin.bottom)
            landed = file.addFreeText(page, text, font, size, pageX + (r.left - origin.left), pageY + (r.top - origin.top), color, name)
            landed != null
        }
        return registerPlaced(page, name, origin, AREA_PAD, place, landedAt = { landed })
    }

    /** Add an image in PDF bounds (right > left, top > bottom). Retains a private bitmap for redo. */
    fun addImage(page: Int, pageRect: RectF, bitmap: Bitmap): String? {
        requireEditThread()
        val file = pdfFile ?: return null
        if (restoring || !validImage(pageRect, bitmap)) return null
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val rect = RectF(pageRect)
        val name = "pdfview-image-" + java.util.UUID.randomUUID()
        if (!file.addImage(page, rect, copy, name)) { copy.recycle(); return null }
        return registerImage(file, page, name, rect, copy)
    }

    /**
     * [addImage] that writes the pixels into the document off the main thread (a large picture
     * takes a while). Call on the main thread; [done] runs there with the name, or null.
     */
    fun addImage(page: Int, pageRect: RectF, bitmap: Bitmap, done: (String?) -> Unit) {
        requireEditThread()
        val file = pdfFile ?: return done(null)
        if (restoring || !validImage(pageRect, bitmap)) return done(null)
        val rect = RectF(pageRect)
        val name = "pdfview-image-" + java.util.UUID.randomUUID()
        viewScope.launch {
            val copy = withContext(Dispatchers.IO) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.let { if (file.addImage(page, rect, it, name)) it else { it.recycle(); null } }
            }
            if (isRecycled || pdfFile !== file) return@launch
            done(copy?.let { registerImage(file, page, name, rect, it) })
        }
    }

    private fun validImage(rect: RectF, bitmap: Bitmap) = !bitmap.isRecycled &&
        listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } && rect.right > rect.left && rect.top > rect.bottom

    /** A picture already in the document: moving it keeps its pixels, only adding it again (redo, saving) writes them. */
    private fun registerImage(file: PdfFile, page: Int, name: String, rect: RectF, copy: Bitmap): String =
        registerPlaced(page, name, rect, AREA_PAD, { r -> file.addImage(page, r, copy, name) }, move = { r -> file.moveImage(page, name, r) })

    /** Add a sticky note whose icon's top left is at a PDF point. Returns its NM name, or null. */
    fun addNote(page: Int, pageX: Float, pageY: Float, text: String, @ColorInt color: Int = NOTE_COLOR): String? {
        requireEditThread()
        val file = pdfFile ?: return null
        val name = "pdfview-note-" + java.util.UUID.randomUUID()
        val place = { r: RectF -> file.addNote(page, r.left, r.top, color, text, name) }
        return addPlaced(page, name, RectF(pageX, pageY, pageX + 20, pageY - 20), place)
    }

    /**
     * Add a shape: [kind] SHAPE_RECT, SHAPE_ELLIPSE, SHAPE_LINE or SHAPE_ARROW. For a line or an
     * arrow [pageRect] runs from (left, top) to (right, bottom), its direction; otherwise it is
     * the bounds. [fill] 0 leaves rectangles and ellipses empty. Returns its NM name, or null.
     */
    fun addShape(page: Int, kind: Int, pageRect: RectF, @ColorInt stroke: Int, widthPt: Float, @ColorInt fill: Int = 0): String? {
        requireEditThread()
        val file = pdfFile ?: return null
        if (!widthPt.isFinite() || widthPt <= 0) return null
        val name = "pdfview-shape-" + java.util.UUID.randomUUID()
        val place = { r: RectF -> file.addShape(page, kind, floatArrayOf(r.left, r.top, r.right, r.bottom), stroke, widthPt, fill, name) }
        // an arrow head reaches past the end of its line
        return addPlaced(page, name, RectF(pageRect), place, AREA_PAD + widthPt + max(8f, widthPt * 4))
    }

    /**
     * An annotation added in this session that can be moved and resized, see [moveAnnotation]:
     * [place] adds it at bounds, [move] (when it has one) changes the bounds of the one there.
     * [pad]: how far, in points, it may draw outside its bounds.
     */
    /** [landedAt]: where the last [place] really put it, when that differs from the bounds asked for (text). */
    private class Placed(val page: Int, var bounds: RectF, val pad: Float, val place: (RectF) -> Boolean, val move: ((RectF) -> Boolean)?,
                         val landedAt: (() -> RectF?)? = null)
    private val placed = HashMap<String, Placed>()

    // Pictures whose rectangle alone was changed by a move (see PdfiumCore.moveImage): their
    // appearance is still at the old place, which pdfium fits into the rectangle but some viewers
    // (Apple's) do not. [saveDocument] writes them again at their bounds. Read on the saving thread.
    private val movedImages = java.util.concurrent.ConcurrentHashMap<String, Placed>()

    private fun addPlaced(page: Int, name: String, bounds: RectF, place: (RectF) -> Boolean, pad: Float = AREA_PAD): String? {
        if (restoring || !place(bounds)) return null
        return registerPlaced(page, name, bounds, pad, place)
    }

    /** Records an annotation that is in the document at [bounds] already. */
    private fun registerPlaced(page: Int, name: String, bounds: RectF, pad: Float, place: (RectF) -> Boolean,
                               move: ((RectF) -> Boolean)? = null, landedAt: (() -> RectF?)? = null): String {
        val item = Placed(page, RectF(bounds), pad, place, move, landedAt)
        placed[name] = item
        val area = shownArea(page, bounds, pad)
        recordAddition(page, name, area) { place(item.bounds) }
        inkChanged(page, area)
        return name
    }

    /** Whether the annotation [name] was added in this session with [addText], [addImage], [addNote] or [addShape]. */
    fun canMoveAnnotation(name: String?): Boolean = name != null && placed.containsKey(name)

    /** Bounds in PDF points of a movable annotation; lines and arrows keep their direction. */
    fun movableBounds(name: String): RectF? = placed[name]?.bounds?.let { RectF(it) }

    /** Puts a movable annotation at [bounds] (its new place and size), as one undoable edit. */
    fun moveAnnotation(name: String, bounds: RectF): Boolean {
        requireEditThread()
        val file = pdfFile ?: return false
        val item = placed[name] ?: return false
        if (restoring) return false
        val from = RectF(item.bounds)
        val to = RectF(bounds)
        fun put(r: RectF): Boolean {
            annotationCache.clear()
            item.move?.let { move -> return move(r).also { moved -> if (moved) { item.bounds = RectF(r); movedImages[name] = item } } }
            if (!file.removeAnnotByName(item.page, name)) return false
            if (item.place(r)) { item.bounds = RectF(item.landedAt?.invoke() ?: r); return true }
            item.place(item.bounds) // put it back where it was
            return false
        }
        if (!put(to)) return false
        val area = union(union(shownArea(item.page, from, item.pad), shownArea(item.page, to, item.pad)), shownArea(item.page, item.bounds, item.pad))
        editUndo.add(EditRecord(item.page, name, { put(to) }, { put(from) }, area = area))
        editRedo.clear()
        inkChanged(item.page, area)
        return true
    }

    /** The /Contents text of an annotation (a note's text), or null. */
    fun getAnnotationText(info: PdfAnnotationInfo): String? {
        requireEditThread()
        val file = pdfFile ?: return null
        return currentIndex(info)?.let { file.getAnnotContents(info.page, it) }
    }

    /** Changes the text of an annotation (a note), as one undoable edit. */
    fun setAnnotationText(info: PdfAnnotationInfo, text: String): Boolean {
        requireEditThread()
        val file = pdfFile ?: return false
        if (restoring) return false
        val before = getAnnotationText(info) ?: ""
        fun set(value: String): Boolean = currentIndex(info)?.let { file.setAnnotContents(info.page, it, value) } == true
        if (!set(text)) return false
        editUndo.add(EditRecord(info.page, info.name ?: "", { set(text) }, { set(before) }))
        editRedo.clear()
        inkChanged(info.page)
        return true
    }

    /** Index of [info] now: indexes shift when annotations before it are removed. */
    private fun currentIndex(info: PdfAnnotationInfo): Int? = getAnnotations(info.page).firstOrNull {
        if (!info.name.isNullOrEmpty()) it.name == info.name else it.index == info.index && it.subtype == info.subtype
    }?.index

    // ---- form fields ----

    /** The fields of a PDF form on [page]; empty when the document has no form. Main thread. */
    fun getFormFields(page: Int): List<com.reader.pdfviewer.pdfium.PdfFormField> {
        requireEditThread()
        return pdfFile?.getFormFields(page) ?: emptyList()
    }

    /** Whether the document has a fillable form. */
    val hasForm: Boolean get() = pdfFile?.hasForm == true

    /** The form field under a view point, or null. */
    fun findFormFieldAt(viewX: Float, viewY: Float): com.reader.pdfviewer.pdfium.PdfFormField? {
        val (page, _) = viewToPagePoint(viewX, viewY) ?: return null
        val slop = 4f * resources.displayMetrics.density
        return getFormFields(page).lastOrNull {
            val rect = pageRectToView(page, it.rect) ?: return@lastOrNull false
            rect.inset(-slop, -slop)
            rect.contains(viewX, viewY)
        }
    }

    /** Types [text] into a text field, replacing its value. Saved with [saveDocument]; not undoable. */
    fun setFormText(field: com.reader.pdfviewer.pdfium.PdfFormField, text: String): Boolean =
        formEdit(field) { pdfFile?.setFormText(field.page, field, text) == true }

    /** Toggles a check box or picks a radio button. */
    fun toggleFormField(field: com.reader.pdfviewer.pdfium.PdfFormField): Boolean =
        formEdit(field) { pdfFile?.clickFormField(field.page, field) == true }

    /** Picks option [option] of a combo box or list. */
    fun setFormChoice(field: com.reader.pdfviewer.pdfium.PdfFormField, option: Int): Boolean =
        formEdit(field) { pdfFile?.setFormChoice(field.page, field, option) == true }

    private fun formEdit(field: com.reader.pdfviewer.pdfium.PdfFormField, edit: () -> Boolean): Boolean {
        requireEditThread()
        if (restoring || field.readOnly || !edit()) return false
        // form values are not in the history; an undo that restores a copy of the document would lose them
        editUndo.removeAll { it.snapshot }
        editRedo.removeAll { it.snapshot }
        inkChanged(field.page)
        return true
    }

    // ---- reading aloud ----

    private var readingPage = -1
    private var readingRects: List<RectF> = emptyList()
    private val readingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5534A853 }

    /** Highlights [rects] (relative to the page, 0..1) of [page], the sentence being read; an empty list clears it. */
    fun setReadingHighlight(page: Int, rects: List<RectF>) {
        readingPage = if (rects.isEmpty()) -1 else page
        readingRects = rects.map { RectF(it) }
        invalidate()
    }

    private fun drawReadingHighlight(canvas: Canvas) {
        val file = pdfFile ?: return
        if (readingPage !in 0 until file.pagesCount) return
        val size = file.getScaledPageSize(readingPage, zoom)
        val offset = computePageOffsets(readingPage)
        for (r in readingRects) {
            canvas.drawRect(offset.x + r.left * size.width, offset.y + r.top * size.height,
                offset.x + r.right * size.width, offset.y + r.bottom * size.height, readingPaint)
        }
    }

    /** List annotation metadata in stacking order. Call on the main thread. */
    fun getAnnotations(page: Int): List<PdfAnnotationInfo> {
        requireEditThread()
        val file = pdfFile ?: return emptyList()
        return annotationCache.getOrPut(page) { file.getAnnotations(page) }
    }

    // Annotations of the pages as last read, dropped on every edit: a tap does not wait for pdfium
    private val annotationCache = HashMap<Int, List<PdfAnnotationInfo>>()

    /** Find the topmost annotation; small bounds get a minimum 16dp touch target. */
    fun findAnnotationAt(viewX: Float, viewY: Float): PdfAnnotationInfo? {
        val (page, _) = viewToPagePoint(viewX, viewY) ?: return null
        val slop = 8f * resources.displayMetrics.density
        return getAnnotations(page).asReversed().firstOrNull {
            val rect = pageRectToView(page, it.rect) ?: return@firstOrNull false
            rect.inset(-max(0f, slop - rect.width() / 2), -max(0f, slop - rect.height() / 2))
            rect.contains(viewX, viewY)
        }
    }

    /** Delete an annotation on the main thread. Named imported annotations use a document
     * snapshot for undo, which takes time proportional to document size: prefer the variant
     * with a callback, which makes it off the main thread.
     * Deleting an imported unnamed annotation cannot be undone and starts a new history.
     * Refresh metadata after edits: indices of unnamed annotations can change.
     */
    fun removeAnnotation(info: PdfAnnotationInfo): Boolean {
        requireEditThread()
        val file = pdfFile ?: return false
        if (restoring) return false
        val current = currentAnnotation(info) ?: return false
        return removeAnnotation(file, info, current, if (needsSnapshot(current)) takeSnapshot(file) else null)
    }

    /**
     * [removeAnnotation] whose document snapshot (see there) is made off the main thread.
     * Call on the main thread; [done] runs there with whether the annotation was deleted.
     */
    fun removeAnnotation(info: PdfAnnotationInfo, done: (Boolean) -> Unit) {
        requireEditThread()
        val file = pdfFile ?: return done(false)
        if (restoring) return done(false)
        val current = currentAnnotation(info) ?: return done(false)
        if (!needsSnapshot(current)) return done(removeAnnotation(file, info, current, null))
        val revision = editRevision
        viewScope.launch {
            val snapshot = withContext(Dispatchers.IO) { takeSnapshot(file) }
            if (isRecycled || pdfFile !== file) {
                snapshot?.delete()
                return@launch
            }
            if (editRevision != revision || restoring) {
                // edited meanwhile: the snapshot lacks that edit, take it again
                snapshot?.delete()
                removeAnnotation(info, done)
                return@launch
            }
            done(removeAnnotation(file, info, current, snapshot))
        }
    }

    private fun currentAnnotation(info: PdfAnnotationInfo): PdfAnnotationInfo? = getAnnotations(info.page).firstOrNull {
        if (!info.name.isNullOrEmpty()) it.name == info.name
        else it.index == info.index && it.subtype == info.subtype && it.rect == info.rect
    }

    /** Only a named annotation that came with the file needs a copy of the document to come back. */
    private fun needsSnapshot(current: PdfAnnotationInfo) = sessionAnnotations[current.name] == null && !current.name.isNullOrEmpty()

    // Snapshots are kept on disk: each is as large as the document, and every deletion keeps one
    private val snapshotFiles = ArrayList<File>()

    /** The document as it is now in a cache file, or null (the deletion then cannot be undone). Any thread. */
    private fun takeSnapshot(file: PdfFile): File? = try {
        file.editSnapshot()?.let { bytes ->
            val folder = File(context.cacheDir, "pdfview-undo").apply { mkdirs() }
            File(folder, java.util.UUID.randomUUID().toString()).also { it.writeBytes(bytes) }
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    private fun removeAnnotation(file: PdfFile, info: PdfAnnotationInfo, current: PdfAnnotationInfo, snapshot: File?): Boolean {
        val original = sessionAnnotations[current.name]
        val removed = if (!current.name.isNullOrEmpty()) file.removeAnnotByName(info.page, current.name)
            else file.removeAnnotAt(info.page, current.index)
        if (!removed) {
            snapshot?.delete()
            return false
        }
        val area = shownArea(info.page, current.rect)
        if (original != null) {
            editUndo.add(EditRecord(info.page, original.name, original.revert, original.apply, area = area))
        } else if (snapshot != null) {
            val name = current.name!!
            snapshotFiles.add(snapshot)
            val restore = {
                val bytes = try { snapshot.readBytes() } catch (e: java.io.IOException) { null } catch (e: OutOfMemoryError) { null }
                bytes != null && file.restoreEditSnapshot(bytes)
            }
            val restored = {
                clearTextSelection()
                pendingInk.clear()
                pageRenderer?.cancelAllTasks()
                for (page in 0 until pageCount) {
                    inkRevisions[page] = (inkRevisions[page] ?: 0) + 1
                    cacheManager.markPageStale(page, zoom)
                }
            }
            editUndo.add(EditRecord(info.page, name, { file.removeAnnotByName(info.page, name) },
                { restore().also { if (it) restored() } }, snapshot = true, restore = restore, restored = restored))
        } else {
            // An unrecorded deletion is a history boundary so an older snapshot cannot resurrect it.
            editUndo.clear()
        }
        editRedo.clear()
        pendingInk.removeAll { it.name == current.name }
        inkRevisions[info.page] = (inkRevisions[info.page] ?: 0) + 1
        inkChanged(info.page, area)
        return true
    }

    /**
     * The eraser: takes off the part of the pen strokes drawn in this session that lies within
     * [radius] view pixels of a view point, leaving the rest of each stroke as strokes of their
     * own. True when something was erased. With [sameRub] what is erased joins the erasing before
     * it as one undo step (the finger is still rubbing). Call on the main thread.
     */
    fun eraseInkAt(viewX: Float, viewY: Float, radius: Float, sameRub: Boolean = false): Boolean {
        requireEditThread()
        val file = pdfFile ?: return false
        if (restoring) return false
        val (page, center) = viewToPagePoint(viewX, viewY) ?: return false
        val mapping = pageMapping(page) ?: return false
        val widthPoints = pageWidthPoints.getOrPut(page) { file.getPageWidthPoint(page) }
        if (widthPoints <= 0) return false
        val reach = radius * widthPoints / file.getScaledPageSize(page, zoom).width
        val alive = getAnnotations(page).mapNotNullTo(HashSet()) { it.name }
        var erased = false
        for (stroke in sessionInk.values.filter { it.page == page && it.name in alive }) {
            val pieces = cutStroke(stroke.points, center.x, center.y, reach + stroke.width / 2) ?: continue
            if (!file.removeAnnotByName(page, stroke.name)) continue
            val revision = (inkRevisions[page] ?: 0) + 1
            inkRevisions[page] = revision
            val parts = pieces.map { points ->
                val shown = points.copyOf().also { mapping.toShown.mapPoints(it) }
                InkStroke(page, points, List(points.size / 2) { PointF(shown[it * 2], shown[it * 2 + 1]) },
                    stroke.color, stroke.width, "pdfview-ink-" + java.util.UUID.randomUUID(), revision)
            }
            fun add(part: InkStroke) = file.addInk(page, part.points, part.width, part.color, part.name)
            pendingInk.removeAll { it.name == stroke.name }
            for (part in parts) {
                if (!add(part)) continue
                sessionInk[part.name] = part
                sessionAnnotations[part.name] = EditRecord(page, part.name, { add(part) }, { file.removeAnnotByName(page, part.name) })
                pendingInk.add(part)
            }
            val bounds = RectF(stroke.points[0], stroke.points[1], stroke.points[0], stroke.points[1])
            for (i in stroke.points.indices step 2) bounds.union(stroke.points[i], stroke.points[i + 1])
            val area = shownArea(page, bounds, AREA_PAD + stroke.width)
            val record = EditRecord(page, stroke.name,
                apply = {
                    file.removeAnnotByName(page, stroke.name).also { if (it) parts.forEach { part -> add(part) } }
                },
                revert = {
                    parts.forEach { part -> file.removeAnnotByName(page, part.name) }
                    pendingInk.removeAll { pending -> parts.any { it.name == pending.name } }
                    add(stroke)
                }, area = area)
            val before = editUndo.lastOrNull()
            if ((sameRub || erased) && before != null && before === lastErase && before.page == page) {
                editUndo[editUndo.lastIndex] = EditRecord(page, before.name, { before.apply() && record.apply() },
                    { record.revert() && before.revert() }, area = union(before.area, area))
            } else {
                editUndo.add(record)
            }
            lastErase = editUndo.last()
            editRedo.clear()
            inkChanged(page, area)
            erased = true
        }
        return erased
    }

    // the undo step of the latest erasing, which the next one of the same rub joins
    private var lastErase: EditRecord? = null

    /**
     * What is left of the line through [points] (x, y pairs) outside the circle of radius [r]
     * around ([cx], [cy]), piece by piece; null when the circle does not touch the line.
     */
    private fun cutStroke(points: FloatArray, cx: Float, cy: Float, r: Float): List<FloatArray>? {
        val pieces = ArrayList<FloatArray>()
        // the piece being followed: it ends at the last point seen when that one is outside the circle
        var piece = ArrayList<Float>()
        var cut = false
        fun close() {
            if (piece.size >= 4) pieces.add(piece.toFloatArray())
            piece = ArrayList()
        }
        var ax = points[0]
        var ay = points[1]
        if ((ax - cx) * (ax - cx) + (ay - cy) * (ay - cy) < r * r) cut = true else { piece.add(ax); piece.add(ay) }
        for (i in 2 until points.size step 2) {
            val bx = points[i]
            val by = points[i + 1]
            val dx = bx - ax
            val dy = by - ay
            val a = dx * dx + dy * dy
            val b = 2 * ((ax - cx) * dx + (ay - cy) * dy)
            val c = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy) - r * r
            val root = b * b - 4 * a * c
            // the segment is inside the circle from t1 to t2 of its length
            val t1 = if (a > 0 && root > 0) (-b - sqrt(root)) / (2 * a) else 1f
            val t2 = if (a > 0 && root > 0) (-b + sqrt(root)) / (2 * a) else 0f
            if (t1 < 1 && t2 > 0) {
                cut = true
                if (t1 > 0) { piece.add(ax + t1 * dx); piece.add(ay + t1 * dy) }
                close()
                if (t2 < 1) { piece.add(ax + t2 * dx); piece.add(ay + t2 * dy); piece.add(bx); piece.add(by) }
            } else if (a > 0 || piece.isNotEmpty()) {
                piece.add(bx); piece.add(by)
            }
            ax = bx
            ay = by
        }
        close()
        return if (cut) pieces else null
    }

    /**
     * The annotations of every page that [keep] accepts, each with its text (a note's), read off
     * the main thread: it goes through every page. [done] runs on the main thread.
     */
    fun loadAnnotations(keep: (PdfAnnotationInfo) -> Boolean, done: (List<Pair<PdfAnnotationInfo, String?>>) -> Unit) {
        requireEditThread()
        val file = pdfFile ?: return done(emptyList())
        viewScope.launch {
            val all = withContext(Dispatchers.Default) {
                val result = ArrayList<Pair<PdfAnnotationInfo, String?>>()
                for (page in 0 until file.pagesCount) {
                    ensureActive()
                    if (isRecycled) break
                    file.readAnnotations(page).filterTo(result) { keep(it.first) }
                }
                result
            }
            if (!isRecycled && pdfFile === file) done(all)
        }
    }

    /** Grows with every edit, undo and redo: what depends on the annotations can tell they changed. */
    var editRevision = 0L
        private set

    /** [area]: the part of the page (0..1 as shown) that changed, null when all of it may have. */
    private fun inkChanged(page: Int, area: RectF? = null) {
        annotationCache.clear()
        editRevision++
        hasUnsavedChanges = true
        // parts drawn without annotations are all out of date
        val all = !isAnnotationRendering
        if (all) enableAnnotationRendering(true)
        reloadPage(page, if (all) null else area)
        invalidate()
        callbacks.onEditChangeListener?.onEditChanged(canUndoEdit(), canRedoEdit())
        callbacks.onInkChangeListener?.onInkChanged(canUndoInk(), canRedoInk())
    }

    internal fun startInkStroke(x: Float, y: Float) {
        cancelInkStroke()
        val file = pdfFile ?: return
        if (state != State.SHOWN || file.pagesCount == 0) return
        animationManager.stopAll()
        val page = file.getPageAtOffset(if (isSwipeVertical) y - currentYOffset else x - currentXOffset, zoom)
        val size = file.getScaledPageSize(page, zoom)
        val offset = computePageOffsets(page)
        val px = x - currentXOffset - offset.x
        val py = y - currentYOffset - offset.y
        if (size.width <= 0 || size.height <= 0 || px < 0 || py < 0 || px > size.width || py > size.height) return
        pageWidthPoints.getOrPut(page) { file.getPageWidthPoint(page) }
        inkPage = page
        strokeColor = inkColor
        strokeWidth = inkWidth
        addInkPoint(x, y)
    }

    internal fun addInkPoint(x: Float, y: Float) {
        if (inkPage < 0) return
        val size = pdfFile?.getScaledPageSize(inkPage, zoom) ?: return
        val offset = computePageOffsets(inkPage)
        val point = PointF(((x - currentXOffset - offset.x) / size.width).coerceIn(0f, 1f),
            ((y - currentYOffset - offset.y) / size.height).coerceIn(0f, 1f))
        val last = inkPoints.lastOrNull()
        if (last != null) {
            val dx = (point.x - last.x) * size.width
            val dy = (point.y - last.y) * size.height
            val threshold = 2f * resources.displayMetrics.density
            if (dx * dx + dy * dy < threshold * threshold) return
        }
        inkPoints.add(point)
        invalidate()
    }

    internal fun cancelInkStroke() {
        inkPage = -1
        inkPoints.clear()
        invalidate()
    }

    internal fun finishInkStroke() {
        val file = pdfFile ?: return
        val page = inkPage
        if (restoring) { cancelInkStroke(); return }
        if (page < 0 || inkPoints.isEmpty()) return
        if (inkPoints.size == 1) inkPoints.add(PointF(inkPoints[0].x, inkPoints[0].y))
        val mapping = pageMapping(page)
        if (mapping == null) { cancelInkStroke(); return }
        val points = FloatArray(inkPoints.size * 2)
        for ((index, point) in inkPoints.withIndex()) {
            points[index * 2] = point.x
            points[index * 2 + 1] = point.y
        }
        mapping.toPage.mapPoints(points)
        var bounds: RectF? = null
        for (i in points.indices step 2) bounds = (bounds ?: RectF(points[i], points[i + 1], points[i], points[i + 1])).apply { union(points[i], points[i + 1]) }
        val area = shownArea(page, bounds!!, AREA_PAD + strokeWidth)
        val stroke = InkStroke(page, points, inkPoints.toList(), strokeColor, strokeWidth,
            "pdfview-ink-" + java.util.UUID.randomUUID(), (inkRevisions[page] ?: 0) + 1)
        cancelInkStroke()
        if (!file.addInk(page, points, stroke.width, stroke.color, stroke.name)) return
        inkRevisions[page] = stroke.revision
        sessionInk[stroke.name] = stroke
        recordAddition(page, stroke.name, area) { file.addInk(page, points, stroke.width, stroke.color, stroke.name) }
        pendingInk.add(stroke)
        inkChanged(page, area)
    }

    // Canvas uses document-strip coordinates here; normalized points follow pan and zoom.
    private fun drawInk(canvas: Canvas) {
        val file = pdfFile ?: return
        for (stroke in pendingInk) {
            val size = file.getScaledPageSize(stroke.page, zoom)
            val offset = computePageOffsets(stroke.page)
            fun bounds(part: PagePart): RectF {
                val b = part.pageRelativeBounds ?: RectF(0f, 0f, 1f, 1f)
                return RectF(offset.x + b.left * size.width, offset.y + b.top * size.height,
                    offset.x + b.right * size.width, offset.y + b.bottom * size.height)
            }
            val parts = cacheManager.pageParts.filter { it.page == stroke.page }
            canvas.save()
            val thumbnail = cacheManager.getThumbnails().lastOrNull { it.page == stroke.page }
            if (thumbnail != null && thumbnail.inkRevision >= stroke.revision) {
                val uncovered = Path()
                for (part in parts) if (part.inkRevision < stroke.revision) uncovered.addRect(bounds(part), Path.Direction.CW)
                canvas.clipPath(uncovered)
            }
            for (part in parts) if (part.inkRevision >= stroke.revision) {
                @Suppress("DEPRECATION")
                canvas.clipRect(bounds(part), Region.Op.DIFFERENCE)
            }
            drawInkPath(canvas, stroke.page, stroke.normalized, stroke.color, stroke.width)
            canvas.restore()
        }
        if (inkPage >= 0) drawInkPath(canvas, inkPage, inkPoints, strokeColor, strokeWidth)
    }

    private fun drawInkPath(canvas: Canvas, page: Int, points: List<PointF>, color: Int, widthPt: Float) {
        if (points.isEmpty()) return
        val file = pdfFile ?: return
        val size = file.getScaledPageSize(page, zoom)
        val widthPoints = pageWidthPoints.getOrPut(page) { file.getPageWidthPoint(page) }
        if (widthPoints <= 0) return
        val offset = computePageOffsets(page)
        inkPaint.color = color
        inkPaint.strokeWidth = widthPt * size.width / widthPoints
        // called on every frame while a stroke is drawn: one path reused, no list copied
        val path = inkPath
        path.rewind()
        var last = points[0]
        var dot = true
        path.moveTo(offset.x + last.x * size.width, offset.y + last.y * size.height)
        for (i in 1 until points.size) {
            val point = points[i]
            if (point.x != last.x || point.y != last.y) dot = false
            path.quadTo(offset.x + last.x * size.width, offset.y + last.y * size.height,
                offset.x + (last.x + point.x) * size.width / 2, offset.y + (last.y + point.y) * size.height / 2)
            last = point
        }
        path.lineTo(offset.x + last.x * size.width, offset.y + last.y * size.height)
        if (dot) canvas.drawPoint(offset.x + last.x * size.width, offset.y + last.y * size.height, inkPaint)
        else canvas.drawPath(path, inkPaint)
    }

    private val inkPath = Path()

    /**
     * Show a loupe when long pressing where there is no text to select, it follows the finger until it is lifted
     */
    fun enableMagnifier(enabled: Boolean) {
        isMagnifierEnabled = enabled
        if (!enabled) {
            hideMagnifier()
        }
    }

    /**
     * @param zoom magnification relative to the current zoom of the document
     */
    fun setMagnifierZoom(zoom: Float) {
        magnifierZoom = max(1f, zoom)
        redraw()
    }

    /**
     * @param width loupe width in pixels
     * @param height loupe height in pixels
     */
    fun setMagnifierSize(width: Float, height: Float) {
        magnifierWidth = max(1f, width)
        magnifierHeight = max(1f, height)
        redraw()
    }

    fun setMagnifierCornerRadius(radius: Float) {
        magnifierCornerRadius = max(0f, radius)
        redraw()
    }

    /**
     * Distance in pixels between the finger and the loupe
     */
    fun setMagnifierVerticalOffset(offset: Float) {
        magnifierVerticalOffset = max(0f, offset)
        redraw()
    }

    fun setMagnifierBorderColor(@ColorInt color: Int) {
        magnifierBorderPaint.color = color
        redraw()
    }

    val isMagnifierShown: Boolean
        get() = magnifierFocus != null

    /**
     * Show the loupe or move it to ([x], [y]) in view coordinates
     *
     * @return false if the magnifier is disabled or the document is not shown
     */
    fun showMagnifier(x: Float, y: Float): Boolean {
        if (isDrawingMode || !isMagnifierEnabled || state != State.SHOWN) {
            return false
        }
        val focus = magnifierFocus
        if (focus == null) {
            magnifierFocus = PointF(x, y)
        } else {
            focus.set(x, y)
        }
        redraw()
        return true
    }

    fun hideMagnifier() {
        if (magnifierFocus != null) {
            magnifierFocus = null
            redraw()
        }
    }

    /**
     * Offer underline and strikethrough in the selection popup
     */
    fun enableTextMarkup(enabled: Boolean) {
        isTextMarkupEnabled = enabled
    }

    /** Set the color for subsequent highlights; defaults to translucent yellow. */
    fun setHighlightColor(@ColorInt color: Int) { highlightColor = color }

    /** Set the color for underline annotations. */
    fun setUnderlineColor(@ColorInt color: Int) {
        underlineColor = color
    }

    fun setStrikethroughColor(@ColorInt color: Int) {
        strikethroughColor = color
    }

    /**
     * Add a highlight, underline, or strikethrough annotation over the selected text, in the document in memory.
     * Call [saveDocument] to write it to a file.
     *
     * @param color annotation color, the configured color of [type] by default
     * @return true if the annotation was added
     */
    @JvmOverloads
    fun addTextMarkupToSelection(type: TextMarkupType, @ColorInt color: Int? = null): Boolean {
        requireEditThread()
        val file = pdfFile ?: return false
        if (restoring || !hasTextSelection()) {
            return false
        }
        val page = selectionPage
        val quads = buildMarkupQuads(file, page, min(selectionStart, selectionEnd), max(selectionStart, selectionEnd))
        if (quads.isEmpty()) {
            return false
        }
        val markupColor = color ?: when (type) {
            TextMarkupType.HIGHLIGHT -> highlightColor
            TextMarkupType.UNDERLINE -> underlineColor
            TextMarkupType.STRIKETHROUGH -> strikethroughColor
        }
        val name = "pdfview-markup-" + java.util.UUID.randomUUID()
        if (!file.addTextMarkup(page, type.annotSubtype, quads, markupColor, name)) {
            Log.e(TAG, "Cannot add $type on page $page")
            return false
        }
        recordAddition(page, name) { file.addTextMarkup(page, type.annotSubtype, quads, markupColor, name) }
        clearTextSelection()
        inkChanged(page)
        callbacks.callOnTextMarkupAdded(page, type)
        return true
    }

    /**
     * One quad per line of the characters [start]..[end], in page coordinates, as pdfium expects them
     */
    private fun buildMarkupQuads(file: PdfFile, page: Int, start: Int, end: Int): FloatArray {
        val lines = ArrayList<RectF>() // left, top, right, bottom with top > bottom (PDF space)
        for (charIndex in start..end) {
            val box = file.getCharBox(page, charIndex) ?: continue
            val left = min(box.left, box.right)
            val right = max(box.left, box.right)
            val top = max(box.top, box.bottom)
            val bottom = min(box.top, box.bottom)
            // Generated characters (line breaks) have no box
            if (right - left <= 0f || top - bottom <= 0f) {
                continue
            }
            val line = lines.lastOrNull()
            val overlap = if (line == null) 0f else min(line.top, top) - max(line.bottom, bottom)
            if (line != null && overlap >= min(line.top - line.bottom, top - bottom) / 2) {
                line.left = min(line.left, left)
                line.right = max(line.right, right)
                line.top = max(line.top, top)
                line.bottom = min(line.bottom, bottom)
            } else {
                lines.add(RectF(left, top, right, bottom))
            }
        }
        val quads = FloatArray(lines.size * 8)
        for ((i, line) in lines.withIndex()) {
            val values = floatArrayOf(
                line.left, line.top, line.right, line.top,
                line.left, line.bottom, line.right, line.bottom
            )
            values.copyInto(quads, i * 8)
        }
        return quads
    }

    /**
     * Render [page] again, after its content changed
     */
    private fun reloadPage(page: Int, area: RectF? = null) {
        pageRenderer?.cancelAllTasks()
        cacheManager.markPageStale(page, zoom, area, inkRevisions[page] ?: 0)
        loadPages()
    }

    /**
     * Write the document with the added markups to [target], through a temporary file in the same folder
     * so the target is never left half written. Blocking, call it off the main thread.
     *
     * The document stays open on the previous file content, which is still valid after the replacement.
     *
     * @return true if the file was written
     */
    fun saveDocument(target: File): Boolean {
        synchronized(saveLock) {
            val file = pdfFile ?: return false
            val folder = target.absoluteFile.parentFile ?: return false
            val temp = File(folder, ".${target.name}.saving")
            // moved pictures go in again at their bounds, so that every viewer shows them there
            val moved = movedImages.entries.toList()
            for ((name, item) in moved) {
                movedImages.remove(name, item)
                if (file.removeAnnotByName(item.page, name)) item.place(RectF(item.bounds))
            }
            if (moved.isNotEmpty()) post { annotationCache.clear() }
            val saved = try {
                file.saveAsCopy(temp.absolutePath) && temp.length() > 0 && temp.renameTo(target)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot save document", e)
                false
            }
            if (!saved) {
                temp.delete()
                return false
            }
            hasUnsavedChanges = false
            return true
        }
    }

    fun setSearchHighlightColor(@ColorInt color: Int) {
        searchHighlightPaint.color = color
        redraw()
    }

    /**
     * Corner radius of the search highlight rectangles, in pixels
     */
    fun setSearchHighlightCornerRadius(radius: Float) {
        searchHighlightCornerRadius = max(0f, radius)
        redraw()
    }

    /**
     * Whether search matches ignore letter case (default true). Changing it searches the current query again.
     */
    fun setSearchIgnoreCase(ignoreCase: Boolean) {
        if (searchIgnoreCase == ignoreCase) {
            return
        }
        searchIgnoreCase = ignoreCase
        val query = searchQuery
        resetSearch()
        searchQuery = query
        redraw()
    }

    /**
     * Highlight every occurrence of [query] (case insensitive) on the pages being displayed.
     * A null or blank query clears the highlights.
     */
    fun setSearchQuery(query: String?) {
        val newQuery = query?.takeIf { it.isNotBlank() }
        if (newQuery == searchQuery) {
            return
        }
        resetSearch()
        searchQuery = newQuery
        redraw()
    }

    fun clearSearch() {
        setSearchQuery(null)
    }

    /**
     * Find the lines of the document containing [query], page by page, on a background thread.
     * Page text is read once and cached for the next searches. With [useOcr], pages without a usable
     * text layer are recognized with OCR, which takes about a second per page the first time.
     * Stops after [maxResults] lines, long lines are cut to a snippet around the match.
     * Returns an empty list when no document is loaded or the query is blank.
     */
    suspend fun searchDocument(
        query: String,
        ignoreCase: Boolean = searchIgnoreCase,
        maxResults: Int = DEFAULT_SEARCH_MAX_RESULTS,
        useOcr: Boolean = true
    ): List<SearchResult> {
        val file = pdfFile ?: return emptyList()
        val index = textIndex ?: return emptyList()
        if (query.isBlank()) {
            return emptyList()
        }
        var recognizedPages = false
        val results = withContext(Dispatchers.Default) {
            val result = ArrayList<SearchResult>()
            try {
                pages@ for (page in 0 until file.pagesCount) {
                    ensureActive()
                    if (index.isClosed) {
                        break
                    }
                    if (useOcr && !index.isIndexed(page, true)) {
                        recognizedPages = true
                    }
                    val text = runCatching { index.pageText(page, useOcr) }
                        .onFailure { Log.e(TAG, "Cannot read text of page $page", it) }
                        .getOrNull()
                    if (text.isNullOrEmpty() || !text.contains(query, ignoreCase)) {
                        continue
                    }
                    for (line in text.lineSequence()) {
                        val matchStart = line.indexOf(query, 0, ignoreCase)
                        if (matchStart < 0) {
                            continue
                        }
                        result.add(SearchResult(page, searchSnippet(line, matchStart, query.length)))
                        if (result.size >= maxResults) {
                            break@pages
                        }
                    }
                }
            } finally {
                index.releaseSource()
            }
            result
        }
        if (recognizedPages && pdfFile === file) {
            // Highlights computed before OCR missed the recognized pages
            invalidateSearchHighlights()
        }
        return results
    }

    private fun searchSnippet(line: String, matchStart: Int, matchLength: Int): String {
        if (line.length <= SEARCH_SNIPPET_LENGTH) {
            return line.trim()
        }
        val start = (matchStart - (SEARCH_SNIPPET_LENGTH - matchLength) / 2).coerceIn(0, line.length - SEARCH_SNIPPET_LENGTH)
        val end = start + SEARCH_SNIPPET_LENGTH
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < line.length) "…" else ""
        return prefix + line.substring(start, end).trim() + suffix
    }

    /**
     * Highlight [query] and go to [page], scrolling to its first match once it is known.
     */
    fun jumpToSearchResult(page: Int, query: String) {
        if (pdfFile == null) {
            return
        }
        setSearchQuery(query)
        val validPage = pdfFile!!.determineValidPageNumberFrom(page)
        jumpTo(validPage, false)
        searchFocusPage = validPage
        if (searchHighlights.containsKey(validPage)) {
            scrollToSearchFocus(validPage)
        } else {
            requestSearchHighlights(validPage)
        }
    }

    private fun startTextIndex(file: PdfFile) {
        val docSource = loadedDocSource
        val core = pdfiumCore
        // An input stream is consumed by the first load
        val source = if (docSource != null && core != null && docSource !is InputStreamSource) {
            DetachedPageSource(file, core, docSource, context, loadedPassword)
        } else {
            DisplayedPageSource(file)
        }
        // OCR results outlive this opening only for plain files: the text of a password protected
        // document is not written to disk unencrypted.
        val ocrCache = (docSource as? FileSource)?.file?.takeIf { loadedPassword == null }
            ?.let { OcrCache(File(context.filesDir, OcrCache.DIR_NAME), it) }
        val index = runCatching { DocumentTextIndex(file, source, context.cacheDir, ocrCache) }
            .onFailure {
                Log.e(TAG, "Cannot create text index", it)
                source.close()
            }
            .getOrNull() ?: return
        textIndex = index
        if (!preloadSearchText || file.pagesCount > PRELOAD_SEARCH_TEXT_MAX_PAGES) {
            return
        }
        textIndexJob = viewScope.launch(Dispatchers.Default) {
            for (page in 0 until file.pagesCount) {
                if (index.isClosed) {
                    break
                }
                runCatching { index.pageText(page, false) }
                    .onFailure { Log.e(TAG, "Cannot index page $page", it) }
                yield()
            }
        }.also { job -> job.invokeOnCompletion { index.releaseSource() } }
    }

    private fun invalidateSearchHighlights() {
        searchGeneration++
        searchHighlights.clear()
        searchPendingPages.clear()
        redraw()
    }

    private fun resetSearch() {
        searchGeneration++
        searchQuery = null
        searchHighlights.clear()
        searchPendingPages.clear()
        searchFocusPage = -1
    }

    private fun drawSearchHighlights(canvas: Canvas) {
        val file = pdfFile ?: return
        if (searchQuery == null) {
            return
        }
        val firstPage: Int
        val lastPage: Int
        if (this.isSwipeVertical) {
            firstPage = file.getPageAtOffset(-currentYOffset, zoom)
            lastPage = file.getPageAtOffset(-currentYOffset + height, zoom)
        } else {
            firstPage = file.getPageAtOffset(-currentXOffset, zoom)
            lastPage = file.getPageAtOffset(-currentXOffset + width, zoom)
        }
        for (page in firstPage..lastPage) {
            val rects = searchHighlights[page]
            if (rects == null) {
                requestSearchHighlights(page)
                continue
            }
            if (rects.isEmpty()) {
                continue
            }
            val pageSize = file.getScaledPageSize(page, zoom)
            val offsets = computePageOffsets(page)
            for (rect in rects) {
                canvas.drawRoundRect(
                    offsets.x + rect.left * pageSize.width,
                    offsets.y + rect.top * pageSize.height,
                    offsets.x + rect.right * pageSize.width,
                    offsets.y + rect.bottom * pageSize.height,
                    searchHighlightCornerRadius,
                    searchHighlightCornerRadius,
                    searchHighlightPaint
                )
            }
        }
    }

    /**
     * Find the matches of the current query on [page] in the background, pdfium calls are too slow for onDraw.
     */
    private fun requestSearchHighlights(page: Int) {
        val file = pdfFile ?: return
        val query = searchQuery ?: return
        if (searchHighlights.containsKey(page) || !searchPendingPages.add(page)) {
            return
        }
        val generation = searchGeneration
        val ignoreCase = searchIgnoreCase
        viewScope.launch {
            val rects = runCatching {
                withContext(Dispatchers.Default) { findSearchRects(file, page, query, ignoreCase) }
            }.onFailure {
                Log.e(TAG, "Cannot search page $page", it)
            }.getOrDefault(emptyList())
            if (generation != searchGeneration || isRecycled || pdfFile !== file) {
                return@launch
            }
            searchPendingPages.remove(page)
            searchHighlights[page] = rects
            if (page == searchFocusPage) {
                scrollToSearchFocus(page)
            }
            redraw()
        }
    }

    private fun findSearchRects(file: PdfFile, page: Int, query: String, ignoreCase: Boolean): List<RectF> {
        textIndex?.ocrLines(page)?.let { return findOcrSearchRects(it, query, ignoreCase) }
        val text = file.getPageText(page)
        if (text.isNullOrEmpty()) {
            return emptyList()
        }
        val pageSize = file.getPageSize(page)
        if (pageSize.width <= 0f || pageSize.height <= 0f) {
            return emptyList()
        }
        // Map at a fixed large size then normalize, so the rects follow any zoom level
        val mappingWidth = SEARCH_MAPPING_WIDTH
        val mappingHeight = SEARCH_MAPPING_WIDTH * pageSize.height / pageSize.width
        val result = ArrayList<RectF>()
        // indexOf with ignoreCase compares char by char, so match indexes stay pdfium char indexes
        var start = text.indexOf(query, 0, ignoreCase)
        while (start >= 0) {
            val lineRects = buildLineSelectionRects(
                file,
                page,
                0,
                0,
                mappingWidth.toInt(),
                mappingHeight.toInt(),
                start,
                start + query.length - 1
            )
            for (rect in lineRects) {
                result.add(
                    RectF(
                        rect.left / mappingWidth.toInt(),
                        rect.top / mappingHeight.toInt(),
                        rect.right / mappingWidth.toInt(),
                        rect.bottom / mappingHeight.toInt()
                    )
                )
            }
            start = text.indexOf(query, start + query.length, ignoreCase)
        }
        return result
    }

    /**
     * Match rects on a page recognized by OCR, the part of a word covered by the match is estimated
     * from its share of the word characters.
     */
    private fun findOcrSearchRects(lines: List<OcrLine>, query: String, ignoreCase: Boolean): List<RectF> {
        val result = ArrayList<RectF>()
        for (line in lines) {
            var start = line.text.indexOf(query, 0, ignoreCase)
            while (start >= 0) {
                val end = start + query.length
                var rect: RectF? = null
                for (word in line.words) {
                    if (word.end <= start || word.start >= end) {
                        continue
                    }
                    val box = word.box
                    val length = (word.end - word.start).toFloat()
                    val left = box.left + box.width() * (max(start, word.start) - word.start) / length
                    val right = box.left + box.width() * (min(end, word.end) - word.start) / length
                    val part = RectF(left, box.top, right, box.bottom)
                    if (rect == null) rect = part else rect.union(part)
                }
                rect?.let { result.add(it) }
                start = line.text.indexOf(query, end, ignoreCase)
            }
        }
        return result
    }

    /**
     * Bring the first match of [page] into view, a third from the top of the screen.
     */
    private fun scrollToSearchFocus(page: Int) {
        searchFocusPage = -1
        val file = pdfFile ?: return
        val rect = searchHighlights[page]?.firstOrNull() ?: return
        val pageSize = file.getScaledPageSize(page, zoom)
        val offsets = computePageOffsets(page)
        if (this.isSwipeVertical) {
            val matchY = offsets.y + rect.top * pageSize.height
            moveTo(currentXOffset, -(matchY - height / 3f))
        } else {
            val matchX = offsets.x + rect.left * pageSize.width
            moveTo(-(matchX - width / 3f), currentYOffset)
        }
        loadPageByOffset()
    }

    fun isStartHandleTouched(x: Float, y: Float): Boolean {
        return selectionStartHandleBounds != null && selectionStartHandleBounds!!.contains(x, y)
    }

    fun isEndHandleTouched(x: Float, y: Float): Boolean {
        return selectionEndHandleBounds != null && selectionEndHandleBounds!!.contains(x, y)
    }

    fun extendSelectionFromStart(x: Float, y: Float) {
        extendSelection(x, y, true)
    }

    fun extendSelectionFromEnd(x: Float, y: Float) {
        extendSelection(x, y, false)
    }

    private fun extendSelection(x: Float, y: Float, fromStartHandle: Boolean) {
        if (!hasTextSelection()) {
            return
        }
        val hit = getSelectionHit(x + handleDragOffsetX, y + handleDragOffsetY)
        if (hit == null || hit.page != selectionPage) {
            return
        }
        val currentIndex = if (fromStartHandle) selectionStart else selectionEnd
        if (currentIndex != hit.charIndex) {
            if (fromStartHandle) {
                selectionStart = hit.charIndex
            } else {
                selectionEnd = hit.charIndex
            }
            selectedText = computeSelectedText()
            updateSelectionActionPopupPosition()
            redraw()
            return
        }
        updateSelectionActionPopupPosition()
    }

    fun startTextSelection(x: Float, y: Float): Boolean {
        if (isDrawingMode || !this.isTextSelectionEnabled || pdfFile == null || state != State.SHOWN) {
            return false
        }

        allowSelectionActionPopupAutoShow = false
        dismissSelectionActionPopup()

        val hit = getSelectionHit(x, y)
        if (hit == null) {
            return false
        }

        selectionPage = hit.page
        // a long press selects the word under the finger, not one letter
        val word = wordAt(hit.page, hit.charIndex)
        selectionStart = word.first
        selectionEnd = word.last
        pressedWord = word
        selectedText = computeSelectedText()
        callbacks.callOnSelectionChanged(true)
        redraw()
        return true
    }

    // the word a long press selected: dragging on from it keeps it selected, whichever way
    private var pressedWord: IntRange? = null

    /** Char indexes of the word holding [charIndex] on [page] (letters, digits and their marks); the char alone when it is not in a word. */
    private fun wordAt(page: Int, charIndex: Int): IntRange {
        val text = pdfFile?.getPageText(page).orEmpty()
        fun inWord(i: Int) = i in text.indices && text[i].let { c ->
            c.isLetterOrDigit() || Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }
        }
        if (!inWord(charIndex)) return charIndex..charIndex
        var a = charIndex; var b = charIndex
        while (inWord(a - 1)) a--
        while (inWord(b + 1)) b++
        return a..b
    }

    fun updateTextSelection(x: Float, y: Float): Boolean {
        if (!hasTextSelection()) {
            return false
        }

        val hit = getSelectionHit(x, y)
        if (hit == null || hit.page != selectionPage) {
            return false
        }

        // from a pressed word, the selection keeps the whole word on the side it starts from
        pressedWord?.let { w -> selectionStart = if (hit.charIndex < w.first) w.last else w.first }
        if (selectionEnd != hit.charIndex) {
            selectionEnd = hit.charIndex
            selectedText = computeSelectedText()
            updateSelectionActionPopupPosition()
            redraw()
        }
        return true
    }

    fun finishTextSelection() {
        pressedWord = null
        if (hasTextSelection()) {
            selectedText = computeSelectedText()
            if (selectionPopupEnabled) {
                allowSelectionActionPopupAutoShow = true
                showSelectionActionPopup()
            }
        }
    }

    private fun clearTextSelectionInternal(redraw: Boolean) {
        pressedWord = null
        allowSelectionActionPopupAutoShow = false
        dismissSelectionActionPopup()
        val hadSelection = hasTextSelection()
        selectionPage = -1
        selectionStart = INVALID_CHAR_INDEX
        selectionEnd = INVALID_CHAR_INDEX
        selectionStartHandleBounds = null
        selectionEndHandleBounds = null
        selectedText = ""
        if (hadSelection) {
            callbacks.callOnSelectionChanged(false)
        }
        if (redraw) {
            redraw()
        }
    }

    private fun computeSelectedText(): String {
        if (!hasTextSelection() || pdfFile == null) {
            return ""
        }
        val start = min(selectionStart, selectionEnd)
        val end = max(selectionStart, selectionEnd)
        return pdfFile!!.getPageText(selectionPage, start, end - start + 1).orEmpty()
    }

    private fun showSelectionActionPopup() {
        dismissSelectionActionPopup()
        if (!hasTextSelection() || selectedText.isEmpty() || windowToken == null) {
            return
        }

        val anchorBounds = this.selectionPopupAnchorBounds
        if (anchorBounds == null || !isSelectionAnchorVisibleOnScreen(anchorBounds)) {
            return
        }

        val actionView = createSelectionActionView()
        val popupWindow = PopupWindow(actionView, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, false)
        popupWindow.isTouchable = true
        popupWindow.isOutsideTouchable = false
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.elevation = Util.getDP(context, SELECTION_POPUP_ELEVATION_DP).toFloat()
        }

        actionView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = actionView.measuredWidth
        val popupHeight = actionView.measuredHeight
        val popupLocation = computeSelectionPopupLocation(anchorBounds, popupWidth, popupHeight)
        if (popupLocation == null) {
            return
        }

        selectionActionPopup = popupWindow
        popupWindow.setOnDismissListener(PopupWindow.OnDismissListener {
            if (selectionActionPopup === popupWindow) {
                selectionActionPopup = null
            }
        })
        popupWindow.showAtLocation(this, Gravity.NO_GRAVITY, popupLocation[0], popupLocation[1])
    }

    /**
     * Default copy action when the host app does not set an [OnSelectionActionListener]
     */
    private fun copySelectedTextToClipboard(text: String) {
        if (text.isEmpty()) {
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
    }

    private fun dismissSelectionActionPopup() {
        if (selectionActionPopup != null) {
            selectionActionPopup!!.dismiss()
            selectionActionPopup = null
        }
    }

    private fun createSelectionActionView(): View {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.HORIZONTAL
        val background = GradientDrawable()
        background.setColor(selectionPopupBackgroundColor)
        background.cornerRadius = Util.getDP(context, SELECTION_POPUP_CORNER_RADIUS_DP).toFloat()
        container.background = background

        val copyText = selectionPopupText ?: context.getString(R.string.pdfview_selection_copy_action)
        container.addView(createSelectionActionButton(copyText) {
            if (callbacks.hasSelectionActionListener()) {
                callbacks.callOnCopySelection(selectedText)
            } else {
                copySelectedTextToClipboard(selectedText)
            }
        })
        if (isTextMarkupEnabled) {
            container.addView(createSelectionActionButton(context.getString(R.string.pdfview_selection_underline_action)) {
                addTextMarkupToSelection(TextMarkupType.UNDERLINE)
            })
            container.addView(createSelectionActionButton(context.getString(R.string.pdfview_selection_strikethrough_action)) {
                addTextMarkupToSelection(TextMarkupType.STRIKETHROUGH)
            })
        }
        return container
    }

    private fun createSelectionActionButton(text: CharSequence, action: () -> Unit): TextView {
        val button = TextView(context)
        button.text = text
        button.setTextColor(selectionPopupTextColor)
        button.textSize = SELECTION_POPUP_TEXT_SIZE_SP
        val horizontalPadding = Util.getDP(context, SELECTION_POPUP_HORIZONTAL_PADDING_DP)
        val verticalPadding = Util.getDP(context, SELECTION_POPUP_VERTICAL_PADDING_DP)
        button.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        button.setOnClickListener {
            allowSelectionActionPopupAutoShow = false
            dismissSelectionActionPopup()
            action()
        }
        return button
    }

    private fun updateSelectionActionPopupPosition() {
        if (selectionActionPopup == null || !selectionActionPopup!!.isShowing) {
            return
        }
        if (!hasTextSelection() || selectedText.isEmpty()) {
            dismissSelectionActionPopup()
            return
        }
        val anchorBounds = this.selectionPopupAnchorBounds
        if (anchorBounds == null || !isSelectionAnchorVisibleOnScreen(anchorBounds)) {
            dismissSelectionActionPopup()
            return
        }
        val contentView = selectionActionPopup!!.contentView
        if (contentView == null) {
            dismissSelectionActionPopup()
            return
        }
        contentView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = contentView.measuredWidth
        val popupHeight = contentView.measuredHeight
        val popupLocation = computeSelectionPopupLocation(anchorBounds, popupWidth, popupHeight)
        if (popupLocation == null) {
            dismissSelectionActionPopup()
            return
        }
        selectionActionPopup!!.update(popupLocation[0], popupLocation[1], -1, -1)
    }

    private fun syncSelectionActionPopupWithViewVisibility() {
        if (!hasTextSelection() || selectedText.isEmpty() || !callbacks.hasSelectionActionListener()) {
            allowSelectionActionPopupAutoShow = false
            dismissSelectionActionPopup()
            return
        }

        if (!isShown || !getGlobalVisibleRect(popupVisibleRect)) {
            dismissSelectionActionPopup()
            return
        }

        val anchorBounds = this.selectionPopupAnchorBounds
        if (anchorBounds == null || !isSelectionAnchorVisibleOnScreen(anchorBounds)) {
            dismissSelectionActionPopup()
            return
        }

        if (selectionActionPopup == null || !selectionActionPopup!!.isShowing) {
            if (allowSelectionActionPopupAutoShow) {
                showSelectionActionPopup()
            }
            return
        }

        updateSelectionActionPopupPosition()
    }

    private fun computeSelectionPopupLocation(anchorBounds: RectF, popupWidth: Int, popupHeight: Int): IntArray {
        val margin = Util.getDP(context, SELECTION_POPUP_MARGIN_DP)
        val location = IntArray(2)
        getLocationOnScreen(location)
        val anchorCenterX = anchorBounds.centerX()
        val desiredX = location[0] + Math.round(anchorCenterX - popupWidth / 2f)
        val desiredY = location[1] + Math.round(anchorBounds.top - popupHeight - margin)

        val minX = location[0] + margin
        val maxX = location[0] + width - popupWidth - margin
        val minY = location[1] + margin
        val maxY = location[1] + height - popupHeight - margin

        val popupX: Int
        if (maxX < minX) {
            popupX = location[0] + Math.round((width - popupWidth) / 2f)
        } else {
            popupX = max(minX, min(desiredX, maxX))
        }

        val popupY: Int
        if (maxY < minY) {
            popupY = location[1] + Math.round((height - popupHeight) / 2f)
        } else {
            popupY = max(minY, min(desiredY, maxY))
        }

        return intArrayOf(popupX, popupY)
    }

    private fun isSelectionAnchorVisibleOnScreen(anchorBounds: RectF?): Boolean {
        if (anchorBounds == null || !getGlobalVisibleRect(popupVisibleRect)) {
            return false
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        val anchorOnScreen = RectF(anchorBounds)
        anchorOnScreen.offset(location[0].toFloat(), location[1].toFloat())
        val visibleRect = RectF(popupVisibleRect)
        return RectF.intersects(anchorOnScreen, visibleRect)
    }

    private val selectionPopupAnchorBounds: RectF?
        get() {
            if (!hasTextSelection() || pdfFile == null) {
                return null
            }

            val page = selectionPage
            if (page < 0 || page >= pdfFile!!.pagesCount) {
                return null
            }

            val pageSize = pdfFile!!.getScaledPageSize(page, zoom)
            val pageX: Int
            val pageY: Int
            if (this.isSwipeVertical) {
                pageX = pdfFile!!.getSecondaryPageOffset(page, zoom).toInt()
                pageY = pdfFile!!.getPageOffset(page, zoom).toInt()
            } else {
                pageY = pdfFile!!.getSecondaryPageOffset(page, zoom).toInt()
                pageX = pdfFile!!.getPageOffset(page, zoom).toInt()
            }

            val start = min(selectionStart, selectionEnd)
            val end = max(selectionStart, selectionEnd)
            val lineRects = buildLineSelectionRects(
                pdfFile!!,
                page,
                pageX,
                pageY,
                pageSize.width.toInt(),
                pageSize.height.toInt(),
                start,
                end
            )
            if (lineRects.isEmpty()) {
                return null
            }

            val anchor = RectF(lineRects.get(0))
            for (i in 1..<lineRects.size) {
                anchor.union(lineRects.get(i))
            }
            return RectF(
                anchor.left + currentXOffset,
                anchor.top + currentYOffset,
                anchor.right + currentXOffset,
                anchor.bottom + currentYOffset
            )
        }

    private fun getSelectionHit(x: Float, y: Float): SelectionHit? {
        if (pdfFile == null) {
            return null
        }

        val mappedX = -currentXOffset + x
        val mappedY = -currentYOffset + y
        val page = pdfFile!!.getPageAtOffset(if (this.isSwipeVertical) mappedY else mappedX, zoom)
        if (page < 0 || page >= pdfFile!!.pagesCount) {
            return null
        }

        val scaledPageSize = pdfFile!!.getScaledPageSize(page, zoom)
        if (scaledPageSize.width <= 0 || scaledPageSize.height <= 0) {
            return null
        }
        val offsets = computePageOffsets(page)
        val pageLeft = offsets.x.toFloat()
        val pageTop = offsets.y.toFloat()

        val pageX = pageLeft.toInt()
        val pageY = pageTop.toInt()
        val charIndexFromDevice = findCharIndexFromDeviceBoxes(page, mappedX, mappedY, pageX, pageY, scaledPageSize)
        if (charIndexFromDevice >= 0) {
            return SelectionHit(page, charIndexFromDevice)
        }

        val localX = mappedX - pageLeft
        val localY = mappedY - pageTop
        if (localX < 0 || localY < 0 || localX > scaledPageSize.width || localY > scaledPageSize.height) {
            return null
        }

        val originalPageSize = pdfFile!!.getOriginalPageSize(page) ?: return null
        if (originalPageSize.width <= 0 || originalPageSize.height <= 0) {
            return null
        }

        val pageCoordX = ((localX / scaledPageSize.width) * originalPageSize.width).toDouble()
        val pageCoordY = ((localY / scaledPageSize.height) * originalPageSize.height).toDouble()
        val toleranceX = max(1.0, (originalPageSize.width / scaledPageSize.width).toDouble())
        val toleranceY = max(1.0, (originalPageSize.height / scaledPageSize.height).toDouble())
        var charIndex = pdfFile!!.getCharIndexAtCoord(page, pageCoordX, pageCoordY, toleranceX, toleranceY)
        if (charIndex < 0) {
            val invertedPageCoordY = originalPageSize.height - pageCoordY
            charIndex = pdfFile!!.getCharIndexAtCoord(page, pageCoordX, invertedPageCoordY, toleranceX, toleranceY)
            if (charIndex < 0) {
                charIndex = findCharIndexFromBoxes(page, pageCoordX.toFloat(), pageCoordY.toFloat(), originalPageSize.height)
            }
        }
        if (charIndex < 0) {
            return null
        }

        return SelectionHit(page, charIndex)
    }

    private fun findCharIndexFromDeviceBoxes(
        page: Int,
        mappedX: Float,
        mappedY: Float,
        pageX: Int,
        pageY: Int,
        pageSize: SizeF
    ): Int {
        val charBoxes = pdfFile!!.getCharBoxes(page) ?: return INVALID_CHAR_INDEX
        val textCount = charBoxes.size / 4
        if (textCount <= 0) {
            return INVALID_CHAR_INDEX
        }
        // All boxes mapped in one native call instead of three calls per character
        val mapped = pdfFile!!.mapRectsToDevice(
            page, pageX, pageY, pageSize.width.toInt(), pageSize.height.toInt(), charBoxes
        ) ?: return INVALID_CHAR_INDEX

        var bestIndex: Int = INVALID_CHAR_INDEX
        var bestDistance = Float.MAX_VALUE
        val mappedRect = RectF()
        for (i in 0..<textCount) {
            mappedRect.set(mapped[i * 4], mapped[i * 4 + 1], mapped[i * 4 + 2], mapped[i * 4 + 3])
            mappedRect.sort()
            if (mappedRect.contains(mappedX, mappedY)) {
                return i
            }
            val centerX = (mappedRect.left + mappedRect.right) / 2f
            val centerY = (mappedRect.top + mappedRect.bottom) / 2f
            val dx = centerX - mappedX
            val dy = centerY - mappedY
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }

        if (bestDistance > MAX_FALLBACK_DEVICE_DISTANCE_SQ) {
            return INVALID_CHAR_INDEX
        }
        return bestIndex
    }

    private fun findCharIndexFromBoxes(page: Int, pageCoordX: Float, pageCoordY: Float, pageHeight: Int): Int {
        val charBoxes = pdfFile!!.getCharBoxes(page) ?: return INVALID_CHAR_INDEX
        val textCount = charBoxes.size / 4
        if (textCount <= 0) {
            return INVALID_CHAR_INDEX
        }

        var bestIndex: Int = INVALID_CHAR_INDEX
        var bestDistance = Float.MAX_VALUE
        val candidateY = floatArrayOf(pageCoordY, pageHeight - pageCoordY)
        val charBox = RectF()
        for (y in candidateY) {
            for (i in 0..<textCount) {
                charBox.set(charBoxes[i * 4], charBoxes[i * 4 + 1], charBoxes[i * 4 + 2], charBoxes[i * 4 + 3])
                charBox.sort()
                if (charBox.contains(pageCoordX, y)) {
                    return i
                }
                val centerX = (charBox.left + charBox.right) / 2f
                val centerY = (charBox.top + charBox.bottom) / 2f
                val dx = centerX - pageCoordX
                val dy = centerY - y
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = i
                }
            }
        }
        if (bestDistance > MAX_FALLBACK_CHAR_DISTANCE_SQ) {
            return INVALID_CHAR_INDEX
        }
        return bestIndex
    }

    private class PageOffsets(val x: Int, val y: Int)

    private fun computePageOffsets(page: Int): PageOffsets {
        val pageX: Int
        val pageY: Int
        if (this.isSwipeVertical) {
            pageX = pdfFile!!.getSecondaryPageOffset(page, zoom).toInt()
            pageY = pdfFile!!.getPageOffset(page, zoom).toInt()
        } else {
            pageY = pdfFile!!.getSecondaryPageOffset(page, zoom).toInt()
            pageX = pdfFile!!.getPageOffset(page, zoom).toInt()
        }
        return PageOffsets(pageX, pageY)
    }

    private class SelectionSetup(val page: Int, val pageSize: SizeF, val pageX: Int, val pageY: Int)

    private fun validateAndSetupSelection(): SelectionSetup? {
        if (!hasTextSelection() || pdfFile == null) {
            return null
        }

        val page = selectionPage
        if (page < 0 || page >= pdfFile!!.pagesCount) {
            return null
        }

        val pageSize = pdfFile!!.getScaledPageSize(page, zoom)
        val offsets = computePageOffsets(page)
        return SelectionSetup(page, pageSize, offsets.x, offsets.y)
    }

    private class SelectionHit(val page: Int, val charIndex: Int)

    /**
     * Use an asset file as the pdf source
     */
    fun fromAsset(assetName: String): Configurator {
        return Configurator(AssetSource(assetName))
    }

    /**
     * Use a file as the pdf source
     */
    fun fromFile(file: File?): Configurator {
        return Configurator(FileSource(file))
    }

    /**
     * Use URI as the pdf source, for use with content providers
     */
    fun fromUri(uri: Uri): Configurator {
        return Configurator(UriSource(uri))
    }

    /**
     * Use bytearray as the pdf source, documents is not saved
     */
    fun fromBytes(bytes: ByteArray?): Configurator {
        return Configurator(ByteArraySource(bytes))
    }

    /**
     * Use stream as the pdf source. Stream will be written to bytearray, because native code does not support Java Streams
     */
    fun fromStream(stream: InputStream): Configurator {
        return Configurator(InputStreamSource(stream))
    }

    /**
     * Use custom source as pdf source
     */
    fun fromSource(docSource: DocumentSource?): Configurator {
        return Configurator(docSource)
    }

    private enum class State {
        DEFAULT, LOADED, SHOWN, ERROR
    }

    inner class Configurator internal constructor(private val documentSource: DocumentSource?) {
        private var drawingMode = false
        private var inkColor = Color.BLACK
        private var inkWidth = 2f
        private var onEditChangeListener: OnEditChangeListener? = null
        private var onInkChangeListener: OnInkChangeListener? = null

        /** Enable one-finger drawing and annotation rendering. */
        fun setDrawingMode(enabled: Boolean): Configurator { drawingMode = enabled; return this }

        /** Set the ink color, including alpha. */
        fun setInkColor(@ColorInt color: Int): Configurator { inkColor = color; return this }

        /** Set the ink width in PDF points. */
        fun setInkWidth(widthPt: Float): Configurator {
            require(widthPt.isFinite() && widthPt > 0f) { "Ink width must be positive and finite" }
            inkWidth = widthPt
            return this
        }

        /** Receive availability of undo and redo for all annotation edits. */
        fun onEditChange(listener: OnEditChangeListener?): Configurator { onEditChangeListener = listener; return this }

        /** Compatibility listener for unified undo/redo availability. */
        fun onInkChange(listener: OnInkChangeListener?): Configurator { onInkChangeListener = listener; return this }

        private var pageNumbers: IntArray? = null

        private var enableSwipe = true

        private var enableDoubletap = true

        private var onDrawListener: OnDrawListener? = null

        private var onDrawAllListener: OnDrawListener? = null

        private var onReadyForPrintingListener: OnReadyForPrintingListener? = null
        private var onLoadCompleteListener: OnLoadCompleteListener? = null
        private var onAttachCompleteListener: OnAttachCompleteListener? = null
        private var onDetachCompleteListener: OnDetachCompleteListener? = null

        private var onErrorListener: OnErrorListener? = null

        private var onPageChangeListener: OnPageChangeListener? = null

        private var onPageScrollListener: OnPageScrollListener? = null

        private var onRenderListener: OnRenderListener? = null

        private var onTapListener: OnTapListener? = null

        private var onLongPressListener: OnLongPressListener? = null

        private var onPageErrorListener: OnPageErrorListener? = null

        private var linkHandler: LinkHandler? = DefaultLinkHandler(this@PDFView)

        private var defaultPage = 0

        private var swipeHorizontal = false

        private var annotationRendering = false

        private var password: String? = null

        private var scrollHandle: ScrollHandle? = null

        private var antialiasing = true

        private var pageSeparatorSpacing = 0
        private var startSpacing = 0
        private var endSpacing = 0
        private var minZoom: Float = DEFAULT_MIN_SCALE
        private var midZoom: Float = DEFAULT_MID_SCALE
        private var maxZoom: Float = DEFAULT_MAX_SCALE

        private var autoSpacing = false

        private var pageFitPolicy: FitPolicy? = FitPolicy.WIDTH

        private var fitEachPage = false

        private var pageFling = false

        private var pageFlingByVelocity = false

        private var pageSnap = false

        private var nightMode = false
        private var textSelectionEnabled = false
        private var selectionPopupEnabled = true
        private var onSelectionActionListener: OnSelectionActionListener? = null
        private var onSelectionChangeListener: OnSelectionChangeListener? = null
        private var selectionHandleColor: Int? = null
        private var selectionHighlightColor: Int? = null
        private var selectionPopupBackgroundColor: Int? = null
        private var selectionPopupTextColor: Int? = null
        private var selectionPopupText: CharSequence? = null
        private var selectionStartHandleDrawable: Drawable? = null
        private var selectionEndHandleDrawable: Drawable? = null
        private var tintSelectionHandleDrawables = false
        private var magnifierEnabled = false
        private var textMarkupEnabled = false
        private var highlightColor: Int? = null
        private var underlineColor: Int? = null
        private var strikethroughColor: Int? = null
        private var onTextMarkupListener: OnTextMarkupListener? = null
        private var magnifierZoom: Float? = null
        private var magnifierWidth: Float? = null
        private var magnifierHeight: Float? = null
        private var magnifierCornerRadius: Float? = null
        private var magnifierBorderColor: Int? = null
        private var searchHighlightColor: Int? = null
        private var searchHighlightCornerRadius: Float? = null
        private var searchIgnoreCase = true
        private var touchPriority = false
        private var useBestQuality = false
        private var thumbnailRatio = Constants.THUMBNAIL_RATIO
        private var horizontalBorder = 0
        private var verticalBorder = 0

        fun pages(vararg pageNumbers: Int): Configurator {
            this.pageNumbers = pageNumbers
            return this
        }

        fun enableSwipe(enableSwipe: Boolean): Configurator {
            this.enableSwipe = enableSwipe
            return this
        }

        fun enableDoubletap(enableDoubletap: Boolean): Configurator {
            this.enableDoubletap = enableDoubletap
            return this
        }

        fun enableAnnotationRendering(annotationRendering: Boolean): Configurator {
            this.annotationRendering = annotationRendering
            return this
        }

        fun onDraw(onDrawListener: OnDrawListener?): Configurator {
            this.onDrawListener = onDrawListener
            return this
        }

        fun onDrawAll(onDrawAllListener: OnDrawListener?): Configurator {
            this.onDrawAllListener = onDrawAllListener
            return this
        }

        fun onReadyForPrinting(onReadyForPrintingListener: OnReadyForPrintingListener?): Configurator {
            this.onReadyForPrintingListener = onReadyForPrintingListener
            return this
        }

        fun onLoad(onLoadCompleteListener: OnLoadCompleteListener?): Configurator {
            this.onLoadCompleteListener = onLoadCompleteListener
            return this
        }

        fun onAttach(onAttachCompleteListener: OnAttachCompleteListener?): Configurator {
            this.onAttachCompleteListener = onAttachCompleteListener
            return this
        }

        fun onDetach(onDetachCompleteListener: OnDetachCompleteListener?): Configurator {
            this.onDetachCompleteListener = onDetachCompleteListener
            return this
        }

        fun onPageScroll(onPageScrollListener: OnPageScrollListener?): Configurator {
            this.onPageScrollListener = onPageScrollListener
            return this
        }

        fun onError(onErrorListener: OnErrorListener?): Configurator {
            this.onErrorListener = onErrorListener
            return this
        }

        fun onPageError(onPageErrorListener: OnPageErrorListener?): Configurator {
            this.onPageErrorListener = onPageErrorListener
            return this
        }

        fun onPageChange(onPageChangeListener: OnPageChangeListener?): Configurator {
            this.onPageChangeListener = onPageChangeListener
            return this
        }

        fun onRender(onRenderListener: OnRenderListener?): Configurator {
            this.onRenderListener = onRenderListener
            return this
        }

        fun onTap(onTapListener: OnTapListener?): Configurator {
            this.onTapListener = onTapListener
            return this
        }

        fun onLongPress(onLongPressListener: OnLongPressListener?): Configurator {
            this.onLongPressListener = onLongPressListener
            return this
        }

        fun linkHandler(linkHandler: LinkHandler?): Configurator {
            this.linkHandler = linkHandler
            return this
        }

        fun defaultPage(defaultPage: Int): Configurator {
            this.defaultPage = defaultPage
            return this
        }

        fun swipeHorizontal(swipeHorizontal: Boolean): Configurator {
            this.swipeHorizontal = swipeHorizontal
            return this
        }

        fun password(password: String?): Configurator {
            this.password = password
            return this
        }

        fun scrollHandle(scrollHandle: ScrollHandle?): Configurator {
            this.scrollHandle = scrollHandle
            return this
        }

        fun enableAntialiasing(antialiasing: Boolean): Configurator {
            this.antialiasing = antialiasing
            return this
        }

        fun pageSeparatorSpacing(pageSeparatorSpacing: Int): Configurator {
            this.pageSeparatorSpacing = pageSeparatorSpacing
            return this
        }

        fun startEndSpacing(startSpacing: Int, endSpacing: Int): Configurator {
            this.startSpacing = startSpacing
            this.endSpacing = endSpacing
            return this
        }

        /**
         * Sets the requested minimum, medium and maximum zoom levels.
         * 
         * 
         * Pinch zoom is constrained to the `[0.3f, 100f]` range. Values outside this
         * range are clamped while processing a pinch gesture. If the resulting maximum is less
         * than the resulting minimum, the effective maximum is raised to the effective minimum.
         * 
         * @param minZoom requested minimum zoom level
         * @param midZoom requested medium zoom level used by double tap
         * @param maxZoom requested maximum zoom level
         * @return this configurator
         */
        fun zoom(minZoom: Float, midZoom: Float, maxZoom: Float): Configurator {
            this.minZoom = minZoom
            this.midZoom = midZoom
            this.maxZoom = maxZoom
            return this
        }

        fun autoSpacing(autoSpacing: Boolean): Configurator {
            this.autoSpacing = autoSpacing
            return this
        }

        fun pageFitPolicy(pageFitPolicy: FitPolicy?): Configurator {
            this.pageFitPolicy = pageFitPolicy
            return this
        }

        fun fitEachPage(fitEachPage: Boolean): Configurator {
            this.fitEachPage = fitEachPage
            return this
        }

        fun pageSnap(pageSnap: Boolean): Configurator {
            this.pageSnap = pageSnap
            return this
        }

        fun pageFling(pageFling: Boolean): Configurator {
            this.pageFling = pageFling
            return this
        }

        /** See [PDFView.isPageFlingByVelocity]. */
        fun pageFlingByVelocity(byVelocity: Boolean): Configurator {
            this.pageFlingByVelocity = byVelocity
            return this
        }

        fun nightMode(nightMode: Boolean): Configurator {
            this.nightMode = nightMode
            return this
        }

        fun enableTextSelection(textSelectionEnabled: Boolean): Configurator {
            this.textSelectionEnabled = textSelectionEnabled
            return this
        }

        fun onSelectionAction(onSelectionActionListener: OnSelectionActionListener?): Configurator {
            this.onSelectionActionListener = onSelectionActionListener
            return this
        }

        fun onSelectionChange(onSelectionChangeListener: OnSelectionChangeListener?): Configurator {
            this.onSelectionChangeListener = onSelectionChangeListener
            return this
        }

        fun selectionPopupEnabled(selectionPopupEnabled: Boolean): Configurator {
            this.selectionPopupEnabled = selectionPopupEnabled
            return this
        }

        fun selectionHandleColor(@ColorInt color: Int): Configurator {
            this.selectionHandleColor = color
            return this
        }

        fun selectionHighlightColor(@ColorInt color: Int): Configurator {
            this.selectionHighlightColor = color
            return this
        }

        /**
         * Custom selection handles, see [PDFView.setSelectionHandleDrawables]
         */
        fun selectionHandleDrawables(start: Drawable?, end: Drawable?, tint: Boolean = false): Configurator {
            this.selectionStartHandleDrawable = start
            this.selectionEndHandleDrawable = end
            this.tintSelectionHandleDrawables = tint
            return this
        }

        /**
         * Offer underline and strikethrough in the selection popup, see [PDFView.addTextMarkupToSelection]
         */
        fun enableTextMarkup(enabled: Boolean): Configurator {
            this.textMarkupEnabled = enabled
            return this
        }

        /** Set the color for highlight annotations. */
        fun highlightColor(@ColorInt color: Int): Configurator { highlightColor = color; return this }

        fun underlineColor(@ColorInt color: Int): Configurator {
            this.underlineColor = color
            return this
        }

        fun strikethroughColor(@ColorInt color: Int): Configurator {
            this.strikethroughColor = color
            return this
        }

        fun onTextMarkup(onTextMarkupListener: OnTextMarkupListener?): Configurator {
            this.onTextMarkupListener = onTextMarkupListener
            return this
        }

        /**
         * Show a loupe on long press where there is no text to select
         */
        fun enableMagnifier(enabled: Boolean): Configurator {
            this.magnifierEnabled = enabled
            return this
        }

        fun magnifierZoom(zoom: Float): Configurator {
            this.magnifierZoom = zoom
            return this
        }

        /**
         * @param width loupe width in pixels
         * @param height loupe height in pixels
         */
        fun magnifierSize(width: Float, height: Float): Configurator {
            this.magnifierWidth = width
            this.magnifierHeight = height
            return this
        }

        fun magnifierCornerRadius(radius: Float): Configurator {
            this.magnifierCornerRadius = radius
            return this
        }

        fun magnifierBorderColor(@ColorInt color: Int): Configurator {
            this.magnifierBorderColor = color
            return this
        }

        fun searchHighlightColor(@ColorInt color: Int): Configurator {
            this.searchHighlightColor = color
            return this
        }

        /**
         * @param radius corner radius in pixels
         */
        fun searchHighlightCornerRadius(radius: Float): Configurator {
            this.searchHighlightCornerRadius = radius
            return this
        }

        fun searchIgnoreCase(ignoreCase: Boolean): Configurator {
            this.searchIgnoreCase = ignoreCase
            return this
        }

        fun selectionPopupBackgroundColor(@ColorInt color: Int): Configurator {
            this.selectionPopupBackgroundColor = color
            return this
        }

        fun selectionPopupTextColor(@ColorInt color: Int): Configurator {
            this.selectionPopupTextColor = color
            return this
        }

        fun selectionPopupText(text: CharSequence?): Configurator {
            this.selectionPopupText = text
            return this
        }

        fun disableLongPress(): Configurator {
            this@PDFView.dragPinchManager.disableLongPress()
            return this
        }

        fun touchPriority(hasPriority: Boolean): Configurator {
            this.touchPriority = hasPriority
            return this
        }

        fun renderDuringScale(renderDuringScale: Boolean): Configurator {
            this@PDFView.renderDuringScale = renderDuringScale
            return this
        }

        /**
         * By default, generated bitmaps are compressed with [Bitmap.Config.RGB_565] format to reduce memory consumption.
         * If [.useBestQuality] is true, rendering will be done with [Bitmap.Config.ARGB_8888].
         * @param useBestQuality true to use [Bitmap.Config.ARGB_8888], false for [Bitmap.Config.RGB_565]
         */
        fun useBestQuality(useBestQuality: Boolean): Configurator {
            this.useBestQuality = useBestQuality
            return this
        }

        fun thumbnailRatio(@FloatRange(from = 0.1, to = 1.0) thumbnailRatio: Float): Configurator {
            this.thumbnailRatio = thumbnailRatio
            return this
        }

        fun horizontalBorder(horizontalBorder: Int): Configurator {
            this.horizontalBorder = horizontalBorder
            return this
        }

        fun verticalBorder(verticalBorder: Int): Configurator {
            this.verticalBorder = verticalBorder
            return this
        }

        fun load() {
            if (!hasSize) {
                waitingDocumentConfigurator = this
                return
            }
            this@PDFView.recycle()
            this@PDFView.callbacks.setOnReadyForPrinting(onReadyForPrintingListener)
            this@PDFView.callbacks.setOnLoadComplete(onLoadCompleteListener)
            this@PDFView.callbacks.setOnAttachCompleteListener(onAttachCompleteListener)
            this@PDFView.callbacks.setOnDetachCompleteListener(onDetachCompleteListener)
            this@PDFView.callbacks.onError = onErrorListener
            this@PDFView.callbacks.onDraw = onDrawListener
            this@PDFView.callbacks.onDrawAll = onDrawAllListener
            this@PDFView.callbacks.setOnPageChange(onPageChangeListener)
            this@PDFView.callbacks.setOnPageScroll(onPageScrollListener)
            this@PDFView.callbacks.setOnRender(onRenderListener)
            this@PDFView.callbacks.setOnTap(onTapListener)
            this@PDFView.callbacks.setOnLongPress(onLongPressListener)
            this@PDFView.callbacks.setOnPageError(onPageErrorListener)
            this@PDFView.callbacks.setLinkHandler(linkHandler)
            this@PDFView.callbacks.setOnSelectionActionListener(onSelectionActionListener)
            this@PDFView.callbacks.setOnSelectionChangeListener(onSelectionChangeListener)
            this@PDFView.isSwipeEnabled = enableSwipe
            this@PDFView.setNightMode(nightMode)
            this@PDFView.enableTextSelection(textSelectionEnabled)
            this@PDFView.setSelectionPopupEnabled(selectionPopupEnabled)
            if (selectionHandleColor != null) this@PDFView.setSelectionHandleColor(selectionHandleColor!!)
            if (selectionHighlightColor != null) this@PDFView.setSelectionHighlightColor(selectionHighlightColor!!)
            if (selectionPopupBackgroundColor != null) this@PDFView.setSelectionPopupBackgroundColor(selectionPopupBackgroundColor!!)
            if (selectionPopupTextColor != null) this@PDFView.setSelectionPopupTextColor(selectionPopupTextColor!!)
            if (selectionPopupText != null) this@PDFView.setSelectionPopupText(selectionPopupText)
            if (selectionStartHandleDrawable != null || selectionEndHandleDrawable != null) {
                this@PDFView.setSelectionHandleDrawables(selectionStartHandleDrawable, selectionEndHandleDrawable, tintSelectionHandleDrawables)
            }
            this@PDFView.enableMagnifier(magnifierEnabled)
            this@PDFView.enableTextMarkup(textMarkupEnabled)
            if (highlightColor != null) this@PDFView.setHighlightColor(highlightColor!!)
            if (underlineColor != null) this@PDFView.setUnderlineColor(underlineColor!!)
            if (strikethroughColor != null) this@PDFView.setStrikethroughColor(strikethroughColor!!)
            this@PDFView.callbacks.onTextMarkupListener = onTextMarkupListener
            if (magnifierZoom != null) this@PDFView.setMagnifierZoom(magnifierZoom!!)
            if (magnifierWidth != null && magnifierHeight != null) this@PDFView.setMagnifierSize(magnifierWidth!!, magnifierHeight!!)
            if (magnifierCornerRadius != null) this@PDFView.setMagnifierCornerRadius(magnifierCornerRadius!!)
            if (magnifierBorderColor != null) this@PDFView.setMagnifierBorderColor(magnifierBorderColor!!)
            if (searchHighlightColor != null) this@PDFView.setSearchHighlightColor(searchHighlightColor!!)
            if (searchHighlightCornerRadius != null) this@PDFView.setSearchHighlightCornerRadius(searchHighlightCornerRadius!!)
            this@PDFView.setSearchIgnoreCase(searchIgnoreCase)
            this@PDFView.enableDoubleTap(enableDoubletap)
            this@PDFView.setDefaultPage(defaultPage)
            this@PDFView.isSwipeVertical = !swipeHorizontal
            this@PDFView.enableAnnotationRendering(annotationRendering)
            this@PDFView.setInkColor(inkColor)
            this@PDFView.setInkWidth(inkWidth)
            this@PDFView.setOnInkChangeListener(onInkChangeListener)
            this@PDFView.setOnEditChangeListener(onEditChangeListener)
            this@PDFView.setDrawingMode(drawingMode)
            this@PDFView.scrollHandle = scrollHandle
            this@PDFView.enableAntialiasing(antialiasing)
            this@PDFView.setAutoSpacing(autoSpacing)
            this@PDFView.pageFitPolicy = pageFitPolicy
            this@PDFView.isFitEachPage = fitEachPage
            this@PDFView.isPageSnap = pageSnap
            this@PDFView.setPageFling(pageFling)
            this@PDFView.isPageFlingByVelocity = pageFlingByVelocity
            this@PDFView.setTouchPriority(touchPriority)
            this@PDFView.minZoom = minZoom
            this@PDFView.midZoom = midZoom
            this@PDFView.maxZoom = maxZoom
            this@PDFView.useBestQuality(useBestQuality)
            this@PDFView.thumbnailRatio = thumbnailRatio
            this@PDFView.setHorizontalBorder(horizontalBorder)
            this@PDFView.setVerticalBorder(verticalBorder)
            renderDuringScale(renderDuringScale)
            setPageSeparatorSpacing(pageSeparatorSpacing)
            setStartSpacing(startSpacing)
            setEndSpacing(endSpacing)

            if (pageNumbers != null) {
                this@PDFView.load(documentSource, password, pageNumbers)
            } else {
                this@PDFView.load(documentSource, password)
            }
        }

        private fun setPageSeparatorSpacing(pageSeparatorSpacingDp: Int) {
            this@PDFView.pageSeparatorSpacing = Util.getDP(context, pageSeparatorSpacingDp)
        }

        private fun setStartSpacing(startSpacing: Int) {
            this@PDFView.startSpacing = Util.getDP(context, startSpacing)
        }

        private fun setEndSpacing(endSpacing: Int) {
            this@PDFView.endSpacing = Util.getDP(context, endSpacing)
        }
    }

    companion object {
        const val SHAPE_RECT = 0
        const val SHAPE_ELLIPSE = 1
        const val SHAPE_LINE = 2
        const val SHAPE_ARROW = 3
        const val NOTE_COLOR = 0xFFFFC107.toInt()
        // points around the bounds of an edit that are drawn again with it (anti-aliasing, line ends)
        private const val AREA_PAD = 16f
        private const val SEPIA_BACKGROUND = 0xFFF4ECD8.toInt()
        private val TAG: String = PDFView::class.java.simpleName
        private val INVALID_CHAR_INDEX = -1
        private const val MAX_FALLBACK_CHAR_DISTANCE_SQ = 400f
        private const val MAX_FALLBACK_DEVICE_DISTANCE_SQ = 900f
        private const val SELECTION_HANDLE_RADIUS_DP = 13
        private const val SELECTION_HANDLE_MIN_TOUCH_DP = 48

        const val DEFAULT_UNDERLINE_COLOR: Int = 0xFF1E88E5.toInt()
        const val DEFAULT_STRIKETHROUGH_COLOR: Int = 0xFFE53935.toInt()
        const val DEFAULT_MAGNIFIER_ZOOM: Float = 1.5f
        const val DEFAULT_MAGNIFIER_BORDER_COLOR: Int = 0x1F000000
        private const val DEFAULT_MAGNIFIER_WIDTH_DP = 132
        private const val DEFAULT_MAGNIFIER_HEIGHT_DP = 64
        private const val DEFAULT_MAGNIFIER_CORNER_RADIUS_DP = 12
        private const val DEFAULT_MAGNIFIER_OFFSET_DP = 42
        private const val MAGNIFIER_SHADOW_RADIUS_DP = 6
        private const val MAGNIFIER_SHADOW_DY_DP = 2
        private const val MAGNIFIER_SHADOW_COLOR = 0x40000000
        private const val SELECTION_POPUP_MARGIN_DP = 8
        private const val SELECTION_POPUP_HORIZONTAL_PADDING_DP = 16
        private const val SELECTION_POPUP_VERTICAL_PADDING_DP = 10
        private const val SELECTION_POPUP_CORNER_RADIUS_DP = 12
        private const val SELECTION_POPUP_ELEVATION_DP = 6
        private const val SELECTION_POPUP_TEXT_SIZE_SP = 14f

        const val DEFAULT_MAX_SCALE: Float = 3.0f
        const val DEFAULT_MID_SCALE: Float = 1.75f
        const val DEFAULT_MIN_SCALE: Float = 0.93f

        const val DEFAULT_SEARCH_HIGHLIGHT_COLOR: Int = 0x66FFC107

        /**
         * Page width in pixels used to map search matches, big enough to keep rounding errors invisible when zoomed
         */
        private const val SEARCH_MAPPING_WIDTH = 4096f
        /** Keeps the results small enough to pass in an Intent */
        const val DEFAULT_SEARCH_MAX_RESULTS = 500
        const val PRELOAD_SEARCH_TEXT_MAX_PAGES = 1000
        private const val SEARCH_SNIPPET_LENGTH = 160
    }
}
