package com.wxiwei.office.editor.ui

import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.editor.docsdk.EditAction
import com.editor.docsdk.EditContext
import com.editor.docsdk.EditRequest
import com.editor.docsdk.EditFeature
import com.editor.docsdk.ShapeItem
import com.editor.docsdk.ShapeOrder
import com.editor.docsdk.TransitionFormat
import com.wxiwei.office.R
import com.wxiwei.office.editor.EditResult
import com.wxiwei.office.editor.pptx.LivePptxSession
import com.wxiwei.office.editor.pptx.PptxShapeInfo
import com.wxiwei.office.editor.pptx.ShapeKind
import com.wxiwei.office.editor.pptx.SlideEffect
import com.wxiwei.office.editor.pptx.SlideTransition
import com.wxiwei.office.editor.pptx.Rect
import com.wxiwei.office.editor.pptx.TextFormat
import com.wxiwei.office.editor.slide.SlideGeometry
import com.wxiwei.office.pg.control.Presentation
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.system.IMainFrame
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * PowerPoint: tap a shape on the slide (or pick it from a list), change its text, move or delete it, or add a
 * text box; the slide updates at once and Save writes the .pptx in place.
 */
class SlideEditPanel @JvmOverloads constructor(
    activity: AppCompatActivity, reader: OfficeDocumentView, file: File, features: Set<EditFeature> = EditFeature.all(),
) : OfficeEditPanel(activity, reader, file, features) {

    // the file the view and the session work on: the original, or a working copy in the cache after
    // edits that needed a reopen (slides added, moved...); Save writes it over the original
    private var working = file
    private var workingChanged = false

    /** The document at some point (a file in the cache) and the slide shown then. */
    private class Point(val file: File, val slide: Int)
    // slide changes reopen the document with a new session, which starts without history: they are
    // undone by going back to the document as it was before them (edits made in one session before
    // a slide change go back together)
    private val undoPoints = ArrayList<Point>()
    private val redoPoints = ArrayList<Point>()
    private var session = LivePptxSession(reader.control!!, file)
    private var shapeId = -1
    // position of the selected shape including moves not saved yet (listShapes reads the file)
    private var rect: Rect? = null
    // the text of the selected shape, as last read or written
    private var shapeText = ""

    override val tabs = listOf(
        Tab(R.string.docsdk_edit_tab_home, listOf(EditAction.BOLD, EditAction.ITALIC, EditAction.UNDERLINE, EditAction.TEXT_COLOR,
            EditAction.FONT, EditAction.FONT_SIZE, EditAction.ALIGN_LEFT, EditAction.ALIGN_CENTER, EditAction.ALIGN_RIGHT, EditAction.SET_TEXT)),
        Tab(R.string.docsdk_edit_tab_insert, listOf(EditAction.ADD_TEXT_BOX, EditAction.INSERT_PICTURE, EditAction.ADD_SHAPE)),
        Tab(R.string.docsdk_edit_tab_arrange, listOf(EditAction.COPY, EditAction.CUT, EditAction.PASTE, EditAction.SHAPE_LIST, EditAction.MOVE_LEFT, EditAction.MOVE_RIGHT, EditAction.MOVE_UP,
            EditAction.MOVE_DOWN, EditAction.ROTATE, EditAction.DELETE)),
        Tab(R.string.docsdk_edit_tab_slides, listOf(EditAction.ADD_SLIDE, EditAction.DUPLICATE_SLIDE, EditAction.SLIDE_UP, EditAction.SLIDE_DOWN,
            EditAction.DELETE_SLIDE, EditAction.TRANSITION, EditAction.ANIMATIONS, EditAction.EXPORT_PNG, EditAction.EXPORT_PDF)),
    )

    private val overlay = SlideSelectionOverlay(context) { docView() as? Presentation }

    init {
        status = str(R.string.docsdk_edit_slide_hint)
        action(EditAction.UNDO, EditFeature.UNDO_REDO) {
            when {
                session.canUndo() -> if (session.undo()) afterUndo() else toast(str(R.string.docsdk_edit_nothing_to_undo))
                undoPoints.isNotEmpty() -> goTo(undoPoints, redoPoints)
                else -> toast(str(R.string.docsdk_edit_nothing_to_undo))
            }
        }
        action(EditAction.REDO, EditFeature.UNDO_REDO) {
            when {
                session.canRedo() -> if (session.redo()) afterUndo() else toast(str(R.string.docsdk_edit_nothing_to_redo))
                canRedoPoint() -> goTo(redoPoints, undoPoints)
                else -> toast(str(R.string.docsdk_edit_nothing_to_redo))
            }
        }
        action(EditAction.SAVE) { save() }
        action(EditAction.SAVE_COPY, EditFeature.SAVE_COPY) { stopInline(commit = true); saveCopy() }
        action(EditAction.BOLD, EditFeature.FORMAT) { toggle { TextFormat(bold = it.bold != true) } }
        action(EditAction.ITALIC, EditFeature.FORMAT) { toggle { TextFormat(italic = it.italic != true) } }
        action(EditAction.UNDERLINE, EditFeature.FORMAT) { toggle { TextFormat(underline = it.underline != true) } }
        action(EditAction.TEXT_COLOR, EditFeature.FORMAT) { pickColor(str(R.string.docsdk_edit_text_color)) { c -> c?.let { format(TextFormat(rgbHex = it)) } } }
        action(EditAction.FONT, EditFeature.FORMAT) {
            if (shapeId < 0) return@action toast(str(R.string.docsdk_edit_select_shape_first))
            val current = runCatching { session.textFormatAt(slide(), shapeId, inlineRange()?.first ?: 0) }.getOrNull()?.font
            pickFont(EditFonts.names, current) { name -> format(TextFormat(font = name)) }
        }
        action(EditAction.FONT_SIZE, EditFeature.FORMAT) { pickSize { format(TextFormat(sizePt = it)) } }
        action(EditAction.ALIGN_LEFT, EditFeature.FORMAT) { format(TextFormat(align = "l")) }
        action(EditAction.ALIGN_CENTER, EditFeature.FORMAT) { format(TextFormat(align = "ctr")) }
        action(EditAction.ALIGN_RIGHT, EditFeature.FORMAT) { format(TextFormat(align = "r")) }
        action(EditAction.SET_TEXT, EditFeature.TEXT) { setText() }
        action(EditAction.SHAPE_LIST) { pickShape() }
        action(EditAction.ADD_TEXT_BOX, EditFeature.SHAPES) { addTextBox() }
        action(EditAction.INSERT_PICTURE, EditFeature.PICTURES) { pickImage() }
        action(EditAction.ADD_SHAPE, EditFeature.SHAPES) { askNewShape() }
        action(EditAction.MOVE_LEFT, EditFeature.SHAPES) { move(-1, 0) }
        action(EditAction.MOVE_RIGHT, EditFeature.SHAPES) { move(1, 0) }
        action(EditAction.MOVE_UP, EditFeature.SHAPES) { move(0, -1) }
        action(EditAction.MOVE_DOWN, EditFeature.SHAPES) { move(0, 1) }
        action(EditAction.ROTATE, EditFeature.SHAPES) { if (shapeId < 0) toast(str(R.string.docsdk_edit_select_shape_first)) else rotate((overlay.shapeRotation + 90f) % 360f) }
        action(EditAction.DELETE, EditFeature.SHAPES) { delete() }
        action(EditAction.COPY, EditFeature.CLIPBOARD) { copyShape() }
        action(EditAction.CUT, EditFeature.CLIPBOARD, EditFeature.SHAPES) { if (copyShape()) delete() }
        action(EditAction.PASTE, EditFeature.CLIPBOARD, EditFeature.SHAPES) { pasteShape() }
        action(EditAction.ANIMATIONS, EditFeature.ANIMATIONS) { editEffects() }
        action(EditAction.TRANSITION, EditFeature.ANIMATIONS) { editTransition() }
        action(EditAction.ADD_SLIDE, EditFeature.SLIDES) { slideOp(R.string.docsdk_edit_slide_add_failed, { it + 1 }) { session.addBlankSlide(slide()) } }
        action(EditAction.DUPLICATE_SLIDE, EditFeature.SLIDES) { slideOp(R.string.docsdk_edit_slide_duplicate_failed, { it + 1 }) { session.duplicateSlide(slide()) } }
        action(EditAction.SLIDE_UP, EditFeature.SLIDES) { slideOp(R.string.docsdk_edit_slide_move_failed, { it - 1 }) { slide() > 0 && session.moveSlide(slide(), slide() - 1) } }
        action(EditAction.SLIDE_DOWN, EditFeature.SLIDES) { slideOp(R.string.docsdk_edit_slide_move_failed, { it + 1 }) { slide() < session.slideCount() - 1 && session.moveSlide(slide(), slide() + 1) } }
        action(EditAction.DELETE_SLIDE, EditFeature.SLIDES) {
            // a presentation keeps at least one slide
            if (session.slideCount() <= 1) return@action toast(str(R.string.docsdk_edit_slide_delete_last))
            slideOp(R.string.docsdk_edit_slide_delete_failed, { it - 1 }) { session.deleteSlide(slide()) }
        }
        action(EditAction.EXPORT_PNG, EditFeature.EXPORT) { exportSlide(pdf = false) }
        action(EditAction.EXPORT_PDF, EditFeature.EXPORT) { exportSlide(pdf = true) }
    }

    override fun canUndo() = session.canUndo() || undoPoints.isNotEmpty()
    override fun canRedo() = session.canRedo() || canRedoPoint()

    // an edit made after going back to a point replaces what was undone
    private fun canRedoPoint() = redoPoints.isNotEmpty() && !session.canUndo()

    override fun isActive(action: EditAction): Boolean {
        if (shapeId < 0) return false
        // the part selected while typing in the box, or else its whole text
        val f = runCatching { session.textFormatAt(slide(), shapeId, inlineRange()?.first ?: 0) }.getOrNull() ?: return false
        return when (action) {
            EditAction.BOLD -> f.bold == true
            EditAction.ITALIC -> f.italic == true
            EditAction.UNDERLINE -> f.underline == true
            else -> false
        }
    }

    init {
        EditFonts.register()
        addOverlay(overlay)
        keepAboveKeyboard(true)
        reader.onDocumentGesture = gesture@{ type, event ->
            when (type) {
                IMainFrame.ON_SINGLE_TAP_CONFIRMED -> tapAt(event.rawX, event.rawY) || shapeId >= 0
                // a double tap never confirms its first tap: it selects a text shape and edits it at once
                IMainFrame.ON_DOUBLE_TAP -> editAt(event.rawX, event.rawY)
                else -> false
            }
        }
        overlay.onTap = { x, y -> tapAt(x, y) }
        overlay.movable = has(EditFeature.SHAPES)
        overlay.onChange = { r -> setRect(r) }
        overlay.onRotate = { deg -> rotate(deg) }
        overlay.onFrame = { frame -> placeInline(frame) }
    }

    // In-place text editing: a second tap on the selected text shape opens an editor over it.
    private var inline: EditText? = null

    private fun startInline(s: PptxShapeInfo) {
        stopInline(commit = true)
        val edit = object : EditText(context) {
            // the format bar and the app's own buttons follow the selection
            override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                super.onSelectionChanged(selStart, selEnd)
                if (inline === this) inlineSelectionChanged()
            }
        }.apply {
            setText(s.text)
            setSelection(text.length)
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0xF0FFFFFF.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        styleInline(edit, s.id)
        inline = edit
        // the keyboard takes half the screen: give the slide the rest while typing on it
        toolbar?.visibility = View.GONE
        addOverlay(edit, FrameLayout.LayoutParams(1, 1))
        placeInline(overlay.frameOnScreen())
        edit.requestFocus()
        (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(edit, 0)
        inlineSelectionChanged()
    }

    /**
     * The shape's own look in the editor, at the slide's zoom (points at 96 dpi, then the view zoom):
     * the first run's for the whole text, then each run as drawn on the slide (a word made bold, red
     * or in another font); spans of earlier runs are taken off first.
     */
    private fun styleInline(edit: EditText, id: Int) {
        val text = edit.editableText
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            val ours = span is android.text.style.ForegroundColorSpan || span is TypefaceSpan || span is android.text.style.RelativeSizeSpan || span is FakeStyleSpan ||
                (span is android.text.style.UnderlineSpan && text.getSpanFlags(span) and android.text.Spanned.SPAN_COMPOSING == 0)
            if (ours) text.removeSpan(span)
        }
        val st = session.textStyle(slide(), id) ?: return
        val zoom = (docView() as? Presentation)?.getZoom() ?: 1f
        edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (st.sizePt * 96f / 72f * zoom).coerceIn(dp(12).toFloat(), dp(48).toFloat()))
        edit.typeface = st.typeface
        val c = st.color or 0xFF000000.toInt()
        val runs = session.textRuns(slide(), id)
        // light text keeps a dark backdrop so it stays readable: the color of most of the text decides
        val most = runs.groupBy { it.second.color }.maxByOrNull { (_, r) -> r.sumOf { it.first.last - it.first.first + 1 } }?.key ?: c
        val light = android.graphics.Color.luminance(most or 0xFF000000.toInt()) > 0.6f
        edit.setTextColor(c)
        edit.setBackgroundColor(if (light) 0xE0303030.toInt() else 0xF0FFFFFF.toInt())
        for ((range, run) in runs) {
            val a = range.first.coerceAtMost(text.length); val b = (range.last + 1).coerceAtMost(text.length)
            if (b <= a) continue
            val flags = android.text.Spanned.SPAN_EXCLUSIVE_INCLUSIVE
            if (run.color != st.color) text.setSpan(android.text.style.ForegroundColorSpan(run.color or 0xFF000000.toInt()), a, b, flags)
            if (run.typeface != st.typeface) text.setSpan(TypefaceSpan(run.typeface), a, b, flags)
            if (run.underline) text.setSpan(android.text.style.UnderlineSpan(), a, b, flags)
            if (run.fake != 0) text.setSpan(FakeStyleSpan(run.fake), a, b, flags)
            if (run.sizePt != st.sizePt && st.sizePt > 0f) text.setSpan(android.text.style.RelativeSizeSpan(run.sizePt / st.sizePt), a, b, flags)
        }
    }

    /** Bold / italic the run's font cannot draw (Typeface.BOLD / ITALIC bits), faked on the paint like the slide does. */
    private class FakeStyleSpan(private val fake: Int) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: android.text.TextPaint) = updateMeasureState(tp)
        override fun updateMeasureState(tp: android.text.TextPaint) {
            if (fake and android.graphics.Typeface.BOLD != 0) tp.isFakeBoldText = true
            if (fake and android.graphics.Typeface.ITALIC != 0) tp.textSkewX = com.wxiwei.office.simpletext.font.FontTypefaceManage.FAKE_ITALIC_SKEW
        }
    }

    // ---- the format bar over the keyboard ----

    /**
     * Shows the format bar over the keyboard while text is typed in a shape (true by default). Off:
     * build your own from [com.editor.docsdk.DocumentEditor.Listener.onStateChanged] (called as the
     * selection changes), [textSelection], [isActive] and [formatText] or the format commands.
     */
    var textBar: Boolean = true

    /** The buttons of the format bar, in order; FONT shows the font, FONT_SIZE shows − size +. */
    var textBarActions: List<EditAction> = listOf(EditAction.BOLD, EditAction.ITALIC, EditAction.UNDERLINE, EditAction.TEXT_COLOR,
        EditAction.FONT, EditAction.FONT_SIZE, EditAction.ALIGN_LEFT, EditAction.ALIGN_CENTER, EditAction.ALIGN_RIGHT)

    override val editContext: EditContext?
        get() = when {
            shapeId < 0 -> null
            inline != null -> if (textBar) EditContext.SHAPE_TEXT else null
            else -> EditContext.SHAPE
        }

    override fun defaultActions(context: EditContext): List<EditAction> = when (context) {
        EditContext.SHAPE_TEXT -> textBarActions
        EditContext.SHAPE -> listOf(EditAction.CUT, EditAction.COPY, EditAction.PASTE, EditAction.DELETE, EditAction.ROTATE) +
            // a shape with text: its whole text formatted at once
            if (shapeText.isEmpty()) emptyList() else listOf(EditAction.BOLD, EditAction.ITALIC, EditAction.UNDERLINE,
                EditAction.TEXT_COLOR, EditAction.FONT_SIZE, EditAction.ALIGN_LEFT, EditAction.ALIGN_CENTER, EditAction.ALIGN_RIGHT)
        else -> emptyList()
    }

    override fun canRun(action: EditAction): Boolean = when (action) {
        EditAction.PASTE -> canPaste
        EditAction.CUT, EditAction.COPY, EditAction.DELETE, EditAction.ROTATE -> shapeId >= 0
        else -> true
    }

    internal override fun barState(): ContextBar.State {
        if (shapeId < 0) return ContextBar.State()
        val at = inlineRange()?.first ?: 0
        val f = runCatching { session.textFormatAt(slide(), shapeId, at) }.getOrNull()
        val run = session.textRuns(slide(), shapeId).firstOrNull { at in it.first }?.second ?: session.textStyle(slide(), shapeId)
        return ContextBar.State(font = f?.font, sizePt = run?.sizePt, color = run?.color)
    }

    /** The selection in the editor moved: light the bar's buttons and tell the app. */
    private fun inlineSelectionChanged() = stateChanged()

    /** What the format commands act on while typing: the selected part, or the word at the caret; null for all the text. */
    private fun inlineRange(): IntRange? {
        textSelection()?.let { return it }
        val edit = inline ?: return null
        val text = edit.text
        val at = edit.selectionStart.coerceIn(0, text.length)
        var a = at; var b = at
        while (a > 0 && text[a - 1].isLetterOrDigit()) a--
        while (b < text.length && text[b].isLetterOrDigit()) b++
        return if (b > a) a until b else null
    }

    /**
     * Formats chars [start, end) of the selected shape's text with [make] (given the format there
     * now). While the text is typed in place, what was typed is written to the shape first and the
     * editor stays open (keyboard, selection) with the new look.
     */
    private fun formatRange(start: Int, end: Int, make: (TextFormat) -> TextFormat): Boolean {
        if (shapeId < 0 || start < 0 || end <= start) return false
        val edit = inline
        if (edit != null) {
            // positions are in the edited text: write it to the shape first
            val value = edit.text.toString()
            if (value != shapeText) {
                if (!session.setShapeText(slide(), shapeId, value)) {
                    toast(session.lastError?.message ?: str(R.string.docsdk_edit_text_change_failed))
                    return false
                }
                shapeText = value
            }
        }
        if (end > shapeText.length) return false
        val format = make(session.textFormatAt(slide(), shapeId, start) ?: TextFormat())
        if (!session.setTextFormat(slide(), shapeId, start, end, format)) {
            toast(session.lastError?.message ?: str(R.string.docsdk_edit_format_failed))
            return false
        }
        if (edit != null && !session.needsReopen) {
            styleInline(edit, shapeId)
            // what was formatted stays selected, for another format
            if (minOf(edit.selectionStart, edit.selectionEnd) != start || maxOf(edit.selectionStart, edit.selectionEnd) != end)
                edit.setSelection(start, end.coerceAtMost(edit.text.length))
            reader.invalidateThumbnail(slide() + 1)
            inlineSelectionChanged()
        } else {
            // not shown live (a shape in a group): the document opens again
            stopInline(commit = false)
            reopenHint()
            stateChanged()
        }
        return true
    }

    /** The part of the text selected in the in-place editor, or null. */
    fun textSelection(): IntRange? {
        val edit = inline ?: return null
        val a = minOf(edit.selectionStart, edit.selectionEnd)
        val b = maxOf(edit.selectionStart, edit.selectionEnd)
        return if (a in 0 until b) a until b else null
    }

    /** The selected shape's text, as being typed while its in-place editor is open; null without a shape. */
    fun selectedText(): String? = if (shapeId < 0) null else inline?.text?.toString() ?: shapeText

    /** Formats chars [start, end) of the selected shape's text (positions as in [selectedText]); false when it could not. */
    fun formatText(start: Int, end: Int, format: TextFormat): Boolean = !isBusy && formatRange(start, end) { format }

    /** Keeps the editor on the shape frame, at least a few lines tall, above the format bar. */
    private fun placeInline(frame: android.graphics.RectF?) {
        val edit = inline ?: return
        if (frame == null) { edit.visibility = View.INVISIBLE; return }
        edit.visibility = View.VISIBLE
        val lp = edit.layoutParams as FrameLayout.LayoutParams
        val w = maxOf(frame.width().toInt(), dp(160)).coerceAtMost(reader.width)
        val h = maxOf(frame.height().toInt(), dp(96))
        val left = frame.left.toInt().coerceIn(0, maxOf(0, reader.width - w))
        // above the bars and the keyboard when the shape is under them
        val top = minOf(frame.top.toInt(), visibleBottom(reader) - h - dp(8)).coerceAtLeast(0)
        if (lp.width != w || lp.height != h || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = w; lp.height = h; lp.leftMargin = left; lp.topMargin = top
            edit.layoutParams = lp
        }
    }

    /** Closes the in-place editor, writing its text to the shape when it changed. */
    private fun stopInline(commit: Boolean) {
        val edit = inline ?: return
        inline = null
        val value = edit.text.toString()
        (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(edit.windowToken, 0)
        removeOverlay(edit)
        toolbar?.visibility = View.VISIBLE
        if (commit && shapeId >= 0 && value != shapeText) {
            if (!session.setShapeText(slide(), shapeId, value)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_text_change_failed))
            else {
                shapeText = value
                reopenHint()
            }
        }
        stateChanged()
    }

    /** The top shape under a screen point, or null. */
    private fun shapeAt(rawX: Float, rawY: Float): PptxShapeInfo? {
        val p = docView() as? Presentation ?: return null
        val origin = IntArray(2)
        p.getLocationOnScreen(origin)
        val point = SlideGeometry.viewToEmu(p, rawX - origin[0], rawY - origin[1]) ?: return null
        // a fingertip's reach around small boxes (a one-line title at a small zoom)
        val slop = SlideGeometry.emuPerPixel(p)?.let { (it * dp(12)).toLong() } ?: 0L
        return SlideGeometry.pick(session.listShapes(slide()), point, slop)
    }

    /** Selects the text shape under a screen point and edits its text in place; false when there is none. */
    private fun editAt(rawX: Float, rawY: Float): Boolean {
        val hit = shapeAt(rawX, rawY)?.takeIf { it.kind == ShapeKind.TEXT && has(EditFeature.TEXT) } ?: return false
        stopInline(commit = true)
        select(hit)
        startInline(hit)
        return true
    }

    /** Selects the top shape under a screen point, or clears the selection. */
    private fun tapAt(rawX: Float, rawY: Float): Boolean {
        val hit = shapeAt(rawX, rawY)
        val editing = inline != null
        stopInline(commit = true)
        // a second tap on the selected text shape edits its text in place
        if (!editing && hit != null && hit.id == shapeId && hit.kind == ShapeKind.TEXT && has(EditFeature.TEXT)) {
            startInline(hit)
            return true
        }
        select(hit)
        return hit != null
    }

    override fun onKeyboardMoved() {
        placeInline(overlay.frameOnScreen())
    }

    override fun close() {
        stopInline(commit = true)
        super.close()
        val points = undoPoints + redoPoints
        undoPoints.clear(); redoPoints.clear()
        points.forEach { drop(it.file) }
        reader.onDocumentGesture = null
        removeOverlay(overlay)
    }

    /** Undo/redo may move the selected shape: re-read its frame and redraw the thumbnail. */
    private fun afterUndo() {
        reader.invalidateThumbnail(slide() + 1)
        if (shapeId < 0) return
        val s = session.listShapes(slide()).firstOrNull { it.id == shapeId }
        rect = s?.rectEmu
        overlay.shapeRotation = s?.rotationDeg ?: 0f
        overlay.selection = rect
        if (s == null) select(null)
    }

    private fun describe(s: PptxShapeInfo): String {
        val t = s.text.replace('\n', ' ').take(40)
        return "#${s.id} ${s.kind.name.lowercase()}" + if (t.isNotEmpty()) ": $t" else " (${s.name})"
    }

    private fun select(s: PptxShapeInfo?) {
        shapeId = s?.id ?: -1
        rect = s?.rectEmu
        overlay.keepAspect = s?.kind == ShapeKind.PICTURE
        overlay.shapeRotation = s?.rotationDeg ?: 0f
        overlay.slideIndex = slide()
        overlay.selection = rect
        status = s?.let { describe(it) } ?: str(R.string.docsdk_edit_no_shape_selected)
        shapeText = s?.text ?: ""
        stateChanged()
    }

    private fun slide(): Int = (reader.state.value.pageNumber - 1).coerceAtLeast(0)

    /** The slide shown, as a PNG (1920 px wide) or a one-page vector PDF, to a file the user picks. */
    private fun exportSlide(pdf: Boolean) {
        val index = slide()
        val p = reader.control?.getView() as? com.wxiwei.office.pg.control.Presentation ?: return
        val name = file.nameWithoutExtension + " - slide " + (index + 1) + if (pdf) ".pdf" else ".png"
        var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
        launcher = activity.activityResultRegistry.register("slide-export-" + System.nanoTime(),
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(if (pdf) "application/pdf" else "image/png")) { uri ->
            launcher?.unregister()
            if (uri == null) return@register
            try {
                val ok = context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> writeSlide(p, index, pdf, out) }
                toast(if (ok) str(R.string.docsdk_edit_exported_slide, index + 1) else str(R.string.docsdk_edit_slide_not_ready))
            } catch (e: Exception) {
                toast(str(R.string.docsdk_edit_export_failed, e.message ?: ""))
            }
        }
        launcher.launch(name)
    }

    /**
     * The shapes of the slide, the top layer first: a tap selects one; ▲ / ▼ move it one layer up or
     * down (z-order), a long press offers "to the front" / "to the back".
     */
    private fun pickShape() {
        if (session.listShapes(slide()).isEmpty()) return toast(str(R.string.docsdk_edit_slide_no_shapes))
        val orders = mapOf(ShapeOrder.FRONT to "front", ShapeOrder.FORWARD to "forward", ShapeOrder.BACKWARD to "backward", ShapeOrder.BACK to "back")
        if (appAnswers(EditRequest.Shapes(running, str(R.string.docsdk_edit_shape_list_title, slide() + 1),
                read = { session.listShapes(slide()).asReversed().map { ShapeItem(it.id, it.name, it.kind.name.lowercase(), it.text, it.id == shapeId) } },
                onSelect = { id -> session.listShapes(slide()).firstOrNull { it.id == id }?.let { select(it) } },
                onReorder = { id, order -> has(EditFeature.SHAPES) && reorder(id, orders.getValue(order)) }))) return
        dialogs.show(str(R.string.docsdk_edit_shape_list_title, slide() + 1)) {
            fun fill() {
                clear()
                for (s in session.listShapes(slide()).asReversed()) {
                    row(describe(s), bold = s.id == shapeId,
                        onClick = { select(s); dialog?.dismiss() },
                        onLongClick = if (!has(EditFeature.SHAPES)) null else fun() {
                            val moves = listOf(str(R.string.docsdk_edit_bring_to_front) to "front", str(R.string.docsdk_edit_bring_forward) to "forward", str(R.string.docsdk_edit_send_backward) to "backward", str(R.string.docsdk_edit_send_to_back) to "back")
                            dialogs.pick(describe(s), moves.map { it.first }) { i -> reorder(s.id, moves[i].second); fill() }
                        },
                        actions = if (!has(EditFeature.SHAPES)) emptyList() else listOf(
                            DialogKit.Action("▲", str(R.string.docsdk_edit_bring_forward_of, s.id)) { reorder(s.id, "forward"); fill() },
                            DialogKit.Action("▼", str(R.string.docsdk_edit_send_backward_of, s.id)) { reorder(s.id, "backward"); fill() },
                        ))
                }
            }
            fill()
            negative(str(R.string.docsdk_edit_close))
        }
    }

    /** The show script with the edits made here (animations just added play in the slideshow). */
    fun showScript(): List<com.wxiwei.office.editor.pptx.SlideScript> = session.showScript()

    // ---- animations ------------------------------------------------------------------------

    private fun effectName(e: SlideEffect): String {
        val name = when (e.effect) {
            SlideEffect.Effect.APPEAR -> if (e.kind == SlideEffect.Kind.EXIT) str(R.string.docsdk_edit_disappear) else str(R.string.docsdk_edit_appear)
            SlideEffect.Effect.FADE -> str(R.string.docsdk_edit_fade)
            SlideEffect.Effect.FLY -> str(if (e.kind == SlideEffect.Kind.EXIT) R.string.docsdk_edit_fly_out_from else R.string.docsdk_edit_fly_in_from, directionName(e.direction))
            SlideEffect.Effect.ZOOM -> if (e.kind == SlideEffect.Kind.EXIT) str(R.string.docsdk_edit_zoom_out) else str(R.string.docsdk_edit_zoom_in)
            SlideEffect.Effect.WIPE -> str(R.string.docsdk_edit_wipe_from, directionName(e.direction))
            SlideEffect.Effect.PULSE -> str(R.string.docsdk_edit_pulse)
            SlideEffect.Effect.OTHER -> str(R.string.docsdk_edit_other_effect)
        }
        val kind = when (e.kind) { SlideEffect.Kind.ENTRANCE -> str(R.string.docsdk_edit_kind_entrance); SlideEffect.Kind.EMPHASIS -> str(R.string.docsdk_edit_kind_emphasis); SlideEffect.Kind.EXIT -> str(R.string.docsdk_edit_kind_exit) }
        val start = when (e.start) { SlideEffect.Start.CLICK -> str(R.string.docsdk_edit_start_on_tap); SlideEffect.Start.WITH -> str(R.string.docsdk_edit_start_with); SlideEffect.Start.AFTER -> str(R.string.docsdk_edit_start_after) }
        return "#${e.shapeId} $name ($kind) — $start, ${"%.1f".format(java.util.Locale.ROOT, e.durationMs / 1000f)} s"
    }

    private fun directionName(d: SlideEffect.Direction) = when (d) {
        SlideEffect.Direction.LEFT -> str(R.string.docsdk_edit_from_left); SlideEffect.Direction.RIGHT -> str(R.string.docsdk_edit_from_right)
        SlideEffect.Direction.TOP -> str(R.string.docsdk_edit_from_top); SlideEffect.Direction.BOTTOM -> str(R.string.docsdk_edit_from_bottom)
    }

    /** The slide's animations in play order: remove, move up/down, or add one to the selected shape. */
    private fun editEffects() {
        val index = slide()
        fun apply(list: List<SlideEffect>): Boolean {
            if (session.setSlideEffects(index, list)) return true
            toast(session.lastError?.message ?: str(R.string.docsdk_edit_effects_save_failed))
            return false
        }
        if (appAnswers(EditRequest.Animations(running, str(R.string.docsdk_edit_effects_title, index + 1), index, shapeId,
                read = { session.slideEffects(index) }, write = { apply(it) }))) return
        dialogs.show(str(R.string.docsdk_edit_effects_title, index + 1)) {
            fun fill() {
                clear()
                val list = session.slideEffects(index)
                if (list.isEmpty()) text(str(R.string.docsdk_edit_no_effects))
                list.forEachIndexed { i, e ->
                    row("${i + 1}. " + effectName(e), bold = e.shapeId == shapeId, actions = listOf(
                        DialogKit.Action("▲", str(R.string.docsdk_edit_play_earlier, i + 1)) { if (i > 0) apply(list.toMutableList().apply { add(i - 1, removeAt(i)) }); fill() },
                        DialogKit.Action("▼", str(R.string.docsdk_edit_play_later, i + 1)) { if (i < list.lastIndex) apply(list.toMutableList().apply { add(i + 1, removeAt(i)) }); fill() },
                        DialogKit.Action("✕", str(R.string.docsdk_edit_delete_effect, i + 1)) { apply(list.toMutableList().apply { removeAt(i) }); fill() },
                    ))
                }
            }
            fill()
            positive(str(R.string.docsdk_edit_add_effect_to_selected)) {
                if (shapeId < 0) toast(str(R.string.docsdk_edit_select_shape_first_hint))
                else askEffect(index, shapeId) { e -> apply(session.slideEffects(index) + e); editEffects() }
            }
            negative(str(R.string.docsdk_edit_close))
        }
    }

    /** Kind, effect, direction, start and length of a new effect for shape [id]. */
    private fun askEffect(index: Int, id: Int, done: (SlideEffect) -> Unit) {
        val kinds = listOf(SlideEffect.Kind.ENTRANCE, SlideEffect.Kind.EMPHASIS, SlideEffect.Kind.EXIT)
        val effectsOf = mapOf(
            SlideEffect.Kind.ENTRANCE to listOf(str(R.string.docsdk_edit_appear) to SlideEffect.Effect.APPEAR, str(R.string.docsdk_edit_fade) to SlideEffect.Effect.FADE, str(R.string.docsdk_edit_fly_in) to SlideEffect.Effect.FLY, str(R.string.docsdk_edit_zoom_in) to SlideEffect.Effect.ZOOM, str(R.string.docsdk_edit_wipe) to SlideEffect.Effect.WIPE),
            SlideEffect.Kind.EMPHASIS to listOf(str(R.string.docsdk_edit_pulse) to SlideEffect.Effect.PULSE),
            SlideEffect.Kind.EXIT to listOf(str(R.string.docsdk_edit_disappear) to SlideEffect.Effect.APPEAR, str(R.string.docsdk_edit_fade) to SlideEffect.Effect.FADE, str(R.string.docsdk_edit_fly_out) to SlideEffect.Effect.FLY, str(R.string.docsdk_edit_zoom_out) to SlideEffect.Effect.ZOOM, str(R.string.docsdk_edit_wipe) to SlideEffect.Effect.WIPE),
        )
        val directions = listOf(SlideEffect.Direction.LEFT, SlideEffect.Direction.RIGHT, SlideEffect.Direction.TOP, SlideEffect.Direction.BOTTOM)
        val starts = listOf(SlideEffect.Start.CLICK, SlideEffect.Start.WITH, SlideEffect.Start.AFTER)
        val lengths = listOf(250, 500, 1000, 2000)
        dialogs.show(str(R.string.docsdk_edit_add_effect_title, id)) {
            caption(str(R.string.docsdk_edit_effect_kind))
            val kindGroup = choices(listOf(str(R.string.docsdk_edit_appear), str(R.string.docsdk_edit_emphasis), str(R.string.docsdk_edit_disappear)), 0, horizontal = true)
            caption(str(R.string.docsdk_edit_animations))
            // one group per kind; the one of the kind picked shows
            val effectGroups = kinds.map { k -> choices(effectsOf.getValue(k).map { it.first }, if (k == SlideEffect.Kind.EMPHASIS) 0 else 1) }
            fun showKind(k: Int) = effectGroups.forEachIndexed { i, g -> g.group.visibility = if (i == k) View.VISIBLE else View.GONE }
            showKind(0)
            kindGroup.onChange { showKind(it) }
            caption(str(R.string.docsdk_edit_effect_direction))
            val directionGroup = choices(listOf(str(R.string.docsdk_edit_left), str(R.string.docsdk_edit_right), str(R.string.docsdk_edit_top), str(R.string.docsdk_edit_bottom)), 3, horizontal = true)
            caption(str(R.string.docsdk_edit_effect_start))
            val startGroup = choices(listOf(str(R.string.docsdk_edit_on_tap), str(R.string.docsdk_edit_with_previous), str(R.string.docsdk_edit_after_previous)), 0, horizontal = true)
            caption(str(R.string.docsdk_edit_duration))
            val lengthGroup = choices(listOf(str(R.string.docsdk_edit_seconds_quarter), str(R.string.docsdk_edit_seconds_half), "1 s", "2 s"), 1, horizontal = true)
            positive(str(R.string.docsdk_edit_add)) {
                val k = kindGroup.picked.coerceAtLeast(0)
                val kind = kinds[k]
                val effect = effectsOf.getValue(kind)[effectGroups[k].picked.coerceAtLeast(0)].second
                done(SlideEffect(id, kind, effect, directions[directionGroup.picked.coerceAtLeast(0)], starts[startGroup.picked.coerceAtLeast(0)],
                    if (effect == SlideEffect.Effect.APPEAR) 0 else lengths[lengthGroup.picked.coerceAtLeast(0)]))
            }
            negative()
        }
    }

    /** How the slide comes in during the slideshow, for this slide or all of them. */
    private fun editTransition() {
        val index = slide()
        val now = session.slideTransition(index)
        fun apply(f: TransitionFormat) {
            if (f.type !in TRANSITION_TYPES || f.direction !in listOf(null, "l", "r", "u", "d") || f.durationMs < 0) return toast(str(R.string.docsdk_edit_invalid_value))
            val t = if (f.type == "none" && f.advanceAfterMs == null) null
                else SlideTransition(f.type, f.direction, f.durationMs, f.advanceAfterMs)
            val slides = if (f.allSlides) (0 until session.slideCount()).toList() else listOf(index)
            if (!session.setSlideTransition(slides, t)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_transition_failed))
            else toast(if (f.allSlides) str(R.string.docsdk_edit_set_for_slides, slides.size) else str(R.string.docsdk_edit_set_for_slide, index + 1))
        }
        val (given, value) = takePreset()
        if (given) return (value as? TransitionFormat)?.let { apply(it) } ?: toast(str(R.string.docsdk_edit_invalid_value))
        val current = now?.let { TransitionFormat(it.type, it.direction, it.durationMs, it.advanceAfterMs) }
        if (appAnswers(EditRequest.Transition(running, str(R.string.docsdk_edit_transition_title, index + 1), current) { apply(it) })) return
        val types = listOf("none" to str(R.string.docsdk_edit_none), "fade" to str(R.string.docsdk_edit_fade), "push" to str(R.string.docsdk_edit_push), "wipe" to str(R.string.docsdk_edit_wipe), "cover" to str(R.string.docsdk_edit_cover), "pull" to str(R.string.docsdk_edit_pull), "split" to str(R.string.docsdk_edit_split), "zoom" to str(R.string.docsdk_edit_zoom_in), "cut" to str(R.string.docsdk_edit_cut))
        val dirs = listOf("l" to str(R.string.docsdk_edit_to_left), "r" to str(R.string.docsdk_edit_to_right), "u" to str(R.string.docsdk_edit_up), "d" to str(R.string.docsdk_edit_down))
        val lengths = listOf(500, 750, 1000, 2000)
        val autos = listOf<Int?>(null, 3000, 5000, 10000, 20000)
        dialogs.show(str(R.string.docsdk_edit_transition_title, index + 1)) {
            caption(str(R.string.docsdk_edit_transition_type))
            val typeGroup = choices(types.map { it.second }, types.indexOfFirst { it.first == (now?.type ?: "none") }.coerceAtLeast(0))
            caption(str(R.string.docsdk_edit_transition_direction))
            val dirGroup = choices(dirs.map { it.second }, dirs.indexOfFirst { it.first == now?.direction }.coerceAtLeast(0), horizontal = true)
            caption(str(R.string.docsdk_edit_duration))
            val lengthGroup = choices(listOf(str(R.string.docsdk_edit_seconds_half), "0,75 s", "1 s", "2 s"), lengths.indexOfFirst { it >= (now?.durationMs ?: 750) }.let { if (it < 0) 3 else it }, horizontal = true)
            caption(str(R.string.docsdk_edit_advance_after))
            val autoGroup = choices(listOf(str(R.string.docsdk_edit_off), "3 s", "5 s", "10 s", "20 s"), autos.indexOf(now?.advanceAfterMs).coerceAtLeast(0), horizontal = true)
            val all = check(str(R.string.docsdk_edit_apply_to_all_slides))
            positive(str(R.string.docsdk_edit_apply)) {
                apply(TransitionFormat(types[typeGroup.picked.coerceAtLeast(0)].first, dirs[dirGroup.picked.coerceAtLeast(0)].first,
                    lengths[lengthGroup.picked.coerceAtLeast(0)], autos[autoGroup.picked.coerceAtLeast(0)], all.isChecked))
            }
            negative()
        }
    }

    /** Z-order move of a shape; the selection frame stays on the selected shape. */
    private fun reorder(id: Int, where: String): Boolean {
        if (session.reorderShape(slide(), id, where)) return true
        toast(session.lastError?.message ?: str(R.string.docsdk_edit_reorder_failed))
        return false
    }

    private fun setText() {
        if (shapeId < 0) return toast(str(R.string.docsdk_edit_select_shape_first))
        stopInline(commit = true)
        askText(str(R.string.docsdk_edit_set_text), str(R.string.docsdk_edit_shape_text_hint), shapeText) { value ->
            if (!session.setShapeText(slide(), shapeId, value)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_text_change_failed))
            else {
                shapeText = value
                reopenHint()
            }
        }
    }

    /** B/I/U turn off when the shape's text already has them. */
    private fun toggle(next: (TextFormat) -> TextFormat) {
        if (shapeId < 0) return toast(str(R.string.docsdk_edit_select_shape_first))
        if (inline != null) inlineRange()?.let { r -> formatRange(r.first, r.last + 1, next); return }
        format(next(session.textFormatOf(slide(), shapeId) ?: TextFormat()))
    }

    /** [f] on the part selected while typing in the shape, or else on all its text. */
    private fun format(f: TextFormat) {
        if (shapeId < 0) return toast(str(R.string.docsdk_edit_select_shape_first))
        if (inline != null) inlineRange()?.let { r -> formatRange(r.first, r.last + 1) { f }; return }
        if (!session.setTextFormat(slide(), shapeId, f)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_shape_no_text))
        else reopenHint()
    }

    private fun move(dx: Int, dy: Int) {
        val r = rect ?: return toast(str(R.string.docsdk_edit_select_shape_first))
        val size = session.slideSizeEmu()
        val step = maxOf(size.width, size.height) / 100 // 1% of the slide per tap
        setRect(Rect(r.x + dx * step, r.y + dy * step, r.width, r.height))
    }

    private fun rotate(degrees: Float) {
        if (shapeId < 0) return
        if (!session.rotateShape(slide(), shapeId, degrees)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_rotate_failed))
        else {
            overlay.shapeRotation = degrees
            reopenHint()
        }
    }

    /** Moves or resizes the selected shape. */
    private fun setRect(moved: Rect) {
        if (shapeId < 0) return
        if (!session.moveShape(slide(), shapeId, moved)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_move_failed))
        else {
            rect = moved
            overlay.selection = moved
            reopenHint()
        }
    }

    private fun delete() {
        stopInline(commit = false)
        if (shapeId < 0) return toast(str(R.string.docsdk_edit_select_shape_first))
        if (!session.deleteShape(slide(), shapeId)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_delete_failed))
        else {
            select(null)
            status = str(R.string.docsdk_edit_deleted)
            reopenHint()
        }
    }

    /** Picks a picture from the device and puts it in the middle of the slide, half its width at most. */
    private fun pickImage() {
        pickPicture { uri -> addImage(uri) }
    }

    private fun addImage(uri: android.net.Uri) {
        val image = pictureFile(uri, "slide-image-") ?: return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return toast(str(R.string.docsdk_edit_picture_unreadable))
        val size = session.slideSizeEmu()
        // half the slide width, or less so it fits the height
        var w = size.width / 2
        var h = w * bounds.outHeight / bounds.outWidth
        if (h > size.height * 4 / 5) { h = size.height * 4 / 5; w = h * bounds.outWidth / bounds.outHeight }
        val rect = Rect((size.width - w) / 2, (size.height - h) / 2, w, h)
        val id = session.addImage(slide(), rect, image)
        if (id < 0) return toast(session.lastError?.message ?: str(R.string.docsdk_edit_picture_add_failed))
        shapeId = id
        this.rect = rect
        overlay.keepAspect = true
        overlay.slideIndex = slide()
        overlay.selection = rect
        status = str(R.string.docsdk_edit_new_picture, id)
        reopenHint()
    }

    /** A basic shape in the middle of the slide, in a color to pick (its outline a darker shade), selected. */
    private fun askNewShape() {
        val shapes = listOf(str(R.string.docsdk_edit_shape_rect) to "rect", str(R.string.docsdk_edit_shape_round_rect) to "roundRect", str(R.string.docsdk_edit_shape_ellipse) to "ellipse", str(R.string.docsdk_edit_shape_triangle) to "triangle", str(R.string.docsdk_edit_shape_arrow) to "rightArrow", str(R.string.docsdk_edit_shape_line) to "line")
        pickOne(str(R.string.docsdk_edit_add_shape_title), shapes) { prst ->
            pickColor(if (prst == "line") str(R.string.docsdk_edit_line_color) else str(R.string.docsdk_edit_fill_color)) { c -> c?.let { addShape(prst, it) } }
        }
    }

    private fun addShape(prst: String, color: String) {
        stopInline(commit = true)
        val size = session.slideSizeEmu()
        val line = prst == "line"
        val rect = if (line) Rect(size.width / 3, size.height / 2, size.width / 3, 0)
            else Rect(size.width * 3 / 8, size.height * 3 / 8, size.width / 4, size.height / 4)
        // Office outlines a filled shape in its color, 25 % darker
        val c = color.toInt(16)
        val darker = "%02X%02X%02X".format((c shr 16 and 255) * 3 / 4, (c shr 8 and 255) * 3 / 4, (c and 255) * 3 / 4)
        val id = session.addShape(slide(), rect, prst, if (line) null else color, if (line) color else darker, if (line) 2f else 1.5f)
        if (id < 0) return toast(session.lastError?.message ?: str(R.string.docsdk_edit_shape_add_failed))
        shapeId = id
        this.rect = rect
        overlay.slideIndex = slide()
        overlay.selection = rect
        status = str(R.string.docsdk_edit_new_shape, id)
        reopenHint()
    }

    private fun addTextBox() {
        val (given, value) = takePreset()
        val content = (if (given) value?.toString() else null)?.ifBlank { null } ?: "Text box"
        val size = session.slideSizeEmu()
        val rect = Rect(size.width / 4, size.height * 2 / 5, size.width / 2, size.height / 5)
        val id = session.addTextBox(slide(), rect, content, sizePt = 32f)
        if (id < 0) toast(session.lastError?.message ?: str(R.string.docsdk_edit_add_failed)) else {
            shapeId = id
            this.rect = rect
            overlay.slideIndex = slide()
            overlay.selection = rect
            status = "#$id text: $content"
            reopenHint()
        }
    }

    /** A slide change, shown at once by reopening a working copy; [show] is the slide to go to. */
    private fun slideOp(failed: Int, show: (Int) -> Int = { it }, op: () -> Boolean) {
        stopInline(commit = true)
        val at = slide()
        // the document before the change: the opened file, or with this session's edits written out
        val start = if (session.canUndo()) keep(working) else null
        val before = if (start == null) keep(working) else workingCopy().takeIf { session.copyTo(it) is EditResult.Ok }
        if (!op()) {
            listOfNotNull(start, before).forEach { drop(it) }
            return toast(session.lastError?.message ?: str(failed))
        }
        start?.let { undoPoints += Point(it, at) }
        before?.let { undoPoints += Point(it, at) }
        redoPoints.toList().also { redoPoints.clear() }.forEach { drop(it.file) }
        reloadWorking(show(at).coerceIn(0, maxOf(0, session.slideCount() - 1)))
    }

    /** [f] kept as an undo point: the original is copied, Save writes over it. */
    private fun keep(f: File): File = if (f != file) f else workingCopy().also { file.copyTo(it, overwrite = true) }

    /** Deletes [f] unless it is still shown, the original, or an undo/redo point. */
    private fun drop(f: File) {
        if (f == working || f == file || (undoPoints + redoPoints).any { it.file == f }) return
        f.delete()
    }

    /** Undo or redo of a slide change: back to the last point of [from]; the document now goes to [to]. */
    private fun goTo(from: MutableList<Point>, to: MutableList<Point>) {
        stopInline(commit = true)
        val point = from.removeAt(from.lastIndex)
        // no edit since the document was opened (see canRedoPoint), so the opened file is the state now
        to += Point(keep(working), slide())
        show(point.file, point.slide.coerceAtLeast(0))
    }

    /**
     * Writes every edit to a new working copy in the cache and shows it: the original file is only
     * written by Save.
     */
    private fun reloadWorking(slideIndex: Int) {
        select(null)
        val next = workingCopy()
        val result = session.save(next)
        if (result !is EditResult.Ok) return report(result, "")
        show(next, slideIndex)
    }

    /** Shows [next], a working copy, at [slideIndex], with a new session on it. */
    private fun show(next: File, slideIndex: Int) {
        select(null)
        val pick = pendingSelect
        pendingSelect = -1
        val previous = working
        working = next
        workingChanged = true
        reopen(next) {
            session = LivePptxSession(reader.control!!, next)
            drop(previous)
            // the slides are read in the background and a jump past those read yet is dropped
            kotlinx.coroutines.withTimeoutOrNull(10_000) { reader.state.first { it.pageNumber >= 1 && it.pageCount > slideIndex } }
            (docView() as? Presentation)?.showSlide(slideIndex, false)
            // the viewer reports the slide once it draws it
            kotlinx.coroutines.withTimeoutOrNull(2_000) { reader.state.first { it.pageNumber == slideIndex + 1 } }
            // slides were added, removed or reordered: every thumbnail may show another slide now
            reader.invalidateThumbnails()
            // a pasted shape is selected, ready to move, once the slide's page is laid out to frame it
            if (pick >= 0) session.listShapes(slideIndex).firstOrNull { it.id == pick }?.let { pasted ->
                kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    while ((docView() as? Presentation)?.let { SlideGeometry.emuToView(it, pasted.rectEmu) } == null) kotlinx.coroutines.delay(50)
                }
                select(pasted)
            }
        }
    }

    // ---- copy and paste of shapes ----

    private val clipboard get() = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    /** The shape last copied here, the text put on the clipboard for it, and the slide it came from. */
    private var shapeClip: com.wxiwei.office.editor.pptx.PptxEditor.ShapeClip? = null
    private var shapeClipText = ""
    private var shapeClipSlide = -1
    // the shape to select once the document is shown again (a pasted one)
    private var pendingSelect = -1

    /** Copies the selected shape (text box, picture, shape, table, group); other apps get its text. False when nothing was. */
    private fun copyShape(): Boolean {
        inline?.let { it.onTextContextMenuItem(android.R.id.copy); return true }
        if (shapeId < 0) { toast(str(R.string.docsdk_edit_select_shape_first)); return false }
        val clip = session.copyShape(slide(), shapeId) ?: run {
            toast(session.lastError?.message ?: str(R.string.docsdk_edit_failed)); return false
        }
        shapeClip = clip
        shapeClipSlide = slide()
        // other apps get the shape's text; a shape without text still needs something to match on paste
        shapeClipText = clip.text.ifEmpty { str(R.string.docsdk_edit_shape_copied, shapeId) }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("shape", shapeClipText))
        toast(str(R.string.docsdk_edit_copied))
        return true
    }

    /**
     * Pastes the copied shape on this slide (a little lower and to the right on the slide it came
     * from, so it does not hide the original) and selects it; text from another app becomes a new
     * text box. Shown by reopening the document, undone like slide changes.
     */
    private fun pasteShape() {
        inline?.let { it.onTextContextMenuItem(android.R.id.paste); return }
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return toast(str(R.string.docsdk_edit_clipboard_empty))
        val clip = shapeClip?.takeIf { text == shapeClipText }
        if (clip == null) {
            if (!has(EditFeature.SHAPES)) return
            run(EditAction.ADD_TEXT_BOX, text, true)
            return
        }
        val size = session.slideSizeEmu()
        val step = if (slide() == shapeClipSlide) maxOf(size.width, size.height) / 40 else 0L
        slideOp(R.string.docsdk_edit_paste_failed) {
            session.pasteShape(slide(), clip, step, step).also { if (it) pendingSelect = session.pastedShape }
        }
    }

    /** Edits the view cannot show at once (shapes inside groups) are shown by reopening a working copy. */
    private fun reopenHint() {
        reader.invalidateThumbnail(slide() + 1)
        if (session.needsReopen) reloadWorking(slide())
    }

    override fun hasChanges(): Boolean {
        stopInline(commit = true)
        return session.hasChanges() || workingChanged
    }

    override fun writeTo(target: File): EditResult {
        if (session.hasChanges()) return session.save(target)
        working.copyTo(target, overwrite = true)
        return EditResult.Ok(target)
    }

    override fun onSaved() {
        select(null)
        // the view already shows what was saved; edit the original from now on
        val previous = working
        working = file
        drop(previous)
        workingChanged = false
        session = LivePptxSession(reader.control!!, file)
    }

    /** Draws its text with [typeface] (TypefaceSpan(Typeface) needs API 28). */
    private class TypefaceSpan(private val typeface: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(paint: android.text.TextPaint) { paint.typeface = typeface }
        override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = typeface }
    }

    private companion object {
        val TRANSITION_TYPES = setOf("none", "fade", "push", "wipe", "cover", "pull", "split", "zoom", "cut")
    }
}

