/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:          	ShapeView.java
 *
 * 编译器:            android2.2
 * 时间:             	下午5:50:05
 */
package com.wxiwei.office.wp.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.wxiwei.office.common.BackgroundDrawer
import com.wxiwei.office.common.PaintKit
import com.wxiwei.office.common.autoshape.AutoShapeKit
import com.wxiwei.office.common.picture.PictureKit
import com.wxiwei.office.common.shape.AbstractShape
import com.wxiwei.office.common.shape.AutoShape
import com.wxiwei.office.common.shape.GroupShape
import com.wxiwei.office.common.shape.IShape
import com.wxiwei.office.common.shape.PictureShape
import com.wxiwei.office.common.shape.WPAutoShape
import com.wxiwei.office.common.shape.WPChartShape
import com.wxiwei.office.common.shape.WPGroupShape
import com.wxiwei.office.common.shape.WPPictureShape
import com.wxiwei.office.common.shape.WatermarkShape
import com.wxiwei.office.constant.wp.WPModelConstant
import com.wxiwei.office.constant.wp.WPViewConstant
import com.wxiwei.office.java.awt.Rectangle
import com.wxiwei.office.simpletext.model.IElement
import com.wxiwei.office.simpletext.view.DocAttr
import com.wxiwei.office.simpletext.view.PageAttr
import com.wxiwei.office.simpletext.view.ParaAttr
import com.wxiwei.office.simpletext.view.ViewKit
import java.util.Hashtable
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave

/**
 * shape view
 */
class ShapeView : LeafView {

    companion object {
        private const val GAP = 100
    }

    private var pageAttr: PageAttr? = null

    // 字符属性
    private var wpShape: WPAutoShape? = null

    fun getShape(): WPAutoShape? = wpShape

    //
    private val rect = Rect()

    //
    private var isInlineFlag = false

    //
    private var roots: MutableMap<Int, WPSTRoot>? = null

    constructor()

    constructor(paraElem: IElement, elem: IElement, shape: AutoShape) : super(paraElem, elem) {
        wpShape = shape as WPAutoShape
        roots = Hashtable()
    }

    override fun getType(): Short {
        return WPViewConstant.SHAPE_VIEW
    }

    /**
     * 视图布局
     */
    override fun doLayout(docAttr: DocAttr?, pageAttr: PageAttr?, paraAttr: ParaAttr?, x: Int, y: Int, w: Int, h: Int, maxEnd: Long, flag: Int): Int {
        this.pageAttr = pageAttr
        val wpShape = wpShape!!

        isInlineFlag = docAttr!!.rootType.toInt() == WPViewConstant.NORMAL_ROOT.toInt()
                || (wpShape.wrap != com.wxiwei.office.common.shape.WPAbstractShape.WRAP_TOP.toInt() && wpShape.wrap != com.wxiwei.office.common.shape.WPAbstractShape.WRAP_BOTTOM.toInt())

        // Floating shapes of headers/footers stay floating (drawn on every page by PageView);
        // laying them out inline made a page-sized header background push the body off the page.
        if (wpShape.isWatermarkShape) {
            isInlineFlag = false
        }

        var width = 0
        val r = requireNotNull(wpShape.bounds)
        if (isInlineFlag) {
            width = r.width
            setSize(width, r.height)
        } else {
            if (wpShape.isWatermarkShape) {
                val watermark = wpShape as WatermarkShape

                paint = Paint()
                val paint = paint!!
                paint.isAntiAlias = true
                val str = watermark.watermartString
                if (str != null && str.length > 0) {
                    val len = str.length
                    val span = pageAttr!!.pageWidth - pageAttr.leftMargin - pageAttr.rightMargin

                    if (watermark.isAutoFontSize) {
                        var fontSize = span / len
                        paint.textSize = fontSize.toFloat()
                        paint.getTextBounds(str, 0, len, rect)
                        var preFontSize = fontSize
                        if (rect.width() < span) {
                            while (rect.width() < span) {
                                preFontSize = fontSize
                                fontSize++
                                paint.textSize = fontSize.toFloat()
                                paint.getTextBounds(str, 0, len, rect)
                            }
                        } else if (rect.width() > span) {
                            while (rect.width() > span) {
                                preFontSize = fontSize
                                fontSize--
                                paint.textSize = fontSize.toFloat()
                                paint.getTextBounds(str, 0, len, rect)
                            }
                        }

                        watermark.fontSize = preFontSize

                        paint.textSize = preFontSize.toFloat()
                    } else {
                        paint.textSize = watermark.fontSize.toFloat()
                    }

                    paint.color = watermark.fontColor
                    val alpha = Math.round(255 * watermark.getOpacity())
                    paint.alpha = alpha

                    paint.getTextBounds(str, 0, len, rect)
                    setX((pageAttr.pageWidth - rect.width()) / 2)
                    setY((pageAttr.pageHeight - rect.height()) / 2)
                }
            } else {
                PositionLayoutKit.instance().processShapePosition(this, wpShape, pageAttr!!)
            }
        }
        setEndOffset(start + 1)
        val keepOne = ViewKit.instance().getBitValue(flag, WPViewConstant.LAYOUT_FLAG_KEEPONE.toInt())
        var breakType = WPViewConstant.BREAK_NO.toInt()
        if (!keepOne && width > w) {
            breakType = WPViewConstant.BREAK_LIMIT.toInt()
        } else {
            layoutTextbox(wpShape, wpShape.groupShape)
        }
        return breakType
    }

