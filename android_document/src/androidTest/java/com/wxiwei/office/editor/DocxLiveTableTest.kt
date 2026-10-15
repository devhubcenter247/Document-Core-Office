/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wxiwei.office.editor.OpenDocument.onMain
import com.wxiwei.office.editor.docx.LiveDocxSession
import com.wxiwei.office.reader.OfficeReader
import com.wxiwei.office.wp.control.Word
import com.wxiwei.office.wp.model.RowElement
import com.wxiwei.office.wp.model.TableElement
import com.wxiwei.office.wp.model.WPDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A table inserted live: shown at once, edited without a save, and the saved file reads back the same. */
@RunWith(AndroidJUnit4::class)
class DocxLiveTableTest {
    private fun ok(s: LiveDocxSession, done: Boolean) = assertTrue(s.lastError?.toString(), done)

    /** The whole body text and the cell texts of every body table, as shown. */
    private fun shown(reader: OfficeReader): Pair<String, List<List<List<String>>>> = onMain {
        val doc = (reader.control!!.getView() as Word).getDocument() as WPDocument
        val tables = doc.getTableCollection(0)!!
        doc.getText(0, doc.getAreaEnd(0)) to (0 until tables.size()).map { i ->
            val t = tables.getElementForIndex(i) as TableElement
            (0 until t.rowCount()).map { r -> (t.getElementForIndex(r) as RowElement).let { row ->
                (0 until row.getCellNumber()).map { k -> row.getElementForIndex(k)!!.let { c -> doc.getText(c.getStartOffset(), c.getEndOffset() - 1) } } } }
        }
    }

    /** Runs [edit] live on [source], checks it needed no reopen, saves; the saved file shows what the live view showed. */
    private fun liveThenSaved(source: File, name: String, edit: suspend (LiveDocxSession, OfficeReader) -> Unit): Pair<String, List<List<List<String>>>> {
        val saved = OpenDocument.output("docx_livetable_${name}_saved.docx")
        var live: Pair<String, List<List<List<String>>>>? = null
        OpenDocument.open(source, { it.layout != null }) { reader ->
            val s = onMain { LiveDocxSession(reader.control!!, source) }
            edit(s, reader)
            assertFalse("shown without reopening", s.needsReopen)
            live = shown(reader)
            val result = onMain { s.save(saved) }
            assertTrue(result.toString(), result is EditResult.Ok)
        }
        var back: Pair<String, List<List<List<String>>>>? = null
        OpenDocument.open(saved, { it.layout != null }) { reader -> back = shown(reader) }
        assertEquals("the saved file reads back as shown", live, back)
        return back!!
    }

    private fun blank(name: String) = OpenDocument.output("docx_livetable_$name.docx").also {
        assertTrue(DocumentCreator.create(InstrumentationRegistry.getInstrumentation().targetContext, DocumentCreator.Format.WORD, it) is EditResult.Ok)
    }

    @Test fun blankDocumentTableTypedAndEdited() {
        val (_, tables) = liveThenSaved(blank("blank"), "blank") { s, _ ->
            ok(s, onMain { s.insertTable(0, 2, 3) })
            ok(s, onMain { s.insertText(s.cellStart(0, 0, 0), "a") })
            ok(s, onMain { s.insertText(s.cellStart(0, 1, 2), "f") })
            ok(s, onMain { s.insertTableRow(s.cellStart(0, 1, 0), below = true) })
            ok(s, onMain { s.insertText(s.cellStart(0, 2, 1), "h") })
            ok(s, onMain { s.deleteTableColumn(s.cellStart(0, 0, 1)) })
            ok(s, onMain { s.deleteTableRow(s.cellStart(0, 1, 0)) })
            ok(s, onMain { s.insertTableColumn(s.cellStart(0, 0, 0), right = true) })
            ok(s, onMain { s.setTableRowHeight(s.cellStart(0, 0, 0), 800) })
            assertTrue(s.lastError?.toString(), onMain { s.resizeTableColumn(s.cellStart(0, 0, 0), 1, 300) } != null)
        }
        assertEquals(listOf(listOf(listOf("a", "", ""), listOf("", "", ""))), tables)
    }

