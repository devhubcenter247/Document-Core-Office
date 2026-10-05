/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor.docx

import com.wxiwei.office.constant.wp.WPModelConstant
import android.graphics.Color
import com.wxiwei.office.editor.EditResult
import com.wxiwei.office.editor.UndoStack
import com.wxiwei.office.editor.Reason
import com.wxiwei.office.simpletext.model.AttrManage
import com.wxiwei.office.simpletext.model.IAttributeSet
import com.wxiwei.office.simpletext.model.LeafElement
import com.wxiwei.office.simpletext.model.ParagraphElement
import com.wxiwei.office.system.IControl
import com.wxiwei.office.wp.control.Word
import com.wxiwei.office.wp.model.WPDocument
import com.wxiwei.office.constant.wp.WPAttrConstant
import com.wxiwei.office.simpletext.model.IElement
import java.io.File

/**
 * Realtime editing of an open .docx. Formatting (bold, italic, underline, color, size,
 * highlight) and text changes inside one paragraph show at once: the model is updated and the
 * pages laid out again. Every change is also queued in [DocxEditor], which writes it on [save].
 *
 * Offsets are CURRENT model offsets (after the live text changes), end exclusive; the session
 * maps them to the file's original offsets for [DocxEditor]. Changes the view cannot show live
 * (a paragraph break, a range across paragraphs) are still saved: [needsReopen]. Text typed in
 * this session is formatted through its queued insert (DocxEditor.formatInserted). Main thread only.
 */
class LiveDocxSession(control: IControl, private val source: File) {
    private companion object { const val EMU_PER_PX = 9525L }

    private val word = control.getView() as? Word ?: error("Open a Word document first")
    private val editor = DocxEditor(source, DocxSourceMap.get(source.absolutePath) ?: error("Document is still loading"))
    private val am = AttrManage.instance()
    /** Held while the model changes: the background page layout takes it for every page it lays out. */
    private val layoutLock: Any get() = word.getDocument()

    private open class Step(val undo: () -> Boolean, val redo: () -> Boolean) {
        open fun runUndo(): Boolean = undo()
        open fun runRedo(): Boolean = redo()
    }

    /** An Enter at [at]. */
    private class SplitStep(val at: Long, undo: () -> Boolean, redo: () -> Boolean) : Step(undo, redo)
    private val undoStack = UndoStack<Step>()
    private val redoStack = ArrayList<Step>()

    /** True after a text change: it is saved, but the view shows it only after reopening. */
    var needsReopen = false
        private set
    private var ownError: EditResult.Error? = null
    val lastError: EditResult.Error? get() = ownError ?: editor.lastError
    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
    fun hasChanges() = editor.pendingCount > 0

    fun setBold(start: Long, end: Long, on: Boolean) = format(start, end, { e, s, t -> e.setBold(s, t, on) }) { am.setFontBold(it, on) }
    fun setItalic(start: Long, end: Long, on: Boolean) = format(start, end, { e, s, t -> e.setItalic(s, t, on) }) { am.setFontItalic(it, on) }
    fun setUnderline(start: Long, end: Long, on: Boolean) = format(start, end, { e, s, t -> e.setUnderline(s, t, on) }) { am.setFontUnderline(it, if (on) 1 else 0) }
    fun setStrike(start: Long, end: Long, on: Boolean) = format(start, end, { e, s, t -> e.setStrike(s, t, on) }) { am.setFontStrike(it, on) }
    /** The font [name] for [start, end); drawn with the app's typeface for it, or a bundled/system one. */
    fun setFont(start: Long, end: Long, name: String): Boolean {
        val index = com.wxiwei.office.simpletext.font.FontTypefaceManage.instance().addFontName(name)
        return format(start, end, { e, s, t -> e.setFont(s, t, name) }) { am.setFontName(it, index) }
    }

    /** Name of the font of the character at [offset], or null. */
    fun fontAt(offset: Long): String? {
        val doc = word.getDocument()
        val para = doc.getParagraph(offset) ?: return null
        val leaf = doc.getLeaf(offset) ?: return null
        return com.wxiwei.office.simpletext.font.FontTypefaceManage.instance().fontName(am.getFontName(para.getAttribute(), leaf.getAttribute()))
    }

    /** [script]: 1 superscript, 2 subscript, 0 back on the line. */
    fun setScript(start: Long, end: Long, script: Int) = format(start, end, { e, s, t -> e.setScript(s, t, script) }) { am.setFontScript(it, script) }
    fun setTextColor(start: Long, end: Long, rgbHex: String): Boolean {
        val rgb = rgbHex.removePrefix("#")
        val color = rgb.toIntOrNull(16)?.let { (0xFF shl 24) or it } ?: return editor.setTextColor(start, end, rgb)
        return format(start, end, { e, s, t -> e.setTextColor(s, t, rgb) }) { am.setFontColor(it, color) }
    }
    fun setFontSize(start: Long, end: Long, pt: Float) = format(start, end, { e, s, t -> e.setFontSize(s, t, pt) }) { am.setFontSize(it, pt) }
    fun highlight(start: Long, end: Long, rgbHex: String = "FFFF00"): Boolean {
        // "none" takes the highlight away
        if (rgbHex == "none") return format(start, end, { e, s, t -> e.highlight(s, t, "none") }) { am.setFontHighLight(it, -1) }
        val rgb = rgbHex.removePrefix("#")
        val color = rgb.toIntOrNull(16)?.let { (0xFF shl 24) or it } ?: Color.YELLOW
        return format(start, end, { e, s, t -> e.highlight(s, t, rgb) }) { am.setFontHighLight(it, color) }
    }

    // ---- text changes --------------------------------------------------------------------

    /** A live text change, in current offsets at the time it was made. */
    private sealed class Edit {
        abstract val at: Long
        class Insert(override val at: Long, var length: Long) : Edit()
        class Delete(override val at: Long, val length: Long) : Edit()
        /** The [length] chars at [from] (a picture, a table) now start at [dest]; nothing else changed. */
        class Move(val from: Long, val length: Long, val dest: Long) : Edit() {
            override val at: Long get() = minOf(from, dest)
            /** Offset after the move -> before it. */
            fun back(x: Long): Long = when {
                x >= dest && x < dest + length -> x - dest + from
                dest < from && x >= dest + length && x < from + length -> x - length
                dest > from && x >= from && x < dest -> x + length
                else -> x
            }
            /** Offset before the move -> after it. */
            fun forward(x: Long): Long = when {
                x >= from && x < from + length -> x - from + dest
                dest < from && x >= dest && x < from -> x + length
                dest > from && x >= from + length && x < dest + length -> x - length
                else -> x
            }
            /** True when [s, e) (after the move) lies across one of its boundaries. */
            fun splits(s: Long, e: Long): Boolean {
                if (e <= s) return false
                val cuts = listOf(minOf(from, dest), dest, dest + length, maxOf(from, dest) + length)
                return cuts.any { it > s && it < e }
            }
        }
    }
    private val edits = ArrayList<Edit>()
    /** The queued file insert of each live insert, whose text is taken from the view on save. */
    private val handles = java.util.IdentityHashMap<Edit.Insert, Any>()
    private fun track(edit: Edit.Insert) { editor.lastOp()?.let { handles[edit] = it } }

    /** The story (body, headers, footers, one text box) of an offset: edits in one never move offsets of another. */
    private fun area(offset: Long) = offset and (WPModelConstant.AREA_MASK or WPModelConstant.TEXTBOX_MASK)

    /**
     * Current model offset -> original file offset (the start of typed text for positions inside it).
     * A caret right where text was deleted stays before it; with [char], the offset names the char
     * there, which was after the deleted text (a row, a cell moved into the place of one deleted).
     */
    private fun toOriginal(offset: Long, char: Boolean = false): Long {
        var x = offset
        for (e in edits.asReversed()) if (area(e.at) == area(offset)) when (e) {
            is Edit.Insert -> if (x >= e.at + e.length) x -= e.length else if (x > e.at) x = e.at
            is Edit.Delete -> if (x > e.at || char && x == e.at) x += e.length
            is Edit.Move -> x = e.back(x)
        }
        return x
    }

    /** True when [start, end) contains text typed in this session (it has no original offsets). */
    private fun touchesTyped(start: Long, end: Long): Boolean {
        var s = start
        var e = end
        for (edit in edits.asReversed()) if (area(edit.at) == area(start)) when (edit) {
            is Edit.Insert -> {
                val a = edit.at
                val b = edit.at + edit.length
                if (s < b && e > a) return true
                if (s >= b) s -= edit.length
                if (e >= b) e -= edit.length
            }
            is Edit.Delete -> {
                if (s > edit.at) s += edit.length
                if (e > edit.at) e += edit.length
            }
            is Edit.Move -> {
                if (edit.splits(s, e)) return true
                val b = edit.back(s); e = b + (e - s); s = b
            }
        }
        return false
    }

    /**
     * [queued] (a file operation just queued) is dropped when save would fail on it: a table edit
     * whose place the file cannot find would otherwise stay queued and fail every save after it.
     */
    private fun checked(queued: Boolean): Boolean {
        if (!queued) return false
        val result = editor.check(overrides(), cellFills())
        if (result !is EditResult.Error) return true
        editor.undoLast()
        ownError = result
        return false
    }

    private fun refuse(message: String): Boolean {
        ownError = EditResult.Error(Reason.INVALID_ARGUMENT, message)
        return false
    }

    // ---- paragraph formatting ---------------------------------------------------------------

    fun setAlignment(start: Long, end: Long, align: String): Boolean {
        val value = when (align) {
            "center" -> WPAttrConstant.PARA_HOR_ALIGN_CENTER
            "right" -> WPAttrConstant.PARA_HOR_ALIGN_RIGHT
            "both" -> WPAttrConstant.PARA_HOR_ALIGN_JUSTIFIED
            else -> WPAttrConstant.PARA_HOR_ALIGN_LEFT
        }.toInt()
        return paragraphFormat(start, end, { e, s, t -> e.setParagraphAlignment(s, t, align) }) { am.setParaHorizontalAlign(it, value) }
    }

    fun setIndentLeft(start: Long, end: Long, twips: Int) =
        paragraphFormat(start, end, { e, s, t -> e.setParagraphIndent(s, t, twips) }) { am.setParaIndentLeft(it, twips) }

    fun setLineSpacing(start: Long, end: Long, multiple: Float) = paragraphFormat(start, end, { e, s, t -> e.setLineSpacing(s, t, multiple) }) {
        am.setParaLineSpaceType(it, WPAttrConstant.LINE_SAPCE_MULTIPLE.toInt())
        am.setParaLineSpace(it, multiple)
    }

    /** Line height [exactly] [points] (or at least that much); the model keeps twips, negative for "exactly" (as DOCXReader). */
    fun setLineSpacingPoints(start: Long, end: Long, points: Float, exactly: Boolean) =
        paragraphFormat(start, end, { e, s, t -> e.setLineSpacingPoints(s, t, points, exactly) }) {
            am.setParaLineSpaceType(it, (if (exactly) WPAttrConstant.LINE_SPACE_EXACTLY else WPAttrConstant.LINE_SAPCE_LEAST).toInt())
            am.setParaLineSpace(it, Math.round(points * 20).toFloat().let { v -> if (exactly) -v else v })
        }

