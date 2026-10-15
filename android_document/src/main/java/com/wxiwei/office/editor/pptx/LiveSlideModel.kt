/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.editor.pptx

import android.graphics.Color
import com.wxiwei.office.common.picture.Picture
import com.wxiwei.office.common.shape.GroupShape
import com.wxiwei.office.common.shape.IShape
import com.wxiwei.office.common.shape.PictureShape
import com.wxiwei.office.common.shape.TextBox
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.constant.wp.WPModelConstant
import com.wxiwei.office.fc.dom4j.DocumentHelper
import com.wxiwei.office.fc.ppt.attribute.SectionAttr
import com.wxiwei.office.java.awt.Rectangle
import com.wxiwei.office.pg.control.Presentation
import com.wxiwei.office.pg.model.PGSlide
import com.wxiwei.office.simpletext.model.AttrManage
import com.wxiwei.office.simpletext.model.IAttributeSet
import com.wxiwei.office.simpletext.model.LeafElement
import com.wxiwei.office.simpletext.model.ParagraphElement
import com.wxiwei.office.simpletext.model.SectionElement
import com.wxiwei.office.system.IControl
import java.io.File

/**
 * What [LivePptxSession] needs from the screen. The Android implementation is [LiveSlideModel];
 * tests inject a fake. Every call returns false when the shape cannot be shown live, in which case
 * the edit is still saved to the file but the view only shows it after a reopen.
 */
interface LiveSlideDisplay {
    fun addTextBox(slideIndex: Int, id: Int, rectEmu: Rect, text: String, sizePt: Float, rgbHex: String, bold: Boolean): Boolean
    fun addImage(slideIndex: Int, id: Int, rectEmu: Rect, imageFile: File): Boolean
    fun addShape(slideIndex: Int, id: Int, rectEmu: Rect, prst: String, fillHex: String?, lineHex: String, lineWidthPt: Float): Boolean
    /** Current text of a shape, or null when it has no text body in the model. */
    fun shapeText(slideIndex: Int, id: Int): String?
    /** [where]: the shape's box, for a shape the view does not draw yet (an empty text box). */
    fun setShapeText(slideIndex: Int, id: Int, text: String, where: Rect? = null): Boolean
    /** The shape's text as the view has it, for [restoreText]; null when not shown live. */
    fun saveText(slideIndex: Int, id: Int): Any? = null
    fun restoreText(slideIndex: Int, id: Int, token: Any): Boolean = false
    /** Height in EMU of the shape's text as laid out now, with the box's insets; null when not shown. */
    fun textHeight(slideIndex: Int, id: Int): Long? = null
    /** Current bounds, or null when the shape is not in the model. */
    fun shapeRect(slideIndex: Int, id: Int): Rect?
    fun moveShape(slideIndex: Int, id: Int, rectEmu: Rect): Boolean
    /** Remove the model shapes with [id]; returns a token that [restoreShape] puts back, or null. */
    fun removeShape(slideIndex: Int, id: Int): Any?
    /** Puts the shapes of the slide (and of its groups) in the drawing order of [order] (shape ids). */
    fun reorder(slideIndex: Int, order: List<Int>): Boolean = false
    fun restoreShape(slideIndex: Int, token: Any): Boolean
    /** Formats the shape's text; returns a token for [restoreFormat], or null when not shown live. */
    /** Rotation in degrees of the shape, or null when it cannot be shown live. */
    fun shapeRotation(slideIndex: Int, id: Int): Float? = null
    fun rotateShape(slideIndex: Int, id: Int, degrees: Float): Boolean = false
    fun setTextFormat(slideIndex: Int, id: Int, format: TextFormat): Any? = null
    /** Formats chars [start, end) of the shape's text; a token for [restoreFormat], or null. */
    fun setTextFormat(slideIndex: Int, id: Int, start: Int, end: Int, format: TextFormat): Any? = null
    /** How the shape's first text run is drawn, for an editor over it; null when not shown. */
    fun textStyle(slideIndex: Int, id: Int): TextStyle? = null
    /** How each run of the shape's text is drawn, by char range [start, end); empty when not shown. */
    fun textRuns(slideIndex: Int, id: Int): List<Pair<IntRange, TextStyle>> = emptyList()
    fun restoreFormat(slideIndex: Int, token: Any): Boolean = false
}

