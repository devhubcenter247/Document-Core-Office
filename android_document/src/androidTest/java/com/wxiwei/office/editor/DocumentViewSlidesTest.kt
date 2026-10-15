/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.docsdk.DocumentView
import com.editor.docsdk.EditAction
import com.wxiwei.office.editor.OpenDocument.onMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Slides added, moved and deleted through [DocumentView]: the page it reports is the slide the
 * edit shows, commands sent while it reopens are refused, and the last slide is never deleted.
 */
@RunWith(AndroidJUnit4::class)
class DocumentViewSlidesTest {
    private fun edit(file: File, block: suspend EditInView.Host.() -> Unit) = EditInView.edit(file, block)
    private fun office(view: DocumentView) = EditInView.office(view)

    /** Waits until the view reports [page] of [count], then checks it stays there. */
    private suspend fun EditInView.Host.settle(page: Int, count: Int) {
        withTimeoutOrNull(15_000) {
            while (onMain { view.currentPage != page || view.pageCount != count }) delay(100)
        }
        delay(500)
        val seen = "events " + onMain { events.toList() } + " state " + onMain { office(view).state.value.let { "p" + it.pageNumber + "/" + it.pageCount } }
        assertEquals(seen, page, onMain { view.currentPage })
        assertEquals(seen, count, onMain { view.pageCount })
    }

