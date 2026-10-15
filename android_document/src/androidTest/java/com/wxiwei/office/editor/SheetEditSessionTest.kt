/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wxiwei.office.editor.OpenDocument.onMain
import com.wxiwei.office.editor.xlsx.SheetEditSession
import com.wxiwei.office.ss.control.ExcelView
import com.wxiwei.office.ss.model.baseModel.Workbook
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * sample.xlsx: "Tổng quan"!D6 = SUM('Dữ liệu chi tiết'!G2:G74) = 279. Editing G2 must update D6
 * at once, and the saved file must reopen with both values.
 */
@RunWith(AndroidJUnit4::class)
class SheetEditSessionTest {
    private fun book(reader: com.wxiwei.office.reader.OfficeReader): Workbook =
        (reader.control!!.getView() as ExcelView).getSpreadsheet()!!.getWorkbook()!!

    private fun num(book: Workbook, sheet: Int, row: Int, col: Int): Double {
        val s = book.getSheet(sheet) ?: error("no sheet $sheet")
        val r = s.getRow(row) ?: error("sheet $sheet '${s.getSheetName()}' state=${s.getState()} rows=${s.getFirstRowNum()}..${s.getLastRowNum()}: no row $row")
        val c = r.getCell(col) ?: error("no cell $row,$col")
        return c.getNumberValue()
    }

    /** Sheets load when first shown: show each once so its rows exist. */
    private suspend fun loadAll(reader: com.wxiwei.office.reader.OfficeReader) {
        val excel = reader.control!!.getView() as ExcelView
        val n = onMain { excel.getSpreadsheet()!!.getSheetCount() }
        for (i in 0 until n) {
            onMain { excel.showSheet(i) }
            delay(1500)
        }
        onMain { excel.showSheet(0) }
    }

