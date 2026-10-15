/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.editor.xlsx

import com.wxiwei.office.editor.EditResult
import com.wxiwei.office.editor.UndoStack
import com.wxiwei.office.editor.Reason
import com.wxiwei.office.ss.control.ExcelView
import com.wxiwei.office.ss.control.Spreadsheet
import com.wxiwei.office.ss.model.baseModel.Cell
import com.wxiwei.office.ss.model.baseModel.Row
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.constant.SSConstant
import com.wxiwei.office.ss.model.baseModel.Sheet
import com.wxiwei.office.ss.model.baseModel.Workbook
import com.wxiwei.office.common.bg.BackgroundAndFill
import com.wxiwei.office.simpletext.font.Font
import com.wxiwei.office.ss.model.style.BuiltinFormats
import com.wxiwei.office.ss.model.style.CellStyle
import com.wxiwei.office.ss.model.style.NumberFormat
import com.wxiwei.office.ss.model.CellRangeAddress
import com.wxiwei.office.system.IControl
import com.wxiwei.office.common.shape.GroupShape
import com.wxiwei.office.common.shape.IShape
import com.wxiwei.office.java.awt.Rectangle
import com.wxiwei.office.ss.util.ModelUtil
import java.io.File
import java.util.Locale

/**
 * Realtime cell editing for an open .xlsx: the new value shows immediately, formulas that depend
 * on it are recalculated in memory, and [save] patches the original file (see [XlsxWriter]).
 *
 * Input rules for [setCellInput] (like typing into Excel): "=..." is a formula, a US-format number
 * ("12", "-1.5", "3e4") is a number, TRUE/FALSE is a boolean, "" clears the cell, anything else is
 * text. UI thread only.
 */
