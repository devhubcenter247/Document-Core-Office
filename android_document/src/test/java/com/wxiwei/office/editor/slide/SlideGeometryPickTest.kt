/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.editor.slide

import com.wxiwei.office.editor.pptx.Point
import com.wxiwei.office.editor.pptx.PptxShapeInfo
import com.wxiwei.office.editor.pptx.Rect
import com.wxiwei.office.editor.pptx.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideGeometryPickTest {
    private fun shape(id: Int, kind: ShapeKind, x: Long, y: Long, w: Long, h: Long, text: String = "") =
        PptxShapeInfo(id, "s$id", kind, Rect(x, y, w, h), text, false)

    @Test fun textUnderTransparentCoverWins() {
        val title = shape(1, ShapeKind.TEXT, 100, 100, 400, 50, "Title")
        val cover = shape(2, ShapeKind.TEXT, 0, 0, 1000, 600)
        assertEquals(title, SlideGeometry.pick(listOf(title, cover), Point(150, 120)))
    }

    @Test fun pictureOnBigTextBodyWins() {
        val body = shape(1, ShapeKind.TEXT, 0, 0, 1000, 600, "Body")
        val photo = shape(2, ShapeKind.PICTURE, 100, 100, 200, 200)
        assertEquals(photo, SlideGeometry.pick(listOf(body, photo), Point(150, 150)))
    }

    @Test fun groupChildBeatsGroup() {
        val group = shape(1, ShapeKind.GROUP, 0, 0, 500, 500)
        val child = shape(2, ShapeKind.TEXT, 10, 10, 100, 40, "a")
        assertEquals(child, SlideGeometry.pick(listOf(group, child), Point(20, 20)))
    }

    @Test fun sameSizeTopmostWins() {
        val a = shape(1, ShapeKind.TEXT, 0, 0, 100, 100, "a")
        val b = shape(2, ShapeKind.TEXT, 0, 0, 100, 100, "b")
        assertEquals(b, SlideGeometry.pick(listOf(a, b), Point(50, 50)))
    }

    @Test fun nearMissWithinSlop() {
        val title = shape(1, ShapeKind.TEXT, 100, 100, 400, 20, "Title")
        assertEquals(title, SlideGeometry.pick(listOf(title), Point(150, 130), slopEmu = 15))
        assertNull(SlideGeometry.pick(listOf(title), Point(150, 130), slopEmu = 5))
    }

    @Test fun emptyBoxesIgnored() {
        assertNull(SlideGeometry.pick(listOf(shape(1, ShapeKind.TEXT, 0, 0, 0, 0)), Point(0, 0), slopEmu = 100))
    }
}