/**
 * Edits the pg model of the open presentation and repaints, so changes show without a reopen.
 * Main thread only. Model bounds are pixels at 96 dpi, i.e. EMU / 9525, and model shape ids are the
 * XML cNvPr ids, so they match [PptxEditor] ids.
 */
/**
 * Text size in points, ARGB color, typeface (with its bold and italic) and underline of a shape's
 * text; [fake]: the Typeface.BOLD / ITALIC bits the typeface cannot draw, to fake on the paint.
 */
data class TextStyle(val sizePt: Float, val color: Int, val typeface: android.graphics.Typeface, val underline: Boolean = false, val fake: Int = 0)

class LiveSlideModel(private val control: IControl) : LiveSlideDisplay {
    private val presentation get() = control.getView() as? Presentation

    private fun slide(index: Int): PGSlide? = presentation?.getSlide(index)

    private fun px(emu: Long) = Math.round(emu / EMU_PER_PX.toDouble()).toInt()
    private fun emu(px: Int) = px.toLong() * EMU_PER_PX
    private fun rectangle(r: Rect) = Rectangle(px(r.x), px(r.y), maxOf(1, px(r.width)), maxOf(1, px(r.height)))

    /** Model shapes with [id], top level and inside groups, with the list that owns them. */
    private fun find(slide: PGSlide, id: Int): List<IShape> {
        val found = ArrayList<IShape>()
        fun visit(shapes: Array<IShape>) {
            for (shape in shapes) {
                if (shape.shapeID == id) found.add(shape)
                if (shape is GroupShape) visit(shape.getShapes())
            }
        }
        visit(slide.getShapes())
        return found
    }

    private fun repaint() {
        val p = presentation ?: return
        val list = p.getPrintMode().getListView()
        if (list != null) for (i in 0 until list.childCount) list.getChildAt(i).invalidate()
        p.postInvalidate()
    }

    /** One paragraph per line, laid out like the PPTX reader builds them (fc/ppt/attribute/RunAttr). */
    private fun buildSection(rect: Rectangle, text: String, paraAttr: IAttributeSet?, leafAttr: IAttributeSet?,
                             sectionAttr: IAttributeSet?): SectionElement =
        buildSection(rect, text.replace("\r\n", "\n").split('\n').map { paraAttr to listOf(it to leafAttr) }, sectionAttr)

    /** Paragraphs of (paragraph attributes, runs of (text, run attributes)); a run "\u000b" is a line break. */
    private fun buildSection(rect: Rectangle, paras: List<Pair<IAttributeSet?, List<Pair<String, IAttributeSet?>>>>,
                             sectionAttr: IAttributeSet?): SectionElement {
        val section = SectionElement()
        section.setStartOffset(0)
        val attr = section.getAttribute()!!
        if (sectionAttr != null) attr.mergeAttribute(sectionAttr)
        else SectionAttr.instance().setSectionAttribute(DocumentHelper.createElement("bodyPr"), attr, null, null, false)
        AttrManage.instance().setPageWidth(attr, (rect.width * MainConstant.PIXEL_TO_TWIPS).toInt())
        AttrManage.instance().setPageHeight(attr, (rect.height * MainConstant.PIXEL_TO_TWIPS).toInt())
        var offset = 0L
        for ((paraAttr, runs) in paras) {
            val para = ParagraphElement()
            para.setStartOffset(offset)
            paraAttr?.let { para.getAttribute()!!.mergeAttribute(it) }
            var leaf: LeafElement? = null
            for ((text, leafAttr) in runs.ifEmpty { listOf("" to null) }) {
                if (text.isEmpty() && leaf != null) continue
                leaf = LeafElement(text.replace(160.toChar(), ' '))
                leafAttr?.let { leaf.getAttribute()!!.mergeAttribute(it) }
                leaf.setStartOffset(offset)
                offset += text.length
                leaf.setEndOffset(offset)
                para.appendLeaf(leaf)
            }
            // Like RunAttr.processRun: the paragraph mark is appended to the last leaf
            leaf!!.setText(leaf.getText(null) + "\n")
            offset++
            leaf.setEndOffset(offset)
            para.setEndOffset(offset)
            section.appendParagraph(para, WPModelConstant.MAIN)
        }
        section.setEndOffset(offset)
        return section
    }