    /** Space before and after the paragraphs, in points. */
    fun setParagraphSpacing(start: Long, end: Long, beforePt: Float, afterPt: Float) =
        paragraphFormat(start, end, { e, s, t -> e.setParagraphSpacing(s, t, beforePt, afterPt) }) {
            am.setParaBefore(it, Math.round(beforePt * 20)); am.setParaAfter(it, Math.round(afterPt * 20))
        }

    /** The line spacing of the paragraph at [offset]: its kind (multiple, exact, at least) and value (a multiple, or points). */
    fun lineSpacingAt(offset: Long): Pair<Int, Float> {
        val a = word.getDocument().getParagraph(offset)?.getAttribute() ?: return WPAttrConstant.LINE_SAPCE_MULTIPLE.toInt() to 1f
        val type = am.getParaLineSpaceType(a)
        val v = am.getParaLineSpace(a)
        return when (type) {
            WPAttrConstant.LINE_SPACE_EXACTLY.toInt(), WPAttrConstant.LINE_SAPCE_LEAST.toInt() -> type to Math.abs(v) / 20f
            else -> WPAttrConstant.LINE_SAPCE_MULTIPLE.toInt() to (if (v > 0f) v else 1f)
        }
    }

    /** What Word's Paragraph dialog shows: alignment, indents in twips (special > 0 first line, < 0 hanging), spacing in points. */
    data class ParagraphLayout(val align: String, val leftTwips: Int, val rightTwips: Int, val specialTwips: Int, val beforePt: Float, val afterPt: Float)

    fun paragraphLayoutAt(offset: Long): ParagraphLayout {
        val a = word.getDocument().getParagraph(offset)?.getAttribute() ?: return ParagraphLayout("left", 0, 0, 0, 0f, 0f)
        val align = when (am.getParaHorizontalAlign(a)) {
            WPAttrConstant.PARA_HOR_ALIGN_CENTER.toInt() -> "center"
            WPAttrConstant.PARA_HOR_ALIGN_RIGHT.toInt() -> "right"
            WPAttrConstant.PARA_HOR_ALIGN_JUSTIFIED.toInt() -> "both"
            else -> "left"
        }
        val special = am.getParaSpecialIndent(a)
        // the model keeps a hanging indent taken off the left one (as DOCXReader reads it)
        val left = am.getParaIndentLeft(a) - (if (special < 0) special else 0)
        return ParagraphLayout(align, left, am.getParaIndentRight(a), special, am.getParaBefore(a) / 20f, am.getParaAfter(a) / 20f)
    }

    /** Applies [l] to every paragraph touching [start, end): one undoable step. */
    fun setParagraphLayout(start: Long, end: Long, l: ParagraphLayout): Boolean {
        val value = when (l.align) {
            "center" -> WPAttrConstant.PARA_HOR_ALIGN_CENTER
            "right" -> WPAttrConstant.PARA_HOR_ALIGN_RIGHT
            "both" -> WPAttrConstant.PARA_HOR_ALIGN_JUSTIFIED
            else -> WPAttrConstant.PARA_HOR_ALIGN_LEFT
        }.toInt()
        return paragraphFormat(start, end, { e, s, t -> e.setParagraphLayout(s, t, l.align, l.leftTwips, l.rightTwips, l.specialTwips, l.beforePt, l.afterPt) }) {
            am.setParaHorizontalAlign(it, value)
            am.setParaIndentLeft(it, l.leftTwips + (if (l.specialTwips < 0) l.specialTwips else 0))
            am.setParaIndentRight(it, l.rightTwips)
            am.setParaSpecialIndent(it, l.specialTwips)
            am.setParaBefore(it, Math.round(l.beforePt * 20)); am.setParaAfter(it, Math.round(l.afterPt * 20))
        }
    }

    /** Space before and after the paragraph at [offset], in points. */
    fun paragraphSpacingAt(offset: Long): Pair<Float, Float> {
        val a = word.getDocument().getParagraph(offset)?.getAttribute() ?: return 0f to 0f
        return am.getParaBefore(a) / 20f to am.getParaAfter(a) / 20f
    }

    /** True when the character at [offset] is bold (italic, underlined): the B/I/U buttons toggle. */
    fun isBold(offset: Long) = charAttr(offset) { p, l -> am.getFontBold(p, l) }
    fun isItalic(offset: Long) = charAttr(offset) { p, l -> am.getFontItalic(p, l) }
    fun isUnderlined(offset: Long) = charAttr(offset) { p, l -> am.getFontUnderline(p, l) > 0 }
    fun isStruck(offset: Long) = charAttr(offset) { p, l -> am.getFontStrike(p, l) }
    fun isSuperscript(offset: Long) = charAttr(offset) { p, l -> am.getFontScript(p, l) == 1 }
    fun isSubscript(offset: Long) = charAttr(offset) { p, l -> am.getFontScript(p, l) == 2 }
    private fun charAttr(offset: Long, read: (IAttributeSet, IAttributeSet) -> Boolean): Boolean {
        val doc = word.getDocument()
        val para = doc.getParagraph(offset) ?: return false
        val leaf = doc.getLeaf(offset) ?: return false
        return read(para.getAttribute()!!, leaf.getAttribute()!!)
    }

    /** Bullets on or off for the paragraphs touching [start, end). */
    fun setBullets(start: Long, end: Long, on: Boolean) = setList(start, end, on, bullet = true)

    /** Numbering (1. 2. 3.) on or off for the paragraphs touching [start, end). */
    fun setNumbering(start: Long, end: Long, on: Boolean) = setList(start, end, on, bullet = false)

    private fun setList(start: Long, end: Long, on: Boolean, bullet: Boolean): Boolean {
        needsFlush = false
        val id = if (bullet) editor.bulletListId else editor.numberingListId
        if (on && id < 0) return refuse("Cannot read the document's lists")
        if (on) ensureList(id, bullet)
        val fileOp: (DocxEditor, Long, Long) -> Boolean =
            if (bullet) { e, s, t -> e.setBullets(s, t, on) } else { e, s, t -> e.setNumbering(s, t, on) }
        return paragraphFormat(start, end, fileOp) {
            // -1 also hides a list the paragraph style would give, like numId 0 in the file
            am.setParaListID(it, if (on) id else -1)
            am.setParaListLevel(it, 0)
        }
    }

    /** List level of the paragraph at [offset] (0 when not in a list). */
    fun listLevelAt(offset: Long): Int = word.getDocument().getParagraph(offset)?.let { maxOf(0, am.getParaListLevel(it.getAttribute())) } ?: 0

    /** Moves the listed paragraphs touching [start, end) to list level [level] (0-8). */
    fun setListLevel(start: Long, end: Long, level: Int): Boolean {
        needsFlush = false
        if (!hasBullet(start)) return refuse("Not in a list")
        return paragraphFormat(start, end, { e, s, t -> e.setListLevel(s, t, level) }) {
            if (am.getParaListID(it) >= 0) am.setParaListLevel(it, level)
        }
    }

    /** List id of the paragraph at [offset] (-1: none). */
    fun listAt(offset: Long): Int = word.getDocument().getParagraph(offset)?.let { am.getParaListID(it.getAttribute()) } ?: -1

    /** True when the paragraph at [offset] shows a bullet or number. */
    fun hasBullet(offset: Long): Boolean = listAt(offset) >= 0

    /** True when the paragraph at [offset] is in the numbered list [setNumbering] uses. */
    fun hasNumbering(offset: Long): Boolean = listAt(offset).let { it >= 0 && it == editor.numberingListId }

    /** The view draws a list from its ListData: add the one save will write when it is new. */
    private fun ensureList(id: Int, bullet: Boolean) {
        val lists = word.getControl().getSysKit().getListManage()
        if (lists.getListData(id) != null) return
        val bullets = charArrayOf('\u25CF', '\u25CB', '\u25A0')
        val formats = intArrayOf(0, 4, 2) // decimal, lowerLetter, lowerRoman
        lists.putListData(id, com.wxiwei.office.common.bulletnumber.ListData().apply {
            listID = id
            levels = Array(9) { i ->
                com.wxiwei.office.common.bulletnumber.ListLevel().apply {
                    startAt = 1
                    // a char below 9 stands for the number of that level ("%1." in the file)
                    numberText = if (bullet) charArrayOf(bullets[i % bullets.size]) else charArrayOf(i.toChar(), '.')
                    numberFormat = if (bullet) 0 else formats[i % formats.size]
                    textIndent = 720 * (i + 1)
                    specialIndent = -360
                }
            }
            simpleList = 9
        })
    }

    /** Left indent (twips) of the paragraph at [offset], to step it. */
    fun indentLeftAt(offset: Long): Int = word.getDocument().getParagraph(offset)?.let { am.getParaIndentLeft(it.getAttribute()) } ?: 0

    private fun paragraphFormat(start: Long, end: Long, fileOp: (DocxEditor, Long, Long) -> Boolean, apply: (IAttributeSet) -> Unit): Boolean {
        synchronized(layoutLock) {
            ownError = null
            needsFlush = false
            val doc = word.getDocument()
            val targets = ArrayList<IElement>()
            var offset = start
            while (true) {
                val para = doc.getParagraph(offset) ?: break
                targets.add(para)
                if (para.getEndOffset() >= maxOf(end, start + 1) || para.getEndOffset() <= offset) break
                offset = para.getEndOffset()
            }
            if (targets.isEmpty()) return refuse("No paragraph here")
            // paragraphs of new cells are written from the view on save: nothing to queue for them
            val inNew = targets.map { touchesNewCells(it.getStartOffset()) }
            if (inNew.any { it } && !inNew.all { it }) return refuseInNewCells()
            val viewOnly = inNew.all { it }
            // the file needs original offsets: use the paragraphs' own starts, which typed text never moves
            val os = toOriginal(targets.first().getStartOffset())
            val oe = toOriginal(targets.last().getEndOffset() - 1)
            val file: () -> Boolean = if (viewOnly) { { true } } else { { fileOp(editor, os, maxOf(oe, os)) } }
            val unfile: () -> Boolean = if (viewOnly) { { true } } else { { editor.undoLast() } }
            if (!file()) return false
            val before = targets.map { it.getAttribute()!!.clone() }
            targets.forEach { apply(it.getAttribute()!!) }
            val after = targets.map { it.getAttribute()!!.clone() }
            word.relayoutContent(targets.first().getStartOffset())
            fun restore(states: List<IAttributeSet>) {
                targets.forEachIndexed { i, p -> p.setAttribute(states[i].clone()) }
                word.relayoutContent(targets.first().getStartOffset())
            }
            undoStack.add(Step(
                undo = { unfile().also { if (it) restore(before) } },
                redo = { file().also { if (it) restore(after) } },
            ))
            redoStack.clear()
            return true
        }
    }

