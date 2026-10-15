/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import android.content.ClipData
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
import java.util.zip.ZipOutputStream

/** Cut, copy, paste, delete, undo and redo in Word through the editor's commands, as the app runs them. */
@RunWith(AndroidJUnit4::class)
class WordClipboardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** A .docx of [paragraphs]. */
    private fun docx(name: String, vararg paragraphs: String): File {
        val file = OpenDocument.output(name)
        val body = paragraphs.joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">$it</w:t></w:r></w:p>" }
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
        d.getText(0, d.getAreaEnd(0)).replace('\r', '\n').trimEnd('\n')
    }

    /** A tap on the document at [offset], as the user puts the caret there; [holdMs] long: a long press. */
    private suspend fun tapAt(host: EditInView.Host, offset: Long, holdMs: Long = 40) {
        // the pages are laid out after the document opens
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
        touchScreen(x, y, holdMs)
        // the tap is confirmed once no second tap came
        delay(800)
    }

    /** A finger down and up at a screen point, held [holdMs]. */
    private suspend fun touchScreen(x: Float, y: Float, holdMs: Long = 40) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val t = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + holdMs)) {
            if (action == MotionEvent.ACTION_UP && holdMs > 100) delay(holdMs)
            MotionEvent.obtain(t, at, action, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle() }
        }
    }

    private fun run(host: EditInView.Host, action: EditAction): Boolean = onMain { host.editor.run(action) }

    @Test
    fun cutCopyPasteUndo() {
        val file = docx("clipboard.docx", "First paragraph here.", "Second paragraph here.", "Third one.")
        val original = "First paragraph here.\nSecond paragraph here.\nThird one."
        EditInView.edit(file) {
            delay(1000)
            assertEquals(original, text(this))

            // caret in the second paragraph, Review → select paragraph, Cut
            tapAt(this, 25)
            assertTrue(run(this, EditAction.SELECT_PARAGRAPH))
            assertEquals("Second paragraph here.", onMain { WordSelection(word(this)).selectedText() })
            assertTrue(run(this, EditAction.CUT))
            delay(500)
            assertEquals("cut", "First paragraph here.\n\nThird one.", text(this))

            // caret at the start, Paste
            tapAt(this, 0)
            assertTrue(run(this, EditAction.PASTE))
            delay(500)
            assertEquals("paste at the start", "Second paragraph here.First paragraph here.\n\nThird one.", text(this))

            // a word copied, pasted twice somewhere else
            tapAt(this, text(this).indexOf("Third").toLong() + 1)
            assertTrue(run(this, EditAction.SELECT_WORD))
            assertTrue(run(this, EditAction.COPY))
            tapAt(this, text(this).indexOf("one.").toLong() + 4)
            assertTrue(run(this, EditAction.PASTE))
            assertTrue(run(this, EditAction.PASTE))
            delay(500)
            assertTrue("pasted twice: " + text(this), text(this).endsWith("Third one.ThirdThird"))

            // text copied in another app
            onMain { clipboard.setPrimaryClip(ClipData.newPlainText("note", "From notes.")) }
            tapAt(this, 0)
            assertTrue(run(this, EditAction.PASTE))
            delay(500)
            assertTrue("pasted from another app: " + text(this), text(this).startsWith("From notes."))

            // select all, delete, then undo
            val before = text(this)
            assertTrue(run(this, EditAction.SELECT_ALL))
            assertTrue(run(this, EditAction.DELETE))
            delay(500)
            assertEquals("delete all", "", text(this))
            assertTrue(run(this, EditAction.UNDO))
            delay(500)
            assertEquals("undo the delete", before, text(this))

            // undo everything: back to the file as it opened, then Undo turns off
            val states = mutableListOf(text(this))
            var steps = 0
            while (onMain { editor.isEnabled(EditAction.UNDO) } && steps++ < 50) {
                assertTrue(run(this, EditAction.UNDO))
                delay(300)
                states.add(text(this))
            }
            assertEquals("undo all", original, text(this))
            run(this, EditAction.UNDO) // once more: nothing happens
            assertEquals(original, text(this))

            // redo everything: the same states again, in order, then Redo turns off
            assertTrue(onMain { editor.isEnabled(EditAction.REDO) })
            val redone = mutableListOf(text(this))
            while (onMain { editor.isEnabled(EditAction.REDO) } && redone.size < 60) {
                assertTrue(run(this, EditAction.REDO))
                delay(300)
                redone.add(text(this))
            }
            // the states undone, in the opposite order, then the delete undone first
            assertEquals("redo walks back the undo steps", states.reversed() + "", redone)
        }
    }

    /** The node showing [label] in any window on screen. */
    private fun onScreen(label: String): android.view.accessibility.AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        return automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }
            .firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    }

    @Test
    fun contextBarOnSelection() {
        val original = "First paragraph here.\nSecond paragraph here.\nThird one."
        val file = docx("menu.docx", "First paragraph here.", "Second paragraph here.", "Third one.")
        val cut = context.getString(EditAction.CUT.label)
        val copy = context.getString(EditAction.COPY.label)
        EditInView.edit(file) {
            delay(1000)
            // a long press selects the word "Second": Cut and Copy come up on the context bar
            tapAt(this, original.indexOf("Second").toLong() + 2, holdMs = 700)
            delay(800)
            assertEquals("Second", onMain { WordSelection(word(this)).selectedText() })
            // a picture of the menu, kept after the test: adb pull /data/local/tmp/word-menu.png
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/word-menu.png").close()
            delay(1500)
            assertTrue("Copy on the bar", onScreen(copy) != null)
            val item = onScreen(cut)
            assertTrue("Cut on the bar", item != null)
            // a finger on the item, as the user does
            val box = android.graphics.Rect().also { item!!.getBoundsInScreen(it) }
            touchScreen(box.exactCenterX(), box.exactCenterY())
            delay(800)
            assertEquals(original.replace("Second", ""), text(this))
            assertEquals("Second", onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() })
            // the selection is gone: nothing to copy
            assertFalse("Copy off", onMain { editor.isEnabled(EditAction.COPY) })
        }
    }
}