    private fun setText(box: TextBox, text: String, paraAttr: IAttributeSet?, leafAttr: IAttributeSet?, sectionAttr: IAttributeSet?) {
        box.rootView?.dispose()
        box.rootView = null // SlideDrawKit lays out a new root on the next draw
        box.element = buildSection(requireNotNull(box.bounds), text, paraAttr, leafAttr, sectionAttr)
    }

    /** New text in [box], keeping the attributes of the runs whose text stays ([Retext]). */
    private fun retext(box: TextBox, section: SectionElement, text: String) {
        val paras = ArrayList<Retext.Para<IAttributeSet?, IAttributeSet?>>()
        val count = section.getParaCollection()?.size() ?: 0
        for (i in 0 until count) {
            val para = section.getParaCollection()!!.getElementForIndex(i) as? ParagraphElement ?: continue
            val runs = ArrayList<Retext.Run<IAttributeSet?>>()
            var end: IAttributeSet? = null
            for (j in 0 until para.leafCount()) {
                val leaf = para.getElementForIndex(j) ?: continue
                end = leaf.getAttribute()
                val t = (leaf.getText(null) ?: "").let { if (j == para.leafCount() - 1) it.removeSuffix("\n") else it }
                // a line break (a:br) is its own leaf "\u000b"
                if (t == "\u000b") runs.add(Retext.Run("\n", end, true)) else if (t.isNotEmpty()) runs.add(Retext.Run(t, end))
            }
            paras.add(Retext.Para(para.getAttribute(), runs, end))
        }
        if (paras.isEmpty()) return setText(box, text, null, null, section.getAttribute()?.clone())
        val built = Retext.apply(paras, text).map { out ->
            out.source.tag?.clone() to out.runs.map { run ->
                (if (run.lineBreak) "\u000b" else run.text) to (run.tag ?: out.source.end)?.clone()
            }
        }
        box.rootView?.dispose()
        box.rootView = null
        box.element = buildSection(requireNotNull(box.bounds), built, section.getAttribute()?.clone())
    }

    override fun textHeight(slideIndex: Int, id: Int): Long? {
        val p = presentation ?: return null
        val box = slide(slideIndex)?.let { find(it, id) }?.filterIsInstance<TextBox>()?.firstOrNull() ?: return null
        val section = box.element ?: return null
        // lay the text out now, as SlideDrawKit would on the next draw, and keep that layout
        val root = box.rootView ?: com.wxiwei.office.simpletext.view.STRoot(p.getEditor(), p.getRenderersDoc()).also {
            p.getRenderersDoc()!!.appendSection(section)
            it.setWrapLine(box.isWrapLine)
            it.doLayout()
            box.rootView = it
        }
        var bottom = 0
        var view = root.getChildView()
        while (view != null) {
            bottom = maxOf(bottom, view.getY() + view.getLayoutSpan(com.wxiwei.office.constant.wp.WPViewConstant.Y_AXIS))
            view = view.getNextView()
        }
        val insetBottom = AttrManage.instance().getPageMarginBottom(section.getAttribute()) * MainConstant.TWIPS_TO_PIXEL
        return emu(Math.round(bottom + insetBottom))
    }

    private class SavedText(val box: TextBox, val section: SectionElement?)

    override fun saveText(slideIndex: Int, id: Int): Any? {
        val box = slide(slideIndex)?.let { find(it, id) }?.filterIsInstance<TextBox>()?.firstOrNull() ?: return null
        return SavedText(box, box.element)
    }

    override fun restoreText(slideIndex: Int, id: Int, token: Any): Boolean {
        val saved = token as? SavedText ?: return false
        saved.box.element = saved.section
        relayout(saved.box)
        return true
    }