    /** Inserts [text] before [offset]; line breaks in it split the paragraph (pasting several lines). */
    fun insertText(offset: Long, text: String): Boolean {
        synchronized(layoutLock) {
            ownError = null
            if (text.isEmpty()) return refuse("Nothing to insert")
            // after the last paragraph mark there is no paragraph to hold the text
            val wp = word.getDocument() as? WPDocument ?: return refuse("Not a Word document")
            if (!wp.isEditableArea(offset)) return refuse("Only the body, headers, footers and text boxes are editable")
            if (offset < 0 || offset >= wp.storyEnd(offset)) return refuse("Cannot insert after the end of the document")
            // in a cell added live: only the view changes, its text is taken from the view on save
            if (touchesNewCells(offset)) return grouped {
                var at = offset
                text.replace("\r\n", "\n").split('\n').withIndex().all { (i, part) ->
                    (if (i == 0) true else liveOnly(at, 1, { wp.splitMainParagraph(at) }, { wp.joinMainParagraph(at) }).also { at += 1 }) &&
                        (part.isEmpty() || liveOnly(at, part.length.toLong(), { wp.insertMainText(at, part) }, { wp.deleteMainText(at, at + part.length) }).also { at += part.length })
                }
            }
            val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            if (lines.length > 1 && lines.contains('\n')) {
                var at = offset
                for ((i, part) in lines.split('\n').withIndex()) {
                    if (i > 0) { if (!insertText(at, "\n")) return false; at += 1 }
                    if (part.isNotEmpty()) { if (!insertText(at, part)) return false; at += part.length }
                }
                return true
            }
            val doc = word.getDocument() as? WPDocument ?: return refuse("Not a Word document")
            val last = undoStack.lastOrNull() as? TypingStep
            // typing on at the end of the previous insert: one queued insert, one undo step
            if (last != null && last.at + last.text.length == offset && doc.insertMainText(offset, text)) {
                editor.undoLast()
                last.text += text
                last.edit.length = last.text.length.toLong()
                editor.insertText(last.original, last.text)
                track(last.edit)
                redoStack.clear()
                word.relayoutContent(offset)
                return true
            }
            // inside the text being typed (an IME editing its composing word)
            if (last != null && text != "\n" && offset >= last.at && offset < last.at + last.text.length) {
                editTyping(offset, offset, text)?.let { return it }
            }
            val original = toOriginal(offset)
            if (text == "\n") return splitParagraph(doc, offset, original)
            if (!editor.insertText(original, text)) return false
            if (!doc.insertMainText(offset, text)) {
                // a paragraph break or a non text position: saved, shown after reopening
                needsReopen = true
                undoStack.add(Step({ editor.undoLast() }, { editor.insertText(original, text) })); redoStack.clear()
                return true
            }
            val edit = Edit.Insert(offset, text.length.toLong())
            edits.add(edit)
            track(edit)
            undoStack.add(TypingStep(offset, original, text, edit)); redoStack.clear()
            word.relayoutContent(offset)
            return true
        }
    }

    /** Enter at [offset]: the paragraph splits at once; the file gets a new w:p. */
    private fun splitParagraph(doc: WPDocument, offset: Long, original: Long): Boolean {
        if (!editor.insertText(original, "\n")) return false
        if (!doc.splitMainParagraph(offset)) {
            needsReopen = true
            undoStack.add(Step({ editor.undoLast() }, { editor.insertText(original, "\n") })); redoStack.clear()
            return true
        }
        val edit = Edit.Insert(offset, 1)
        edits.add(edit)
        track(edit)
        undoStack.add(SplitStep(offset,
            undo = { editor.undoLast() && doc.joinMainParagraph(offset).also { edits.remove(edit); word.relayoutContent(offset) } },
            redo = { editor.insertText(original, "\n") && doc.splitMainParagraph(offset).also { track(edit); edits.add(edit); word.relayoutContent(offset) } },
        ))
        redoStack.clear()
        word.relayoutContent(offset)
        return true
    }

    private fun joinParagraphs(doc: WPDocument, mark: Long, os: Long, oe: Long): Boolean {
        if (!editor.deleteText(os, oe)) return false
        if (!doc.joinMainParagraph(mark)) {
            needsReopen = true
            undoStack.add(Step({ editor.undoLast() }, { editor.deleteText(os, oe) })); redoStack.clear()
            return true
        }
        val edit = Edit.Delete(mark, 1)
        edits.add(edit)
        undoStack.add(Step(
            undo = { editor.undoLast() && doc.splitMainParagraph(mark).also { edits.remove(edit); word.relayoutContent(mark) } },
            redo = { editor.deleteText(os, oe) && doc.joinMainParagraph(mark).also { edits.add(edit); word.relayoutContent(mark) } },
        ))
        redoStack.clear()
        word.relayoutContent(mark)
        return true
    }

    fun deleteText(start: Long, end: Long): Boolean {
        synchronized(layoutLock) {
            ownError = null
            if (end <= start) return refuse("Empty range")
            // like a picture of the file, one inserted live is not deleted as text
            if (newPictures.keys.any { it.getStartOffset() in start until end }) return refuse("Pictures and fields cannot be deleted as text")
            // Backspace right after Enter at the same place: take the Enter back
            (undoStack.lastOrNull() as? SplitStep)?.let { if (it.at == start && end == start + 1) return undo() }
            editTyping(start, end, "")?.let { return it }
            val doc = word.getDocument() as? WPDocument ?: return refuse("Not a Word document")
            if (doc.getLeaf(start) == null) return refuse("No text to delete there")
            val removedAll = doc.getText(start, end)
            // text inserted in this session, paragraph marks, or several paragraphs: piece by piece
            if (touchesTyped(start, end) || (removedAll.length > 1 && removedAll.contains('\n'))) return deletePieces(doc, start, end)
            val os = toOriginal(start)
            val oe = toOriginal(end)
            val removed = removedAll
            // a lone paragraph mark: join the two paragraphs (Backspace at a paragraph start)
            if (removed == "\n") return joinParagraphs(doc, start, os, oe)
            if (!editor.deleteText(os, oe)) return false
            if (!doc.deleteMainText(start, end)) {
                needsReopen = true
                undoStack.add(Step({ editor.undoLast() }, { editor.deleteText(os, oe) })); redoStack.clear()
                return true
            }
            val edit = Edit.Delete(start, end - start)
            edits.add(edit)
            undoStack.add(Step(
                undo = {
                    editor.undoLast() && doc.insertMainText(start, removed).also {
                        edits.remove(edit); word.relayoutContent(start)
                    }
                },
                redo = {
                    editor.deleteText(os, oe) && doc.deleteMainText(start, end).also {
                        edits.add(edit); word.relayoutContent(start)
                    }
                },
            ))
            redoStack.clear()
            word.relayoutContent(start)
            return true
        }
    }

    fun replaceText(start: Long, end: Long, text: String): Boolean {
        synchronized(layoutLock) {
            if (text.isEmpty()) return deleteText(start, end)
            ownError = null
            if (end <= start) return refuse("Empty range")
            if (word.getDocument().getLeaf(start) == null) return refuse("No text to replace there")
            val nl = text.indexOf('\n')
            if (nl >= 0) {
                // several lines: the first replaces the range, the rest is inserted after it
                if (nl == 0) return insertText(start, "\n") && (if (text.length > 1) replaceText(start + 1, end + 1, text.substring(1)) else deleteText(start + 1, end + 1))
                return replaceText(start, end, text.substring(0, nl)) && insertText(start + nl, text.substring(nl))
            }
            editTyping(start, end, text)?.let { return it }
            // over text inserted in this session: delete, then insert (its text is taken on save)
            if (touchesTyped(start, end)) return grouped { deleteText(start, end) && insertText(start, text) }
            // across paragraphs: the marks go piece by piece
            if ((word.getDocument() as? WPDocument)?.getText(start, end)?.contains('\n') == true) return grouped { deleteText(start, end) && insertText(start, text) }
            val doc = word.getDocument() as? WPDocument ?: return refuse("Not a Word document")
            val os = toOriginal(start)
            val oe = toOriginal(end)
            val removed = doc.getText(start, end)
            // one file operation: a delete then an insert at the same place would lose the insert
            if (!editor.replaceText(os, oe, text)) return false
            // the new text goes into the run of the replaced text: insert first, then delete the old
            if (!doc.insertMainText(start, text) ) {
                needsReopen = true
                undoStack.add(Step({ editor.undoLast() }, { editor.replaceText(os, oe, text) })); redoStack.clear()
                return true
            }
            val n = text.length.toLong()
            doc.deleteMainText(start + n, end + n)
            val delete = Edit.Delete(start, end - start)
            val insert = Edit.Insert(start, n)
            edits.add(delete); edits.add(insert)
            track(insert)
            undoStack.add(Step(
                undo = {
                    editor.undoLast() && doc.insertMainText(start + n, removed).also {
                        doc.deleteMainText(start, start + n)
                        edits.remove(insert); edits.remove(delete); word.relayoutContent(start)
                    }
                },
                redo = {
                    editor.replaceText(os, oe, text) && doc.insertMainText(start, text).also {
                        doc.deleteMainText(start + n, end + n)
                        track(insert)
                        edits.add(delete); edits.add(insert); word.relayoutContent(start)
                    }
                },
            ))
            redoStack.clear()
            word.relayoutContent(start)
            return true
        }
    }

    /**
     * Replaces [start, end) with [text] when the range lies in the text of the last typing step
     * (Backspace while typing, an IME changing its composing word): the step's text changes in
     * place, still one queued insert and one undo step. Null when the range is elsewhere.
     */
    private fun editTyping(start: Long, end: Long, text: String): Boolean? {
        val last = undoStack.lastOrNull() as? TypingStep ?: return null
        if (start < last.at || end > last.at + last.text.length) return null
        val doc = word.getDocument() as? WPDocument ?: return null
        // the new text goes into the run first, then the old text is removed
        if (text.isNotEmpty() && !doc.insertMainText(start, text)) return null
        if (end > start) doc.deleteMainText(start + text.length, end + text.length)
        val from = (start - last.at).toInt()
        val updated = last.text.substring(0, from) + text + last.text.substring((end - last.at).toInt())
        editor.undoLast()
        if (updated.isEmpty()) {
            undoStack.removeAt(undoStack.lastIndex)
            edits.remove(last.edit)
        } else {
            last.text = updated
            last.edit.length = updated.length.toLong()
            editor.insertText(last.original, updated)
            track(last.edit)
        }
        redoStack.clear()
        word.relayoutContent(start)
        return true
    }

    /** Text typed at one place; grows while the user keeps typing there. */
    private inner class TypingStep(val at: Long, val original: Long, var text: String, val edit: Edit.Insert) : Step(
        undo = { false }, redo = { false },
    ) {

        override fun runUndo(): Boolean {
            val doc = word.getDocument() as? WPDocument ?: return false
            if (!editor.undoLast()) return false
            doc.deleteMainText(at, at + text.length)
            edits.remove(edit)
            word.relayoutContent(at)
            return true
        }

        override fun runRedo(): Boolean {
            val doc = word.getDocument() as? WPDocument ?: return false
            if (!editor.insertText(original, text) || !doc.insertMainText(at, text)) return false
            track(edit)
            edits.add(edit)
            word.relayoutContent(at)
            return true
        }
    }

    fun undo(): Boolean {
        synchronized(layoutLock) {
            val step = undoStack.lastOrNull() ?: return false
            if (!step.runUndo()) return false
            undoStack.removeAt(undoStack.lastIndex); redoStack.add(step); return true
        }
    }

