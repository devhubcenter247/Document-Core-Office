/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.editor.pptx

import com.wxiwei.office.editor.EditResult
import com.wxiwei.office.editor.UndoStack
import com.wxiwei.office.system.IControl
import java.io.File

/**
 * Realtime PPTX editing: every call changes the open slide immediately ([LiveSlideDisplay]) and
 * queues the same change in [PptxEditor], which writes the file on [save]. No reopen is needed.
 *
 * Main thread only, like the viewer. When the view cannot show an edit live (for example a shape
 * inside a group that has to be deleted), the edit is still saved and [needsReopen] becomes true.
 */
class LivePptxSession internal constructor(private val editor: PptxEditor, private val display: LiveSlideDisplay) {
    constructor(control: IControl, source: File) : this(PptxEditor(source), LiveSlideModel(control))

    fun interface OnChangeListener { fun onChanged(canUndo: Boolean, canRedo: Boolean) }

    /** A reversible change, applied to both layers. */
    private class Step(val redo: () -> Boolean, val undo: () -> Boolean)

    private val undoStack = UndoStack<Step>()
    private val redoStack = ArrayList<Step>()
    private var changes = 0

    var listener: OnChangeListener? = null
    /** Last failure of the file layer (invalid id, missing slide...). */
    val lastError: EditResult.Error? get() = editor.lastError
    /** True when at least one saved edit could not be shown live; reopen the saved file to see it. */
    var needsReopen = false
        private set

    fun slideSizeEmu(): Size = editor.slideSizeEmu()
    fun listShapes(slideIndex: Int): List<PptxShapeInfo> = editor.listShapes(slideIndex)
    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
    fun hasChanges() = changes != 0

    private fun live(ok: Boolean) { if (!ok) needsReopen = true }

    private fun push(step: Step) {
        undoStack.add(step); redoStack.clear(); changes++
        listener?.onChanged(canUndo(), canRedo())
    }

    /**
     * Every session call queues exactly one [PptxEditor] op, so undo drops the last queued op
     * ([PptxEditor.undoLast]) and redo queues it again; the display layer reverts on its own.
     */
    private fun record(redoFile: () -> Boolean, redoLive: () -> Boolean, undoLive: () -> Boolean) = record(1, redoFile, redoLive, undoLive)

    /** Same for a step that queues [fileOps] file operations. */
    private fun record(fileOps: Int, redoFile: () -> Boolean, redoLive: () -> Boolean, undoLive: () -> Boolean) = push(Step(
        redo = { redoFile().also { if (it) live(redoLive()) } },
        undo = { (0 until fileOps).all { editor.undoLast() }.also { if (it) live(undoLive()) } }))

    /** Returns the new shape id, or -1 ([lastError] says why). */
    fun addTextBox(slideIndex: Int, rectEmu: Rect, text: String, sizePt: Float = 18f, rgbHex: String = "000000", bold: Boolean = false): Int {
        val id = editor.addTextBox(slideIndex, rectEmu, text, sizePt, rgbHex, bold)
        if (id < 0) return -1
        val show = { display.addTextBox(slideIndex, id, rectEmu, text, sizePt, rgbHex, bold) }
        live(show())
        // Re-adding gets the same id: ids are max + 1 and the undone shape had the max id
        record({ editor.addTextBox(slideIndex, rectEmu, text, sizePt, rgbHex, bold) == id }, show) { display.removeShape(slideIndex, id) != null }
        return id
    }

    /** A preset shape (see [PptxEditor.addShape]); returns its id, or -1. */
    fun addShape(slideIndex: Int, rect: Rect, prst: String, fillHex: String?, lineHex: String, lineWidthPt: Float = 1.5f): Int {
        // a flat line has no height (or width): 1 EMU keeps its box valid for the moves after
        val rectEmu = Rect(rect.x, rect.y, maxOf(1L, rect.width), maxOf(1L, rect.height))
        val id = editor.addShape(slideIndex, rectEmu, prst, fillHex, lineHex, lineWidthPt)
        if (id < 0) return -1
        val show = { display.addShape(slideIndex, id, rectEmu, prst, fillHex, lineHex, lineWidthPt) }
        live(show())
        record({ editor.addShape(slideIndex, rectEmu, prst, fillHex, lineHex, lineWidthPt) == id }, show) { display.removeShape(slideIndex, id) != null }
        return id
    }