    /** The laid-out text of text box [index] when this shape is that text box (not in a group), else null. */
    fun textRoot(index: Int): WPSTRoot? {
        val shape = wpShape ?: return null
        if (shape.groupShape != null || shape.elementIndex != index) return null
        return roots?.get(index)
    }

    private fun layoutTextbox(wpShape: WPAutoShape?, wpGroup: WPGroupShape?) {
        if (wpGroup != null) {
            val shapes = wpGroup.getShapes()
            for (shape in shapes) {
                if (shape.type.toInt() == AbstractShape.SHAPE_GROUP.toInt()) {
                    layoutTextbox(null, shape as WPGroupShape)
                } else if (shape is WPAutoShape) {
                    layoutTextbox(shape, shape.groupShape)
                }
            }
        } else if (wpShape!!.elementIndex >= 0) {
            val stRoot = WPSTRoot(getContainer()!!, getDocument()!!, wpShape.elementIndex)
            stRoot.setWrapLine(wpShape.isTextWrapLine)
            stRoot.doLayout()
            stRoot.setParentView(this)
            roots!!.put(wpShape.elementIndex, stRoot)

            if (!wpShape.isTextWrapLine) {
                //not text wrap line, adjust textbox width
                wpShape.bounds!!.width = stRoot.getAdjustTextboxWidth()
            }
        }
    }

    /**
     * 得到指定结束位置字符宽度
     */
    override fun getTextWidth(): Float {
        return (if (isInlineFlag) wpShape!!.bounds!!.width else 0).toFloat()
    }

    @Synchronized
    override fun draw(canvas: Canvas, originX: Int, originY: Int, zoom: Float) {
        if (isInlineFlag) {
            val wpShape = wpShape!!
            val dX = (x * zoom).toInt() + originX
            val dY = (y * zoom).toInt() + originY
            val r = requireNotNull(wpShape.bounds)
            rect.set(dX, dY, (dX + r.width * zoom).toInt(), (dY + r.height * zoom).toInt())
            if (wpShape.groupShape != null) {
                drawGroupShape(canvas, wpShape.groupShape, rect, zoom)
            } else if (wpShape.type.toInt() == AbstractShape.SHAPE_AUTOSHAPE.toInt()) {
                AutoShapeKit.instance().drawAutoShape(canvas, getControl(), getPageNumber(), wpShape, rect, zoom)
            } else if (wpShape.type.toInt() == AbstractShape.SHAPE_CHART.toInt()) {
                val chart = (wpShape as WPChartShape).aChart
                chart?.zoomRate = zoom
                chart?.draw(canvas, getControl(), rect.left, rect.top, rect.width(), rect.height(), PaintKit.instance().getPaint())
            }

            if (roots!!.isNotEmpty() && wpShape.elementIndex >= 0) {
                val root = roots!!.get(wpShape.elementIndex)
                if (root != null) {
                    canvas.withRotation(
                        wpShape.rotation,
                        rect.exactCenterX(),
                        rect.exactCenterY()
                    ) {
                        root.draw(this, dX, dY, zoom)
                    }
                }
            }
        }
    }