    fun redo(): Boolean {
        synchronized(layoutLock) {
            val step = redoStack.lastOrNull() ?: return false
            if (!step.runRedo()) return false
            redoStack.removeAt(redoStack.lastIndex); undoStack.add(step); return true
        }
    }

    fun save(target: File): EditResult = synchronized(layoutLock) { editor.save(target, overrides(), cellFills()) }


    /** Leaves of [start, end) in every paragraph it touches, split at the ends. */
    private fun leaves(start: Long, end: Long): List<LeafElement> {
        val doc = word.getDocument()
        val result = ArrayList<LeafElement>()
        var offset = start
        while (offset < end) {
            val para = doc.getParagraph(offset) as? ParagraphElement ?: break
            result.addAll(para.leavesFor(maxOf(start, para.getStartOffset()), minOf(end, para.getEndOffset())))
            if (para.getEndOffset() <= offset) break
            offset = para.getEndOffset()
        }
        return result
    }

    private fun format(start: Long, end: Long, fileOp: (DocxEditor, Long, Long) -> Boolean, apply: (IAttributeSet) -> Unit): Boolean {
        synchronized(layoutLock) {
            ownError = null
            if (end <= start) return refuse("Empty range")
            // text inserted in this session is written from the view on save: only original text
            // takes file operations
            val parts = ArrayList<Pair<Long, Long>>()
            var i = start
            while (i < end) {
                val inserted = insertedAt(i)
                var j = i + 1
                while (j < end && insertedAt(j) == inserted) j++
                if (!inserted) parts.add(i to j)
                i = j
            }
            val fileOps = parts.map { (s, e) ->
                val os = toOriginal(s); val oe = toOriginal(e)
                ({ fileOp(editor, os, oe) } to { editor.undoLast() })
            }
            fun redoFile(): Boolean {
                for ((k, op) in fileOps.withIndex()) if (!op.first()) { for (u in fileOps.take(k).asReversed()) u.second(); return false }
                return true
            }
            if (!redoFile()) return false
            // by offsets, not leaf objects: undoing and redoing text before this step makes new leaves
            val before = leaves(start, end).map { Triple(it.getStartOffset(), it.getEndOffset(), it.getAttribute().clone()) }
            fun applyNow() {
                leaves(start, end).forEach { apply(it.getAttribute()) }
                word.relayoutContent(start)
            }
            fun restoreBefore() {
                for ((s, e, attr) in before) leaves(s, e).forEach { it.setAttribute(attr.clone()) }
                word.relayoutContent(start)
            }
            applyNow()
            undoStack.add(Step(
                undo = { fileOps.asReversed().all { it.second() }.also { restoreBefore() } },
                redo = { redoFile().also { if (it) applyNow() } },
            ))
            redoStack.clear()
            return true
        }
    }

    /** True when the character at [pos] was inserted in this session (typed, pasted, an Enter). */
    private fun insertedAt(pos: Long): Boolean {
        var x = pos
        for (edit in edits.asReversed()) if (area(edit.at) == area(pos)) when (edit) {
            is Edit.Insert -> {
                if (x >= edit.at && x < edit.at + edit.length) return true
                if (x >= edit.at + edit.length) x -= edit.length
            }
            is Edit.Delete -> if (x >= edit.at) x += edit.length
            is Edit.Move -> x = edit.back(x)
        }
        return false
    }

    /** A picture inserted live: the [op] that makes it in the file, at original [anchor]. */
    private class NewPicture(var op: Any?, val anchor: Long)
    private val newPictures = java.util.IdentityHashMap<IElement, NewPicture>()
    private fun newPictureAt(offset: Long): NewPicture? =
        word.getDocument().getLeaf(offset)?.takeIf { it.getStartOffset() == offset }?.let { newPictures[it] }

    /**
     * An in-line picture at [offset], [widthPx] x [heightPx] (96 dpi), shown at once; in typed text
     * (which has no place in the file) or where the view cannot take it, after the file is read
     * again ([needsReopen]). The image file is read on save: it must stay until then.
     */
    fun insertImage(offset: Long, image: File, widthPx: Int, heightPx: Int): Boolean = synchronized(layoutLock) {
        ownError = null
        needsFlush = false
        if (touchesNewCells(offset)) return refuseInNewCells()
        val o = toOriginal(offset)
        if (!checked(editor.insertImage(o, image, widthPx, heightPx))) return false
        val doc = word.getDocument() as? WPDocument
        // like a picture read from the file: a one-char object whose shape draws the image
        val pictures = word.getControl().getSysKit().getPictureManage()
        val picture = com.wxiwei.office.common.shape.PictureShape().apply {
            pictureIndex = pictures.addPicture(com.wxiwei.office.common.picture.Picture().apply { tempFilePath = image.absolutePath; setPictureType(image.extension) })
            setZoomX(1000.toShort()); setZoomY(1000.toShort())
            bounds = com.wxiwei.office.java.awt.Rectangle(0, 0, widthPx, heightPx)
        }
        val shape = com.wxiwei.office.common.shape.WPPictureShape().apply {
            setPictureShape(picture); bounds = picture.bounds; setWrap(com.wxiwei.office.common.shape.WPAbstractShape.WRAP_OLE)
        }
        val leaf = LeafElement("1")
        am.setShapeID(leaf.getAttribute()!!, word.getControl().getSysKit().getWPShapeManage().addShape(shape))
        // before a char in the file: text typed at that place is written before it too
        if (doc == null || insertedAt(offset) || !doc.insertMainObject(offset, leaf)) {
            needsReopen = true
            undoStack.add(Step({ editor.undoLast() }, { editor.insertImage(o, image, widthPx, heightPx) })); redoStack.clear()
            return true
        }
        val made = NewPicture(editor.lastOp(), o)
        val edit = Edit.Insert(offset, 1)
        edits.add(edit); newPictures[leaf] = made
        word.relayoutContent(offset)
        undoStack.add(Step(
            undo = { editor.undoLast().also { if (it) {
                doc.removeMainObject(leaf.getStartOffset()); edits.remove(edit); newPictures.remove(leaf); word.relayoutContent(offset)
            } } },
            redo = { editor.insertImage(o, image, widthPx, heightPx).also { if (it) {
                made.op = editor.lastOp(); doc.insertMainObject(offset, leaf); edits.add(edit); newPictures[leaf] = made; word.relayoutContent(offset)
            } } },
        ))
        redoStack.clear()
        true
    }

    /** The picture or shape whose one-char object is at [offset], or null. */
    fun shapeAt(offset: Long): com.wxiwei.office.common.shape.IShape? {
        val doc = word.getDocument()
        val leaf = doc.getLeaf(offset) ?: return null
        if (leaf.getEndOffset() - leaf.getStartOffset() != 1L) return null
        val id = am.getShapeID(leaf.getAttribute())
        if (id < 0) return null
        return word.getControl().getSysKit().getWPShapeManage().getShape(id)
    }

    private fun objectEdit(fileOp: () -> Boolean): Boolean = synchronized(layoutLock) {
        ownError = null
        if (!fileOp()) return false
        needsReopen = true
        undoStack.add(Step({ editor.undoLast() }, fileOp)); redoStack.clear()
        true
    }

    /** The picture at [offset] and the inner picture a WPPictureShape draws with; their boxes change together. */
    private fun shapeBoxes(offset: Long): List<com.wxiwei.office.java.awt.Rectangle> {
        val shape = shapeAt(offset) ?: return emptyList()
        val inner = (shape as? com.wxiwei.office.common.shape.WPPictureShape)?.getPictureShape()?.bounds
        return listOfNotNull(shape.bounds, inner?.takeIf { it !== shape.bounds })
    }

    /**
     * A picture edit shown at once: [fileOp] is queued for save, [live] changes the shape in the
     * view (and [back] undoes that), then the pages are laid out again from [offset]. Without a
     * shape in the view it is shown after the file is read again ([needsReopen]).
     */
    private fun liveObjectEdit(offset: Long, fileOp: () -> Boolean, live: () -> Boolean, back: () -> Unit): Boolean = synchronized(layoutLock) {
        ownError = null
        if (!fileOp()) return false
        if (!live()) {
            needsReopen = true
            undoStack.add(Step({ editor.undoLast() }, fileOp)); redoStack.clear()
            return true
        }
        word.relayoutContent(offset)
        undoStack.add(Step(
            undo = { editor.undoLast().also { if (it) { back(); word.relayoutContent(offset) } } },
            redo = { fileOp() && live().also { word.relayoutContent(offset) } },
        ))
        redoStack.clear()
        true
    }

    /** New size (pixels at 96 dpi) of the picture at [offset]; shown at once. */
    fun resizeObject(offset: Long, widthPx: Int, heightPx: Int): Boolean {
        // a picture inserted live is found by the op that makes it
        val made = newPictureAt(offset)
        val o = made?.anchor ?: toOriginal(offset)
        val boxes = shapeBoxes(offset)
        val before = boxes.map { it.width to it.height }
        return liveObjectEdit(offset, { editor.resizeObject(o, widthPx * EMU_PER_PX, heightPx * EMU_PER_PX, made?.op) },
            live = { boxes.isNotEmpty().also { boxes.forEach { b -> b.width = widthPx; b.height = heightPx } } },
            back = { boxes.forEachIndexed { i, b -> b.width = before[i].first; b.height = before[i].second } })
    }

    /** Moves the in-line picture at [from] to the text position [to] (it lands at [to] - 1 when [to] > [from]); shown at once. */
    fun moveObject(from: Long, to: Long): Boolean = synchronized(layoutLock) {
        ownError = null
        needsFlush = false
        if (touchesNewCells(from) || touchesNewCells(to)) return refuseInNewCells()
        val made = newPictureAt(from)
        val of = made?.anchor ?: toOriginal(from)
        val ot = toOriginal(to)
        if (!editor.moveObject(of, ot, made?.op)) return false
        val doc = word.getDocument() as? WPDocument
        if (doc == null || !doc.moveMainObject(from, to)) {
            needsReopen = true
            undoStack.add(Step({ editor.undoLast() }, { editor.moveObject(of, ot, made?.op) })); redoStack.clear()
            return true
        }
        // for the offset map: one char from [from] to where it landed
        val at = if (to > from) to - 1 else to
        val moved = Edit.Move(from, 1, at)
        edits.add(moved)
        val first = minOf(from, at)
        word.relayoutContent(first)
        // back: from [at] to before the char now at [from] (+1 when it moved up past it)
        val home = if (from > at) from + 1 else from
        undoStack.add(Step(
            undo = { editor.undoLast() && doc.moveMainObject(at, home).also { edits.remove(moved); word.relayoutContent(first) } },
            redo = { editor.moveObject(of, ot, made?.op) && doc.moveMainObject(from, to).also { edits.add(moved); word.relayoutContent(first) } },
        ))
        redoStack.clear()
        true
    }

    /** Moves the floating picture at [offset] by [dxPx], [dyPx]; shown at once. */
    fun shiftObject(offset: Long, dxPx: Int, dyPx: Int): Boolean {
        val o = toOriginal(offset)
        val box = shapeAt(offset)?.bounds
        return liveObjectEdit(offset, { editor.shiftObject(o, dxPx * EMU_PER_PX, dyPx * EMU_PER_PX) },
            live = { box != null && true.also { box.x += dxPx; box.y += dyPx } },
            back = { box?.let { it.x -= dxPx; it.y -= dyPx } })
    }

