/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:          BNView.java
 *
 * 编译器:            android2.2
 * 时间:              上午9:33:36
 */
package com.wxiwei.office.wp.view

import com.wxiwei.office.system.*

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.wxiwei.office.common.bulletnumber.ListLevel
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.simpletext.font.FontTypefaceManage
import com.wxiwei.office.constant.wp.WPViewConstant
import com.wxiwei.office.simpletext.model.AttrManage
import com.wxiwei.office.simpletext.model.IDocument
import com.wxiwei.office.simpletext.model.IElement
import com.wxiwei.office.simpletext.model.StyleManage
import com.wxiwei.office.simpletext.view.AbstractView
import com.wxiwei.office.simpletext.view.CharAttr
import com.wxiwei.office.simpletext.view.DocAttr
import com.wxiwei.office.simpletext.view.PageAttr
import com.wxiwei.office.simpletext.view.ParaAttr

/**
 * bullet and number view
 */
class BNView : AbstractView() {

    // 显示文本
    private var content: Any? = null

    //
    private var paint: Paint? = null

    //
    private var charAttr: CharAttr? = null

    //
    private var currLevel: ListLevel? = null

    init {
        charAttr = CharAttr()
        paint = Paint()
        paint!!.flags = Paint.ANTI_ALIAS_FLAG
    }

    override fun getType(): Short {
        return WPViewConstant.BN_VIEW
    }

    @Synchronized
    fun doLayout(doc: IDocument, docAttr: DocAttr, pageAttr: PageAttr?, paraAttr: ParaAttr,
                 para: ParagraphView, x: Int, y: Int, w: Int, h: Int, flag: Int): Int {
        setLocation(paraAttr.listAlignIndent + x, y)
        val breakType = WPViewConstant.BREAK_NO.toInt()
        val paraElem = para.getElement()
        var leafElem: IElement? = null

        var text: String? = ""
        if (paraAttr.listID >= 0) {
            var listData = para.getControl()!!.getSysKit().getListManage().getListData(paraAttr.listID)
            if (listData == null) {
                return breakType
            }
            if (listData.linkStyleID >= 0) {
                val style = StyleManage.instance().getStyle(listData.linkStyleID.toInt())
                if (style != null) {
                    val listID = AttrManage.instance().getParaListID(style.getAttrbuteSet())
                    listData = para.getControl()!!.getSysKit().getListManage().getListData(listID)
                    if (listData == null || listData.levels.isNullOrEmpty()) {
                        return breakType
                    }
                }
            }
            leafElem = doc.getLeaf(paraElem!!.getEndOffset() - 1)
            val listLevel = listData.getLevel(paraAttr.listLevel.toInt()) ?: return breakType
            text = com.wxiwei.office.common.bulletnumber.ListKit.instance().getBulletText(listData, listLevel, docAttr, paraAttr.listLevel.toInt())
            val preParaLevel = if (docAttr.rootType.toInt() == WPViewConstant.NORMAL_ROOT.toInt())
                listData.normalPreParaLevel else listData.preParaLevel
            //
            if (paraAttr.listLevel < preParaLevel) {
                // 大于当前级别的listLevel的paraCount 置 0
                for (i in paraAttr.listLevel + 1 until 9) {
                    if (docAttr.rootType.toInt() == WPViewConstant.NORMAL_ROOT.toInt()) {
                        listData.getLevel(i)?.normalParaCount = 0
                    } else {
                        listData.getLevel(i)?.paraCount = 0
                    }
                }
            } else if (paraAttr.listLevel > preParaLevel) {
                // 在当前级别与前一个级别之间的 paraCount 也需要加 1
                for (i in preParaLevel + 1 until paraAttr.listLevel) {
                    val temp = listData.getLevel(i) ?: continue
                    if (docAttr.rootType.toInt() == WPViewConstant.NORMAL_ROOT.toInt()) {
                        temp.normalParaCount = temp.normalParaCount + 1
                    } else {
                        temp.paraCount = temp.paraCount + 1
                    }
                }
            }
            // set previous paragraph count
            if (docAttr.rootType.toInt() == WPViewConstant.NORMAL_ROOT.toInt()) {
                listLevel.normalParaCount = listLevel.normalParaCount + 1
                listData.normalPreParaLevel = paraAttr.listLevel
            } else {
                listLevel.paraCount = listLevel.paraCount + 1
                listData.preParaLevel = paraAttr.listLevel
            }
            currLevel = listLevel
        }
        // PowerPoint bullet and number
        else if (paraAttr.pgBulletID >= 0) {
            leafElem = doc.getLeaf(paraElem!!.getStartOffset())
            text = para.getControl()!!.getSysKit().getPGBulletText().getBulletText(paraAttr.pgBulletID)
            if (text == null) {
                text = ""
            }
        }

        val paint = paint!!
        val charAttr = charAttr!!
        AttrManage.instance().fillCharAttr(charAttr, paraElem!!.getAttribute(), leafElem!!.getAttribute())
        // the number takes the paragraph's font, like Word ("1." in Arial, not a serif)
        FontTypefaceManage.instance().applyTo(paint, charAttr.fontIndex, charAttr.isBold, charAttr.isItalic)
        // 字号
        paint.textSize = charAttr.fontSizeF * (charAttr.fontScale / 100f) * MainConstant.POINT_TO_PIXEL
        // 颜色
        paint.color = charAttr.fontColor

        val widths = FloatArray(text!!.length)
        paint.getTextWidths(text, widths)
        var tW = 0f
        for (i in widths.indices) {
            tW += widths[i]
        }
        val ex = ((tW + getX()) % MainConstant.DEFAULT_TAB_WIDTH_PIXEL).toInt()
        if (ex > 0) {
            tW += (MainConstant.DEFAULT_TAB_WIDTH_PIXEL - ex)
        }
        //
        setSize(tW.toInt(), Math.ceil((paint.descent() - paint.ascent()).toDouble()).toInt())
        //
        content = text
        return breakType
    }

    override fun draw(canvas: Canvas, originX: Int, originY: Int, zoom: Float) {
        val dX = (x * zoom).toInt() + originX
        val dY = (y * zoom).toInt() + originY

        val content = content
        if (content != null && content is String) {
            val paint = paint!!
            val oldFontSize = paint.textSize
            // 如果是上下标，则字号 / 2
            paint.textSize = (if (charAttr!!.subSuperScriptType > 0) oldFontSize / 2 else oldFontSize) * zoom
            canvas.drawText(content, 0, content.length, dX.toFloat(), dY - paint.ascent(), paint)

            paint.textSize = oldFontSize
        }
    }

    /**
     * 得到基线
     */
    fun getBaseline(): Int {
        return (-paint!!.ascent()).toInt()
    }

    override fun free() {
    }

    @Synchronized
    override fun dispose() {
        content = null
        paint = null
        charAttr = null
        if (currLevel != null) {
            currLevel!!.paraCount = currLevel!!.paraCount - 1
        }
    }
}