    override fun addTextBox(slideIndex: Int, id: Int, rectEmu: Rect, text: String, sizePt: Float, rgbHex: String, bold: Boolean): Boolean {
        val slide = slide(slideIndex) ?: return false
        val box = TextBox()
        box.bounds = rectangle(rectEmu)
        box.shapeID = id
        box.isWrapLine = true
        val leafAttr = com.wxiwei.office.simpletext.model.AttributeSetImpl()
        AttrManage.instance().setFontSize(leafAttr, Math.round(sizePt))
        AttrManage.instance().setFontColor(leafAttr, Color.parseColor("#" + rgbHex.removePrefix("#")))
        if (bold) AttrManage.instance().setFontBold(leafAttr, true)
        // centered like the file's new text box (PptxEditor.addTextBox): algn="ctr", anchor="ctr"
        val paraAttr = com.wxiwei.office.simpletext.model.AttributeSetImpl()
        AttrManage.instance().setParaHorizontalAlign(paraAttr, com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_CENTER.toInt())
        val sectionAttr = com.wxiwei.office.simpletext.model.AttributeSetImpl()
        SectionAttr.instance().setSectionAttribute(DocumentHelper.createElement("bodyPr")!!.apply { addAttribute("wrap", "square"); addAttribute("anchor", "ctr") },
            sectionAttr, null, null, false)
        setText(box, text, paraAttr, leafAttr, sectionAttr)
        slide.appendShapes(box)
        repaint()
        return true
    }

    /** Built like ShapeManage.processAutoShape builds one of the file: an AutoShape, or a LineShape for a line. */
    override fun addShape(slideIndex: Int, id: Int, rectEmu: Rect, prst: String, fillHex: String?, lineHex: String, lineWidthPt: Float): Boolean {
        val slide = slide(slideIndex) ?: return false
        val type = com.wxiwei.office.common.autoshape.AutoShapeTypes.instance().getAutoShapeType(prst)
        val outline = com.wxiwei.office.common.borders.Line().apply {
            backgroundAndFill = com.wxiwei.office.common.bg.BackgroundAndFill().apply {
                fillType = com.wxiwei.office.common.bg.BackgroundAndFill.FILL_SOLID
                foregroundColor = Color.parseColor("#" + lineHex)
            }
            lineWidth = maxOf(1, Math.round(lineWidthPt * 96f / 72f))
        }
        val shape: com.wxiwei.office.common.shape.AbstractShape = if (type == com.wxiwei.office.common.shape.ShapeTypes.Line) {
            com.wxiwei.office.common.shape.LineShape().apply { shapeType = type; line = outline }
        } else {
            com.wxiwei.office.common.shape.AutoShape(type).apply {
                line = outline
                if (fillHex != null) backgroundAndFill = com.wxiwei.office.common.bg.BackgroundAndFill().apply {
                    fillType = com.wxiwei.office.common.bg.BackgroundAndFill.FILL_SOLID
                    foregroundColor = Color.parseColor("#" + fillHex)
                }
            }
        }
        shape.bounds = rectangle(rectEmu)
        shape.shapeID = id
        slide.appendShapes(shape)
        repaint()
        return true
    }

    override fun addImage(slideIndex: Int, id: Int, rectEmu: Rect, imageFile: File): Boolean {
        val slide = slide(slideIndex) ?: return false
        val picture = Picture()
        picture.data = imageFile.readBytes()
        picture.setPictureType(imageFile.extension.lowercase().let { if (it == "jpg") "jpeg" else it })
        val shape = PictureShape()
        shape.pictureIndex = control.getSysKit().getPictureManage().addPicture(picture)
        shape.bounds = rectangle(rectEmu)
        shape.shapeID = id
        slide.appendShapes(shape)
        repaint()
        return true
    }

    override fun shapeText(slideIndex: Int, id: Int): String? {
        val slide = slide(slideIndex) ?: return null
        val box = find(slide, id).filterIsInstance<TextBox>().firstOrNull() ?: return null
        return box.element?.getText(null)?.removeSuffix("\n")
    }