    /** A cell standing for a later grid column of a merged cell (no offsets, no width of its own). */
    private fun isSpanFiller(cell: IElement): Boolean =
        cell.getStartOffset() == cell.getEndOffset() && cell.getAttribute()?.getAttribute(com.wxiwei.office.constant.wp.AttrIDConstant.TABLE_CELL_WIDTH_ID) == Int.MIN_VALUE

    /** The cells of [table] ending at grid border [boundary] (they grow by d) and starting at it (they shrink by d). */
    private fun cellsAt(table: com.wxiwei.office.wp.model.TableElement, boundary: Int): Pair<List<IElement>, List<IElement>> {
        val before = ArrayList<IElement>(); val after = ArrayList<IElement>()
        for (r in 0 until table.rowCount()) {
            val row = table.getElementForIndex(r) as? com.wxiwei.office.wp.model.RowElement ?: continue
            var k = 0
            while (k < row.getCellNumber()) {
                val cell = row.getElementForIndex(k) ?: break
                var span = 1
                while (k + span < row.getCellNumber() && row.getElementForIndex(k + span)?.let { isSpanFiller(it) } == true) span++
                if (k + span == boundary) before.add(cell)
                if (k == boundary) after.add(cell)
                k += span
            }
        }
        return before to after
    }

    /**
     * Moves the border after grid column [boundary] - 1 of the table holding [offset] by
     * [dTwips] (kept so no column gets narrower than 240 twips), shown at once. The column after
     * it gets narrower; at the right edge the table gets wider. Returns the move made, or null.
     */
    fun resizeTableColumn(offset: Long, boundary: Int, dTwips: Int): Int? = synchronized(layoutLock) {
        ownError = null
        val doc = word.getDocument() as? WPDocument ?: return null.also { refuse("Not a Word document") }
        val table = doc.getParagraph0(offset) as? com.wxiwei.office.wp.model.TableElement ?: return null.also { refuse("No table here") }
        val (grow, shrink) = cellsAt(table, boundary)
        if (grow.isEmpty()) return null.also { refuse("No column border there") }
        val min = 240
        var d = dTwips
        grow.forEach { d = maxOf(d, min - am.getTableCellWidth(it.getAttribute())) }
        shrink.forEach { d = minOf(d, am.getTableCellWidth(it.getAttribute()) - min) }
        if (d == 0) return null.also { refuse("Column at its smallest") }
        val made = newTables[table]
        val o = made?.anchor ?: toOriginal(table.getStartOffset(), char = true)
        val ref = made?.let { DocxEditor.CellRef(0, 0, 0, 0, it.op) }
        val start = table.getStartOffset()
        fun apply(by: Int) {
            grow.forEach { am.setTableCellWidth(it.getAttribute(), am.getTableCellWidth(it.getAttribute()) + by) }
            shrink.forEach { am.setTableCellWidth(it.getAttribute(), am.getTableCellWidth(it.getAttribute()) - by) }
        }
        val step = d
        return if (liveObjectEdit(start, { checked(editor.resizeTableColumn(o, boundary, step, ref)) }, live = { apply(step); true }, back = { apply(-step) })) d else null
    }

    /** The row starting at [rowStart] is at least [twips] high (the text still fits), shown at once. */
    fun setTableRowHeight(rowStart: Long, twips: Int): Boolean = synchronized(layoutLock) {
        ownError = null
        needsFlush = false
        val doc = word.getDocument() as? WPDocument ?: return refuse("Not a Word document")
        val table = doc.getParagraph0(rowStart) as? com.wxiwei.office.wp.model.TableElement ?: return refuse("No table here")
        val r = (0 until table.rowCount()).firstOrNull { table.getElementForIndex(it)?.getStartOffset() == rowStart } ?: return refuse("No row here")
        val row = table.getElementForIndex(r)!!
        val before = am.getTableRowHeight(row.getAttribute())
        val (o, ref) = rowRef(table, r) ?: return refuseInNewCells()
        val h = maxOf(0, twips)
        liveObjectEdit(rowStart, { checked(editor.setTableRowHeight(o, h, ref)) },
            live = { am.setTableRowHeight(row.getAttribute(), h); true },
            back = { am.setTableRowHeight(row.getAttribute(), before) })
    }

    // ---- rows and columns added or removed live --------------------------------------------

    /**
     * Cells added live in this session, with the queued file operation that makes them ([op], a
     * [DocxEditor.lastOp] handle): they have no place in the original file, so their text is
     * taken from the view on save, and what needs a file position inside them waits for a save.
     */
    private class NewCells(var op: Any?, val cells: List<IElement>)
    private val newCells = ArrayList<NewCells>()
    /** Cells added live, then removed with their row or column: nothing of them to write. */
    private val goneCells: MutableSet<IElement> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    private fun isNew(cell: IElement) = newCells.any { g -> g.cells.any { it === cell } }

    /** A table inserted live: the [op] that makes it in the file, after the paragraph at original [anchor]. */
    private class NewTable(var op: Any?, val anchor: Long)
    private val newTables = java.util.IdentityHashMap<com.wxiwei.office.wp.model.TableElement, NewTable>()

    /**
     * How the file finds cell [k] of row [r] of [table]: by its original offset, or, for a cell
     * added live, by counting from an original cell of the table ([DocxEditor.CellRef]). Null when
     * no cell of the table is in the file yet.
     */
    private fun cellRef(table: com.wxiwei.office.wp.model.TableElement, r: Int, k: Int): Pair<Long, DocxEditor.CellRef?>? {
        val rows = (0 until table.rowCount()).map { table.getElementForIndex(it) as com.wxiwei.office.wp.model.RowElement }
        val cell = rows[r].getElementForIndex(k) ?: return null
        newTables[table]?.let { return it.anchor to DocxEditor.CellRef(0, 0, r, k, it.op) }
        if (!isNew(cell)) return toOriginal(cell.getStartOffset(), char = true) to null
        for ((i, row) in rows.withIndex()) for (j in 0 until row.getCellNumber()) {
            val c = row.getElementForIndex(j) ?: continue
            if (!isNew(c)) return toOriginal(c.getStartOffset(), char = true) to DocxEditor.CellRef(i, j, r, k)
        }
        return null
    }

    /** [cellRef] of row [r]: one of its cells in the file, else its first cell. */
    private fun rowRef(table: com.wxiwei.office.wp.model.TableElement, r: Int): Pair<Long, DocxEditor.CellRef?>? {
        val row = table.getElementForIndex(r) as com.wxiwei.office.wp.model.RowElement
        val k = (0 until row.getCellNumber()).firstOrNull { row.getElementForIndex(it)?.let { c -> !isNew(c) } == true } ?: 0
        return cellRef(table, r, k)
    }

    /** True after an edit was refused because it needs the document saved first ([touchesNewCells]). */
    var needsFlush = false
        private set

    /** True when [start, end] (a caret when equal) touches a cell added live in this session. */
    fun touchesNewCells(start: Long, end: Long = start): Boolean = newCells.any { g ->
        g.cells.any { c -> c.getStartOffset() <= maxOf(start, end) && c.getEndOffset() > start }
    }

    /** Refusal of an edit inside cells added live: saving the working copy first gives them their place. */
    private fun refuseInNewCells(): Boolean {
        needsFlush = true
        return refuse("Lưu ô vừa thêm trước")
    }

    private fun isMergedCell(cell: IElement) = isSpanFiller(cell) || am.isTableVerMerged(cell.getAttribute()) || am.isTableVerFirstMerged(cell.getAttribute())

    /** No merged cells (across rows or columns) anywhere in [table]. */
    private fun plainTable(table: com.wxiwei.office.wp.model.TableElement): Boolean = (0 until table.rowCount()).all { r ->
        val row = table.getElementForIndex(r) as? com.wxiwei.office.wp.model.RowElement ?: return@all false
        (0 until row.getCellNumber()).all { k -> row.getElementForIndex(k)?.let { !isMergedCell(it) } ?: false }
    }

    /** An empty cell like [like]: its properties (no merge), one empty paragraph like its first one. */
    private fun emptyCellLike(doc: WPDocument, like: IElement, width: Int? = null): Pair<com.wxiwei.office.wp.model.CellElement, com.wxiwei.office.simpletext.model.ParagraphElement> {
        val cell = com.wxiwei.office.wp.model.CellElement()
        cell.setAttribute(like.getAttribute()!!.clone())
        am.setTableVerMerged(cell.getAttribute(), false); am.setTableVerFirstMerged(cell.getAttribute(), false)
        if (width != null) am.setTableCellWidth(cell.getAttribute(), width)
        val first = doc.getParagraph(like.getStartOffset()) as? com.wxiwei.office.simpletext.model.ParagraphElement
        val para = com.wxiwei.office.simpletext.model.ParagraphElement()
        first?.getAttribute()?.let { para.setAttribute(it.clone()) }
        val markLeaf = first?.let { p -> (0 until p.leafCount()).mapNotNull { p.getElementForIndex(it) }.lastOrNull() }
        val mark = LeafElement("\n")
        markLeaf?.getAttribute()?.let { mark.setAttribute(it.clone()) }
        // a mark, never a picture: the layout takes any leaf with a shape id for one
        mark.getAttribute()?.removeAttribute(com.wxiwei.office.constant.wp.AttrIDConstant.FONT_SHAPE_ID)
        para.setStartOffset(0); para.setEndOffset(1)
        mark.setStartOffset(0); mark.setEndOffset(1)
        para.appendLeaf(mark)
        return cell to para
    }

    /** [n] chars put in at [at] by [put] (taken out by [take]), in the view only. */
    private fun liveOnly(at: Long, n: Long, put: () -> Boolean, take: () -> Boolean): Boolean {
        if (!put()) return refuse("Cannot insert here")
        val edit = Edit.Insert(at, n)
        edits.add(edit)
        word.relayoutContent(at)
        undoStack.add(Step(
            undo = { take().also { edits.remove(edit); word.relayoutContent(at) } },
            redo = { put().also { edits.add(edit); word.relayoutContent(at) } },
        ))
        redoStack.clear()
        return true
    }

    /** A file edit shown after the file is read again ([needsReopen]), with its undo. */
    private fun reopenEdit(fileOp: () -> Boolean): Boolean = synchronized(layoutLock) {
        ownError = null
        if (!fileOp()) return false
        needsReopen = true
        undoStack.add(Step({ editor.undoLast() }, fileOp)); redoStack.clear()
        true
    }

