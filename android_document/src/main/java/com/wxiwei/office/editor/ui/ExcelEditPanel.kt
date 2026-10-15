package com.wxiwei.office.editor.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.editor.docsdk.CellAlignmentFormat
import com.editor.docsdk.EditAction
import com.editor.docsdk.EditContext
import com.editor.docsdk.EditRequest
import com.editor.docsdk.EditFeature
import com.wxiwei.office.R
import com.wxiwei.office.editor.xlsx.A1FormulaShifter
import com.wxiwei.office.editor.xlsx.CellFormat
import com.wxiwei.office.editor.xlsx.SheetEditSession
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.ss.control.ExcelView
import com.wxiwei.office.ss.model.style.CellStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Excel: the selected cell (tap a cell in the sheet) can be typed into (values or =formulas,
 * dependent formulas recalculate at once) and formatted; Save writes the .xlsx in place.
 */
class ExcelEditPanel @JvmOverloads constructor(
    activity: AppCompatActivity, reader: OfficeDocumentView, file: File, features: Set<EditFeature> = EditFeature.all(),
) : OfficeEditPanel(activity, reader, file, features) {

    private val excel = reader.control!!.getView() as ExcelView
    private var session = SheetEditSession(reader.control!!, file)
    private var sheet = -1
    private var row = -1
    private var col = -1

    private val cellName = label("A1").apply {
        contentDescription = str(R.string.docsdk_edit_cell_or_range)
        setOnClickListener { askRange() }
    }
    private val value = input(str(R.string.docsdk_edit_cell_value_hint)).apply {
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, _, _ -> applyValue(); true }
        isEnabled = has(EditFeature.TEXT)
    }
    private val watcher: Job

    // the formula box: the cell name (tap it to go to a cell or range), the cell's input, a check to apply it
    private val apply = android.widget.ImageButton(context).apply {
        setImageResource(editStyle.icons?.icon(EditAction.CELL_VALUE) ?: R.drawable.docsdk_ic_check)
        imageTintList = android.content.res.ColorStateList.valueOf(editStyle.accent)
        background = null
        contentDescription = str(android.R.string.ok)
        tag = "APPLY_VALUE"
        visibility = if (has(EditFeature.TEXT)) View.VISIBLE else View.GONE
        setOnClickListener { applyValue() }
    }
    override val header: View = line(cellName, value, apply, weights = floatArrayOf(0f, 1f, 0f)).apply {
        setPadding(dp(8), 0, dp(4), 0)
    }

    /** The selected cell or range, like "B3" or "A1:C10". */
    val selectedRange: String get() = cellName.text.toString()

    /** What the formula box holds for the selected cell: its text, number or "=formula" (as typed so far). */
    val cellInput: String get() = value.text.toString()

    override val tabs = listOf(
        Tab(R.string.docsdk_edit_tab_home, listOf(EditAction.COPY, EditAction.CUT, EditAction.PASTE, EditAction.BOLD, EditAction.ITALIC, EditAction.UNDERLINE, EditAction.STRIKETHROUGH,
            EditAction.TEXT_COLOR, EditAction.FILL_COLOR, EditAction.FONT_SIZE, EditAction.ALIGN_LEFT, EditAction.ALIGN_CENTER,
            EditAction.ALIGN_RIGHT, EditAction.WRAP_TEXT, EditAction.CELL_ALIGNMENT)),
        Tab(R.string.docsdk_edit_tab_cells, listOf(EditAction.NUMBER_FORMAT, EditAction.BORDERS, EditAction.MERGE_CELLS,
            EditAction.TEXT_ROTATION, EditAction.CLEAR_CELLS, EditAction.GO_TO_CELL)),
        Tab(R.string.docsdk_edit_tab_rows_columns, listOf(EditAction.ROW_ABOVE, EditAction.ROW_BELOW, EditAction.DELETE_ROW,
            EditAction.COLUMN_LEFT, EditAction.COLUMN_RIGHT, EditAction.DELETE_COLUMN, EditAction.ROW_HEIGHT, EditAction.ROW_SHORTER,
            EditAction.ROW_TALLER, EditAction.COLUMN_WIDTH, EditAction.COLUMN_NARROWER, EditAction.COLUMN_WIDER)),
        Tab(R.string.docsdk_edit_tab_insert, listOf(EditAction.INSERT_PICTURE, EditAction.ADD_SHEET)),
    )

    override fun canUndo() = session.canUndo()
    override fun canRedo() = session.canRedo()

    init {
        action(EditAction.UNDO, EditFeature.UNDO_REDO) { if (!session.undo()) toast(str(R.string.docsdk_edit_nothing_to_undo)) else { syncSheets(); dropStalePicture(); refresh(true) } }
        action(EditAction.REDO, EditFeature.UNDO_REDO) { if (!session.redo()) toast(str(R.string.docsdk_edit_nothing_to_redo)) else { syncSheets(); dropStalePicture(); refresh(true) } }
        action(EditAction.SAVE) { commitTyped(); save() }
        action(EditAction.SAVE_COPY, EditFeature.SAVE_COPY) { commitTyped(); saveCopy() }
        action(EditAction.CELL_VALUE, EditFeature.TEXT) {
            if (sheet < 0) return@action toast(str(R.string.docsdk_edit_select_cell_first))
            askText(str(R.string.docsdk_edit_cell_value), str(R.string.docsdk_edit_cell_value_hint), value.text.toString()) { v ->
                value.setText(v)
                applyValue()
            }
        }
        action(EditAction.GO_TO_CELL) { askRange() }
        action(EditAction.COPY, EditFeature.CLIPBOARD) { copyCells() }
        action(EditAction.CUT, EditFeature.CLIPBOARD, EditFeature.TEXT) { if (copyCells()) clearCells() }
        action(EditAction.PASTE, EditFeature.CLIPBOARD, EditFeature.TEXT) { pasteCells() }
        action(EditAction.BOLD, EditFeature.FORMAT) { toggleFont { CellFormat(bold = !it.isBold()) } }
        action(EditAction.ITALIC, EditFeature.FORMAT) { toggleFont { CellFormat(italic = !it.isItalic()) } }
        action(EditAction.UNDERLINE, EditFeature.FORMAT) { toggleFont { CellFormat(underline = it.getUnderline() == 0) } }
        action(EditAction.STRIKETHROUGH, EditFeature.FORMAT) { toggleFont { CellFormat(strike = !it.isStrikeline()) } }
        action(EditAction.TEXT_COLOR, EditFeature.FORMAT) { pickColor(str(R.string.docsdk_edit_text_color)) { c -> c?.let { format(CellFormat(fontColor = it)) } } }
        action(EditAction.FILL_COLOR, EditFeature.FORMAT) { pickColor(str(R.string.docsdk_edit_fill_color), none = str(R.string.docsdk_edit_fill_none)) { c -> format(CellFormat(fillColor = c ?: "none")) } }
        action(EditAction.FONT_SIZE, EditFeature.FORMAT) { pickSize { format(CellFormat(fontSize = it.toDouble())) } }
        action(EditAction.ALIGN_LEFT, EditFeature.FORMAT) { format(CellFormat(horizontal = "left")) }
        action(EditAction.ALIGN_CENTER, EditFeature.FORMAT) { format(CellFormat(horizontal = "center")) }
        action(EditAction.ALIGN_RIGHT, EditFeature.FORMAT) { format(CellFormat(horizontal = "right")) }
        action(EditAction.CELL_ALIGNMENT, EditFeature.FORMAT) { pickAlignment() }
        action(EditAction.WRAP_TEXT, EditFeature.FORMAT) { toggleWrap() }
        action(EditAction.TEXT_ROTATION, EditFeature.FORMAT) { pickRotation() }
        action(EditAction.BORDERS, EditFeature.FORMAT) { pickBorder() }
        action(EditAction.NUMBER_FORMAT, EditFeature.FORMAT) { pickNumberFormat() }
        action(EditAction.MERGE_CELLS, EditFeature.MERGE_CELLS) { mergeOrSplit() }
        action(EditAction.INSERT_PICTURE, EditFeature.PICTURES) { pickImage() }
        action(EditAction.CLEAR_CELLS, EditFeature.TEXT) { deleteSelection() }
        action(EditAction.COLUMN_WIDTH, EditFeature.ROWS_COLUMNS) { askSize(str(R.string.docsdk_edit_column_width_of, A1FormulaShifter.address(0, col).dropLast(1)), session.columnWidth(sheet, col)) { structural { session.setColumnWidth(sheet, col, it) } } }
        action(EditAction.COLUMN_NARROWER, EditFeature.ROWS_COLUMNS) { structural { session.setColumnWidth(sheet, col, maxOf(0.5, session.columnWidth(sheet, col) * 0.8)) } }
        action(EditAction.COLUMN_WIDER, EditFeature.ROWS_COLUMNS) { structural { session.setColumnWidth(sheet, col, minOf(255.0, session.columnWidth(sheet, col) * 1.25)) } }
        action(EditAction.ROW_HEIGHT, EditFeature.ROWS_COLUMNS) { askSize(str(R.string.docsdk_edit_row_height_of, row + 1), session.rowHeight(sheet, row)) { structural { session.setRowHeight(sheet, row, it) } } }
        action(EditAction.ROW_SHORTER, EditFeature.ROWS_COLUMNS) { structural { session.setRowHeight(sheet, row, maxOf(1.0, session.rowHeight(sheet, row) * 0.8)) } }
        action(EditAction.ROW_TALLER, EditFeature.ROWS_COLUMNS) { structural { session.setRowHeight(sheet, row, minOf(409.0, session.rowHeight(sheet, row) * 1.25)) } }
        action(EditAction.ROW_ABOVE, EditFeature.ROWS_COLUMNS) { structural { session.insertRows(sheet, row, 1) } }
        action(EditAction.ROW_BELOW, EditFeature.ROWS_COLUMNS) { structural { session.insertRows(sheet, row + 1, 1) } }
        action(EditAction.DELETE_ROW, EditFeature.ROWS_COLUMNS) { structural { session.deleteRows(sheet, row, 1) } }
        action(EditAction.COLUMN_LEFT, EditFeature.ROWS_COLUMNS) { structural { session.insertColumns(sheet, col, 1) } }
        action(EditAction.COLUMN_RIGHT, EditFeature.ROWS_COLUMNS) { structural { session.insertColumns(sheet, col + 1, 1) } }
        action(EditAction.DELETE_COLUMN, EditFeature.ROWS_COLUMNS) { structural { session.deleteColumns(sheet, col, 1) } }
        action(EditAction.ADD_SHEET, EditFeature.SHEETS) { askNewSheet() }
    }

    override val editContext: EditContext?
        get() = if (sheet >= 0 && excel.getSpreadsheet()?.getSheetView()?.selectedShape == null) EditContext.CELLS else null

    override fun defaultActions(context: EditContext): List<EditAction> =
        if (context != EditContext.CELLS) emptyList()
        else listOf(EditAction.CUT, EditAction.COPY, EditAction.PASTE, EditAction.CLEAR_CELLS, EditAction.BOLD, EditAction.ITALIC,
            EditAction.UNDERLINE, EditAction.TEXT_COLOR, EditAction.FILL_COLOR, EditAction.ALIGN_LEFT, EditAction.ALIGN_CENTER,
            EditAction.ALIGN_RIGHT, EditAction.WRAP_TEXT, EditAction.MERGE_CELLS, EditAction.BORDERS, EditAction.NUMBER_FORMAT)

    override fun canRun(action: EditAction): Boolean = when (action) {
        EditAction.PASTE -> canPaste
        else -> true
    }

    override fun isActive(action: EditAction): Boolean {
        if (sheet < 0) return false
        val style = currentStyle() ?: return false
        if (action == EditAction.WRAP_TEXT) return style.isWrapText()
        val font = excel.getSpreadsheet()?.getWorkbook()?.getFont(style.getFontIndex().toInt()) ?: return false
        return when (action) {
            EditAction.BOLD -> font.isBold()
            EditAction.ITALIC -> font.isItalic()
            EditAction.UNDERLINE -> font.getUnderline() != 0
            EditAction.STRIKETHROUGH -> font.isStrikeline()
            else -> false
        }
    }

    init {
        keepAboveKeyboard(true)
        // a picture added here: touch it to select and drag it, drag a corner to resize it;
        // a range: long press a cell and drag, or drag the round handle of the selection
        reader.onDocumentGesture = gesture@{ type, event ->
            val ss = excel.getSpreadsheet() ?: return@gesture false
            val sv = ss.getSheetView() ?: return@gesture false
            when (type) {
                com.wxiwei.office.system.IMainFrame.ON_DOWN -> {
                    if (grabPicture(event)) return@gesture true
                    if (sv.getCurrentSheet()?.getSelectionRange() == null) return@gesture false
                    val h = sv.selectionHandle()
                    val p = sheetPoint(event.rawX, event.rawY)
                    if (Math.hypot((p.x - h.x).toDouble(), (p.y - h.y).toDouble()) > dp(24)) return@gesture false
                    reader.touchCapture = { e -> dragSelection(e) }
                    true
                }
                com.wxiwei.office.system.IMainFrame.ON_LONG_PRESS -> {
                    val p = sheetPoint(event.rawX, event.rawY)
                    val at = sv.cellAt(p.x, p.y) ?: return@gesture false
                    commitTyped()
                    sv.getCurrentSheet()!!.setActiveCellRowCol(at[0], at[1])
                    sv.getCurrentSheet()!!.setSelectionEnd(at[0], at[1])
                    ss.postInvalidate()
                    reader.touchCapture = { e -> dragSelection(e) }
                    true
                }
                else -> false
            }
        }
        // the sheet selects a cell on tap; follow it
        watcher = activity.lifecycleScope.launch {
            while (isActive) {
                refresh(false)
                delay(250)
            }
        }
    }

    private companion object {
        const val MAX_PICTURE_PX = 320
    }

    override fun close() {
        super.close()
        reader.onDocumentGesture = null
        reader.touchCapture = null
        excel.getSpreadsheet()?.getSheetView()?.selectedShape = null
        excel.getSpreadsheet()?.getSheetView()?.getCurrentSheet()?.let { it.setActiveCellRowCol(it.getActiveCellRow(), it.getActiveCellColumn()) }
        watcher.cancel()
    }

    // the cell last selected in the sheet, and the input of the edited cell as loaded
    private var seen = Triple(-1, -1, -1)
    private var loaded = ""
    // a reference put in the formula by a tap: the next tap replaces it
    private var tappedRef: IntRange? = null

    private fun refresh(force: Boolean) {
        val ss = excel.getSpreadsheet() ?: return
        val current = ss.getSheetView()?.getCurrentSheet() ?: return
        val s = session.sheetIndexOf(current)
        val r = current.getActiveCellRow()
        val c = current.getActiveCellColumn()
        val tapped = Triple(s, r, c)
        val range = current.getSelectionRange()
        val name = range?.let { A1FormulaShifter.address(it.getFirstRow(), it.getFirstColumn()) + ":" + A1FormulaShifter.address(it.getLastRow(), it.getLastColumn()) }
            ?: A1FormulaShifter.address(r, c)
        if (name != cellName.text.toString()) {
            cellName.text = name
            // a range grown by dragging: the app's own box shows it
            if (tapped == seen) stateChanged()
        }
        if (!force && tapped == seen) return
        seen = tapped
        val typed = value.text.toString()
        if (!force && sheet >= 0 && typed != loaded) {
            // a formula being typed: the tapped cell goes in as a reference, like in Excel
            if (typed.startsWith("=")) return insertReference(current, s, r, c).also { stateChanged() }
            applyValue()
        }
        sheet = s; row = r; col = c
        loaded = session.getInput(s, r, c)
        tappedRef = null
        value.setText(loaded)
        stateChanged()
    }

    private fun insertReference(current: com.wxiwei.office.ss.model.baseModel.Sheet, s: Int, r: Int, c: Int) {
        val name = current.getSheetName() ?: ""
        val ref = (if (s != sheet) "'" + name.replace("'", "''") + "'!" else "") + A1FormulaShifter.address(r, c)
        val text = value.text
        val previous = tappedRef?.takeIf { it.last < text.length && value.selectionStart == it.last + 1 }
        val start = previous?.first ?: value.selectionStart.coerceAtLeast(0)
        val end = previous?.let { it.last + 1 } ?: value.selectionEnd.coerceAtLeast(0)
        text.replace(start, end, ref)
        tappedRef = start until start + ref.length
        value.requestFocus()
        value.setSelection(start + ref.length)
    }

    /** Writes what was typed in the edited cell (Save, or a tap on another cell). */
    private fun commitTyped() {
        if (sheet >= 0 && value.text.toString() != loaded) applyValue()
    }

    private fun applyValue() {
        if (sheet < 0 || !has(EditFeature.TEXT)) return
        if (!session.setCellInput(sheet, row, col, value.text.toString())) {
            toast(session.lastError?.message ?: str(R.string.docsdk_edit_cell_edit_failed))
            return
        }
        loaded = value.text.toString()
        tappedRef = null
        session.warnings.firstOrNull()?.let { toast(it) }
        refresh(true)
    }

    private fun pickNumberFormat() {
        val formats = listOf(str(R.string.docsdk_edit_general) to "General", str(R.string.docsdk_edit_nf_integer) to "0", str(R.string.docsdk_edit_nf_decimal) to "0.00", str(R.string.docsdk_edit_nf_thousands) to "#,##0",
            str(R.string.docsdk_edit_nf_thousands_decimal) to "#,##0.00", str(R.string.docsdk_edit_nf_percent) to "0%", str(R.string.docsdk_edit_nf_percent_decimal) to "0.00%",
            str(R.string.docsdk_edit_nf_dong) to "#,##0 \"₫\"", str(R.string.docsdk_edit_nf_dollar) to "\$#,##0.00", str(R.string.docsdk_edit_nf_date) to "d/m/yyyy",
            str(R.string.docsdk_edit_nf_date_time) to "d/m/yyyy h:mm", str(R.string.docsdk_edit_nf_time) to "h:mm:ss", str(R.string.docsdk_edit_nf_text) to "@")
        pickOne(str(R.string.docsdk_edit_number_format), formats) { f -> format(CellFormat(numberFormat = f)) }
    }

    /** Borders of the cell: all around (thin or thick), at the bottom, or none; then their color. */
    private fun pickBorder() {
        if (sheet < 0) return
        val choices = listOf(str(R.string.docsdk_edit_border_all) to "all", str(R.string.docsdk_edit_border_thick) to "thick", str(R.string.docsdk_edit_border_bottom) to "bottom", str(R.string.docsdk_edit_border_none) to "none")
        pickOne(str(R.string.docsdk_edit_cell_borders), choices) { kind ->
            if (kind == "none") format(CellFormat(border = "none"))
            else pickColor(str(R.string.docsdk_edit_border_color)) { c -> format(CellFormat(border = kind, borderColor = c ?: "000000")) }
        }
    }

    /**
     * Excel's alignment tab: horizontal (general, left, center, right, fill, justify, center across
     * selection, distributed), vertical (top, center, bottom, justify, distributed), indent and wrap,
     * starting from the cell's current values; applied as one undoable step.
     */
    private fun pickAlignment() {
        if (sheet < 0) return
        val style = currentStyle()
        val nowH = when (style?.getHorizontalAlign()) {
            CellStyle.ALIGN_LEFT -> "left"; CellStyle.ALIGN_CENTER -> "center"; CellStyle.ALIGN_RIGHT -> "right"
            CellStyle.ALIGN_FILL -> "fill"; CellStyle.ALIGN_JUSTIFY -> "justify"; CellStyle.ALIGN_CENTER_SELECTION -> "centerContinuous"
            else -> "general"
        }
        val nowV = when (style?.getVerticalAlign()) {
            CellStyle.VERTICAL_TOP -> "top"; CellStyle.VERTICAL_CENTER -> "center"; CellStyle.VERTICAL_JUSTIFY -> "justify"
            else -> "bottom"
        }
        val nowIndent = style?.getIndent()?.toInt() ?: 0
        val nowWrap = style?.isWrapText() == true
        val horizontals = listOf(str(R.string.docsdk_edit_general) to "general", str(R.string.docsdk_edit_left) to "left", str(R.string.docsdk_edit_center) to "center", str(R.string.docsdk_edit_right) to "right",
            str(R.string.docsdk_edit_fill) to "fill", str(R.string.docsdk_edit_justify) to "justify", str(R.string.docsdk_edit_center_across) to "centerContinuous", str(R.string.docsdk_edit_distributed) to "distributed")
        val verticals = listOf(str(R.string.docsdk_edit_top) to "top", str(R.string.docsdk_edit_center) to "center", str(R.string.docsdk_edit_bottom) to "bottom", str(R.string.docsdk_edit_justify) to "justify", str(R.string.docsdk_edit_distributed) to "distributed")
        fun apply(a: CellAlignmentFormat) {
            if (horizontals.none { it.second == a.horizontal } || verticals.none { it.second == a.vertical } || a.indent !in 0..15) return toast(str(R.string.docsdk_edit_invalid_value))
            // only what changed, so the file keeps the rest of the cell's alignment as it was
            val f = CellFormat(
                horizontal = a.horizontal.takeIf { it != nowH },
                vertical = a.vertical.takeIf { it != nowV },
                indent = a.indent.takeIf { it != nowIndent },
                wrap = a.wrap.takeIf { it != nowWrap },
            )
            if (f.changesAlignment) format(f)
        }
        val (given, value) = takePreset()
        if (given) return (value as? CellAlignmentFormat)?.let { apply(it) } ?: toast(str(R.string.docsdk_edit_invalid_value))
        if (appAnswers(EditRequest.CellAlignment(running, str(R.string.docsdk_edit_alignment), CellAlignmentFormat(nowH, nowV, nowIndent, nowWrap)) { apply(it) })) return
        dialogs.show(str(R.string.docsdk_edit_alignment)) {
            caption(str(R.string.docsdk_edit_horizontal))
            val hGroup = choices(horizontals.map { it.first }, horizontals.indexOfFirst { it.second == nowH })
            caption(str(R.string.docsdk_edit_vertical))
            val vGroup = choices(verticals.map { it.first }, verticals.indexOfFirst { it.second == nowV })
            caption(str(R.string.docsdk_edit_indent_cells))
            val indent = stepper(nowIndent, 0..15, str(R.string.docsdk_edit_indent_decrease), str(R.string.docsdk_edit_indent_increase))
            val wrap = check(str(R.string.docsdk_edit_wrap_long_text), nowWrap)
            positive(str(R.string.docsdk_edit_apply)) {
                apply(CellAlignmentFormat(horizontals.getOrNull(hGroup.picked)?.second ?: nowH, verticals.getOrNull(vGroup.picked)?.second ?: nowV, indent(), wrap.isChecked))
            }
            negative()
        }
    }

    /** Turns the text of the cell: flat, 45° / 90° up or down, or letters stacked. */
    private fun pickRotation() {
        if (sheet < 0) return
        val choices = listOf(str(R.string.docsdk_edit_rotate_none) to 0, str(R.string.docsdk_edit_rotate_up) to 45, str(R.string.docsdk_edit_rotate_vertical_up) to 90, str(R.string.docsdk_edit_rotate_down) to 135, str(R.string.docsdk_edit_rotate_vertical_down) to 180, str(R.string.docsdk_edit_rotate_stacked) to 255)
        pickOne(str(R.string.docsdk_edit_text_rotation), choices) { rotation ->
            format(CellFormat(rotation = rotation))
            fitRowToTurnedText(rotation)
        }
    }

    /** Like Excel: a row too low for the turned text of the cell grows to hold it. */
    private fun fitRowToTurnedText(rotation: Int) {
        if (rotation == 0) return
        val text = session.getInput(sheet, row, col).takeIf { it.isNotEmpty() } ?: return
        val book = excel.getSpreadsheet()?.getWorkbook() ?: return
        val sizePt = (currentStyle()?.let { book.getFont(it.getFontIndex().toInt()) }?.getFontSize() ?: 11.0).toFloat()
        // points: a paint whose text size is the font size measures in points
        val paint = android.graphics.Paint().apply { textSize = sizePt }
        val width = paint.measureText(text)
        val line = paint.fontMetrics.let { it.descent - it.ascent }
        val needed = if (rotation == 255) line * text.length else {
            val rad = Math.toRadians((if (rotation <= 90) rotation else rotation - 90).toDouble())
            (Math.abs(Math.sin(rad)) * width + Math.abs(Math.cos(rad)) * line).toFloat()
        } + 4f
        if (needed > session.rowHeight(sheet, row)) structural { session.setRowHeight(sheet, row, minOf(409.0, needed.toDouble())) }
    }

    /** Asks the new sheet's name, adds it after the last one and shows it. */
    private fun askNewSheet() {
        askText(str(R.string.docsdk_edit_new_sheet), str(R.string.docsdk_edit_sheet_name), session.nextSheetName()) { name ->
            val index = session.addSheet(name.trim())
            if (index < 0) return@askText toast(session.lastError?.message ?: str(R.string.docsdk_edit_sheet_add_failed))
            excel.refreshSheetBar(index)
            excel.showSheet(index)
            refresh(true)
        }
    }

    /** The sheet tabs after an undo/redo added or took out a sheet; a sheet gone shows the last one. */
    private fun syncSheets() {
        val ss = excel.getSpreadsheet() ?: return
        val book = ss.getWorkbook() ?: return
        val shown = ss.getSheetView()?.getCurrentSheet()?.let { book.getSheetIndex(it) } ?: -1
        val focus = if (shown in 0 until book.getSheetCount()) shown else book.getSheetCount() - 1
        excel.refreshSheetBar(focus)
        if (focus != shown) excel.showSheet(focus)
    }

    private fun askSize(title: String, current: Double, onSet: (Double) -> Unit) {
        if (sheet < 0) return
        askNumber(title, current, onSet)
    }

    private fun structural(action: () -> Boolean) {
        if (sheet < 0) return
        if (!action()) toast(session.lastError?.message ?: str(R.string.docsdk_edit_failed))
        else {
            session.warnings.firstOrNull()?.let { toast(it) }
            refresh(true)
        }
    }

    /** The selected range (or null for one cell) of the shown sheet. */
    private fun range(): com.wxiwei.office.ss.model.CellRangeAddress? =
        excel.getSpreadsheet()?.getSheetView()?.getCurrentSheet()?.getSelectionRange()

    /** Formats the selected range, or the cell. */
    private fun format(f: CellFormat) {
        if (sheet < 0) return
        val r = range()
        val ok = if (r != null) session.setRangeFormat(sheet, r.getFirstRow(), r.getFirstColumn(), r.getLastRow(), r.getLastColumn(), f)
            else session.setCellFormat(sheet, row, col, f)
        if (!ok) toast(session.lastError?.message ?: str(R.string.docsdk_edit_format_failed))
    }

    /** Where a screen point falls in the sheet view. */
    private fun sheetPoint(rawX: Float, rawY: Float): android.graphics.PointF {
        val loc = IntArray(2)
        excel.getSpreadsheet()!!.getLocationOnScreen(loc)
        return android.graphics.PointF(rawX - loc[0], rawY - loc[1])
    }

    /** The finger moves the far corner of the range; near an edge the sheet scrolls along. */
    private fun dragSelection(e: android.view.MotionEvent) {
        val ss = excel.getSpreadsheet() ?: return
        val sv = ss.getSheetView() ?: return
        // the finger is lifted: the cells are chosen (the context bar offers what to do with them)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) return stateChanged()
        val p = sheetPoint(e.rawX, e.rawY)
        val edge = dp(32).toFloat()
        val dx = when { p.x > ss.width - edge -> dp(12).toFloat(); p.x < sv.getRowHeaderWidth() + edge / 2 -> -dp(12).toFloat(); else -> 0f }
        val dy = when { p.y > ss.height - edge -> dp(12).toFloat(); p.y < sv.getColumnHeaderHeight() + edge / 2 -> -dp(12).toFloat(); else -> 0f }
        if (dx != 0f || dy != 0f) sv.scrollBy(dx / sv.getZoom(), dy / sv.getZoom(), true)
        val at = sv.cellAt(p.x.coerceAtLeast(sv.getRowHeaderWidth() + 1f), p.y.coerceAtLeast(sv.getColumnHeaderHeight() + 1f)) ?: return
        sv.getCurrentSheet()!!.setSelectionEnd(at[0], at[1])
        ss.abortDrawing()
        ss.postInvalidate()
    }

    /** The name box: type a cell ("B3") or a range ("A1:C10") to select it. */
    private fun askRange() {
        askText(str(R.string.docsdk_edit_go_to_cells), str(R.string.docsdk_edit_range_hint), cellName.text.toString()) { selectRange(it) }
    }

    private fun selectRange(text: String) {
        val m = Regex("(?i)^\\s*\\$?([A-Z]{1,3})\\$?(\\d{1,7})(?:\\s*:\\s*\\$?([A-Z]{1,3})\\$?(\\d{1,7}))?\\s*$").find(text)
            ?: return toast(str(R.string.docsdk_edit_invalid_range))
        fun col(s: String) = s.uppercase().fold(0) { n, ch -> n * 26 + (ch - 'A' + 1) } - 1
        val (c1, r1) = col(m.groupValues[1]) to m.groupValues[2].toInt() - 1
        val (c2, r2) = if (m.groupValues[3].isEmpty()) c1 to r1 else col(m.groupValues[3]) to m.groupValues[4].toInt() - 1
        if (minOf(r1, r2) < 0 || maxOf(r1, r2) >= 1048576 || maxOf(c1, c2) >= 16384) return toast(str(R.string.docsdk_edit_invalid_range))
        val ss = excel.getSpreadsheet() ?: return
        val current = ss.getSheetView()?.getCurrentSheet() ?: return
        commitTyped()
        current.setActiveCellRowCol(r1, c1)
        if (r2 != r1 || c2 != c1) current.setSelectionEnd(r2, c2)
        ss.getSheetView()?.goToCell(minOf(r1, r2), minOf(c1, c2))
        ss.postInvalidate()
        refresh(true)
    }

    /** Picks a picture from the device and puts it at the selected cell. */
    private fun pickImage() {
        if (sheet < 0) return toast(str(R.string.docsdk_edit_select_cell_first))
        pickPicture { uri -> pictureFile(uri, "sheet-image-")?.let { addImageFile(it) } }
    }

    /** The picture at the selected cell: its own size, at most [MAX_PICTURE_PX] wide or high at 100%. */
    fun addImageFile(image: File): Boolean {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { toast(str(R.string.docsdk_edit_picture_unreadable)); return false }
        val scale = minOf(1f, MAX_PICTURE_PX.toFloat() / maxOf(bounds.outWidth, bounds.outHeight))
        val w = maxOf(1, Math.round(bounds.outWidth * scale)); val h = maxOf(1, Math.round(bounds.outHeight * scale))
        commitTyped()
        if (!session.addPicture(sheet, row, col, image, w, h)) { toast(session.lastError?.message ?: str(R.string.docsdk_edit_picture_add_failed)); return false }
        // the new picture is selected: it can be dragged at once
        excel.getSpreadsheet()?.getSheetView()?.let { sv ->
            sv.selectedShape = sv.getCurrentSheet()?.getShapes()?.lastOrNull()
            excel.getSpreadsheet()?.postInvalidate()
        }
        return true
    }

    /**
     * A touch on a corner of the selected picture resizes it (keeping its proportions), on a
     * picture added in this session selects and moves it; elsewhere the picture is let go.
     * The picture follows the finger and is placed, as one undoable step, when it is lifted.
     */
    private fun grabPicture(event: android.view.MotionEvent): Boolean {
        if (!has(EditFeature.PICTURES)) return false
        val ss = excel.getSpreadsheet() ?: return false
        val sv = ss.getSheetView() ?: return false
        val p = sheetPoint(event.rawX, event.rawY)
        val selected = sv.selectedShape?.takeIf { session.isMovablePicture(it) && sv.getCurrentSheet()?.getShapes()?.contains(it) == true }
        val box = selected?.let { sv.shapeRect(it) }
        val reach = dp(24).toFloat()
        // corners: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right
        val corner = box?.let { b ->
            listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)
                .indexOfFirst { (x, y) -> Math.hypot((p.x - x).toDouble(), (p.y - y).toDouble()) <= reach }
        } ?: -1
        val shape = if (corner >= 0) selected!! else sv.shapeAt(p.x, p.y) { session.isMovablePicture(it) }
        if (shape == null) {
            if (sv.selectedShape != null) { sv.selectedShape = null; ss.postInvalidate() }
            return false
        }
        commitTyped()
        sv.selectedShape = shape
        ss.abortDrawing()
        ss.postInvalidate()
        val start = shape.bounds!!.let { com.wxiwei.office.java.awt.Rectangle(it.x, it.y, it.width, it.height) }
        val downX = event.rawX
        val downY = event.rawY
        reader.touchCapture = { e ->
            val zoom = sv.getZoom()
            val dx = (e.rawX - downX) / zoom
            val dy = (e.rawY - downY) / zoom
            val to = if (corner < 0) com.wxiwei.office.java.awt.Rectangle(
                maxOf(0, Math.round(start.x + dx)), maxOf(0, Math.round(start.y + dy)), start.width, start.height)
            else {
                val sx = if (corner == 1 || corner == 3) 1 else -1
                val sy = if (corner >= 2) 1 else -1
                val min = 8f / maxOf(1, minOf(start.width, start.height))
                val scale = maxOf(min, (start.width + sx * dx) / start.width, (start.height + sy * dy) / start.height)
                val w = maxOf(1, Math.round(start.width * scale)); val h = maxOf(1, Math.round(start.height * scale))
                com.wxiwei.office.java.awt.Rectangle(if (sx > 0) start.x else start.x + start.width - w,
                    if (sy > 0) start.y else start.y + start.height - h, w, h)
            }
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                shape.bounds = start
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !session.setPictureBounds(shape, to))
                    toast(session.lastError?.message ?: str(R.string.docsdk_edit_picture_move_failed))
                ss.postInvalidate()
            } else {
                shape.bounds = to
                ss.abortDrawing()
                ss.postInvalidate()
            }
        }
        return true
    }

    /** "Xóa ô": the selected picture, or else the cell. */
    private fun deleteSelection() {
        val sv = excel.getSpreadsheet()?.getSheetView()
        val picture = sv?.selectedShape?.takeIf { session.isMovablePicture(it) }
        if (picture != null) {
            if (session.removePicture(picture)) sv.selectedShape = null
            else toast(session.lastError?.message ?: str(R.string.docsdk_edit_picture_delete_failed))
            return
        }
        clearCells()
    }

    // ---- copy and paste ----

    private val clipboard get() = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    /** What was last copied from this workbook: pasted with its formulas and look while the clipboard still holds its text. */
    private var copied: SheetEditSession.CellBlock? = null

    /** The selected range, or the cell: first row, first column, last row, last column. */
    private fun selectedCells(): IntArray {
        val r = range() ?: return intArrayOf(row, col, row, col)
        return intArrayOf(r.getFirstRow(), r.getFirstColumn(), r.getLastRow(), r.getLastColumn())
    }

    /** Copies the selected cells; false when nothing could be copied. */
    private fun copyCells(): Boolean {
        if (sheet < 0) { toast(str(R.string.docsdk_edit_select_cell_first)); return false }
        commitTyped()
        val (r1, c1, r2, c2) = selectedCells()
        val block = session.copyRange(sheet, r1, c1, r2, c2) ?: run { toast(session.lastError?.message ?: str(R.string.docsdk_edit_failed)); return false }
        copied = block
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cells", block.text))
        toast(str(R.string.docsdk_edit_copied))
        return true
    }

    /** Empties the selected cells. */
    private fun clearCells() {
        if (sheet < 0) return
        val (r1, c1, r2, c2) = selectedCells()
        if (!session.clearRange(sheet, r1, c1, r2, c2)) toast(session.lastError?.message ?: str(R.string.docsdk_edit_failed))
        refresh(true)
    }

    /**
     * Pastes at the selected cell: cells copied here keep their formulas (moved like Excel) and look;
     * text from another app fills a cell per tab-separated value, a row per line. The pasted cells
     * are then selected.
     */
    private fun pasteCells() {
        if (sheet < 0) return toast(str(R.string.docsdk_edit_select_cell_first))
        commitTyped()
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return toast(str(R.string.docsdk_edit_clipboard_empty))
        val (r1, c1) = selectedCells()
        val block = copied?.takeIf { it.text == text }
        val ok = if (block != null) session.pasteBlock(sheet, r1, c1, block) else session.pasteText(sheet, r1, c1, text)
        if (!ok) return toast(session.lastError?.message ?: str(R.string.docsdk_edit_paste_failed))
        val rows = block?.rows ?: text.trimEnd('\n', '\r').lines().size
        val cols = block?.cols ?: text.trimEnd('\n', '\r').lines().maxOf { it.split('\t').size }
        val current = excel.getSpreadsheet()?.getSheetView()?.getCurrentSheet()
        current?.setActiveCellRowCol(r1, c1)
        if (rows > 1 || cols > 1) current?.setSelectionEnd(r1 + rows - 1, c1 + cols - 1)
        session.warnings.firstOrNull()?.let { toast(it) }
        refresh(true)
    }

    /** A picture taken off by an undo/redo is no longer selected. */
    private fun dropStalePicture() {
        val sv = excel.getSpreadsheet()?.getSheetView() ?: return
        val shape = sv.selectedShape ?: return
        if (sv.getCurrentSheet()?.getShapes()?.contains(shape) != true) sv.selectedShape = null
    }

    /** "Gộp ô": merges the selected range (asks when values would be dropped); on a merged cell, splits it. */
    private fun mergeOrSplit() {
        if (sheet < 0) return
        val r = range()
        if (r == null) {
            if (session.mergeAt(sheet, row, col) == null) return toast(str(R.string.docsdk_edit_merge_hint))
            return structural { session.unmergeCells(sheet, row, col) }
        }
        val merge = { structural { session.mergeCells(sheet, r.getFirstRow(), r.getFirstColumn(), r.getLastRow(), r.getLastColumn()) } }
        if (!session.mergeDropsValues(sheet, r.getFirstRow(), r.getFirstColumn(), r.getLastRow(), r.getLastColumn())) return merge()
        confirm(str(R.string.docsdk_edit_merge_cells), str(R.string.docsdk_edit_merge_warning), str(R.string.docsdk_edit_merge)) { merge() }
    }

    private fun currentStyle(): CellStyle? =
        excel.getSpreadsheet()?.getWorkbook()?.getSheet(sheet)?.getRow(row)?.getCell(col)?.getCellStyle()

    private fun toggleFont(change: (com.wxiwei.office.simpletext.font.Font) -> CellFormat) {
        val book = excel.getSpreadsheet()?.getWorkbook() ?: return
        val font = currentStyle()?.let { book.getFont(it.getFontIndex().toInt()) } ?: com.wxiwei.office.simpletext.font.Font()
        format(change(font))
    }

    private fun toggleWrap() = format(CellFormat(wrap = currentStyle()?.isWrapText() != true))

    override fun hasChanges(): Boolean { commitTyped(); return session.hasChanges() }

    override fun writeTo(target: File) = session.save(target)

    // the file now holds every change: start a new session on it
    override fun onSaved() {
        session = SheetEditSession(reader.control!!, file)
    }
}