    @Test
    fun editRecalculatesAndSaves() {
        val source = OpenDocument.copySample("sample.xlsx", "edit_source.xlsx")
        val saved = OpenDocument.output("edit_saved.xlsx")
        var g2Before = 0.0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val book = book(reader)
            g2Before = onMain { num(book, 1, 1, 6) }
            assertEquals(279.0, onMain { num(book, 0, 5, 3) }, 0.0)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            val ok = onMain { session.setCellInput(1, 1, 6, (g2Before + 10).toLong().toString()) }
            assertTrue(session.lastError?.toString(), ok)
            assertEquals("SUM follows the edit", 289.0, onMain { num(book, 0, 5, 3) }, 0.0)
            // undo / redo
            assertTrue(onMain { session.undo() })
            assertEquals(279.0, onMain { num(book, 0, 5, 3) }, 0.0)
            assertTrue(onMain { session.redo() })
            assertEquals(289.0, onMain { num(book, 0, 5, 3) }, 0.0)
            // a formula typed into a cell
            assertTrue(onMain { session.setCellInput(3, 20, 1, "=SUM(1,2,3)") })
            assertEquals(6.0, onMain { num(book, 3, 20, 1) }, 0.0)
            // format: sheet 4 ("Ghi chú dữ liệu") C6 shares its style with the rest of the table body
            val otherStyleBefore = onMain { book.getSheet(3)!!.getRow(6)!!.getCell(2)!!.getCellStyleIndex() }
            val fmt = com.wxiwei.office.editor.xlsx.CellFormat(bold = true, fillColor = "FFFF00", horizontal = "center", numberFormat = "0.00")
            assertTrue(onMain { session.setCellFormat(3, 20, 1, fmt) })
            onMain {
                val st = book.getSheet(3)!!.getRow(20)!!.getCell(1)!!.getCellStyle()!!
                assertTrue("bold", book.getFont(st.getFontIndex().toInt())!!.isBold())
                assertEquals(0xFFFFFF00.toInt(), st.getFgColor())
                assertEquals(com.wxiwei.office.ss.model.style.CellStyle.ALIGN_CENTER, st.getHorizontalAlign())
                assertEquals("0.00", st.getFormatCode())
            }
            assertTrue(onMain { session.setRangeFormat(3, 5, 1, 6, 2, com.wxiwei.office.editor.xlsx.CellFormat(italic = true)) })
            assertTrue(onMain { session.undo() })
            assertEquals("undo restores the style", otherStyleBefore, onMain { book.getSheet(3)!!.getRow(6)!!.getCell(2)!!.getCellStyleIndex() })
            val result = onMain { session.save(saved) }
            assertTrue(result.toString(), result is EditResult.Ok)
        }
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            val book = book(reader)
            assertEquals(g2Before + 10, onMain { num(book, 1, 1, 6) }, 0.0)
            assertEquals(289.0, onMain { num(book, 0, 5, 3) }, 0.0)
            assertEquals(6.0, onMain { num(book, 3, 20, 1) }, 0.0)
            assertEquals("SUM(1,2,3)", onMain { book.getSheet(3)!!.getRow(20)!!.getCell(1)!!.formula })
            onMain {
                val st = book.getSheet(3)!!.getRow(20)!!.getCell(1)!!.getCellStyle()!!
                assertTrue("bold saved", book.getFont(st.getFontIndex().toInt())!!.isBold())
                assertEquals(0xFFFFFF00.toInt(), st.getFgColor())
                assertEquals(com.wxiwei.office.ss.model.style.CellStyle.ALIGN_CENTER, st.getHorizontalAlign())
                assertEquals("0.00", st.getFormatCode())
                val other = book.getSheet(3)!!.getRow(6)!!.getCell(2)!!.getCellStyle()!!
                assertTrue("untouched cell keeps its font", !book.getFont(other.getFontIndex().toInt())!!.isItalic())
            }
        }
    }

    private fun formula(book: Workbook, sheet: Int, row: Int, col: Int): String? = book.getSheet(sheet)!!.getRow(row)?.getCell(col)?.formula

    @Test
    fun insertAndDeleteRowsAndColumns() {
        val source = OpenDocument.copySample("sample.xlsx", "rows_source.xlsx")
        val saved = OpenDocument.output("rows_saved.xlsx")
        var g2 = 0.0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val book = book(reader)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            g2 = onMain { num(book, 1, 1, 6) }
            // two rows before row 4 of "Dữ liệu chi tiết"
            assertTrue(session.lastError?.toString(), onMain { session.insertRows(1, 3, 2) })
            assertEquals("COUNTA('Dữ liệu chi tiết'!B2:B76)", onMain { formula(book, 0, 5, 1) })
            assertEquals("SUM('Dữ liệu chi tiết'!G2:G76)", onMain { formula(book, 0, 5, 3) })
            assertEquals(279.0, onMain { num(book, 0, 5, 3) }, 0.0)
            assertEquals("the old row 4 moved to row 6", "F6-E6+1", onMain { formula(book, 1, 5, 6) })
            assertTrue(onMain { session.undo() })
            assertEquals("SUM('Dữ liệu chi tiết'!G2:G74)", onMain { formula(book, 0, 5, 3) })
            assertEquals("F4-E4+1", onMain { formula(book, 1, 3, 6) })
            // delete row 2: the SUM loses G2
            assertTrue(onMain { session.deleteRows(1, 1, 1) })
            assertEquals("SUM('Dữ liệu chi tiết'!G2:G73)", onMain { formula(book, 0, 5, 3) })
            assertEquals(279.0 - g2, onMain { num(book, 0, 5, 3) }, 0.0)
            assertEquals("F2-E2+1", onMain { formula(book, 1, 1, 6) })
            // a column before B on "Dữ liệu chi tiết"
            assertTrue(onMain { session.insertColumns(1, 1, 1) })
            assertEquals("SUM('Dữ liệu chi tiết'!H2:H73)", onMain { formula(book, 0, 5, 3) })
            assertEquals("G2-F2+1", onMain { formula(book, 1, 1, 7) })
            val result = onMain { session.save(saved) }
            assertTrue(result.toString(), result is EditResult.Ok)
        }
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            val book = book(reader)
            assertEquals("SUM('Dữ liệu chi tiết'!H2:H73)", onMain { formula(book, 0, 5, 3) })
            assertEquals(279.0 - g2, onMain { num(book, 0, 5, 3) }, 0.0)
            assertEquals("G2-F2+1", onMain { formula(book, 1, 1, 7) })
            val table = onMain { book.getSheet(1)!!.getTables()!!.first().getTableReference()!! }
            assertEquals("table A1:R74 -> A1:S73", listOf(0, 0, 72, 18), listOf(table.getFirstRow(), table.getFirstColumn(), table.getLastRow(), table.getLastColumn()))
        }
    }

    @Test
    fun rowsAndColumnsMoveCharts() {
        val source = OpenDocument.copySample("sample.xlsx", "xlsx_shapes.xlsx")
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val b = book(reader)
            val index = (0 until b.getSheetCount()).first { (b.getSheet(it)?.getShapeCount() ?: 0) > 0 }
            val sheet = b.getSheet(index)!!
            val session = onMain { SheetEditSession(reader.control!!, source) }
            val shape = sheet.getShapes().maxByOrNull { it.bounds!!.y }!!
            val before = onMain { com.wxiwei.office.java.awt.Rectangle(shape.bounds!!) }
            val rowH = sheet.getDefaultRowHeight()
            // two rows above the chart push it down by two row heights
            assertTrue(onMain { session.insertRows(index, 0, 2) })
            val down = onMain { shape.bounds!! }
            assertEquals((before.y + 2 * rowH).toDouble(), down.y.toDouble(), 1.0)
            assertEquals(before.height, down.height)
            assertTrue(onMain { session.undo() })
            assertEquals(before, onMain { shape.bounds!! })
            // a column inserted left of it moves it right
            assertTrue(onMain { session.insertColumns(index, 0, 1) })
            assertTrue("moved right", onMain { shape.bounds!!.x } > before.x)
            assertTrue(onMain { session.undo() })
            // deleting a row above moves it up
            assertTrue(onMain { session.deleteRows(index, 0, 1) })
            assertTrue("moved up", onMain { shape.bounds!!.y } < before.y)
        }
    }

    /** Formula or shown text of every cell in the first rows/columns of [sheet]. */
    private fun grid(book: Workbook, sheet: Int): String {
        val s = book.getSheet(sheet)!!
        val sb = StringBuilder()
        for (r in 0 until 60) {
            val row = s.getRow(r)
            for (c in 0 until 14) {
                val cell = row?.getCell(c)
                val v = cell?.let { it.formula?.let { f -> "=$f" } ?: com.wxiwei.office.ss.util.ModelUtil.instance().getFormatContents(book, it) } ?: ""
                val bold = cell?.getCellStyle()?.let { st -> book.getFont(st.getFontIndex().toInt())?.isBold() } == true
                sb.append(v).append(if (bold) "*" else "").append('|')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Random values, formulas, rows/columns in and out, undo/redo; the saved file reads back the same. */
    @Test
    fun randomEditsSaveAsShown() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        for (seed in args.getString("fuzzSeed")?.let { listOf(it.toLong()) } ?: listOf(1L, 2L, 3L)) {
            val source = OpenDocument.copySample("sample.xlsx", "xlsx_fuzz_$seed.xlsx")
            val saved = OpenDocument.output("xlsx_fuzz_saved_$seed.xlsx")
            var shown = ""
            val sheet = 1
            val log = StringBuilder()
            OpenDocument.open(source) { reader ->
                loadAll(reader)
                val session = onMain { SheetEditSession(reader.control!!, source) }
                val rnd = java.util.Random(seed)
                repeat(args.getString("fuzzOps")?.toInt() ?: 60) { step ->
                    val r = rnd.nextInt(40); val c = rnd.nextInt(10)
                    val kind = rnd.nextInt(13)
                    val ok = onMain {
                        when (kind) {
                            0, 1, 2 -> session.setCellInput(sheet, r, c, (rnd.nextInt(1000) / 10.0).toString())
                            3 -> session.setCellInput(sheet, r, c, listOf("xin chào", "Đà Nẵng", "abc").let { it[rnd.nextInt(it.size)] })
                            4 -> session.setCellInput(sheet, r, c, "=SUM(A1:B${1 + rnd.nextInt(20)})+1")
                            5 -> session.insertRows(sheet, r, 1 + rnd.nextInt(2))
                            6 -> session.deleteRows(sheet, r, 1)
                            7 -> session.insertColumns(sheet, c, 1)
                            8 -> session.undo()
                            9 -> session.redo()
                            10 -> session.setCellFormat(sheet, r, c, com.wxiwei.office.editor.xlsx.CellFormat(bold = rnd.nextBoolean()))
                            11 -> session.deleteColumns(sheet, c, 1)
                            else -> session.setRangeFormat(sheet, r, c, r + 2, c + 1, com.wxiwei.office.editor.xlsx.CellFormat(bold = true))
                        }
                    }
                    log.append("$step:$kind@$r,$c:$ok ")
                }
                delay(500)
                shown = onMain { grid(book(reader), sheet) }
                val result = onMain { session.save(saved) }
                assertTrue(result.toString(), result is EditResult.Ok)
            }
            OpenDocument.open(saved) { reader ->
                loadAll(reader)
                val reread = onMain { grid(book(reader), sheet) }
                if (reread != shown) {
                    val a = shown.lines(); val b = reread.lines()
                    val i = a.indices.first { it >= b.size || a[it] != b[it] }
                    throw AssertionError("seed $seed row $i: shown='${a[i]}' saved='${b.getOrNull(i)}' ops=$log")
                }
            }
        }
    }

    /** A sheet added after the last one: shown at once, typed into (text and a formula), undone and redone, saved, read again. */
    @Test
    fun addSheet() {
        val source = OpenDocument.copySample("sample.xlsx", "add_sheet_source.xlsx")
        val saved = OpenDocument.output("add_sheet_saved.xlsx")
        var count = 0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val book = book(reader)
            count = onMain { book.getSheetCount() }
            val session = onMain { SheetEditSession(reader.control!!, source) }
            assertEquals("a name in use is refused", -1, onMain { session.addSheet(book.getSheet(0)!!.getSheetName()!!) })
            assertTrue(onMain { session.sheetNameProblem("a/b") } != null)
            val index = onMain { session.addSheet("Mới") }
            assertEquals(count, index)
            assertEquals(count + 1, onMain { book.getSheetCount() })
            val excel = reader.control!!.getView() as ExcelView
            onMain { excel.refreshSheetBar(index); excel.showSheet(index) }
            delay(500)
            assertTrue(session.lastError?.toString(), onMain { session.setCellInput(index, 0, 0, "xin chào") })
            assertTrue(session.lastError?.toString(), onMain { session.setCellInput(index, 1, 0, "=2+3") })
            assertEquals(5.0, onMain { num(book, index, 1, 0) }, 0.0)
            // undo the formula, the text and the sheet; then redo all
            repeat(3) { assertTrue(onMain { session.undo() }) }
            assertEquals(count, onMain { book.getSheetCount() })
            repeat(3) { assertTrue(onMain { session.redo() }) }
            assertEquals(count + 1, onMain { book.getSheetCount() })
            assertEquals(5.0, onMain { num(book, index, 1, 0) }, 0.0)
            assertTrue(onMain { session.save(saved) } is EditResult.Ok)
        }
        val workbook = java.util.zip.ZipFile(saved).use { z -> z.getInputStream(z.getEntry("xl/workbook.xml")).readBytes().toString(Charsets.UTF_8) }
        assertTrue("listed in the workbook: $workbook", workbook.contains("name=\"Mới\""))
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            val book = book(reader)
            assertEquals(count + 1, onMain { book.getSheetCount() })
            assertEquals("Mới", onMain { book.getSheet(count)!!.getSheetName() })
            assertEquals(5.0, onMain { num(book, count, 1, 0) }, 0.0)
            assertEquals("xin chào", onMain { book.getSheet(count)!!.getRow(0)!!.getCell(0)!!.let { c -> SheetEditSession(reader.control!!, saved).getInput(count, 0, 0) } })
        }
    }

    /** X2: justify and vertical center on a cell: in the model at once, saved, read back. */
    @Test
    fun justifyAndVerticalCenter() {
        val source = OpenDocument.copySample("sample.xlsx", "align_source.xlsx")
        val saved = OpenDocument.output("align_saved.xlsx")
        fun style(reader: com.wxiwei.office.reader.OfficeReader) = onMain { book(reader).getSheet(0)!!.getRow(5)!!.getCell(3)!!.getCellStyle()!! }
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            assertTrue(session.lastError?.toString(), onMain { session.setCellFormat(0, 5, 3, com.wxiwei.office.editor.xlsx.CellFormat(horizontal = "justify", vertical = "center")) })
            val st = style(reader)
            assertEquals(com.wxiwei.office.ss.model.style.CellStyle.ALIGN_JUSTIFY, onMain { st.getHorizontalAlign() })
            assertEquals(com.wxiwei.office.ss.model.style.CellStyle.VERTICAL_CENTER, onMain { st.getVerticalAlign() })
            assertTrue(onMain { session.save(saved) } is EditResult.Ok)
        }
        val styles = java.util.zip.ZipFile(saved).use { z -> z.getInputStream(z.getEntry("xl/styles.xml")).readBytes().toString(Charsets.UTF_8) }
        assertTrue("alignment saved", Regex("<alignment[^>]*horizontal=\"justify\"[^>]*vertical=\"center\"|<alignment[^>]*vertical=\"center\"[^>]*horizontal=\"justify\"").containsMatchIn(styles))
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            val st = style(reader)
            assertEquals(com.wxiwei.office.ss.model.style.CellStyle.ALIGN_JUSTIFY, onMain { st.getHorizontalAlign() })
            assertEquals(com.wxiwei.office.ss.model.style.CellStyle.VERTICAL_CENTER, onMain { st.getVerticalAlign() })
        }
    }

    /** Merge A201:C203 (the other values go), undo/redo, a range cutting it refused, saved, read back, split again. */
    @Test
    fun mergeAndUnmerge() {
        val source = OpenDocument.copySample("sample.xlsx", "merge_source.xlsx")
        val saved = OpenDocument.output("merge_saved.xlsx")
        val split = OpenDocument.output("merge_split.xlsx")
        fun mergeXml(f: java.io.File) = java.util.zip.ZipFile(f).use { z -> z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8) }
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            val sheet = onMain { book(reader).getSheet(0)!! }
            assertTrue(onMain { session.setCellInput(0, 200, 0, "top") && session.setCellInput(0, 201, 1, "x") })
            assertTrue("drops a value", onMain { session.mergeDropsValues(0, 200, 0, 202, 2) })
            assertTrue(session.lastError?.toString(), onMain { session.mergeCells(0, 200, 0, 202, 2) })
            assertEquals("", onMain { session.getInput(0, 201, 1) })
            assertEquals("top", onMain { session.getInput(0, 200, 0) })
            assertTrue("merged", onMain { sheet.mergeIndexAt(202, 2) >= 0 && sheet.getRow(201)!!.getCell(1)!!.getRangeAddressIndex() >= 0 })
            assertTrue(onMain { session.undo() })
            assertEquals("value back", "x", onMain { session.getInput(0, 201, 1) })
            assertTrue("split by undo", onMain { sheet.mergeIndexAt(202, 2) < 0 && sheet.getRow(201)!!.getCell(1)!!.getRangeAddressIndex() < 0 })
            assertTrue(onMain { session.redo() })
            assertTrue("merged again", onMain { sheet.mergeIndexAt(200, 0) >= 0 })
            assertFalse("a range cutting the merged cell", onMain { session.mergeCells(0, 201, 1, 204, 3) })
            // selecting across it grows over the whole merged cell
            onMain { sheet.setActiveCellRowCol(199, 1); sheet.setSelectionEnd(201, 1) }
            assertEquals("A200:C203", onMain { sheet.getSelectionRange()!!.let { com.wxiwei.office.editor.xlsx.A1FormulaShifter.address(it.getFirstRow(), it.getFirstColumn()) + ":" + com.wxiwei.office.editor.xlsx.A1FormulaShifter.address(it.getLastRow(), it.getLastColumn()) } })
            assertTrue(onMain { session.save(saved) } is EditResult.Ok)
        }
        val xml = mergeXml(saved)
        assertTrue("mergeCell saved", xml.contains("<mergeCell ref=\"A201:C203\"/>"))
        assertTrue("before the page margins", xml.indexOf("<mergeCells") in 0 until xml.indexOf("<pageMargins").let { if (it < 0) Int.MAX_VALUE else it })
        assertTrue("after sheetData", xml.indexOf("<mergeCells") > xml.indexOf("</sheetData>"))
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            val sheet = onMain { book(reader).getSheet(0)!! }
            assertTrue("read back merged", onMain { sheet.mergeIndexAt(201, 1) >= 0 })
            val session = onMain { SheetEditSession(reader.control!!, saved) }
            assertTrue(onMain { session.unmergeCells(0, 201, 1) })
            assertTrue("split", onMain { sheet.mergeIndexAt(201, 1) < 0 })
            assertTrue(onMain { session.save(split) } is EditResult.Ok)
        }
        assertFalse("split saved", mergeXml(split).contains("A201:C203"))
    }

    /** X6: a picture on sheet 1 (its drawing has charts) and on a new sheet (no drawing yet): shown, undo/redo, saved, read back. */
    @Test
    fun addPictures() {
        val source = OpenDocument.copySample("sample.xlsx", "picture_source.xlsx")
        val saved = OpenDocument.output("picture_saved.xlsx")
        val png = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sheet_pic.png")
        android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            .let { b -> png.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        var shapes0 = 0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            val sheet = onMain { book(reader).getSheet(0)!! }
            shapes0 = onMain { sheet.getShapeCount() }
            assertTrue(session.lastError?.toString(), onMain { session.addPicture(0, 2, 1, png, 120, 80) })
            assertEquals(shapes0 + 1, onMain { sheet.getShapeCount() })
            val bounds = onMain { sheet.getShape(shapes0)!!.bounds!! }
            assertEquals("size", 120 to 80, bounds.width to bounds.height)
            assertTrue(onMain { session.undo() }); assertEquals(shapes0, onMain { sheet.getShapeCount() })
            assertTrue(onMain { session.redo() }); assertEquals(shapes0 + 1, onMain { sheet.getShapeCount() })
            val added = onMain { session.addSheet("Ảnh") }
            assertTrue(onMain { session.addPicture(added, 0, 0, png, 60, 40) })
            assertTrue(onMain { session.save(saved) } is EditResult.Ok)
        }
        java.util.zip.ZipFile(saved).use { z ->
            val names = z.entries().toList().map { it.name }
            val media = names.filter { it.startsWith("xl/media/image") }
            assertTrue("two new media: $media", media.size >= 2)
            val types = z.getInputStream(z.getEntry("[Content_Types].xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue("png type", types.contains("Extension=\"png\""))
            val drawings = names.filter { it.matches(Regex("xl/drawings/drawing\\d+\\.xml")) }
            val anchors = drawings.sumOf { d -> Regex("<xdr:oneCellAnchor").findAll(z.getInputStream(z.getEntry(d)).readBytes().toString(Charsets.UTF_8)).count() }
            assertTrue("two anchors in $drawings", anchors >= 2)
        }
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            assertEquals("read back on sheet 1", shapes0 + 1, onMain { book(reader).getSheet(0)!!.getShapeCount() })
            val last = onMain { book(reader).getSheetCount() - 1 }
            assertEquals("read back on the new sheet", 1, onMain { book(reader).getSheet(last)!!.getShapeCount() })
        }
    }

    /** X6b: a picture added here moved and resized (undo/redo), another removed; saved at its new cell and offset. */
    @Test
    fun movePicture() {
        val source = OpenDocument.copySample("sample.xlsx", "picture_move_source.xlsx")
        val saved = OpenDocument.output("picture_move_saved.xlsx")
        val png = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sheet_pic_move.png")
        android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            .let { b -> png.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        var want = IntArray(0)
        var shapes0 = 0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            val sheet = onMain { book(reader).getSheet(0)!! }
            shapes0 = onMain { sheet.getShapeCount() }
            assertTrue(onMain { session.addPicture(0, 2, 1, png, 120, 80) })
            val shape = onMain { sheet.getShape(shapes0)!! }
            assertTrue("added here", session.isMovablePicture(shape))
            // charts of the file are not pictures: they stay put
            val charts = onMain { sheet.getShapes().filter { it !is com.wxiwei.office.common.shape.PictureShape } }
            assertTrue(charts.none { session.isMovablePicture(it) })
            val start = onMain { shape.bounds!!.let { com.wxiwei.office.java.awt.Rectangle(it.x, it.y, it.width, it.height) } }
            // into column D + 7 px, row 6 + 5 px, twice as big
            val x = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueX(sheet, 3, 7).toInt() }
            val y = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueY(sheet, 5, 5).toInt() }
            assertTrue(onMain { session.setPictureBounds(shape, com.wxiwei.office.java.awt.Rectangle(x, y, 240, 160)) })
            assertEquals(listOf(x, y, 240, 160), onMain { shape.bounds!!.let { listOf(it.x, it.y, it.width, it.height) } })
            assertTrue(onMain { session.undo() })
            assertEquals(listOf(start.x, start.y), onMain { shape.bounds!!.let { listOf(it.x, it.y) } })
            assertTrue(onMain { session.redo() })
            want = intArrayOf(5, 3)
            // a second picture, removed: not saved
            assertTrue(onMain { session.addPicture(0, 0, 0, png, 60, 40) })
            assertTrue(onMain { session.removePicture(sheet.getShape(shapes0 + 1)!!) })
            assertEquals(shapes0 + 1, onMain { sheet.getShapeCount() })
            assertTrue(onMain { session.save(saved) } is EditResult.Ok)
        }
        java.util.zip.ZipFile(saved).use { z ->
            val xml = z.entries().toList().map { it.name }.filter { it.matches(Regex("xl/drawings/drawing\\d+\\.xml")) }
                .joinToString("") { z.getInputStream(z.getEntry(it)).readBytes().toString(Charsets.UTF_8) }
            val anchors = Regex("<xdr:oneCellAnchor>.*?</xdr:oneCellAnchor>").findAll(xml).map { it.value }.toList()
            assertEquals("one new picture: $anchors", 1, anchors.size)
            val a = anchors[0]
            assertTrue(a, a.contains("<xdr:col>${want[1]}</xdr:col>") && a.contains("<xdr:row>${want[0]}</xdr:row>"))
            assertTrue(a, !a.contains("<xdr:colOff>0</xdr:colOff>") && !a.contains("<xdr:rowOff>0</xdr:rowOff>"))
        }
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            assertEquals("read back", shapes0 + 1, onMain { book(reader).getSheet(0)!!.getShapeCount() })
        }
    }

    /**
     * A picture added, moved and saved, then the file opened again: the picture can be selected,
     * moved again and saved (by its drawing id); a picture that came with the file is deleted.
     */
    @Test
    fun movePictureAfterReopen() {
        val source = OpenDocument.copySample("sample.xlsx", "picture_reopen_source.xlsx")
        val first = OpenDocument.output("picture_reopen_1.xlsx")
        val second = OpenDocument.output("picture_reopen_2.xlsx")
        val png = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sheet_pic_reopen.png")
        android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
            .let { b -> png.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        fun pictures(book: Workbook) = book.getSheet(0)!!.getShapes().filterIsInstance<com.wxiwei.office.common.shape.PictureShape>()
        var filePictures = 0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            filePictures = onMain { pictures(book(reader)).size }
            assertTrue(onMain { session.addPicture(0, 2, 1, png, 120, 80) })
            val shape = onMain { pictures(book(reader)).last() }
            val x = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueX(book(reader).getSheet(0)!!, 3, 0).toInt() }
            val y = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueY(book(reader).getSheet(0)!!, 6, 0).toInt() }
            assertTrue(onMain { session.setPictureBounds(shape, com.wxiwei.office.java.awt.Rectangle(x, y, 120, 80)) })
            assertTrue(onMain { session.save(first) } is EditResult.Ok)
        }
        var movedTo = 0 to 0
        OpenDocument.open(first) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, first) }
            val all = onMain { pictures(book(reader)) }
            assertEquals(filePictures + 1, all.size)
            // every picture of the file can be picked up now
            assertTrue(all.all { onMain { session.isMovablePicture(it) } })
            val ours = all.last()
            val sheet = onMain { book(reader).getSheet(0)!! }
            val x = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueX(sheet, 5, 10).toInt() }
            val y = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueY(sheet, 12, 4).toInt() }
            movedTo = 12 to 5
            assertTrue(onMain { session.setPictureBounds(ours, com.wxiwei.office.java.awt.Rectangle(x, y, 200, 100)) })
            if (filePictures > 0) assertTrue(onMain { session.removePicture(all.first()) })
            assertTrue(onMain { session.save(second) } is EditResult.Ok)
        }
        OpenDocument.open(second) { reader ->
            loadAll(reader)
            val all = onMain { pictures(book(reader)) }
            assertEquals("the file's first picture deleted", filePictures + 1 - (if (filePictures > 0) 1 else 0), all.size)
            val sheet = onMain { book(reader).getSheet(0)!! }
            val b = onMain { all.last().bounds!! }
            val x = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueX(sheet, movedTo.second, 10) }
            val y = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueY(sheet, movedTo.first, 4) }
            assertEquals("moved again: $b", x, b.x.toFloat(), 2f)
            assertEquals("moved again: $b", y, b.y.toFloat(), 2f)
            assertEquals(200f, b.width.toFloat(), 2f)
        }
    }

    /** The picture that came with the file (sheet 3): moved and saved, then deleted and saved; undo brings it back. */
    @Test
    fun moveAndDeleteFilePicture() {
        val source = OpenDocument.copySample("sample.xlsx", "file_picture_source.xlsx")
        val moved = OpenDocument.output("file_picture_moved.xlsx")
        val deleted = OpenDocument.output("file_picture_deleted.xlsx")
        fun pictures(book: Workbook) = book.getSheet(2)!!.getShapes().filterIsInstance<com.wxiwei.office.common.shape.PictureShape>()
        var size = 0 to 0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, source) }
            val pic = onMain { pictures(book(reader)) }.single()
            assertTrue(onMain { session.isMovablePicture(pic) })
            size = onMain { pic.bounds!!.width to pic.bounds!!.height }
            val sheet = onMain { book(reader).getSheet(2)!! }
            val x = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueX(sheet, 7, 0).toInt() }
            val y = onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueY(sheet, 20, 0).toInt() }
            assertTrue(onMain { session.setPictureBounds(pic, com.wxiwei.office.java.awt.Rectangle(x, y, size.first, size.second)) })
            assertTrue(onMain { session.save(moved) } is EditResult.Ok)
        }
        java.util.zip.ZipFile(moved).use { z ->
            val xml = z.getInputStream(z.getEntry("xl/drawings/drawing2.xml")).readBytes().toString(Charsets.UTF_8)
            val from = Regex("<xdr:from>(.*?)</xdr:from>").findAll(xml).map { it.groupValues[1] }.toList()
            // rows 21..108 of this sheet are hidden: the anchor names the first row shown there
            assertTrue("anchored in column H: $from", from.single().contains("<xdr:col>7</xdr:col><xdr:colOff>0</xdr:colOff>"))
            assertFalse("moved from row 119", from.single().contains("<xdr:row>118</xdr:row>"))
        }
        OpenDocument.open(moved) { reader ->
            loadAll(reader)
            val session = onMain { SheetEditSession(reader.control!!, moved) }
            val pic = onMain { pictures(book(reader)) }.single()
            val sheet = onMain { book(reader).getSheet(2)!! }
            assertEquals("kept its place", onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueX(sheet, 7, 0) }, pic.bounds!!.x.toFloat(), 2f)
            assertEquals("kept its place", onMain { com.wxiwei.office.ss.util.ModelUtil.instance().getValueY(sheet, 20, 0) }, pic.bounds!!.y.toFloat(), 2f)
            assertEquals("kept its size", size.first.toFloat(), pic.bounds!!.width.toFloat(), 2f)
            assertTrue(onMain { session.removePicture(pic) })
            assertTrue(onMain { pictures(book(reader)) }.isEmpty())
            assertTrue(onMain { session.undo() })
            assertEquals("back after undo", 1, onMain { pictures(book(reader)) }.size)
            assertTrue(onMain { session.redo() })
            assertTrue(onMain { session.save(deleted) } is EditResult.Ok)
        }
        OpenDocument.open(deleted) { reader ->
            loadAll(reader)
            assertTrue("deleted for good", onMain { pictures(book(reader)) }.isEmpty())
        }
    }

    /** X3: a red border all around one cell: shown, the cells sharing its old style unchanged, saved, read back. */
    @Test
    fun cellBorder() {
        val source = OpenDocument.copySample("sample.xlsx", "border_source.xlsx")
        val saved = OpenDocument.output("border_saved.xlsx")
        var at = 0 to 0
        var twin = 0 to 0
        OpenDocument.open(source) { reader ->
            loadAll(reader)
            val book = book(reader)
            // a cell and another one with the same style
            onMain {
                val sheet = book.getSheet(0)!!
                val cells = (0..40).flatMap { r -> (0..8).mapNotNull { c -> sheet.getRow(r)?.getCell(c)?.let { (r to c) to it } } }
                val pair = cells.groupBy { it.second.getCellStyle() }.values.first { it.size >= 2 }
                at = pair[0].first; twin = pair[1].first
            }
            fun style(p: Pair<Int, Int>) = book.getSheet(0)!!.getRow(p.first)!!.getCell(p.second)!!.getCellStyle()!!
            val twinBefore = onMain { style(twin).getBorderLeft() to style(twin).getBorderTop() }
            val session = onMain { SheetEditSession(reader.control!!, source) }
            assertTrue(session.lastError?.toString(), onMain { session.setCellFormat(0, at.first, at.second, com.wxiwei.office.editor.xlsx.CellFormat(border = "all", borderColor = "FF0000")) })
            val st = onMain { style(at) }
            assertEquals(com.wxiwei.office.ss.model.style.BorderStyle.BORDER_THIN, onMain { st.getBorderLeft() })
            assertEquals(com.wxiwei.office.ss.model.style.BorderStyle.BORDER_THIN, onMain { st.getBorderBottom() })
            assertEquals(0xFF0000, onMain { book.getColor(st.getBorderTopColorIdx().toInt()) and 0xFFFFFF })
            assertEquals("the cell sharing its old style keeps its borders", twinBefore, onMain { style(twin).getBorderLeft() to style(twin).getBorderTop() })
            assertTrue(onMain { session.save(saved) } is EditResult.Ok)
        }
        val styles = java.util.zip.ZipFile(saved).use { z -> z.getInputStream(z.getEntry("xl/styles.xml")).readBytes().toString(Charsets.UTF_8) }
        assertTrue("border saved", Regex("<left style=\"thin\"><color rgb=\"FFFF0000\"/></left><right style=\"thin\">").containsMatchIn(styles))
        OpenDocument.open(saved) { reader ->
            loadAll(reader)
            val st = onMain { book(reader).getSheet(0)!!.getRow(at.first)!!.getCell(at.second)!!.getCellStyle()!! }
            assertEquals(com.wxiwei.office.ss.model.style.BorderStyle.BORDER_THIN, onMain { st.getBorderRight() })
            assertEquals(0xFF0000, onMain { book(reader).getColor(st.getBorderRightColorIdx().toInt()) and 0xFFFFFF })
        }
    }

    /** Copy and paste of cells: formulas move, the look comes along, undo takes the paste back whole. */
    @Test
    fun copyPasteCells() {
        val source = OpenDocument.copySample("sample.xlsx", "xlsx_copy_paste.xlsx")
        OpenDocument.open(source) { reader ->
            val session = onMain { SheetEditSession(reader.control!!, source) }
            onMain {
                assertTrue(session.setCellInput(0, 199, 26, "5"))
                assertTrue(session.setCellInput(0, 200, 26, "=AA200*2"))
                assertTrue(session.setCellFormat(0, 199, 26, com.wxiwei.office.editor.xlsx.CellFormat(bold = true)))
            }
            val block = onMain { session.copyRange(0, 199, 26, 200, 26) }!!
            assertEquals("5\n10", block.text)
            onMain { assertTrue(session.pasteBlock(0, 204, 28, block)) }
            assertEquals("5", onMain { session.getInput(0, 204, 28) })
            assertEquals("=AC205*2", onMain { session.getInput(0, 205, 28) })
            onMain { assertTrue(session.undo()) }
            assertEquals("after undo", "", onMain { session.getInput(0, 204, 28) })
            assertEquals("after undo", "", onMain { session.getInput(0, 205, 28) })
            onMain { assertTrue(session.redo()) }
            assertEquals("=AC205*2", onMain { session.getInput(0, 205, 28) })
        }
    }
}