    /** Slide part names in the order of the presentation. */
    private fun slideOrder(file: File): List<String> = ZipFile(file).use { zip ->
        fun text(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
        val rels = Regex("""<Relationship [^>]*?Id="(rId\d+)"[^>]*?Target="([^"]+)"""").findAll(text("ppt/_rels/presentation.xml.rels"))
            .associate { it.groupValues[1] to it.groupValues[2] }
        Regex("""<p:sldId [^>]*?r:id="(rId\d+)"""").findAll(text("ppt/presentation.xml")).map { rels.getValue(it.groupValues[1]) }.toList()
    }

    @Test
    fun arrangeSlides() {
        val file = OpenDocument.copySample("sample.pptx", "arrange.pptx")
        val before = slideOrder(file)
        edit(file) {
            settle(0, 10)
            onMain { view.goToPage(1) }
            settle(1, 10)

            // the second tap comes while the first move reopens the document
            assertTrue(onMain { editor.run(EditAction.SLIDE_DOWN) })
            assertFalse(onMain { editor.run(EditAction.SLIDE_DOWN) })
            settle(2, 10)
            assertTrue(onMain { editor.run(EditAction.SLIDE_DOWN) })
            settle(3, 10)

            // the move is reported even though the page count stayed the same
            assertEquals(3 to 10, events.last())

            assertTrue(onMain { editor.run(EditAction.ADD_SLIDE) })
            settle(4, 11)
            assertTrue(onMain { editor.run(EditAction.DELETE_SLIDE) })
            settle(3, 10)

            assertTrue(onMain { editor.save() })
        }
        val after = slideOrder(file)
        assertEquals(before[1], after[3])
        assertEquals(before.toMutableList().apply { add(3, removeAt(1)) }, after)
    }

    @Test
    fun undoSlideChanges() {
        val file = OpenDocument.copySample("sample.pptx", "undo-slides.pptx")
        val before = slideOrder(file)
        edit(file) {
            settle(0, 10)
            onMain { view.goToPage(1) }
            settle(1, 10)
            assertFalse(onMain { editor.isEnabled(EditAction.UNDO) })

            // move, delete, add: each reopens the document
            assertTrue(onMain { editor.run(EditAction.SLIDE_DOWN) })
            settle(2, 10)
            assertTrue(onMain { editor.run(EditAction.DELETE_SLIDE) })
            settle(1, 9)
            assertTrue(onMain { editor.run(EditAction.ADD_SLIDE) })
            settle(2, 10)

            // undone one by one, each back on the slide it was done on
            assertTrue(onMain { editor.isEnabled(EditAction.UNDO) })
            assertTrue(onMain { editor.run(EditAction.UNDO) })
            settle(1, 9)
            assertTrue(onMain { editor.run(EditAction.UNDO) })
            settle(2, 10)
            assertTrue(onMain { editor.run(EditAction.UNDO) })
            settle(1, 10)
            assertFalse("nothing left to undo", onMain { editor.isEnabled(EditAction.UNDO) })

            // redone in order
            assertTrue(onMain { editor.isEnabled(EditAction.REDO) })
            assertTrue(onMain { editor.run(EditAction.REDO) })
            settle(2, 10)
            assertTrue(onMain { editor.run(EditAction.REDO) })
            settle(1, 9)
            assertTrue(onMain { editor.run(EditAction.REDO) })
            settle(2, 10)
            assertFalse("nothing left to redo", onMain { editor.isEnabled(EditAction.REDO) })

            // a new change after an undo drops what was undone
            assertTrue(onMain { editor.run(EditAction.UNDO) })
            settle(1, 9)
            assertTrue(onMain { editor.run(EditAction.SLIDE_UP) })
            settle(0, 9)
            assertFalse(onMain { editor.isEnabled(EditAction.REDO) })

            // back to the start: the saved file has the slides of the original, in order
            while (onMain { editor.isEnabled(EditAction.UNDO) }) {
                assertTrue(onMain { editor.run(EditAction.UNDO) })
                withTimeoutOrNull(15_000) { while (onMain { !editor.isEnabled(EditAction.UNDO) && !editor.isEnabled(EditAction.REDO) }) delay(100) }
                delay(300)
            }
            settle(1, 10)
            assertTrue(onMain { editor.save() })
        }
        assertEquals(before, slideOrder(file))
    }

    @Test
    fun lastSlideStays() {
        val file = OpenDocument.output("one.pptx")
        assertTrue(DocumentCreator.create(InstrumentationRegistry.getInstrumentation().targetContext, DocumentCreator.Format.POWERPOINT, file) is EditResult.Ok)
        assertEquals(1, slideOrder(file).size)
        edit(file) {
            settle(0, 1)
            onMain { editor.run(EditAction.DELETE_SLIDE) }
            delay(1000)
            settle(0, 1)
            assertFalse(onMain { editor.hasUnsavedChanges() })
        }
        assertEquals(1, slideOrder(file).size)
    }

    /** Double taps the top text shape of the first slide, as the user does; the shape. */
    private suspend fun EditInView.Host.doubleTapText(file: File): com.wxiwei.office.editor.pptx.PptxShapeInfo {
        // a text shape on top at its own centre
        val shapes = com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0)
        val shape = shapes.first { s ->
            val r = s.rectEmu
            s.kind == com.wxiwei.office.editor.pptx.ShapeKind.TEXT &&
                com.wxiwei.office.editor.slide.SlideGeometry.pick(shapes, com.wxiwei.office.editor.pptx.Point(r.x + r.width / 2, r.y + r.height / 2)) == s
        }
        val (x, y) = onMain {
            val p = office(view).control!!.getView() as com.wxiwei.office.pg.control.Presentation
            val r = com.wxiwei.office.editor.slide.SlideGeometry.emuToView(p, shape.rectEmu)!!
            val origin = IntArray(2).also { p.getLocationOnScreen(it) }
            (origin[0] + r.centerX()) to (origin[1] + r.centerY())
        }
        // nothing selected yet: two quick taps
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun touch(action: Int, down: Long, at: Long) = android.view.MotionEvent.obtain(down, at, action, x, y, 0).also {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(it, true)
            it.recycle()
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        touch(android.view.MotionEvent.ACTION_DOWN, t0, t0)
        touch(android.view.MotionEvent.ACTION_UP, t0, t0 + 40)
        touch(android.view.MotionEvent.ACTION_DOWN, t0 + 80, t0 + 80)
        touch(android.view.MotionEvent.ACTION_UP, t0 + 80, t0 + 120)
        delay(1000)
        return shape
    }

    @Test
    fun doubleTapEditsText() {
        val file = OpenDocument.copySample("sample.pptx", "doubletap.pptx")
        edit(file) {
            settle(0, 10)
            delay(500)
            val shape = doubleTapText(file)
            val editText = onMain {
                fun find(v: android.view.View): android.widget.EditText? = when (v) {
                    is android.widget.EditText -> v
                    is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                    else -> null
                }
                find(view)
            }
            assertTrue("a double tap on a text shape opens the text editor", editText != null)
            assertEquals(shape.text, onMain { editText!!.text.toString() })
        }
    }

    @Test
    fun formatTextRangeAndFont() {
        val file = OpenDocument.copySample("sample.pptx", "format-range.pptx")
        val format = com.editor.docsdk.TextFormat(bold = true, color = "C00000", font = "Times New Roman")
        var part = ""
        var shapeId = 0
        edit(file) {
            settle(0, 10)
            delay(500)
            val shape = doubleTapText(file)
            shapeId = shape.id
            assertEquals(shape.text, onMain { editor.selectedText })
            part = shape.text.substring(0, 4)

            // the app formats a range: the text editor stays open with that part selected
            assertTrue(onMain { editor.formatText(0, 4, format) })
            delay(500)
            assertEquals(0 until 4, onMain { editor.textSelection })
            assertTrue(onMain { editor.isActive(EditAction.BOLD) })
            // commands act on the selected part
            assertTrue(onMain { editor.run(EditAction.ITALIC) })
            delay(300)
            assertTrue(onMain { editor.run(EditAction.FONT, "Courier New") })
            delay(300)
            // a picture of the slide as formatted, kept after the test: adb pull /data/local/tmp/pptx-format.png
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/pptx-format.png").close()
            delay(1000)
            assertEquals(0 until 4, onMain { editor.textSelection })
            // undo the font: back to Times New Roman; redo
            assertTrue(onMain { editor.run(EditAction.UNDO) })
            assertTrue(onMain { editor.run(EditAction.REDO) })
            // out of the text, or no shape: refused
            assertFalse(onMain { editor.formatText(0, shape.text.length + 5, format) })
            assertTrue(onMain { editor.save() })
        }
        val xml = ZipFile(file).use { z -> z.getInputStream(z.getEntry("ppt/slides/slide1.xml")).readBytes().toString(Charsets.UTF_8) }
        val sp = xml.substring(Regex("<p:cNvPr[^>]*\\bid=\"$shapeId\"").find(xml)!!.range.first).substringBefore("</p:sp>")
        val runs = Regex("<a:r><a:rPr([^>]*)>(.*?)</a:rPr><a:t>([^<]*)</a:t></a:r>").findAll(sp).toList()
        val styled = runs.filter { it.groupValues[2].contains("typeface=\"Courier New\"") }
        assertEquals(sp, part, styled.joinToString("") { it.groupValues[3] })
        for (r in styled) {
            val (attrs, props) = r.groupValues[1] to r.groupValues[2]
            assertTrue(attrs, attrs.contains("b=\"1\"") && attrs.contains("i=\"1\""))
            // CT_TextCharacterProperties order: the fill before the fonts, latin before cs
            assertTrue(props, props.indexOf("C00000") in 0 until props.indexOf("<a:latin"))
            assertTrue(props, props.indexOf("<a:latin") < props.indexOf("<a:cs"))
        }
        assertTrue("the rest keeps its font", runs.filter { it !in styled }.none { it.groupValues[2].contains("Courier New") })
    }

    @Test
    fun formatBarOverKeyboard() {
        val file = OpenDocument.copySample("sample.pptx", "format-bar.pptx")
        var shapeId = 0
        var part = ""
        edit(file) {
            settle(0, 10)
            delay(500)
            val shape = doubleTapText(file)
            shapeId = shape.id
            part = shape.text.substring(0, 4)
            fun find(v: View, match: (View) -> Boolean): View? = if (match(v)) v else (v as? android.view.ViewGroup)?.let { g ->
                (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it), match) } }
            val edit = onMain { find(view) { it is android.widget.EditText } as android.widget.EditText }
            // the bar is up, over the keyboard, under the editor
            assertTrue("format bar shown", onMain { find(view) { it.tag == "UNDERLINE" }?.isShown == true })
            onMain { edit.setSelection(0, 4) }
            delay(300)
            onMain { find(view) { it.tag == "UNDERLINE" }!!.performClick() }
            delay(300)
            // the editor stays open, same part selected, the bar lit
            assertEquals(0 until 4, onMain { editor.textSelection })
            assertTrue(onMain { editor.isActive(EditAction.UNDERLINE) })
            // + steps the size up; the size shows the new value
            val before = onMain { (find(view) { it.tag == "FONT_SIZE" } as android.widget.TextView).text.toString() }
            onMain { find(view) { (it as? android.widget.TextView)?.text == "+" }!!.performClick() }
            delay(300)
            val after = onMain { (find(view) { it.tag == "FONT_SIZE" } as android.widget.TextView).text.toString() }
            assertTrue("size $before -> $after", after.toFloat() > before.toFloat())
            // a caret without a selection: the word at the caret
            onMain { edit.setSelection(shape.text.length - 1) }
            onMain { find(view) { it.tag == "ITALIC" }!!.performClick() }
            delay(300)
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/pptx-bar.png").close()
            delay(1000)
            assertTrue(onMain { editor.save() })
        }
        val xml = ZipFile(file).use { z -> z.getInputStream(z.getEntry("ppt/slides/slide1.xml")).readBytes().toString(Charsets.UTF_8) }
        val sp = xml.substring(Regex("<p:cNvPr[^>]*\\bid=\"$shapeId\"").find(xml)!!.range.first).substringBefore("</p:sp>")
        val runs = Regex("<a:r><a:rPr([^>]*)>.*?<a:t>([^<]*)</a:t></a:r>").findAll(sp).toList()
        assertEquals(sp, part, runs.filter { it.groupValues[1].contains("u=\"sng\"") }.joinToString("") { it.groupValues[2] })
        val lastWord = runs.filter { it.groupValues[1].contains("i=\"1\"") }.joinToString("") { it.groupValues[2] }
        assertTrue("italic word: '$lastWord'", lastWord.isNotBlank() && part !in lastWord)
    }

    @Test
    fun formatBarOnNewTextBox() {
        val file = OpenDocument.output("format-bar-new.pptx")
        assertTrue(DocumentCreator.create(InstrumentationRegistry.getInstrumentation().targetContext, DocumentCreator.Format.POWERPOINT, file) is EditResult.Ok)
        // the app's own look of a button stays when it is lit
        val styled = android.graphics.drawable.ColorDrawable(0x3300FF00)
        com.editor.docsdk.EditStyle.customizer = com.editor.docsdk.EditStyle.Customizer { _, style ->
            style.copy(itemStyler = com.editor.docsdk.EditStyle.ItemStyler { b, a -> if (a == EditAction.BOLD) b.background = styled })
        }
        try {
            edit(file) {
                settle(0, 1)
                assertTrue(onMain { editor.run(EditAction.ADD_TEXT_BOX) })
                delay(300)
                withTimeoutOrNull(20_000) { while (onMain { editor.panel.isBusy }) delay(100) }
                delay(1500)
                val panel = editor.panel
                val session = onMain { panel.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(panel) as com.wxiwei.office.editor.pptx.LivePptxSession }
                val shape = onMain { session.listShapes(0).last() }
                val (x, y) = onMain {
                    val p = office(view).control!!.getView() as com.wxiwei.office.pg.control.Presentation
                    val r = com.wxiwei.office.editor.slide.SlideGeometry.emuToView(p, shape.rectEmu)!!
                    val o = IntArray(2).also { p.getLocationOnScreen(it) }
                    (o[0] + r.centerX()) to (o[1] + r.centerY())
                }
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                fun touch(action: Int, down: Long, at: Long) = android.view.MotionEvent.obtain(down, at, action, x, y, 0).also {
                    it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle() }
                val t0 = android.os.SystemClock.uptimeMillis()
                touch(android.view.MotionEvent.ACTION_DOWN, t0, t0); touch(android.view.MotionEvent.ACTION_UP, t0, t0 + 40)
                touch(android.view.MotionEvent.ACTION_DOWN, t0 + 80, t0 + 80); touch(android.view.MotionEvent.ACTION_UP, t0 + 80, t0 + 120)
                delay(1200)
                fun find(v: View, match: (View) -> Boolean): View? = if (match(v)) v else (v as? android.view.ViewGroup)?.let { g ->
                    (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it), match) } }
                val edit = onMain { find(view) { it is android.widget.EditText } as android.widget.EditText }
                // "box" of "Text box" in italic
                onMain { edit.setSelection(5, 8) }
                delay(300)
                onMain { find(view) { it.tag == "ITALIC" }!!.performClick() }
                delay(500)
                automation.executeShellCommand("screencap -p /data/local/tmp/pptx-bar-new.png").close()
                delay(800)
                val run = onMain { session.textRuns(0, shape.id).first { 6 in it.first }.second }
                assertTrue("drawn italic", run.typeface.isItalic || run.fake and android.graphics.Typeface.ITALIC != 0)
                assertTrue("the editor shows it", onMain { edit.paint.textSkewX != 0f || edit.text.let { t ->
                    (t as android.text.Spanned).getSpans(5, 8, android.text.style.MetricAffectingSpan::class.java).isNotEmpty() } })
                // buttons apart, not overlapping
                val (b, i) = onMain { find(view) { it.tag == "BOLD" }!! to find(view) { it.tag == "ITALIC" }!! }
                assertTrue("a gap between buttons", onMain { i.left - b.right } > 0)
                assertTrue(onMain { i.isSelected })
                assertTrue("the styler's background stays", onMain { b.background } === styled)
            }
        } finally {
            com.editor.docsdk.EditStyle.customizer = null
        }
    }
}