    /**
     * A new empty row [below] (or above) the row holding [offset]: shown at once for a table
     * without merged cells, otherwise after the file is read again ([needsReopen]).
     */
    fun insertTableRow(offset: Long, below: Boolean): Boolean = synchronized(layoutLock) {
        ownError = null; needsFlush = false
        val place = cellAt(offset) ?: return refuse("Not in a table")
        val doc = word.getDocument() as WPDocument
        val table = doc.getParagraph0(offset) as com.wxiwei.office.wp.model.TableElement
        val (o, ref) = cellRef(table, place.row, place.cell) ?: return refuseInNewCells()
        if (!plainTable(table)) return reopenEdit { checked(editor.insertTableRow(o, below, ref)) }
        val source = table.getElementForIndex(place.row) as com.wxiwei.office.wp.model.RowElement
        val row = com.wxiwei.office.wp.model.RowElement()
        row.setAttribute(source.getAttribute()!!.clone())
        am.setTableHeaderRow(row.getAttribute(), false)
        val paras = ArrayList<List<com.wxiwei.office.simpletext.model.ParagraphElement>?>()
        for (k in 0 until source.getCellNumber()) {
            val (cell, para) = emptyCellLike(doc, source.getElementForIndex(k)!!)
            row.appendCell(cell); paras.add(listOf(para))
        }
        val index = if (below) place.row + 1 else place.row
        if (!checked(editor.insertTableRow(o, below, ref))) return false
        val group = NewCells(editor.lastOp(), (0 until row.getCellNumber()).map { row.getElementForIndex(it)!! })
        doc.insertTableRow(table, index, row, paras)
        val edit = Edit.Insert(row.getStartOffset(), row.getEndOffset() - row.getStartOffset())
        edits.add(edit); newCells.add(group)
        val from = table.getStartOffset()
        word.relayoutContent(from)
        undoStack.add(Step(
            undo = { editor.undoLast() && (doc.removeTableRow(table, index) != null).also { edits.remove(edit); newCells.remove(group); word.relayoutContent(from) } },
            redo = { editor.insertTableRow(o, below, ref).also { if (it) {
                group.op = editor.lastOp(); doc.insertTableRow(table, index, row, paras); edits.add(edit); newCells.add(group); word.relayoutContent(from)
            } } },
        ))
        redoStack.clear()
        true
    }

    /**
     * A new empty column [right] of (or left of) the cell holding [offset]; the columns shrink so
     * the table keeps its width. Shown at once for a table without merged cells, otherwise after
     * the file is read again ([needsReopen]).
     */
    fun insertTableColumn(offset: Long, right: Boolean): Boolean = synchronized(layoutLock) {
        ownError = null; needsFlush = false
        val place = cellAt(offset) ?: return refuse("Not in a table")
        val doc = word.getDocument() as WPDocument
        val table = doc.getParagraph0(offset) as com.wxiwei.office.wp.model.TableElement
        val (o, ref) = cellRef(table, place.row, place.cell) ?: return refuseInNewCells()
        val rows = (0 until table.rowCount()).map { table.getElementForIndex(it) as com.wxiwei.office.wp.model.RowElement }
        if (!plainTable(table) || rows.any { it.getCellNumber() != rows[place.row].getCellNumber() }) return reopenEdit { checked(editor.insertTableColumn(o, right, ref)) }
        val at = if (right) place.cell + 1 else place.cell
        // like the file: the new column as wide as the cell's column was, all of them scaled to the old width
        val widths = (0 until rows[place.row].getCellNumber()).map { am.getTableCellWidth(rows[place.row].getElementForIndex(it)!!.getAttribute()) }
        val w = widths[place.cell]
        val total = widths.sum()
        val scale = if (total > 0) total.toDouble() / (total + w) else 1.0
        val before = rows.map { r -> (0 until r.getCellNumber()).map { am.getTableCellWidth(r.getElementForIndex(it)!!.getAttribute()) } }
        val made = rows.map { r -> emptyCellLike(doc, r.getElementForIndex(place.cell)!!, Math.round(w * scale).toInt()) }
        if (!checked(editor.insertTableColumn(o, right, ref))) return false
        val group = NewCells(editor.lastOp(), made.map { it.first })
        val added = ArrayList<Edit.Insert>()
        fun apply() {
            added.clear()
            rows.forEachIndexed { i, r ->
                for (k in 0 until r.getCellNumber()) r.getElementForIndex(k)!!.getAttribute()?.let { a -> am.setTableCellWidth(a, Math.round(before[i][k] * scale).toInt()) }
                doc.insertTableCell(table, r, at, made[i].first, listOf(made[i].second))
                added.add(Edit.Insert(made[i].first.getStartOffset(), 1).also { edits.add(it) })
            }
            newCells.add(group)
        }
        fun back() {
            for ((i, r) in rows.withIndex().reversed()) {
                doc.removeTableCell(table, r, at)
                for (k in 0 until r.getCellNumber()) r.getElementForIndex(k)!!.getAttribute()?.let { a -> am.setTableCellWidth(a, before[i][k]) }
            }
            added.forEach { edits.remove(it) }
            newCells.remove(group)
        }
        apply()
        val from = table.getStartOffset()
        word.relayoutContent(from)
        undoStack.add(Step(
            undo = { editor.undoLast().also { if (it) { back(); word.relayoutContent(from) } } },
            redo = { editor.insertTableColumn(o, right, ref).also { if (it) { group.op = editor.lastOp(); apply(); word.relayoutContent(from) } } },
        ))
        redoStack.clear()
        true
    }

    /** Removes the row holding [offset]: shown at once for a table without merged cells, otherwise after the file is read again. */
    fun deleteTableRow(offset: Long): Boolean = synchronized(layoutLock) {
        ownError = null; needsFlush = false
        val doc = word.getDocument() as? WPDocument ?: return refuse("Not a Word document")
        val table = doc.getParagraph0(offset) as? com.wxiwei.office.wp.model.TableElement ?: return refuse("Not in a table")
        if (table.rowCount() <= 1) return refuse("Bảng chỉ còn một hàng")
        val place = cellAt(offset) ?: return refuse("Not in a table")
        val row = table.getElementForIndex(place.row) as com.wxiwei.office.wp.model.RowElement
        val (o, ref) = rowRef(table, place.row) ?: return refuseInNewCells()
        if (!plainTable(table)) return reopenEdit { checked(editor.deleteTableRow(o, ref)) }
        if (!checked(editor.deleteTableRow(o, ref))) return false
        val cells = (0 until row.getCellNumber()).map { row.getElementForIndex(it)!! }
        // its paragraphs, cell by cell, to put back on undo
        val paras = (0 until row.getCellNumber()).map { k ->
            val cell = row.getElementForIndex(k)!!
            paragraphsIn(doc, cell.getStartOffset(), cell.getEndOffset())
        }
        val start = row.getStartOffset(); val len = row.getEndOffset() - start
        doc.removeTableRow(table, place.row)
        val edit = Edit.Delete(start, len)
        edits.add(edit)
        goneCells.addAll(cells)
        val from = table.getStartOffset()
        word.relayoutContent(from)
        undoStack.add(Step(
            undo = { editor.undoLast().also { if (it) { doc.insertTableRow(table, place.row, row, paras); edits.remove(edit); goneCells.removeAll(cells); word.relayoutContent(from) } } },
            redo = { editor.deleteTableRow(o, ref).also { if (it) { doc.removeTableRow(table, place.row); edits.add(edit); goneCells.addAll(cells); word.relayoutContent(from) } } },
        ))
        redoStack.clear()
        true
    }

    /** Removes the column of the cell holding [offset]; the others widen so the table keeps its width. Shown at once for a table without merged cells. */
    fun deleteTableColumn(offset: Long): Boolean = synchronized(layoutLock) {
        ownError = null; needsFlush = false
        val place = cellAt(offset) ?: return refuse("Not in a table")
        val doc = word.getDocument() as WPDocument
        val table = doc.getParagraph0(offset) as com.wxiwei.office.wp.model.TableElement
        val rows = (0 until table.rowCount()).map { table.getElementForIndex(it) as com.wxiwei.office.wp.model.RowElement }
        if (place.span >= rows[place.row].getCellNumber()) return refuse("Bảng chỉ còn một cột")
        val (o, ref) = cellRef(table, place.row, place.cell) ?: return refuseInNewCells()
        if (!plainTable(table) || rows.any { it.getCellNumber() != rows[place.row].getCellNumber() }) return reopenEdit { checked(editor.deleteTableColumn(o, ref)) }
        if (!checked(editor.deleteTableColumn(o, ref))) return false
        val c = place.cell
        val before = rows.map { r -> (0 until r.getCellNumber()).map { am.getTableCellWidth(r.getElementForIndex(it)!!.getAttribute()) } }
        val total = before[place.row].sum()
        val kept = total - before[place.row][c]
        val scale = if (kept > 0) total.toDouble() / kept else 1.0
        val cells = rows.map { it.getElementForIndex(c)!! }
        val paras = cells.map { paragraphsIn(doc, it.getStartOffset(), it.getEndOffset()) }
        val removed = ArrayList<Edit.Delete>()
        fun apply() {
            removed.clear()
            rows.forEachIndexed { i, r ->
                val cell = r.getElementForIndex(c)!!
                val e = Edit.Delete(cell.getStartOffset(), cell.getEndOffset() - cell.getStartOffset())
                doc.removeTableCell(table, r, c)
                edits.add(e); removed.add(e)
                for (k in 0 until r.getCellNumber()) r.getElementForIndex(k)!!.getAttribute()?.let { a -> am.setTableCellWidth(a, Math.round(am.getTableCellWidth(a) * scale).toInt()) }
            }
            goneCells.addAll(cells)
        }
        fun back() {
            for ((i, r) in rows.withIndex().reversed()) {
                doc.insertTableCell(table, r, c, cells[i] as com.wxiwei.office.wp.model.CellElement, paras[i])
                for (k in 0 until r.getCellNumber()) r.getElementForIndex(k)!!.getAttribute()?.let { a -> am.setTableCellWidth(a, before[i][k]) }
            }
            removed.forEach { edits.remove(it) }
            goneCells.removeAll(cells)
        }
        apply()
        val from = table.getStartOffset()
        word.relayoutContent(from)
        undoStack.add(Step(
            undo = { editor.undoLast().also { if (it) { back(); word.relayoutContent(from) } } },
            redo = { editor.deleteTableColumn(o, ref).also { if (it) { apply(); word.relayoutContent(from) } } },
        ))
        redoStack.clear()
        true
    }

    /** The paragraphs of the main text in [start, end), in order. */
    private fun paragraphsIn(doc: WPDocument, start: Long, end: Long): List<com.wxiwei.office.simpletext.model.ParagraphElement> {
        val out = ArrayList<com.wxiwei.office.simpletext.model.ParagraphElement>()
        var o = start
        while (o < end) {
            val p = doc.getParagraph(o) as? com.wxiwei.office.simpletext.model.ParagraphElement ?: break
            out.add(p)
            o = maxOf(o + 1, p.getEndOffset())
        }
        return out
    }

    /** Text and run formatting shown in each cell added live, by the file operation that makes it. */
    private fun cellFills(): Map<Any, List<DocxEditor.InsertOverride?>> {
        val doc = word.getDocument()
        val out = java.util.IdentityHashMap<Any, List<DocxEditor.InsertOverride?>>()
        for (g in newCells) {
            val op = g.op ?: continue
            out[op] = g.cells.map { c ->
                if (c in goneCells) return@map null
                val s = c.getStartOffset(); val e = c.getEndOffset() - 1 // without the cell's last mark
                val paras = paragraphsIn(doc as WPDocument, c.getStartOffset(), c.getEndOffset()).map { paraFormat(it.getAttribute()!!) }
                if (e <= s) DocxEditor.InsertOverride("", emptyList(), paras) else DocxEditor.InsertOverride(doc.getText(s, e), runFormats(s, e), paras)
            }
        }
        return out
    }