    fun addImage(slideIndex: Int, rectEmu: Rect, imageFile: File): Int {
        val id = editor.addImage(slideIndex, rectEmu, imageFile)
        if (id < 0) return -1
        val show = { display.addImage(slideIndex, id, rectEmu, imageFile) }
        live(show())
        record({ editor.addImage(slideIndex, rectEmu, imageFile) == id }, show) { display.removeShape(slideIndex, id) != null }
        return id
    }

    fun setShapeText(slideIndex: Int, shapeId: Int, text: String): Boolean {
        val old = display.shapeText(slideIndex, shapeId) ?: listShapes(slideIndex).firstOrNull { it.id == shapeId }?.text
        val where = listShapes(slideIndex).firstOrNull { it.id == shapeId }?.rectEmu
        if (!editor.setShapeText(slideIndex, shapeId, text)) return false
        // undo puts back the very runs shown before, not the old text in new runs
        val before = display.saveText(slideIndex, shapeId)
        val show = { display.setShapeText(slideIndex, shapeId, text, where) }
        live(show())
        // a box that fits its text (spAutoFit) takes the height of the new text, top kept, like PowerPoint
        var fitted: Rect? = null
        // the file's box: the view's is rounded to pixels
        val oldRect = where ?: display.shapeRect(slideIndex, shapeId)
        if (oldRect != null && editor.autoFits(slideIndex, shapeId)) {
            val height = display.textHeight(slideIndex, shapeId)
            val box = oldRect
            if (height != null && height > 0 && Math.abs(height - box.height) > LiveSlideModel.EMU_PER_PX) {
                fitted = Rect(box.x, box.y, box.width, height)
                if (editor.moveShape(slideIndex, shapeId, fitted)) live(display.moveShape(slideIndex, shapeId, fitted)) else fitted = null
            }
        }
        val resize = fitted
        record(if (resize != null) 2 else 1,
            { editor.setShapeText(slideIndex, shapeId, text) && (resize == null || editor.moveShape(slideIndex, shapeId, resize)) },
            { show() && (resize == null || display.moveShape(slideIndex, shapeId, resize)) }) {
                (resize == null || oldRect == null || display.moveShape(slideIndex, shapeId, oldRect)) &&
                    if (before != null) display.restoreText(slideIndex, shapeId, before)
                    else old != null && display.setShapeText(slideIndex, shapeId, old, where)
        }
        return true
    }

    /** Bold, italic, underline, size, color, font or alignment for all the text of a shape. */
    fun setTextFormat(slideIndex: Int, shapeId: Int, format: TextFormat): Boolean =
        formatText(slideIndex, shapeId, format, { editor.setTextFormat(slideIndex, shapeId, format) }) { display.setTextFormat(slideIndex, shapeId, format) }

    /** Formats chars [start, end) of the shape's text (positions as in [PptxShapeInfo.text]). */
    fun setTextFormat(slideIndex: Int, shapeId: Int, start: Int, end: Int, format: TextFormat): Boolean =
        formatText(slideIndex, shapeId, format, { editor.setTextFormat(slideIndex, shapeId, start, end, format) }) { display.setTextFormat(slideIndex, shapeId, start, end, format) }

    /**
     * A format change: [fileOp] for the file, [show] for the view. A new size changes the text's
     * height: a box that fits its text (spAutoFit) takes it, top kept, like PowerPoint does
     * (otherwise the text runs out of its box and no longer sits in it).
     */
    private fun formatText(slideIndex: Int, shapeId: Int, format: TextFormat, fileOp: () -> Boolean, show: () -> Any?): Boolean {
        val where = listShapes(slideIndex).firstOrNull { it.id == shapeId }?.rectEmu ?: display.shapeRect(slideIndex, shapeId)
        if (!fileOp()) return false
        var token = show()
        live(token != null)
        val fitted = if (format.sizePt != null && where != null) fitToText(slideIndex, shapeId, where) else null
        record(if (fitted != null) 2 else 1,
            { fileOp() && (fitted == null || editor.moveShape(slideIndex, shapeId, fitted)) },
            { (show().also { token = it } != null) && (fitted == null || display.moveShape(slideIndex, shapeId, fitted)) },
            { (fitted == null || display.moveShape(slideIndex, shapeId, where!!)) && (token?.let { display.restoreFormat(slideIndex, it) } ?: false) })
        return true
    }