    @Synchronized
    fun drawForWrap(canvas: Canvas, originX: Int, originY: Int, zoom: Float) {
        try {
            val wpShape = wpShape!!
            val dX = (x * zoom).toInt() + originX
            val dY = (y * zoom).toInt() + originY
            val r = requireNotNull(wpShape.bounds)
            if (wpShape.isWatermarkShape) {
                // center in horizontal and vertical, and relative to margin
                val str = (wpShape as WatermarkShape).watermartString
                if (str != null && str.length > 0) {
                    canvas.withSave {

                        val paint = paint!!
                        val pageAttr = pageAttr!!
                        val oldSize = paint.textSize
                        paint.textSize = wpShape.fontSize * zoom

                        val angle = wpShape.rotation

                        val mainBodyWidth =
                            pageAttr.pageWidth - pageAttr.leftMargin - pageAttr.rightMargin
                        val mainBodyHeight =
                            pageAttr.pageHeight - pageAttr.topMargin - pageAttr.bottomMargin

                        val centerX = originX + (pageAttr.leftMargin + mainBodyWidth / 2f) * zoom
                        val centerY = originY + (pageAttr.topMargin + mainBodyHeight / 2f) * zoom

                        translate(centerX, centerY)

                        rotate(angle, 0f, 0f)

                        drawText(str, -rect.width() * zoom / 2f, 0f, paint)

                        paint.textSize = oldSize
                    }
                    return
                }
            } else {
                rect.set(dX, dY, (dX + r.width * zoom).toInt(), (dY + r.height * zoom).toInt())
                if (wpShape.groupShape != null) {
                    //maybe samrt art background, so need to be drawed
                    AutoShapeKit.instance().drawAutoShape(canvas, getControl(), getPageNumber(), wpShape, rect, zoom)
                    drawGroupShape(canvas, wpShape.groupShape, rect, zoom)
                } else if (wpShape.type.toInt() == AbstractShape.SHAPE_AUTOSHAPE.toInt()) {
                    AutoShapeKit.instance().drawAutoShape(canvas, getControl(), getPageNumber(), wpShape, rect, zoom)
                } else if (wpShape.type.toInt() == AbstractShape.SHAPE_CHART.toInt()) {
                    val chart = (wpShape as WPChartShape).aChart
                    chart?.zoomRate = zoom
                    chart?.draw(canvas, getControl(), rect.left, rect.top, rect.width(), rect.height(), PaintKit.instance().getPaint())
                }
            }
            if (roots!!.isNotEmpty() && wpShape.elementIndex >= 0) {
                val root = roots!![wpShape.elementIndex]
                if (root != null) {
                    canvas.withRotation(
                        wpShape.rotation,
                        rect.exactCenterX(),
                        rect.exactCenterY()
                    ) {
                        root.draw(this, dX, dY, zoom)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun drawGroupShape(canvas: Canvas, gs: GroupShape?, rect: Rect, zoom: Float) {
        if (gs != null) {
            val shapes = gs.getShapes()
            val gsRect = Rect()
            var r: Rectangle
            for (shapeItem in shapes) {
                var shape: IShape? = shapeItem
                if (shape!!.type.toInt() == AbstractShape.SHAPE_GROUP.toInt()) {
                    drawGroupShape(canvas, shape as GroupShape, rect, zoom)
                } else if (shape.type.toInt() == AbstractShape.SHAPE_PICTURE.toInt()) {
                    gsRect.setEmpty()
                    r = shape.bounds ?: continue
                    gsRect.left = rect.left + (r.x * zoom).toInt()
                    gsRect.top = rect.top + (r.y * zoom).toInt()
                    gsRect.right = (gsRect.left + r.width * zoom).toInt()
                    gsRect.bottom = (gsRect.top + r.height * zoom).toInt()
                    if (shape is WPPictureShape) {
                        shape = shape.getPictureShape()
                    }

                    if (shape != null) {
                        BackgroundDrawer.drawLineAndFill(canvas, getControl(), getPageNumber(),
                            shape as PictureShape, rect, zoom)

                        PictureKit.instance().drawPicture(canvas, getControl(), getPageNumber(), shape.getPicture(getControl()),
                            gsRect.left.toFloat(), gsRect.top.toFloat(), zoom, shape.bounds!!.width * zoom, shape.bounds!!.height * zoom,
                            shape.pictureEffectInfor)
                    }
                } else if (shape.type.toInt() == AbstractShape.SHAPE_AUTOSHAPE.toInt()) {
                    gsRect.setEmpty()
                    r = shape.bounds ?: continue
                    gsRect.left = rect.left + (r.x * zoom).toInt()
                    gsRect.top = rect.top + (r.y * zoom).toInt()
                    gsRect.right = (gsRect.left + r.width * zoom).toInt()
                    gsRect.bottom = (gsRect.top + r.height * zoom).toInt()
                    AutoShapeKit.instance().drawAutoShape(canvas, getControl(), getPageNumber(), shape as AutoShape, gsRect, zoom)
                    val txShape = shape as WPAutoShape
                    if (txShape.elementIndex >= 0) {
                        val root = roots!![txShape.elementIndex]
                        root?.draw(canvas, gsRect.left, gsRect.top, zoom)
                    }
                }
            }
        }
    }

    /**
     * model到视图
     */
    override fun modelToView(offset: Long, rect: Rectangle, isBack: Boolean): Rectangle {
        rect.x += getX()
        rect.y += getY()
        return rect
    }

    override fun viewToModel(x: Int, y: Int, isBack: Boolean): Long {
        return start
    }

    /**
     * 得到基线
     */
    override fun getBaseline(): Int {
        return if (isInlineFlag) wpShape!!.bounds!!.getHeight().toInt() else 0
    }

    fun isBehindDoc(): Boolean {
        val wpShape = wpShape!!
        return if (wpShape.groupShape != null) {
            wpShape.groupShape!!.wrapType.toInt() == com.wxiwei.office.common.shape.WPAbstractShape.WRAP_BOTTOM.toInt()
        } else {
            wpShape.wrap == com.wxiwei.office.common.shape.WPAbstractShape.WRAP_BOTTOM.toInt()
        }
    }

    fun isInline(): Boolean {
        return isInlineFlag
    }

    /**
     * 放回对象池
     */
    override fun free() {
    }

    override fun dispose() {
        super.dispose()
        val roots = roots
        if (roots != null) {
            for (key in roots.keys) {
                roots[key]?.dispose()
            }
            roots.clear()
            this.roots = null
        }
        wpShape = null
    }
}