    override fun setShapeText(slideIndex: Int, id: Int, text: String, where: Rect?): Boolean {
        val slide = slide(slideIndex) ?: return false
        val shapes = find(slide, id)
        val box = shapes.filterIsInstance<TextBox>().firstOrNull()
        if (box == null) {
            // A shape without a text body in the model: add one over its bounds
            val bounds = shapes.firstOrNull()?.bounds ?: where?.let { rectangle(it) } ?: return false
            val created = TextBox()
            created.bounds = bounds
            created.shapeID = id
            created.isWrapLine = true
            setText(created, text, null, null, null)
            slide.appendShapes(created)
        } else {
            // runs already hold the inherited styles; keep them for the text that stays
            val section = box.element
            if (section is SectionElement) retext(box, section, text) else setText(box, text, null, null, null)
        }
        repaint()
        return true
    }

    override fun textStyle(slideIndex: Int, id: Int): TextStyle? {
        val box = slide(slideIndex)?.let { find(it, id) }?.filterIsInstance<TextBox>()?.firstOrNull() ?: return null
        val para = box.element?.getParaCollection()?.getElementForIndex(0) as? ParagraphElement ?: return null
        val leaf = para.getElementForIndex(0) ?: return null
        val am = AttrManage.instance()
        val p = para.getAttribute(); val l = leaf.getAttribute()
        val bold = am.getFontBold(p, l); val italic = am.getFontItalic(p, l)
        val fonts = com.wxiwei.office.simpletext.font.FontTypefaceManage.instance()
        return TextStyle(am.getFontSizeF(p, l), am.getFontColor(p, l), fonts.getFontTypeface(am.getFontName(p, l), bold, italic),
            fake = fonts.fakeStyle(am.getFontName(p, l), bold, italic))
    }

    override fun textRuns(slideIndex: Int, id: Int): List<Pair<IntRange, TextStyle>> {
        val box = slide(slideIndex)?.let { find(it, id) }?.filterIsInstance<TextBox>()?.firstOrNull() ?: return emptyList()
        val paras = box.element?.getParaCollection() ?: return emptyList()
        val am = AttrManage.instance()
        val runs = ArrayList<Pair<IntRange, TextStyle>>()
        // the text starts at offset 0: model offsets are the shape's char positions
        for (i in 0 until paras.size()) {
            val para = paras.getElementForIndex(i) as? ParagraphElement ?: continue
            val p = para.getAttribute()
            for (j in 0 until para.leafCount()) {
                val leaf = para.getElementForIndex(j) ?: continue
                val start = leaf.getStartOffset().toInt(); val end = leaf.getEndOffset().toInt()
                if (end <= start) continue
                val l = leaf.getAttribute()
                val bold = am.getFontBold(p, l); val italic = am.getFontItalic(p, l)
                val fonts = com.wxiwei.office.simpletext.font.FontTypefaceManage.instance()
                runs += (start until end) to TextStyle(am.getFontSizeF(p, l), am.getFontColor(p, l),
                    fonts.getFontTypeface(am.getFontName(p, l), bold, italic),
                    am.getFontUnderline(p, l) != 0, fonts.fakeStyle(am.getFontName(p, l), bold, italic))
            }
        }
        return runs
    }

    override fun setTextFormat(slideIndex: Int, id: Int, format: TextFormat): Any? {
        val slide = slide(slideIndex) ?: return null
        // no text drawn (an empty or undrawn text box): nothing to show
        val box = find(slide, id).filterIsInstance<TextBox>().firstOrNull() ?: return FormatToken(null, emptyList())
        val section = box.element ?: return FormatToken(null, emptyList())
        val am = AttrManage.instance()
        val saved = ArrayList<Pair<com.wxiwei.office.simpletext.model.IElement, IAttributeSet>>()
        val count = section.getParaCollection()?.size() ?: 0
        for (i in 0 until count) {
            val para = section.getParaCollection()!!.getElementForIndex(i) as? ParagraphElement ?: continue
            saved.add(para to para.getAttribute()!!.clone())
            format.align?.let {
                am.setParaHorizontalAlign(para.getAttribute(), when (it) {
                    "ctr" -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_CENTER.toInt()
                    "r" -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_RIGHT.toInt()
                    "just" -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_JUSTIFIED.toInt()
                    else -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_LEFT.toInt()
                })
            }
            for (j in 0 until para.leafCount()) {
                val leaf = para.getElementForIndex(j) ?: continue
                val attr = leaf.getAttribute()!!
                saved.add(leaf to attr.clone())
                runFormat(attr, format)
            }
        }
        relayout(box)
        return FormatToken(box, saved)
    }

