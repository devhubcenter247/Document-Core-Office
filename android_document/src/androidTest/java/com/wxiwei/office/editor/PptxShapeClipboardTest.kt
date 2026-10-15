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
import com.wxiwei.office.editor.pptx.PptxEditor
import com.wxiwei.office.editor.pptx.PptxShapeInfo
import com.wxiwei.office.editor.pptx.ShapeKind
import com.wxiwei.office.editor.slide.SlideGeometry
import com.wxiwei.office.pg.control.Presentation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Shapes of a slide copied, cut and pasted on the same slide and on another, undone and saved. */
@RunWith(AndroidJUnit4::class)
class PptxShapeClipboardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** The top shape of [kind] at its own centre on slide [slide] of [file], as the user would tap it. */
    private fun topShape(file: File, slide: Int, kind: ShapeKind): PptxShapeInfo {
        val shapes = PptxEditor(file).listShapes(slide)
        return shapes.first { s ->
            val r = s.rectEmu
            s.kind == kind && SlideGeometry.pick(shapes, com.wxiwei.office.editor.pptx.Point(r.x + r.width / 2, r.y + r.height / 2)) == s
        }
    }

    /** One tap on the middle of [shape], then the wait for the tap to be confirmed. */
    private suspend fun tap(host: EditInView.Host, shape: PptxShapeInfo) {
        val (x, y) = onMain {
            val p = EditInView.office(host.view).control!!.getView() as Presentation
            val r = SlideGeometry.emuToView(p, shape.rectEmu)!!
            val o = IntArray(2).also { p.getLocationOnScreen(it) }
            (o[0] + r.centerX()) to (o[1] + r.centerY())
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val t = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 40)) {
            MotionEvent.obtain(t, at, action, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle() }
        }
        delay(900)
    }

    private fun run(host: EditInView.Host, action: EditAction): Boolean = onMain { host.editor.run(action) }

    /** Waits for the document to be read again after a paste or an undo of one. */
    private suspend fun reopened(host: EditInView.Host) {
        delay(300)
        withTimeout(20_000) { while (onMain { host.editor.panel.isBusy }) delay(100) }
        delay(1000)
    }

    /** The shapes of [slide] as the editor has them now (queued edits included). */
    private fun shapes(host: EditInView.Host, slide: Int): List<PptxShapeInfo> = onMain {
        val panel = host.editor.panel
        val f = panel.javaClass.getDeclaredField("session").apply { isAccessible = true }
        (f.get(panel) as com.wxiwei.office.editor.pptx.LivePptxSession).listShapes(slide)
    }

    @Test
    fun copyPasteShapes() {
        val file = OpenDocument.copySample("sample.pptx", "shape-clip.pptx")
        val text = topShape(file, 0, ShapeKind.TEXT)
        val before0 = PptxEditor(file).listShapes(0).size
        val before1 = PptxEditor(file).listShapes(1).size
        EditInView.edit(file) {
            withTimeout(15_000) { while (onMain { view.pageCount } < 10) delay(100) }
            delay(800)
            // tap a text box: it is selected and the menu comes up over it
            tap(this, text)
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/pptx-shape-menu.png").close()
            delay(800)
            assertTrue(run(this, EditAction.COPY))
            assertEquals("its text for other apps", text.text, onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() })

            // paste on the same slide: a copy a little lower and to the right
            assertTrue(run(this, EditAction.PASTE))
            reopened(this)
            val same = shapes(this, 0)
            assertEquals("one more shape", before0 + 1, same.size)
            val pasted = same.last()
            assertEquals(text.text, pasted.text)
            assertTrue("moved off the original", pasted.rectEmu.x > text.rectEmu.x && pasted.rectEmu.y > text.rectEmu.y)
            assertEquals(text.rectEmu.width, pasted.rectEmu.width)

            // on another slide: at the same place
            onMain { view.goToPage(1) }
            delay(1200)
            assertTrue(run(this, EditAction.PASTE))
            reopened(this)
            val other = shapes(this, 1)
            assertEquals(before1 + 1, other.size)
            assertEquals(text.rectEmu, other.last().rectEmu)

            // undo the last paste, redo it
            assertTrue(run(this, EditAction.UNDO))
            reopened(this)
            assertEquals(before1, shapes(this, 1).size)
            assertTrue(run(this, EditAction.REDO))
            reopened(this)
            assertEquals(before1 + 1, shapes(this, 1).size)
            assertFalse(onMain { editor.isEnabled(EditAction.REDO) })
            assertTrue(onMain { editor.save() })
        }
        val saved = PptxEditor(file)
        assertEquals(before0 + 1, saved.listShapes(0).size)
        assertEquals(before1 + 1, saved.listShapes(1).size)
        // every shape id of a slide is still unique
        for (i in 0..1) saved.listShapes(i).map { it.id }.let { assertEquals(it.size, it.toSet().size) }
    }

    @Test
    fun copyPastePicture() {
        val file = OpenDocument.copySample("sample.pptx", "picture-clip.pptx")
        val slide = (0 until PptxEditor(file).slideCount()).first { i -> runCatching { topShape(file, i, ShapeKind.PICTURE) }.isSuccess }
        val picture = topShape(file, slide, ShapeKind.PICTURE)
        val pictures = PptxEditor(file).listShapes(slide).count { it.kind == ShapeKind.PICTURE }
        EditInView.edit(file) {
            withTimeout(15_000) { while (onMain { view.pageCount } < 10) delay(100) }
            onMain { view.goToPage(slide) }
            delay(1500)
            tap(this, picture)
            assertTrue(run(this, EditAction.COPY))
            assertTrue(run(this, EditAction.PASTE))
            reopened(this)
            assertTrue(onMain { editor.save() })
        }
        assertEquals("a second picture", pictures + 1, PptxEditor(file).listShapes(slide).count { it.kind == ShapeKind.PICTURE })
        // the copy has its own picture, which the file holds
        val reopened = OpenDocument.output("picture-clip-check.pptx").also { file.copyTo(it, overwrite = true) }
        assertTrue(PptxEditor(reopened).save(OpenDocument.output("picture-clip-check2.pptx")) is EditResult.Ok)
    }
}