class SheetEditSession internal constructor(
    private val book: Workbook,
    private val source: File,
    private val repaint: () -> Unit,
    /** Pictures of the open document (pictures added in edit mode go there to be drawn). */
    private val pictureManage: com.wxiwei.office.common.picture.PictureManage? = null,
) {
    constructor(control: IControl, source: File) : this(spreadsheetOf(control).getWorkbook()!!, source, {
        val ss = spreadsheetOf(control)
        ss.getSheetView()?.invalidateTiles()
        ss.postInvalidate()
    }, control.getSysKit().getPictureManage())

    /** Formula engine of the workbook; rebuilt after rows or columns are inserted or deleted. */
    var engine = XlsxFormulaEngine(book)
        private set
    /** Warnings of the last recalculation (unsupported function, sheet still loading...). */
    var warnings: List<String> = emptyList(); private set
    var lastError: EditResult.Error? = null; private set

    private data class Key(val sheet: Int, val row: Int, val col: Int)
    /** One undoable edit: a typed value, a format change, or several applied together (a range). */
    private sealed class Step {
        data class Input(val key: Key, val before: String, val after: String) : Step()
        data class Format(val key: Key, val beforeStyle: Int, val afterStyle: Int, val format: CellFormat) : Step()
        data class Group(val steps: List<Step>) : Step()
        class Structure(val apply: () -> Unit, val revert: () -> Unit) : Step()
    }
    /** Rows/columns inserted or deleted, in order; save replays them on the file first. */
    private val structure = ArrayList<StructureWrite>()
    private val undoStack = UndoStack<Step>()
    private val redoStack = ArrayList<Step>()
    /** Format changes applied to each cell, in order; merged relative to the file's style on save. */
    private val formats = HashMap<Key, MutableList<CellFormat>>()
    /** Cells typed by the user plus formula cells whose result changed: everything [save] writes. */
    private val dirty = LinkedHashSet<Key>()

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
    fun hasChanges() = undoStack.isNotEmpty() || sizes.isNotEmpty() || dirty.isNotEmpty() || formats.values.any { it.isNotEmpty() } || structure.isNotEmpty() || addedSheets.isNotEmpty() || pictures.isNotEmpty() ||
        movedPictures.isNotEmpty() || deletedPictures.isNotEmpty()

    /** Pictures of the file deleted, or moved (to where they are now; a move undone writes the same place back). */
    private fun pictureEdits(): List<PictureEdit> {
        val edits = deletedPictures.map { (sheet, id) -> PictureEdit(sheet, id, delete = true) }.toMutableList()
        for ((sheetIndex, shape) in movedPictures) {
            val sheet = book.getSheet(sheetIndex) ?: continue
            val id = sheet.pictureId(shape) ?: continue
            if (sheetIndex to id in deletedPictures) continue
            val b = shape.bounds ?: continue
            val (r1, dy1, c1, dx1) = cellOffset(sheet, b.x, b.y)
            val (r2, dy2, c2, dx2) = cellOffset(sheet, b.x + b.width, b.y + b.height)
            edits += PictureEdit(sheetIndex, id,
                from = CellPoint(r1, c1, dx1 * EMU_PER_PX, dy1 * EMU_PER_PX), to = CellPoint(r2, c2, dx2 * EMU_PER_PX, dy2 * EMU_PER_PX),
                xEmu = b.x * EMU_PER_PX, yEmu = b.y * EMU_PER_PX, cxEmu = b.width * EMU_PER_PX, cyEmu = b.height * EMU_PER_PX)
        }
        return edits
    }

    /** Sheets added in this session (after the file's last one), in order: save writes them first. */
    private val addedSheets = ArrayList<Sheet>()

    /** Why [name] cannot name a new sheet (Excel's rules), or null when it can. */
    fun sheetNameProblem(name: String): String? = when {
        name.isBlank() -> "Tên sheet trống"
        name.length > 31 -> "Tên sheet tối đa 31 ký tự"
        name.any { it in "[]:*?/\\" } -> "Tên sheet không được chứa [ ] : * ? / \\"
        name.startsWith("'") || name.endsWith("'") -> "Tên sheet không được bắt đầu hay kết thúc bằng '"
        (0 until book.getSheetCount()).any { book.getSheet(it)?.getSheetName().equals(name, ignoreCase = true) } -> "Đã có sheet tên \"$name\""
        else -> null
    }

    /** A name like "Sheet4" that no sheet has yet. */
    fun nextSheetName(): String {
        var n = book.getSheetCount() + 1
        while (sheetNameProblem("Sheet$n") != null) n++
        return "Sheet$n"
    }

    /** An empty sheet [name] after the last one, shown at once; its index, or -1 (see [lastError]). */
    fun addSheet(name: String): Int {
        sheetNameProblem(name)?.let { fail(Reason.INVALID_ARGUMENT, it); return -1 }
        val first = book.getSheet(0)
        val sheet = Sheet().apply {
            setWorkbook(book)
            setSheetName(name)
            first?.let { setDefaultRowHeight(it.getDefaultRowHeight()); setDefaultColWidth(it.getDefaultColWidth()) }
            setState(Sheet.State_Accomplished)
        }
        val index = book.getSheetCount()
        fun add() { book.addSheet(index, sheet); addedSheets.add(sheet); engine = XlsxFormulaEngine(book) }
        fun remove() { book.removeLastSheet(sheet); addedSheets.remove(sheet); engine = XlsxFormulaEngine(book) }
        add()
        undoStack.add(Step.Structure(apply = { add() }, revert = { remove() })); redoStack.clear()
        return index
    }

    // ---- rows and columns ---------------------------------------------------------------

    // column widths / row heights set, each with the number of row/column changes made before it
    private val sizes = ArrayList<Pair<SizeWrite, Int>>()

    /** Width of a column in characters (Excel's unit). */
    fun columnWidth(sheetIndex: Int, col: Int): Double =
        (book.getSheet(sheetIndex)?.getColumnPixelWidth(col) ?: 0f) / (SSConstant.COLUMN_CHAR_WIDTH * MainConstant.POINT_TO_PIXEL).toDouble()

    /** Height of a row in points. */
    fun rowHeight(sheetIndex: Int, row: Int): Double {
        val sheet = book.getSheet(sheetIndex) ?: return 15.0
        val px = sheet.getRow(row)?.getRowPixelHeight() ?: sheet.getDefaultRowHeight().toFloat()
        return px / MainConstant.POINT_TO_PIXEL.toDouble()
    }

    /** Sets the width of column [col] to [chars] characters (0.5..255). */
    fun setColumnWidth(sheetIndex: Int, col: Int, chars: Double): Boolean = setSize(sheetIndex, false, col, chars)

    /** Sets the height of row [row] to [points] (1..409). */
    fun setRowHeight(sheetIndex: Int, row: Int, points: Double): Boolean = setSize(sheetIndex, true, row, points)

    private fun setSize(sheetIndex: Int, rows: Boolean, index: Int, size: Double): Boolean {
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found")
        if (index < 0 || !size.isFinite() || size < (if (rows) 1.0 else 0.5) || size > (if (rows) 409.0 else 255.0))
            return fail(Reason.INVALID_ARGUMENT, "Bad size")
        val before = if (rows) rowHeight(sheetIndex, index) else columnWidth(sheetIndex, index)
        fun show(v: Double) {
            if (rows) {
                val row = sheet.getRow(index) ?: Row(1).also {
                    it.setRowNumber(index); it.setSheet(sheet); it.completed(); sheet.addRow(it)
                }
                row.setRowPixelHeight((v * MainConstant.POINT_TO_PIXEL).toFloat())
            } else sheet.setColumnPixelWidth(index, Math.round(v * SSConstant.COLUMN_CHAR_WIDTH * MainConstant.POINT_TO_PIXEL).toInt())
        }
        val entry = SizeWrite(sheetIndex, rows, index, size) to structure.size
        val apply = { show(size); sizes.add(entry); Unit }
        val revert = { show(before); sizes.removeAll { it === entry }; Unit }
        apply()
        undoStack.add(Step.Structure(apply, revert)); redoStack.clear()
        repaint()
        return true
    }

    /** [w] moved by the row/column changes made after it; null when they deleted it. */
    private fun finalSize(w: SizeWrite, after: Int): SizeWrite? {
        var v = w.index
        for (c in structure.drop(after)) {
            if (c.sheetIndex != w.sheetIndex || c.rows != w.rows) continue
            v = when {
                c.count > 0 -> if (v >= c.at) v + c.count else v
                v < c.at -> v
                v >= c.at - c.count -> v + c.count
                else -> return null
            }
        }
        return w.copy(index = v)
    }

    fun insertRows(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, true, at, count)
    fun deleteRows(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, true, at, -count)
    fun insertColumns(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, false, at, count)
    fun deleteColumns(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, false, at, -count)

    /**
     * A shape span ([pos], [len]) after inserting a band of [band] pixels at [start] (it moves
     * down, or grows when the band opens inside it) or deleting the band [start, start + band)
     * (the part inside the band goes).
     */
    private fun moveSpan(pos: Int, len: Int, start: Float, band: Float, insert: Boolean): Pair<Int, Int> {
        val b = Math.round(band)
        val s = Math.round(start)
        if (insert) return if (pos >= s) (pos + b) to len else if (pos + len > s) pos to (len + b) else pos to len
        fun cut(v: Int) = when { v >= s + b -> v - b; v > s -> s; else -> v }
        val from = cut(pos)
        return from to maxOf(0, cut(pos + len) - from)
    }

    /**
     * Inserts ([count] > 0) or deletes rows/columns at [at]: cells, merged ranges, tables and
     * filters move, every formula of the workbook referring to them is rewritten (#REF! for
     * deleted cells), and the change is replayed on the file by [save]. One undoable step.
     */
    private fun structural(sheetIndex: Int, rows: Boolean, at: Int, count: Int): Boolean {
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found")
        val limit = if (rows) 1048576 else 16384
        if (count == 0 || at < 0 || at >= limit || Math.abs(count) > limit - at) return fail(Reason.INVALID_ARGUMENT, "Bad row/column range")
        if (sheet.getState() != Sheet.State_Accomplished) return fail(Reason.INVALID_ARGUMENT, "Sheet is still loading")
        val change = RefShifter.Change(sheet.getSheetName() ?: "", rows, at, count)
        // snapshots for undo
        val formulasBefore = HashMap<Cell, String>()
        for (i in 0 until book.getSheetCount()) {
            val s = book.getSheet(i) ?: continue
            for (r in s.getFirstRowNum()..s.getLastRowNum()) {
                val row = s.getRow(r) ?: continue
                for (cell in row.cellCollection()) cell.formula?.let { formulasBefore[cell] = it }
            }
        }
        fun ranges(): List<CellRangeAddress> = ArrayList<CellRangeAddress>().apply {
            for (i in 0 until sheet.getMergeRangeCount()) sheet.getMergeRange(i)?.let { add(it) }
            sheet.getTables()?.forEach { t -> t.getTableReference()?.let { add(it) } }
            sheet.getAutoFilters().forEach { add(it.range) }
        }
        val rangesBefore = ranges().map { it to intArrayOf(it.getFirstRow(), it.getFirstColumn(), it.getLastRow(), it.getLastColumn()) }
        val dirtyBefore = LinkedHashSet(dirty)
        val formatsBefore = formats.mapValues { ArrayList(it.value) }
        var removedRows: List<Row> = emptyList()
        var removedCells: Map<Int, Map<Int, Cell>> = emptyMap()
        val write = StructureWrite(sheetIndex, rows, at, count)

        fun moveKey(k: Key): Key? {
            if (k.sheet != sheetIndex) return k
            val v = if (rows) k.row else k.col
            val moved = when {
                count > 0 -> if (v >= at) v + count else v
                v < at -> v
                v >= at - count -> v + count
                else -> return null
            }
            return if (rows) k.copy(row = moved) else k.copy(col = moved)
        }

        // pictures and charts were placed in pixels when read: move them with the rows/columns
        fun shapes(list: Array<IShape>): List<IShape> = list.flatMap { s -> listOf(s) + if (s is GroupShape) shapes(s.getShapes()) else emptyList() }
        val placed = shapes(sheet.getShapes()).mapNotNull { s -> s.bounds?.let { s to Rectangle(it.x, it.y, it.width, it.height) } }
        fun edge(i: Int) = if (rows) ModelUtil.instance().getValueY(sheet, i, 0) else ModelUtil.instance().getValueX(sheet, i, 0)

        val apply = {
            val bandStart = edge(at)
            var band = if (count < 0) edge(at - count) - bandStart else 0f
            if (rows) removedRows = sheet.shiftRows(at, count) else removedCells = sheet.shiftColumns(at, count)
            if (count > 0) band = edge(at + count) - bandStart
            for ((shape, r) in placed) {
                val (pos, len) = moveSpan(if (rows) r.y else r.x, if (rows) r.height else r.width, bandStart, band, count > 0)
                shape.bounds = if (rows) Rectangle(r.x, pos, r.width, len) else Rectangle(pos, r.y, len, r.height)
            }
            // merged ranges moved with the rows; tables and filters here
            sheet.getTables()?.forEach { t -> t.getTableReference()?.let { sheet.shiftRange(it, rows, at, count) } }
            sheet.getAutoFilters().forEach { sheet.shiftRange(it.range, rows, at, count) }
            for ((cell, f) in formulasBefore) {
                val owner = cell.getSheet()?.getSheetName() ?: continue
                cell.formula = RefShifter.shift(f, owner, change)
            }
            val keys = dirtyBefore.mapNotNull { moveKey(it) }
            dirty.clear(); dirty.addAll(keys)
            val fmts = formatsBefore.entries.mapNotNull { (k, v) -> moveKey(k)?.let { it to ArrayList(v) } }
            formats.clear(); fmts.forEach { (k, v) -> formats[k] = v }
            structure.add(write)
            refreshFormulas()
        }
        val revert = {
            // by identity: an equal earlier change must stay where it is
            structure.indexOfLast { it === write }.takeIf { it >= 0 }?.let { structure.removeAt(it) }
            for ((shape, r) in placed) shape.bounds = Rectangle(r.x, r.y, r.width, r.height)
            if (rows) {
                sheet.shiftRows(at, -count) // the inverse change; deleted rows come back below
                if (count < 0) sheet.restoreRows(removedRows)
            } else {
                sheet.shiftColumns(at, -count)
                if (count < 0) for ((r, cells) in removedCells) sheet.getRow(r)?.restoreCells(cells)
            }
            for ((range, v) in rangesBefore) {
                range.setFirstRow(v[0]); range.setFirstColumn(v[1]); range.setLastRow(v[2]); range.setLastColumn(v[3])
            }
            for ((cell, f) in formulasBefore) cell.formula = f
            dirty.clear(); dirty.addAll(dirtyBefore)
            formats.clear(); formatsBefore.forEach { (k, v) -> formats[k] = ArrayList(v) }
            refreshFormulas()
        }
        apply()
        undoStack.add(Step.Structure(apply, revert)); redoStack.clear()
        lastError = null
        repaint()
        return true
    }

    /** New engine for the moved formulas; values that changed (#REF! after a delete) are saved. */
    private fun refreshFormulas() {
        engine = XlsxFormulaEngine(book)
        val w = ArrayList<String>()
        for (changed in engine.recalc(w)) dirty.add(Key(changed.sheetIndex, changed.cell.getRowNumber(), changed.cell.getColNumber()))
        warnings = w
        for (i in 0 until book.getSheetCount()) {
            val s = book.getSheet(i) ?: continue
            for (r in s.getFirstRowNum()..s.getLastRowNum()) s.getRow(r)?.cellCollection()?.forEach { it.removeSTRoot() }
        }
    }

    fun sheetIndexOf(sheet: Sheet): Int = book.getSheetIndex(sheet)

    private fun cell(k: Key): Cell? = book.getSheet(k.sheet)?.getRow(k.row)?.getCell(k.col)

    /** What an edit box should show for a cell: "=FORMULA" or the raw value. */
    fun getInput(sheetIndex: Int, row: Int, col: Int): String = inputOf(cell(Key(sheetIndex, row, col)))

    private fun inputOf(cell: Cell?): String {
        if (cell == null) return ""
        cell.formula?.let { return "=$it" }
        return when (cell.getCellType()) {
            // a styled empty cell is read as a number without a value
            Cell.CELL_TYPE_NUMERIC -> cell.getNumberValue().let { if (it.isNaN()) "" else if (it == Math.rint(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
            Cell.CELL_TYPE_STRING -> engine.adapter.stringOf(cell)
            Cell.CELL_TYPE_BOOLEAN -> if (cell.getBooleanValue()) "TRUE" else "FALSE"
            Cell.CELL_TYPE_ERROR -> com.wxiwei.office.fc.hssf.formula.eval.ErrorEval.getText(cell.getErrorValue())
            else -> ""
        }
    }

    /** Returns false (see [lastError]) for a bad formula or a sheet that does not exist. */
    fun setCellInput(sheetIndex: Int, row: Int, col: Int, input: String): Boolean {
        val key = Key(sheetIndex, row, col)
        val before = inputOf(cell(key))
        if (!apply(key, input)) return false
        undoStack.add(Step.Input(key, before, input)); redoStack.clear()
        return true
    }

    /**
     * Formats one cell (see [CellFormat]); the view shows it at once and [save] writes a new cell
     * format to styles.xml. Returns false (see [lastError]) for an invalid format or cell.
     */
    fun setCellFormat(sheetIndex: Int, row: Int, col: Int, format: CellFormat): Boolean =
        setRangeFormat(sheetIndex, row, col, row, col, format)

    /** Formats every cell of a rectangle as one undoable step. */
    fun setRangeFormat(sheetIndex: Int, row1: Int, col1: Int, row2: Int, col2: Int, format: CellFormat): Boolean {
        format.validate()?.let { return fail(Reason.INVALID_ARGUMENT, it) }
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found")
        if (minOf(row1, col1, row2, col2) < 0 || maxOf(row1, row2) >= 1048576 || maxOf(col1, col2) >= 16384 ||
            (maxOf(row1, row2) - minOf(row1, row2) + 1L) * (maxOf(col1, col2) - minOf(col1, col2) + 1L) > MAX_FORMAT_CELLS
        ) return fail(Reason.INVALID_ARGUMENT, "Range out of bounds or too large")
        val steps = ArrayList<Step>()
        for (r in minOf(row1, row2)..maxOf(row1, row2)) for (c in minOf(col1, col2)..maxOf(col1, col2)) {
            val key = Key(sheetIndex, r, c)
            val cell = cell(key) ?: create(sheet, key)
            val before = cell.getCellStyleIndex()
            val after = styleFor(cell.getCellStyle(), format)
            val step = Step.Format(key, before, after, format)
            applyFormat(step, true)
            steps.add(step)
        }
        undoStack.add(if (steps.size == 1) steps[0] else Step.Group(steps)); redoStack.clear()
        lastError = null
        repaint()
        return true
    }

    private fun applyFormat(step: Step.Format, redo: Boolean) {
        val cell = cell(step.key) ?: return
        cell.setCellStyle(if (redo) step.afterStyle else step.beforeStyle)
        cell.removeSTRoot()
        val list = formats.getOrPut(step.key) { ArrayList() }
        if (redo) list.add(step.format) else if (list.isNotEmpty()) list.removeAt(list.lastIndex)
    }

    /** A new model style: [base] with [f] applied (fonts are copied, never changed in place). */
    private fun styleFor(base: CellStyle?, f: CellFormat): Int {
        val style = base?.copy() ?: CellStyle()
        if (f.changesFont) {
            val font = (book.getFont(style.getFontIndex().toInt()) ?: Font()).copy()
            f.bold?.let { font.setBold(it) }
            f.italic?.let { font.setItalic(it) }
            f.underline?.let { font.setUnderline(if (it) Font.U_SINGLE.toInt() else Font.U_NONE.toInt()) }
            f.strike?.let { font.setStrikeline(it) }
            f.fontSize?.let { font.setFontSize(it) }
            f.fontColor?.let { font.setColorIndex(book.addColor(argb(it))) }
            val fontIndex = freeIndex { book.getFont(it) != null }
            font.setIndex(fontIndex)
            book.addFont(fontIndex, font)
            style.setFontIndex(fontIndex.toShort())
        }
        f.fillColor?.let {
            style.setFillPattern(if (it == "none") null else BackgroundAndFill().apply {
                fillType = BackgroundAndFill.FILL_SOLID
                foregroundColor = argb(it)
            })
        }
        f.horizontal?.let { style.setHorizontalAlign(it) }
        f.vertical?.let { style.setVerticalAlign(it) }
        f.wrap?.let { style.setWrapText(it) }
        f.rotation?.let { style.setRotation(it.toShort()) }
        f.indent?.let { style.setIndent(it.toShort()) }
        f.border?.let { kind ->
            // its own copy: cells with the same borderId in the file share one border object
            style.setBorder(style.getBorder()?.copy() ?: com.wxiwei.office.ss.model.style.CellBorder())
            val line = when (kind) {
                "none" -> com.wxiwei.office.ss.model.style.BorderStyle.BORDER_NONE
                "thick" -> com.wxiwei.office.ss.model.style.BorderStyle.BORDER_MEDIUM
                else -> com.wxiwei.office.ss.model.style.BorderStyle.BORDER_THIN
            }
            val color = book.addColor(argb(f.borderColor ?: "000000")).toShort()
            if (kind == "bottom") { style.setBorderBottom(line); style.setBorderBottomColorIdx(color) }
            else {
                style.setBorderLeft(line); style.setBorderRight(line); style.setBorderTop(line); style.setBorderBottom(line)
                style.setBorderLeftColorIdx(color); style.setBorderRightColorIdx(color); style.setBorderTopColorIdx(color); style.setBorderBottomColorIdx(color)
            }
        }
        f.numberFormat?.let { code ->
            val builtin = BuiltinFormats.getBuiltinFormat(code)
            style.setNumberFormat(NumberFormat((if (builtin >= 0) builtin else CUSTOM_FORMAT_ID).toShort(), code))
        }
        val index = freeIndex { book.getCellStyle(it) != null }
        style.setIndex(index.toShort())
        book.addCellStyle(index, style)
        return index
    }

    private fun argb(rgb: String): Int = (0xFF shl 24) or rgb.removePrefix("#").toInt(16)

    /** An index the workbook does not use yet (model indexes; the file gets its own on save). */
    private fun freeIndex(used: (Int) -> Boolean): Int {
        var i = FIRST_EDIT_INDEX
        while (used(i)) i++
        return i
    }

    fun clearCell(sheetIndex: Int, row: Int, col: Int) = setCellInput(sheetIndex, row, col, "")

    // ---- copy and paste ----------------------------------------------------------------------

    /**
     * Cells copied from [row], [col]: each cell's input ("=formula" or its value) and look, row by
     * row, and [text] as other apps take it (shown values, tabs between cells, a line per row).
     */
    class CellBlock internal constructor(
        val row: Int, val col: Int, val rows: Int, val cols: Int,
        internal val inputs: List<String>, internal val formats: List<CellFormat?>, val text: String,
    )

    /** The cells of a rectangle (see [CellBlock]); null (see [lastError]) when it is too large or the sheet does not exist. */
    fun copyRange(sheetIndex: Int, row1: Int, col1: Int, row2: Int, col2: Int): CellBlock? {
        val r1 = minOf(row1, row2); val r2 = maxOf(row1, row2); val c1 = minOf(col1, col2); val c2 = maxOf(col1, col2)
        if (book.getSheet(sheetIndex) == null) { fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found"); return null }
        if (r1 < 0 || c1 < 0 || (r2 - r1 + 1L) * (c2 - c1 + 1L) > MAX_PASTE_CELLS) { fail(Reason.INVALID_ARGUMENT, "Range out of bounds or too large"); return null }
        val inputs = ArrayList<String>(); val formats = ArrayList<CellFormat?>()
        val text = StringBuilder()
        for (r in r1..r2) {
            for (c in c1..c2) {
                val cell = cell(Key(sheetIndex, r, c))
                inputs += inputOf(cell)
                formats += cell?.let { formatOf(it) }
                if (c > c1) text.append('\t')
                text.append(cell?.let { ModelUtil.instance().getFormatContents(book, it) }.orEmpty().replace('\n', ' ').replace('\t', ' '))
            }
            if (r < r2) text.append('\n')
        }
        lastError = null
        return CellBlock(r1, c1, r2 - r1 + 1, c2 - c1 + 1, inputs, formats, text.toString())
    }

    /**
     * Pastes [block] with its top-left cell at [row], [col]: values, formulas (relative references
     * moved by the distance, like Excel) and the look of each cell; one undoable step.
     */
    fun pasteBlock(sheetIndex: Int, row: Int, col: Int, block: CellBlock): Boolean {
        val dr = row - block.row; val dc = col - block.col
        return pasteCells(sheetIndex, row, col, block.rows, block.cols, { i ->
            val input = block.inputs[i]
            if (input.startsWith("=") && (dr != 0 || dc != 0)) "=" + A1FormulaShifter.shift(input.substring(1), dr, dc) else input
        }) { i -> block.formats[i] }
    }

    /** Pastes text from another app at [row], [col]: a cell per tab-separated value, a row per line; one undoable step. */
    fun pasteText(sheetIndex: Int, row: Int, col: Int, text: String): Boolean {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').split('\n').map { it.split('\t') }
        val cols = lines.maxOf { it.size }
        return pasteCells(sheetIndex, row, col, lines.size, cols, { i -> lines[i / cols].getOrElse(i % cols) { "" } }) { null }
    }

    /** Empties every cell of a rectangle (values and formulas; the look stays); one undoable step. */
    fun clearRange(sheetIndex: Int, row1: Int, col1: Int, row2: Int, col2: Int): Boolean {
        val r1 = minOf(row1, row2); val c1 = minOf(col1, col2)
        val rows = maxOf(row1, row2) - r1 + 1; val cols = maxOf(col1, col2) - c1 + 1
        return pasteCells(sheetIndex, r1, c1, rows, cols, { "" }) { null }
    }

    /** Writes [rows] x [cols] cells from [row], [col]: the input of cell i (row by row) and its look, if any. */
    private fun pasteCells(sheetIndex: Int, row: Int, col: Int, rows: Int, cols: Int, input: (Int) -> String, format: (Int) -> CellFormat?): Boolean {
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found")
        if (row < 0 || col < 0 || row + rows > 1048576 || col + cols > 16384 || rows.toLong() * cols > MAX_PASTE_CELLS)
            return fail(Reason.INVALID_ARGUMENT, "Range out of bounds or too large")
        // every formula is checked first: nothing is written when one does not parse
        for (i in 0 until rows * cols) input(i).trim().takeIf { it.startsWith("=") && it.length > 1 }?.let {
            try { engine.adapter.parse(it.substring(1), sheetIndex) } catch (e: Exception) { return fail(Reason.INVALID_ARGUMENT, "Formula error: ${e.message}") }
        }
        val steps = ArrayList<Step>()
        for (i in 0 until rows * cols) {
            val key = Key(sheetIndex, row + i / cols, col + i % cols)
            val before = inputOf(cell(key))
            val after = input(i)
            if (before != after) {
                if (!apply(key, after)) { replay(Step.Group(steps), false); return false }
                steps += Step.Input(key, before, after)
            }
            val f = format(i) ?: continue
            val c = cell(key) ?: create(sheet, key)
            val step = Step.Format(key, c.getCellStyleIndex(), styleFor(c.getCellStyle(), f), f)
            applyFormat(step, true)
            steps += step
        }
        if (steps.isNotEmpty()) { undoStack.add(Step.Group(steps)); redoStack.clear() }
        lastError = null
        repaint()
        return true
    }

    /** The look of [cell] as a format that gives another cell the same look. */
    private fun formatOf(cell: Cell): CellFormat? {
        val style = cell.getCellStyle() ?: return null
        val font = book.getFont(style.getFontIndex().toInt())
        fun hex(argb: Int) = "%06X".format(argb and 0xFFFFFF)
        val fill = style.getFillPattern()?.takeIf { it.fillType == BackgroundAndFill.FILL_SOLID }?.let { hex(it.foregroundColor) }
        val horizontal = when (style.getHorizontalAlign()) {
            CellStyle.ALIGN_LEFT -> "left"; CellStyle.ALIGN_CENTER -> "center"; CellStyle.ALIGN_RIGHT -> "right"
            CellStyle.ALIGN_FILL -> "fill"; CellStyle.ALIGN_JUSTIFY -> "justify"; CellStyle.ALIGN_CENTER_SELECTION -> "centerContinuous"
            else -> "general"
        }
        val vertical = when (style.getVerticalAlign()) {
            CellStyle.VERTICAL_TOP -> "top"; CellStyle.VERTICAL_CENTER -> "center"; CellStyle.VERTICAL_JUSTIFY -> "justify"; else -> "bottom"
        }
        // borders: all four sides, the bottom only, or none (thick when any side is thicker than thin)
        val sides = listOf(style.getBorderLeft(), style.getBorderRight(), style.getBorderTop(), style.getBorderBottom())
        val border = when {
            sides.all { it != CellStyle.BORDER_NONE } -> if (sides.any { it != CellStyle.BORDER_THIN }) "thick" else "all"
            sides.take(3).all { it == CellStyle.BORDER_NONE } && sides[3] != CellStyle.BORDER_NONE -> "bottom"
            else -> "none"
        }
        val borderColor = if (border == "none") null else hex(book.getColor(style.getBorderBottomColorIdx().toInt(), true))
        return CellFormat(
            bold = font?.isBold() ?: false, italic = font?.isItalic() ?: false, underline = (font?.getUnderline() ?: 0) != 0,
            strike = font?.isStrikeline() ?: false, fontSize = font?.getFontSize(),
            fontColor = font?.let { hex(book.getColor(it.getColorIndex())) },
            fillColor = fill ?: "none", horizontal = horizontal, vertical = vertical, wrap = style.isWrapText(),
            numberFormat = style.getFormatCode()?.takeIf { it.isNotEmpty() && !it.equals("General", true) },
            rotation = style.getRotation().toInt().takeIf { it != 0 }, indent = style.getIndent().toInt().takeIf { it != 0 },
            border = border, borderColor = borderColor,
        ).takeIf { it.validate() == null }
    }

    // ---- pictures ---------------------------------------------------------------------------

    private class NewPicture(val sheet: Int, val shape: com.wxiwei.office.common.shape.PictureShape, val file: File)
    /** Pictures added in this session, in order: [save] writes them into the sheets' drawings where they are then. */
    private val pictures = ArrayList<NewPicture>()
    /** Pictures of the file moved or resized ([save] writes where they are then), or deleted, by sheet. */
    private val movedPictures = LinkedHashSet<Pair<Int, IShape>>()
    private val deletedPictures = LinkedHashSet<Pair<Int, Int>>()

    /**
     * Puts [image] (png, jpeg, gif, bmp) on the sheet with its top-left corner at the cell, [widthPx]
     * x [heightPx] at 100% zoom; shown at once, one undoable step, [save] adds it to the drawing.
     */
    fun addPicture(sheetIndex: Int, row: Int, col: Int, image: File, widthPx: Int, heightPx: Int): Boolean {
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found")
        val pm = pictureManage ?: return fail(Reason.UNSUPPORTED_FORMAT, "No picture store")
        val ext = image.extension.lowercase().let { if (it == "jpg") "jpeg" else it }
        if (ext !in setOf("png", "jpeg", "gif", "bmp")) return fail(Reason.INVALID_ARGUMENT, "Unsupported picture type: $ext")
        if (row < 0 || col < 0 || row >= 1048576 || col >= 16384 || widthPx <= 0 || heightPx <= 0) return fail(Reason.INVALID_ARGUMENT, "Bad picture place or size")
        val picture = com.wxiwei.office.common.picture.Picture()
        picture.data = image.readBytes()
        picture.setPictureType(ext)
        val shape = com.wxiwei.office.common.shape.PictureShape()
        shape.pictureIndex = pm.addPicture(picture)
        val anchor = com.wxiwei.office.ss.model.drawing.CellAnchor(com.wxiwei.office.ss.model.drawing.CellAnchor.ONECELLANCHOR).apply {
            setStart(com.wxiwei.office.ss.model.drawing.AnchorPoint().apply { setRow(row); setColumn(col.toShort()) })
            setWidth(widthPx); setHeight(heightPx)
        }
        shape.bounds = ModelUtil.instance().getCellAnchor(sheet, anchor)
        // its id in the drawing from now on: saved with it, it stays movable in the next session
        sheet.setPictureId(shape, sheet.maxDrawingId + 1)
        val added = NewPicture(sheetIndex, shape, image)
        fun add() { sheet.appendShapes(shape); pictures.add(added); repaint() }
        fun remove() { sheet.removeShape(shape); pictures.remove(added); repaint() }
        add()
        undoStack.add(Step.Structure(apply = { add() }, revert = { remove() })); redoStack.clear()
        lastError = null
        return true
    }

    /** The sheet index of a picture that can be moved, resized and deleted (added now, or of the file's drawing), or -1. */
    private fun pictureSheet(shape: IShape?): Int {
        if (shape == null) return -1
        pictures.firstOrNull { it.shape === shape }?.let { return it.sheet }
        for (i in 0 until book.getSheetCount()) {
            val sheet = book.getSheet(i) ?: continue
            if (sheet.pictureId(shape) != null && sheet.indexOfShape(shape) >= 0) return i
        }
        return -1
    }

    /** True for a picture that can be moved, resized and deleted: added in this session, or a picture of the file. */
    fun isMovablePicture(shape: IShape?): Boolean = pictureSheet(shape) >= 0

    /** Moves and/or resizes a picture to [bounds] (sheet pixels at 100%); one undoable step. */
    fun setPictureBounds(shape: IShape, bounds: Rectangle): Boolean {
        val sheetIndex = pictureSheet(shape)
        if (sheetIndex < 0) return fail(Reason.INVALID_ARGUMENT, "Not a picture that can be moved")
        if (bounds.width <= 0 || bounds.height <= 0) return fail(Reason.INVALID_ARGUMENT, "Bad picture size")
        val before = shape.bounds!!.let { Rectangle(it.x, it.y, it.width, it.height) }
        val after = Rectangle(maxOf(0, bounds.x), maxOf(0, bounds.y), bounds.width, bounds.height)
        if (before == after) { lastError = null; return true }
        fun place(r: Rectangle) { shape.bounds = Rectangle(r.x, r.y, r.width, r.height); repaint() }
        if (pictures.none { it.shape === shape }) movedPictures += sheetIndex to shape
        place(after)
        undoStack.add(Step.Structure(apply = { place(after) }, revert = { place(before) })); redoStack.clear()
        lastError = null
        return true
    }

    /** Takes a picture off its sheet; one undoable step. */
    fun removePicture(shape: IShape): Boolean {
        val added = pictures.firstOrNull { it.shape === shape } ?: return removeFilePicture(shape)
        val sheet = book.getSheet(added.sheet) ?: return fail(Reason.NOT_FOUND, "Sheet ${added.sheet} not found")
        val at = pictures.indexOf(added)
        fun remove() { sheet.removeShape(shape); pictures.remove(added); repaint() }
        fun add() { sheet.appendShapes(shape); pictures.add(at.coerceAtMost(pictures.size), added); repaint() }
        remove()
        undoStack.add(Step.Structure(apply = { remove() }, revert = { add() })); redoStack.clear()
        lastError = null
        return true
    }

    /** A picture of the file taken off: [save] deletes its anchor. */
    private fun removeFilePicture(shape: IShape): Boolean {
        val sheetIndex = pictureSheet(shape)
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.INVALID_ARGUMENT, "Not a picture that can be deleted")
        val id = sheet.pictureId(shape) ?: return fail(Reason.INVALID_ARGUMENT, "Not a picture that can be deleted")
        val at = sheet.indexOfShape(shape)
        fun remove() { sheet.removeShape(shape); deletedPictures += sheetIndex to id; repaint() }
        fun add() { sheet.insertShape(at, shape); deletedPictures -= sheetIndex to id; repaint() }
        remove()
        undoStack.add(Step.Structure(apply = { remove() }, revert = { add() })); redoStack.clear()
        lastError = null
        return true
    }

    /** The cell holding a sheet point (sheet pixels at 100%) and the point's offset in it: row, dy, column, dx. */
    private fun cellOffset(sheet: Sheet, x: Int, y: Int): IntArray {
        var col = 0
        var left = 0f
        while (col < 16383) {
            if (!sheet.isColumnHidden(col)) {
                val w = sheet.getColumnPixelWidth(col)
                if (left + w > x) break
                left += w
            }
            col++
        }
        var row = 0
        var top = 0f
        while (row < 1048575) {
            val r = sheet.getRow(row)
            if (r == null || !r.isZeroHeight()) {
                val h = r?.getRowPixelHeight() ?: sheet.getDefaultRowHeight().toFloat()
                if (top + h > y) break
                top += h
            }
            row++
        }
        return intArrayOf(row, maxOf(0, Math.round(y - top)), col, maxOf(0, Math.round(x - left)))
    }

    // ---- merged cells ---------------------------------------------------------------------

    /** Sheets whose merged ranges changed: [save] writes their `<mergeCells>` from the model. */
    private val mergedSheets = HashSet<Int>()

    /** The merged range containing the cell, or null. */
    fun mergeAt(sheetIndex: Int, row: Int, col: Int): com.wxiwei.office.ss.model.CellRangeAddress? {
        val sheet = book.getSheet(sheetIndex) ?: return null
        val i = sheet.mergeIndexAt(row, col)
        return if (i < 0) null else sheet.getMergeRange(i)
    }

    /** True when merging the range would drop values: cells other than its top-left one hold something. */
    fun mergeDropsValues(sheetIndex: Int, row1: Int, col1: Int, row2: Int, col2: Int): Boolean {
        for (r in minOf(row1, row2)..maxOf(row1, row2)) for (c in minOf(col1, col2)..maxOf(col1, col2)) {
            if (r == minOf(row1, row2) && c == minOf(col1, col2)) continue
            if (getInput(sheetIndex, r, c).isNotEmpty()) return true
        }
        return false
    }

    /**
     * Merges the rectangle into one cell like Excel's "Merge & Center" without the centering: the
     * top-left value stays, the other values are cleared, merged ranges inside it are absorbed.
     * One undoable step; shown at once, [save] writes `<mergeCells>`.
     */
    fun mergeCells(sheetIndex: Int, row1: Int, col1: Int, row2: Int, col2: Int): Boolean {
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found")
        val r1 = minOf(row1, row2); val r2 = maxOf(row1, row2); val c1 = minOf(col1, col2); val c2 = maxOf(col1, col2)
        if (r1 < 0 || c1 < 0 || r2 >= 1048576 || c2 >= 16384 || (r2 - r1 + 1L) * (c2 - c1 + 1L) > MAX_FORMAT_CELLS)
            return fail(Reason.INVALID_ARGUMENT, "Range out of bounds or too large")
        if (r1 == r2 && c1 == c2) return fail(Reason.INVALID_ARGUMENT, "Select more than one cell to merge")
        val inside = (0 until sheet.getMergeRangeCount()).filter { i ->
            val m = sheet.getMergeRange(i)!!
            sheet.isLiveMerge(m) && !(m.getFirstRow() > r2 || m.getLastRow() < r1 || m.getFirstColumn() > c2 || m.getLastColumn() < c1)
        }
        if (inside.any { i -> sheet.getMergeRange(i)!!.let { it.getFirstRow() < r1 || it.getLastRow() > r2 || it.getFirstColumn() < c1 || it.getLastColumn() > c2 } })
            return fail(Reason.INVALID_ARGUMENT, "The range cuts a merged cell")
        val steps = ArrayList<Step>()
        // values other than the top-left one go, like in Excel
        for (r in r1..r2) for (c in c1..c2) {
            if (r == r1 && c == c1) continue
            val key = Key(sheetIndex, r, c)
            val before = inputOf(cell(key))
            if (before.isEmpty()) continue
            if (!apply(key, "")) return false
            steps.add(Step.Input(key, before, ""))
        }
        for (r in r1..r2) for (c in c1..c2) { val key = Key(sheetIndex, r, c); if (cell(key) == null) create(sheet, key) }
        val absorbed = inside.map { it to com.wxiwei.office.ss.model.CellRangeAddress(sheet.getMergeRange(it)!!.getFirstRow(), sheet.getMergeRange(it)!!.getFirstColumn(), sheet.getMergeRange(it)!!.getLastRow(), sheet.getMergeRange(it)!!.getLastColumn()) }
        val bounds = com.wxiwei.office.ss.model.CellRangeAddress(r1, c1, r2, c2)
        val index = sheet.addMergeRange(com.wxiwei.office.ss.model.CellRangeAddress(r1, c1, r2, c2)) - 1
        fun merge() { absorbed.forEach { sheet.parkMerge(it.first) }; sheet.restoreMerge(index, bounds); mergedSheets.add(sheetIndex); sheet.setActiveCellRowCol(r1, c1) }
        fun split() { sheet.parkMerge(index); absorbed.forEach { (i, b) -> sheet.restoreMerge(i, b) }; mergedSheets.add(sheetIndex) }
        merge()
        steps.add(Step.Structure(apply = { merge() }, revert = { split() }))
        undoStack.add(Step.Group(steps)); redoStack.clear()
        lastError = null
        repaint()
        return true
    }

    /** Splits the merged range containing the cell back into single cells; one undoable step. */
    fun unmergeCells(sheetIndex: Int, row: Int, col: Int): Boolean {
        val sheet = book.getSheet(sheetIndex) ?: return fail(Reason.NOT_FOUND, "Sheet $sheetIndex not found")
        val index = sheet.mergeIndexAt(row, col)
        if (index < 0) return fail(Reason.NOT_FOUND, "The cell is not merged")
        val m = sheet.getMergeRange(index)!!
        val bounds = com.wxiwei.office.ss.model.CellRangeAddress(m.getFirstRow(), m.getFirstColumn(), m.getLastRow(), m.getLastColumn())
        fun split() { sheet.parkMerge(index); mergedSheets.add(sheetIndex) }
        fun merge() { sheet.restoreMerge(index, bounds); mergedSheets.add(sheetIndex) }
        split()
        undoStack.add(Step.Structure(apply = { split() }, revert = { merge() })); redoStack.clear()
        lastError = null
        repaint()
        return true
    }

    fun undo(): Boolean {
        val c = undoStack.lastOrNull() ?: return false
        if (!replay(c, false)) return false
        undoStack.removeAt(undoStack.lastIndex); redoStack.add(c); repaint(); return true
    }

    fun redo(): Boolean {
        val c = redoStack.lastOrNull() ?: return false
        if (!replay(c, true)) return false
        redoStack.removeAt(redoStack.lastIndex); undoStack.add(c); repaint(); return true
    }

    private fun replay(step: Step, redo: Boolean): Boolean = when (step) {
        is Step.Input -> apply(step.key, if (redo) step.after else step.before)
        is Step.Format -> { applyFormat(step, redo); true }
        is Step.Group -> (if (redo) step.steps else step.steps.asReversed()).all { replay(it, redo) }
        is Step.Structure -> { if (redo) step.apply() else step.revert(); true }
    }

    private fun fail(reason: Reason, message: String): Boolean { lastError = EditResult.Error(reason, message); return false }

    private fun apply(key: Key, input: String): Boolean {
        val sheet = book.getSheet(key.sheet) ?: return fail(Reason.NOT_FOUND, "Sheet ${key.sheet} not found")
        if (key.row < 0 || key.col < 0 || key.row >= 1048576 || key.col >= 16384) return fail(Reason.INVALID_ARGUMENT, "Cell out of range")
        val text = input.trim()
        val formula = if (text.startsWith("=") && text.length > 1) text.substring(1) else null
        if (formula != null) {
            try { engine.adapter.parse(formula, key.sheet) } catch (e: Exception) {
                return fail(Reason.INVALID_ARGUMENT, "Formula error: ${e.message}")
            }
        }
        val cell = cell(key) ?: create(sheet, key)
        val hadFormula = cell.formula != null
        cell.formula = formula
        when {
            formula != null -> Unit // value comes from the recalculation below
            text.isEmpty() -> { cell.setCellType(Cell.CELL_TYPE_BLANK); cell.setCellValue(null) }
            text.equals("TRUE", true) || text.equals("FALSE", true) -> { cell.setCellType(Cell.CELL_TYPE_BOOLEAN); cell.setCellValue(text.equals("TRUE", true)) }
            number(text) != null -> { cell.setCellType(Cell.CELL_TYPE_NUMERIC); cell.setCellValue(number(text)) }
            else -> { cell.setCellType(Cell.CELL_TYPE_STRING); cell.setCellValue(book.addSharedString(input)) }
        }
        cell.removeSTRoot()
        if (formula != null || hadFormula) engine.formulaChanged(key.sheet, cell) else engine.valueChanged(key.sheet, cell)
        if (formula != null) {
            // Evaluate the edited cell first so a bad reference shows as an error value, not a stale one
            try { engine.store(cell, engine.evaluate(key.sheet, cell)) } catch (e: Exception) {
                cell.setCellType(Cell.CELL_TYPE_ERROR); cell.setCellValue(com.wxiwei.office.fc.ss.usermodel.ErrorConstants.ERROR_NAME.toByte())
            }
        }
        dirty.add(key)
        val w = ArrayList<String>()
        for (changed in engine.recalcAfter(key.sheet, cell, w)) dirty.add(Key(changed.sheetIndex, changed.cell.getRowNumber(), changed.cell.getColNumber()))
        warnings = w
        lastError = null
        repaint()
        return true
    }

    private fun number(text: String): Double? =
        if (text.matches(Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?"))) text.toDoubleOrNull() else null

    private fun create(sheet: Sheet, key: Key): Cell {
        val row = sheet.getRow(key.row) ?: Row(key.col + 1).also {
            it.setRowNumber(key.row); it.setSheet(sheet)
            it.setRowPixelHeight(sheet.getDefaultRowHeight().toFloat())
            it.completed()
            sheet.addRow(it)
        }
        val cell = Cell(Cell.CELL_TYPE_BLANK)
        cell.setSheet(sheet); cell.setRowNumber(key.row); cell.setColNumber(key.col)
        cell.setCellStyle(if (row.getRowStyle() > 0) row.getRowStyle() else sheet.getColumnStyle(key.col))
        row.addCell(cell)
        return cell
    }

    /** Patch the original file with every changed cell. The view already shows it: no reopen needed. */
    fun save(target: File): EditResult {
        val writes = dirty.mapNotNull { k ->
            val cell = cell(k)
            val f = cell?.formula
            when {
                cell == null -> CellWrite.Blank(k.sheet, k.row, k.col)
                cell.getCellType() == Cell.CELL_TYPE_NUMERIC -> CellWrite.Number(k.sheet, k.row, k.col, cell.getNumberValue().let { if (it.isNaN()) 0.0 else it }, f)
                cell.getCellType() == Cell.CELL_TYPE_STRING -> CellWrite.Text(k.sheet, k.row, k.col, engine.adapter.stringOf(cell), f)
                cell.getCellType() == Cell.CELL_TYPE_BOOLEAN -> CellWrite.Bool(k.sheet, k.row, k.col, cell.getBooleanValue(), f)
                cell.getCellType() == Cell.CELL_TYPE_ERROR -> CellWrite.Error(k.sheet, k.row, k.col, cell.getErrorValue(), f)
                f != null -> CellWrite.Number(k.sheet, k.row, k.col, 0.0, f)
                else -> CellWrite.Blank(k.sheet, k.row, k.col)
            }
        }
        val styles = formats.filterValues { it.isNotEmpty() }
            .map { (k, list) -> StyleWrite(k.sheet, k.row, k.col, list.reduce { a, b -> a + b }) }
        val result = XlsxWriter(source) { s, r, c -> book.getSheet(s)?.getRow(r)?.getCell(c)?.formula }
            .save(target, writes, styles, structure, sizes.mapNotNull { (w, after) -> finalSize(w, after) }, addedSheets.map { it.getSheetName() ?: "Sheet" },
                mergedSheets.associateWith { i -> book.getSheet(i)?.liveMerges().orEmpty().map { m ->
                    A1FormulaShifter.address(m.getFirstRow(), m.getFirstColumn()) + ":" + A1FormulaShifter.address(m.getLastRow(), m.getLastColumn())
                } },
                pictures.mapNotNull { p ->
                    val sheet = book.getSheet(p.sheet) ?: return@mapNotNull null
                    val b = p.shape.bounds ?: return@mapNotNull null
                    val (row, dy, col, dx) = cellOffset(sheet, b.x, b.y)
                    PictureWrite(p.sheet, row, col, b.width * EMU_PER_PX, b.height * EMU_PER_PX, p.file, dx * EMU_PER_PX, dy * EMU_PER_PX,
                        id = sheet.pictureId(p.shape) ?: 0)
                }, pictureEdits())
        return if (result is EditResult.Ok) result.copy(warnings = warnings) else result
    }

    companion object {
        private const val FIRST_EDIT_INDEX = 20_000 // font and style indexes are Shorts
        private const val CUSTOM_FORMAT_ID = 200
        private const val MAX_FORMAT_CELLS = 100_000L
        /** At most this many cells are copied or pasted at once. */
        private const val MAX_PASTE_CELLS = 20_000L
        private const val EMU_PER_PX = (MainConstant.EMU_PER_INCH / MainConstant.PIXEL_DPI).toLong()

        private fun spreadsheetOf(control: IControl): Spreadsheet =
            (control.getView() as? ExcelView)?.getSpreadsheet() ?: error("Open an .xlsx first")
    }
}
