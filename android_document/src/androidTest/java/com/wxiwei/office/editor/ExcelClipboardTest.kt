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
import com.wxiwei.office.editor.ui.ExcelEditPanel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Copy, cut and paste of cells in Excel through the editor's commands and the menu over the selection. */
@RunWith(AndroidJUnit4::class)
class ExcelClipboardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun panel(host: EditInView.Host) = host.editor.panel as ExcelEditPanel

    /** Selects [range] ("B3" or "A1:C2") and returns what the formula box shows for its first cell. */
    private suspend fun go(host: EditInView.Host, range: String): String {
        assertTrue(onMain { host.editor.run(EditAction.GO_TO_CELL, range) })
        delay(400)
        return onMain { panel(host).cellInput }
    }

    private suspend fun set(host: EditInView.Host, cell: String, input: String) {
        go(host, cell)
        assertTrue(onMain { host.editor.run(EditAction.CELL_VALUE, input) })
        delay(300)
    }

    private fun run(host: EditInView.Host, action: EditAction): Boolean = onMain { host.editor.run(action) }

    @Test
    fun copyCutPaste() {
        val file = OpenDocument.copySample("sample.xlsx", "clipboard.xlsx")
        EditInView.edit(file) {
            delay(1000)
            set(this, "AA200", "5")
            set(this, "AA201", "=AA200*2")

            // copy two cells, paste them lower: the formula follows, like Excel
            go(this, "AA200:AA201")
            assertTrue(run(this, EditAction.COPY))
            assertEquals("5\n10", onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() })
            go(this, "AC205")
            assertTrue(run(this, EditAction.PASTE))
            delay(400)
            assertEquals("pasted range selected", "AC205:AC206", onMain { panel(this).selectedRange })
            assertEquals("5", go(this, "AC205"))
            assertEquals("=AC205*2", go(this, "AC206"))

            // undo takes the whole paste back in one step
            assertTrue(run(this, EditAction.UNDO))
            delay(300)
            assertEquals("", go(this, "AC205"))
            assertEquals("", go(this, "AC206"))

            // text from another app: a cell per tab, a row per line
            onMain { clipboard.setPrimaryClip(ClipData.newPlainText("table", "Name\tQty\nPen\t3\n")) }
            go(this, "AE200")
            assertTrue(run(this, EditAction.PASTE))
            delay(400)
            assertEquals("Name", go(this, "AE200"))
            assertEquals("Qty", go(this, "AF200"))
            assertEquals("3", go(this, "AF201"))

            // cut: the cells are emptied, then pasted elsewhere
            go(this, "AA200:AA201")
            assertTrue(run(this, EditAction.CUT))
            delay(300)
            assertEquals("", go(this, "AA200"))
            go(this, "AH210")
            assertTrue(run(this, EditAction.PASTE))
            delay(400)
            assertEquals("5", go(this, "AH210"))
            assertEquals("=AH210*2", go(this, "AH211"))

            // clear empties every selected cell, not only the first
            go(this, "AE200:AF201")
            assertTrue(run(this, EditAction.CLEAR_CELLS))
            delay(300)
            assertEquals("", go(this, "AF201"))

            // a long press on a cell, lifted: the menu comes up over it
            val (x, y) = onMain {
                val ss = (EditInView.office(view).control!!.getView() as com.wxiwei.office.ss.control.ExcelView).getSpreadsheet()!!
                val o = IntArray(2).also { ss.getLocationOnScreen(it) }
                (o[0] + ss.width / 2f) to (o[1] + ss.height / 3f)
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val t = SystemClock.uptimeMillis()
            for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 900)) {
                if (action == MotionEvent.ACTION_UP) delay(900)
                MotionEvent.obtain(t, at, action, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle() }
            }
            delay(1200)
            automation.executeShellCommand("screencap -p /data/local/tmp/excel-menu.png").close()
            delay(800)
            val copy = context.getString(EditAction.COPY.label)
            // the context bar over the sheet
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val shown = automation.windows.asSequence().mapNotNull { it.root }.any { it.findAccessibilityNodeInfosByText(copy).isNotEmpty() }
            assertTrue("context bar with Copy", shown)
        }
    }
}
