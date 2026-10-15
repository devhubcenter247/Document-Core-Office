/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.docsdk.EditAction
import com.wxiwei.office.editor.OpenDocument.onMain
import com.wxiwei.office.editor.word.WordSelection
import com.wxiwei.office.wp.control.Word
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A Word table selected whole, copied and pasted as a table, undone and redone, cut. */
@RunWith(AndroidJUnit4::class)
class WordTableClipboardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Two paragraphs around a 2 x 2 table whose first cell is bold and second shaded. */
    private fun docx(name: String): File {
        val file = OpenDocument.output(name)
        fun p(text: String, rPr: String = "") = "<w:p><w:r>$rPr<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
        fun tc(text: String, tcPr: String = "", rPr: String = "") = "<w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/>$tcPr</w:tcPr>${p(text, rPr)}</w:tc>"
        val table = "<w:tbl><w:tblPr><w:tblW w:w=\"8000\" w:type=\"dxa\"/><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/><w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>" +
            "<w:tblGrid><w:gridCol w:w=\"4000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
            "<w:tr>${tc("A1", rPr = "<w:rPr><w:b/></w:rPr>")}${tc("B1", "<w:shd w:val=\"clear\" w:fill=\"FFFF00\"/>")}</w:tr>" +
            "<w:tr>${tc("A2")}${tc("B2")}</w:tr></w:tbl>"
        val body = p("Before the table.") + table + p("After the table.")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, xml: String) { zip.putNextEntry(ZipEntry(path)); zip.write(xml.toByteArray()); zip.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            put("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""")
        }
        return file
    }

    private fun word(host: EditInView.Host) = EditInView.office(host.view).control!!.getView() as Word
    private fun text(host: EditInView.Host): String = onMain {
        val d = word(host).getDocument()
        d.getText(0, d.getAreaEnd(0))
    }
    private fun tables(host: EditInView.Host): Int = Regex("A1").findAll(text(host)).count()

    /** A tap on the document at [offset], as the user puts the caret there. */
    private suspend fun tapAt(host: EditInView.Host, offset: Long) {
        val (x, y) = withTimeout(10_000) {
            var spot: Pair<Float, Float>? = null
            while (spot == null) {
                spot = onMain {
                    val w = word(host)
                    val origin = IntArray(2).also { w.getLocationOnScreen(it) }
                    WordSelection(w).caretRect(offset)?.let { r -> (origin[0] + r.left + 1f) to (origin[1] + r.centerY().toFloat()) }
                }
                if (spot == null) delay(100)
            }
            spot
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val t = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 40)) {
            MotionEvent.obtain(t, at, action, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle() }
        }
        delay(800)
    }

    private fun run(host: EditInView.Host, action: EditAction): Boolean = onMain { host.editor.run(action) }

    /** Waits for the document to be read again after an edit that reopens it. */
    private suspend fun reopened(host: EditInView.Host) {
        delay(300)
        withTimeout(20_000) { while (onMain { host.editor.panel.isBusy }) delay(100) }
        delay(800)
    }

    @Test
    fun copyPasteUndoCutTable() {
        val file = docx("table-clip.docx")
        EditInView.edit(file) {
            delay(1000)
            assertEquals(1, tables(this))

            // caret in a cell, select the table, copy it
            tapAt(this, text(this).indexOf("B2").toLong() + 1)
            assertTrue(run(this, EditAction.SELECT_TABLE))
            assertTrue(run(this, EditAction.COPY))
            assertEquals("cells for other apps", "A1\tB1\nA2\tB2", onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() })

            // paste after the last paragraph: a second table
            tapAt(this, text(this).indexOf("After").toLong() + 3)
            assertTrue(run(this, EditAction.PASTE))
            reopened(this)
            assertEquals("pasted as a table", 2, tables(this))

            // undo / redo across the reopen
            assertTrue(onMain { editor.isEnabled(EditAction.UNDO) })
            assertTrue(run(this, EditAction.UNDO))
            reopened(this)
            assertEquals("undone", 1, tables(this))
            assertTrue(onMain { editor.isEnabled(EditAction.REDO) })
            assertTrue(run(this, EditAction.REDO))
            reopened(this)
            assertEquals("redone", 2, tables(this))
            assertFalse(onMain { editor.isEnabled(EditAction.REDO) })

            assertTrue(onMain { editor.save() })
        }
        val xml = ZipFile(file).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }
        val tbls = Regex("<w:tbl>.*?</w:tbl>").findAll(xml).map { it.value }.toList()
        assertEquals(2, tbls.size)
        assertTrue("same look: ${tbls[1]}", tbls[1].contains("w:fill=\"FFFF00\"") && tbls[1].contains("<w:b/>") && tbls[1].contains("insideV"))
        assertTrue("a paragraph after each table", Regex("</w:tbl><w:p").findAll(xml).count() == 2)
        assertTrue("pasted after 'After the table.'", xml.indexOf("After the table.") < xml.lastIndexOf("<w:tbl>"))

        // cut: the table goes, the text around stays
        EditInView.edit(file) {
            delay(1000)
            assertEquals(2, tables(this))
            tapAt(this, text(this).indexOf("A1").toLong() + 1)
            assertTrue(run(this, EditAction.SELECT_TABLE))
            assertTrue(run(this, EditAction.CUT))
            reopened(this)
            assertEquals("cut", 1, tables(this))
            assertTrue(text(this).contains("Before the table.") && text(this).contains("After the table."))
        }
    }
}
