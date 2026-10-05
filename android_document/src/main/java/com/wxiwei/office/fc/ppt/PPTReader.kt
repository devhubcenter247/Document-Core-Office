/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:           PPTReader.java
 *  
 * 编译器:             android2.2
 * 时间:               下午4:23:51
 */
package com.wxiwei.office.fc.ppt

import android.graphics.PointF
import com.wxiwei.office.common.PaintKit
import com.wxiwei.office.common.autoshape.ExtendPath
import com.wxiwei.office.common.bg.BackgroundAndFill
import com.wxiwei.office.common.bg.Gradient
import com.wxiwei.office.common.bg.LinearGradientShader
import com.wxiwei.office.common.bg.RadialGradientShader
import com.wxiwei.office.common.bg.TileShader
import com.wxiwei.office.common.borders.Line
import com.wxiwei.office.common.pictureefftect.PictureEffectInfoFactory
import com.wxiwei.office.common.shape.AbstractShape
import com.wxiwei.office.common.shape.ArbitraryPolygonShape
import com.wxiwei.office.common.shape.Arrow
import com.wxiwei.office.common.shape.GroupShape
import com.wxiwei.office.common.shape.IShape
import com.wxiwei.office.common.shape.LineShape
import com.wxiwei.office.common.shape.PictureShape
import com.wxiwei.office.common.shape.ShapeTypes
import com.wxiwei.office.common.shape.TableShape
import com.wxiwei.office.common.shape.TextBox
import com.wxiwei.office.constant.AutoShapeConstant
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.constant.wp.WPAttrConstant
import com.wxiwei.office.constant.wp.WPModelConstant
import com.wxiwei.office.fc.FCKit
import com.wxiwei.office.fc.ShapeKit
import com.wxiwei.office.fc.hslf.HSLFSlideShow
import com.wxiwei.office.fc.hslf.model.AutoShape
import com.wxiwei.office.fc.hslf.model.Fill
import com.wxiwei.office.fc.hslf.model.Freeform
import com.wxiwei.office.fc.hslf.model.HeadersFooters
import com.wxiwei.office.fc.hslf.model.Hyperlink
import com.wxiwei.office.fc.hslf.model.MasterSheet
import com.wxiwei.office.fc.hslf.model.Notes
import com.wxiwei.office.fc.hslf.model.Picture
import com.wxiwei.office.fc.hslf.model.Shape
import com.wxiwei.office.fc.hslf.model.ShapeGroup
import com.wxiwei.office.fc.hslf.model.SimpleShape
import com.wxiwei.office.fc.hslf.model.Slide
import com.wxiwei.office.fc.hslf.model.Table
import com.wxiwei.office.fc.hslf.model.TextShape
import com.wxiwei.office.fc.hslf.record.BinaryTagDataBlob
import com.wxiwei.office.fc.hslf.record.ClientVisualElementContainer
import com.wxiwei.office.fc.hslf.record.OEPlaceholderAtom
import com.wxiwei.office.fc.hslf.record.PositionDependentRecordContainer
import com.wxiwei.office.fc.hslf.record.SlideAtom.SSlideLayoutAtom
import com.wxiwei.office.fc.hslf.record.SlideProgBinaryTagContainer
import com.wxiwei.office.fc.hslf.record.SlideProgTagsContainer
import com.wxiwei.office.fc.hslf.record.TextHeaderAtom
import com.wxiwei.office.fc.hslf.record.TimeAnimateBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeColorBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeCommandBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeEffectBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeMotionBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeNodeAttributeContainer
import com.wxiwei.office.fc.hslf.record.TimeNodeContainer
import com.wxiwei.office.fc.hslf.record.TimeRotationBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeScaleBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeSetBehaviorContainer
import com.wxiwei.office.fc.hslf.record.TimeVariant
import com.wxiwei.office.fc.hslf.record.VisualShapeAtom
import com.wxiwei.office.fc.hslf.usermodel.RichTextRun
import com.wxiwei.office.fc.hslf.usermodel.SlideShow
import com.wxiwei.office.fc.ppt.bulletnumber.BulletNumberManage
import com.wxiwei.office.java.awt.Color
import com.wxiwei.office.java.awt.Rectangle
import com.wxiwei.office.java.awt.Rectanglef
import com.wxiwei.office.java.awt.geom.Rectangle2D
import com.wxiwei.office.pg.animate.ShapeAnimation
import com.wxiwei.office.pg.model.PGModel
import com.wxiwei.office.pg.model.PGNotes
import com.wxiwei.office.pg.model.PGSlide
import com.wxiwei.office.simpletext.font.FontTypefaceManage
import com.wxiwei.office.simpletext.model.AttrManage
import com.wxiwei.office.simpletext.model.LeafElement
import com.wxiwei.office.simpletext.model.ParagraphElement
import com.wxiwei.office.simpletext.model.SectionElement
import com.wxiwei.office.ss.util.format.NumericFormatter
import com.wxiwei.office.system.AbstractReader
import com.wxiwei.office.system.BackReaderThread
import com.wxiwei.office.system.IControl
import java.io.File
import java.util.Date
import kotlin.math.ceil
import kotlin.math.min

/**
 * 处理ppt文档
 *
 *
 *
 *
 * Read版本:       Read V1.0
 *
 *
 * 作者:           jhy1790
 *
 *
 * 日期:           2012-1-30
 *
 *
 * 负责人:         jhy1790
 *
 *
 *
 * 负责小组:
 *
 *
 *
 *
 */
