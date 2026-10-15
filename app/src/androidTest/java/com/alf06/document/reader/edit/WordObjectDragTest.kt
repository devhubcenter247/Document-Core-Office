package com.alf06.document.reader.edit

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alf06.document.reader.R
import com.alf06.document.reader.model.DocumentType
import com.alf06.document.reader.model.RecentDocument
import com.alf06.document.reader.ui.home.document.office.ReadDocumentActivity
import com.wxiwei.office.constant.wp.WPModelConstant
import com.wxiwei.office.editor.docx.DocxSourceMap
import com.wxiwei.office.editor.word.WordSelection
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.reader.ReaderState
import com.wxiwei.office.wp.control.Word
import com.wxiwei.office.wp.model.WPDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Word pictures and tables moved and resized with real touches on sample.docx: a long press then
 * a drag, a tap then a drag, a drag of a corner. Screenshots go to files/edit-ui on the device.
 */
@RunWith(AndroidJUnit4::class)
class WordObjectDragTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val shots = File(context.getExternalFilesDir(null), "edit-ui").apply { mkdirs() }

    private fun sample(name: String): File {
        val file = File(context.filesDir, "drag-test-$name")
        instrumentation.context.assets.open("samples/$name").use { i -> file.outputStream().use { i.copyTo(it) } }
        return file
    }

    private fun screenshot(name: String) {
        Thread.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out")
    }

    private fun <T : View> find(root: View, match: (View) -> Boolean): T? {
        if (match(root)) @Suppress("UNCHECKED_CAST") return root as T
        if (root is ViewGroup) for (i in 0 until root.childCount) find<T>(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // a finger left down by a test that failed half way makes the system refuse every touch after it
        assertTrue("touch injected", instrumentation.uiAutomation.injectInputEvent(event, true))
        event.recycle()
    }

    /** A test that failed with the finger down: lift it, or the system refuses the touches of every test after. */
    @org.junit.After
    fun liftFinger() {
        val t = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    /** A finger down at (x, y), held [holdMs], moved by (dx, dy) in steps, then lifted. */
    private fun drag(x: Float, y: Float, dx: Float, dy: Float, holdMs: Long) {
        val t = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, t)
        Thread.sleep(holdMs)
        val steps = 20
        for (i in 1..steps) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, x + dx * i / steps, y + dy * i / steps, t) }
        Thread.sleep(100)
        inject(MotionEvent.ACTION_UP, x + dx, y + dy, t)
        Thread.sleep(2500)
    }

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, t)
        inject(MotionEvent.ACTION_UP, x, y, t)
        Thread.sleep(1200)
    }

    private fun open(file: File, body: (ActivityScenario<ReadDocumentActivity>, OfficeDocumentView) -> Unit) {
        val intent = Intent(context, ReadDocumentActivity::class.java)
            .putExtra(ReadDocumentActivity.ARG_DOCUMENT, RecentDocument(path = file.absolutePath, size = file.length(), type = DocumentType.Doc))
        ActivityScenario.launch<ReadDocumentActivity>(intent).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(800)
            body(scenario, viewer)
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick() }
            Thread.sleep(2500)
        }
    }

    private fun word(viewer: OfficeDocumentView) = viewer.control!!.getView() as Word

    /** Scrolls so the [box] (Word view coordinates) is in the upper part of the view; the box on the screen. */
    private fun reveal(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView, box: (WordSelection) -> Rect?): Rect {
        var out = Rect()
        scenario.onActivity {
            val w = word(viewer)
            val r = box(WordSelection(w))!!
            w.scrollTo(w.scrollX, maxOf(0, w.scrollY + r.top - w.height / 5))
            w.postInvalidate()
        }
        Thread.sleep(800)
        scenario.onActivity {
            val w = word(viewer)
            val r = box(WordSelection(w))!!
            val o = IntArray(2); w.getLocationOnScreen(o)
            out = Rect(r).apply { offset(o[0], o[1]) }
        }
        android.util.Log.i("DragTest", "box on screen $out")
        return out
    }

    /** An SDK text in the app's language. */
    private fun sdk(scenario: ActivityScenario<ReadDocumentActivity>, id: Int): String {
        var text = ""
        scenario.onActivity { text = it.getString(id) }
        return text
    }

    private fun label(scenario: ActivityScenario<ReadDocumentActivity>): String {
        var text = ""
        scenario.onActivity { a ->
            val panel = a.findViewById<ViewGroup>(R.id.editPanel)
            text = find<TextView>(panel) { it is TextView && it.tag == com.wxiwei.office.editor.ui.EditToolbar.STATUS }?.text?.toString().orEmpty()
        }
        return text
    }

    private fun pictureOffset(file: File): Long {
        val map = DocxSourceMap.get(file.absolutePath)!!
        return (0 until map.size).map { map.leaf(it) }.first { it.kind == DocxSourceMap.Kind.OBJECT && it.start < WPModelConstant.HEADER }.start
    }

    private fun xml(file: File) = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }

    /** The text before the picture's run in the saved body: where the picture now sits. */
    private fun textBeforePicture(file: File): String {
        val x = xml(file)
        val before = x.substring(0, x.indexOf("<w:drawing>"))
        return Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(before).joinToString("") { it.groupValues[1] }
    }

    /** The picture moved live (same view) and its line is where the finger was lifted ([liftY] on the screen). */
    private fun landedAt(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView, before: Any?, liftY: Float) {
        scenario.onActivity {
            val w = word(viewer)
            assertTrue("shown in place, not by reopening the document", w === before)
        }
        scenario.onActivity {
            val w = word(viewer)
            val doc = w.getDocument()
            // the picture's one-char object: the leaf with a shape
            var found: Rect? = null
            var o = 0L
            val end = (doc as WPDocument).getAreaEnd(0)
            while (o < end && found == null) {
                val leaf = doc.getLeaf(o) ?: run { o++; null } ?: continue
                if (leaf.getEndOffset() - leaf.getStartOffset() == 1L && com.wxiwei.office.simpletext.model.AttrManage.instance().getShapeID(leaf.getAttribute()) >= 0) {
                    found = WordSelection(w).inlineObjectRect(o)
                }
                o = maxOf(o + 1, leaf.getEndOffset())
            }
            val r = found!!
            val at = IntArray(2); w.getLocationOnScreen(at); r.offset(at[0], at[1])
            android.util.Log.i("DragTest", "moved: lifted at y=$liftY, picture now $r")
            // an in-line picture sits on its line: the lift point is on that line (the picture, or the text next to it)
            assertTrue("picture landed where the finger was lifted: $r vs $liftY", liftY >= r.top - 80 && liftY <= r.bottom + 80)
        }
    }

    /** The table frame (with its handles) is shown over the document. */
    private fun tableFrameShown(scenario: ActivityScenario<ReadDocumentActivity>): Boolean {
        var shown = false
        scenario.onActivity { a ->
            val o = find<View>(a.findViewById(R.id.officeViewer)) { it.javaClass.simpleName == "WordPictureOverlay" } ?: return@onActivity
            val active = o.javaClass.getDeclaredMethod("getActive").invoke(o) as Boolean
            val resizable = o.javaClass.getDeclaredMethod("getResizable").invoke(o) as Boolean
            shown = active && !resizable
        }
        return shown
    }

    private fun scrollY(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView): Int {
        var y = 0
        scenario.onActivity { y = word(viewer).scrollY }
        return y
    }

    @Test
    fun pictureLongPressDragMoves() {
        val file = sample("sample.docx")
        val beforeText = textBeforePicture(file)
        open(file) { scenario, viewer ->
            val at = pictureOffset(file)
            val box = reveal(scenario, viewer) { it.inlineObjectRect(at) }
            screenshot("picture_lp_before")
            val scroll = scrollY(scenario, viewer)
            var view: Any? = null
            scenario.onActivity { view = word(viewer) }
            // onto the line "— Hết tài liệu —" just above the picture, near its start
            val liftX = box.left + 60f
            val liftY = box.top - 25f
            drag(box.exactCenterX(), box.exactCenterY(), liftX - box.exactCenterX(), liftY - box.exactCenterY(), holdMs = 900)
            screenshot("picture_lp_after")
            landedAt(scenario, viewer, view, liftY)
            android.util.Log.i("DragTest", "long press drag: scroll $scroll -> ${scrollY(scenario, viewer)} label='${label(scenario)}'")
            assertTrue("view stays near the picture: $scroll -> ${scrollY(scenario, viewer)}", Math.abs(scrollY(scenario, viewer) - scroll) < box.height() + 400)
        }
        val after = textBeforePicture(file)
        assertTrue("picture moved up: '${beforeText.takeLast(40)}' -> '${after.takeLast(40)}'", after.length < beforeText.length)
        assertTrue("into the line above: '${after.takeLast(40)}'", !after.endsWith("Hết tài liệu —"))
    }

    @Test
    fun pictureTapThenDragMoves() {
        val file = sample("sample.docx")
        val beforeText = textBeforePicture(file)
        open(file) { scenario, viewer ->
            val at = pictureOffset(file)
            val box = reveal(scenario, viewer) { it.inlineObjectRect(at) }
            tap(box.exactCenterX(), box.exactCenterY())
            screenshot("picture_tap_selected")
            assertTrue("selected: '${label(scenario)}'", label(scenario) == sdk(scenario, com.wxiwei.office.R.string.docsdk_edit_picture_selected_hint))
            val scroll = scrollY(scenario, viewer)
            var view: Any? = null
            scenario.onActivity { view = word(viewer) }
            // onto the line "— Hết tài liệu —" just above the picture, near its start
            val liftX = box.left + 60f
            val liftY = box.top - 25f
            drag(box.exactCenterX(), box.exactCenterY(), liftX - box.exactCenterX(), liftY - box.exactCenterY(), holdMs = 50)
            screenshot("picture_tap_dragged")
            landedAt(scenario, viewer, view, liftY)
            android.util.Log.i("DragTest", "tap drag: scroll $scroll -> ${scrollY(scenario, viewer)}")
            assertTrue("view stays near the picture: $scroll -> ${scrollY(scenario, viewer)}", Math.abs(scrollY(scenario, viewer) - scroll) < box.height() + 400)
        }
        val after = textBeforePicture(file)
        assertTrue("picture moved up", after.length < beforeText.length)
    }

    @Test
    fun pictureCornerResizes() {
        val file = sample("sample.docx")
        val extent = Regex("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"").find(xml(file))!!.groupValues
        open(file) { scenario, viewer ->
            val at = pictureOffset(file)
            val box = reveal(scenario, viewer) { it.inlineObjectRect(at) }
            scenario.onActivity {
                val shape = com.wxiwei.office.editor.docx.LiveDocxSession(viewer.control!!, file).shapeAt(at)
                android.util.Log.i("DragTest", "picture box $box shape=${shape?.javaClass?.simpleName} bounds=${shape?.bounds} zoom=${word(viewer).getZoom()}")
            }
            screenshot("picture_resize_before")
            tap(box.exactCenterX(), box.exactCenterY())
            screenshot("picture_resize_selected")
            var view: Any? = null
            scenario.onActivity { view = word(viewer) }
            // bottom-right corner, 120 px towards the inside: smaller
            drag(box.right.toFloat() - 2, box.bottom.toFloat() - 2, -120f, -120f, holdMs = 50)
            screenshot("picture_resized")
            scenario.onActivity {
                val w = word(viewer)
                assertTrue("shown in place, not by reopening the document", w === view)
                val r = WordSelection(w).inlineObjectRect(at)!!
                val o = IntArray(2); w.getLocationOnScreen(o); r.offset(o[0], o[1])
                android.util.Log.i("DragTest", "resize: frame ${box.width()}x${box.height()} dragged to ${box.width() - 120} wide; now $r (${r.width()}x${r.height()})")
                assertEquals("width follows the frame", (box.width() - 120).toFloat(), r.width().toFloat(), 3f)
                assertEquals("left edge stays", box.left.toFloat(), r.left.toFloat(), 3f)
                assertEquals("top edge stays", box.top.toFloat(), r.top.toFloat(), 3f)
            }
        }
        val now = Regex("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"").find(xml(file))!!.groupValues
        assertTrue("smaller: ${extent[1]}x${extent[2]} -> ${now[1]}x${now[2]}", now[1].toLong() < extent[1].toLong())
        // proportions kept (within rounding)
        val r0 = extent[1].toDouble() / extent[2].toDouble(); val r1 = now[1].toDouble() / now[2].toDouble()
        assertEquals(r0, r1, 0.03)
    }

    @Test
    fun tableLongPressDragMoves() {
        val file = sample("sample.docx")
        val original = xml(file)
        open(file) { scenario, viewer ->
            var start = 0L; var end = 0L
            scenario.onActivity {
                val doc = word(viewer).getDocument() as WPDocument
                // the second table: there is text above it to move it over
                val t = doc.getTableCollection(0)!!.getElementForIndex(1)!!
                start = t.getStartOffset(); end = t.getEndOffset()
            }
            val box = reveal(scenario, viewer) { it.tableRect(start, end) }
            screenshot("table_before")
            val scroll = scrollY(scenario, viewer)
            var view: Any? = null
            scenario.onActivity { view = word(viewer) }
            // long press: the table's frame shows at once
            val x = box.left + box.width() * 0.3f; val y = box.top + 25f
            val t = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, x, y, t)
            Thread.sleep(900)
            assertTrue("table frame shown", tableFrameShown(scenario))
            // then up by 150 px (not into the top edge, where the document would scroll), with a pause half way for a look at the drop line
            for (i in 1..20) {
                Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, x, y - 150f * i / 20, t)
                if (i == 20) screenshot("table_dragging")
            }
            inject(MotionEvent.ACTION_UP, x, y - 150f, t)
            Thread.sleep(2500)
            screenshot("table_after")
            scenario.onActivity { assertTrue("shown in place, not by reopening the document", word(viewer) === view) }
            android.util.Log.i("DragTest", "table drag: scroll $scroll -> ${scrollY(scenario, viewer)} label='${label(scenario)}'")
            assertTrue("view stays where it was: $scroll -> ${scrollY(scenario, viewer)}", Math.abs(scrollY(scenario, viewer) - scroll) < 150)
        }
        fun tables(x: String) = Regex("<w:tbl>").findAll(x).map { it.range.first }.toList()
        val saved = xml(file)
        assertEquals("same number of tables", tables(original).size, tables(saved).size)
        // the text right before the second table changed: it went above a paragraph
        fun textBefore(x: String, at: Int) = Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(x.substring(0, at)).joinToString("") { it.groupValues[1] }.takeLast(30)
        val was = textBefore(original, tables(original)[1]); val now = textBefore(saved, tables(saved)[1])
        android.util.Log.i("DragTest", "before the table: '$was' -> '$now'")
        assertTrue("table moved: '$was' -> '$now'", was != now)
    }

    /** A table held at the bottom edge while dragged: the document scrolls under it, and it lands pages further down. */
    @Test
    fun tableDragToAnotherPage() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            fun table(): LongRange {
                var out = 0L..0L
                scenario.onActivity {
                    val t = (word(viewer).getDocument() as WPDocument).getTableCollection(0)!!.getElementForIndex(1)!!
                    out = t.getStartOffset() until t.getEndOffset()
                }
                return out
            }
            val from = table()
            val box = reveal(scenario, viewer) { it.tableRect(from.first, from.last + 1) }
            val scroll = scrollY(scenario, viewer)
            var view: Any? = null
            var text = ""
            var bottom = 0f; var height = 0
            scenario.onActivity {
                val w = word(viewer); view = w
                text = w.getDocument().getText(from.first, from.last + 1)
                val o = IntArray(2); w.getLocationOnScreen(o)
                bottom = (o[1] + w.height).toFloat(); height = w.height
            }
            val x = box.left + box.width() * 0.3f; val y = box.top + 25f
            val t = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, x, y, t)
            Thread.sleep(900)
            // down to the bottom edge, held there while the document scrolls
            val edge = bottom - 20f
            for (i in 1..20) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, x, y + (edge - y) * i / 20, t) }
            val end = System.currentTimeMillis() + 15_000
            while (scrollY(scenario, viewer) - scroll < height && System.currentTimeMillis() < end) {
                Thread.sleep(50); inject(MotionEvent.ACTION_MOVE, x, edge, t)
            }
            val scrolled = scrollY(scenario, viewer) - scroll
            assertTrue("the document scrolled under the held table: $scrolled of $height", scrolled >= height)
            // back up into the page (the document stops scrolling), onto a paragraph that has one after it, and dropped
            val middle = edge - height * 0.5f
            for (i in 1..10) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, x, edge + (middle - edge) * i / 10, t) }
            Thread.sleep(400)
            var dropY = 0f
            scenario.onActivity {
                val w = word(viewer); val doc = w.getDocument() as WPDocument
                val sel = WordSelection(w)
                dropY = generateSequence(middle) { it - 40f }.takeWhile { it > edge - height * 0.85f }.first { sy ->
                    val block = sel.offsetAtScreen(x, sy).takeIf { it >= 0 }?.let { doc.getParagraph0(it) }
                    block is com.wxiwei.office.simpletext.model.ParagraphElement && block.getEndOffset() < doc.getAreaEnd(0) && block.getStartOffset() > from.last
                }
            }
            for (i in 1..10) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, x, middle + (dropY - middle) * i / 10, t) }
            screenshot("table_other_page_dragging")
            scenario.onActivity {
                val w = word(viewer); val doc = w.getDocument() as WPDocument
                val o = WordSelection(w).offsetAtScreen(x, dropY)
                val block = doc.getParagraph0(o)
                val now = doc.getTableCollection(0)!!.getElementForIndex(1)!!
                val next = block?.let { doc.getParagraph0(it.getEndOffset()) }
                android.util.Log.i("DragTest", "drop at offset $o of ${doc.getAreaEnd(0)}: ${block?.javaClass?.simpleName} ${block?.getStartOffset()}..${block?.getEndOffset()}, next ${next?.javaClass?.simpleName} ${next?.getStartOffset()}..${next?.getEndOffset()}, table $from now ${now.getStartOffset()}..${now.getEndOffset()}")
            }
            inject(MotionEvent.ACTION_UP, x, dropY, t)
            Thread.sleep(2500)
            android.util.Log.i("DragTest", "after drop: label='${label(scenario)}'")
            screenshot("table_other_page_after")
            scenario.onActivity { assertTrue("shown in place, not by reopening the document", word(viewer) === view) }
            // the same table, found again by its text (it is no longer the second one)
            var to = 0L..0L
            scenario.onActivity {
                val doc = word(viewer).getDocument() as WPDocument
                val c = doc.getTableCollection(0)!!
                to = (0 until c.size()).map { c.getElementForIndex(it)!! }.first { doc.getText(it.getStartOffset(), it.getEndOffset()) == text }
                    .let { it.getStartOffset() until it.getEndOffset() }
            }
            var rect: Rect? = null
            scenario.onActivity { rect = WordSelection(word(viewer)).tableRect(to.first, to.last + 1) }
            android.util.Log.i("DragTest", "table to another page: $from -> $to, now at $rect, label='${label(scenario)}'")
            assertTrue("the table went down the document: $from -> $to", to.first > from.first)
            assertTrue("it is where it was dropped, on the screen: $rect", rect != null && rect!!.bottom > 0 && rect!!.top < height)
        }
    }

    /** The first table: selected by a long press, its first column border dragged 90 px right, its first row's bottom 50 px down. */
    @Test
    fun tableColumnAndRowResize() {
        val file = sample("sample.docx")
        val grid0 = xml(file).let { x -> Regex("<w:gridCol w:w=\"(\\d+)\"").findAll(x.substring(x.indexOf("<w:tbl>"))).take(2).map { it.groupValues[1].toInt() }.toList() }
        open(file) { scenario, viewer ->
            var start = 0L; var end = 0L
            scenario.onActivity {
                val t = (word(viewer).getDocument() as WPDocument).getTableCollection(0)!!.getElementForIndex(0)!!
                start = t.getStartOffset(); end = t.getEndOffset()
            }
            val box = reveal(scenario, viewer) { it.tableRect(start, end) }
            // select it: a long press, lifted in place
            val t0 = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, box.left + box.width() * 0.3f, box.top + 25f, t0)
            Thread.sleep(900)
            inject(MotionEvent.ACTION_UP, box.left + box.width() * 0.3f, box.top + 25f, t0)
            Thread.sleep(600)
            assertTrue("table frame shown", tableFrameShown(scenario))
            screenshot("table_handles")
            var view: Any? = null
            fun guides(): com.wxiwei.office.editor.word.WordSelection.TableGuides {
                var g: com.wxiwei.office.editor.word.WordSelection.TableGuides? = null
                scenario.onActivity {
                    view = view ?: word(viewer)
                    g = WordSelection(word(viewer)).tableGuides(start, end)
                }
                return g!!
            }
            val o = IntArray(2)
            scenario.onActivity { word(viewer).getLocationOnScreen(o) }
            val g0 = guides()
            val col = g0.columns.first { it.index == 1 }
            val row0 = g0.rows[0]
            android.util.Log.i("DragTest", "guides: columns ${g0.columns.map { it.index to it.x }} rows ${g0.rows.map { it.top to it.bottom }}")
            // the column border, grabbed by its handle at the table's top edge
            val cy = g0.top + o[1] - 12 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val tc = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, col.x + o[0].toFloat(), cy, tc)
            for (i in 1..15) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, col.x + o[0] + 90f * i / 15, cy, tc); if (i == 15) screenshot("table_column_dragging") }
            inject(MotionEvent.ACTION_UP, col.x + o[0] + 90f, cy, tc)
            Thread.sleep(1200)
            val g1 = guides()
            val col1 = g1.columns.first { it.index == 1 }
            android.util.Log.i("DragTest", "column 1: ${col.x} -> ${col1.x}")
            assertEquals("column border follows the finger", (col.x + 90).toFloat(), col1.x.toFloat(), 4f)
            // the first row's bottom, grabbed by its handle at the table's left edge
            val tr = SystemClock.uptimeMillis()
            val rx = g1.left + o[0] - 12 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density; val ry = g1.rows[0].bottom + o[1].toFloat()
            inject(MotionEvent.ACTION_DOWN, rx, ry, tr)
            for (i in 1..10) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, rx, ry + 50f * i / 10, tr) }
            inject(MotionEvent.ACTION_UP, rx, ry + 50f, tr)
            Thread.sleep(1200)
            screenshot("table_resized")
            val g2 = guides()
            android.util.Log.i("DragTest", "row 1: ${row0.bottom - row0.top} -> ${g2.rows[0].bottom - g2.rows[0].top}")
            assertEquals("row follows the finger", (g1.rows[0].bottom - g1.rows[0].top + 50).toFloat(), (g2.rows[0].bottom - g2.rows[0].top).toFloat(), 4f)
            scenario.onActivity { assertTrue("shown in place, not by reopening the document", word(viewer) === view) }
            assertTrue("table frame shown", tableFrameShown(scenario))
        }
        val x = xml(file)
        val tbl = x.substring(x.indexOf("<w:tbl>"), x.indexOf("</w:tbl>"))
        val grid = Regex("<w:gridCol w:w=\"(\\d+)\"").findAll(tbl).take(2).map { it.groupValues[1].toInt() }.toList()
        android.util.Log.i("DragTest", "grid $grid0 -> $grid")
        assertTrue("saved: first column wider", grid[0] > grid0[0] && grid[0] + grid[1] == grid0[0] + grid0[1])
        assertTrue("saved: first row height", tbl.split("<w:tr>", "<w:tr ")[1].contains("<w:trHeight"))
    }

    /** Types [text] with the keyboard into the field the Word panel types through. */
    private fun type(scenario: ActivityScenario<ReadDocumentActivity>, text: String) {
        instrumentation.runOnMainSync {
            scenario.onActivity { a ->
                val typing = find<android.widget.EditText>(a.findViewById<ViewGroup>(R.id.officeViewer)) { it is android.widget.EditText && it.alpha == 0f }!!
                assertTrue("typing field focused", typing.hasFocus())
                typing.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText(text, 1)
            }
        }
        Thread.sleep(600)
    }

    /** Screen point in the middle of the first char of [needle] (found in the model text from [from]). */
    private fun pointOf(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView, needle: String): Pair<Float, Float> {
        // in the upper part of the view first: a keyboard and the edit bar cover the lower part
        reveal(scenario, viewer) { sel -> val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong(); if (at < 0) null else sel.rectsFor(at, at + 1).firstOrNull() }
        var p = 0f to 0f
        scenario.onActivity {
            val w = word(viewer)
            val doc = w.getDocument()
            val text = doc.getText(0, (doc as WPDocument).getAreaEnd(0))
            val at = text.indexOf(needle).toLong()
            assertTrue("'$needle' in the document", at >= 0)
            val r = WordSelection(w).rectsFor(at, at + 1).first()
            val o = IntArray(2); w.getLocationOnScreen(o)
            p = (o[0] + r.left + r.width() * 0.3f) to (o[1] + r.exactCenterY())
        }
        return p
    }

    private fun modelText(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView): String {
        var t = ""
        scenario.onActivity { val doc = word(viewer).getDocument(); t = doc.getText(0, (doc as WPDocument).getAreaEnd(0)) }
        return t
    }

    /** Text typed into table cells: a tap on a cell, a tap on a cell of a selected table, an empty cell of a new 3 x 3 table. */
    @Test
    fun tableCellTyping() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            var start = 0L; var end = 0L
            scenario.onActivity {
                val t = (word(viewer).getDocument() as WPDocument).getTableCollection(0)!!.getElementForIndex(0)!!
                start = t.getStartOffset(); end = t.getEndOffset()
            }
            val box = reveal(scenario, viewer) { it.tableRect(start, end) }
            // 1. a tap on a cell
            pointOf(scenario, viewer, "WATCH").let { (x, y) -> tap(x, y) }
            type(scenario, "A1 ")
            assertTrue("typed in the cell", modelText(scenario, viewer).contains("A1 WATCH"))
            // 2. the table selected by a long press (where it is now: typing scrolls above the keyboard), then a tap on another cell
            box.set(reveal(scenario, viewer) { it.tableRect(start, end) })
            val t0 = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, box.left + box.width() * 0.5f, box.top + 25f, t0)
            Thread.sleep(900)
            inject(MotionEvent.ACTION_UP, box.left + box.width() * 0.5f, box.top + 25f, t0)
            // the keyboard of step 1 goes: let the page settle before aiming at a cell
            Thread.sleep(1800)
            assertTrue("table frame shown", tableFrameShown(scenario))
            // the long press put the caret in an empty spot: the keyboard is up, bring "CHALLENGE" above it
            reveal(scenario, viewer) { sel -> val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf("CHALLENGE").toLong(); sel.rectsFor(at, at + 1).firstOrNull() }
            pointOf(scenario, viewer, "CHALLENGE").let { (x, y) -> tap(x, y) }
            // the frame stays: the tap was in the same table (typing in a cell)
            type(scenario, "B2 ")
            assertTrue("typed in the cell of the selected table", modelText(scenario, viewer).contains("B2 CHALLENGE"))
            screenshot("cells_typed")
            // 3. a new 2 x 2 table after the heading, typed into its first (empty) cell
            pointOf(scenario, viewer, "Tổng quan tính năng").let { (x, y) -> tap(x, y) }
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "INSERT_TABLE" }!!.performClick() }
            Thread.sleep(800)
            // the dialog: 3 x 3 (its default), Insert
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.wxiwei.office.R.string.docsdk_edit_insert))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            Thread.sleep(1000)
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            var cell = 0f to 0f
            var firstCell = 0L
            scenario.onActivity {
                val w = word(viewer)
                val tables = (w.getDocument() as WPDocument).getTableCollection(0)!!
                val empty = (0 until tables.size()).map { tables.getElementForIndex(it)!! }.first { w.getDocument().getText(it.getStartOffset(), it.getEndOffset()).isBlank() }
                val g = WordSelection(w).tableGuides(empty.getStartOffset(), empty.getEndOffset())!!
                val o = IntArray(2); w.getLocationOnScreen(o)
                cell = (o[0] + (g.left + g.columns.first().x) / 2f) to (o[1] + (g.rows[0].top + g.rows[0].bottom) / 2f)
                val row = (empty as com.wxiwei.office.wp.model.TableElement).getElementForIndex(0) as com.wxiwei.office.wp.model.RowElement
                firstCell = row.getElementForIndex(0)!!.getStartOffset()
            }
            tap(cell.first, cell.second)
            type(scenario, "C3")
            screenshot("empty_cell_typed")
            scenario.onActivity { assertEquals("typed in the first cell", "C3", word(viewer).getDocument().getText(firstCell, firstCell + 2)) }
        }
        val x = xml(file)
        val plain = Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(x).joinToString("") { it.groupValues[1] }
        assertTrue("saved A1", plain.contains("A1 WATCH"))
        assertTrue("saved B2", plain.contains("B2 CHALLENGE"))
        val cells = Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(x).map { it.value }
        assertTrue("saved C3 in a cell", cells.any { it.contains(">C3<") })
    }

    /** Clicks the edit bar's button of the EditAction named [action]. */
    private fun press(scenario: ActivityScenario<ReadDocumentActivity>, action: String) {
        scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == action }!!.performClick() }
    }

    /** A tap on "WATCH", "+ Hàng dưới", typed "R1" in the new row; "+ Cột phải", typed "K1" in the new column; saved. */
    @Test
    fun tableInsertRowAndColumn() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            var start = 0L; var end = 0L
            scenario.onActivity {
                val t = (word(viewer).getDocument() as WPDocument).getTableCollection(0)!!.getElementForIndex(1)!!
                start = t.getStartOffset(); end = t.getEndOffset()
            }
            reveal(scenario, viewer) { it.tableRect(start, end) }
            pointOf(scenario, viewer, "WATCH").let { (x, y) -> tap(x, y) }
            val scroll = scrollY(scenario, viewer)
            press(scenario, "ROW_BELOW")
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            assertTrue("typing in the new cell: '${label(scenario)}'", label(scenario).startsWith(sdk(scenario, com.wxiwei.office.R.string.docsdk_edit_typing_hint)))
            android.util.Log.i("DragTest", "insert row: scroll $scroll -> ${scrollY(scenario, viewer)}")
            type(scenario, "R1")
            screenshot("table_row_added")
            press(scenario, "COLUMN_RIGHT")
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            type(scenario, "K1")
            screenshot("table_column_added")
            assertTrue("view stays near the table: $scroll -> ${scrollY(scenario, viewer)}", Math.abs(scrollY(scenario, viewer) - scroll) < 300)
        }
        val x = xml(file)
        val first = x.indexOf("<w:tbl>")
        val at = x.indexOf("<w:tbl>", first + 1)
        val tbl = x.substring(at, x.indexOf("</w:tbl>", at))
        val rows = tbl.split(Regex("<w:tr[ >]")).drop(1)
        fun texts(r: String) = Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(r).map { c -> Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(c.value).joinToString("") { it.groupValues[1] } }.toList()
        android.util.Log.i("DragTest", "rows ${rows.map { texts(it) }}")
        assertEquals(5, rows.size)
        assertEquals("the new row: R1, then K1 in the new column", listOf("R1", "K1", "", ""), texts(rows[2]))
        assertEquals("WATCH's row gets an empty cell", listOf("WATCH", ""), texts(rows[1]).take(2))
    }

    /** A tap on "PLAY_ALONG", "− Hàng"; a tap on "Mô tả", "− Cột"; saved. */
    @Test
    fun tableDeleteRowAndColumn() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            var start = 0L; var end = 0L
            scenario.onActivity {
                val t = (word(viewer).getDocument() as WPDocument).getTableCollection(0)!!.getElementForIndex(1)!!
                start = t.getStartOffset(); end = t.getEndOffset()
            }
            reveal(scenario, viewer) { it.tableRect(start, end) }
            fun count(t: String) = Regex(Regex.escape(t)).findAll(modelText(scenario, viewer)).count()
            val plays = count("PLAY_ALONG"); val descs = count("Mô tả")
            pointOf(scenario, viewer, "PLAY_ALONG").let { (x, y) -> tap(x, y) }
            press(scenario, "DELETE_ROW")
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            assertTrue("caret in the row taking its place: '${label(scenario)}'", label(scenario).startsWith(sdk(scenario, com.wxiwei.office.R.string.docsdk_edit_typing_hint)))
            assertEquals("row gone", plays - 1, count("PLAY_ALONG"))
            pointOf(scenario, viewer, "Mô tả").let { (x, y) -> tap(x, y) }
            press(scenario, "DELETE_COLUMN")
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            screenshot("table_deleted")
            assertEquals("column gone", descs - 1, count("Mô tả"))
        }
        val x = xml(file)
        val a = x.indexOf("<w:tbl>", x.indexOf("<w:tbl>") + 1)
        val tbl = x.substring(a, x.indexOf("</w:tbl>", a))
        fun texts(r: String) = Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(r).map { c -> Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(c.value).joinToString("") { it.groupValues[1] } }.toList()
        assertEquals(listOf(listOf("Chế độ", "Dùng khi"), listOf("WATCH", "Demo bài, học giai điệu lần đầu"), listOf("CHALLENGE", "Thử thách, thi đấu điểm")),
            tbl.split(Regex("<w:tr[ >]")).drop(1).map { texts(it) })
    }

    /** A new 3 x 3 table: its first column border dragged, then each cell tapped and typed into: the text and the caret go to that cell. */
    @Test
    fun newTableResizeThenTypeInCells() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            pointOf(scenario, viewer, "Tổng quan tính năng").let { (x, y) -> tap(x, y) }
            press(scenario, "INSERT_TABLE")
            Thread.sleep(800)
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.wxiwei.office.R.string.docsdk_edit_insert))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click())
            Thread.sleep(1000)
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            fun emptyTable(): LongRange {
                var r = 0L until 0L
                scenario.onActivity {
                    val w = word(viewer)
                    val tables = (w.getDocument() as WPDocument).getTableCollection(0)!!
                    val t = (0 until tables.size()).map { tables.getElementForIndex(it)!! }.first { e -> w.getDocument().getText(e.getStartOffset(), e.getEndOffset()).replace("\n", "").length <= 20 }
                    r = t.getStartOffset() until t.getEndOffset()
                }
                return r
            }
            var t = emptyTable()
            val box = reveal(scenario, viewer) { it.tableRect(t.first, t.last + 1) }
            // select it (long press, lifted in place), drag the first column border 120 px right
            val t0 = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, box.left + 20f, box.top + 10f, t0); Thread.sleep(900)
            inject(MotionEvent.ACTION_UP, box.left + 20f, box.top + 10f, t0); Thread.sleep(600)
            val o = IntArray(2)
            scenario.onActivity { word(viewer).getLocationOnScreen(o) }
            var g: com.wxiwei.office.editor.word.WordSelection.TableGuides? = null
            scenario.onActivity { g = WordSelection(word(viewer)).tableGuides(t.first, t.last + 1) }
            val col = g!!.columns.first { it.index == 1 }
            val cy = g!!.top + o[1] - 12 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val tc = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, col.x + o[0].toFloat(), cy, tc)
            for (i in 1..12) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, col.x + o[0] + 120f * i / 12, cy, tc) }
            inject(MotionEvent.ACTION_UP, col.x + o[0] + 120f, cy, tc)
            Thread.sleep(1200)
            // each cell: a tap in its middle, one letter typed
            t = emptyTable()
            scenario.onActivity { g = WordSelection(word(viewer)).tableGuides(t.first, t.last + 1) }
            val xs = listOf(g!!.left) + g!!.columns.map { it.x }
            val problems = ArrayList<String>()
            for (r in 0 until 3) for (c in 0 until 3) {
                scenario.onActivity { g = WordSelection(word(viewer)).tableGuides(t.first, t.last + 1); word(viewer).getLocationOnScreen(o) }
                val cols = listOf(g!!.left) + g!!.columns.map { it.x }
                val x = (cols[c] + cols[c + 1]) / 2f + o[0]; val y = (g!!.rows[r].top + g!!.rows[r].bottom) / 2f + o[1]
                tap(x, y)
                var caretIn = ""
                scenario.onActivity {
                    val caret = find<View>(viewer) { it.javaClass.simpleName == "WordCaretOverlay" }
                    val rect = caret?.javaClass?.getDeclaredField("rect")?.apply { isAccessible = true }?.get(caret)
                    caretIn = rect.toString()
                }
                val letter = "" + ('a' + r * 3 + c)
                type(scenario, letter)
                scenario.onActivity {
                    val w = word(viewer)
                    val table = (w.getDocument() as WPDocument).getParagraph0(t.first) as com.wxiwei.office.wp.model.TableElement
                    val cell = (table.getElementForIndex(r) as com.wxiwei.office.wp.model.RowElement).getElementForIndex(c)!!
                    val text = w.getDocument().getText(cell.getStartOffset(), cell.getEndOffset()).trimEnd('\n')
                    android.util.Log.i("DragTest", "cell $r,$c tap ($x,$y) caret $caretIn -> '$text'")
                    if (text != letter) problems.add("cell $r,$c has '$text', expected '$letter'")
                }
                t = emptyTable()
            }
            screenshot("new_table_typed")
            assertEquals(emptyList<String>(), problems)
        }
    }

    /** The text right before and right after the table starting at [start] in the model (what it sits between). */
    private fun neighbours(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView, start: Long, end: Long): Pair<String, String> {
        var r = "" to ""
        scenario.onActivity {
            val d = word(viewer).getDocument()
            r = d.getText(maxOf(0, start - 25), start).replace("\n", "⏎") to d.getText(end, end + 25).replace("\n", "⏎")
        }
        return r
    }

    /** Drags the table [t] by its middle [dy] px; [selectFirst]: a long press lifted in place before, then a plain drag. */
    private fun dragTable(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView, t: LongRange, dy: Float, selectFirst: Boolean): Rect {
        var box = Rect()
        scenario.onActivity {
            val w = word(viewer); val r = WordSelection(w).tableRect(t.first, t.last + 1)!!
            val o = IntArray(2); w.getLocationOnScreen(o); box = Rect(r).apply { offset(o[0], o[1]) }
        }
        val x = box.left + box.width() * 0.4f; val y = box.top + minOf(20f, box.height() / 3f)
        if (selectFirst) {
            // selected by a long press lifted in place, then moved by its handle (outside the top-left corner)
            val t0 = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, x, y, t0); Thread.sleep(900); inject(MotionEvent.ACTION_UP, x, y, t0); Thread.sleep(500)
            val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            drag(box.left - 20 * d, box.top - 20 * d, 0f, dy, holdMs = 50)
        } else drag(x, y, 0f, dy, holdMs = 900)
        return box
    }

    /** Tables moved by a drag: a new one down and up, one of the file down, a selected one. */
    @Test
    fun tableMoveCases() {
        val file = sample("sample.docx")
        val problems = ArrayList<String>()
        open(file) { scenario, viewer ->
            // a new 3 x 3 table after "Tổng quan tính năng"
            pointOf(scenario, viewer, "Tổng quan tính năng").let { (x, y) -> tap(x, y) }
            press(scenario, "INSERT_TABLE")
            Thread.sleep(800)
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.wxiwei.office.R.string.docsdk_edit_insert))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click())
            Thread.sleep(1000)
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            fun tables(): List<LongRange> {
                var out = emptyList<LongRange>()
                scenario.onActivity {
                    val c = (word(viewer).getDocument() as WPDocument).getTableCollection(0)!!
                    out = (0 until c.size()).map { c.getElementForIndex(it)!!.let { e -> e.getStartOffset() until e.getEndOffset() } }
                }
                return out
            }
            fun emptyTable(): LongRange = tables().first { r -> var blank = false; scenario.onActivity { blank = word(viewer).getDocument().getText(r.first, r.last + 1).replace("\n", "").isEmpty() }; blank }
            for ((name, dy, selectFirst) in listOf(Triple("new table down", 260f, false), Triple("new table up", -200f, false), Triple("new table selected, down", 200f, true))) {
                var t = emptyTable()
                reveal(scenario, viewer) { it.tableRect(t.first, t.last + 1) }
                val before = neighbours(scenario, viewer, t.first, t.last + 1)
                dragTable(scenario, viewer, t, dy, selectFirst)
                t = emptyTable()
                val after = neighbours(scenario, viewer, t.first, t.last + 1)
                android.util.Log.i("DragTest", "$name: '${before.first}' | '${before.second}'  ->  '${after.first}' | '${after.second}'  label='${label(scenario)}'")
                if (before == after) problems.add("$name: did not move")
            }
            // a table of the file ("Chế độ / Mô tả / Dùng khi"), down
            var t = tables().first { r -> var ok = false; scenario.onActivity { ok = word(viewer).getDocument().getText(r.first, r.last + 1).contains("WATCH") }; ok }
            reveal(scenario, viewer) { it.tableRect(t.first, t.last + 1) }
            val before = neighbours(scenario, viewer, t.first, t.last + 1)
            dragTable(scenario, viewer, t, 260f, false)
            t = tables().first { r -> var ok = false; scenario.onActivity { ok = word(viewer).getDocument().getText(r.first, r.last + 1).contains("WATCH") }; ok }
            val after = neighbours(scenario, viewer, t.first, t.last + 1)
            android.util.Log.i("DragTest", "file table down: '${before.first}' | '${before.second}'  ->  '${after.first}' | '${after.second}'")
            if (before == after) problems.add("file table down: did not move")
            screenshot("table_moves")
        }
        assertEquals(emptyList<String>(), problems)
    }

    /** "I" on a word selected by a long press: in a paragraph, and in a table cell. */
    @Test
    fun italicOnSelectedWords() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            for (needle in listOf("Play-along", "WATCH")) {
                val (x, y) = pointOf(scenario, viewer, needle).let { p -> var q = p; q }
                reveal(scenario, viewer) { sel -> var at = 0L; val w = word(viewer); at = w.getDocument().getText(0, (w.getDocument() as WPDocument).getAreaEnd(0)).indexOf(needle).toLong(); sel.rectsFor(at, at + 1).firstOrNull() }
                val (px, py) = pointOf(scenario, viewer, needle)
                val t = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, px + 8, py, t); Thread.sleep(900); inject(MotionEvent.ACTION_UP, px + 8, py, t); Thread.sleep(700)
                var selected = ""
                scenario.onActivity { selected = WordSelection(word(viewer)).selectedText() }
                android.util.Log.i("DragTest", "$needle: selected '$selected' label='${label(scenario)}'")
                press(scenario, "ITALIC")
                Thread.sleep(700)
                screenshot("italic_" + needle)
                scenario.onActivity {
                    val w = word(viewer); val d = w.getDocument()
                    val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong()
                    val italic = com.wxiwei.office.simpletext.model.AttrManage.instance().getFontItalic(d.getParagraph(at + 1)!!.getAttribute(), d.getLeaf(at + 1)!!.getAttribute())
                    android.util.Log.i("DragTest", "$needle: italic=$italic")
                    assertTrue("'$needle' selected: '$selected'", selected.isNotEmpty() && needle.startsWith(selected.trim()).or(selected.contains(needle.take(4))))
                    assertTrue("'$needle' italic in the model", italic)
                }
            }
        }
    }

    /** "I" with only the caret: inside a word, the word; between words, the text typed next. */
    @Test
    fun italicAtCaret() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            // inside "Parse"
            var at = 0L
            scenario.onActivity { val d = word(viewer).getDocument(); at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf("Parse MIDI").toLong() }
            var p = 0f to 0f
            scenario.onActivity {
                val r = WordSelection(word(viewer)).rectsFor(at + 2, at + 3).first(); val o = IntArray(2); word(viewer).getLocationOnScreen(o)
                p = (o[0] + r.left + 1f) to (o[1] + r.exactCenterY())
            }
            tap(p.first, p.second)
            press(scenario, "ITALIC")
            Thread.sleep(600)
            fun italic(o: Long): Boolean { var b = false; scenario.onActivity { val d = word(viewer).getDocument(); b = com.wxiwei.office.simpletext.model.AttrManage.instance().getFontItalic(d.getParagraph(o)!!.getAttribute(), d.getLeaf(o)!!.getAttribute()) }; return b }
            assertTrue("the word under the caret", (0 until 5).all { italic(at + it) })
            assertTrue("not the next word", !italic(at + 6))
            // after "MIDI " (between words): I, then type
            scenario.onActivity {
                val r = WordSelection(word(viewer)).rectsFor(at + 11, at + 12).first(); val o = IntArray(2); word(viewer).getLocationOnScreen(o)
                p = (o[0] + r.left + 1f) to (o[1] + r.exactCenterY())
            }
            tap(p.first, p.second)
            press(scenario, "ITALIC")
            type(scenario, "xyz")
            var typedAt = -1L
            scenario.onActivity { val d = word(viewer).getDocument(); typedAt = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf("xyz").toLong() }
            assertTrue("typed", typedAt > 0)
            assertTrue("typed text italic", (0 until 3).all { italic(typedAt + it) })
            screenshot("italic_caret")
        }
        val xml = xml(file)
        assertTrue("saved italic", Regex("<w:i/>|<w:i w:val=\"1\"/>|<w:i w:val=\"true\"/>").containsMatchIn(xml.substring(xml.indexOf("Parse") - 400, xml.indexOf("Parse"))) || xml.contains("<w:i/>"))
    }

    /** W1: ⇤ ↔ ⇥ ☰ with the caret in a paragraph, and on a word selected in a table cell. */
    @Test
    fun alignButtons() {
        val file = sample("sample.docx")
        val needle = "Tính năng Play-along cho phép"
        open(file) { scenario, viewer ->
            fun align(text: String): Int { var a = -1; scenario.onActivity { val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(text).toLong(); a = com.wxiwei.office.simpletext.model.AttrManage.instance().getParaHorizontalAlign(d.getParagraph(at)!!.getAttribute()) }; return a }
            /** Left edge of the first line of the paragraph holding [text], in view pixels. */
            fun lineLeft(text: String): Int { var x = 0; scenario.onActivity { val w = word(viewer); val d = w.getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(text).toLong(); x = WordSelection(w).rectsFor(at, at + 1).first().left }; return x }
            pointOf(scenario, viewer, needle).let { (x, y) -> tap(x + 20, y) }
            val left0 = lineLeft(needle)
            val expected = mapOf("ALIGN_RIGHT" to com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_RIGHT, "ALIGN_CENTER" to com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_CENTER,
                "ALIGN_JUSTIFY" to com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_JUSTIFIED, "ALIGN_LEFT" to com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_LEFT)
            for ((button, value) in expected) {
                press(scenario, button)
                Thread.sleep(600)
                assertEquals("$button with the caret", value.toInt(), align(needle))
            }
            // right, then back to left: the line really moved
            press(scenario, "ALIGN_RIGHT"); Thread.sleep(600)
            val right = lineLeft(needle)
            press(scenario, "ALIGN_LEFT"); Thread.sleep(600)
            assertTrue("right-aligned line starts further right: $left0 -> $right", right > left0)
            assertEquals("back at the left", left0, lineLeft(needle))
            press(scenario, "ALIGN_RIGHT"); Thread.sleep(600)
            // a word selected in a table cell, centered
            val (cx, cy) = pointOf(scenario, viewer, "WATCH")
            val t = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, cx + 8, cy, t); Thread.sleep(900); inject(MotionEvent.ACTION_UP, cx + 8, cy, t); Thread.sleep(700)
            press(scenario, "ALIGN_CENTER"); Thread.sleep(600)
            assertEquals("↔ on a word in a cell", com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_CENTER.toInt(), align("WATCH"))
            screenshot("align")
        }
        val xml = xml(file)
        fun jcBefore(text: String): String? { val at = xml.indexOf(text); val p = xml.lastIndexOf("<w:p>", at).coerceAtLeast(xml.lastIndexOf("<w:p ", at)); return Regex("<w:jc w:val=\"(\\w+)\"").find(xml.substring(p, at))?.groupValues?.get(1) }
        assertEquals("saved right", "right", jcBefore("Tính năng Play-along cho phép"))
        assertEquals("saved center in the cell", "center", jcBefore("WATCH"))
    }

    /** "Đoạn văn…": justified, left indent 1 cm, first line 0.5 cm: model, first line moved right, one undo, saved. */
    @Test
    fun paragraphDialog() {
        val file = sample("sample.docx")
        val needle = "Tính năng Play-along cho phép"
        open(file) { scenario, viewer ->
            fun layout(): com.wxiwei.office.editor.docx.LiveDocxSession.ParagraphLayout? { var l: com.wxiwei.office.editor.docx.LiveDocxSession.ParagraphLayout? = null
                scenario.onActivity { val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong()
                    val a = d.getParagraph(at)!!.getAttribute(); val am = com.wxiwei.office.simpletext.model.AttrManage.instance()
                    val align = when (am.getParaHorizontalAlign(a)) { 1 -> "center"; 2 -> "right"; 3 -> "both"; else -> "left" }
                    val special = am.getParaSpecialIndent(a)
                    l = com.wxiwei.office.editor.docx.LiveDocxSession.ParagraphLayout(align, am.getParaIndentLeft(a) - (if (special < 0) special else 0), am.getParaIndentRight(a), special, am.getParaBefore(a) / 20f, am.getParaAfter(a) / 20f) }; return l }
            fun lineLeft(offsetInPara: Int): Int { var x = 0; scenario.onActivity { val w = word(viewer); val d = w.getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong() + offsetInPara; x = WordSelection(w).rectsFor(at, at + 1).first().left }; return x }
            pointOf(scenario, viewer, needle).let { (x, y) -> tap(x + 20, y) }
            val before = layout()!!
            val firstBefore = lineLeft(0)
            press(scenario, "PARAGRAPH"); Thread.sleep(800)
            a11yTap(ui(com.wxiwei.office.R.string.docsdk_edit_justify))
            a11ySetField(0, "1")
            a11yTap(ui(com.wxiwei.office.R.string.docsdk_edit_indent_first_line))
            a11ySetField(2, "0.5")
            a11yTap(ui(com.wxiwei.office.R.string.docsdk_edit_apply)); Thread.sleep(900)
            val after = layout()!!
            android.util.Log.i("DragTest", "paragraph $before -> $after")
            assertEquals("both", after.align)
            assertEquals(567, after.leftTwips)
            assertEquals(283, after.specialTwips)
            val firstAfter = lineLeft(0)
            assertTrue("first line moved right: $firstBefore -> $firstAfter", firstAfter > firstBefore + 20)
            screenshot("paragraph_dialog")
            // one step back
            press(scenario, "UNDO"); Thread.sleep(800)
            assertEquals("undo", before, layout())
            press(scenario, "REDO"); Thread.sleep(800)
            assertEquals("redo", after, layout())
        }
        val xml = xml(file)
        val at = xml.indexOf(needle); val p = maxOf(xml.lastIndexOf("<w:p>", at), xml.lastIndexOf("<w:p ", at))
        val pPr = xml.substring(p, at)
        assertTrue("jc both: $pPr", pPr.contains("<w:jc w:val=\"both\"/>"))
        assertTrue("ind: $pPr", Regex("<w:ind [^>]*w:left=\"567\"[^>]*w:firstLine=\"283\"").containsMatchIn(pPr))
    }

    /** ☰ on a paragraph of several lines: its first line is really spread to the full width, the last one is not. */
    @Test
    fun justifyButton() {
        val file = sample("sample.docx")
        val needle = "Tính năng Play-along cho phép"
        open(file) { scenario, viewer ->
            /** Extra space per gap on the first and last line of the paragraph holding [text]. */
            fun spread(text: String): Pair<Float, Float> {
                var r = 0f to 0f
                scenario.onActivity {
                    val w = word(viewer); val d = w.getDocument()
                    val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(text).toLong()
                    val para = d.getParagraph(at)!!
                    val root = w.getRoot(com.wxiwei.office.constant.wp.WPViewConstant.PAGE_ROOT.toInt())!!
                    fun extra(offset: Long): Float {
                        var e = 0f
                        var leaf = root.getView(offset, com.wxiwei.office.constant.wp.WPViewConstant.LINE_VIEW.toInt(), false)?.getChildView()
                        while (leaf != null) { if (leaf is com.wxiwei.office.wp.view.LeafView) e = maxOf(e, leaf.justifyExtra); leaf = leaf.getNextView() }
                        return e
                    }
                    r = extra(para.getStartOffset()) to extra(para.getEndOffset() - 1)
                }
                return r
            }
            pointOf(scenario, viewer, needle).let { (x, y) -> tap(x + 20, y) }
            press(scenario, "ALIGN_LEFT"); Thread.sleep(600)
            assertEquals("left: nothing spread", 0f to 0f, spread(needle))
            press(scenario, "ALIGN_JUSTIFY"); Thread.sleep(800)
            val (first, last) = spread(needle)
            android.util.Log.i("JustifyTest", "first line +$first px per space, last line +$last")
            assertTrue("first line spread: $first", first > 0f)
            assertEquals("last line keeps its spacing", 0f, last)
            screenshot("justify")
        }
        val xml = xml(file)
        val at = xml.indexOf(needle); val p = maxOf(xml.lastIndexOf("<w:p>", at), xml.lastIndexOf("<w:p ", at))
        assertEquals("saved both", "both", Regex("<w:jc w:val=\"(\\w+)\"").find(xml.substring(p, at))?.groupValues?.get(1))
    }

    /** W3: "S̶" on a selected word: struck through in the view, saved as w:strike in schema order, read back. */
    @Test
    fun strikeThrough() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            val (x, y) = pointOf(scenario, viewer, "WATCH")
            val t = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, x + 8, y, t); Thread.sleep(900); inject(MotionEvent.ACTION_UP, x + 8, y, t); Thread.sleep(700)
            press(scenario, "STRIKETHROUGH"); Thread.sleep(600)
            scenario.onActivity {
                val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf("WATCH").toLong()
                assertTrue("struck in the model", com.wxiwei.office.simpletext.model.AttrManage.instance().getFontStrike(d.getParagraph(at)!!.getAttribute(), d.getLeaf(at + 1)!!.getAttribute()))
            }
            screenshot("strike")
        }
        val xml = xml(file)
        val at = xml.indexOf(">WATCH<")
        val run = xml.substring(xml.lastIndexOf("<w:r>", at).coerceAtLeast(xml.lastIndexOf("<w:r ", at)), at)
        android.util.Log.i("DragTest", "strike run: $run")
        assertTrue("w:strike saved: $run", run.contains("<w:strike w:val=\"1\"/>"))
        // CT_RPr order: strike before color, sz
        val names = Regex("<w:(\\w+)[ />]").findAll(run.substring(run.indexOf("<w:rPr>"), run.indexOf("</w:rPr>"))).map { it.groupValues[1] }.filter { it != "rPr" }.toList()
        val order = listOf("rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "color", "spacing", "position", "sz", "szCs", "highlight", "u", "shd", "vertAlign", "lang")
        val ranks = names.map { order.indexOf(it) }.filter { it >= 0 }
        assertEquals("rPr in schema order: $names", ranks.sorted(), ranks)
    }

    /** Runs [block] on the window in front (a dialog too), through accessibility. */
    private fun a11y(block: (android.view.accessibility.AccessibilityNodeInfo) -> Unit) {
        // just after a window opens there may be none active for a moment
        var root = instrumentation.uiAutomation.rootInActiveWindow
        val end = System.currentTimeMillis() + 3000
        while (root == null && System.currentTimeMillis() < end) { Thread.sleep(100); root = instrumentation.uiAutomation.rootInActiveWindow }
        block(root ?: error("no active window"))
    }

    private fun find(n: android.view.accessibility.AccessibilityNodeInfo?, match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        n ?: return null
        if (match(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), match)?.let { return it }
        return null
    }

    /** W4: "Màu chữ": a #RRGGBB code, then a theme swatch; the last used comes back under "Gần đây". */
    @Test
    fun colorPicker() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            fun color(): Int { var c = 0; scenario.onActivity { val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf("WATCH").toLong(); c = com.wxiwei.office.simpletext.model.AttrManage.instance().getFontColor(d.getParagraph(at)!!.getAttribute(), d.getLeaf(at + 1)!!.getAttribute()) and 0xFFFFFF }; return c }
            fun selectWatch() {
                val (x, y) = pointOf(scenario, viewer, "WATCH")
                val t = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, x + 8, y, t); Thread.sleep(900); inject(MotionEvent.ACTION_UP, x + 8, y, t); Thread.sleep(700)
            }
            selectWatch()
            press(scenario, "TEXT_COLOR"); Thread.sleep(800)
            a11y { root -> find(root) { it.className?.toString() == "android.widget.EditText" && it.isEditable }?.let { n ->
                n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "1A2B3C") }) } }
            Thread.sleep(300)
            a11y { it.findAccessibilityNodeInfosByText("OK").firstOrNull { n -> n.isClickable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
            Thread.sleep(700)
            assertEquals("hex code", 0x1A2B3C, color())
            // the code field's keyboard goes away first
            Thread.sleep(1500)
            selectWatch()
            press(scenario, "TEXT_COLOR"); Thread.sleep(1200)
            screenshot("color_picker")
            var r = android.graphics.Rect()
            a11y { root -> find(root) { it.contentDescription?.toString() == ui(com.wxiwei.office.R.string.docsdk_edit_color_description, "ED7D31") }?.getBoundsInScreen(r) }
            assertTrue("swatch found", !r.isEmpty)
            tap(r.exactCenterX(), r.exactCenterY())
            Thread.sleep(700)
            assertEquals("theme swatch", 0xED7D31, color())
        }
        val xml = xml(file)
        val at = xml.indexOf(">WATCH<")
        assertTrue("saved color", xml.substring(xml.lastIndexOf("<w:r>", at), at).contains("<w:color w:val=\"ED7D31\"/>"))
    }

    /** Taps the node of the front window whose text is [text] (a radio button, a dialog button). */
    private fun a11yTap(text: String) {
        val r = android.graphics.Rect()
        a11y { root -> find(root) { it.text?.toString() == text }?.getBoundsInScreen(r) }
        assertTrue("'$text' on screen", !r.isEmpty)
        tap(r.exactCenterX(), r.exactCenterY())
    }

    /** Sets the [index]-th text field of the front window to [value]. */
    private fun a11ySetField(index: Int, value: String) {
        a11y { root ->
            val fields = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) { n ?: return; if (n.className?.toString() == "android.widget.EditText") fields.add(n); for (i in 0 until n.childCount) walk(n.getChild(i)) }
            walk(root)
            fields[index].performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
        }
    }

    /** W7: "Giãn dòng": 2.0 with 12 pt before and 6 pt after, then exactly 30 pt; shown and saved. */
    @Test
    fun lineSpacingDialog() {
        val file = sample("sample.docx")
        val needle = "Tính năng Play-along cho phép"
        open(file) { scenario, viewer ->
            fun para(): IntArray { var v = IntArray(4); scenario.onActivity { val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong(); val a = d.getParagraph(at)!!.getAttribute(); val am = com.wxiwei.office.simpletext.model.AttrManage.instance()
                v = intArrayOf(am.getParaLineSpaceType(a), Math.round(am.getParaLineSpace(a) * 100), am.getParaBefore(a), am.getParaAfter(a)) }; return v }
            /** Distance between the first two lines of the paragraph, in view pixels. */
            fun pitch(): Int { var p = 0; scenario.onActivity { val w = word(viewer); val d = w.getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong(); val rs = WordSelection(w).rectsFor(at, at + 120); p = rs[1].top - rs[0].top }; return p }
            val (x, y) = pointOf(scenario, viewer, needle)
            tap(x + 20, y)
            val before = pitch()
            press(scenario, "LINE_SPACING"); Thread.sleep(800)
            a11yTap("2.0")
            a11ySetField(1, "12"); a11ySetField(2, "6")
            a11yTap("OK"); Thread.sleep(800)
            val p2 = para()
            assertEquals("multiple", com.wxiwei.office.constant.wp.WPAttrConstant.LINE_SAPCE_MULTIPLE.toInt(), p2[0])
            assertEquals("2.0", 200, p2[1])
            assertEquals("12 pt before", 240, p2[2]); assertEquals("6 pt after", 120, p2[3])
            val doubled = pitch()
            android.util.Log.i("DragTest", "line pitch $before -> $doubled")
            assertTrue("lines further apart: $before -> $doubled", doubled > before * 1.5)
            press(scenario, "LINE_SPACING"); Thread.sleep(800)
            a11yTap(ui(com.wxiwei.office.R.string.docsdk_edit_spacing_exactly))
            a11ySetField(0, "30")
            a11yTap("OK"); Thread.sleep(800)
            val p3 = para()
            assertEquals("exactly", com.wxiwei.office.constant.wp.WPAttrConstant.LINE_SPACE_EXACTLY.toInt(), p3[0])
            assertEquals("30 pt = -600 twips", -60000, p3[1])
            screenshot("line_spacing")
        }
        val xml = xml(file)
        val at = xml.indexOf("Tính năng Play-along cho phép")
        val p = xml.substring(xml.lastIndexOf("<w:p>", at).coerceAtLeast(xml.lastIndexOf("<w:p ", at)), at)
        android.util.Log.i("DragTest", "spacing: ${Regex("<w:spacing[^>]*>").find(p)?.value}")
        val spacing = Regex("<w:spacing[^>]*>").find(p)!!.value
        assertTrue(spacing, spacing.contains("w:line=\"600\"") && spacing.contains("w:lineRule=\"exact\"") && spacing.contains("w:before=\"240\"") && spacing.contains("w:after=\"120\""))
    }

    /** W8: x² on "WATCH", x₂ on "Play": shown raised / lowered, saved as w:vertAlign. */
    @Test
    fun superAndSubscript() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            fun script(text: String): Int { var v = -1; scenario.onActivity { val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(text).toLong(); v = com.wxiwei.office.simpletext.model.AttrManage.instance().getFontScript(d.getParagraph(at)!!.getAttribute(), d.getLeaf(at + 1)!!.getAttribute()) }; return v }
            fun selectWord(text: String) {
                val (x, y) = pointOf(scenario, viewer, text)
                val t = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, x + 8, y, t); Thread.sleep(900); inject(MotionEvent.ACTION_UP, x + 8, y, t); Thread.sleep(700)
            }
            selectWord("WATCH"); press(scenario, "SUPERSCRIPT"); Thread.sleep(600)
            assertEquals("superscript", 1, script("WATCH"))
            screenshot("superscript")
            selectWord("Play-along cho"); press(scenario, "SUBSCRIPT"); Thread.sleep(600)
            assertEquals("subscript", 2, script("Play-along cho"))
            screenshot("subscript")
        }
        val xml = xml(file)
        fun runOf(text: String): String { val at = xml.indexOf(text); return xml.substring(xml.lastIndexOf("<w:r>", at).coerceAtLeast(xml.lastIndexOf("<w:r ", at)), at) }
        assertTrue("saved superscript", runOf(">WATCH<").contains("<w:vertAlign w:val=\"superscript\"/>"))
        assertTrue("saved subscript", xml.contains("<w:vertAlign w:val=\"subscript\"/>"))
    }

    /** W2: "Font" on a selected word: Times New Roman (drawn narrower than Arial), then Roboto; saved as w:rFonts. */
    @Test
    fun fontPicker() {
        val file = sample("sample.docx")
        val needle = "Play-along cho"
        open(file) { scenario, viewer ->
            fun font(): String? { var f: String? = null; scenario.onActivity { val d = word(viewer).getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong(); f = com.wxiwei.office.simpletext.font.FontTypefaceManage.instance().fontName(com.wxiwei.office.simpletext.model.AttrManage.instance().getFontName(d.getParagraph(at)!!.getAttribute(), d.getLeaf(at + 1)!!.getAttribute())) }; return f }
            fun width(): Int { var w = 0; scenario.onActivity { val ww = word(viewer); val d = ww.getDocument(); val at = d.getText(0, (d as WPDocument).getAreaEnd(0)).indexOf(needle).toLong(); w = WordSelection(ww).rectsFor(at, at + 4).first().width() }; return w }
            fun selectWord() {
                val (x, y) = pointOf(scenario, viewer, needle)
                val t = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, x + 8, y, t); Thread.sleep(900); inject(MotionEvent.ACTION_UP, x + 8, y, t); Thread.sleep(700)
            }
            val arial = width()
            selectWord(); press(scenario, "FONT"); Thread.sleep(700)
            screenshot("font_list")
            a11yTap("Times New Roman"); Thread.sleep(700)
            assertEquals("Times New Roman", font())
            val times = width()
            android.util.Log.i("DragTest", "'Play' width: Arial $arial, Times $times")
            assertTrue("drawn in another font: $arial -> $times", times != arial)
            selectWord(); press(scenario, "FONT"); Thread.sleep(700)
            a11yTap("Roboto"); Thread.sleep(700)
            assertEquals("Roboto", font())
        }
        val xml = xml(file)
        // "Play" got its own run (the rest of the word kept its font)
        val at = xml.indexOf(">Play<")
        assertTrue("run of the word", at > 0)
        val run = xml.substring(xml.lastIndexOf("<w:r>", at).coerceAtLeast(xml.lastIndexOf("<w:r ", at)), at)
        assertTrue("saved font: $run", run.contains("w:ascii=\"Roboto\"") && run.contains("w:hAnsi=\"Roboto\""))
    }

    /** W5: "Tìm & thay": find next, replace one, replace all (one undo step), saved. */
    @Test
    fun findAndReplace() {
        val file = sample("sample.docx")
        open(file) { scenario, viewer ->
            fun count(t: String) = Regex(Regex.escape(t), RegexOption.IGNORE_CASE).findAll(modelText(scenario, viewer)).count()
            val n = count("Play-along")
            android.util.Log.i("DragTest", "Play-along x$n")
            assertTrue(n >= 3)
            press(scenario, "FIND_REPLACE"); Thread.sleep(800)
            a11ySetField(0, "Play-along"); a11ySetField(1, "Chơi cùng")
            a11yTap(ui(com.wxiwei.office.R.string.docsdk_edit_find_next)); Thread.sleep(700)
            var selected = ""
            scenario.onActivity { selected = WordSelection(word(viewer)).selectedText() }
            assertTrue("first match selected: '$selected'", selected.equals("Play-along", ignoreCase = true))
            // the match shows: not under the dialog
            val dialogBox = android.graphics.Rect()
            a11y { it.getBoundsInScreen(dialogBox) }
            var match = android.graphics.Rect()
            scenario.onActivity {
                val w = word(viewer); val r = WordSelection(w).selection()!!
                val box = WordSelection(w).rectsFor(r.first, r.last + 1).first(); val o = IntArray(2); w.getLocationOnScreen(o)
                match = android.graphics.Rect(box).apply { offset(o[0], o[1]) }
            }
            android.util.Log.i("DragTest", "match $match dialog $dialogBox")
            assertTrue("match $match not under the dialog $dialogBox", !android.graphics.Rect.intersects(match, dialogBox))
            screenshot("find_first")
            a11yTap(ui(com.wxiwei.office.R.string.docsdk_edit_replace)); Thread.sleep(700)
            assertEquals("one replaced", n - 1, count("Play-along"))
            assertTrue("the new text is there", count("Chơi cùng") >= 1)
            a11yTap(ui(com.wxiwei.office.R.string.docsdk_edit_replace_all)); Thread.sleep(1200)
            val left = count("Play-along")
            android.util.Log.i("DragTest", "after replace all: $left left")
            assertEquals("all replaced", 0, left)
            // close the dialog, undo once: the replace-all comes back as one step
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(600)
            press(scenario, "UNDO"); Thread.sleep(800)
            assertEquals("one undo brings them all back", n - 1, count("Play-along"))
            press(scenario, "REDO"); Thread.sleep(800)
            assertEquals(0, count("Play-along"))
        }
        val plain = Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(xml(file)).joinToString("") { it.groupValues[1] }
        assertTrue("saved", !plain.contains("Play-along", ignoreCase = true) && plain.contains("Chơi cùng"))
    }

    /** E-D8: while the bar is open, the unsaved edits go to a draft on their own (not only when the app goes to the background). */
    @Test
    fun autosaveDraft() {
        val file = sample("sample.docx")
        com.wxiwei.office.editor.ui.OfficeEditPanel.autosaveMs = 3000
        try {
            open(file) { scenario, viewer ->
                com.wxiwei.office.editor.ui.EditDrafts.delete(context, file)
                pointOf(scenario, viewer, "Tính năng Play-along cho phép").let { (x, y) -> tap(x + 20, y) }
                type(scenario, "zz")
                Thread.sleep(5000)
                val draft = com.wxiwei.office.editor.ui.EditDrafts.pending(context, file)
                assertTrue("a draft kept while editing", draft != null)
                val text = java.util.zip.ZipFile(draft!!).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }
                assertTrue("the typed text is in it", text.contains("zz"))
            }
        } finally {
            com.wxiwei.office.editor.ui.OfficeEditPanel.autosaveMs = 120_000L
        }
    }

    /** An SDK text in the language of the activity on screen. */
    private fun ui(id: Int, vararg args: Any): String {
        var text = ""
        instrumentation.runOnMainSync {
            val a = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            text = a.getString(id, *args)
        }
        return text
    }
}
