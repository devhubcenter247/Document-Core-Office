package com.alf06.document.reader.edit

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alf06.document.reader.R
import com.alf06.document.reader.model.DocumentType
import com.alf06.document.reader.model.RecentDocument
import com.alf06.document.reader.ui.home.document.office.ReadDocumentActivity
import com.wxiwei.office.editor.ui.EditDrafts
import com.wxiwei.office.reader.OfficeDocumentView
import com.wxiwei.office.reader.ReaderState
import com.wxiwei.office.ss.control.ExcelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the edit toolbar of ReadDocumentActivity like a user: open an .xlsx, tap the edit button,
 * type into the selected cell, save, reopen. Screenshots go to files/edit-ui on the device.
 */
@RunWith(AndroidJUnit4::class)
class ReadDocumentEditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val shots = File(context.getExternalFilesDir(null), "edit-ui").apply { mkdirs() }

    private fun sample(name: String): File {
        val file = File(context.filesDir, "edit-test-$name")
        instrumentation.context.assets.open("samples/$name").use { i -> file.outputStream().use { i.copyTo(it) } }
        return file
    }

    private fun launch(file: File, type: DocumentType): ActivityScenario<ReadDocumentActivity> {
        val intent = Intent(context, ReadDocumentActivity::class.java)
            .putExtra(ReadDocumentActivity.ARG_DOCUMENT, RecentDocument(path = file.absolutePath, size = file.length(), type = type))
        return ActivityScenario.launch(intent)
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

    private fun screenshot(name: String) {
        Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun <T : View> find(root: View, match: (View) -> Boolean): T? {
        if (match(root)) @Suppress("UNCHECKED_CAST") return root as T
        if (root is ViewGroup) for (i in 0 until root.childCount) find<T>(root.getChildAt(i), match)?.let { return it }
        return null
    }

    @Test
    fun excelEditToolbar() {
        val file = sample("sample.xlsx")
        launch(file, DocumentType.Excel).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            waitFor { scenario.let { var v = false; it.onActivity { a -> v = a.findViewById<View>(R.id.icEditApp).visibility == View.VISIBLE }; v } }
            Thread.sleep(2000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            // select "Tổng quan"!B21 (row 20, column 1), an empty cell
            scenario.onActivity {
                val excel = viewer.control!!.getView() as ExcelView
                excel.getSpreadsheet()!!.getSheetView()!!.getCurrentSheet()!!.setActiveCellRowCol(20, 1)
            }
            Thread.sleep(800)
            scenario.onActivity { a ->
                val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                val cellName = find<TextView>(panel) { it is TextView && it.text.toString() == "B21" }
                assertTrue("toolbar follows the selected cell", cellName != null)
                find<EditText>(panel) { it is EditText }!!.setText("=SUM(1,2,3)")
                find<View>(panel) { it.tag == "APPLY_VALUE" }!!.performClick()
                find<View>(panel) { it.tag == "BOLD" }!!.performClick()
            }
            screenshot("excel_toolbar")
            scenario.onActivity { a ->
                val excel = viewer.control!!.getView() as ExcelView
                val cell = excel.getSpreadsheet()!!.getWorkbook()!!.getSheet(0)!!.getRow(20)!!.getCell(1)!!
                assertEquals(6.0, cell.getNumberValue(), 0.0)
                val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                find<View>(panel) { it.tag == "SAVE" }!!.performClick()
            }
            screenshot("excel_saved")
        }
        // reopen the saved file
        launch(file, DocumentType.Excel).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(2000)
            scenario.onActivity {
                val book = (viewer.control!!.getView() as ExcelView).getSpreadsheet()!!.getWorkbook()!!
                val cell = book.getSheet(0)!!.getRow(20)!!.getCell(1)!!
                assertEquals("SUM(1,2,3)", cell.formula)
                assertTrue("bold saved", book.getFont(cell.getCellStyle()!!.getFontIndex().toInt())!!.isBold())
            }
        }
    }

    @Test
    fun customIcons() {
        val file = sample("sample.docx")
        val before = com.editor.docsdk.EditStyle.customizer
        com.editor.docsdk.EditStyle.customizer = com.editor.docsdk.EditStyle.Customizer { _, style ->
            style.copy(icons = com.editor.docsdk.EditStyle.Icons { action ->
                if (action == com.editor.docsdk.EditAction.BOLD) com.editor.docsdk.EditAction.ITALIC.icon else null
            })
        }
        try {
            launch(file, DocumentType.Doc).use { scenario ->
                lateinit var viewer: OfficeDocumentView
                scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
                waitFor { viewer.state.value.status == ReaderState.Status.Ready }
                Thread.sleep(2000)
                scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
                Thread.sleep(500)
                scenario.onActivity { a ->
                    val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                    fun pixels(tag: String): Bitmap {
                        val icon = find<android.widget.ImageView>(find<ViewGroup>(panel) { it.tag == tag }!!) { it is android.widget.ImageView }!!
                        return Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888).also { icon.draw(android.graphics.Canvas(it)) }
                    }
                    assertTrue("BOLD shows the icon from EditStyle.icons", pixels("BOLD").sameAs(pixels("ITALIC")))
                    assertTrue("other buttons keep the SDK's icon", !pixels("UNDERLINE").sameAs(pixels("ITALIC")))
                }
            }
        } finally {
            com.editor.docsdk.EditStyle.customizer = before
        }
    }

    /** The shape of [drawable] (alpha only, so tints do not count) at 48x48. */
    private fun shape(drawable: android.graphics.drawable.Drawable): IntArray {
        val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        drawable.constantState!!.newDrawable().mutate().apply { setTintList(null); setBounds(0, 0, 48, 48); draw(android.graphics.Canvas(b)) }
        return IntArray(48 * 48).also { b.getPixels(it, 0, 48, 0, 0, 48, 48) }.map { it ushr 24 }.toIntArray()
    }

    /** Same icon: at most a few pixels differ (anti-aliasing). */
    private fun sameShape(drawable: android.graphics.drawable.Drawable, id: Int): Boolean {
        val x = shape(drawable); val y = shape(context.getDrawable(id)!!)
        return x.indices.count { Math.abs(x[it] - y[it]) > 64 } < 10
    }

    @Test
    fun excelCheckAndInputsFollowStyle() {
        val file = sample("sample.xlsx")
        val italic = com.editor.docsdk.EditAction.ITALIC.icon
        val editBefore = com.editor.docsdk.EditStyle.customizer
        val dialogBefore = com.editor.docsdk.DialogStyle.customizer
        com.editor.docsdk.EditStyle.customizer = com.editor.docsdk.EditStyle.Customizer { _, style ->
            style.copy(
                icons = com.editor.docsdk.EditStyle.Icons { if (it == com.editor.docsdk.EditAction.CELL_VALUE) italic else null },
                inputHint = 0xFF00AA00.toInt(),
                inputStyler = com.editor.docsdk.InputStyler { it.setBackgroundColor(0xFFFFEE00.toInt()) },
            )
        }
        com.editor.docsdk.DialogStyle.customizer = com.editor.docsdk.DialogStyle.Customizer { _, style ->
            style.copy(inputStyler = com.editor.docsdk.InputStyler { it.setBackgroundColor(0xFF00EEFF.toInt()) })
        }
        try {
            launch(file, DocumentType.Excel).use { scenario ->
                lateinit var viewer: OfficeDocumentView
                scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
                waitFor { viewer.state.value.status == ReaderState.Status.Ready }
                Thread.sleep(2000)
                scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
                Thread.sleep(500)
                scenario.onActivity { a ->
                    val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                    val check = find<android.widget.ImageView>(panel) { it.tag == "APPLY_VALUE" }!!
                    assertTrue("the check shows the CELL_VALUE icon", sameShape(check.drawable, italic))
                    val formula = find<EditText>(panel) { it is EditText }!!
                    assertEquals(0xFF00AA00.toInt(), formula.currentHintTextColor)
                    assertEquals(0xFFFFEE00.toInt(), (formula.background as android.graphics.drawable.ColorDrawable).color)
                    lateinit var field: EditText
                    val dialog = com.wxiwei.office.editor.ui.DialogKit(a).show("t") { field = input("hint") }
                    assertEquals(0xFF00EEFF.toInt(), (field.background as android.graphics.drawable.ColorDrawable).color)
                    dialog.dismiss()
                }
            }
        } finally {
            com.editor.docsdk.EditStyle.customizer = editBefore
            com.editor.docsdk.DialogStyle.customizer = dialogBefore
        }
    }

    @Test
    fun viewerBackIcon() {
        val file = sample("sample.docx")
        val italic = com.editor.docsdk.EditAction.ITALIC.icon
        val before = com.editor.docsdk.EditStyle.customizer
        com.editor.docsdk.EditStyle.customizer = com.editor.docsdk.EditStyle.Customizer { _, style -> style.copy(backIcon = italic) }
        try {
            ActivityScenario.launch<android.app.Activity>(com.editor.docsdk.DocumentViewer.intent(context, android.net.Uri.fromFile(file))).use { scenario ->
                scenario.onActivity { a ->
                    val back = find<android.widget.ImageButton>(a.window.decorView) { it.contentDescription == a.getString(com.wxiwei.office.R.string.docsdk_back) }!!
                    assertTrue("back shows backIcon", sameShape(back.drawable, italic))
                }
            }
        } finally {
            com.editor.docsdk.EditStyle.customizer = before
        }
    }

    @Test
    fun wordEditToolbarShows() {
        val file = sample("sample.docx")
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            scenario.onActivity { a -> assertEquals(View.VISIBLE, a.findViewById<View>(R.id.editPanel).visibility) }
            // select "CHƠI CÙNG FILE MIDI" and make it italic from the toolbar
            var start = -1L
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
                for (i in 0 until map.size) {
                    val l = map.leaf(i); val k = l.text.indexOf("CHƠI CÙNG")
                    if (k >= 0) { start = l.start + k; break }
                }
                com.wxiwei.office.editor.word.WordSelection(word).setSelection(start, start + "CHƠI CÙNG".length)
            }
            scenario.onActivity { a ->
                val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                find<View>(panel) { it.tag == "ITALIC" }!!.performClick()
            }
            Thread.sleep(1500)
            scenario.onActivity {
                val doc = (viewer.control!!.getView() as com.wxiwei.office.wp.control.Word).getDocument()
                val italic = com.wxiwei.office.simpletext.model.AttrManage.instance()
                    .getFontItalic(doc.getParagraph(start + 1)!!.getAttribute(), doc.getLeaf(start + 1)!!.getAttribute())
                assertTrue("italic shows at once", italic)
            }
            screenshot("word_toolbar")
        }
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    @Test
    fun slideTapSelectAndDrag() {
        val file = sample("ppt2.pptx")
        val shapes = com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0)
        // the top shape under the centre of the last text shape of slide 1
        val target = shapes.last { it.kind == com.wxiwei.office.editor.pptx.ShapeKind.TEXT && it.rectEmu.width > 0 }
        val r = target.rectEmu
        val center = com.wxiwei.office.editor.pptx.Point(r.x + r.width / 2, r.y + r.height / 2)
        val expected = com.wxiwei.office.editor.slide.SlideGeometry.pick(shapes, center)!!
        launch(file, DocumentType.Ppt).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            var screen = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val p = viewer.control!!.getView() as com.wxiwei.office.pg.control.Presentation
                val v = com.wxiwei.office.editor.slide.SlideGeometry.emuToView(p, com.wxiwei.office.editor.pptx.Rect(center.x, center.y, 1, 1))!!
                val o = IntArray(2); p.getLocationOnScreen(o)
                screen = floatArrayOf(v.left + o[0], v.top + o[1])
            }
            val down = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, screen[0], screen[1], down)
            inject(android.view.MotionEvent.ACTION_UP, screen[0], screen[1], down)
            Thread.sleep(1000)
            scenario.onActivity { a ->
                val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                assertTrue("tap selects #${expected.id}", find<TextView>(panel) { it is TextView && it.text.startsWith("#${expected.id} ") } != null)
            }
            screenshot("slide_selected")
            // drag the selection 120 px to the right
            val start = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, screen[0], screen[1], start)
            for (i in 1..10) { Thread.sleep(16); inject(android.view.MotionEvent.ACTION_MOVE, screen[0] + i * 12, screen[1], start) }
            inject(android.view.MotionEvent.ACTION_UP, screen[0] + 120, screen[1], start)
            Thread.sleep(1000)
            screenshot("slide_dragged")
            // rotate a quarter turn with the toolbar
            scenario.onActivity { a ->
                val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                find<View>(panel) { it.tag == "ROTATE" }!!.performClick()
            }
            screenshot("slide_rotated")
            scenario.onActivity { a ->
                val panel = a.findViewById<ViewGroup>(R.id.editPanel)
                find<View>(panel) { it.tag == "SAVE" }!!.performClick()
            }
            Thread.sleep(1500)
        }
        assertEquals(90f, com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0).first { it.id == expected.id }.rotationDeg, 0.01f)
        val after = com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0).first { it.id == expected.id }.rectEmu
        assertTrue("moved right: ${expected.rectEmu} -> $after", after.x > expected.rectEmu.x)
        assertEquals(expected.rectEmu.y, after.y)
        assertEquals(expected.rectEmu.width, after.width)
    }

    @Test
    fun wordTapAndType() {
        val file = sample("sample.docx")
        var start = -1L
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            // tap the left half of the first letter of "CHƠI CÙNG"
            var screen = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
                for (i in 0 until map.size) {
                    val l = map.leaf(i); val k = l.text.indexOf("CHƠI CÙNG")
                    if (k >= 0) { start = l.start + k; break }
                }
                val r = com.wxiwei.office.editor.word.WordSelection(word).rectsFor(start, start + 1).first()
                val o = IntArray(2); word.getLocationOnScreen(o)
                screen = floatArrayOf(o[0] + r.left + r.width() * 0.25f, o[1] + r.exactCenterY())
            }
            val down = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, screen[0], screen[1], down)
            inject(android.view.MotionEvent.ACTION_UP, screen[0], screen[1], down)
            Thread.sleep(1200)
            // type like a Telex keyboard: committed text, then a composing syllable that changes
            instrumentation.runOnMainSync {
                lateinit var typing: EditText
                scenario.onActivity { a -> typing = find(a.findViewById<ViewGroup>(R.id.officeViewer)) { it is EditText && it.alpha == 0f }!! }
                assertTrue("typing field focused", typing.hasFocus())
                val ic = typing.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                ic.commitText("Xin ", 1)
                ic.setComposingText("cha", 1)
                ic.setComposingText("chaa", 1)
                ic.setComposingText("châ", 1)
                ic.setComposingText("chào", 1)
                ic.finishComposingText()
                ic.commitText("! ", 1)
                // one Backspace inside the typed text
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
            }
            Thread.sleep(800)
            screenshot("word_typed")
            scenario.onActivity {
                val doc = (viewer.control!!.getView() as com.wxiwei.office.wp.control.Word).getDocument()
                val text = doc.getText(start, start + 30)
                assertTrue(text, text.startsWith("Xin chào!CHƠI CÙNG"))
            }
            scenario.onActivity { a ->
                find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick()
            }
            Thread.sleep(2000)
        }
        val xml = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }
        val plain = Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(xml).joinToString("") { it.groupValues[1] }
        assertTrue("saved", plain.contains("Xin chào!CHƠI CÙNG"))
    }

    @Test
    fun wordCaretScrollsAboveKeyboard() {
        val file = sample("sample.docx")
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            var point = floatArrayOf(0f, 0f)
            var height = 0; var scroll = 0
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val o = IntArray(2); word.getLocationOnScreen(o)
                height = word.height; scroll = word.scrollY
                point = floatArrayOf(o[0] + word.width / 2f, o[1] + word.height * 0.85f)
            }
            val down = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, point[0], point[1], down)
            inject(android.view.MotionEvent.ACTION_UP, point[0], point[1], down)
            Thread.sleep(2500)
            screenshot("word_caret_keyboard")
            scenario.onActivity { a ->
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val ime = androidx.core.view.ViewCompat.getRootWindowInsets(word)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                if (ime > 0) {
                    assertTrue("scrolled for the keyboard: $scroll -> ${word.scrollY}", word.scrollY > scroll)
                    val panel = a.findViewById<View>(R.id.editPanel)
                    val p = IntArray(2); panel.getLocationOnScreen(p)
                    assertTrue("toolbar above the keyboard", p[1] + panel.height <= word.rootView.height - ime + 1)
                }
            }
        }
    }

    @Test
    fun wordSelectionHandleDrag() {
        val file = sample("sample.docx")
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            // long-press "CHƠI" to select it
            var start = -1L
            var point = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
                for (i in 0 until map.size) {
                    val l = map.leaf(i); val k = l.text.indexOf("CHƠI CÙNG")
                    if (k >= 0) { start = l.start + k; break }
                }
                val r = com.wxiwei.office.editor.word.WordSelection(word).rectsFor(start, start + 2).first()
                val o = IntArray(2); word.getLocationOnScreen(o)
                point = floatArrayOf(o[0] + r.exactCenterX(), o[1] + r.exactCenterY())
            }
            val down = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, point[0], point[1], down)
            Thread.sleep(900)
            inject(android.view.MotionEvent.ACTION_UP, point[0], point[1], down)
            Thread.sleep(800)
            var knob = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val sel = com.wxiwei.office.editor.word.WordSelection(word)
                assertEquals(start until start + 4, sel.selection())
                val c = sel.caretRect(start + 4)!!
                val o = IntArray(2); word.getLocationOnScreen(o)
                val d = word.resources.displayMetrics.density
                knob = floatArrayOf(o[0] + c.left.toFloat(), o[1] + c.bottom + 9 * d)
            }
            // drag the end handle to the right, over "CÙNG FILE"
            val t = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, knob[0], knob[1], t)
            for (i in 1..10) { Thread.sleep(16); inject(android.view.MotionEvent.ACTION_MOVE, knob[0] + i * 25, knob[1], t) }
            inject(android.view.MotionEvent.ACTION_UP, knob[0] + 250, knob[1], t)
            Thread.sleep(600)
            screenshot("word_handles")
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val sel = com.wxiwei.office.editor.word.WordSelection(word).selection()!!
                assertEquals(start, sel.first)
                assertTrue("end moved right: $sel", sel.last + 1 > start + 6)
            }
        }
    }

    /** Bold "CHƠI CÙNG" from the toolbar; returns its offset. */
    /** A tap on the header text of page 1 puts the caret in the header; typing shows there and is saved to header1.xml. */
    @Test
    fun wordTapAndTypeInHeader() {
        val file = sample("sample.docx")
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            var header = -1L
            var screen = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
                for (i in 0 until map.size) {
                    val l = map.leaf(i); val k = l.text.indexOf("Chơi cùng")
                    if (k >= 0 && l.start >= com.wxiwei.office.constant.wp.WPModelConstant.HEADER) { header = l.start + k; break }
                }
                assertTrue("header text mapped", header > 0)
                val sel = com.wxiwei.office.editor.word.WordSelection(word).apply { storyPage = 0 }
                val r = sel.rectsFor(header, header + 1).first()
                val o = IntArray(2); word.getLocationOnScreen(o)
                // the left half of "C": the caret goes before it
                screen = floatArrayOf(o[0] + r.left + r.width() * 0.25f, o[1] + r.exactCenterY())
            }
            val down = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, screen[0], screen[1], down)
            inject(android.view.MotionEvent.ACTION_UP, screen[0], screen[1], down)
            Thread.sleep(1200)
            instrumentation.runOnMainSync {
                lateinit var typing: EditText
                scenario.onActivity { a -> typing = find(a.findViewById<ViewGroup>(R.id.officeViewer)) { it is EditText && it.alpha == 0f }!! }
                assertTrue("typing field focused", typing.hasFocus())
                typing.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText("Học ", 1)
            }
            Thread.sleep(1500)
            screenshot("word_header_typed")
            scenario.onActivity {
                val doc = (viewer.control!!.getView() as com.wxiwei.office.wp.control.Word).getDocument()
                val text = doc.getText(header, header + 13)
                assertTrue(text, text == "Học Chơi cùng")
            }
            scenario.onActivity { a ->
                find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick()
            }
            Thread.sleep(2000)
        }
        val xml = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry("word/header1.xml")).readBytes().toString(Charsets.UTF_8) }
        val plain = Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>").findAll(xml).joinToString("") { it.groupValues[1] }
        assertTrue(plain, plain.contains("PianoLearn — Học Chơi cùng MIDI"))
    }

    private fun boldTitle(scenario: ActivityScenario<ReadDocumentActivity>, viewer: OfficeDocumentView, file: File): Long {
        var start = -1L
        scenario.onActivity {
            val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
            val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
            for (i in 0 until map.size) {
                val l = map.leaf(i); val k = l.text.indexOf("PianoLearn — Tính")
                if (k >= 0) { start = l.start + k; break }
            }
            com.wxiwei.office.editor.word.WordSelection(word).setSelection(start, start + "PianoLearn".length)
        }
        scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "BOLD" }!!.performClick() }
        Thread.sleep(800)
        return start
    }

    private fun savedXml(file: File) = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }

    /** The run holding "PianoLearn" in the saved subtitle is bold. */
    private fun subtitleBold(file: File): Boolean {
        val xml = savedXml(file)
        val i = xml.indexOf(">PianoLearn")
        if (i < 0) return false
        val run = xml.substring(xml.lastIndexOf("<w:r>", i).coerceAtLeast(xml.lastIndexOf("<w:r ", i)), i)
        return run.contains("<w:b/>") || run.contains("<w:b ")
    }

    @Test
    fun unsavedEditsDraftAndPrompt() {
        val file = sample("sample.docx")
        val context = instrumentation.targetContext
        EditDrafts.delete(context, file)
        assertTrue(!subtitleBold(file))
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            boldTitle(scenario, viewer, file)
            // to the background: the edit goes to a draft, the file is untouched
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            Thread.sleep(1000)
            assertTrue("draft written", EditDrafts.pending(context, file) != null)
            assertTrue("file untouched", !subtitleBold(file))
        }
        // the app was closed: opening the document offers the draft
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(1500)
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Khôi phục"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            Thread.sleep(1500)
            assertTrue("draft restored into the file", subtitleBold(file))
            assertTrue("draft used up", EditDrafts.pending(context, file) == null)
            // edit again, then leave the toolbar: asked to save; discard keeps the file
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(2500)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            val xmlBefore = savedXml(file)
            var start = -1L
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                start = com.wxiwei.office.editor.word.WordSelection(word).let { sel ->
                    val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
                    var s = -1L
                    for (i in 0 until map.size) { val l = map.leaf(i); val k = l.text.indexOf("Parse MIDI"); if (k >= 0) { s = l.start + k; break } }
                    sel.setSelection(s, s + 5); s
                }
            }
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "ITALIC" }!!.performClick() }
            Thread.sleep(500)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(800)
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Bỏ thay đổi"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            Thread.sleep(1500)
            assertEquals("discarded edits are not saved", xmlBefore, savedXml(file))
            scenario.onActivity { a -> assertEquals(View.GONE, a.findViewById<View>(R.id.editPanel).visibility) }
        }
    }

    @Test
    fun slideInlineTextEdit() {
        val file = sample("ppt2.pptx")
        val shapes = com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0)
        val target = shapes.last { it.kind == com.wxiwei.office.editor.pptx.ShapeKind.TEXT && it.rectEmu.width > 0 }
        val r = target.rectEmu
        val center = com.wxiwei.office.editor.pptx.Point(r.x + r.width / 2, r.y + r.height / 2)
        val expected = com.wxiwei.office.editor.slide.SlideGeometry.pick(shapes, center)!!
        launch(file, DocumentType.Ppt).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            var screen = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val p = viewer.control!!.getView() as com.wxiwei.office.pg.control.Presentation
                val v = com.wxiwei.office.editor.slide.SlideGeometry.emuToView(p, com.wxiwei.office.editor.pptx.Rect(center.x, center.y, 1, 1))!!
                val o = IntArray(2); p.getLocationOnScreen(o)
                screen = floatArrayOf(v.left + o[0], v.top + o[1])
            }
            fun tap(x: Float, y: Float) {
                val t = android.os.SystemClock.uptimeMillis()
                inject(android.view.MotionEvent.ACTION_DOWN, x, y, t)
                inject(android.view.MotionEvent.ACTION_UP, x, y, t)
                Thread.sleep(1000)
            }
            tap(screen[0], screen[1]) // select
            tap(screen[0], screen[1]) // edit in place
            scenario.onActivity {
                val edit = find<EditText>(viewer) { it is EditText }
                assertTrue("in-place editor open", edit != null && edit.hasFocus())
                assertEquals(expected.text, edit!!.text.toString())
                edit.setText("SỬA TẠI CHỖ")
            }
            screenshot("slide_inline")
            // a tap elsewhere on the slide commits
            scenario.onActivity { }
            val p = IntArray(2); var size = intArrayOf(0, 0)
            scenario.onActivity { viewer.getLocationOnScreen(p); size = intArrayOf(viewer.width, viewer.height) }
            tap(p[0] + size[0] * 0.15f, p[1] + size[1] * 0.5f)
            scenario.onActivity { assertTrue("editor closed", find<EditText>(viewer) { it is EditText } == null) }
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick() }
            Thread.sleep(1500)
        }
        assertEquals("SỬA TẠI CHỖ", com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0).first { it.id == expected.id }.text)
    }

    /** Screenshots of the caret: in body text, in a table cell, and after Save (for a visual check). */
    @Test
    fun wordCaretScreenshots() {
        val file = sample("sample.docx")
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(800)
            fun tapOn(needle: String) {
                var screen = floatArrayOf(0f, 0f)
                scenario.onActivity {
                    val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                    val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
                    var start = -1L
                    for (i in 0 until map.size) { val l = map.leaf(i); val k = l.text.indexOf(needle); if (k >= 0) { start = l.start + k; break } }
                    val r = com.wxiwei.office.editor.word.WordSelection(word).rectsFor(start + 2, start + 3).first()
                    val o = IntArray(2); word.getLocationOnScreen(o)
                    screen = floatArrayOf(o[0] + r.exactCenterX(), o[1] + r.exactCenterY())
                }
                val t = android.os.SystemClock.uptimeMillis()
                inject(android.view.MotionEvent.ACTION_DOWN, screen[0], screen[1], t)
                inject(android.view.MotionEvent.ACTION_UP, screen[0], screen[1], t)
                Thread.sleep(1500)
            }
            tapOn("PianoLearn — Tính năng")
            scenario.onActivity {
                val kids = (0 until viewer.childCount).map { i -> viewer.getChildAt(i).let { c -> c.javaClass.simpleName + "(" + c.width + "x" + c.height + " vis=" + c.visibility + ")" } }
                android.util.Log.i("CaretDebug", "viewer children: $kids padding=${viewer.paddingBottom}")
                val caret = (0 until viewer.childCount).map { viewer.getChildAt(it) }.firstOrNull { it.javaClass.simpleName == "WordCaretOverlay" }
                if (caret != null) {
                    val f = caret.javaClass.getDeclaredField("visible").apply { isAccessible = true }
                    val r = caret.javaClass.getDeclaredField("rect").apply { isAccessible = true }
                    val act = caret.javaClass.getDeclaredMethod("getActive").invoke(caret)
                    android.util.Log.i("CaretDebug", "caret active=$act visible=${f.get(caret)} rect=${r.get(caret)}")
                }
            }
            repeat(6) { k ->
                val bmp = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(shots, "caret_burst_$k.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                Thread.sleep(180)
            }
            screenshot("caret_body")
            tapOn("MidiConverter.kt, MidiAccompaniment")
            repeat(4) { k ->
                val bmp = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(shots, "caret_cellburst_$k.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                Thread.sleep(180)
            }
            screenshot("caret_cell")
            instrumentation.runOnMainSync {
                scenario.onActivity { a ->
                    lateinit var typing: EditText
                    typing = find(a.findViewById<ViewGroup>(R.id.officeViewer)) { it is EditText && it.alpha == 0f }!!
                    typing.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText("X", 1)
                }
            }
            Thread.sleep(800)
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick() }
            Thread.sleep(4000)
            tapOn("PianoLearn — Tính năng")
            screenshot("caret_after_save")
        }
    }

    /** In the in-place slide editor, select 3 chars and pick "Đậm" in the selection menu: only they are bold, and saved. */
    @Test
    fun slideFormatSelectedText() {
        val file = sample("ppt2.pptx")
        val shapes = com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0)
        val target = shapes.last { it.kind == com.wxiwei.office.editor.pptx.ShapeKind.TEXT && it.rectEmu.width > 0 && it.text.length > 4 }
        val r = target.rectEmu
        val center = com.wxiwei.office.editor.pptx.Point(r.x + r.width / 2, r.y + r.height / 2)
        val expected = com.wxiwei.office.editor.slide.SlideGeometry.pick(shapes, center)!!
        launch(file, DocumentType.Ppt).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            var screen = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val p = viewer.control!!.getView() as com.wxiwei.office.pg.control.Presentation
                val v = com.wxiwei.office.editor.slide.SlideGeometry.emuToView(p, com.wxiwei.office.editor.pptx.Rect(center.x, center.y, 1, 1))!!
                val o = IntArray(2); p.getLocationOnScreen(o)
                screen = floatArrayOf(v.left + o[0], v.top + o[1])
            }
            fun tap(x: Float, y: Float) {
                val t = android.os.SystemClock.uptimeMillis()
                inject(android.view.MotionEvent.ACTION_DOWN, x, y, t)
                inject(android.view.MotionEvent.ACTION_UP, x, y, t)
                Thread.sleep(1000)
            }
            tap(screen[0], screen[1]) // select
            tap(screen[0], screen[1]) // edit in place
            instrumentation.runOnMainSync {
                val edit = find<EditText>(viewer) { it is EditText }!!
                edit.setSelection(0, 3)
                // the "Đậm" button of the format bar over the keyboard
                find<View>(viewer) { it.tag == "BOLD" && it.isShown }!!.performClick()
            }
            Thread.sleep(1500)
            // the editor stays open on the same part, for another format
            scenario.onActivity {
                val edit = find<EditText>(viewer) { it is EditText }
                assertTrue("editor still open", edit != null)
                assertEquals(0 to 3, edit!!.selectionStart to edit.selectionEnd)
            }
            screenshot("slide_format_selection")
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick() }
            Thread.sleep(1500)
        }
        val xml = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry("ppt/slides/slide1.xml")).readBytes().toString(Charsets.UTF_8) }
        val shape = xml.substring(Regex("<p:cNvPr[^>]*\\bid=\"${expected.id}\"").find(xml)!!.range.first).substringBefore("</p:sp>")
        val bold = Regex("<a:r><a:rPr([^>]*)>.*?<a:t>([^<]*)</a:t></a:r>").findAll(shape).filter { it.groupValues[1].contains("b=\"1\"") }.joinToString("") { it.groupValues[2] }
        assertEquals(expected.text.take(3), bold)
        assertEquals(expected.text, com.wxiwei.office.editor.pptx.PptxEditor(file).listShapes(0).first { it.id == expected.id }.text)
    }

    @Test
    fun wordBulletAtCaret() {
        val file = sample("sample.docx")
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(3000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            var start = -1L
            var point = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val map = com.wxiwei.office.editor.docx.DocxSourceMap.get(file.absolutePath)!!
                for (i in 0 until map.size) { val l = map.leaf(i); val k = l.text.indexOf("Tính năng Play-along"); if (k >= 0) { start = l.start + k; break } }
                val r = com.wxiwei.office.editor.word.WordSelection(word).rectsFor(start + 3, start + 4).first()
                val o = IntArray(2); word.getLocationOnScreen(o)
                point = floatArrayOf(o[0] + r.exactCenterX(), o[1] + r.exactCenterY())
            }
            val t = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, point[0], point[1], t)
            inject(android.view.MotionEvent.ACTION_UP, point[0], point[1], t)
            Thread.sleep(1200)
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "BULLETS" }!!.performClick() }
            Thread.sleep(1000)
            screenshot("word_bullet")
            scenario.onActivity {
                val doc = (viewer.control!!.getView() as com.wxiwei.office.wp.control.Word).getDocument()
                assertTrue("bullet on", com.wxiwei.office.simpletext.model.AttrManage.instance().getParaListID(doc.getParagraph(start)!!.getAttribute()) >= 0)
            }
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick() }
            Thread.sleep(2000)
        }
        val xml = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }
        val i = xml.indexOf("Tính năng Play-along")
        assertTrue("numPr saved", xml.lastIndexOf("<w:numPr>", i) > xml.lastIndexOf("<w:p>", i).coerceAtLeast(xml.lastIndexOf("<w:p ", i)))
    }

    @Test
    fun newWordDocumentTypeAndSave() {
        val file = File(context.filesDir, "edit-test-new.docx").apply { delete() }
        assertTrue(com.wxiwei.office.editor.DocumentCreator.create(context, com.wxiwei.office.editor.DocumentCreator.Format.WORD, file) is com.wxiwei.office.editor.EditResult.Ok)
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(2000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            // tap the empty page, below the only (empty) paragraph
            var point = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val o = IntArray(2); viewer.getLocationOnScreen(o)
                point = floatArrayOf(o[0] + viewer.width / 2f, o[1] + viewer.height * 0.3f)
            }
            val t = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, point[0], point[1], t)
            inject(android.view.MotionEvent.ACTION_UP, point[0], point[1], t)
            Thread.sleep(1200)
            instrumentation.runOnMainSync {
                lateinit var typing: EditText
                scenario.onActivity { a -> typing = find(a.findViewById<ViewGroup>(R.id.officeViewer)) { it is EditText && it.alpha == 0f }!! }
                val ic = typing.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                ic.commitText("Tài liệu mới", 1)
                ic.commitText("\n", 1)
                ic.commitText("Dòng hai", 1)
            }
            Thread.sleep(800)
            screenshot("new_word_typed")
            scenario.onActivity { a -> find<View>(a.findViewById<ViewGroup>(R.id.editPanel)) { it.tag == "SAVE" }!!.performClick() }
            Thread.sleep(2000)
        }
        val xml = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(xml, xml.contains("Tài liệu mới") && xml.contains("Dòng hai"))
    }

    @Test
    fun emptyDocumentLongPressThenTapStillTypes() {
        val file = File(context.filesDir, "edit-test-empty.docx").apply { delete() }
        assertTrue(com.wxiwei.office.editor.DocumentCreator.create(context, com.wxiwei.office.editor.DocumentCreator.Format.WORD, file) is com.wxiwei.office.editor.EditResult.Ok)
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(2000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            var point = floatArrayOf(0f, 0f)
            scenario.onActivity {
                val o = IntArray(2); viewer.getLocationOnScreen(o)
                point = floatArrayOf(o[0] + viewer.width / 2f, o[1] + viewer.height * 0.2f)
            }
            fun tap() {
                val t = android.os.SystemClock.uptimeMillis()
                inject(android.view.MotionEvent.ACTION_DOWN, point[0], point[1], t)
                inject(android.view.MotionEvent.ACTION_UP, point[0], point[1], t)
                Thread.sleep(1200)
            }
            tap()
            screenshot("empty_after_tap")
            // long-press on the empty page, then tap again
            val t = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, point[0], point[1], t)
            Thread.sleep(900)
            inject(android.view.MotionEvent.ACTION_UP, point[0], point[1], t)
            Thread.sleep(800)
            screenshot("empty_after_longpress")
            tap()
            screenshot("empty_after_tap2")
            instrumentation.runOnMainSync {
                lateinit var typing: EditText
                scenario.onActivity { a -> typing = find(a.findViewById<ViewGroup>(R.id.officeViewer)) { it is EditText && it.alpha == 0f }!! }
                var focus = ""
                scenario.onActivity { a -> focus = a.currentFocus?.toString() ?: "none" }
                assertTrue("keyboard target focused after tapping again; focus=$focus", typing.hasFocus())
                typing.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText("Vẫn gõ được", 1)
            }
            Thread.sleep(800)
            scenario.onActivity {
                val doc = (viewer.control!!.getView() as com.wxiwei.office.wp.control.Word).getDocument()
                assertTrue(doc.getText(0, doc.getAreaEnd(0)).endsWith("Vẫn gõ được\n"))
            }
        }
    }

    @Test
    fun wordClickAndType() {
        val file = File(context.filesDir, "edit-test-click.docx").apply { delete() }
        assertTrue(com.wxiwei.office.editor.DocumentCreator.create(context, com.wxiwei.office.editor.DocumentCreator.Format.WORD, file) is com.wxiwei.office.editor.EditResult.Ok)
        launch(file, DocumentType.Doc).use { scenario ->
            lateinit var viewer: OfficeDocumentView
            scenario.onActivity { viewer = it.findViewById(R.id.officeViewer) }
            waitFor { viewer.state.value.status == ReaderState.Status.Ready }
            Thread.sleep(2000)
            scenario.onActivity { it.findViewById<View>(R.id.icEditApp).performClick() }
            Thread.sleep(500)
            // the middle of the empty page
            var point = floatArrayOf(0f, 0f)
            var scrollAtTap = 0
            scenario.onActivity {
                val o = IntArray(2); viewer.getLocationOnScreen(o)
                point = floatArrayOf(o[0] + viewer.width / 2f, o[1] + viewer.height * 0.45f)
                scrollAtTap = viewer.control!!.getView()!!.scrollY
            }
            val t = android.os.SystemClock.uptimeMillis()
            inject(android.view.MotionEvent.ACTION_DOWN, point[0], point[1], t)
            inject(android.view.MotionEvent.ACTION_UP, point[0], point[1], t)
            Thread.sleep(1500)
            instrumentation.runOnMainSync {
                lateinit var typing: EditText
                scenario.onActivity { a -> typing = find(a.findViewById<ViewGroup>(R.id.officeViewer)) { it is EditText && it.alpha == 0f }!! }
                typing.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText("Giữa trang", 1)
            }
            Thread.sleep(800)
            screenshot("word_click_and_type")
            var typedY = 0
            scenario.onActivity {
                val word = viewer.control!!.getView() as com.wxiwei.office.wp.control.Word
                val doc = word.getDocument()
                val text = doc.getText(0, doc.getAreaEnd(0))
                assertTrue(text, text.endsWith("Giữa trang\n") && text.startsWith("\n\n"))
                val at = text.indexOf("Giữa").toLong()
                assertEquals("centred", com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_CENTER.toInt(),
                    com.wxiwei.office.simpletext.model.AttrManage.instance().getParaHorizontalAlign(doc.getParagraph(at)!!.getAttribute()))
                val r = com.wxiwei.office.editor.word.WordSelection(word).caretRect(at)!!
                val o = IntArray(2); word.getLocationOnScreen(o)
                // the page scrolls up when the keyboard and edit bar cover the caret
                typedY = o[1] + r.centerY() + (word.scrollY - scrollAtTap)
            }
            assertTrue("text where tapped: $typedY vs ${point[1]}", Math.abs(typedY - point[1]) < viewerLine(scenario))
        }
    }

    private fun viewerLine(scenario: ActivityScenario<ReadDocumentActivity>): Float {
        var h = 0f
        scenario.onActivity { h = it.resources.displayMetrics.density * 40 }
        return h
    }
}

