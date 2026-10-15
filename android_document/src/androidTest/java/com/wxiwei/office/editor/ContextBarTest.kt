/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.docsdk.DocumentEditor
import com.editor.docsdk.EditAction
import com.editor.docsdk.EditContext
import com.wxiwei.office.editor.OpenDocument.onMain
import com.wxiwei.office.editor.pptx.PptxEditor
import com.wxiwei.office.editor.pptx.ShapeKind
import com.wxiwei.office.editor.slide.SlideGeometry
import com.wxiwei.office.editor.word.WordSelection
import com.wxiwei.office.pg.control.Presentation
import com.wxiwei.office.wp.control.Word
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The context bar: the commands for what is selected in Word, Excel and PowerPoint. */
@RunWith(AndroidJUnit4::class)
class ContextBarTest {
    private fun find(v: View, match: (View) -> Boolean): View? = if (match(v)) v else (v as? android.view.ViewGroup)?.let { g ->
        (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it), match) } }

    /** The bar's button of [action] as shown, or null. */
    private fun button(host: EditInView.Host, action: EditAction): View? = onMain { find(host.view) { it.tag == action.name && it.isShown } }

    private fun tap(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val t = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 40)) {
            MotionEvent.obtain(t, at, action, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle() }
        }
    }

    private fun shot(name: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name.png").close()

    private fun docx(): java.io.File {
        val file = OpenDocument.output("context-bar.docx")
        fun p(text: String) = "<w:p><w:r><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
        fun tc(text: String) = "<w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>${p(text)}</w:tc>"
        val table = "<w:tbl><w:tblPr><w:tblW w:w=\"8000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
            "<w:tr>${tc("A1")}${tc("B1")}</w:tr><w:tr>${tc("A2")}${tc("B2")}</w:tr></w:tbl>"
        val body = p("Before the table.") + table + p("After the table.")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, xml: String) { zip.putNextEntry(ZipEntry(path)); zip.write(xml.toByteArray()); zip.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            put("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""")
        }
        return file
    }

    @Test
    fun wordTextAndTable() {
        EditInView.edit(docx()) {
            delay(1000)
            fun word() = EditInView.office(view).control!!.getView() as Word
            fun text() = onMain { word().getDocument().let { it.getText(0, it.getAreaEnd(0)) } }
            suspend fun tapAt(offset: Long) {
                val (x, y) = onMain {
                    val w = word()
                    val o = IntArray(2).also { w.getLocationOnScreen(it) }
                    WordSelection(w).caretRect(offset)!!.let { r -> (o[0] + r.left + 1f) to (o[1] + r.centerY().toFloat()) }
                }
                tap(x, y)
                delay(900)
            }
            // nothing yet: no bar
            assertEquals(null, onMain { editor.context })
            assertEquals(null, button(this, EditAction.BOLD))

            // the caret in text: copy and paste, the format of the text
            tapAt(text().indexOf("Before").toLong() + 2)
            assertEquals(EditContext.TEXT, onMain { editor.context })
            assertTrue("format on the bar", button(this, EditAction.BOLD) != null)
            assertFalse("nothing selected to copy", button(this, EditAction.COPY)!!.let { b -> onMain { b.isEnabled } })
            shot("context-word-text")
            delay(600)

            // in the table: its rows and columns first
            val before = text()
            tapAt(text().indexOf("B2").toLong() + 1)
            assertEquals(EditContext.TABLE, onMain { editor.context })
            assertTrue(button(this, EditAction.SELECT_TABLE) != null)
            shot("context-word-table")
            delay(600)
            button(this, EditAction.ROW_BELOW)!!.let { b -> onMain { b.performClick() } }
            delay(1500)
            withTimeout(15_000) { while (onMain { editor.panel.isBusy }) delay(100) }
            assertTrue("a row added", text().length > before.length)

            // the app picks the commands of a context
            onMain { editor.contextCommands = DocumentEditor.ContextCommands { c, d -> if (c == EditContext.TABLE) listOf(EditAction.SELECT_TABLE) else d } }
            delay(300)
            assertTrue(button(this, EditAction.SELECT_TABLE) != null)
            assertEquals(null, button(this, EditAction.ROW_BELOW))
            // or turns the bar off
            onMain { editor.contextBar = false }
            delay(300)
            assertEquals(null, button(this, EditAction.SELECT_TABLE))
        }
    }

    @Test
    fun excelCells() {
        val file = OpenDocument.copySample("sample.xlsx", "context-bar.xlsx")
        EditInView.edit(file) {
            delay(1000)
            assertTrue(onMain { editor.run(EditAction.GO_TO_CELL, "B2:C3") })
            delay(600)
            assertEquals(EditContext.CELLS, onMain { editor.context })
            assertTrue(button(this, EditAction.COPY) != null)
            shot("context-excel")
            delay(600)
            // B on the bar turns bold on or off, as the cell was
            val bold = onMain { editor.isActive(EditAction.BOLD) }
            button(this, EditAction.BOLD)!!.let { b -> onMain { b.performClick() } }
            delay(400)
            assertEquals(!bold, onMain { editor.isActive(EditAction.BOLD) })
        }
    }

    @Test
    fun slideShape() {
        val file = OpenDocument.copySample("sample.pptx", "context-bar.pptx")
        val shapes = PptxEditor(file).listShapes(0)
        val shape = shapes.first { s ->
            val r = s.rectEmu
            s.kind == ShapeKind.TEXT && SlideGeometry.pick(shapes, com.wxiwei.office.editor.pptx.Point(r.x + r.width / 2, r.y + r.height / 2)) == s
        }
        EditInView.edit(file) {
            withTimeout(15_000) { while (onMain { view.pageCount } < 10) delay(100) }
            delay(800)
            val (x, y) = onMain {
                val p = EditInView.office(view).control!!.getView() as Presentation
                val r = SlideGeometry.emuToView(p, shape.rectEmu)!!
                val o = IntArray(2).also { p.getLocationOnScreen(it) }
                (o[0] + r.centerX()) to (o[1] + r.centerY())
            }
            tap(x, y)
            delay(1000)
            assertEquals(EditContext.SHAPE, onMain { editor.context })
            assertTrue(button(this, EditAction.CUT) != null)
            shot("context-pptx-shape")
            delay(600)
            button(this, EditAction.COPY)!!.let { b -> onMain { b.performClick() } }
            delay(300)
            val clip = onMain { (InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() }
            assertEquals(shape.text, clip)
        }
    }
}