    /** Cell position of [offset] in a body table: table index, row index, cell (grid column) index, the cell's column span. */
    class CellPlace(val table: Int, val row: Int, val cell: Int, val span: Int)

    fun cellAt(offset: Long): CellPlace? {
        val doc = word.getDocument() as? WPDocument ?: return null
        val table = doc.getParagraph0(offset) as? com.wxiwei.office.wp.model.TableElement ?: return null
        val tables = doc.getTableCollection(offset) ?: return null
        val ti = (0 until tables.size()).firstOrNull { tables.getElementForIndex(it) === table } ?: return null
        for (r in 0 until table.rowCount()) {
            val row = table.getElementForIndex(r) as? com.wxiwei.office.wp.model.RowElement ?: continue
            if (offset < row.getStartOffset() || offset >= row.getEndOffset()) continue
            for (k in 0 until row.getCellNumber()) {
                val cell = row.getElementForIndex(k) ?: continue
                if (offset < cell.getStartOffset() || offset >= cell.getEndOffset()) continue
                var span = 1
                while (k + span < row.getCellNumber() && row.getElementForIndex(k + span)?.let { isSpanFiller(it) } == true) span++
                return CellPlace(ti, r, k, span)
            }
        }
        return null
    }

    /** Start of cell [cell] of row [row] of body table [table], or -1. */
    fun cellStart(table: Int, row: Int, cell: Int): Long {
        val doc = word.getDocument() as? WPDocument ?: return -1
        val t = doc.getTableCollection(0)?.getElementForIndex(table) as? com.wxiwei.office.wp.model.TableElement ?: return -1
        val r = t.getElementForIndex(row) as? com.wxiwei.office.wp.model.RowElement ?: return -1
        val c = r.getElementForIndex(cell) ?: return -1
        return if (isSpanFiller(c)) -1 else c.getStartOffset()
    }

    /** Offsets of the table the last [moveTable] showed at once, where it is now. */
    var movedTable: LongRange? = null
        private set

    /**
     * Moves the table holding [offset] before the body paragraph (or table) holding [to], or [after]
     * it; shown at once. Returns false when it cannot go there.
     */
    fun moveTable(offset: Long, to: Long, after: Boolean): Boolean = synchronized(layoutLock) {
        ownError = null
        needsFlush = false
        if (touchesNewCells(to)) return refuseInNewCells()
        movedTable = null
        val doc = word.getDocument() as? WPDocument ?: return refuse("Not a Word document")
        val table = doc.getParagraph0(offset) as? com.wxiwei.office.wp.model.TableElement ?: return refuse("No table here")
        val block = doc.getParagraph0(to) ?: return refuse("No paragraph here")
        val start = table.getStartOffset(); val end = table.getEndOffset()
        // the live view puts it before a block: the one after [block] for [after]
        val at = if (after) block.getEndOffset() else block.getStartOffset()
        if (at in start..end) return refuse("The table is already there")
        // a table inserted live is found by the op that makes it
        val made = newTables[table]
        val os = made?.anchor ?: toOriginal(start, char = true); val ot = toOriginal(to)
        val ref = made?.let { DocxEditor.CellRef(0, 0, 0, 0, it.op) }
        if (!checked(editor.moveTable(os, ot, after, ref))) return false
        val len = end - start
        if (!doc.moveMainTable(start, end, at)) {
            needsReopen = true
            undoStack.add(Step({ editor.undoLast() }, { editor.moveTable(os, ot, after, ref) })); redoStack.clear()
            return true
        }
        val dest = if (at < start) at else at - len
        movedTable = dest until dest + len
        val moved = Edit.Move(start, len, dest)
        edits.add(moved)
        val first = minOf(start, dest)
        word.relayoutContent(first)
        // back: the block now at [dest] goes before what now starts where it was
        val home = if (dest < start) start + len else start
        undoStack.add(Step(
            undo = { editor.undoLast() && doc.moveMainTable(dest, dest + len, home).also { edits.remove(moved); word.relayoutContent(first) } },
            redo = { editor.moveTable(os, ot, after, ref) && doc.moveMainTable(start, end, at).also { edits.add(moved); word.relayoutContent(first) } },
        ))
        redoStack.clear()
        true
    }

    /** Offsets of the table holding [offset], or null when it is not in a table. */
    fun tableAt(offset: Long): LongRange? {
        val doc = word.getDocument() as? com.wxiwei.office.wp.model.WPDocument ?: return null
        val t = doc.getParagraph0(offset) as? com.wxiwei.office.wp.model.TableElement ?: return null
        return t.getStartOffset() until t.getEndOffset()
    }

    /**
     * A [rows] x [cols] table with thin borders after the body paragraph at [offset], shown at once
     * (with an empty paragraph after it when a table or the end follows, as in the file); otherwise
     * after the file is read again ([needsReopen]).
     */
    fun insertTable(offset: Long, rows: Int, cols: Int): Boolean = synchronized(layoutLock) {
        ownError = null
        val doc = word.getDocument() as? WPDocument
        val para = doc?.getParagraph(offset) as? ParagraphElement
        // live: a body paragraph whose mark is in the file (the table goes after that paragraph there)
        val live = doc != null && para != null && (offset and WPModelConstant.AREA_MASK) == WPModelConstant.MAIN &&
            am.getParaLevel(para.getAttribute()) < 0 && !touchesTyped(para.getEndOffset() - 1, para.getEndOffset())
        if (!live) {
            if (!checked(editor.insertTable(toOriginal(offset), rows, cols))) return false
            needsReopen = true
            undoStack.add(Step({ editor.undoLast() }, { editor.insertTable(toOriginal(offset), rows, cols) })); redoStack.clear()
            return true
        }
        doc!!; para!!
        val o = toOriginal(para.getEndOffset() - 1)
        if (!checked(editor.insertTable(o, rows, cols))) return false
        // like the file: the text width split evenly, single borders, Word's cell margins
        val sect = doc.getSection(offset)?.getAttribute()
        val textWidth = sect?.let { am.getPageWidth(it) - am.getPageMarginLeft(it) - am.getPageMarginRight(it) }?.takeIf { it > 0 } ?: 9360
        val colWidth = maxOf(1440, textWidth) / cols
        fun emptyParagraph(level: Int): ParagraphElement = ParagraphElement().also { p ->
            if (level > 0) am.setParaLevel(p.getAttribute()!!, level)
            if (doc.defaultParaStyleID >= 0) am.setParaStyleID(p.getAttribute()!!, doc.defaultParaStyleID)
            val mark = LeafElement("\n")
            p.setStartOffset(0); p.setEndOffset(1); mark.setStartOffset(0); mark.setEndOffset(1)
            p.appendLeaf(mark)
        }
        val table = com.wxiwei.office.wp.model.TableElement()
        val rowList = ArrayList<com.wxiwei.office.wp.model.RowElement>()
        val paras = ArrayList<List<ParagraphElement>>()
        val cells = ArrayList<IElement>()
        repeat(rows) {
            val row = com.wxiwei.office.wp.model.RowElement()
            val mine = ArrayList<ParagraphElement>()
            repeat(cols) {
                val cell = com.wxiwei.office.wp.model.CellElement()
                val a = cell.getAttribute()!!
                am.setTableCellWidth(a, colWidth)
                am.setTableTopBorder(a, 4); am.setTableTopBorderColor(a, Color.BLACK)
                am.setTableBottomBorder(a, 4); am.setTableBottomBorderColor(a, Color.BLACK)
                am.setTableLeftBorder(a, 4); am.setTableLeftBorderColor(a, Color.BLACK)
                am.setTableRightBorder(a, 4); am.setTableRightBorderColor(a, Color.BLACK)
                am.setTableTopMargin(a, 0); am.setTableBottomMargin(a, 0)
                am.setTableLeftMargin(a, 108); am.setTableRightMargin(a, 108)
                row.appendCell(cell); cells.add(cell)
                mine.add(emptyParagraph(1))
            }
            rowList.add(row); paras.add(mine)
        }
        val at = para.getEndOffset()
        // Word wants a paragraph after a table: the file adds one when no paragraph follows
        val next = if (at < doc.storyEnd(at)) doc.getParagraph0(at) else null
        val after = if (next == null || next is com.wxiwei.office.wp.model.TableElement) emptyParagraph(0) else null
        val made = NewTable(editor.lastOp(), o)
        val group = NewCells(made.op, cells)
        var inserted: Edit.Insert? = null
        fun put() {
            doc.insertMainTable(at, table, rowList, paras, after)
            edits.add(Edit.Insert(at, (after?.getEndOffset() ?: table.getEndOffset()) - at).also { inserted = it })
            newTables[table] = made; newCells.add(group)
        }
        put()
        word.relayoutContent(at)
        undoStack.add(Step(
            undo = { editor.undoLast().also { if (it) {
                doc.removeMainTable(table, after); inserted?.let { e -> edits.remove(e) }; newTables.remove(table); newCells.remove(group); word.relayoutContent(at)
            } } },
            redo = { editor.insertTable(o, rows, cols).also { if (it) { made.op = editor.lastOp(); group.op = made.op; put(); word.relayoutContent(at) } } },
        ))
        redoStack.clear()
        true
    }

    /** Where [query] is in the body (tables included), in order; none across a paragraph mark. */
    fun find(query: String, matchCase: Boolean = false): List<LongRange> {
        if (query.isEmpty() || query.contains('\n')) return emptyList()
        val doc = word.getDocument() as? WPDocument ?: return emptyList()
        val text = doc.getText(0, doc.getAreaEnd(0)) ?: return emptyList()
        val out = ArrayList<LongRange>()
        var i = text.indexOf(query, 0, ignoreCase = !matchCase)
        while (i >= 0) {
            out.add(i.toLong() until (i + query.length).toLong())
            i = text.indexOf(query, i + query.length, ignoreCase = !matchCase)
        }
        return out
    }

    /**
     * Every [query] in the body becomes [text], one undo step. Returns how many were replaced and how
     * many could not be (over a picture, a field...): those are left as they are.
     */
    fun replaceAll(query: String, text: String, matchCase: Boolean = false): Pair<Int, Int> {
        val all = find(query, matchCase)
        var done = 0
        var skipped = 0
        grouped {
            // from the end: the offsets of the ones before do not move
            for (r in all.asReversed()) if (replaceText(r.first, r.last + 1, text)) done++ else skipped++
            done > 0
        }
        ownError = null
        return done to skipped
    }

    /** Text with its character formatting, copied with [copyFormatted] to paste with [pasteFormatted]. */
    class FormattedText(val text: String, val spans: List<Span>) {
        /** Formatting of chars [from, to); [highlight] is an ARGB fill or null. */
        data class Span(val from: Int, val to: Int, val bold: Boolean, val italic: Boolean, val underline: Boolean,
                        val rgb: Int, val sizePt: Float, val highlight: Int?)
    }