    /** For a box that fits its text (spAutoFit): the height of its text now, top kept; queued and shown. Null when unchanged. */
    private fun fitToText(slideIndex: Int, shapeId: Int, box: Rect): Rect? {
        if (!editor.autoFits(slideIndex, shapeId)) return null
        val height = display.textHeight(slideIndex, shapeId) ?: return null
        if (height <= 0 || Math.abs(height - box.height) <= LiveSlideModel.EMU_PER_PX) return null
        val fitted = Rect(box.x, box.y, box.width, height)
        if (!editor.moveShape(slideIndex, shapeId, fitted)) return null
        live(display.moveShape(slideIndex, shapeId, fitted))
        return fitted
    }

    // Slide changes are saved; the open view shows them after a reopen ([needsReopen]).
    fun slideCount(): Int = editor.slideCount()
    /** Size, color and typeface of the shape's text as shown, for an editor over it. */
    fun textStyle(slideIndex: Int, shapeId: Int): TextStyle? = display.textStyle(slideIndex, shapeId)
    /** How each run of the shape's text is drawn, by char range, for an editor over it. */
    fun textRuns(slideIndex: Int, shapeId: Int): List<Pair<IntRange, TextStyle>> = display.textRuns(slideIndex, shapeId)
    /** Current bold/italic/underline of the shape's text (from its first run). */
    fun textFormatOf(slideIndex: Int, shapeId: Int): TextFormat? = editor.textFormatOf(slideIndex, shapeId)
    /** Bold/italic/underline and font of the text at char [offset] of the shape's text. */
    fun textFormatAt(slideIndex: Int, shapeId: Int, offset: Int): TextFormat? = editor.textFormatAt(slideIndex, shapeId, offset)

    fun deleteSlide(slideIndex: Int): Boolean = slideChange { editor.deleteSlide(slideIndex) }
    fun duplicateSlide(slideIndex: Int): Boolean = slideChange { editor.duplicateSlide(slideIndex) >= 0 }
    fun moveSlide(from: Int, to: Int): Boolean = slideChange { editor.moveSlide(from, to) }
    /** A copy of the shape for [pasteShape]; null (see [lastError]) when it cannot be copied. */
    fun copyShape(slideIndex: Int, shapeId: Int): PptxEditor.ShapeClip? = editor.copyShape(slideIndex, shapeId)
    /** The id of the shape the last [pasteShape] made. */
    var pastedShape = -1
        private set
    /** Puts a copy of [clip] on the slide, moved by [dx], [dy] EMU; shown after a reopen like slide changes. */
    fun pasteShape(slideIndex: Int, clip: PptxEditor.ShapeClip, dx: Long, dy: Long): Boolean =
        slideChange { editor.pasteShape(slideIndex, clip, dx, dy).also { pastedShape = it } >= 0 }
    /** An empty slide after [afterIndex]; shown after saving and reopening like other slide changes. */
    fun addBlankSlide(afterIndex: Int): Boolean = slideChange { editor.addBlankSlide(afterIndex) >= 0 }

    private fun slideChange(op: () -> Boolean): Boolean {
        if (!op()) return false
        needsReopen = true
        record(op, { false }, { false })
        return true
    }

    fun moveShape(slideIndex: Int, shapeId: Int, rectEmu: Rect): Boolean {
        val old = display.shapeRect(slideIndex, shapeId) ?: listShapes(slideIndex).firstOrNull { it.id == shapeId }?.rectEmu
        if (!editor.moveShape(slideIndex, shapeId, rectEmu)) return false
        val show = { display.moveShape(slideIndex, shapeId, rectEmu) }
        live(show())
        record({ editor.moveShape(slideIndex, shapeId, rectEmu) }, show) { old != null && display.moveShape(slideIndex, shapeId, old) }
        return true
    }