    /**
     * A new document made two pages long with Enter and a table on the second page (which reopens
     * the working copy, as the panel does), then Backspace again and again from just before it.
     */
    @Test fun backspaceBeforeTableOfNewDocument() {
        val source = blank("backspace")
        val working = OpenDocument.output("docx_livetable_backspace_working.docx")
        OpenDocument.open(source, { it.layout != null }) { reader ->
            val s = onMain { LiveDocxSession(reader.control!!, source) }
            for (i in 0 until 60) ok(s, onMain { s.insertText(i.toLong(), "\n") })
            // after a paragraph typed just now: the working copy is saved first
            assertFalse(onMain { s.insertTable(55, 2, 2) })
            assertTrue("asks for a save first", s.needsFlush)
            val result = onMain { s.save(working) }
            assertTrue(result.toString(), result is EditResult.Ok)
        }
        OpenDocument.open(working, { it.layout != null }) { reader ->
            val s = onMain { LiveDocxSession(reader.control!!, working) }
            val doc = onMain { (reader.control!!.getView() as Word).getDocument() as WPDocument }
            ok(s, onMain { s.insertTable(55, 2, 2) })
            assertFalse("shown at once", s.needsReopen)
            val table = onMain { doc.getTableCollection(0)!!.getElementForIndex(0)!!.let { it.getStartOffset() until it.getEndOffset() } }
            assertEquals("right after the paragraph it was asked for", 56L, table.first)
            // the mark right before the table cannot go (the table would have no paragraph to follow): refused, nothing queued
            assertFalse("Backspace at the start of the table is refused", onMain { s.deleteText(table.first - 1, table.first) })
            assertFalse(s.needsReopen)
            // the empty paragraphs above it go one by one, and typing still works
            var at = table.first - 1
            repeat(20) { ok(s, onMain { s.deleteText(at - 1, at) }); at-- }
            ok(s, onMain { s.insertText(at, "x") })
            ok(s, onMain { s.insertText(s.cellStart(0, 0, 0), "cell") })
            val saved = onMain { s.save(OpenDocument.output("docx_livetable_backspace_saved.docx")) }
            assertTrue(saved.toString(), saved is EditResult.Ok)
        }
    }

    @Test fun twoTablesAfterOneParagraph() {
        val (_, tables) = liveThenSaved(blank("two"), "two") { s, _ ->
            ok(s, onMain { s.insertTable(0, 1, 2) })
            ok(s, onMain { s.insertText(s.cellStart(0, 0, 0), "first") })
            // after the same paragraph again: the new one comes first
            ok(s, onMain { s.insertTable(0, 1, 1) })
            ok(s, onMain { s.insertText(s.cellStart(0, 0, 0), "second") })
        }
        assertEquals(listOf(listOf(listOf("second")), listOf(listOf("first", ""))), tables)
    }

    @Test fun betweenParagraphsOfSample() {
        val source = OpenDocument.copySample("sample.docx", "docx_livetable_sample.docx")
        val (_, tables) = liveThenSaved(source, "sample") { s, reader ->
            val at = onMain { ((reader.control!!.getView() as Word).getDocument()).getParagraph(0)!!.getEndOffset() + 1 }
            ok(s, onMain { s.insertTable(at, 2, 2) })
            ok(s, onMain { s.insertText(s.cellStart(0, 1, 1), "x") })
        }
        assertEquals(listOf(listOf("", ""), listOf("", "x")), tables[0])
    }

    @Test fun movedDownAndUp() {
        val source = OpenDocument.copySample("sample.docx", "docx_livetable_move.docx")
        val (_, tables) = liveThenSaved(source, "move") { s, reader ->
            val doc = onMain { (reader.control!!.getView() as Word).getDocument() as WPDocument }
            val at = onMain { doc.getParagraph(0)!!.getEndOffset() + 1 }
            ok(s, onMain { s.insertTable(at, 1, 2) })
            ok(s, onMain { s.insertText(s.cellStart(0, 0, 1), "moved") })
            // below the paragraph after it, then back above the one before it
            val next = onMain { doc.getParagraph0(s.cellStart(0, 0, 0))!!.getEndOffset() }
            ok(s, onMain { s.moveTable(s.cellStart(0, 0, 0), next, after = true) })
            ok(s, onMain { s.moveTable(s.cellStart(0, 0, 0), 0, after = false) })
        }
        assertEquals(listOf(listOf("", "moved")), tables[0])
    }

    @Test fun undoRedo() {
        val (_, tables) = liveThenSaved(blank("undo"), "undo") { s, _ ->
            ok(s, onMain { s.insertTable(0, 2, 2) })
            ok(s, onMain { s.undo() })
            ok(s, onMain { s.redo() })
            ok(s, onMain { s.insertTableRow(s.cellStart(0, 0, 0), below = false) })
            ok(s, onMain { s.undo() })
            ok(s, onMain { s.insertText(s.cellStart(0, 1, 0), "z") })
        }
        assertEquals(listOf(listOf(listOf("", ""), listOf("z", ""))), tables)
    }