/**
 * Slide [index] of [p] into [out]: a PNG 1920 px wide, or a one-page PDF drawn as vectors, the
 * slide's size in points (the slide is laid out at 96 px per inch). False when not laid out yet.
 */
/** Slide [index] (0-based). */
fun writeSlide(p: com.wxiwei.office.pg.control.Presentation, index: Int, pdf: Boolean, out: java.io.OutputStream): Boolean {
    val size = p.getPageSize() ?: return false
    val w = size.getWidth().toInt(); val h = size.getHeight().toInt()
    if (w <= 0 || h <= 0) return false
    if (pdf) {
        val k = 72f / 96f
        val doc = android.graphics.pdf.PdfDocument()
        try {
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(Math.round(w * k), Math.round(h * k), 1).create())
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.scale(k, k)
            p.drawSlide(index + 1, page.canvas)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally { doc.close() }
    } else {
        val scale = 1920f / w
        val bitmap = android.graphics.Bitmap.createBitmap(1920, Math.round(h * scale), android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.scale(scale, scale)
            p.drawSlide(index + 1, canvas)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        } finally { bitmap.recycle() }
    }
    return true
}

/** Every slide as one page of a vector PDF; the number of slides written. */
fun writeDeckPdf(p: com.wxiwei.office.pg.control.Presentation, count: Int, out: java.io.OutputStream, progress: (Int) -> Unit = {}): Int {
    val size = p.getPageSize() ?: return 0
    val w = size.getWidth().toInt(); val h = size.getHeight().toInt()
    if (w <= 0 || h <= 0) return 0
    val k = 72f / 96f
    val doc = android.graphics.pdf.PdfDocument()
    var written = 0
    try {
        for (i in 0 until count) {
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(Math.round(w * k), Math.round(h * k), i + 1).create())
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.scale(k, k)
            if (p.drawSlide(i + 1, page.canvas)) written++
            doc.finishPage(page)
            progress(i + 1)
        }
        doc.writeTo(out)
    } finally { doc.close() }
    return written
}