    private fun runFormat(attr: IAttributeSet, format: TextFormat) {
        val am = AttrManage.instance()
        format.bold?.let { am.setFontBold(attr, it) }
        format.italic?.let { am.setFontItalic(attr, it) }
        format.underline?.let { am.setFontUnderline(attr, if (it) 1 else 0) }
        format.sizePt?.let { am.setFontSize(attr, it) }
        format.rgbHex?.let { am.setFontColor(attr, (0xFF shl 24) or it.removePrefix("#").toInt(16)) }
        format.font?.let { am.setFontName(attr, com.wxiwei.office.simpletext.font.FontTypefaceManage.instance().addFontName(it)) }
    }

    override fun setTextFormat(slideIndex: Int, id: Int, start: Int, end: Int, format: TextFormat): Any? {
        val slide = slide(slideIndex) ?: return null
        val box = find(slide, id).filterIsInstance<TextBox>().firstOrNull() ?: return FormatToken(null, emptyList())
        val section = box.element ?: return FormatToken(null, emptyList())
        val saved = ArrayList<Pair<com.wxiwei.office.simpletext.model.IElement, IAttributeSet>>()
        val count = section.getParaCollection()?.size() ?: 0
        // the text starts at offset 0: the shape's char positions are model offsets
        for (i in 0 until count) {
            val para = section.getParaCollection()!!.getElementForIndex(i) as? ParagraphElement ?: continue
            val ps = para.getStartOffset(); val pe = para.getEndOffset()
            if (pe <= start || ps >= end) continue
            format.align?.let {
                saved.add(para to para.getAttribute()!!.clone())
                AttrManage.instance().setParaHorizontalAlign(para.getAttribute(), when (it) {
                    "ctr" -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_CENTER.toInt()
                    "r" -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_RIGHT.toInt()
                    "just" -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_JUSTIFIED.toInt()
                    else -> com.wxiwei.office.constant.wp.WPAttrConstant.PARA_HOR_ALIGN_LEFT.toInt()
                })
            }
            for (leaf in para.leavesFor(maxOf(start.toLong(), ps), minOf(end.toLong(), pe))) {
                saved.add(leaf to leaf.getAttribute()!!.clone())
                runFormat(leaf.getAttribute()!!, format)
            }
        }
        relayout(box)
        return FormatToken(box, saved)
    }

    private class FormatToken(val box: TextBox?, val saved: List<Pair<com.wxiwei.office.simpletext.model.IElement, IAttributeSet>>)

    override fun restoreFormat(slideIndex: Int, token: Any): Boolean {
        val t = token as? FormatToken ?: return false
        for ((element, attr) in t.saved) element.setAttribute(attr.clone())
        t.box?.let { relayout(it) }
        return true
    }

    private fun relayout(box: TextBox) {
        box.rootView?.dispose()
        box.rootView = null // laid out again on the next draw
        repaint()
    }

    override fun shapeRect(slideIndex: Int, id: Int): Rect? {
        val slide = slide(slideIndex) ?: return null
        val b = find(slide, id).firstOrNull()?.bounds ?: return null
        return Rect(emu(b.x), emu(b.y), emu(b.width), emu(b.height))
    }

    override fun moveShape(slideIndex: Int, id: Int, rectEmu: Rect): Boolean {
        val slide = slide(slideIndex) ?: return false
        val shapes = find(slide, id)
        if (shapes.isEmpty()) return true // not drawn (an empty text box): nothing to show
        val target = rectangle(rectEmu)
        for (shape in shapes) {
            val old = shape.bounds
            if (shape is GroupShape && old != null && old.width > 0 && old.height > 0) moveGroupChildren(shape, old, target)
            shape.bounds = Rectangle(target.x, target.y, target.width, target.height)
            if (shape is TextBox) {
                val section = shape.element
                if (section != null) {
                    AttrManage.instance().setPageWidth(section.getAttribute(), (target.width * MainConstant.PIXEL_TO_TWIPS).toInt())
                    AttrManage.instance().setPageHeight(section.getAttribute(), (target.height * MainConstant.PIXEL_TO_TWIPS).toInt())
                }
                shape.rootView?.dispose()
                shape.rootView = null
            }
        }
        repaint()
        return true
    }