class PPTReader @JvmOverloads constructor(
    control: IControl?, //
    private var filePath: String?, isGetThumbnail: Boolean = false
) : AbstractReader() {
    /**
     *
     */
    @Throws(Exception::class)
    override fun getModel(): Any? {
        if (model != null) {
            return model
        }
        poiSlideShow = SlideShow(HSLFSlideShow(control, filePath), isGetThumbnail)


        /*URL url = new URL("http://172.25.3.147:8080/ppt_test.ppt");
        SlideShow slideShow = new SlideShow(new HSLFSlideShow(url.openStream()));*/

        /*InputStream is = SocketClient.instance().getFile("E:/workdocument/reader/testdocument/ppt_test.ppt");
        SlideShow slideShow = new SlideShow(new HSLFSlideShow(is));*/
        model = PGModel()


        // 页面size
        val d = poiSlideShow!!.pageSize
        d.width = (d.width * MainConstant.POINT_TO_PIXEL).toInt()
        d.height = (d.height * MainConstant.POINT_TO_PIXEL).toInt()
        model!!.setPageSize(d)

        val docAtom = poiSlideShow!!.documentRecord?.documentAtom
        if (docAtom != null) {
            model!!.setSlideNumberOffset(docAtom.firstSlideNum - 1)
            model!!.setOmitTitleSlide(docAtom.getOmitTitlePlace())
        }


        //
        val count = poiSlideShow!!.slideCount
        model!!.setSlideCount(count)
        if (count == 0) {
            /*PGSlide pgSlide = new PGSlide();
            model.appendSlide(pgSlide);*/
            throw Exception("Format error")
        } else {
            poiHeadersFooters = poiSlideShow!!.slideHeadersFooters
            val len = min(count, FIRST_READ_SLIDE_NUM)
            var i = 0
            while (i < len && !abortReader) {
                processSlide(requireNotNull(poiSlideShow!!.getSlide(currentReaderIndex++)))
                i++
            }
            if (!isReaderFinish() && !isGetThumbnail) {
                BackReaderThread(this, control).start()
            }
        }

        return model
    }

    /**
     *
     *
     */
    override fun isReaderFinish(): Boolean {
        if (model != null && poiSlideShow != null) {
            return abortReader || model!!.getSlideCount() == 0 || currentReaderIndex >= poiSlideShow!!.slideCount
        }
        return true
    }

    /**
     *
     */
    @Throws(Exception::class)
    override fun backReader() {
        // the slide counts as read only once it is: the reading thread disposes a finished reader
        try { processSlide(requireNotNull(poiSlideShow!!.getSlide(currentReaderIndex))) } finally { currentReaderIndex++ }
        //control.actionEvent(EventConstant.PG_REPAINT_ID, null);
        if (!isGetThumbnail) {
            control!!.actionEvent(EventConstant.APP_COUNT_PAGES_CHANGE_ID, null)
        }
    }

    private fun isTitleSlide(slide: Slide): Boolean {
        var geometry = 0
        val sa = slide.slideRecord?.slideAtom
        if (sa != null && sa.sSlideLayoutAtom != null) {
            geometry = sa.sSlideLayoutAtom!!.geometryType
        }

        if (geometry == SSlideLayoutAtom.TITLE_SLIDE) {
            return true
        } else if (geometry == SSlideLayoutAtom.BLANK_SLIDE) {
            val shapes = slide.shapes
            for (shape in shapes) {
                if (shape !is TextShape) {
                    return false
                }

                val placeHolder = shape.placeholderAtom
                if (placeHolder != null) {
                    val placeHolderID = placeHolder.placeholderId
                    if (placeHolderID != OEPlaceholderAtom.CenteredTitle.toInt() && placeHolderID != OEPlaceholderAtom.Subtitle.toInt() && placeHolderID != -0x1)  //The value 0xFFFFFFFF specifies that the corresponding shape is not a placeholder shape.
                    {
                        return false
                    }
                }
            }

            return true
        }

        return false
    }

    private fun resetFlag() {
        hasProcessedMasterDateTime = false
        hasProcessedMasterFooter = false
        hasProcessedMasterSlideNumber = false
    }

    /**
     * 处理slide
     */
    private fun processSlide(slide: Slide) {
        val pgSlide = PGSlide()
        pgSlide.setSlideType(PGSlide.Slide_Normal.toInt())
        // slide number
        pgSlide.setSlideNo(number++)


        // 背景
        slide.background?.let { background ->
            pgSlide.setBackgroundAndFill(converFill(pgSlide, background.fill))
        }
        // master
        processMaster(pgSlide, slide)
        val sa = slide.slideRecord?.slideAtom
        if (sa != null && sa.sSlideLayoutAtom != null) {
            pgSlide.setGeometryType(sa.sSlideLayoutAtom!!.geometryType)
        }

        resetFlag()


        // 处理shape
        val shapes = slide.shapes
        for (shape in shapes) {
            processShape(pgSlide, null, shape, PGSlide.Slide_Normal.toInt())
        }


        // slide headersfooters
        if (!model!!.isOmitTitleSlide() || !isTitleSlide(slide)) {
            var tempShape: TextBox? = null
            val masterSlide: PGSlide? = model!!.getSlideMaster(pgSlide.getMasterIndexs()[0])
            if (masterSlide != null) {
                val slideHeadersFooters = slide.slideHeadersFooters
                if (slideHeadersFooters != null) {
                    pgSlide.setShowMasterHeadersFooters(false)

                    if (slideHeadersFooters.isSlideNumberVisible && !hasProcessedMasterSlideNumber) {
                        tempShape =
                            masterSlide.getTextboxByPlaceHolderID(OEPlaceholderAtom.MasterSlideNumber.toInt()) as TextBox?
                        if (tempShape != null) {
                            tempShape = processCurrentSlideHeadersFooters(
                                tempShape,
                                (pgSlide.getSlideNo() + model!!.getSlideNumberOffset()).toString()
                            )
                            pgSlide.appendShapes(tempShape)
                        }
                    }

                    if (!hasProcessedMasterFooter && slideHeadersFooters.isFooterVisible && slideHeadersFooters.footerText != null) {
                        tempShape =
                            masterSlide.getTextboxByPlaceHolderID(OEPlaceholderAtom.MasterFooter.toInt()) as TextBox?
                        if (tempShape != null) {
                            tempShape = processCurrentSlideHeadersFooters(
                                tempShape,
                                slideHeadersFooters.footerText
                            )
                            pgSlide.appendShapes(tempShape)
                        }
                    }

                    if (!hasProcessedMasterDateTime && slideHeadersFooters.isUserDateVisible && slideHeadersFooters.dateTimeText != null) {
                        tempShape =
                            masterSlide.getTextboxByPlaceHolderID(OEPlaceholderAtom.MasterDate.toInt()) as TextBox?
                        if (tempShape != null) {
                            tempShape = processCurrentSlideHeadersFooters(
                                tempShape,
                                slideHeadersFooters.dateTimeText
                            )
                            pgSlide.appendShapes(tempShape)
                        }
                    } else if (!hasProcessedMasterDateTime && slideHeadersFooters.isDateTimeVisible) {
                        //TTOD
                        val `val` = NumericFormatter.instance().getFormatContents(
                            "yyyy/m/d", Date(
                                System.currentTimeMillis()
                            )
                        )

                        tempShape =
                            masterSlide.getTextboxByPlaceHolderID(OEPlaceholderAtom.MasterDate.toInt()) as TextBox?
                        if (tempShape != null && tempShape.element != null) {
                            tempShape = processCurrentSlideHeadersFooters(tempShape, `val`)
                            pgSlide.appendShapes(tempShape)
                        }
                    }
                } else {
                    if (!hasProcessedMasterSlideNumber && poiHeadersFooters!!.isSlideNumberVisible) {
                        tempShape =
                            masterSlide.getTextboxByPlaceHolderID(OEPlaceholderAtom.MasterSlideNumber.toInt()) as TextBox?
                        if (tempShape != null) {
                            tempShape = processCurrentSlideHeadersFooters(
                                tempShape,
                                (pgSlide.getSlideNo() + model!!.getSlideNumberOffset()).toString()
                            )
                            pgSlide.appendShapes(tempShape)
                        }
                    }

                    if (!hasProcessedMasterFooter && poiHeadersFooters!!.isFooterVisible && poiHeadersFooters!!.footerText != null) {
                        tempShape =
                            masterSlide.getTextboxByPlaceHolderID(OEPlaceholderAtom.MasterFooter.toInt()) as TextBox?
                        if (tempShape != null) {
                            pgSlide.appendShapes(tempShape)
                        }
                    }

                    if (!hasProcessedMasterDateTime
                        && ((poiHeadersFooters!!.dateTimeText != null && poiHeadersFooters!!.isUserDateVisible)
                                || poiHeadersFooters!!.isDateTimeVisible)
                    ) {
                        tempShape =
                            masterSlide.getTextboxByPlaceHolderID(OEPlaceholderAtom.MasterDate.toInt()) as TextBox?

                        if (tempShape != null) {
                            pgSlide.appendShapes(tempShape)
                        }
                    }
                }
            }
        }


        // 处理 notes
        processNotes(pgSlide, slide.notesSheet)


        //group shape
        processGroupShape(pgSlide)


        //slide transition
        val slideInfotAtom = slide.slideShowSlideInfoAtom
        pgSlide.setTransition(slideInfotAtom != null && slideInfotAtom.isValidateTransition)
        //slide animation
        processSlideshow(pgSlide, slide.slideProgTagsContainer)


        //
        model!!.appendSlide(pgSlide)
        //
        if (abortReader || model!!.getSlideCount() == 0 || currentReaderIndex >= poiSlideShow!!.slideCount) {
            slideMasterIndexs!!.clear()
            slideMasterIndexs = null
            titleMasterIndexs!!.clear()
            titleMasterIndexs = null
        }
    }

    private fun processCurrentSlideHeadersFooters(styleShape: TextBox?, text: String?): TextBox? {
        var text = text
        if (styleShape != null && text != null && text.length > 0) {
            if (styleShape.element != null && styleShape.element!!
                    .getEndOffset() - styleShape.element!!.getStartOffset() > 0
            ) {
                val textShape = TextBox()
                textShape.bounds = styleShape.bounds
                textShape.isWrapLine = styleShape.isWrapLine


                // section
                val secElem = SectionElement()
                secElem.setStartOffset(0)
                secElem.setEndOffset(text.length.toLong())
                secElem.setAttribute(styleShape.element!!.getAttribute().clone())
                textShape.element = secElem


                // para
                val paraElem = styleShape.element!!.getParaCollection()!!
                    .getElementForIndex(0) as ParagraphElement
                val paraElemNew = ParagraphElement()
                paraElemNew.setStartOffset(0)
                paraElemNew.setEndOffset(text.length.toLong())
                paraElemNew.setAttribute(paraElem.getAttribute().clone())
                secElem.appendParagraph(paraElemNew, WPModelConstant.MAIN)


                // leaf
                val leafElem = paraElem.getElementForIndex(0) as LeafElement
                val str = leafElem.getText(null)
                if (str != null && str.contains("*")) {
                    text = str.replace("*", text)
                }
                val leafElemNew = LeafElement(text)
                leafElemNew.setStartOffset(0)
                leafElemNew.setEndOffset(text.length.toLong())
                leafElemNew.setAttribute(leafElem.getAttribute().clone())
                paraElemNew.appendLeaf(leafElemNew)

                return textShape
            }
        }

        return null
    }

    private fun processGroupShape(pgSlide: PGSlide) {
        val grpShape = pgSlide.getGroupShape()
        if (grpShape == null) {
            return
        }

        val count = pgSlide.getShapeCount()
        var grpSpID: Int
        for (i in 0..<count) {
            val shape = pgSlide.getShape(i)
            grpSpID = getGroupShapeID(shape!!.shapeID, grpShape)
            shape.groupShapeID = grpSpID
        }
    }

    /**
     * get group id of shape
     * @param shapeID
     * @param grpShape
     * @return
     */
    private fun getGroupShapeID(shapeID: Int, grpShape: MutableMap<Int, MutableList<Int>>): Int {
        val grpIDIter = grpShape.keys.iterator()
        while (grpIDIter.hasNext()) {
            val grpID: Int = grpIDIter.next()!!
            val childShape = grpShape.get(grpID)
            if (childShape != null && childShape.contains(shapeID)) {
                return grpID
            }
        }

        return -1
    }


    private fun processSlideshow(pgSlide: PGSlide, propTagsContainer: SlideProgTagsContainer?) {
        try {
            if (propTagsContainer == null) {
                return
            }
            var records = propTagsContainer.getChildRecords()
            if (records == null || records.isEmpty() || (records[0] !is SlideProgBinaryTagContainer)) {
                return
            }

            var rec =
                (records[0] as SlideProgBinaryTagContainer).findFirstOfType(BinaryTagDataBlob.RECORD_ID)
            if (rec == null) {
                return
            }
            rec = (rec as BinaryTagDataBlob).findFirstOfType(TimeNodeContainer.RECORD_ID)
            if (rec == null) {
                return
            }

            rec = (rec as TimeNodeContainer).findFirstOfType(TimeNodeContainer.RECORD_ID)
            if (rec == null) {
                return
            }

            records = (rec as TimeNodeContainer).getChildRecords()
            if (records != null) {
                for (record in records) {
                    if (record is TimeNodeContainer) {
                        val animations = processAnimation(pgSlide, record)
                        if (animations != null) {
                            for (anim in animations) {
                                pgSlide.addShapeAnimation(anim)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            control!!.getSysKit().getErrorKit().writerLog(e)
        }
    }

    private fun processAnimation(
        pgSlide: PGSlide,
        timeNodeContainer: TimeNodeContainer
    ): MutableList<ShapeAnimation?>? {
        var timeNodeContainer = timeNodeContainer
        try {
            val animations: MutableList<ShapeAnimation?> = ArrayList<ShapeAnimation?>()
            var childRecords = timeNodeContainer.getChildRecords()

            val timeNodeContainerList: MutableList<TimeNodeContainer> = arrayListOf()
            for (i in childRecords.indices) {
                if (childRecords[i] is TimeNodeContainer) {
                    timeNodeContainerList.add((childRecords[i] as TimeNodeContainer?)!!)
                }
            }

            var shapeAnim: ShapeAnimation?
            if (timeNodeContainerList.size > 1) {
                //After Previous
                for (container in timeNodeContainerList) {
                    val record = container.findFirstOfType(TimeNodeContainer.RECORD_ID)
                    if (record != null) {
                        shapeAnim = processSingleAnimation(pgSlide, record as TimeNodeContainer)
                        if (shapeAnim != null) {
                            animations.add(shapeAnim)
                        }
                    }
                }
            } else if (timeNodeContainerList.size == 1) {
                timeNodeContainer = timeNodeContainerList.get(0)
                timeNodeContainerList.clear()
                childRecords = timeNodeContainer.getChildRecords()
                for (i in childRecords.indices) {
                    if (childRecords[i] is TimeNodeContainer) {
                        timeNodeContainerList.add((childRecords[i] as TimeNodeContainer?)!!)
                    }
                }

                if (timeNodeContainerList.size == 1) {
                    //On Click
                    shapeAnim = processSingleAnimation(pgSlide, timeNodeContainerList.get(0))
                    if (shapeAnim != null) {
                        animations.add(shapeAnim)
                    }
                } else if (timeNodeContainerList.size > 1) {
                    //With Previous
                    for (container in timeNodeContainerList) {
                        shapeAnim = processSingleAnimation(pgSlide, container)
                        if (shapeAnim != null) {
                            animations.add(shapeAnim)
                        }
                    }
                }
            }

            return animations
        } catch (e: Exception) {
            return null
        }
    }

    private fun processSingleAnimation(
        pgSlide: PGSlide,
        timeNodeContainer: TimeNodeContainer
    ): ShapeAnimation? {
        var timeNodeContainer = timeNodeContainer
        try {
            //animation type
            var type: Byte = -1
            val timeNodeAttrContainer =
                timeNodeContainer.findFirstOfType(TimeNodeAttributeContainer.RECORD_ID) as TimeNodeAttributeContainer
            var records = timeNodeAttrContainer.getChildRecords()
            for (record in records) {
                if (record is TimeVariant) {
                    if (record.attributeType == TimeVariant.TPID_EffectType.toInt()) {
                        val t = record.value as Int
                        when (t) {
                            TimeVariant.TimeEffectType__Entrance.toInt() -> type = ShapeAnimation.SA_ENTR
                            TimeVariant.TimeEffectType__Exit.toInt() -> type = ShapeAnimation.SA_EXIT
                            TimeVariant.TimeEffectType__Emphasis.toInt() -> type = ShapeAnimation.SA_EMPH
                            else -> return null
                        }
                        break
                    }
                }
            }


            //animation target
            timeNodeContainer =
                (timeNodeContainer.findFirstOfType(TimeNodeContainer.RECORD_ID) as TimeNodeContainer?)!!

            records = timeNodeContainer.getChildRecords()
            for (record in records) {
                if (record.getRecordType() == TimeAnimateBehaviorContainer.RECORD_ID || record.getRecordType() == TimeColorBehaviorContainer.RECORD_ID || record.getRecordType() == TimeEffectBehaviorContainer.RECORD_ID || record.getRecordType() == TimeMotionBehaviorContainer.RECORD_ID || record.getRecordType() == TimeRotationBehaviorContainer.RECORD_ID || record.getRecordType() == TimeScaleBehaviorContainer.RECORD_ID || record.getRecordType() == TimeSetBehaviorContainer.RECORD_ID || record.getRecordType() == TimeCommandBehaviorContainer.RECORD_ID) {
                    val behaviorContainer =
                        (record as PositionDependentRecordContainer).findFirstOfType(
                            TimeBehaviorContainer.RECORD_ID
                        ) as TimeBehaviorContainer
                    val clientVisualElement =
                        behaviorContainer.findFirstOfType(ClientVisualElementContainer.RECORD_ID) as ClientVisualElementContainer
                    val visualElementAtom =
                        clientVisualElement.findFirstOfType(VisualShapeAtom.RECORD_ID) as VisualShapeAtom

                    when (visualElementAtom.targetElementType) {
                        VisualShapeAtom.TVET_Shape -> return (ShapeAnimation(
                            visualElementAtom.targetElementID,
                            type, ShapeAnimation.Para_All, ShapeAnimation.Para_All
                        ))

                        VisualShapeAtom.TVET_ShapeOnly -> return (ShapeAnimation(
                            visualElementAtom.targetElementID,
                            type, ShapeAnimation.Para_BG, ShapeAnimation.Para_BG
                        ))

                        VisualShapeAtom.TVET_TextRange -> {
                            val paraID = getParaIndex(pgSlide, visualElementAtom)
                            return (ShapeAnimation(
                                visualElementAtom.targetElementID,
                                type, paraID, paraID
                            ))
                        }
                    }
                    break
                }
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun getParaIndex(pgSlide: PGSlide, visualElementAtom: VisualShapeAtom): Int {
        val shapes = pgSlide.getShapes()
        val cnt = shapes.size

        var element: ParagraphElement? = null
        for (i in 0..<cnt) {
            if ((shapes[i] is TextBox)
                && shapes[i].shapeID == visualElementAtom.targetElementID
            ) {
                var offset: Long = 0
                var paraID = 0
                val sec = (shapes[i] as TextBox).element ?: continue
                element = sec.getElement(offset) as ParagraphElement?
                while (element != null) {
                    offset = element.getEndOffset()
                    if (element.getStartOffset() == visualElementAtom.data1.toLong() &&
                        (offset == visualElementAtom.data2.toLong()
                                || offset == (visualElementAtom.data2 - 1).toLong())
                    )  //last para element
                    {
                        return paraID
                    }

                    paraID++
                    element = sec.getElement(offset) as ParagraphElement?
                }

                break
            }
        }

        return ShapeAnimation.Para_All
    }

    /**
     *
     * @param pgSlide
     * @param slide
     */
    fun processMaster(pgSlide: PGSlide, slide: Slide) {
        if (slideMasterIndexs == null) {
            slideMasterIndexs = HashMap<Int?, Int?>()
        }
        if (titleMasterIndexs == null) {
            titleMasterIndexs = HashMap<Int?, Int?>()
        }

        val sheet: MasterSheet? = null
        val sa = slide.slideRecord?.slideAtom
        sa?.followMasterObjects?.let {
            if (!it) {
                return
            }
        }
        val masterId = sa?.masterID

        val master = poiSlideShow!!.slidesMasters.orEmpty()
        for (i in master.indices) {
            if (masterId == master[i]!!._getSheetNumber()) {
                var index = slideMasterIndexs!!.get(masterId)
                if (index != null) {
                    pgSlide.setMasterSlideIndex(index)
                    return
                } else {
                    val slideMaster = PGSlide()
                    slideMaster.setSlideType(PGSlide.Slide_Master.toInt())
                    slideMaster.setBackgroundAndFill(pgSlide.getBackgroundAndFill())

                    val sh = master[i]!!.shapes
                    for (j in sh.indices) {
                        processShape(slideMaster, null, sh[j], PGSlide.Slide_Master.toInt())
                    }
                    if (slideMaster.getShapeCount() > 0) {
                        index = model!!.appendSlideMaster(slideMaster)
                        pgSlide.setMasterSlideIndex(index)
                        slideMasterIndexs!!.put(masterId, index)
                    }
                }
                break
            }
        }
        if (sheet == null) {
            val titleMaster = poiSlideShow!!.titleMasters
            if (titleMaster != null) {
                for (i in titleMaster.indices) {
                    if (masterId == titleMaster[i]!!._getSheetNumber()) {
                        var index = titleMasterIndexs!!.get(masterId)
                        if (index != null) {
                            pgSlide.setLayoutSlideIndex(index)
                        } else {
                            val slideMaster = PGSlide()
                            slideMaster.setSlideType(PGSlide.Slide_Master.toInt())
                            slideMaster.setBackgroundAndFill(pgSlide.getBackgroundAndFill())

                            val sh = titleMaster[i]!!.shapes
                            for (j in sh.indices) {
                                processShape(slideMaster, null, sh[j], PGSlide.Slide_Master.toInt())
                            }
                            if (slideMaster.getShapeCount() > 0) {
                                index = model!!.appendSlideMaster(slideMaster)
                                pgSlide.setLayoutSlideIndex(index)
                                titleMasterIndexs!!.put(masterId, index)
                            }
                        }
                        break
                    }
                }
            }
        }
    }

    private fun getShapeLine(shape: SimpleShape?): Line? {
        return getShapeLine(shape, false)
    }

    private fun getShapeLine(shape: SimpleShape?, isTableCellLine: Boolean): Line? {
        var line: Line? = null
        if (shape != null && shape.hasLine()) {
            val lineWidth = Math.round(shape.lineWidth * MainConstant.POINT_TO_PIXEL).toInt()
            val dash = shape.lineDashing > AutoShapeConstant.LINESTYLE_SOLID
            val color = shape.lineColor

            if (color != null) {
                line = Line()
                val lineFill = BackgroundAndFill()
                lineFill.foregroundColor = converterColor(color)

                line.backgroundAndFill = lineFill
                line.isDash = dash
                line.lineWidth = lineWidth
            }
        } else if (isTableCellLine) {
            line = Line()
            val lineFill = BackgroundAndFill()
            lineFill.foregroundColor = -0x1000000

            line.backgroundAndFill = lineFill
        }

        return line
    }

    /**
     *
     */
    private fun converFill(pgSlide: PGSlide, fill: Fill?): BackgroundAndFill? {
        var bgFill: BackgroundAndFill? = null
        if (fill != null) {
            val type = fill.fillType

            // 填充类型
            if (type == BackgroundAndFill.FILL_BACKGROUND.toInt()) {
                bgFill = pgSlide.getBackgroundAndFill()
            } else if (type == BackgroundAndFill.FILL_SOLID.toInt()) {
                val foregroundColor = fill.foregroundColor
                if (foregroundColor != null) {
                    bgFill = BackgroundAndFill()
                    bgFill.fillType = BackgroundAndFill.FILL_SOLID
                    // 前景颜色
                    bgFill.foregroundColor = converterColor(foregroundColor)
                }
            } else if (type == BackgroundAndFill.FILL_SHADE_LINEAR.toInt() || type == BackgroundAndFill.FILL_SHADE_RADIAL.toInt() || type == BackgroundAndFill.FILL_SHADE_RECT.toInt() || type == BackgroundAndFill.FILL_SHADE_SHAPE.toInt()) {
                var angle = fill.fillAngle
                when (angle) {
                    -90, 0 -> angle += 90
                    -45 -> angle = 135
                    -135 -> angle = 45
                }
                val focus = fill.fillFocus
                val fillColor = fill.foregroundColor
                val fillbackColor = fill.fillbackColor

                var colors: IntArray? = null
                var positions: FloatArray? = null
                if (fill.isShaderPreset) {
                    colors = fill.shaderColors
                    positions = fill.shaderPositions
                }

                if (colors == null) {
                    colors = intArrayOf(
                        if (fillColor == null) -0x1 else fillColor.getRGB(),
                        if (fillbackColor == null) -0x1 else fillbackColor.getRGB()
                    )
                }
                if (positions == null) {
                    positions = floatArrayOf(0f, 1f)
                }

                var gradient: Gradient? = null
                if (type == BackgroundAndFill.FILL_SHADE_LINEAR.toInt()) {
                    gradient = LinearGradientShader(angle.toFloat(), colors, positions)
                } else if (type == BackgroundAndFill.FILL_SHADE_RADIAL.toInt() || type == BackgroundAndFill.FILL_SHADE_RECT.toInt() || type == BackgroundAndFill.FILL_SHADE_SHAPE.toInt()) {
                    gradient =
                        RadialGradientShader(
                            fill.radialGradientPositionType,
                            colors,
                            positions
                        )
                }

                if (gradient != null) {
                    gradient.focus = focus
                }

                bgFill = BackgroundAndFill()
                bgFill.fillType = type.toByte()
                bgFill.shader = gradient
            } else if (type == BackgroundAndFill.FILL_SHADE_TILE.toInt()) {
                bgFill = BackgroundAndFill()
                bgFill.fillType = BackgroundAndFill.FILL_SHADE_TILE
                // 背景为图片
                val pData = fill.pictureData
                if (pData != null) {
                    // 图片数据
                    val index = control!!.getSysKit().getPictureManage().addPicture(pData)
                    bgFill.shader = TileShader(
                        control!!.getSysKit().getPictureManage().getPicture(index),
                        TileShader.Flip_None, 1f, 1.0f
                    )
                }
            } else if (type == BackgroundAndFill.FILL_PICTURE.toInt()) {
                // 背景为图片
                val pData = fill.pictureData
                if (pData != null) {
                    bgFill = BackgroundAndFill()
                    bgFill.fillType = BackgroundAndFill.FILL_PICTURE
                    // 图片数据
                    bgFill.pictureIndex = control!!.getSysKit().getPictureManage().addPicture(pData)
                }
            } else if (type == BackgroundAndFill.FILL_PATTERN.toInt()) {
                val fillbackColor = fill.fillbackColor
                if (fillbackColor != null) {
                    bgFill = BackgroundAndFill()
                    bgFill.fillType = BackgroundAndFill.FILL_SOLID
                    // 前景颜色
                    bgFill.foregroundColor = converterColor(fillbackColor)
                }
            }
        }

        return bgFill
    }

    /**
     *
     */
    private fun processNotes(pgSlide: PGSlide, notes: Notes?) {
        if (notes != null) {
            var note = ""
            for (shape in notes.shapes) {
                if (abortReader) {
                    break
                }
                if (shape is AutoShape // 文本框
                    || shape is com.wxiwei.office.fc.hslf.model.TextBox
                )  // 占位符
                {
                    val phAtom = shape.placeholderAtom
                    if (phAtom != null && phAtom.placeholderId == OEPlaceholderAtom.NotesBody.toInt()) {
                        val text = shape.text
                        if (text != null && text.length > 0) {
                            note += text
                            note += '\n'
                        }
                    }
                }
            }
            if (note.trim { it <= ' ' }.length > 0) {
                val pgNotes = PGNotes(note.trim { it <= ' ' })
                pgSlide.setNotes(pgNotes)
            }
        }
    }

    /**
     * 处理shape
     */
    private fun processShape(pgSlide: PGSlide, parent: GroupShape?, shape: Shape, slideType: Int) {
        val addShape = true
        var placeHolderID = -1
        tableShape = false
        if (abortReader || shape.isHidden) {
            return
        }

        var rect2D: Rectangle2D? = null
        if (shape is ShapeGroup) {
            rect2D = shape.getClientAnchor2D(shape)
        } else {
            rect2D = shape.logicalAnchor2D
        }
        if (rect2D == null) {
            return
        }


//        if (slideType == PGSlide.Slide_Master && MasterSheet.isPlaceholder(shape))
//        {
//            addShape = false;
//            if (poiHeadersFooters != null)
//            {
//                OEPlaceholderAtom placeHolder = ((TextShape)shape).getPlaceholderAtom();
//                if (placeHolder != null)
//                {
//                    placeHolderID = placeHolder.placeholderId;
//                    if (placeHolderID == OEPlaceholderAtom.MasterFooter
//                        || placeHolderID == OEPlaceholderAtom.MasterSlideNumber
//                        || placeHolderID == OEPlaceholderAtom.MasterDate)
//                    {
//                        addShape = true;
//                    }
//                }
//            }
//        }
//
//        if (!addShape)
//        {
//            return;
//        }
        val rect = Rectangle()
        rect.x = (rect2D.getX() * MainConstant.POINT_TO_PIXEL).toInt()
        rect.y = (rect2D.getY() * MainConstant.POINT_TO_PIXEL).toInt()
        rect.width = (rect2D.getWidth() * MainConstant.POINT_TO_PIXEL).toInt()
        rect.height = (rect2D.getHeight() * MainConstant.POINT_TO_PIXEL).toInt()

        var fill: BackgroundAndFill? = null
        var line: Line? = null
        if (shape is SimpleShape) {
            var masterShape: IShape? = null
            if (slideType == PGSlide.Slide_Normal.toInt()) {
                //find master shape
                val masterShapeID = shape.masterShapeID

                val indexs = pgSlide.getMasterIndexs()
                val master: PGSlide? = model!!.getSlideMaster(indexs!![0])
                if (master != null) {
                    val count = master.getShapeCount()
                    for (i in 0..<count) {
                        val item = master.getShape(i)
                        if (item!!.shapeID == masterShapeID) {
                            masterShape = item
                            break
                        }
                    }
                }
            }

            fill = converFill(pgSlide, shape.fill)
            if (fill == null && masterShape != null && masterShape is AbstractShape) {
                fill = masterShape.backgroundAndFill
            }

            line = getShapeLine(shape)
            if (line == null && masterShape != null && masterShape is AbstractShape) {
                line = masterShape.line
            }
        }

        if (shape is com.wxiwei.office.fc.hslf.model.Line
            || shape is Freeform
            || shape is AutoShape
            || shape is com.wxiwei.office.fc.hslf.model.TextBox
            || shape is Picture
        ) {
            if (shape is com.wxiwei.office.fc.hslf.model.Line) {
                if (line != null) {
                    val lineShape = LineShape()
                    lineShape.shapeType = shape.shapeType
                    lineShape.bounds = rect
                    lineShape.backgroundAndFill = fill

                    lineShape.line = line

                    val adj = shape.adjustmentValue
                    if (lineShape.shapeType == ShapeTypes.BentConnector2 && adj == null) {
                        lineShape.adjustData = arrayOf<Float?>(1.0f)
                    } else {
                        lineShape.adjustData = adj
                    }

                    var type = shape.startArrowType
                    if (type > 0) {
                        lineShape.createStartArrow(
                            type.toByte(),
                            shape.startArrowWidth,
                            shape.startArrowLength
                        )
                    }

                    type = shape.endArrowType
                    if (type > 0) {
                        lineShape.createEndArrow(
                            type.toByte(),
                            shape.endArrowWidth,
                            shape.endArrowLength
                        )
                    }
                    processGrpRotation(shape as SimpleShape, lineShape)

                    lineShape.shapeID = shape.shapeId
                    if (parent == null) {
                        pgSlide.appendShapes(lineShape)
                    } else {
                        parent.appendShapes(lineShape)
                    }
                }
            } else if (shape is Freeform) {
                if (fill != null || line != null) {
                    val arbitraryPolygonShape = ArbitraryPolygonShape()
                    arbitraryPolygonShape.shapeType = ShapeTypes.ArbitraryPolygon
                    arbitraryPolygonShape.bounds = rect

                    var startArrowTailCenter: PointF? = null
                    var endArrowTailCenter: PointF? = null

                    val startArrowType = shape.startArrowType
                    if (startArrowType > 0) {
                        val arrowPathAndTail = shape.getStartArrowPathAndTail(rect)
                        if (arrowPathAndTail != null && arrowPathAndTail.arrowPath != null) {
                            startArrowTailCenter = arrowPathAndTail.arrowTailCenter
                            val pathExtend = ExtendPath()
                            pathExtend.path = arrowPathAndTail.arrowPath
                            pathExtend.setArrowFlag(true)
                            if (startArrowType != Arrow.Arrow_Arrow.toInt()) {
                                if (line == null || line.backgroundAndFill == null) {
                                    val color = (shape as SimpleShape).lineColor
                                    if (color != null) {
                                        val arrowFill = BackgroundAndFill()
                                        arrowFill.fillType = BackgroundAndFill.FILL_SOLID
                                        arrowFill.foregroundColor = converterColor(color)
                                        pathExtend.backgroundAndFill = arrowFill
                                    }
                                } else {
                                    pathExtend.backgroundAndFill = line.backgroundAndFill
                                }
                            } else {
                                pathExtend.setLine(line)
                            }

                            arbitraryPolygonShape.appendPath(pathExtend)
                        }
                    }

                    val endArrowType = shape.endArrowType
                    if (endArrowType > 0) {
                        val arrowPathAndTail = shape.getEndArrowPathAndTail(rect)
                        if (arrowPathAndTail != null && arrowPathAndTail.arrowPath != null) {
                            endArrowTailCenter = arrowPathAndTail.arrowTailCenter
                            val pathExtend = ExtendPath()
                            pathExtend.path = arrowPathAndTail.arrowPath

                            pathExtend.setArrowFlag(true)
                            if (endArrowType != Arrow.Arrow_Arrow.toInt()) {
                                if (line == null || line.backgroundAndFill == null) {
                                    val color = (shape as SimpleShape).lineColor
                                    if (color != null) {
                                        val arrowFill = BackgroundAndFill()
                                        arrowFill.fillType = BackgroundAndFill.FILL_SOLID
                                        arrowFill.foregroundColor = converterColor(color)
                                        pathExtend.backgroundAndFill = arrowFill
                                    }
                                } else {
                                    pathExtend.backgroundAndFill = line.backgroundAndFill
                                }
                            } else {
                                pathExtend.setLine(line)
                            }

                            arbitraryPolygonShape.appendPath(pathExtend)
                        }
                    }

                    val paths = shape.getFreeformPath(
                        rect,
                        startArrowTailCenter,
                        startArrowType.toByte(),
                        endArrowTailCenter,
                        endArrowType.toByte()
                    )
                    var i = 0
                    while (paths != null && i < paths.size) {
                        val pathExtend = ExtendPath()
                        pathExtend.path = paths[i]
                        if (line != null) {
                            pathExtend.setLine(line)
                        }
                        if (fill != null) {
                            pathExtend.backgroundAndFill = fill
                        }
                        arbitraryPolygonShape.appendPath(pathExtend)
                        i++
                    }

                    processGrpRotation(shape as SimpleShape, arbitraryPolygonShape)
                    arbitraryPolygonShape.shapeID = shape.shapeId
                    if (parent == null) {
                        pgSlide.appendShapes(arbitraryPolygonShape)
                    } else {
                        parent.appendShapes(arbitraryPolygonShape)
                    }
                }
            } else if (shape is AutoShape
                || shape is com.wxiwei.office.fc.hslf.model.TextBox
            ) {
                // autoShape
                placeHolderID = shape.placeholderId

                var autoShape: com.wxiwei.office.common.shape.AutoShape? = null
                if (fill != null || line != null) {
                    val shapeType = shape.shapeType
                    if (shapeType == ShapeTypes.Line || shapeType == ShapeTypes.StraightConnector1 || shapeType == ShapeTypes.BentConnector2 || shapeType == ShapeTypes.BentConnector3 || shapeType == ShapeTypes.BentConnector4 || shapeType == ShapeTypes.BentConnector5 || shapeType == ShapeTypes.CurvedConnector2 || shapeType == ShapeTypes.CurvedConnector3 || shapeType == ShapeTypes.CurvedConnector4 || shapeType == ShapeTypes.CurvedConnector5) {
                        val lineShape = LineShape()
                        lineShape.shapeType = shape.shapeType
                        lineShape.bounds = rect
                        lineShape.line = line

                        val adj = shape.adjustmentValue
                        if (lineShape.shapeType == ShapeTypes.BentConnector2 && adj == null) {
                            lineShape.adjustData = arrayOf<Float?>(1.0f)
                        } else {
                            lineShape.adjustData = adj
                        }

                        var type = shape.startArrowType
                        if (type > 0) {
                            lineShape.createStartArrow(
                                type.toByte(),
                                shape.startArrowWidth,
                                shape.startArrowLength
                            )
                        }

                        type = shape.endArrowType
                        if (type > 0) {
                            lineShape.createEndArrow(
                                type.toByte(),
                                shape.endArrowWidth,
                                shape.endArrowLength
                            )
                        }

                        autoShape = lineShape
                    } else {
                        autoShape = com.wxiwei.office.common.shape.AutoShape(shape.shapeType)
                        autoShape.setAuotShape07(false)
                        autoShape.bounds = rect
                        autoShape.backgroundAndFill = fill

                        if (line != null) {
                            autoShape.line = line
                        }
                        if (shape.shapeType != ShapeTypes.TextBox) {
                            autoShape.adjustData = shape.adjustmentValue
                        }
                    }

                    processGrpRotation(shape as SimpleShape, autoShape)

                    autoShape.shapeID = shape.shapeId
                    autoShape.placeHolderID = placeHolderID
                    if (parent == null) {
                        pgSlide.appendShapes(autoShape)
                    } else {
                        parent.appendShapes(autoShape)
                    }
                }


                // text
                val tb = TextBox()
                val mcType = shape.metaCharactersType
                tb.mcType = mcType

                processTextShape(tb, shape, rect, slideType, placeHolderID)
                if (tb.element != null) {
                    if (tb.isWordArt && autoShape != null) {
                        //set wordart background null
                        autoShape.backgroundAndFill = null
                    }
                    processGrpRotation(shape as SimpleShape, tb)
                    tb.shapeID = shape.shapeId
                    tb.placeHolderID = placeHolderID

                    if (slideType == PGSlide.Slide_Normal.toInt()) {
                        if (placeHolderID == OEPlaceholderAtom.MasterFooter.toInt()) {
                            hasProcessedMasterFooter = true
                        } else if (placeHolderID == OEPlaceholderAtom.MasterDate.toInt()
                            && (mcType == TextBox.MC_DateTime || mcType == TextBox.MC_GenericDate || mcType == TextBox.MC_RTFDateTime)
                        ) {
                            hasProcessedMasterDateTime = true
                        } else if (placeHolderID == OEPlaceholderAtom.MasterSlideNumber.toInt() && mcType == TextBox.MC_SlideNumber) {
                            hasProcessedMasterSlideNumber = true
                        }
                    }

                    if (parent == null || (slideType == PGSlide.Slide_Master.toInt() && MasterSheet.isPlaceholder(
                            shape
                        ))
                    ) {
                        pgSlide.appendShapes(tb)
                    } else {
                        parent.appendShapes(tb)
                    }
                }
            } else if (shape is Picture) {
                val poiPic = shape
                val pData = poiPic.pictureData
                if (pData != null) {
                    val pictureShape = PictureShape()
                    pictureShape.pictureIndex = control!!.getSysKit().getPictureManage().addPicture(pData)
                    pictureShape.bounds = rect
                    processGrpRotation(shape as SimpleShape, pictureShape)
                    pictureShape.shapeID = shape.shapeId
                    pictureShape.pictureEffectInfor = PictureEffectInfoFactory.getPictureEffectInfor(
                        poiPic.escherOptRecord
                    )

                    pictureShape.backgroundAndFill = fill
                    pictureShape.line = line

                    if (parent == null) {
                        pgSlide.appendShapes(pictureShape)
                    } else {
                        parent.appendShapes(pictureShape)
                    }
                } else if (fill != null || line != null) {
                    //smart background or line
                    val autoShape = com.wxiwei.office.common.shape.AutoShape(ShapeTypes.Rectangle)
                    autoShape.setAuotShape07(false)
                    autoShape.bounds = rect
                    autoShape.backgroundAndFill = fill
                    autoShape.line = line
                    if (parent == null) {
                        pgSlide.appendShapes(autoShape)
                    } else {
                        parent.appendShapes(autoShape)
                    }
                }
            }
        } else if (shape is Table) {
            val poiTable = shape
            if (poiTable != null) {
                processTable(pgSlide, poiTable, parent, slideType)
            }
        } else if (shape is ShapeGroup) {
            val shapeGroup = shape
            val groupShape = GroupShape()
            groupShape.bounds = rect
            groupShape.shapeID = shape.getShapeId()
            groupShape.flipHorizontal = shapeGroup.flipHorizontal
            groupShape.flipVertical = shapeGroup.flipVertical
            groupShape.parent = parent
            processGrpRotation(shape, groupShape)

            val sh = shape.shapes
            val childShapeLst: MutableList<Int> = ArrayList<Int>(sh.size)
            for (i in sh.indices) {
                processShape(pgSlide, groupShape, sh[i]!!, slideType)
                childShapeLst.add(sh[i]!!.shapeId)
            }
            if (parent == null) {
                pgSlide.appendShapes(groupShape)
            } else {
                parent.appendShapes(groupShape)
            }
            pgSlide.addGroupShape(shape.getShapeId(), childShapeLst)
        }
    }

    /**
     *
     * @param pgSlide
     */
    private fun processTable(
        pgSlide: PGSlide,
        poiTable: Table,
        parent: GroupShape?,
        slideType: Int
    ) {
        val clientAnchor = poiTable.getClientAnchor2D(poiTable)
        val spgrAnchor = poiTable.coordinates
        tableShape = true
        val rows = poiTable.numberOfRows
        val columns = poiTable.numberOfColumns
        val table = TableShape(rows, columns)


        //table cells
        for (i in 0..<rows) {
            for (j in 0..<columns) {
                if (abortReader) {
                    return
                }
                val poiCell = poiTable.getCell(i, j)
                if (poiCell != null) {
                    val anchor = poiCell.logicalAnchor2D
                    if (anchor != null) {
                        val scalex = spgrAnchor.getWidth() / clientAnchor.getWidth()
                        val scaley = spgrAnchor.getHeight() / clientAnchor.getHeight()

                        val x = clientAnchor.getX() + (anchor.getX() - spgrAnchor.getX()) / scalex
                        val y = clientAnchor.getY() + (anchor.getY() - spgrAnchor.getY()) / scaley
                        val width = anchor.getWidth() / scalex
                        val height = anchor.getHeight() / scaley


                        // table cell anchor
                        val rect = Rectanglef()
                        rect.x = (x * MainConstant.POINT_TO_PIXEL).toFloat()
                        rect.y = (y * MainConstant.POINT_TO_PIXEL).toFloat()
                        rect.width = (width * MainConstant.POINT_TO_PIXEL).toFloat()
                        rect.height = (height * MainConstant.POINT_TO_PIXEL).toFloat()

                        val cell = com.wxiwei.office.common.shape.TableCell()
                        //
                        cell.bounds = rect


                        // border line color
                        cell.leftLine = getShapeLine(poiCell.getBorderLeft(), true)
                        cell.rightLine = getShapeLine(poiCell.getBorderRight(), true)
                        cell.topLine = getShapeLine(poiCell.getBorderTop(), true)
                        cell.bottomLine = getShapeLine(poiCell.getBorderBottom(), true)


                        // background
                        cell.backgroundAndFill = converFill(pgSlide, poiCell.fill)


                        // text
                        val text = poiCell.text
                        if (text != null && text.trim { it <= ' ' }.length > 0) {
                            val textBox = TextBox()
                            val r = Rectangle(
                                rect.x.toInt(),
                                rect.y.toInt(),
                                rect.width.toInt(),
                                rect.height.toInt()
                            )
                            processTextShape(textBox, poiCell, r, slideType, -1)
                            if (textBox.element != null) {
                                processGrpRotation(poiCell as SimpleShape, textBox)
                                cell.text = textBox
                            }
                        }
                        table.addCell(i * columns + j, cell)
                    }
                }
            }
        }


        //table borders
        val borders = poiTable.tableBorders.orEmpty().filterNotNull()
        for (line in borders) {
            val aLine = getShapeLine(line, true)
            if (aLine != null) {
                val rect2D = line.logicalAnchor2D
                if (rect2D == null) {
                    return
                }
                val rect = Rectangle()
                rect.x = (rect2D.getX() * MainConstant.POINT_TO_PIXEL).toInt()
                rect.y = (rect2D.getY() * MainConstant.POINT_TO_PIXEL).toInt()
                rect.width = (rect2D.getWidth() * MainConstant.POINT_TO_PIXEL).toInt()
                rect.height = (rect2D.getHeight() * MainConstant.POINT_TO_PIXEL).toInt()

                val lineShape = LineShape()
                lineShape.shapeType = line.shapeType
                lineShape.bounds = rect
                lineShape.line = aLine

                val adj = line.adjustmentValue
                if (lineShape.shapeType == ShapeTypes.BentConnector2 && adj == null) {
                    lineShape.adjustData = arrayOf<Float?>(1.0f)
                } else {
                    lineShape.adjustData = null
                }


                processGrpRotation(line as SimpleShape, lineShape)

                lineShape.shapeID = line.shapeId
                pgSlide.appendShapes(lineShape)
            }
        }


        val rect = Rectangle()
        rect.x = (clientAnchor.getX() * MainConstant.POINT_TO_PIXEL).toInt()
        rect.y = (clientAnchor.getY() * MainConstant.POINT_TO_PIXEL).toInt()
        rect.width = (clientAnchor.getWidth() * MainConstant.POINT_TO_PIXEL).toInt()
        rect.height = (clientAnchor.getHeight() * MainConstant.POINT_TO_PIXEL).toInt()

        table.bounds = rect
        table.shapeID = poiTable.getShapeId()
        table.isTable07 = false
        if (parent == null) {
            pgSlide.appendShapes(table)
        } else {
            parent.appendShapes(table)
        }
        tableShape = false
    }

    /**
     * get border line color
     * @param line
     * @return
     */
    private fun getBorderColor(line: com.wxiwei.office.fc.hslf.model.Line?): Int {
        if (line != null) {
            val color = line.lineColor
            if (color != null) {
                return converterColor(color)
            }
        }
        return android.graphics.Color.BLACK
    }

    /**
     *
     * @param pgdoc
     * @param ts
     */
    private fun processTextShape(
        tb: TextBox,
        ts: TextShape,
        rect: Rectangle?,
        slideType: Int,
        placeHolderID: Int
    ) {
        var rect = rect
        if (rect == null) {
            val rect2D = ts.logicalAnchor2D
            if (rect2D == null) {
                return
            }
            rect = Rectangle()
            rect.x = (rect2D.getX() * MainConstant.POINT_TO_PIXEL).toInt()
            rect.y = (rect2D.getY() * MainConstant.POINT_TO_PIXEL).toInt()
            rect.width = (rect2D.getWidth() * MainConstant.POINT_TO_PIXEL).toInt()
            rect.height = (rect2D.getHeight() * MainConstant.POINT_TO_PIXEL).toInt()
        }
        //
        tb.bounds = rect
        // 自动换行
        tb.isWrapLine = ts.wordWrap == 0
        // ======== 处理文本 ========
        var text = ts.text
        if (text != null) {
            processNormalTextShape(tb, ts, rect, slideType, placeHolderID)
        } else {
            //wordart
            text = ts.unicodeGeoText
            if (text != null && text.length > 0) {
                tb.isWordArt = true
                processWordArtTextShape(tb, ts, text, rect, slideType, placeHolderID)
            }
        }
    }

    private fun processNormalTextShape(
        tb: TextBox,
        ts: TextShape,
        rect: Rectangle,
        slideType: Int,
        placeHolderID: Int
    ) {
        val text = ts.text
        if (text != null && text.trim { it <= ' ' }.length > 0) {
            // 建立章节
            val secElem = SectionElement()
            tb.element = secElem
            // 属性
            val attr = secElem.getAttribute()
            // 宽度
            AttrManage.instance()
                .setPageWidth(attr, (rect.width * MainConstant.PIXEL_TO_TWIPS).toInt())
            // 高度
            AttrManage.instance()
                .setPageHeight(attr, (rect.height * MainConstant.PIXEL_TO_TWIPS).toInt())
            // 左边距
            AttrManage.instance()
                .setPageMarginLeft(attr, (ts.marginLeft * MainConstant.POINT_TO_TWIPS).toInt())
            // 右边距
            AttrManage.instance().setPageMarginRight(
                attr,
                (ts.marginRight * MainConstant.POINT_TO_TWIPS).toInt()
            )
            // 上边距
            AttrManage.instance()
                .setPageMarginTop(attr, (ts.marginTop * MainConstant.POINT_TO_TWIPS).toInt())
            // 下边框
            AttrManage.instance().setPageMarginBottom(
                attr,
                (ts.marginBottom * MainConstant.POINT_TO_TWIPS).toInt()
            )
            var verAlign = WPAttrConstant.PAGE_V_TOP
            /*if (tableShape)
            {
                verAlign = WPAttrConstant.PAGE_V_CENTER;
            }
            else*/
            run {
                val align = ts.verticalAlignment
                when (align) {
                    TextShape.AnchorTop, TextShape.AnchorTopBaseline, TextShape.AnchorTopCentered, TextShape.AnchorTopCenteredBaseline -> verAlign =
                        WPAttrConstant.PAGE_V_TOP

                    TextShape.AnchorMiddle, TextShape.AnchorMiddleCentered -> verAlign =
                        WPAttrConstant.PAGE_V_CENTER

                    TextShape.AnchorBottom, TextShape.AnchorBottomBaseline, TextShape.AnchorBottomCentered, TextShape.AnchorBottomCenteredBaseline -> verAlign =
                        WPAttrConstant.PAGE_V_BOTTOM

                    else -> {}
                }
                if (align == TextShape.AnchorTopCentered || align == TextShape.AnchorTopCenteredBaseline || align == TextShape.AnchorMiddleCentered || align == TextShape.AnchorBottomCentered || align == TextShape.AnchorBottomCenteredBaseline) {
                    AttrManage.instance().setPageHorizontalAlign(attr, WPAttrConstant.PAGE_H_CENTER)
                }
            }
            AttrManage.instance().setPageVerticalAlign(attr, verAlign)
            // 开始Offset
            offset = 0
            secElem.setStartOffset(offset.toLong())
            val len = text.length
            val textRun = ts.textRun ?: return
            val links = textRun.hyperlinks
            var start = 0
            // title type just a paragraph needs be processed specially
            if (textRun.runType != TextHeaderAtom.TITLE_TYPE) {
                for (i in 0..<len) {
                    if (abortReader) {
                        break
                    }
                    if (text.get(i) == '\n') {
                        if (i + 1 >= len) {
                            break
                        }
                        processParagraph(secElem, ts, text, links, start, i + 1, placeHolderID)
                        start = i + 1
                    }
                }
            }
            processParagraph(secElem, ts, text, links, start, len, placeHolderID)
            // 结束Offset
            secElem.setEndOffset(offset.toLong())
            BulletNumberManage.instance().clearData()
        }
    }

    private fun processWordArtTextShape(
        tb: TextBox,
        ts: TextShape,
        text: String,
        rect: Rectangle,
        slideType: Int,
        placeHolderID: Int
    ) {
        var text: String? = text
        if (placeHolderID == OEPlaceholderAtom.MasterFooter.toInt() && text?.contains("*") == true) {
            if (slideType == PGSlide.Slide_Master.toInt()) {
                if (poiHeadersFooters!!.footerText != null) {
                    text = poiHeadersFooters!!.footerText ?: text
                }
            } else if (slideType == PGSlide.Slide_Normal.toInt()) {
                text = null

                if (poiHeadersFooters!!.footerText != null) {
                    text = poiHeadersFooters!!.footerText ?: text
                }
            }
        } else if (placeHolderID == OEPlaceholderAtom.MasterDate.toInt() && text?.contains("*") == true) {
            if (slideType == PGSlide.Slide_Master.toInt()) {
                if (poiHeadersFooters!!.dateTimeText != null) {
                    text = poiHeadersFooters!!.dateTimeText
                }
            } else if (slideType == PGSlide.Slide_Normal.toInt()) {
                text = null
                if (poiHeadersFooters!!.dateTimeText != null) {
                    text = poiHeadersFooters!!.dateTimeText
                }
            }
        }


        // 建立章节
        val secElem = SectionElement()
        tb.element = secElem
        // 属性
        val attr = secElem.getAttribute()
        // 宽度
        AttrManage.instance().setPageWidth(attr, (rect.width * MainConstant.PIXEL_TO_TWIPS).toInt())
        // 高度
        AttrManage.instance()
            .setPageHeight(attr, (rect.height * MainConstant.PIXEL_TO_TWIPS).toInt())
        // 左边距
        AttrManage.instance()
            .setPageMarginLeft(attr, (ts.marginLeft * MainConstant.POINT_TO_TWIPS).toInt())
        // 右边距
        AttrManage.instance()
            .setPageMarginRight(attr, (ts.marginRight * MainConstant.POINT_TO_TWIPS).toInt())
        // 上边距
        AttrManage.instance()
            .setPageMarginTop(attr, (ts.marginTop * MainConstant.POINT_TO_TWIPS).toInt())
        // 下边框
        AttrManage.instance()
            .setPageMarginBottom(attr, (ts.marginBottom * MainConstant.POINT_TO_TWIPS).toInt())

        AttrManage.instance().setPageHorizontalAlign(attr, WPAttrConstant.PAGE_H_CENTER)
        AttrManage.instance().setPageVerticalAlign(attr, WPAttrConstant.PAGE_V_CENTER)

        val width =
            (rect.width - (ts.marginLeft + ts.marginRight) * MainConstant.POINT_TO_PIXEL).toInt()
        val height =
            (rect.height - (ts.marginTop + ts.marginBottom) * MainConstant.POINT_TO_PIXEL).toInt()


        // 开始Offset
        offset = 0
        secElem.setStartOffset(offset.toLong())
        val fill = ts.fill
        val type = fill.fillType

        var fontColor = -0x1000000
        // 填充类型
        if (type == BackgroundAndFill.FILL_SOLID.toInt()) {
            val foregroundColor = fill.foregroundColor
            if (foregroundColor != null) {
                fontColor = converterColor(foregroundColor)
            }
        } else if (type == BackgroundAndFill.FILL_SHADE_LINEAR.toInt() || type == BackgroundAndFill.FILL_SHADE_RADIAL.toInt() || type == BackgroundAndFill.FILL_SHADE_RECT.toInt() || type == BackgroundAndFill.FILL_SHADE_SHAPE.toInt()) {
            val fillColor = fill.foregroundColor

            var colors: IntArray? = null
            if (fill.isShaderPreset) {
                colors = fill.shaderColors
                if (colors != null) {
                    fontColor = colors[0]
                } else if (fillColor != null) {
                    fontColor = fillColor.getRGB()
                }
            }
        }

        processWordArtParagraph(secElem, text!!, width, height, fontColor)


        // 结束Offset
        secElem.setEndOffset(offset.toLong())
        BulletNumberManage.instance().clearData()
    }

    /**
     *
     * @param pgdoc
     * @param ts
     * @param start
     * @param end
     */
    private fun processParagraph(
        secElem: SectionElement, ts: TextShape, text: String,
        links: Array<Hyperlink?>?, start: Int, end: Int, placeHolderID: Int
    ) {
        var start = start
        val paraElem = ParagraphElement()
        paraElem.setStartOffset(offset.toLong())
        // 属性
        val attr = paraElem.getAttribute()
        val textRun = ts.textRun ?: return
        val rt = textRun.getRichTextRunAt(start) ?: return


        // 水平对齐
        AttrManage.instance().setParaHorizontalAlign(attr, rt!!.alignment)


        // 行距
        var temp = rt.lineSpacing
        // 多倍行距
        if (temp >= 0) {
            if (temp == 0) {
                temp = 100
            }
            AttrManage.instance()
                .setParaLineSpaceType(attr, WPAttrConstant.LINE_SAPCE_MULTIPLE.toInt())
            AttrManage.instance().setParaLineSpace(attr, temp / 100f)
        } else {
            AttrManage.instance()
                .setParaLineSpaceType(attr, WPAttrConstant.LINE_SPACE_EXACTLY.toInt())
            AttrManage.instance()
                .setParaLineSpace(attr, (-temp / 8 * MainConstant.POINT_TO_TWIPS).toInt().toFloat())
        }
        // special settings of table
        if (tableShape) {
            if (start == 0) {
                AttrManage.instance().setParaBefore(paraElem.getAttribute(), 0)
            }
            if (end == text.length) {
                AttrManage.instance().setParaAfter(paraElem.getAttribute(), 0)
            }
        }


        // indent
        var bulletOffset = (rt.textOffset * MainConstant.POINT_TO_TWIPS).toInt()
        var textOffset = (rt.bulletOffset * MainConstant.POINT_TO_TWIPS).toInt()
        val indent = rt.indentLevel
        val ruler = textRun.textRuler
        if (ruler != null) {
            temp = ruler.bulletOffsets?.getOrNull(indent) ?: -1
            if (temp >= 0) {
                bulletOffset = (temp * MainConstant.POINT_DPI
                        / ShapeKit.MASTER_DPI * MainConstant.POINT_TO_TWIPS).toInt()
            }
            temp = ruler.textOffsets?.getOrNull(indent) ?: -1
            if (temp >= 0) {
                textOffset = (temp * MainConstant.POINT_DPI
                        / ShapeKit.MASTER_DPI * MainConstant.POINT_TO_TWIPS).toInt()
            }
        }
        temp = textOffset - bulletOffset
        AttrManage.instance().setParaSpecialIndent(attr, temp)
        if (temp < 0) {
            // 悬挂缩进
            AttrManage.instance().setParaIndentLeft(attr, textOffset)
        } else {
            AttrManage.instance().setParaIndentLeft(attr, bulletOffset)
        }


        // bullet number
        if (rt.isBullet && "\n" != text.substring(start, end)) {
            temp = BulletNumberManage.instance().addBulletNumber(
                control!!, indent, textRun.getNumberingType(start),
                textRun.getNumberingStart(start), rt.bulletChar
            )
            if (temp >= 0) {
                AttrManage.instance().setPGParaBulletID(attr, temp)
            }
        }


        // '\n' of title type needs be processed specially
        var handleReturn = false
        if (textRun.runType == TextHeaderAtom.TITLE_TYPE) {
            handleReturn = true
        }
        while (start < end) {
            if (abortReader) {
                break
            }
            val run = textRun.getRichTextRunAt(start)
            if (run == null) {
                break
            }
            var rtEnd = run.endIndex
            if (rtEnd > end) {
                rtEnd = end
            }
            if (links != null) {
                var hasHyperlink = false
                for (i in links.indices) {
                    val linkStart = links[i]!!.startIndex
                    val linkEnd = links[i]!!.endIndex
                    if (linkStart >= start && linkStart <= rtEnd) {
                        temp = control!!.getSysKit().getHyperlinkManage().addHyperlink(
                            links[i]!!.getAddress(),
                            com.wxiwei.office.common.hyperlink.Hyperlink.LINK_URL
                        )
                        processRun(
                            ts,
                            run,
                            paraElem,
                            text.substring(start, linkStart),
                            -1,
                            start,
                            linkStart,
                            handleReturn
                        )
                        if (linkEnd <= rtEnd) {
                            processRun(
                                ts,
                                run,
                                paraElem,
                                text.substring(linkStart, linkEnd),
                                temp,
                                linkStart,
                                linkEnd,
                                handleReturn
                            )
                            start = linkEnd
                        } else {
                            processRun(
                                ts,
                                run,
                                paraElem,
                                text.substring(linkStart, rtEnd),
                                temp,
                                linkStart,
                                rtEnd,
                                handleReturn
                            )
                            start = rtEnd
                        }
                        hasHyperlink = true
                        break
                    } else if (start > linkStart && linkEnd > start) {
                        temp = control!!.getSysKit().getHyperlinkManage().addHyperlink(
                            links[i]!!.getAddress(),
                            com.wxiwei.office.common.hyperlink.Hyperlink.LINK_URL
                        )
                        if (rtEnd <= linkEnd) {
                            processRun(
                                ts,
                                run,
                                paraElem,
                                text.substring(start, rtEnd),
                                temp,
                                start,
                                rtEnd,
                                handleReturn
                            )
                            start = rtEnd
                        } else {
                            processRun(
                                ts,
                                run,
                                paraElem,
                                text.substring(start, linkEnd),
                                temp,
                                start,
                                linkEnd,
                                handleReturn
                            )
                            start = linkEnd
                        }
                        hasHyperlink = true
                        break
                    }
                }
                if (hasHyperlink) {
                    continue
                }
            }
            if (placeHolderID == OEPlaceholderAtom.MasterDate.toInt() || placeHolderID == OEPlaceholderAtom.MasterFooter.toInt()) {
                processRun(ts, run, paraElem, text, -1, start, rtEnd, handleReturn)
                start = end
            } else {
                processRun(
                    ts,
                    run,
                    paraElem,
                    text.substring(start, rtEnd),
                    -1,
                    start,
                    rtEnd,
                    handleReturn
                )
                start = rtEnd
            }
        }
        // 段前
        temp = rt.spaceBefore
        if (temp > 0) {
            AttrManage.instance().setParaBefore(
                attr,
                (temp / 100f * maxFontSize * POINT_PER_LINE_PER_FONTSIZE * MainConstant.POINT_TO_TWIPS).toInt()
            )
        } else if (temp < 0) {
            AttrManage.instance()
                .setParaBefore(attr, (-temp / 8 * MainConstant.POINT_TO_TWIPS).toInt())
        }


        // 段后
        temp = rt.spaceAfter
        if (temp >= 0) {
            AttrManage.instance().setParaAfter(
                attr,
                (temp / 100f * maxFontSize * POINT_PER_LINE_PER_FONTSIZE * MainConstant.POINT_TO_TWIPS).toInt()
            )
        } else if (temp < 0) {
            AttrManage.instance()
                .setParaAfter(attr, (-temp / 8 * MainConstant.POINT_TO_TWIPS).toInt())
        }

        paraElem.setEndOffset(offset.toLong())
        secElem.appendParagraph(paraElem, WPModelConstant.MAIN)
    }

    /**
     *
     * @param pgdoc
     * @param ts
     * @param start
     * @param end
     */
    private fun processWordArtParagraph(
        secElem: SectionElement,
        text: String,
        width: Int,
        height: Int,
        fontColor: Int
    ) {
        val paraElem = ParagraphElement()
        paraElem.setStartOffset(offset.toLong())
        // 属性
        val paraAttr = paraElem.getAttribute()

        // 水平对齐
        AttrManage.instance().setParaHorizontalAlign(paraAttr, WPAttrConstant.PAGE_H_CENTER.toInt())

        val leaf = LeafElement(text)
        // 属性
        val attr = leaf.getAttribute()
        // 字号
        //int temp = run.getFontSize();
        var fontsize = 12
        val paint = PaintKit.instance().getPaint()
        paint.textSize = fontsize.toFloat()
        var fm = paint.fontMetrics
        while (paint.measureText(text)
                .toInt() < width && (ceil((fm.descent - fm.ascent).toDouble())).toInt() < height
        ) {
            paint.textSize = (++fontsize).toFloat()
            fm = paint.fontMetrics
        }

        AttrManage.instance().setFontSize(
            leaf.getAttribute(),
            ((fontsize - 1) * MainConstant.PIXEL_TO_POINT).toInt()
        )

        AttrManage.instance().setFontColor(attr, fontColor)

        setMaxFontSize(18)


        // 开始 offset
        leaf.setStartOffset(offset.toLong())

        // 结束 offset
        offset += text.length
        leaf.setEndOffset(offset.toLong())
        paraElem.appendLeaf(leaf)

        paraElem.setEndOffset(offset.toLong())
        secElem.appendParagraph(paraElem, WPModelConstant.MAIN)
    }

    /**
     *
     */
    private fun processRun(
        ts: TextShape, run: RichTextRun, paraElem: ParagraphElement,
        text: String, linkIndex: Int, start: Int, end: Int, handleReturn: Boolean
    ) {
        var text = text
        var start = start
        val sheet = ts.sheet
        val mcType = ts.metaCharactersType

        text = text.replace(160.toChar(), ' ')
        var pos = 0
        if (handleReturn) {
            for (i in 0..<text.length) {
                val c = text.get(i)
                if (c == '\n') {
                    processRun(
                        ts,
                        run,
                        paraElem,
                        text.substring(pos, i),
                        linkIndex,
                        start + pos,
                        start + i,
                        false
                    )
                    processRun(
                        ts,
                        run,
                        paraElem,
                        '\u000b'.toString(),
                        linkIndex,
                        start + i,
                        start + i + 1,
                        false
                    )
                    pos = i + 1
                }
            }
            if (pos < text.length) {
                processRun(
                    ts,
                    run,
                    paraElem,
                    text.substring(pos, text.length),
                    linkIndex,
                    start + pos,
                    start + text.length,
                    false
                )
                pos = text.length
            }
        }
        start += pos
        maxFontSize = 0
        if (end <= start) {
            return
        }

        if (text.length > end) {
            text = text.substring(start, end)
        }


//    	if (text != null)
//      {
//          if (placeHolderID == OEPlaceholderAtom.MasterFooter && text.contains("*"))
//          {
//              if (slideType == PGSlide.Slide_Master)
//              {
//                  if (poiHeadersFooters.footerText != null)
//                  {
//                      text = poiHeadersFooters.footerText;
//                  }
//              }
//              else if (slideType == PGSlide.Slide_Normal)
//              {
//                  text = null;
//
//                  if (poiHeadersFooters.footerText != null)
//                  {
//                      text = poiHeadersFooters.footerText;
//                  }
//              }
//          }
//          else if (placeHolderID == OEPlaceholderAtom.MasterDate && text.contains("*"))
//          {
//              if (slideType == PGSlide.Slide_Master)
//              {
//                  if (poiHeadersFooters.dateTimeText != null)
//                  {
//                      text = poiHeadersFooters.dateTimeText;
//                  }
//              }
//              else if (slideType == PGSlide.Slide_Normal)
//              {
        //                  text = null;
//                  if (poiHeadersFooters.dateTimeText != null)
//                  {
//                      text = poiHeadersFooters.dateTimeText;
//                  }
//              }
//          }
//      }
        if (text.contains("*")) {
            if (mcType == TextBox.MC_DateTime || mcType == TextBox.MC_GenericDate || mcType == TextBox.MC_RTFDateTime) {
                //field code: auto updated datetime
                val `val` = NumericFormatter.instance()
                    .getFormatContents("yyyy/m/d", Date(System.currentTimeMillis()))
                text = text.replace("*", `val`)
            } else if (mcType == TextBox.MC_Footer && poiHeadersFooters!!.footerText != null) {
                text = poiHeadersFooters!!.footerText ?: text
            }
        }

        val leaf = LeafElement(text)
        // 属性
        val attr = leaf.getAttribute()
        // 字号
        var temp = run.fontSize
        AttrManage.instance().setFontSize(attr, if (temp > 0) temp else 18)
        setMaxFontSize(run.fontSize)
        if ("\n" != text) {
            // 字体
            if (run.getFontName() != null) {
                temp = FontTypefaceManage.instance().addFontName(run.getFontName())
                if (temp >= 0) {
                    AttrManage.instance().setFontName(attr, temp)
                }
            }
            // 字符颜色
            AttrManage.instance().setFontColor(attr, run.getFontColor()?.let { converterColor(it) } ?: android.graphics.Color.BLACK)
            // 粗体
            AttrManage.instance().setFontBold(attr, run.isBold)
            // 斜体
            AttrManage.instance().setFontItalic(attr, run.isItalic)
            // 下划线
            AttrManage.instance().setFontUnderline(attr, if (run.isUnderlined == true) 1 else 0)
            // 删除线
            AttrManage.instance().setFontStrike(attr, run.isStrikethrough)
            temp = run.getSuperscript()
            if (temp != 0) {
                AttrManage.instance().setFontScript(attr, if (temp > 0) 1 else 2)
            }
            // hyperlink
            if (linkIndex >= 0) {
                var color = android.graphics.Color.BLUE
                sheet?.colorScheme?.let { colorScheme ->
                    color = FCKit.BGRtoRGB(colorScheme.accentAndHyperlinkColourRGB)
                }
                AttrManage.instance().setFontColor(attr, color)
                AttrManage.instance().setFontUnderline(attr, 1)
                AttrManage.instance().setFontUnderlineColr(attr, color)
                AttrManage.instance().setHyperlinkID(attr, linkIndex)
            }
        }

        // 开始 offset
        leaf.setStartOffset(offset.toLong())

        // 结束 offset
        offset += text.length
        leaf.setEndOffset(offset.toLong())
        paraElem.appendLeaf(leaf)
    }

    /**
     *
     * @param file
     * @param key
     * @return
     */
    @Throws(Exception::class)
    override fun searchContent(file: File?, key: String): Boolean {
        val slideShow = SlideShow(HSLFSlideShow(control, filePath))
        val slides = slideShow.slides.orEmpty().filterNotNull()
        for (slide in slides) {
            // search slide
            val shapes = slide.shapes
            for (shape in shapes) {
                if (searchShape(shape, key)) {
                    return true
                }
            }


            // search notes
            val notes = slide.notesSheet
            if (notes != null) {
                for (shape in notes.shapes) {
                    if (shape is AutoShape // 文本框
                        || shape is com.wxiwei.office.fc.hslf.model.TextBox
                    )  // 占位符
                    {
                        val phAtom = shape.placeholderAtom
                        if (phAtom != null && phAtom.placeholderId == OEPlaceholderAtom.NotesBody.toInt() && searchShape(
                                shape,
                                key
                            )
                        ) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     *
     * @param shape
     * @param key
     * @return
     */
    fun searchShape(shape: Shape?, key: String): Boolean {
        val sb = StringBuilder()
        if (shape is AutoShape // 文本框
            || shape is com.wxiwei.office.fc.hslf.model.TextBox
        )  // 占位符
        {
            sb.append(shape.text)
            if (sb.indexOf(key) >= 0) {
                return true
            }
            sb.delete(0, sb.length)
        } else if (shape is ShapeGroup) {
            val sh = shape.shapes
            for (i in sh.indices) {
                if (searchShape(sh[i], key)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     *
     */
    private fun converterColor(color: Color): Int {
//        if(color.getAlpha() == 0)
//        {
//            return color.getRGB() | 0xFF000000;
//        }
        return color.getRGB()
    }

    /**
     *
     * @param size
     */
    fun setMaxFontSize(size: Int) {
        if (size > maxFontSize) {
            maxFontSize = size
        }
    }

    /**
     * temp code for background of textbox
     * @param ts
     * @return
     */
    fun isRectangle(ts: TextShape): Boolean {
        val type = ts.shapeType
        return type == ShapeTypes.Rectangle || type == ShapeTypes.RoundRectangle || type == ShapeTypes.TextBox
    }

    /**
     *
     * @param parent
     * @param shape
     * @param spPr
     */
    fun processGrpRotation(shape: Shape, autoShape: IShape) {
        var angle = shape.rotation.toFloat()
        if (shape.flipHorizontal) {
            autoShape.flipHorizontal = true
            angle = -angle
        }
        if (shape.flipVertical) {
            autoShape.flipVertical = true
            angle = -angle
        }

        if (autoShape is LineShape) {
            if ((angle == 45f || angle == 135f || angle == 225f)
                && !autoShape.flipHorizontal && !autoShape.flipVertical
            ) {
                angle -= 90f
            }
        }
        autoShape.rotation = angle
    }

    /**
     * 
     */
    override fun dispose() {
        if (isReaderFinish()) {
            super.dispose()

            if (abortReader && model != null && model!!.getSlideCount() < FIRST_READ_SLIDE_NUM && poiSlideShow!!.slideCount > 0) {
                model!!.dispose()
            }
            model = null
            filePath = null
            if (poiSlideShow != null) {
                try {
                    poiSlideShow!!.dispose()
                } catch (e: Exception) {
                }
                poiSlideShow = null
            }
            if (slideMasterIndexs != null) {
                slideMasterIndexs!!.clear()
                slideMasterIndexs = null
            }
            if (titleMasterIndexs != null) {
                titleMasterIndexs!!.clear()
                titleMasterIndexs = null
            }
            BulletNumberManage.instance().dispose()
            System.gc()
        }
    }

    //
    private var number = 1

    //
    private var currentReaderIndex = 0

    //
    private var model: PGModel? = null

    //
    private var poiSlideShow: SlideShow? = null

    // 一个段落下字号的最大值
    private var maxFontSize = 0

    // slidemaster sheet number, slidemaster index
    private var slideMasterIndexs: MutableMap<Int?, Int?>? = null

    // titlemaster sheet number, slidemaster index
    private var titleMasterIndexs: MutableMap<Int?, Int?>? = null

    //
    private var offset = 0

    // table or not
    private var tableShape = false

    //
    private val isGetThumbnail: Boolean
    private var poiHeadersFooters: HeadersFooters? = null
    private var hasProcessedMasterSlideNumber = false
    private var hasProcessedMasterFooter = false
    private var hasProcessedMasterDateTime = false
    /**
     * 
     * @param path
     */
    /**
     * 
     * @param path
     */
    init {
        this.control = control
        this.isGetThumbnail = isGetThumbnail
    }

    companion object {
        // 
        const val FIRST_READ_SLIDE_NUM: Int = 2

        // 一行一磅字符对应的段前段后磅值
        const val POINT_PER_LINE_PER_FONTSIZE: Float = 1.2f

        // default table cell width and height
        const val DEFAULT_CELL_WIDTH: Int = 100
        const val DEFAULT_CELL_HEIGHT: Int = 40
    }
}