    /** The text of [start, end) with the formatting shown on it. */
    fun copyFormatted(start: Long, end: Long): FormattedText = synchronized(layoutLock) {
        val doc = word.getDocument()
        val spans = ArrayList<FormattedText.Span>()
        var pos = start
        while (pos < end) {
            val para = doc.getParagraph(pos) ?: break
            val leaf = doc.getLeaf(pos) ?: break
            val stop = minOf(end, leaf.getEndOffset()).let { if (it <= pos) pos + 1 else it }
            val p = para.getAttribute(); val l = leaf.getAttribute()
            val fill = am.getFontHighLight(p, l).takeIf { it != -1 && it != Int.MIN_VALUE && (it ushr 24) != 0 }
            spans.add(FormattedText.Span((pos - start).toInt(), (stop - start).toInt(), am.getFontBold(p, l), am.getFontItalic(p, l),
                am.getFontUnderline(p, l) > 0, am.getFontColor(p, l) and 0xFFFFFF, am.getFontSizeF(p, l), fill))
            pos = stop
        }
        FormattedText(doc.getText(start, end), spans)
    }

    /**
     * Puts [clip] over [start, end) (an insert when empty) with its formatting, as one undo step.
     * Only what differs from how the pasted text shows is set.
     */
    fun pasteFormatted(start: Long, end: Long, clip: FormattedText): Boolean = grouped {
        if (clip.text.isEmpty()) return@grouped refuse("Nothing to paste")
        val placed = if (end > start) replaceText(start, end, clip.text) else insertText(start, clip.text)
        placed && clip.spans.all { s ->
            val a = start + s.from
            val b = start + s.to
            // the paragraph marks of a multi-paragraph paste carry no text formatting
            if (clip.text.substring(s.from, s.to).all { it == '\n' }) return@all true
            (isBold(a) == s.bold || setBold(a, b, s.bold)) &&
                (isItalic(a) == s.italic || setItalic(a, b, s.italic)) &&
                (isUnderlined(a) == s.underline || setUnderline(a, b, s.underline)) &&
                (colorAt(a) == s.rgb || setTextColor(a, b, "%06X".format(s.rgb))) &&
                (Math.abs(sizeAt(a) - s.sizePt) < 0.01f || setFontSize(a, b, s.sizePt)) &&
                (s.highlight == null || highlight(a, b, "%06X".format(s.highlight and 0xFFFFFF)))
        }
    }

    private fun colorAt(offset: Long): Int {
        val doc = word.getDocument()
        val para = doc.getParagraph(offset) ?: return -1
        val leaf = doc.getLeaf(offset) ?: return -1
        return am.getFontColor(para.getAttribute(), leaf.getAttribute()) and 0xFFFFFF
    }

    private fun sizeAt(offset: Long): Float {
        val doc = word.getDocument()
        val para = doc.getParagraph(offset) ?: return 0f
        val leaf = doc.getLeaf(offset) ?: return 0f
        return am.getFontSizeF(para.getAttribute(), leaf.getAttribute())
    }

    /** Runs [block] as one undo step, whatever steps it records. */
    private fun grouped(block: () -> Boolean): Boolean {
        val mark = undoStack.size
        val ok = block()
        if (undoStack.size - mark > 1) {
            val steps = ArrayList(undoStack.subList(mark, undoStack.size))
            repeat(steps.size) { undoStack.removeAt(undoStack.lastIndex) }
            undoStack.add(Step(
                undo = { steps.asReversed().all { it.runUndo() } },
                redo = { steps.all { it.runRedo() } },
            ))
        }
        return ok
    }

    /**
     * Deletes [start, end) from the end backwards: paragraph marks join paragraphs, text inserted
     * in this session only leaves the view (its file text is taken from the view on save),
     * original text is also deleted in the file. One undo step.
     */
    private fun deletePieces(doc: WPDocument, start: Long, end: Long): Boolean = grouped {
        var e = end
        var ok = true
        while (e > start && ok) {
            val c = doc.getText(e - 1, e)
            val inserted = insertedAt(e - 1)
            var s = e - 1
            if (c != "\n") while (s > start && doc.getText(s - 1, s) != "\n" && insertedAt(s - 1) == inserted) s--
            ok = deletePiece(doc, s, e, c == "\n", inserted)
            e = s
        }
        ok
    }

    private fun deletePiece(doc: WPDocument, s: Long, e: Long, mark: Boolean, inserted: Boolean): Boolean {
        val os = toOriginal(s)
        val oe = toOriginal(e)
        val removed = doc.getText(s, e)
        if (!inserted && !editor.deleteText(os, oe)) return false
        val fileUndo = { if (inserted) true else editor.undoLast() }
        val fileRedo = { if (inserted) true else editor.deleteText(os, oe) }
        val live = { if (mark) doc.joinMainParagraph(s) else doc.deleteMainText(s, e) }
        val back = { if (mark) doc.splitMainParagraph(s) else doc.insertMainText(s, removed) }
        if (!live()) {
            if (!inserted) editor.undoLast()
            return refuse("Cannot delete here")
        }
        val edit = Edit.Delete(s, e - s)
        edits.add(edit)
        undoStack.add(Step(
            undo = { fileUndo() && back().also { edits.remove(edit); word.relayoutContent(s) } },
            redo = { fileRedo() && live().also { edits.add(edit); word.relayoutContent(s) } },
        ))
        redoStack.clear()
        word.relayoutContent(s)
        return true
    }

    /** Text and run formatting of the live inserts, as shown now, for [DocxEditor.save]. */
    private fun overrides(): Map<Any, DocxEditor.InsertOverride> {
        class Group(var s: Long, var e: Long, val leader: Any)
        val groups = ArrayList<Group>()
        val member = java.util.IdentityHashMap<Any, Group>()
        for ((k, edit) in edits.withIndex()) {
            if (edit !is Edit.Insert) continue
            val op = handles[edit] ?: continue
            // where that text is now
            var s = edit.at
            var e = edit.at + edit.length
            for (later in edits.subList(k + 1, edits.size)) if (area(later.at) == area(edit.at)) when (later) {
                is Edit.Insert -> if (later.at < s) { s += later.length; e += later.length } else if (later.at <= e) e += later.length
                is Edit.Delete -> {
                    val a = later.at
                    val b = a + later.length
                    fun cut(v: Long) = when { v >= b -> v - later.length; v > a -> a; else -> v }
                    s = cut(s); e = cut(e)
                }
                // typed text never spans a moved block's edge: it moves whole
                is Edit.Move -> { val n = later.forward(s); e = n + (e - s); s = n }
            }
            // inserts touching each other are written together, by the first of them
            val touching = groups.filter { s <= it.e && e >= it.s }
            val group = touching.firstOrNull() ?: Group(s, e, op).also { groups.add(it) }
            for (other in touching.drop(1)) {
                group.s = minOf(group.s, other.s); group.e = maxOf(group.e, other.e)
                member.entries.filter { it.value === other }.forEach { it.setValue(group) }
                groups.remove(other)
            }
            group.s = minOf(group.s, s); group.e = maxOf(group.e, e)
            member[op] = group
        }
        val doc = word.getDocument()
        val result = java.util.IdentityHashMap<Any, DocxEditor.InsertOverride>()
        for ((op, g) in member) {
            result[op] = if (g.leader === op) DocxEditor.InsertOverride(doc.getText(g.s, g.e), runFormats(g.s, g.e))
            else DocxEditor.InsertOverride("", emptyList())
        }
        return result
    }

    /**
     * The paragraph properties set on [attr] itself (not from its style), as the file writes them:
     * what a paragraph of a new cell got from the cell it was made like, and what was set on it since.
     */
    private fun paraFormat(attr: IAttributeSet): DocxEditor.ParaFormat {
        val set = attr as? com.wxiwei.office.simpletext.model.AttributeSetImpl ?: return DocxEditor.ParaFormat()
        fun own(id: Short) = set.getOwnAttribute(id).takeIf { it != Int.MIN_VALUE }
        val special = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_SPECIALINDENT_ID)
        val lineType = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_LINESPACE_TYPE_ID)
        val line = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_LINESPACE_ID)?.let { it / 100f }
        val (lineTwips, rule) = when {
            line == null -> null to null
            lineType == WPAttrConstant.LINE_SAPCE_MULTIPLE.toInt() -> Math.round(line * 240) to "auto"
            lineType == WPAttrConstant.LINE_SAPCE_LEAST.toInt() -> Math.round(line) to "atLeast"
            lineType == WPAttrConstant.LINE_SPACE_EXACTLY.toInt() -> Math.round(-line) to "exact"
            else -> null to null
        }
        val listId = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_LIST_ID)
        return DocxEditor.ParaFormat(
            align = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_HORIZONTAL_ID)?.let {
                when (it) { WPAttrConstant.PARA_HOR_ALIGN_CENTER.toInt() -> "center"; WPAttrConstant.PARA_HOR_ALIGN_RIGHT.toInt() -> "right"
                    WPAttrConstant.PARA_HOR_ALIGN_JUSTIFIED.toInt() -> "both"; else -> "left" }
            },
            // the view keeps a hanging indent inside the left one (as paragraphLayoutAt reads it back)
            leftTwips = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_INDENT_LEFT_ID)?.let { it - minOf(special ?: am.getParaSpecialIndent(attr), 0) },
            rightTwips = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_INDENT_RIGHT_ID),
            specialTwips = special,
            beforeTwips = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_BEFORE_ID),
            afterTwips = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_AFTER_ID),
            line = lineTwips, lineRule = rule,
            // only the lists this session sets; one copied from the cell it was made like is in its pPr already
            list = when {
                listId == null -> null
                listId < 0 -> "0"
                listId == editor.bulletListId -> "bullet"
                listId == editor.numberingListId -> "decimal"
                else -> null
            },
            listLevel = own(com.wxiwei.office.constant.wp.AttrIDConstant.PARA_LIST_LEVEL_ID),
        )
    }

    /** The formatting shown on [s, e), as run properties relative to [s]. */
    private fun runFormats(s: Long, e: Long): List<DocxEditor.RunFormat> {
        val doc = word.getDocument()
        val out = ArrayList<DocxEditor.RunFormat>()
        var pos = s
        while (pos < e) {
            val para = doc.getParagraph(pos) ?: break
            val leaf = doc.getLeaf(pos) ?: break
            val stop = minOf(e, leaf.getEndOffset()).let { if (it <= pos) pos + 1 else it }
            val p = para.getAttribute()
            val l = leaf.getAttribute()
            val props = arrayListOf(
                "b" to (if (am.getFontBold(p, l)) "1" else "0"),
                "i" to (if (am.getFontItalic(p, l)) "1" else "0"),
                "u" to (if (am.getFontUnderline(p, l) > 0) "single" else "none"),
                "strike" to (if (am.getFontStrike(p, l)) "1" else "0"),
                "vertAlign" to when (am.getFontScript(p, l)) { 1 -> "superscript"; 2 -> "subscript"; else -> "baseline" },
                "color" to "%06X".format(am.getFontColor(p, l) and 0xFFFFFF),
                "sz" to Math.round(am.getFontSizeF(p, l) * 2).toString(),
            )
            val highlight = am.getFontHighLight(p, l)
            if (highlight != -1 && highlight != Int.MIN_VALUE && (highlight ushr 24) != 0) props.add("shd" to "%06X".format(highlight and 0xFFFFFF))
            com.wxiwei.office.simpletext.font.FontTypefaceManage.instance().fontName(am.getFontName(p, l))?.let { props.add("rFonts" to it) }
            out.add(DocxEditor.RunFormat((pos - s).toInt(), (stop - s).toInt(), props))
            pos = stop
        }
        return out
    }
}