    override fun shapeRotation(slideIndex: Int, id: Int): Float? {
        val shape = slide(slideIndex)?.let { find(it, id) }?.firstOrNull() ?: return null
        return if (shape is GroupShape) null else shape.rotation
    }

    // groups are not rotated live: their children carry their own drawing rotation
    override fun rotateShape(slideIndex: Int, id: Int, degrees: Float): Boolean {
        val shapes = slide(slideIndex)?.let { find(it, id) } ?: return false
        // not drawn at all (an empty text box): nothing to show
        if (shapes.isEmpty()) return true
        if (shapes.any { it is GroupShape }) return false
        val normalized = (degrees % 360f + 360f) % 360f
        shapes.forEach { it.rotation = normalized }
        repaint()
        return true
    }

    private fun moveGroupChildren(group: GroupShape, from: Rectangle, to: Rectangle) {
        val sx = to.width.toDouble() / from.width; val sy = to.height.toDouble() / from.height
        for (child in group.getShapes()) {
            val b = child.bounds ?: continue
            val moved = Rectangle(
                (to.x + (b.x - from.x) * sx).toInt(), (to.y + (b.y - from.y) * sy).toInt(),
                maxOf(1, (b.width * sx).toInt()), maxOf(1, (b.height * sy).toInt()))
            if (child is GroupShape) moveGroupChildren(child, b, moved)
            child.bounds = moved
            if (child is TextBox) { child.rootView?.dispose(); child.rootView = null
            }
        }
    }

    private class Removed(val entries: List<Triple<GroupShape?, Int, IShape>>)

    override fun removeShape(slideIndex: Int, id: Int): Any? {
        val slide = slide(slideIndex) ?: return null
        // top-level shapes and group children, each with where it was
        val entries = ArrayList<Triple<GroupShape?, Int, IShape>>()
        fun visit(group: GroupShape?, shapes: Array<IShape>) {
            for (shape in shapes) {
                if (shape.shapeID == id) entries.add(Triple(group, -1, shape))
                else if (shape is GroupShape) visit(shape, shape.getShapes())
            }
        }
        visit(null, slide.getShapes())
        val removed = entries.map { (group, _, shape) -> Triple(group, group?.removeShape(shape) ?: slide.removeShape(shape), shape) }
        // not drawn at all (an empty text box): nothing to take away
        if (removed.isNotEmpty()) repaint()
        return Removed(removed)
    }

    override fun reorder(slideIndex: Int, order: List<Int>): Boolean {
        val slide = slide(slideIndex) ?: return false
        val pos = order.withIndex().associate { it.value to it.index }
        // the shapes the file orders take each other's places; others (and several model shapes of
        // one file shape) keep theirs
        fun sort(list: List<IShape>, remove: (IShape) -> Unit, insert: (Int, IShape) -> Unit) {
            val slots = list.indices.filter { pos.containsKey(list[it].shapeID) }
            val sorted = slots.map { list[it] }.sortedBy { pos[it.shapeID] }
            if (sorted != slots.map { list[it] }) {
                val rebuilt = list.toMutableList()
                slots.forEachIndexed { k, slot -> rebuilt[slot] = sorted[k] }
                list.forEach(remove)
                rebuilt.forEachIndexed { i, s -> insert(i, s) }
            }
            list.filterIsInstance<GroupShape>().forEach { g -> sort(g.getShapes().toList(), { g.removeShape(it) }, { i, s -> g.insertShape(i, s) }) }
        }
        sort(slide.getShapes().toList(), { slide.removeShape(it) }, { i, s -> slide.insertShape(i, s) })
        repaint()
        return true
    }

    override fun restoreShape(slideIndex: Int, token: Any): Boolean {
        val slide = slide(slideIndex) ?: return false
        val removed = token as? Removed ?: return false
        for ((group, index, shape) in removed.entries.reversed()) {
            if (group != null) group.insertShape(index, shape) else slide.insertShape(index, shape)
        }
        repaint()
        return true
    }

    companion object {
        const val EMU_PER_PX = 9525L
    }
}
