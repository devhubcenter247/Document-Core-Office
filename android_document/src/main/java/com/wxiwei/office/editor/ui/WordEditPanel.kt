package com.wxiwei.office.editor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.editor.docsdk.EditFeature
import com.editor.docsdk.EditAction
import com.editor.docsdk.EditRequest
import com.editor.docsdk.Finder
import com.editor.docsdk.FindResult
import com.editor.docsdk.LineSpacingFormat
import com.editor.docsdk.ParagraphFormat
import com.wxiwei.office.R
import com.wxiwei.office.constant.wp.WPModelConstant
import kotlinx.coroutines.launch
import com.wxiwei.office.editor.docx.LiveDocxSession
import com.wxiwei.office.editor.word.WordSelection
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.system.IMainFrame
import java.io.File

/**
 * Word: tap the text to put the caret there and type with the keyboard (Vietnamese IMEs
 * included); long-press a word to select it and drag its handles to extend the selection.
 * Formatting and text changes show at once; Save writes the .docx in place.
 */
class WordEditPanel @JvmOverloads constructor(
    activity: AppCompatActivity, reader: OfficeDocumentView, file: File, features: Set<EditFeature> = EditFeature.all(),
) : OfficeEditPanel(activity, reader, file, features) {

    private var session: LiveDocxSession? = null
    private var anchor: LongRange? = null

    // Typing: the keyboard edits [typing], a hidden buffer whose text mirrors the document from
    // [base] on; every change of the buffer is replayed on the document at base + its index.
    private var base = -1L
    private var muted = false
    private val caret = WordCaretOverlay(context, { docView() }) {
        if (base < 0) null else selection()?.caretRect(base + typing.selectionEnd.coerceAtLeast(0))
    }
    private val typing: EditText = object : EditText(context) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            if (base >= 0) caret.touch()
        }
    }.apply {
        alpha = 0f
        isCursorVisible = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (!muted && base >= 0) typed(start, before, s.subSequence(start, start + count).toString())
            }
        })
        setOnKeyListener { _, keyCode, event ->
            // Backspace with nothing typed before the caret deletes the document text before it
            // (not at the start of the body, a header, a footer or a text box: nothing is before it)
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && base > 0 &&
                (base and (WPModelConstant.AREA_MASK or WPModelConstant.TEXTBOX_MASK).inv()) > 0 &&
                selectionStart == 0 && selectionEnd == 0) {
                val s = session() ?: return@setOnKeyListener true
                if (s.deleteText(base - 1, base)) { base -= 1; caret.touch(); pagesChangedFrom(base) }
                else toast(s.lastError?.message ?: str(R.string.docsdk_edit_delete_failed))
                true
            } else false
        }
        setOnFocusChangeListener { _, focused -> if (!focused) stopTyping() }
    }

    override val tabs = listOf(
        Tab(R.string.docsdk_edit_tab_home, listOf(EditAction.BOLD, EditAction.ITALIC, EditAction.UNDERLINE, EditAction.STRIKETHROUGH,
            EditAction.TEXT_COLOR, EditAction.HIGHLIGHT, EditAction.FONT, EditAction.FONT_SIZE, EditAction.SUPERSCRIPT, EditAction.SUBSCRIPT)),
        Tab(R.string.docsdk_edit_tab_paragraph, listOf(EditAction.BULLETS, EditAction.NUMBERING, EditAction.ALIGN_LEFT, EditAction.ALIGN_CENTER,
            EditAction.ALIGN_RIGHT, EditAction.ALIGN_JUSTIFY, EditAction.INDENT_LESS, EditAction.INDENT_MORE, EditAction.LINE_SPACING, EditAction.PARAGRAPH)),
        Tab(R.string.docsdk_edit_tab_insert, listOf(EditAction.INSERT_PICTURE, EditAction.INSERT_TABLE, EditAction.NEW_LINE)),
        Tab(R.string.docsdk_edit_tab_table, listOf(EditAction.ROW_ABOVE, EditAction.ROW_BELOW, EditAction.COLUMN_LEFT, EditAction.COLUMN_RIGHT,
            EditAction.DELETE_ROW, EditAction.DELETE_COLUMN)),
        Tab(R.string.docsdk_edit_tab_select, listOf(EditAction.SELECT_WORD, EditAction.SELECT_PARAGRAPH, EditAction.SELECT_ALL, EditAction.DESELECT,
            EditAction.COPY, EditAction.CUT, EditAction.PASTE, EditAction.DELETE, EditAction.FIND_REPLACE)),
    )

    init {
        status = str(R.string.docsdk_edit_word_hint)
        action(EditAction.UNDO, EditFeature.UNDO_REDO) { stopTyping(); session?.let { if (!it.undo()) toast(str(R.string.docsdk_edit_nothing_to_undo)) } }
        action(EditAction.REDO, EditFeature.UNDO_REDO) { stopTyping(); session?.let { if (!it.redo()) toast(str(R.string.docsdk_edit_nothing_to_redo)) } }
        action(EditAction.SAVE) { save() }
        action(EditAction.SAVE_COPY, EditFeature.SAVE_COPY) { stopTyping(); saveCopy() }
        action(EditAction.BOLD, EditFeature.FORMAT) { toggle(str(R.string.docsdk_edit_bold), { e, a -> e.isBold(a) }) { e, a, b, on -> e.setBold(a, b, on) } }
        action(EditAction.ITALIC, EditFeature.FORMAT) { toggle(str(R.string.docsdk_edit_italic), { e, a -> e.isItalic(a) }) { e, a, b, on -> e.setItalic(a, b, on) } }
        action(EditAction.UNDERLINE, EditFeature.FORMAT) { toggle(str(R.string.docsdk_edit_underline), { e, a -> e.isUnderlined(a) }) { e, a, b, on -> e.setUnderline(a, b, on) } }
        action(EditAction.STRIKETHROUGH, EditFeature.FORMAT) { toggle(str(R.string.docsdk_edit_strikethrough), { e, a -> e.isStruck(a) }) { e, a, b, on -> e.setStrike(a, b, on) } }
        action(EditAction.SUPERSCRIPT, EditFeature.FORMAT) { toggle(str(R.string.docsdk_edit_superscript), { e, a -> e.isSuperscript(a) }) { e, a, b, on -> e.setScript(a, b, if (on) 1 else 0) } }
        action(EditAction.SUBSCRIPT, EditFeature.FORMAT) { toggle(str(R.string.docsdk_edit_subscript), { e, a -> e.isSubscript(a) }) { e, a, b, on -> e.setScript(a, b, if (on) 2 else 0) } }
        action(EditAction.TEXT_COLOR, EditFeature.FORMAT) { needSelection { pickColor(str(R.string.docsdk_edit_text_color)) { c -> c?.let { op { e, r -> e.setTextColor(r.first, r.last + 1, it) } } } } }
        action(EditAction.HIGHLIGHT, EditFeature.FORMAT) { needSelection { pickColor(str(R.string.docsdk_edit_highlight), none = str(R.string.docsdk_edit_highlight_none)) { c -> op { e, r -> e.highlight(r.first, r.last + 1, c ?: "none") } } } }
        action(EditAction.FONT, EditFeature.FORMAT) {
            needSelection {
                val current = selection()?.selection()?.let { session()?.fontAt(it.first) }
                pickFont(EditFonts.names, current) { name -> op { e, r -> e.setFont(r.first, r.last + 1, name) } }
            }
        }
        action(EditAction.FONT_SIZE, EditFeature.FORMAT) { needSelection { pickSize { pt -> op { e, r -> e.setFontSize(r.first, r.last + 1, pt) } } } }
        action(EditAction.BULLETS, EditFeature.PARAGRAPH) { paraOp { e, r -> e.setBullets(r.first, r.last + 1, !e.hasBullet(r.first)) } }
        action(EditAction.NUMBERING, EditFeature.PARAGRAPH) { paraOp { e, r -> e.setNumbering(r.first, r.last + 1, !e.hasNumbering(r.first)) } }
        action(EditAction.ALIGN_LEFT, EditFeature.PARAGRAPH) { paraOp { e, r -> e.setAlignment(r.first, r.last + 1, "left") } }
        action(EditAction.ALIGN_CENTER, EditFeature.PARAGRAPH) { paraOp { e, r -> e.setAlignment(r.first, r.last + 1, "center") } }
        action(EditAction.ALIGN_RIGHT, EditFeature.PARAGRAPH) { paraOp { e, r -> e.setAlignment(r.first, r.last + 1, "right") } }
        action(EditAction.ALIGN_JUSTIFY, EditFeature.PARAGRAPH) { paraOp { e, r -> e.setAlignment(r.first, r.last + 1, "both") } }
        action(EditAction.INDENT_MORE, EditFeature.PARAGRAPH) { paraOp { e, r ->
            // in a list: one level deeper, like Tab in Word
            if (e.hasBullet(r.first)) e.setListLevel(r.first, r.last + 1, minOf(8, e.listLevelAt(r.first) + 1))
            else e.setIndentLeft(r.first, r.last + 1, e.indentLeftAt(r.first) + 720)
        } }
        action(EditAction.INDENT_LESS, EditFeature.PARAGRAPH) { paraOp { e, r ->
            if (e.hasBullet(r.first)) e.setListLevel(r.first, r.last + 1, maxOf(0, e.listLevelAt(r.first) - 1))
            else e.setIndentLeft(r.first, r.last + 1, maxOf(0, e.indentLeftAt(r.first) - 720))
        } }
        action(EditAction.PARAGRAPH, EditFeature.PARAGRAPH) { askParagraph() }
        action(EditAction.LINE_SPACING, EditFeature.PARAGRAPH) { askLineSpacing() }
        action(EditAction.SELECT_WORD) { selectAround { sel, at -> sel.wordAt(at) } }
        action(EditAction.SELECT_PARAGRAPH) { selectAround { _, at -> paragraphAt(at) } }
        action(EditAction.SELECT_ALL) { selectAround { _, _ -> wholeDocument() } }
        action(EditAction.DESELECT) { clearSelection() }
        action(EditAction.COPY, EditFeature.CLIPBOARD) { copy() }
        action(EditAction.CUT, EditFeature.CLIPBOARD, EditFeature.TEXT) { copy(); op { e, r -> e.deleteText(r.first, r.last + 1) } }
        action(EditAction.PASTE, EditFeature.CLIPBOARD, EditFeature.TEXT) { paste() }
        action(EditAction.DELETE, EditFeature.TEXT) { op { e, r -> e.deleteText(r.first, r.last + 1) } }
        action(EditAction.NEW_LINE, EditFeature.TEXT) { op { e, r -> e.insertText(r.first, "\n") } }
        action(EditAction.INSERT_TEXT, EditFeature.TEXT) { insert() }
        action(EditAction.REPLACE_TEXT, EditFeature.TEXT) { replace() }
        action(EditAction.FIND_REPLACE, EditFeature.FIND_REPLACE) { findReplace() }
        action(EditAction.INSERT_PICTURE, EditFeature.PICTURES) { pickImage() }
        action(EditAction.INSERT_TABLE, EditFeature.TABLES) { askTable() }
        action(EditAction.ROW_ABOVE, EditFeature.TABLES) { insertRowOrColumn(row = true, after = false) }
        action(EditAction.ROW_BELOW, EditFeature.TABLES) { insertRowOrColumn(row = true, after = true) }
        action(EditAction.COLUMN_LEFT, EditFeature.TABLES) { insertRowOrColumn(row = false, after = false) }
        action(EditAction.COLUMN_RIGHT, EditFeature.TABLES) { insertRowOrColumn(row = false, after = true) }
        action(EditAction.DELETE_ROW, EditFeature.TABLES) { deleteRowOrColumn(row = true) }
        action(EditAction.DELETE_COLUMN, EditFeature.TABLES) { deleteRowOrColumn(row = false) }
    }

    override fun isActive(action: EditAction): Boolean {
        val s = session ?: return false
        // the selection, or the text just before the caret (what typing goes on with)
        val at = selection()?.selection()?.first ?: if (base >= 0) (base + typing.selectionEnd.coerceAtLeast(0) - 1).coerceAtLeast(0) else return false
        fun style(name: Int, now: (LiveDocxSession, Long) -> Boolean) = pending[str(name)]?.first ?: now(s, at)
        return runCatching {
            when (action) {
                EditAction.BOLD -> style(R.string.docsdk_edit_bold) { e, a -> e.isBold(a) }
                EditAction.ITALIC -> style(R.string.docsdk_edit_italic) { e, a -> e.isItalic(a) }
                EditAction.UNDERLINE -> style(R.string.docsdk_edit_underline) { e, a -> e.isUnderlined(a) }
                EditAction.STRIKETHROUGH -> style(R.string.docsdk_edit_strikethrough) { e, a -> e.isStruck(a) }
                EditAction.SUPERSCRIPT -> style(R.string.docsdk_edit_superscript) { e, a -> e.isSuperscript(a) }
                EditAction.SUBSCRIPT -> style(R.string.docsdk_edit_subscript) { e, a -> e.isSubscript(a) }
                EditAction.BULLETS -> s.hasBullet(at)
                EditAction.NUMBERING -> s.hasNumbering(at)
                else -> false
            }
        }.getOrDefault(false)
    }

    // the file the view and the session work on: the original, or a working copy in the cache after
    // an edit that needed a reopen (a picture, a table); Save writes it over the original
    private var working = file
    private var workingChanged = false

    /** Where the caret or the selection starts, or -1. */
    private fun here(): Long = selection()?.selection()?.first ?: if (base >= 0) base + typing.selectionStart.coerceAtLeast(0) else -1L

    /** [action] on the selected text; with only the caret in a word, on that word (like Word). */
    private fun needSelection(action: () -> Unit) {
        val sel = selection() ?: return
        if (sel.selection() == null && base >= 0) {
            val caretAt = base + typing.selectionEnd.coerceAtLeast(0)
            val word = sel.wordAt(caretAt)
            if (!word.isEmpty() && caretAt >= word.first && caretAt <= word.last + 1) {
                stopTyping()
                anchor = word
                select(sel, word)
            }
        }
        if (sel.selection() == null) return toast(str(R.string.docsdk_edit_select_text_first_hint))
        action()
    }

    /** Selects the range [pick] gives around the caret (or the current selection). */
    private fun selectAround(pick: (WordSelection, Long) -> LongRange) {
        val sel = selection() ?: return
        val at = here()
        if (at < 0) return toast(str(R.string.docsdk_edit_tap_text_first))
        stopTyping()
        val range = pick(sel, at)
        if (range.isEmpty()) return toast(str(R.string.docsdk_edit_no_text_here))
        anchor = range
        select(sel, range)
    }

    private fun paragraphAt(offset: Long): LongRange {
        val w = docView() as? com.wxiwei.office.wp.control.Word ?: return LongRange.EMPTY
        val p = w.getDocument().getParagraph(offset) ?: return LongRange.EMPTY
        // without its paragraph mark
        return p.getStartOffset() until maxOf(p.getStartOffset(), p.getEndOffset() - 1)
    }

    private fun wholeDocument(): LongRange {
        val w = docView() as? com.wxiwei.office.wp.control.Word ?: return LongRange.EMPTY
        return 0L until maxOf(0L, w.getDocument().getAreaEnd(0) - 1)
    }

    private fun pickImage() {
        val at = here()
        if (at < 0) return toast(str(R.string.docsdk_edit_tap_picture_place_first))
        pickPicture { uri -> insertImage(at, uri) }
    }

    private fun insertImage(at: Long, uri: android.net.Uri) {
        val image = pictureFile(uri, "word-image-") ?: return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return toast(str(R.string.docsdk_edit_picture_unreadable))
        // at most 6 inches wide (576 px at 96 dpi), keeping its proportions
        val w = minOf(576, bounds.outWidth)
        val h = maxOf(1, w * bounds.outHeight / bounds.outWidth)
        val s = session() ?: return
        stopTyping()
        val shown = !s.needsReopen
        if (!s.insertImage(at, image, w, h)) return toast(s.lastError?.message ?: str(R.string.docsdk_edit_picture_insert_failed))
        if (shown && s.needsReopen) return reloadWorking()
        // shown at once: the caret after it
        pagesChangedFrom(at)
        startTyping(at + 1)
    }

    /**
     * A new empty row above/below ([after]) or column left/right of the cell with the caret (or
     * the first cell of the selected table). The working copy is reopened (a new cell has no place
     * in the file yet), the view stays where it was and the caret goes into the new cell.
     */
    private fun insertRowOrColumn(row: Boolean, after: Boolean) {
        val s = session() ?: return
        val at = here().takeIf { it >= 0 } ?: tableAt?.first ?: -1L
        val place = if (at >= 0) s.cellAt(at) else null
        if (place == null) return toast(str(R.string.docsdk_edit_tap_table_cell_first))
        stopTyping()
        clearPicture()
        val shown = !s.needsReopen
        val ok = if (row) s.insertTableRow(at, after) else s.insertTableColumn(at, after)
        // next to a cell added just now: save the working copy first (it has no place in the file yet), then again
        // the same text is read back: the caret goes back to [at], then again
        if (!ok && s.needsFlush) return reloadWorking { startTyping(at); insertRowOrColumn(row, after) }
        if (!ok) return toast(s.lastError?.message ?: if (row) str(R.string.docsdk_edit_row_add_failed) else str(R.string.docsdk_edit_column_add_failed))
        // the new cell: same column in the new row, or the new column in the same row
        val (r, c) = if (row) (if (after) place.row + 1 else place.row) to place.cell
            else place.row to (if (after) place.cell + place.span else place.cell)
        val typeThere = { session()?.cellStart(place.table, r, c)?.takeIf { it >= 0 }?.let { startTyping(it) } }
        pagesChangedFrom(at)
        // shown at once (a table without merged cells); otherwise the file is read again
        if (shown && s.needsReopen) reloadWorking { typeThere() } else typeThere()
    }

    /**
     * Removes the row or the column of the cell with the caret (or the first cell of the selected
     * table); reopened like [insertRowOrColumn], with the caret in the cell that takes its place.
     */
    private fun deleteRowOrColumn(row: Boolean) {
        val s = session() ?: return
        val at = here().takeIf { it >= 0 } ?: tableAt?.first ?: -1L
        val place = if (at >= 0) s.cellAt(at) else null
        if (place == null) return toast(str(R.string.docsdk_edit_tap_table_cell_first))
        stopTyping()
        clearPicture()
        val shown = !s.needsReopen
        val ok = if (row) s.deleteTableRow(at) else s.deleteTableColumn(at)
        if (!ok && s.needsFlush) return reloadWorking { startTyping(at); deleteRowOrColumn(row) }
        if (!ok) return toast(s.lastError?.message ?: if (row) str(R.string.docsdk_edit_row_delete_failed) else str(R.string.docsdk_edit_column_delete_failed))
        pagesChangedFrom(at)
        val caretBack: () -> Unit = {
            val t = session()
            // the row now in its place (or the one above, for the last row); the column before it
            val tries = if (row) listOf(place.row to place.cell, place.row - 1 to place.cell, place.row to 0, place.row - 1 to 0)
                else listOf(place.row to place.cell - 1, place.row to place.cell, place.row to 0)
            tries.firstNotNullOfOrNull { (r, c) -> if (r < 0 || c < 0 || t == null) null else t.cellStart(place.table, r, c).takeIf { it >= 0 } }?.let { startTyping(it) }
        }
        if (shown && s.needsReopen) reloadWorking { caretBack() } else caretBack()
    }

    /**
     * Word's Paragraph dialog: alignment, left/right indent and first-line / hanging indent (cm),
     * space before/after (pt), starting from the paragraph at the caret; one undoable step.
     */
    private fun askParagraph() {
        val s = session() ?: return
        val at = here()
        if (at < 0) return toast(str(R.string.docsdk_edit_tap_paragraph_first))
        val now = s.paragraphLayoutAt(at)
        val twipsPerCm = 1440f / 2.54f
        fun twips(cm: Float) = Math.round(cm * twipsPerCm)
        val current = ParagraphFormat(now.align, now.leftTwips / twipsPerCm, now.rightTwips / twipsPerCm, now.specialTwips / twipsPerCm, now.beforePt, now.afterPt)
        fun apply(f: ParagraphFormat) {
            if (f.alignment !in listOf("left", "center", "right", "both") || f.spaceBeforePt < 0f || f.spaceAfterPt < 0f) return toast(str(R.string.docsdk_edit_invalid_value))
            val layout = LiveDocxSession.ParagraphLayout(f.alignment, twips(f.leftIndentCm), twips(f.rightIndentCm), twips(f.firstLineCm), f.spaceBeforePt, f.spaceAfterPt)
            if (layout != now) paraOp { e, rg -> e.setParagraphLayout(rg.first, rg.last + 1, layout) }
        }
        val (given, value) = takePreset()
        if (given) return (value as? ParagraphFormat)?.let { apply(it) } ?: toast(str(R.string.docsdk_edit_invalid_value))
        if (appAnswers(EditRequest.Paragraph(running, str(R.string.docsdk_edit_paragraph), current) { apply(it) })) return
        fun cm(tw: Int) = "%.2f".format(java.util.Locale.ROOT, tw / twipsPerCm).trimEnd('0').trimEnd('.')
        fun num(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)
        val aligns = listOf(str(R.string.docsdk_edit_left) to "left", str(R.string.docsdk_edit_center) to "center", str(R.string.docsdk_edit_right) to "right", str(R.string.docsdk_edit_justify) to "both")
        dialogs.show(str(R.string.docsdk_edit_paragraph)) {
            caption(str(R.string.docsdk_edit_alignment))
            val alignGroup = choices(aligns.map { it.first }, aligns.indexOfFirst { it.second == now.align })
            caption(str(R.string.docsdk_edit_indents_cm))
            val left = input(str(R.string.docsdk_edit_indent_left_cm), cm(now.leftTwips), numeric = true)
            val right = input(str(R.string.docsdk_edit_indent_right_cm), cm(now.rightTwips), numeric = true)
            caption(str(R.string.docsdk_edit_indent_special))
            val specialGroup = choices(listOf(str(R.string.docsdk_edit_none), str(R.string.docsdk_edit_indent_first_line), str(R.string.docsdk_edit_indent_hanging)), when { now.specialTwips > 0 -> 1; now.specialTwips < 0 -> 2; else -> 0 }, horizontal = true)
            val special = input(str(R.string.docsdk_edit_indent_special_cm), cm(Math.abs(now.specialTwips)), numeric = true)
            special.isEnabled = now.specialTwips != 0
            specialGroup.onChange { special.isEnabled = it != 0 }
            caption(str(R.string.docsdk_edit_spacing_pt))
            val before = input(str(R.string.docsdk_edit_space_before), num(now.beforePt), numeric = true)
            val after = input(str(R.string.docsdk_edit_space_after), num(now.afterPt), numeric = true)
            positive(str(R.string.docsdk_edit_apply)) {
                fun f(e: android.widget.EditText) = e.text.toString().replace(',', '.').ifBlank { "0" }.toFloatOrNull()
                val l = f(left); val r = f(right); val sp = f(special); val b = f(before); val a = f(after)
                if (l == null || r == null || sp == null || b == null || a == null || sp < 0f) return@positive toast(str(R.string.docsdk_edit_invalid_value))
                apply(ParagraphFormat(
                    alignment = aligns.getOrNull(alignGroup.picked)?.second ?: now.align,
                    leftIndentCm = l, rightIndentCm = r,
                    firstLineCm = when (specialGroup.picked) { 1 -> sp; 2 -> -sp; else -> 0f },
                    spaceBeforePt = b, spaceAfterPt = a,
                ))
            }
            negative()
        }
    }

    /**
     * Line spacing of the paragraphs at the caret (or selected): 1.0 / 1.15 / 1.5 / 2.0, a multiple,
     * exactly or at least some points; and the space before and after them. Filled with what they have.
     */
    private fun askLineSpacing() {
        val s = session() ?: return
        val at = here()
        if (at < 0) return toast(str(R.string.docsdk_edit_tap_paragraph_first))
        val (kind, value) = s.lineSpacingAt(at)
        val (before, after) = s.paragraphSpacingAt(at)
        val rule = when (kind) {
            com.wxiwei.office.constant.wp.WPAttrConstant.LINE_SPACE_EXACTLY.toInt() -> LineSpacingFormat.Rule.EXACTLY
            com.wxiwei.office.constant.wp.WPAttrConstant.LINE_SAPCE_LEAST.toInt() -> LineSpacingFormat.Rule.AT_LEAST
            else -> LineSpacingFormat.Rule.MULTIPLE
        }
        fun apply(f: LineSpacingFormat) {
            if (f.value <= 0f || f.spaceBeforePt < 0f || f.spaceAfterPt < 0f) return toast(str(R.string.docsdk_edit_invalid_value))
            paraOp { e, r ->
                val lineOk = if (f.rule == LineSpacingFormat.Rule.MULTIPLE) e.setLineSpacing(r.first, r.last + 1, f.value)
                    else e.setLineSpacingPoints(r.first, r.last + 1, f.value, exactly = f.rule == LineSpacingFormat.Rule.EXACTLY)
                lineOk && (f.spaceBeforePt == before && f.spaceAfterPt == after || e.setParagraphSpacing(r.first, r.last + 1, f.spaceBeforePt, f.spaceAfterPt))
            }
        }
        val (given, preset) = takePreset()
        if (given) return (preset as? LineSpacingFormat)?.let { apply(it) } ?: toast(str(R.string.docsdk_edit_invalid_value))
        if (appAnswers(EditRequest.LineSpacing(running, str(R.string.docsdk_edit_line_spacing), LineSpacingFormat(rule, value, before, after)) { apply(it) })) return
        val labels = listOf("1.0", "1.15", "1.5", "2.0", str(R.string.docsdk_edit_spacing_multiple), str(R.string.docsdk_edit_spacing_exactly), str(R.string.docsdk_edit_spacing_at_least))
        fun num(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        // what the paragraph has now
        val presets = listOf(1f, 1.15f, 1.5f, 2f)
        val checked = when (rule) {
            LineSpacingFormat.Rule.EXACTLY -> 5
            LineSpacingFormat.Rule.AT_LEAST -> 6
            else -> presets.indexOfFirst { Math.abs(it - value) < 0.01f }.let { if (it >= 0) it else 4 }
        }
        dialogs.show(str(R.string.docsdk_edit_line_spacing)) {
            val group = choices(labels, checked)
            val amount = input(str(R.string.docsdk_edit_value), num(value), numeric = true)
            amount.isEnabled = checked >= 4
            group.onChange { amount.isEnabled = it >= 4 }
            caption(str(R.string.docsdk_edit_paragraph_spacing))
            val spaceBefore = input(str(R.string.docsdk_edit_space_before), num(before), numeric = true)
            val spaceAfter = input(str(R.string.docsdk_edit_space_after), num(after), numeric = true)
            positive(str(android.R.string.ok)) {
                val pick = group.picked
                val v = amount.text.toString().replace(',', '.').toFloatOrNull()
                val b = spaceBefore.text.toString().replace(',', '.').toFloatOrNull() ?: before
                val a = spaceAfter.text.toString().replace(',', '.').toFloatOrNull() ?: after
                if (pick >= 4 && (v == null || v <= 0f)) return@positive toast(str(R.string.docsdk_edit_invalid_value))
                apply(when (pick) {
                    in 0..3 -> LineSpacingFormat(LineSpacingFormat.Rule.MULTIPLE, presets[pick], b, a)
                    4 -> LineSpacingFormat(LineSpacingFormat.Rule.MULTIPLE, v!!, b, a)
                    5 -> LineSpacingFormat(LineSpacingFormat.Rule.EXACTLY, v!!, b, a)
                    else -> LineSpacingFormat(LineSpacingFormat.Rule.AT_LEAST, v!!, b, a)
                })
            }
            negative()
        }
    }

    /** Find and replace in the body; [reveal] scrolls a match just selected into view. */
    private fun finder(reveal: (LongRange) -> Unit) = object : Finder {
        private fun matches(text: String, matchCase: Boolean) = session()?.find(text, matchCase).orEmpty()

        private fun next(text: String, matchCase: Boolean, from: Long): FindResult? {
            val all = matches(text, matchCase)
            val r = all.firstOrNull { it.first >= from } ?: all.firstOrNull() ?: return null
            select(selection() ?: return null, r)
            reveal(r)
            handles.refresh()
            return FindResult(all.indexOf(r) + 1, all.size)
        }

        override fun findNext(text: String, matchCase: Boolean) = next(text, matchCase, selection()?.selection()?.let { it.first + 1 } ?: 0L)

        override fun replace(text: String, replacement: String, matchCase: Boolean): FindResult? {
            val s = session() ?: return null
            val current = selection()?.selection()
            if (current == null || matches(text, matchCase).none { it == current }) return next(text, matchCase, 0L)
            if (!s.replaceText(current.first, current.last + 1, replacement)) {
                toast(s.lastError?.message ?: str(R.string.docsdk_edit_replace_failed))
                return null
            }
            pagesChangedFrom(current.first)
            return next(text, matchCase, current.first + replacement.length)
        }

        override fun replaceAll(text: String, replacement: String, matchCase: Boolean): Int {
            val s = session() ?: return 0
            val (done, skipped) = s.replaceAll(text, replacement, matchCase)
            clearSelection()
            pagesChangedFrom(0)
            if (skipped > 0) toast(str(R.string.docsdk_edit_replace_skipped, skipped))
            return done
        }
    }

    /**
     * Find and replace in the body: "Tìm tiếp" selects the next match (round), "Thay" replaces the
     * selected one and goes on, "Thay tất cả" replaces all (one undo step). The dialog sits at the
     * top and stays open; the match is scrolled into view under it.
     */
    private fun findReplace() {
        stopTyping()
        val title = str(R.string.docsdk_edit_find_replace)
        if (appAnswers(EditRequest.FindReplace(running, title, finder { r -> selection()?.revealCaret(r.first, dp(24), visibleBottom(docView() ?: return@finder)) }))) return
        var atTop = true
        val dialog = dialogs.show(title, scroll = false) {
            val find = input(str(R.string.docsdk_edit_find))
            val with = input(str(R.string.docsdk_edit_replace_with))
            val matchCase = check(str(R.string.docsdk_edit_match_case))
            val status = text("")
            val search = finder { r ->
                val sel = selection() ?: return@finder
                val word = docView() ?: return@finder
                val tall = (dialog?.window?.decorView?.height ?: 0) + dp(24)
                val top = IntArray(2).also { word.getLocationOnScreen(it) }[1]
                // the dialog covers the top of the view (it sits at the top) or its bottom
                fun coveredTop() = if (atTop) maxOf(0, tall - top) else dp(24)
                fun visibleBottom() = if (atTop) word.height else word.height - tall
                sel.revealCaret(r.first, coveredTop(), visibleBottom())
                // near the start of the document the page cannot scroll under the dialog: move it down
                val caret = sel.caretRect(r.first)
                if (atTop && caret != null && caret.top < coveredTop()) {
                    atTop = false
                    dialog?.window?.setGravity(android.view.Gravity.BOTTOM)
                    sel.revealCaret(r.first, coveredTop(), visibleBottom())
                }
            }
            fun show(found: FindResult?) {
                status.text = found?.let { str(R.string.docsdk_edit_find_result, it.number, it.count) } ?: str(R.string.docsdk_edit_not_found)
            }
            keepOpenOnButtons()
            neutral(str(R.string.docsdk_edit_find_next)) { show(search.findNext(find.text.toString(), matchCase.isChecked)) }
            positive(str(R.string.docsdk_edit_replace)) { show(search.replace(find.text.toString(), with.text.toString(), matchCase.isChecked)) }
            negative(str(R.string.docsdk_edit_replace_all)) {
                status.text = str(R.string.docsdk_edit_replaced, search.replaceAll(find.text.toString(), with.text.toString(), matchCase.isChecked))
            }
        }
        dialog.window?.setGravity(android.view.Gravity.TOP)
        dialog.window?.setDimAmount(0f)
    }

    private fun askTable() {
        val at = here()
        if (at < 0) return toast(str(R.string.docsdk_edit_tap_table_place_first))
        askTableSize { size ->
            val s = session() ?: return@askTableSize
            stopTyping()
            val shown = !s.needsReopen
            if (!s.insertTable(at, size.rows, size.columns)) return@askTableSize toast(s.lastError?.message ?: str(R.string.docsdk_edit_table_insert_failed))
            if (shown && s.needsReopen) return@askTableSize reloadWorking()
            // shown at once: the caret in its first cell, right after the paragraph
            pagesChangedFrom(at)
            ((docView() as? com.wxiwei.office.wp.control.Word)?.getDocument()?.getParagraph(at)?.getEndOffset())?.let { startTyping(it) }
        }
    }

    /** Writes every edit to a working copy in the cache and shows it; the original waits for Save. Then [then], once the view is back where it was. */
    private fun reloadWorking(then: (() -> Unit)? = null) {
        val s = session ?: return
        val next = workingCopy()
        val result = s.save(next)
        if (result !is com.wxiwei.office.editor.EditResult.Ok) return report(result, "")
        val previous = working
        working = next
        workingChanged = true
        session = null
        anchor = null
        status = str(R.string.docsdk_edit_word_hint)
        // the reopened document starts at page 1: bring back the place the edit was made
        val w = docView() as? com.wxiwei.office.wp.control.Word
        val place = w?.let { Triple(it.scrollX, it.scrollY, it.getZoom()) }
        reopen(next) {
            if (previous != file) previous.delete()
            if (place != null) restoreScroll(place.first, place.second, place.third, then) else then?.invoke()
        }
    }

    /** Scrolls the reopened document to ([x], [y]) at [zoom], once its pages are laid out that far. */
    private fun restoreScroll(x: Int, y: Int, zoom: Float, then: (() -> Unit)? = null) {
        activity.lifecycleScope.launch {
            val end = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < end) {
                val w = docView() as? com.wxiwei.office.wp.control.Word ?: return@launch
                val scale = w.getZoom() / zoom
                val tx = Math.round(x * scale); val ty = Math.round(y * scale)
                if (w.getWordHeight() * w.getZoom() - w.height >= ty) {
                    w.scrollTo(tx, ty)
                    w.postInvalidate()
                    then?.invoke()
                    return@launch
                }
                kotlinx.coroutines.delay(50)
            }
            then?.invoke()
        }
    }

    init {
        reader.onDocumentGesture = gesture@{ type, event ->
            val selection = selection() ?: return@gesture false
            when (type) {
                IMainFrame.ON_LONG_PRESS -> {
                    stopTyping()
                    // a picture: selected, and the finger still down moves it
                    if (pictureTap(selection, event.rawX, event.rawY)) {
                        if (picture.grab(event.rawX, event.rawY)) reader.touchCapture = { picture.follow(it) }
                        return@gesture true
                    }
                    clearPicture()
                    val offset = selection.offsetAtScreen(event.rawX, event.rawY)
                    if (offset < 0) return@gesture false
                    // in a table: the word is selected like anywhere (to type over, format...) and the
                    // table's frame shows; the finger moving on picks the whole table up instead
                    session()?.tableAt(offset)?.takeIf { has(EditFeature.TABLES) }?.let { table ->
                        val word = selection.wordAt(offset)
                        if (word.isEmpty()) startTyping(offset) else { anchor = word; select(selection, word) }
                        showTableFrame(table)
                        dragTableFrom(table, event.rawX, event.rawY)
                        return@gesture true
                    }
                    val word = selection.wordAt(offset)
                    // no word here (an empty paragraph, a space): just put the caret
                    if (word.isEmpty()) return@gesture startTyping(offset)
                    anchor = word
                    select(selection, word)
                    true
                }
                IMainFrame.ON_SINGLE_TAP_CONFIRMED -> {
                    tapAt(event.rawX, event.rawY)
                }
                else -> false
            }
        }
    }

    private val handles = WordSelectionHandles(
        context,
        source = { docView() },
        range = { selection()?.selection() },
        caret = { offset -> selection()?.caretRect(offset) },
        offsetAt = { x, y -> selection()?.offsetAtScreen(x, y) ?: -1 },
        onChange = { start, end ->
            selection()?.let { select(it, start until end) }
            anchor = start until end
        },
        onTap = { x, y -> tapAt(x, y) },
    )

    // the selected picture: its one-char object in the text, and whether it floats on the page
    private var pictureAt = -1L
    private var pictureFloats = false
    // the table being dragged: its offsets
    private var tableAt: LongRange? = null
    private val picture = WordPictureOverlay(context, { docView() },
        frame = {
            val sel = selection()
            val table = currentTable()
            when {
                sel == null -> null
                table != null -> sel.tableRect(table.first, table.last + 1)
                pictureAt < 0 -> null
                pictureFloats -> sel.floatingShapeRect(pictureAt)
                else -> sel.inlineObjectRect(pictureAt)
            }
        },
        onMove = { rawX, rawY, dx, dy -> if (tableAt != null) moveTable(rawX, rawY, dy) else movePicture(rawX, rawY, dx, dy) },
        // an in-line picture goes to a text position: a caret shows it under the finger
        dropAt = { rawX, rawY, dy ->
            val sel = selection()
            val table = tableAt
            when {
                sel == null -> null
                table != null -> tableDrop(sel, table, rawX, rawY, dy)
                pictureFloats -> null
                else -> sel.offsetAtScreen(rawX, rawY).takeIf { it >= 0 }?.let { sel.caretRect(it) }
            }
        },
        guides = { currentTable()?.let { t -> selection()?.tableGuides(t.first, t.last + 1) } },
        onColumn = { index, dx -> resizeColumn(index, dx) },
        onRow = { start, height -> resizeRow(start, height) },
        onResize = { w, h -> resizePicture(w, h) },
    )

    init {
        EditFonts.register()
        addOverlay(caret)
        addOverlay(handles)
        addOverlay(picture)
        // the keyboard's target: out of sight, over the document
        addOverlay(typing, FrameLayout.LayoutParams(1, 1))
        keepAboveKeyboard(true)
    }

    override fun close() {
        super.close()
        reader.onDocumentGesture = null
        reader.touchCapture = null
        stopTyping()
        removeOverlay(caret)
        removeOverlay(handles)
        removeOverlay(picture)
        removeOverlay(typing)
        clearSelection()
    }

    private fun zoom(): Float = (docView() as? com.wxiwei.office.wp.control.Word)?.getZoom() ?: 1f

    private fun isPicture(shape: com.wxiwei.office.common.shape.IShape?) =
        shape != null && (shape is com.wxiwei.office.common.shape.PictureShape || shape is com.wxiwei.office.common.shape.WPPictureShape ||
            shape.type.toInt() == com.wxiwei.office.common.shape.AbstractShape.SHAPE_PICTURE.toInt())

    /** Selects the picture under a tap (floating, or in a line of text); false when there is none. */
    private fun pictureTap(sel: WordSelection, rawX: Float, rawY: Float): Boolean {
        if (!has(EditFeature.PICTURES)) return false
        val word = docView() ?: return false
        val at = IntArray(2)
        word.getLocationOnScreen(at)
        sel.floatingShapeAt(rawX - at[0], rawY - at[1])?.takeIf { isPicture(it.shape) }?.let { return selectPicture(it.offset, true) }
        val s = session() ?: return false
        val offset = sel.offsetAtScreen(rawX, rawY)
        if (offset < 0) return false
        for (o in listOf(offset, offset - 1)) {
            if (o >= 0 && isPicture(s.shapeAt(o))) {
                // in a line: only when the tap is on the picture itself
                val r = sel.inlineObjectRect(o) ?: continue
                if (r.contains((rawX - at[0]).toInt(), (rawY - at[1]).toInt())) return selectPicture(o, false)
            }
        }
        return false
    }

    private fun selectPicture(offset: Long, floats: Boolean): Boolean {
        stopTyping()
        clearSelection()
        pictureAt = offset
        pictureFloats = floats
        picture.active = true
        status = str(R.string.docsdk_edit_picture_selected_hint)
        return true
    }

    private fun clearPicture() {
        if (pictureAt < 0 && tableAt == null) return
        pictureAt = -1
        tableAt = null
        picture.active = false
        picture.resizable = true
        status = str(R.string.docsdk_edit_word_hint)
    }

    /**
     * Where a dragged [table] would go for the finger at ([rawX], [rawY]): a line across the table's
     * width at the top of the paragraph under it, or at its bottom when dragged down ([dy] > 0).
     */
    private fun tableDrop(sel: WordSelection, table: LongRange, rawX: Float, rawY: Float, dy: Float): android.graphics.Rect? {
        val to = sel.offsetAtScreen(rawX, rawY)
        if (to < 0 || to in table) return null
        val doc = (docView() as? com.wxiwei.office.wp.control.Word)?.getDocument() as? com.wxiwei.office.wp.model.WPDocument ?: return null
        // the body paragraph or table the drop goes next to
        val block = doc.getParagraph0(to) ?: return null
        val lines = if (block is com.wxiwei.office.wp.model.TableElement) listOfNotNull(sel.tableRect(block.getStartOffset(), block.getEndOffset()))
            else sel.rectsFor(block.getStartOffset(), block.getEndOffset())
        if (lines.isEmpty()) return null
        val y = if (dy > 0) lines.maxOf { it.bottom } else lines.minOf { it.top }
        val span = sel.tableRect(table.first, table.last + 1) ?: return null
        val half = Math.round(1.5f * context.resources.displayMetrics.density)
        return android.graphics.Rect(span.left, y - half, span.right, y + half)
    }

    /**
     * Thumbnails of the pages from the one holding [offset] on are drawn again (an edit there can
     * push the rest down); the pages before it did not change and keep theirs.
     */
    private fun pagesChangedFrom(offset: Long) {
        val thumbs = reader.thumbnails ?: return
        val root = (docView() as? com.wxiwei.office.wp.control.Word)?.getRoot(com.wxiwei.office.constant.wp.WPViewConstant.PAGE_ROOT.toInt()) as? com.wxiwei.office.wp.view.PageRoot
        val count = maxOf(reader.state.value.pageCount, root?.getPageCount() ?: 0)
        val first = root?.let { r -> (0 until r.getPageCount()).lastOrNull { (r.getPageView(it)?.getStartOffset(null) ?: Long.MAX_VALUE) <= offset } } ?: 0
        for (page in first + 1..maxOf(first + 1, count)) thumbs.invalidate(page)
    }

    /** Pixels shown at the current zoom -> twips of the document. */
    private fun twips(px: Float): Int = Math.round(px / zoom() * com.wxiwei.office.constant.MainConstant.PIXEL_TO_TWIPS)

    private fun resizeColumn(index: Int, dx: Float) {
        val s = session() ?: return
        val table = currentTable() ?: return
        if (s.resizeTableColumn(table.first, index, twips(dx)) == null) toast(s.lastError?.message ?: str(R.string.docsdk_edit_column_width_failed))
        pagesChangedFrom(table.first)
        picture.invalidate()
    }

    private fun resizeRow(start: Long, height: Float) {
        val s = session() ?: return
        if (!s.setTableRowHeight(start, twips(height))) toast(s.lastError?.message ?: str(R.string.docsdk_edit_row_height_failed))
        pagesChangedFrom(start)
        picture.invalidate()
    }

    /** The table's frame with its handles, over whatever text is selected or typed in it. */
    private fun showTableFrame(table: LongRange) {
        pictureAt = -1
        tableAt = table
        picture.resizable = false
        picture.active = true
    }

    /** The table picked up to move: the text selection goes. */
    private fun selectTable(table: LongRange) {
        stopTyping()
        clearSelection()
        showTableFrame(table)
        status = str(R.string.docsdk_edit_table_drag_hint)
    }

    /** The table shown now, [tableAt] as the text typed in it grew it (its start stays). */
    private fun currentTable(): LongRange? = tableAt?.let { t -> session()?.tableAt(t.first) ?: t }

    /**
     * After a long press in [table]: when the finger then moves, the table is picked up and follows
     * it; lifted in place, the word stays selected (and the frame shows).
     */
    private fun dragTableFrom(table: LongRange, rawX: Float, rawY: Float) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var picked = false
        reader.touchCapture = { e ->
            if (!picked && e.actionMasked == android.view.MotionEvent.ACTION_MOVE &&
                Math.hypot((e.rawX - rawX).toDouble(), (e.rawY - rawY).toDouble()) > slop) {
                picked = true
                selectTable(table)
                if (!picture.grab(rawX, rawY)) clearPicture()
            }
            if (picked) picture.follow(e)
        }
    }

    /** Puts the dragged table before the paragraph where the finger was lifted, or after it when dragged down. */
    private fun moveTable(rawX: Float, rawY: Float, dy: Float) {
        val table = currentTable() ?: return
        val s = session() ?: return
        val to = selection()?.offsetAtScreen(rawX, rawY) ?: return
        // dropped on itself: it stays selected
        if (to < 0 || to in table) return picture.invalidate()
        val shown = !s.needsReopen
        if (!s.moveTable(table.first, to, after = dy > 0)) { clearPicture(); return toast(s.lastError?.message ?: str(R.string.docsdk_edit_table_move_failed)) }
        pagesChangedFrom(minOf(table.first, to))
        if (shown && s.needsReopen) { clearPicture(); return reloadWorking() }
        // shown at once: keep it selected where it is now
        tableAt = s.movedTable ?: return clearPicture()
        picture.active = true
    }

    private fun movePicture(rawX: Float, rawY: Float, dx: Float, dy: Float) {
        val s = session() ?: return
        val sel = selection() ?: return
        val z = zoom()
        val shown = !s.needsReopen
        var at = pictureAt
        val ok = if (pictureFloats) s.shiftObject(pictureAt, Math.round(dx / z), Math.round(dy / z))
        else {
            // to the text position where the finger was lifted (the caret shown while dragging)
            val to = sel.offsetAtScreen(rawX, rawY)
            if (to == pictureAt || to == pictureAt + 1) return picture.invalidate()
            (to >= 0 && s.moveObject(pictureAt, to)).also { if (it) at = if (to > pictureAt) to - 1 else to }
        }
        if (!ok) { clearPicture(); return toast(s.lastError?.message ?: str(R.string.docsdk_edit_picture_move_failed)) }
        pictureEdited(s, shown, at)
    }

    /**
     * After a picture edit: the view shows it already (the picture stays selected at [at]), or,
     * when the session could not show it, the working copy is reopened.
     */
    private fun pictureEdited(s: LiveDocxSession, shown: Boolean, at: Long) {
        pagesChangedFrom(minOf(at, pictureAt.takeIf { it >= 0 } ?: at))
        if (shown && s.needsReopen) { clearPicture(); return reloadWorking() }
        pictureAt = at
        picture.active = true
    }

    private fun resizePicture(width: Float, height: Float) {
        val s = session() ?: return
        val z = zoom()
        val shown = !s.needsReopen
        val ok = s.resizeObject(pictureAt, maxOf(1, Math.round(width / z)), maxOf(1, Math.round(height / z)))
        if (!ok) { clearPicture(); return toast(s.lastError?.message ?: str(R.string.docsdk_edit_picture_resize_failed)) }
        pictureEdited(s, shown, pictureAt)
    }

    /** A tap ends any selection and puts the caret there; the handles extend a selection. */
    private fun tapAt(rawX: Float, rawY: Float): Boolean {
        val sel = selection() ?: return false
        if (pictureTap(sel, rawX, rawY)) return true
        val offset = sel.offsetAtScreen(rawX, rawY)
        // a tap in the table keeps its frame (typing in a cell); anywhere else takes it off
        if (currentTable()?.let { offset in it } != true) clearPicture()
        if (offset < 0) return false
        return clickAndType(sel, offset, rawX, rawY) || startTyping(offset)
    }

    /**
     * Word's "click and type": a tap on the empty page below the last paragraph adds empty
     * paragraphs down to it, aligned left, centre or right by where the tap was.
     */
    private fun clickAndType(sel: WordSelection, offset: Long, rawX: Float, rawY: Float): Boolean {
        if (!has(EditFeature.TEXT)) return false
        val w = docView() as? com.wxiwei.office.wp.control.Word ?: return false
        val end = w.getDocument().getAreaEnd(0) - 1 // before the document's last paragraph mark
        if (offset != end) return false
        val origin = IntArray(2)
        w.getLocationOnScreen(origin)
        val x = rawX - origin[0]
        val y = rawY - origin[1]
        val last = sel.caretRect(end) ?: return false
        if (y < last.bottom + last.height() / 2) return false // on or next to the last line
        val bottom = sel.bodyBottomAt(end) ?: return false
        val target = minOf(y, bottom.toFloat())
        val s = session() ?: return false
        stopTyping()
        // one paragraph first: its height, with the paragraph spacing, tells how many are needed
        if (!s.insertText(end, "\n")) return false
        var at = end + 1
        val next = sel.caretRect(at)
        if (next != null) {
            val pitch = (next.top - last.top).coerceAtLeast(1)
            val more = Math.ceil(((target - next.bottom) / pitch).toDouble()).toInt().coerceIn(0, 80)
            if (more > 0 && s.insertText(at, "\n".repeat(more))) at += more
        }
        when {
            x > w.width * 2f / 3 -> s.setAlignment(at, at + 1, "right")
            x > w.width / 3f -> s.setAlignment(at, at + 1, "center")
        }
        pagesChangedFrom(at)
        return startTyping(at)
    }

    /** Puts the caret before [offset] and opens the keyboard. */
    private fun startTyping(offset: Long): Boolean {
        if (!has(EditFeature.TEXT)) return false
        // the caret moved: B / I / U pressed before apply at the old place only
        pending.clear()
        session() ?: return false
        anchor = null
        selection()?.clearSelection()
        handles.refresh()
        resetBuffer(offset)
        typing.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(typing, 0)
        caret.active = true
        status = str(R.string.docsdk_edit_typing_hint) + pendingText()
        return true
    }

    private fun resetBuffer(offset: Long) {
        muted = true
        typing.setText("")
        muted = false
        base = offset
    }

    private fun stopTyping() {
        pending.clear()
        if (base < 0) return
        base = -1
        caret.active = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typing.windowToken, 0)
    }

    /** Replays a change of the typing buffer on the document. */
    /** B / I / U turned on or off with only the caret: for the text typed next (like Word), by name. */
    private val pending = LinkedHashMap<String, Pair<Boolean, (LiveDocxSession, Long, Long, Boolean) -> Boolean>>()

    /**
     * B / I / U: on the selected text; with only the caret, on the word it is in (like Word), or,
     * between words, for the text typed next.
     */
    private fun toggle(name: String, isOn: (LiveDocxSession, Long) -> Boolean, set: (LiveDocxSession, Long, Long, Boolean) -> Boolean) {
        if (base < 0 || selection()?.selection() != null) return op { e, r -> set(e, r.first, r.last + 1, !isOn(e, r.first)) }
        val s = session() ?: return
        val caretAt = base + typing.selectionEnd.coerceAtLeast(0)
        val word = selection()?.wordAt(caretAt)
        if (word != null && !word.isEmpty() && caretAt > word.first && caretAt <= word.last) {
            if (!set(s, word.first, word.last + 1, !isOn(s, word.first))) {
                if (s.needsFlush) { stopTyping(); return reloadWorking { startTyping(caretAt); toggle(name, isOn, set) } }
                return toast(s.lastError?.message ?: str(R.string.docsdk_edit_failed))
            }
            resetBuffer(caretAt)
            caret.touch()
            pagesChangedFrom(word.first)
            return
        }
        val on = !(pending[name]?.first ?: (caretAt > 0 && isOn(s, caretAt - 1)))
        pending[name] = on to set
        status = str(if (on) R.string.docsdk_edit_style_on else R.string.docsdk_edit_style_off, name)
    }

    private fun typed(start: Int, removed: Int, added: String) {
        val s = session() ?: return
        val at = base + start
        val ok = when {
            removed > 0 && added.isNotEmpty() -> s.replaceText(at, at + removed, added)
            removed > 0 -> s.deleteText(at, at + removed)
            added.isNotEmpty() -> s.insertText(at, added)
            else -> true
        }
        if (!ok) {
            toast(s.lastError?.message ?: str(R.string.docsdk_edit_typing_failed))
            // the document did not change: start over at the caret the document still has
            resetBuffer(at)
            return
        }
        // B / I / U pressed before typing: on what was just typed
        if (added.isNotEmpty()) for ((on, set) in pending.values) set(s, at, at + added.length, on)
        caret.touch()
        revealCaret()
        pagesChangedFrom(at)
    }

    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** What was last copied from this document, with its formatting (see [paste]). */
    private var formattedClip: com.wxiwei.office.editor.docx.LiveDocxSession.FormattedText? = null

    private fun copy() {
        val sel = selection()
        val range = sel?.selection()
        val t = if (range != null) sel.selectedText() else null
        if (t.isNullOrEmpty()) return toast(str(R.string.docsdk_edit_select_text_first))
        formattedClip = session()?.copyFormatted(range!!.first, range.last + 1)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", t))
        toast(str(R.string.docsdk_edit_copied))
    }

    /** The formatted copy when the clipboard still holds its text (nothing else was copied since). */
    private fun formattedFor(text: String) = formattedClip?.takeIf { it.text.replace('\r', '\n') == text.replace('\r', '\n') }

    /**
     * Pastes at the caret while typing, else over the selection: text copied from this document
     * keeps its formatting, other text is pasted plain.
     */
    private fun paste() {
        val t = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (t.isNullOrEmpty()) return toast(str(R.string.docsdk_edit_clipboard_empty))
        val formatted = formattedFor(t)
        if (formatted != null) {
            val s = session() ?: return
            if (base >= 0) {
                val at = base + typing.selectionStart.coerceAtLeast(0)
                val until = base + typing.selectionEnd.coerceAtLeast(0)
                stopTyping()
                if (!s.pasteFormatted(minOf(at, until), maxOf(at, until), formatted)) return toast(s.lastError?.message ?: str(R.string.docsdk_edit_paste_failed))
                pagesChangedFrom(minOf(at, until))
                startTyping(minOf(at, until) + formatted.text.length)
                return
            }
            return op { e, r -> e.pasteFormatted(r.first, r.last + 1, formatted) }
        }
        if (base >= 0) {
            // through the typing buffer, so it stays in step with the document
            val at = typing.selectionEnd.coerceAtLeast(0)
            typing.text.replace(typing.selectionStart.coerceAtLeast(0), at, t)
            return
        }
        op { e, r -> e.replaceText(r.first, r.last + 1, t) }
    }

    /** Keeps the caret above the keyboard and inside the screen. */
    private fun revealCaret() {
        if (base < 0) return
        val word = docView() ?: return
        selection()?.revealCaret(base + typing.selectionEnd.coerceAtLeast(0), dp(24), visibleBottom(word))
    }

    override fun onKeyboardMoved() {
        reader.post { revealCaret() }
    }

    private fun selection(): WordSelection? = reader.control?.let { runCatching { WordSelection(it) }.getOrNull() }

    private fun select(selection: WordSelection, range: LongRange) {
        if (range.isEmpty()) return
        selection.setSelection(range.first, range.last + 1)
        val t = selection.selectedText().replace('\n', ' ')
        status = str(R.string.docsdk_edit_selected_text, if (t.length > 60) t.take(60) + "…" else t) + pendingText()
        handles.refresh()
    }

    private fun clearSelection() {
        anchor = null
        selection()?.clearSelection()
        status = str(R.string.docsdk_edit_word_hint) + pendingText()
        handles.refresh()
    }

    private fun pendingText() = if (session?.needsReopen == true) "  ·  " + str(R.string.docsdk_edit_some_after_save) else ""

    private fun session(): LiveDocxSession? {
        session?.let { return it }
        val control = reader.control ?: return null
        return runCatching { LiveDocxSession(control, working) }.getOrElse {
            toast(str(R.string.docsdk_edit_not_ready))
            null
        }?.also { session = it }
    }

    /** Runs [action] on the selection: formatting shows at once, text changes after Save. */
    /** A paragraph change: works on the selection, or on the caret's paragraph while typing. */
    private fun paraOp(action: (LiveDocxSession, LongRange) -> Boolean) {
        if (base < 0 || selection()?.selection() != null) return op(action)
        val caretAt = base + typing.selectionEnd.coerceAtLeast(0)
        val s = session() ?: return
        if (!action(s, caretAt..caretAt)) {
            // in a cell added just now: save the working copy first (the cell gets its place), then again
            if (s.needsFlush) { stopTyping(); return reloadWorking { startTyping(caretAt); paraOp(action) } }
            return toast(s.lastError?.message ?: str(R.string.docsdk_edit_failed))
        }
        // offsets did not move: keep typing at the same place
        resetBuffer(caretAt)
        caret.touch()
        pagesChangedFrom(caretAt)
    }

    private fun op(action: (LiveDocxSession, LongRange) -> Boolean) {
        stopTyping()
        val range = selection()?.selection() ?: return toast(str(R.string.docsdk_edit_select_text_first))
        val s = session() ?: return
        if (!action(s, range)) {
            if (s.needsFlush) return reloadWorking { selection()?.let { select(it, range) }; op(action) }
            return toast(s.lastError?.message ?: str(R.string.docsdk_edit_failed))
        }
        // the pages were laid out again: show the selection on the new layout, refresh thumbnails
        selection()?.let { select(it, range) }
        pagesChangedFrom(range.first)
        if (s.needsReopen) toast(str(R.string.docsdk_edit_shown_after_save))
    }

    private fun replace() {
        val range = selection()?.selection() ?: return toast(str(R.string.docsdk_edit_select_text_first))
        val now = selection()?.selectedText().orEmpty()
        askText(str(R.string.docsdk_edit_replace_text), str(R.string.docsdk_edit_word_text_hint), now) { t ->
            op { e, r -> e.replaceText(r.first, r.last + 1, t) }
        }
    }

    private fun insert() {
        val at = here().takeIf { it >= 0 } ?: return toast(str(R.string.docsdk_edit_tap_text_first))
        askText(str(R.string.docsdk_edit_insert_text), str(R.string.docsdk_edit_word_text_hint), "") { t -> insertAt(at, t) }
    }

    private fun insertAt(at: Long, t: String) {
        if (t.isEmpty()) return toast(str(R.string.docsdk_edit_enter_text))
        stopTyping()
        val s = session() ?: return
        if (!s.insertText(at, t)) {
            if (s.needsFlush) return reloadWorking { insertAt(at, t) }
            return toast(s.lastError?.message ?: str(R.string.docsdk_edit_failed))
        }
        pagesChangedFrom(at)
        if (s.needsReopen) toast(str(R.string.docsdk_edit_shown_after_save))
        // show the inserted text selected (Thay / B / I apply to it after saving)
        selection()?.let { select(it, at until at + t.length) }
    }

    override fun hasChanges() = session?.hasChanges() == true || workingChanged

    override fun writeTo(target: File): com.wxiwei.office.editor.EditResult {
        session?.takeIf { it.hasChanges() }?.let { return it.save(target) }
        working.copyTo(target, overwrite = true)
        return com.wxiwei.office.editor.EditResult.Ok(target)
    }

    override fun onSaved() {
        session = null
        anchor = null
        stopTyping()
        status = str(R.string.docsdk_edit_word_hint)
        if (working != file) working.delete()
        working = file
        workingChanged = false
        // show the saved text: reopen the document (the panel stays open)
        reopen(file) {}
    }
}
