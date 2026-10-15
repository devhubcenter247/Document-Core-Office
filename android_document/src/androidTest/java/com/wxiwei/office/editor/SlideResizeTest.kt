/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wxiwei.office.editor.OpenDocument.onMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The slide shown stays the same when the keyboard takes more than half of the slide area. */
@RunWith(AndroidJUnit4::class)
class SlideResizeTest {
    @Test
    fun shrinkingTheAreaKeepsTheSlide() {
        val file = OpenDocument.copySample("sample.pptx", "resize.pptx")
        OpenDocument.open(file) { reader ->
            assertTrue("needs two slides", reader.state.value.pageCount >= 2)
            onMain { reader.jumpToPage(2) }
            withTimeout(10_000) { reader.state.first { it.pageNumber == 2 } }
            delay(1000)
            val area = onMain { reader.control!!.getView()!!.parent as View }
            // what the edit panel does when the keyboard comes up
            onMain { area.setPadding(0, 0, 0, area.height * 6 / 10) }
            delay(1000)
            assertEquals(2, reader.state.value.pageNumber)
            onMain { area.setPadding(0, 0, 0, 0) }
            delay(1000)
            assertEquals(2, reader.state.value.pageNumber)
        }
    }
}
