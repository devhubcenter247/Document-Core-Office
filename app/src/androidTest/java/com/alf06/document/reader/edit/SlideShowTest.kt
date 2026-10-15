package com.alf06.document.reader.edit

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alf06.document.reader.R
import com.alf06.document.reader.model.DocumentType
import com.alf06.document.reader.model.RecentDocument
import com.alf06.document.reader.ui.home.document.office.ReadDocumentActivity
import com.alf06.document.reader.ui.home.document.office.SlideShowActivity
import com.wxiwei.office.editor.EditResult
import com.wxiwei.office.editor.ooxml.A
import com.wxiwei.office.editor.ooxml.OoxmlPackage
import com.wxiwei.office.editor.ooxml.P
import com.wxiwei.office.editor.pptx.PptxEditor
import com.wxiwei.office.editor.pptx.SlideEffect
import com.wxiwei.office.editor.pptx.SlideTransition
import com.wxiwei.office.fc.dom4j.Element
import com.wxiwei.office.fc.dom4j.QName
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.reader.ReaderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S1/S2/S4/S5/S6: the slideshow plays a slide's animations tap by tap and its transition, goes back
 * to the end state, follows a "last slide" link, draws with the pen, jumps from the slide list and
 * moves on by itself.
 */
@RunWith(AndroidJUnit4::class)
class SlideShowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val out = File(context.getExternalFilesDir(null), "edit-ui").apply { mkdirs() }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { b -> File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }

    private fun <T> onMain(block: () -> T): T { var r: T? = null; instrumentation.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun showActivity(): SlideShowActivity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SlideShowActivity>().firstOrNull()
    }

    private fun waitFor(what: String, ms: Long = 15_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { assertTrue("waiting for $what", System.currentTimeMillis() < end); Thread.sleep(100) }
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(e, true); e.recycle()
    }

    private fun tap(x: Float, y: Float) { val t = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, x, y, t); inject(MotionEvent.ACTION_UP, x, y, t) }

    private fun find(v: View, match: (View) -> Boolean): View? {
        if (match(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun click(activity: Activity, text: String) = onMain { find(activity.window.decorView) { it is TextView && it.text.toString() == text }!!.performClick() }

    /** Taps the dialog item starting with [prefix] (accessibility: dialogs are other windows). */
    private fun a11yTapStarting(prefix: String) {
        val end = System.currentTimeMillis() + 5000
        while (true) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            fun look(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                n ?: return null; if (n.text?.toString()?.startsWith(prefix) == true) return n
                for (i in 0 until n.childCount) look(n.getChild(i))?.let { return it }
                return null
            }
            val node = look(root)
            if (node != null) {
                val r = android.graphics.Rect(); node.getBoundsInScreen(r); tap(r.exactCenterX(), r.exactCenterY()); Thread.sleep(600); return
            }
            assertTrue("no item $prefix", System.currentTimeMillis() < end)
            Thread.sleep(200)
        }
    }

    /** ppt2.pptx with four effects on slide 1, a "last slide" link on a third shape, a push into slide 2. */
    private fun deck(): Triple<File, List<Int>, Int> {
        val source = File(context.filesDir, "show-source.pptx")
        instrumentation.context.assets.open("samples/ppt2.pptx").use { i -> source.outputStream().use { i.copyTo(it) } }
        val animated = File(context.filesDir, "show-animated.pptx").apply { delete() }
        val editor = PptxEditor(source)
        // top-level shapes (PowerPoint animates a group as a whole, not its members)
        val ids = OoxmlPackage.open(source).xml("ppt/slides/slide1.xml").rootElement!!.elements()!!.filterIsInstance<Element>()
            .first { it.name == "cSld" }.elements()!!.filterIsInstance<Element>().first { it.name == "spTree" }
            .elements()!!.filterIsInstance<Element>().mapNotNull { e ->
                e.elements()!!.filterIsInstance<Element>().firstOrNull { it.name!!.startsWith("nv") }
                    ?.elements()?.filterIsInstance<Element>()?.firstOrNull { it.name == "cNvPr" }?.attributeValue("id")?.toIntOrNull()
            }
        assertTrue("three shapes on slide 1: $ids", ids.size >= 3)
        val (a, b) = ids[0] to ids[1]
        assertTrue(editor.setSlideEffects(0, listOf(
            SlideEffect(a, SlideEffect.Kind.ENTRANCE, SlideEffect.Effect.FADE, durationMs = 400),
            SlideEffect(b, SlideEffect.Kind.ENTRANCE, SlideEffect.Effect.FLY, SlideEffect.Direction.LEFT, SlideEffect.Start.WITH, 400),
            SlideEffect(a, SlideEffect.Kind.EMPHASIS, SlideEffect.Effect.PULSE, start = SlideEffect.Start.AFTER, durationMs = 400),
            SlideEffect(b, SlideEffect.Kind.EXIT, SlideEffect.Effect.ZOOM, durationMs = 400),
        )))
        assertTrue(editor.setSlideTransition(listOf(1), SlideTransition("push", "l", 500)))
        assertTrue(editor.save(animated) is EditResult.Ok)
        // the link: "ppaction://hlinkshowjump?jump=lastslide" on the third shape
        val linked = ids[2]
        val file = File(context.filesDir, "show-test.pptx").apply { delete() }
        val pkg = OoxmlPackage.open(animated)
        val root = pkg.xml("ppt/slides/slide1.xml").rootElement!!
        fun all(e: Element): Sequence<Element> = sequenceOf(e) + e.elements()!!.filterIsInstance<Element>().asSequence().flatMap { all(it) }
        val cNvPr = all(root).first { it.name == "cNvPr" && it.namespaceURI == P.uRI && it.attributeValue("id") == linked.toString() }
        cNvPr.addElement(QName("hlinkClick", A))!!.addAttribute(QName("id", com.wxiwei.office.editor.ooxml.R), "")!!
            .addAttribute("action", "ppaction://hlinkshowjump?jump=lastslide")
        assertTrue(pkg.saveTo(file) is EditResult.Ok)
        return Triple(file, ids, linked)
    }

    @Test
    fun slideshowPlaysAnimationsLinksPenListAndAuto() {
        val (file, ids, linked) = deck()
        val (a, b) = ids[0] to ids[1]
        val rects = PptxEditor(file).listShapes(0).associate { it.id to it.rectEmu }
        val intent = Intent(context, ReadDocumentActivity::class.java)
            .putExtra(ReadDocumentActivity.ARG_DOCUMENT, RecentDocument(path = file.absolutePath, size = file.length(), type = DocumentType.Ppt))
        ActivityScenario.launch<ReadDocumentActivity>(intent).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor("the deck", 60_000) { viewer.state.value.status == ReaderState.Status.Ready && viewer.state.value.pageCount >= 10 }
            Thread.sleep(2000)
            scenario.onActivity { ReadDocumentActivity::class.java.getDeclaredMethod("startSlideShow").apply { isAccessible = true }.invoke(it) }
            waitFor("the show") { showActivity() != null }
            val show = showActivity()!!
            waitFor("slide 1") { onMain { show.shownSlide == 0 && !show.showView.slideBounds.isEmpty && !show.showView.isAnimating } }
            onMain {
                val p = viewer.control!!.getView() as com.wxiwei.office.pg.control.Presentation
                android.util.Log.i("ShowTest", "file shapes ${PptxEditor(file).listShapes(0).map { "${it.id}:${it.kind}:${it.name}" }}")
                android.util.Log.i("ShowTest", "model shapes ${p.getSlide(0)!!.getShapes().map { "${it.shapeID}:${it.javaClass.simpleName}:${it.isHidden}" }}")
                android.util.Log.i("ShowTest", "layers ${show.showView.animatedShapes}")
            }
            fun shape(id: Int) = onMain { show.showView.shapeView(id)!!.visibility }
            assertEquals("A waits for its entrance", View.INVISIBLE, shape(a))
            assertEquals("B waits for its entrance", View.INVISIBLE, shape(b))
            screenshot("show_1_start")
            // tap 1: A fades in, B flies in with it, then A pulses
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            waitFor("tap 1") { onMain { show.shownStep == 1 } }
            Thread.sleep(200)
            screenshot("show_1_moving")
            waitFor("tap 1 done") { onMain { !show.showView.isAnimating } }
            assertEquals(1, onMain { show.shownStep })
            assertEquals(View.VISIBLE, shape(a)); assertEquals(View.VISIBLE, shape(b))
            screenshot("show_1_step1")
            // tap 2: B zooms out
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
            waitFor("tap 2") { onMain { show.shownStep == 2 } }
            waitFor("tap 2 done") { onMain { !show.showView.isAnimating } }
            android.util.Log.i("ShowTest", "after tap 2: step ${onMain { show.shownStep }} B alpha ${onMain { show.showView.shapeView(b)!!.alpha }} scale ${onMain { show.showView.shapeView(b)!!.scaleX }}")
            assertEquals("B gone", View.INVISIBLE, shape(b))
            // tap 3: slide 2, pushed in
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("slide 2") { onMain { show.shownSlide == 1 } }
            Thread.sleep(150)
            screenshot("show_2_push")
            waitFor("push done") { onMain { !show.showView.isAnimating } }
            // back: slide 1 as it ended
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            waitFor("back on slide 1") { onMain { show.shownSlide == 0 && !show.showView.isAnimating } }
            assertEquals("at its end", 2, onMain { show.shownStep })
            assertEquals("B still gone", View.INVISIBLE, shape(b))
            Thread.sleep(300)
            android.util.Log.i("ShowTest", "back: A ${shape(a)} alpha ${onMain { show.showView.shapeView(a)!!.alpha }}")
            screenshot("show_1_back")
            assertEquals("A shown at the end of slide 1", View.VISIBLE, shape(a))
            // the link on the third shape jumps to the last slide
            val (lx, ly) = onMain {
                val at = show.showView.slideBounds
                val loc = IntArray(2); show.showView.getLocationOnScreen(loc)
                // px of the stage per px of the slide at zoom 1
                val zoom = at.width() / (viewer.control!!.getView() as com.wxiwei.office.pg.control.Presentation).getPageSize()!!.width
                val r = rects.getValue(linked)
                (loc[0] + at.left + (r.x + r.width / 2f) / 9525f * zoom) to (loc[1] + at.top + (r.y + r.height / 2f) / 9525f * zoom)
            }
            tap(lx, ly)
            waitFor("the last slide") { onMain { show.shownSlide == viewer.state.value.pageCount - 1 } }
            screenshot("show_last")
            // pen: one stroke on this slide
            click(show, "✎ Bút")
            val (cx, cy) = onMain { val loc = IntArray(2); show.showView.getLocationOnScreen(loc); (loc[0] + show.showView.width / 2f) to (loc[1] + show.showView.height / 2f) }
            val t = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, cx - 200, cy, t)
            for (k in 1..10) { inject(MotionEvent.ACTION_MOVE, cx - 200 + k * 40f, cy + (k % 2) * 30f, t); Thread.sleep(16) }
            inject(MotionEvent.ACTION_UP, cx + 200, cy, t)
            Thread.sleep(300)
            assertEquals("one stroke", 1, onMain { show.showView.strokeCount() })
            screenshot("show_pen")
            click(show, "Xong")
            // the slide list: back to slide 1
            click(show, "☰ Danh sách")
            a11yTapStarting("1. ")
            waitFor("slide 1 from the list") { onMain { show.shownSlide == 0 && !show.showView.isAnimating } }
            // timed advance: 3 s
            click(show, "⏱ Tự chuyển")
            a11yTapStarting("3 giây")
            Thread.sleep(4500)
            assertTrue("moved on by itself", onMain { show.shownStep >= 1 || show.shownSlide > 0 })
            onMain { show.finish() }
        }
    }

    private fun openDeck(name: String, body: (ActivityScenario<ReadDocumentActivity>, OfficeDocumentView) -> Unit) {
        val file = File(context.filesDir, name).apply { delete() }
        instrumentation.context.assets.open("samples/ppt2.pptx").use { i -> file.outputStream().use { i.copyTo(it) } }
        val intent = Intent(context, ReadDocumentActivity::class.java)
            .putExtra(ReadDocumentActivity.ARG_DOCUMENT, RecentDocument(path = file.absolutePath, size = file.length(), type = DocumentType.Ppt))
        ActivityScenario.launch<ReadDocumentActivity>(intent).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor("the deck", 60_000) { viewer.state.value.status == ReaderState.Status.Ready && viewer.state.value.pageCount >= 10 }
            Thread.sleep(2000)
            body(scenario, viewer)
        }
    }

    /** S6 in reading mode: the list shows the titles, a tap shows that slide. */
    @Test
    fun slideListInReadingMode() = openDeck("list-test.pptx") { scenario, viewer ->
        scenario.onActivity { ReadDocumentActivity::class.java.getDeclaredMethod("showSlideList").apply { isAccessible = true }.invoke(it) }
        a11yTapStarting("3. ")
        waitFor("slide 3") { viewer.state.value.pageNumber == 3 }
    }

    /** S8: all slides to one PDF (a page each, each drawn, slide 2 not slide 1) and to 10 PNGs in Pictures. */
    @Test
    fun exportWholeDeck() = openDeck("export-deck.pptx") { scenario, viewer ->
        val pdf = File(out, "deck.pdf").apply { delete() }
        val p = viewer.control!!.getView() as com.wxiwei.office.pg.control.Presentation
        val written = kotlinx.coroutines.runBlocking {
            pdf.outputStream().use { o -> viewer.thumbnails!!.onDrawingThread { com.wxiwei.office.editor.ui.writeDeckPdf(p, 10, o) } }
        }
        assertEquals(10, written)
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            assertEquals(10, r.pageCount)
            val sums = (0 until 3).map { i ->
                r.openPage(i).use { page ->
                    val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.WHITE)
                    page.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var sum = 0L; for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) sum += b.getPixel(x, y) and 0xFFFFFF
                    sum
                }
            }
            assertTrue("pages differ: $sums", sums.toSet().size == 3)
        }
        // pictures: Pictures/export-deck/Slide N.png
        scenario.onActivity { ReadDocumentActivity::class.java.getDeclaredMethod("exportSlides", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, false) }
        fun count(): Int = context.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID), android.provider.MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
            arrayOf("%Pictures/export-deck%"), null)?.use { it.count } ?: 0
        val before = count()
        waitFor("10 pictures", 60_000) { count() >= before + 10 || count() >= 10 }
        screenshot("export_images")
    }

    /** S5 in reading mode: a tap on the "last slide" link of slide 1 shows slide 10. */
    @Test
    fun linkInReadingMode() {
        val (file, _, linked) = deck()
        val rect = PptxEditor(file).listShapes(0).first { it.id == linked }.rectEmu
        val intent = Intent(context, ReadDocumentActivity::class.java)
            .putExtra(ReadDocumentActivity.ARG_DOCUMENT, RecentDocument(path = file.absolutePath, size = file.length(), type = DocumentType.Ppt))
        ActivityScenario.launch<ReadDocumentActivity>(intent).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor("the deck", 60_000) { viewer.state.value.status == ReaderState.Status.Ready && viewer.state.value.pageCount >= 10 }
            Thread.sleep(3000)
            val (x, y) = onMain {
                val p = viewer.control!!.getView() as com.wxiwei.office.pg.control.Presentation
                val list = p.getPrintMode().getListView()!!
                val item = list.getCurrentPageView()
                val loc = IntArray(2); item.getLocationOnScreen(loc)
                (loc[0] + (rect.x + rect.width / 2f) / 9525f * list.getZoom()) to (loc[1] + (rect.y + rect.height / 2f) / 9525f * list.getZoom())
            }
            tap(x, y)
            waitFor("slide 10") { viewer.state.value.pageNumber == 10 }
            screenshot("reading_link")
        }
    }
}