    /** Sets the clockwise rotation of a shape in degrees. */
    fun rotateShape(slideIndex: Int, shapeId: Int, degrees: Float): Boolean {
        val old = display.shapeRotation(slideIndex, shapeId) ?: listShapes(slideIndex).firstOrNull { it.id == shapeId }?.rotationDeg ?: 0f
        if (!editor.rotateShape(slideIndex, shapeId, degrees)) return false
        val show = { display.rotateShape(slideIndex, shapeId, degrees) }
        live(show())
        record({ editor.rotateShape(slideIndex, shapeId, degrees) }, show) { display.rotateShape(slideIndex, shapeId, old) }
        return true
    }

    fun slideEffects(slideIndex: Int): List<SlideEffect> = editor.slideEffects(slideIndex)
    fun slideTransition(slideIndex: Int): SlideTransition? = editor.slideTransition(slideIndex)
    /** The animations of the slide (they play in the slideshow; the edit view shows the end state). One undoable step. */
    fun setSlideEffects(slideIndex: Int, effects: List<SlideEffect>): Boolean {
        if (!editor.setSlideEffects(slideIndex, effects)) return false
        record({ editor.setSlideEffects(slideIndex, effects) }, { true }, { true })
        return true
    }
    /** The transition of [slideIndexes]; one undoable step. */
    fun setSlideTransition(slideIndexes: List<Int>, t: SlideTransition?): Boolean {
        if (!editor.setSlideTransition(slideIndexes, t)) return false
        record({ editor.setSlideTransition(slideIndexes, t) }, { true }, { true })
        return true
    }
    /** See [PptxEditor.showScript]: what the slideshow plays, edits included. */
    fun showScript(): List<SlideScript> = editor.showScript()
    /** See [PptxEditor.readPackage]. */
    fun <T> readPackage(fallback: T, block: (com.wxiwei.office.editor.ooxml.OoxmlPackage) -> T): T = editor.readPackage(fallback, block)

    /** Z-order: "front", "back", "forward" or "backward" among the shape's siblings; one undoable step. */
    fun reorderShape(slideIndex: Int, shapeId: Int, where: String): Boolean {
        if (!editor.reorderShape(slideIndex, shapeId, where)) return false
        // the view follows the file's order (after the move, and after undoing it)
        val sync = { display.reorder(slideIndex, editor.shapeOrder(slideIndex)) }
        live(sync())
        record({ editor.reorderShape(slideIndex, shapeId, where) }, sync, sync)
        return true
    }

    fun deleteShape(slideIndex: Int, shapeId: Int): Boolean {
        if (!editor.deleteShape(slideIndex, shapeId)) return false
        var token = display.removeShape(slideIndex, shapeId)
        live(token != null)
        record({ editor.deleteShape(slideIndex, shapeId) },
            { display.removeShape(slideIndex, shapeId).also { token = it } != null },
            { token?.let { display.restoreShape(slideIndex, it) } ?: false })
        return true
    }

    fun undo(): Boolean {
        val step = undoStack.lastOrNull() ?: return false
        if (!step.undo()) return false
        undoStack.removeAt(undoStack.lastIndex); redoStack.add(step); changes--
        listener?.onChanged(canUndo(), canRedo())
        return true
    }

    fun redo(): Boolean {
        val step = redoStack.lastOrNull() ?: return false
        if (!step.redo()) return false
        redoStack.removeAt(redoStack.lastIndex); undoStack.add(step); changes++
        listener?.onChanged(canUndo(), canRedo())
        return true
    }

    /** Write the file. The live view already matches it, so no reopen is needed (see [needsReopen]). */
    fun save(target: File): EditResult = editor.save(target).also { if (it is EditResult.Ok) changes = 0 }
    /** Writes the file like [save], but the edits still count as not saved (an undo point, not a save). */
    fun copyTo(target: File): EditResult = editor.save(target)
}