    /** The page with a live table looks like the page of the saved file read again. */
    @Test fun looksLikeTheSavedFile() {
        val source = OpenDocument.copySample("sample.docx", "docx_livetable_look.docx")
        val saved = OpenDocument.output("docx_livetable_look_saved.docx")
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "render").apply { mkdirs() }
        suspend fun page(reader: OfficeReader, name: String): android.graphics.Bitmap {
            reader.thumbnails!!.invalidateAll()
            kotlinx.coroutines.delay(800)
            return reader.thumbnails!!.render(1, 1000)!!.also { b -> File(out, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } }
        }
        var live: android.graphics.Bitmap? = null
        OpenDocument.open(source, { it.layout != null }) { reader ->
            val s = onMain { LiveDocxSession(reader.control!!, source) }
            val at = onMain { ((reader.control!!.getView() as Word).getDocument()).getParagraph(0)!!.getEndOffset() + 1 }
            ok(s, onMain { s.insertTable(at, 3, 3) })
            ok(s, onMain { s.insertText(s.cellStart(0, 1, 1), "Ô giữa") })
            live = page(reader, "livetable_live")
            assertTrue(onMain { s.save(saved) } is EditResult.Ok)
        }
        var back: android.graphics.Bitmap? = null
        OpenDocument.open(saved, { it.layout != null }) { reader -> back = page(reader, "livetable_saved") }
        val a = live!!; val b = back!!
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var diff = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
        android.util.Log.i("DocxLiveTableTest", "pixels differing: $diff of ${a.width * a.height}")
        assertTrue("pixels differing: $diff", diff < a.width * a.height / 1000)
    }

    /**
     * Paragraph formatting of the paragraphs of every body table (alignment, indents, spacing, line, list), as shown.
     * Single, 1.5, double and multiple line spacing are laid out alike (a multiple of the value): one kind here.
     */
    private fun tableParagraphs(reader: OfficeReader): List<String> = onMain {
        val doc = (reader.control!!.getView() as Word).getDocument() as WPDocument
        val am = com.wxiwei.office.simpletext.model.AttrManage.instance()
        val tables = doc.getTableCollection(0)!!
        (0 until tables.size()).flatMap { i ->
            val t = tables.getElementForIndex(i)!!
            val out = ArrayList<String>()
            var o = t.getStartOffset()
            while (o < t.getEndOffset()) {
                val p = doc.getParagraph(o) ?: break
                val a = p.getAttribute()
                out.add("${doc.getText(p.getStartOffset(), p.getEndOffset() - 1)}|jc${am.getParaHorizontalAlign(a)}|l${am.getParaIndentLeft(a)}|r${am.getParaIndentRight(a)}|s${am.getParaSpecialIndent(a)}" +
                    "|b${am.getParaBefore(a)}|a${am.getParaAfter(a)}|lt${am.getParaLineSpaceType(a).let { t -> if (t == 3 || t == 4) t.toString() else "x" }}|ls${am.getParaLineSpace(a)}|list${am.getParaListID(a) >= 0}/${maxOf(0, am.getParaListLevel(a))}")
                o = maxOf(o + 1, p.getEndOffset())
            }
            out
        }
    }

    /** Paragraphs of new cells formatted without a save: the saved file reads back with the same formatting. */
    @Test fun paragraphFormattingInNewCells() {
        val source = OpenDocument.copySample("sample.docx", "docx_livetable_pfmt.docx")
        val saved = OpenDocument.output("docx_livetable_pfmt_saved.docx")
        var live: List<String>? = null
        OpenDocument.open(source, { it.layout != null }) { reader ->
            val s = onMain { LiveDocxSession(reader.control!!, source) }
            // a new row in the second table of the file, then a new table before the tables
            ok(s, onMain { s.insertTableRow(s.cellStart(1, 1, 0), below = true) })
            ok(s, onMain { s.insertText(s.cellStart(1, 2, 0), "one\ntwo") })
            val c0 = s.cellStart(1, 2, 0)
            ok(s, onMain { s.setAlignment(c0, c0 + 1, "center") })
            ok(s, onMain { s.setBullets(c0 + 4, c0 + 5, true) })
            ok(s, onMain { s.setParagraphSpacing(c0, c0 + 5, 6f, 12f) })
            ok(s, onMain { s.setLineSpacing(s.cellStart(1, 2, 1), s.cellStart(1, 2, 1) + 1, 1.5f) })
            val at = onMain { ((reader.control!!.getView() as Word).getDocument()).getParagraph(0)!!.getEndOffset() + 1 }
            ok(s, onMain { s.insertTable(at, 1, 2) })
            ok(s, onMain { s.insertText(s.cellStart(0, 0, 1), "n") })
            val n = s.cellStart(0, 0, 1)
            ok(s, onMain { s.setNumbering(n, n + 1, true) })
            ok(s, onMain { s.setParagraphLayout(s.cellStart(0, 0, 0), s.cellStart(0, 0, 0) + 1, LiveDocxSession.ParagraphLayout("right", 360, 180, -240, 3f, 0f)) })
            assertFalse("shown without reopening", s.needsReopen)
            live = tableParagraphs(reader)
            val result = onMain { s.save(saved) }
            assertTrue(result.toString(), result is EditResult.Ok)
        }
        var back: List<String>? = null
        OpenDocument.open(saved, { it.layout != null }) { reader -> back = tableParagraphs(reader) }
        android.util.Log.i("DocxLiveTableTest", "live ${live!!.take(4)}")
        assertEquals(live, back)
    }
}
