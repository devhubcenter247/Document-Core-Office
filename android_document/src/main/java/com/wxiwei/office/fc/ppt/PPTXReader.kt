/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:          PPTXReader.java
 *  
 * 编译器:            android2.2
 * 时间:              下午2:35:29
 */
package com.wxiwei.office.fc.ppt

import com.wxiwei.office.fc.ppt.reader.pptXmlBoolean
import com.wxiwei.office.common.bg.BackgroundAndFill
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.constant.wp.AttrIDConstant
import com.wxiwei.office.fc.dom4j.Element
import com.wxiwei.office.fc.dom4j.ElementHandler
import com.wxiwei.office.fc.dom4j.ElementPath
import com.wxiwei.office.fc.dom4j.io.SAXReader
import com.wxiwei.office.fc.openxml4j.opc.ContentTypes
import com.wxiwei.office.fc.openxml4j.opc.PackagePart
import com.wxiwei.office.fc.openxml4j.opc.PackageRelationship
import com.wxiwei.office.fc.openxml4j.opc.PackageRelationshipTypes
import com.wxiwei.office.fc.openxml4j.opc.ZipPackage
import com.wxiwei.office.fc.ppt.attribute.RunAttr
import com.wxiwei.office.fc.ppt.bulletnumber.BulletNumberManage
import com.wxiwei.office.fc.ppt.reader.BackgroundReader
import com.wxiwei.office.fc.ppt.reader.EmbeddedFontReader
import com.wxiwei.office.fc.ppt.reader.HyperlinkReader
import com.wxiwei.office.fc.ppt.reader.LayoutReader
import com.wxiwei.office.fc.ppt.reader.MasterReader
import com.wxiwei.office.fc.ppt.reader.PictureReader
import com.wxiwei.office.fc.ppt.reader.ReaderKit
import com.wxiwei.office.fc.ppt.reader.SmartArtReader
import com.wxiwei.office.fc.ppt.reader.StyleReader
import com.wxiwei.office.fc.ppt.reader.TableStyleReader
import com.wxiwei.office.java.awt.Dimension
import com.wxiwei.office.pg.animate.ShapeAnimation
import com.wxiwei.office.pg.model.PGLayout
import com.wxiwei.office.pg.model.PGMaster
import com.wxiwei.office.pg.model.PGModel
import com.wxiwei.office.pg.model.PGNotes
import com.wxiwei.office.pg.model.PGPlaceholderUtil
import com.wxiwei.office.pg.model.PGSlide
import com.wxiwei.office.pg.model.PGStyle
import com.wxiwei.office.simpletext.model.StyleManage
import com.wxiwei.office.system.AbortReaderError
import com.wxiwei.office.system.AbstractReader
import com.wxiwei.office.system.BackReaderThread
import com.wxiwei.office.system.IControl
import com.wxiwei.office.system.StopReaderError
import java.io.File
import java.util.Hashtable
import kotlin.math.min

/**
 * 解析pptx的文档
 * 
 * 
 * 
 * 
 * Read版本:        Read V1.0
 * 
 * 
 * 作者:            ljj8494
 * 
 * 
 * 日期:            2012-2-15
 * 
 * 
 * 负责人:          ljj8494
 * 
 * 
 * 负责小组:
 * 
 * 
 * 
 * 
 */
class PPTXReader(control: IControl?, filePath: String?) : AbstractReader() {
    /**
     * fix very large XML documents
     * 
     */
    internal inner class PresentationSaxHandler : ElementHandler {
        /**
         * 
         * 
         */
        override fun onStart(elementPath: ElementPath?) {
        }

        /**
         * @throws Exception
         */
        override fun onEnd(elementPath: ElementPath?) {
            if (abortReader) {
                throw AbortReaderError("abort Reader")
            }
            val elem = elementPath?.current
            val name = elem!!.name
            try {
                if (name == "sldMasterIdLst") {
                    // master part
                    processMasterPart(elem)
                } else if (name == "defaultTextStyle") {
                    processDefaultTextStyle(elem)
                } else if (name == "sldSz") {
                    setPageSize(elem)
                } else if (name == "sldId") {
                    addSlideID(elem)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            elem!!.detach()
        }
    }

    /**
     * fix very large XML documents
     * 
     */
    internal inner class SlideSaxHandler : ElementHandler {
        /**
         * 
         * 
         */
        override fun onStart(elementPath: ElementPath?) {
        }

        /**
         * @throws Exception
         */
        override fun onEnd(elementPath: ElementPath?) {
            if (abortReader) {
                throw AbortReaderError("abort Reader")
            }

            val elem = elementPath?.current
            try {
                if (("bg") == elem!!.name) {
                    // 背景
                    processBackground(slidePart, pgMaster!!, pgLayout!!, pgSlide!!, elem)
                } else if ("sld" == elem!!.name) {
                    if (elem!!.attribute("showMasterSp") != null) {
                        val `val` = elem!!.attributeValue("showMasterSp")
                        if (`val` != null && `val`.length > 0 && !pptXmlBoolean(`val`, true)) {
                            showMasterSp = false
                        }
                    }
                } else if ("par" == elem!!.name) {
                    processSlideShow(pgSlide!!, elem)
                } else if ("transition" == elem!!.name) {
                    //slide transition
                    pgSlide!!.setTransition(elem!!.elements()!!.size > 0)
                } else {
                    ShapeManage.instance().processShape(
                        control!!,
                        zipPackage!!,
                        slidePart!!,
                        pgModel!!,
                        pgMaster,
                        pgLayout,
                        defaultStyle,
                        pgSlide!!,
                        PGSlide.Slide_Normal,
                        elem,
                        null,
                        1.0f,
                        1.0f
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            elem!!.detach()
        }
    }

    /**
     * fix very large XML documents
     * 
     */
    internal inner class PresentationSaxHandler_Search : ElementHandler {
        /**
         * 
         * 
         */
        override fun onStart(elementPath: ElementPath?) {
        }

        /**
         * @throws Exception
         */
        override fun onEnd(elementPath: ElementPath?) {
            if (abortReader) {
                throw AbortReaderError("abort Reader")
            }

            val elem = elementPath?.current
            if (("sldId") == elem!!.name) {
                note = false
                val slidePart = zipPackage!!.getPart(
                    packagePart!!.getRelationship(elem!!.attribute(1)!!.value).getTargetURI()
                )
                if (slidePart != null) {
                    val saxreader = SAXReader()
                    try {
                        // search slide
                        var `in` = slidePart.getInputStream()
                        val slideSaxHandler = SlideNoteSaxHandler_Search()
                        saxreader.addHandler("/sld/cSld/spTree/sp", slideSaxHandler)
                        saxreader.addHandler("/sld/cSld/spTree/grpSp", slideSaxHandler)
                        saxreader.read(`in`)
                        `in`.close()


                        // search notes                    
                        val notesShip = slidePart.getRelationshipsByType(
                            PackageRelationshipTypes.NOTES_SLIDE
                        ).getRelationship(0)
                        if (notesShip != null) {
                            val notesPart = zipPackage!!.getPart(notesShip.getTargetURI())

                            note = true
                            `in` = notesPart.getInputStream()
                            saxreader.resetHandlers()
                            saxreader.addHandler("/notes/cSld/spTree/sp", slideSaxHandler)
                            saxreader.read(`in`)
                            `in`.close()
                        }
                    } catch (e: StopReaderError) {
                        elem!!.detach()
                        throw StopReaderError("stop")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        saxreader.resetHandlers()
                    }
                }
            }

            elem!!.detach()
        }
    }


    /**
     * fix very large XML documents
     * 
     */
    internal inner class SlideNoteSaxHandler_Search : ElementHandler {
        /**
         * 
         * 
         */
        override fun onStart(elementPath: ElementPath?) {
        }

        /**
         * @throws Exception
         */
        override fun onEnd(elementPath: ElementPath?) {
            if (abortReader) {
                throw AbortReaderError("abort Reader")
            }

            val elem = elementPath?.current
            searchContentForText(elem!!, key!!)

            elem.detach()

            if (searched) {
                throw StopReaderError("stop")
            }
        }
    }

    /**
     * 
     * @return
     */
    @Throws(Exception::class)
    override fun getModel(): Any? {
        if (pgModel != null) {
            return pgModel
        }
        initPackagePart()
        pgModel = PGModel()

        processPresentation()
        return pgModel
    }

    /**
     * 
     */
    @Throws(Exception::class)
    fun initPackagePart() {
        zipPackage = ZipPackage(filePath)


        /*URL url = new URL("http://172.25.3.147:8080/ppt_test_2007.pptx");
        zipPackage = new ZipPackage(url.openStream());*/

        /*InputStream is = SocketClient.instance().getFile("E:/workdocument/reader/testdocument/ppt_test_2007.pptx");
        zipPackage = new ZipPackage(is);*/
        val coreRel = zipPackage!!.getRelationshipsByType(
            PackageRelationshipTypes.CORE_DOCUMENT
        ).getRelationship(0)
        if (coreRel == null || coreRel.getTargetURI().toString() != "/ppt/presentation.xml") {
            throw Exception("Format error")
        }
        packagePart = zipPackage!!.getPart(coreRel)
    }

    /**
     * process presentation xml
     * @param model
     * @throws Exception
     */
    @Throws(Exception::class)
    private fun processPresentation() {
        // get presentation xml
        val saxreader = SAXReader()
        try {
            val `in` = packagePart!!.getInputStream()

            val preSaxHandler = PresentationSaxHandler()

            saxreader.addHandler("/presentation/sldMasterIdLst", preSaxHandler)
            saxreader.addHandler("/presentation/defaultTextStyle", preSaxHandler)
            saxreader.addHandler("/presentation/sldSz", preSaxHandler)
            saxreader.addHandler("/presentation/sldIdLst/sldId", preSaxHandler)

            val poi = saxreader.read(`in`)
            val root = poi!!.rootElement
            if (root != null) {
                if (root.attribute("firstSlideNum") != null) {
                    val `val` = root.attributeValue("firstSlideNum")
                    if (`val` != null && `val`.length > 0) {
                        pgModel!!.setSlideNumberOffset(`val`.toInt() - 1)
                    }
                }
                EmbeddedFontReader.read(control, zipPackage, packagePart, root.element("embeddedFontLst"))
            }
            `in`.close()
            if (sldIds == null) {
                throw Exception("Format error")
            }
            pgModel!!.setSlideCount(sldIds!!.size)


            //table style part
            val tableStyleParts = zipPackage!!.getPartsByContentType(ContentTypes.TABLE_STYLE_PART)
            if (tableStyleParts.size > 0) {
                val tableStylePackagePart = tableStyleParts.get(0)
                if (tableStylePackagePart != null) {
                    val style = StyleManage.instance().getStyle(defaultStyle!!.getStyle(1))
                    var fontsize = 12
                    if (style != null) {
                        fontsize = style.getAttrbuteSet()!!.getAttribute(AttrIDConstant.FONT_SIZE_ID)
                            .let { if (it < 0) it else Math.round(com.wxiwei.office.simpletext.model.AttrManage.instance().decodeFontSize(it)) }
                        if (fontsize < 0) {
                            fontsize = 12
                        }
                    }

                    TableStyleReader.instance().read(pgModel, tableStylePackagePart, fontsize)
                }
            }

            processSlidePart()
        } catch (e: Exception) {
            throw e
        } finally {
            saxreader.resetHandlers()
        }
    }

    /**
     * reader style
     */
    private fun processDefaultFontColor(style: Element?, lvl: Int) {
        if (style != null) {
            var `val`: String? = null
            var temp = style.element("defRPr")
            if (temp != null) {
                temp = temp.element("solidFill")
                if (temp != null) {
                    if ((temp.element("schemeClr")
                            .also { temp = it }) != null && temp!!.attribute("val") != null
                    ) {
                        `val` = temp.attributeValue("val")
                        if (`val` != null && `val`.length > 0) {
                            defaultStyle!!.addDefaultFontColor(lvl, `val`)
                        }
                    }
                }
            }
        }
    }

    /**
     * 
     * @param defaultTextStyle
     */
    private fun processDefaultTextStyle(defaultTextStyle: Element?) {
        if (nameMaster != null) {
            val iter = nameMaster!!.keys.iterator()
            if (iter.hasNext()) {
                StyleReader.instance().setStyleIndex(1)
                defaultStyle = StyleReader.instance()
                    .getStyles(control, nameMaster!!.get(iter.next()), null, defaultTextStyle)
            }
        }


        // default font colol
        if (defaultTextStyle != null && defaultStyle != null) {
            var temp = defaultTextStyle.element("lvl1pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 1)
            }
            temp = defaultTextStyle.element("lvl2pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 2)
            }
            temp = defaultTextStyle.element("lvl3pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 3)
            }
            temp = defaultTextStyle.element("lvl4pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 4)
            }
            temp = defaultTextStyle.element("lvl5pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 5)
            }
            temp = defaultTextStyle.element("lvl6pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 6)
            }
            temp = defaultTextStyle.element("lvl7pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 7)
            }
            temp = defaultTextStyle.element("lvl8pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 8)
            }
            temp = defaultTextStyle.element("lvl9pPr")
            if (temp != null) {
                processDefaultFontColor(temp, 9)
            }
        }
    }

    /**
     * 
     * @param masterIdLst
     * @throws Exception
     */
    @Throws(Exception::class)
    fun processMasterPart(masterIdLst: Element) {
        // process the first master part
        val masterIds: MutableList<Element> = masterIdLst.elements("sldMasterId") as MutableList<Element>
        if (masterIds.size > 0) {
            val masterId = masterIds.get(0)
            if (abortReader) {
                return
            }
            var index = 0
            if (masterId.attributeCount() > 1) {
                index = 1
            }
            val masterPart = zipPackage!!.getPart(
                packagePart!!.getRelationship(masterId.attribute(index)!!.value).getTargetURI()
            )
            nameMaster!!.put(
                masterPart.getPartName().getName(),
                MasterReader.instance().getMasterData(control!!, zipPackage!!, masterPart, pgModel!!)
            )
        }
    }

    /**
     * 
     * @param slideId
     */
    private fun addSlideID(slideId: Element) {
        if (sldIds == null) {
            sldIds = ArrayList<String?>()
        }
        sldIds!!.add(slideId.attribute(1)!!.value)
    }

    /**
     * process slidepart
     * @throws Exception
     */
    @Throws(Exception::class)
    fun processSlidePart() {
        if (sldIds!!.size > 0) {
            val len = min(sldIds!!.size, FIRST_READ_SLIDE_NUM)
            var i = 0
            while (i < len && !abortReader) {
                processSlide(sldIds!!.get(currentReaderIndex++))
                i++
            }

            if (!isReaderFinish()) {
                BackReaderThread(this, control).start()
            }
        } else {
            /*PGSlide pgSlide = new PGSlide();
            pgModel.appendSlide(pgSlide);*/
            throw Exception("Format error")
        }
    }

    /**
     * 
     * 
     */
    override fun isReaderFinish(): Boolean {
        if (pgModel != null && sldIds != null) {
            return abortReader || pgModel!!.getSlideCount() == 0 || currentReaderIndex >= sldIds!!.size
        }
        return true
    }

    /**
     * 
     */
    @Throws(Exception::class)
    override fun backReader() {
        try {
            // the slide counts as read only once it is: the reading thread disposes a finished reader
            try { processSlide(sldIds!!.get(currentReaderIndex)) } finally { currentReaderIndex++ }
            //control.actionEvent(EventConstant.PG_REPAINT_ID, null);
            control!!.actionEvent(EventConstant.APP_COUNT_PAGES_CHANGE_ID, null)
        } catch (e: Error) {
            control!!.getSysKit().getErrorKit().writerLog(e, true)
        }
    }

    /**
     * 处理slide
     */
    @Throws(Exception::class)
    private fun processSlide(sldId: String?) {
        showMasterSp = true
        slidePart = zipPackage!!.getPart(
            packagePart!!.getRelationship(sldId).getTargetURI()
        )


        // get layout part
        val layoutShip = slidePart!!.getRelationshipsByType(
            PackageRelationshipTypes.LAYOUT_PART
        ).getRelationship(0)
        val layoutPart = zipPackage!!.getPart(layoutShip.getTargetURI())


        // get master 
        val ship = layoutPart.getRelationshipsByType(
            PackageRelationshipTypes.SLIDE_MASTER
        ).getRelationship(0)
        pgMaster = nameMaster!!.get(ship.getTargetURI().toString())
        if (pgMaster == null) {
            val masterPart = zipPackage!!.getPart(ship.getTargetURI())
            pgMaster =
                MasterReader.instance().getMasterData(control!!, zipPackage!!, masterPart, pgModel!!)
            nameMaster!!.put(masterPart.getPartName().getName(), pgMaster)
        }

        // layout
        pgLayout = nameLayout!!.get(layoutPart.getPartName().getName())
        if (pgLayout == null) {
            pgLayout = LayoutReader.instance()
                .getLayouts(control!!, zipPackage!!, layoutPart, pgModel!!, pgMaster, null)
            nameLayout!!.put(layoutPart.getPartName().getName(), pgLayout)
        }

        pgSlide = PGSlide()
        pgSlide!!.setSlideType(PGSlide.Slide_Normal.toInt())


        //smart art
        val smartArtDataCollection =
            slidePart!!.getRelationshipsByType(PackageRelationshipTypes.DIAGRAM_DATA)
        if (smartArtDataCollection != null && smartArtDataCollection.size() > 0) {
            val cnt = smartArtDataCollection.size()
            var rel: PackageRelationship? = null
            for (i in 0..<cnt) {
                rel = smartArtDataCollection.getRelationship(i)
                pgSlide!!.addSmartArt(
                    rel.getId(),
                    SmartArtReader.instance().read(
                        control!!,
                        zipPackage!!,
                        pgModel!!,
                        pgMaster,
                        pgLayout,
                        pgSlide!!,
                        slidePart!!,
                        zipPackage!!.getPart(rel.getTargetURI())
                    )
                )
            }
        }


        // hyperlink
        HyperlinkReader.instance().getHyperlinkList(control!!, slidePart!!)


        // slide xml
        val saxreader = SAXReader()
        try {
            val `in` = slidePart!!.getInputStream()

            val slideSaxHandler = SlideSaxHandler()
            saxreader.addHandler("/sld/cSld/bg", slideSaxHandler)
            saxreader.addHandler("/sld/cSld/spTree/sp", slideSaxHandler)
            saxreader.addHandler("/sld/cSld/spTree/cxnSp", slideSaxHandler)
            saxreader.addHandler("/sld/cSld/spTree/pic", slideSaxHandler)
            saxreader.addHandler("/sld/cSld/spTree/graphicFrame", slideSaxHandler)
            saxreader.addHandler("/sld/cSld/spTree/grpSp", slideSaxHandler)
            saxreader.addHandler("/sld/cSld/spTree/AlternateContent", slideSaxHandler)
            saxreader.addHandler(
                "/sld/timing/tnLst/par/cTn/childTnLst/seq/cTn/childTnLst/par",
                slideSaxHandler
            )
            saxreader.addHandler("/sld/timing/bldLst/bldP", slideSaxHandler)
            //07 pg document
            saxreader.addHandler("/sld/transition", slideSaxHandler)
            //2010 pg document
            saxreader.addHandler("/sld/AlternateContent/Choice/transition", slideSaxHandler)
            saxreader.addHandler("/sld", slideSaxHandler)
            saxreader.read(`in`)


            //
            `in`.close()

            processBackground(slidePart, pgMaster!!, pgLayout!!, pgSlide!!, null)


            //group shape          
            processGroupShape(pgSlide!!)


            // slide number
            pgSlide!!.setSlideNo(slideNum++)
            // 处理 notes
            processNotes(slidePart!!, pgSlide!!)
            // slidemaster
            if (showMasterSp && pgLayout!!.isAddShapes()) {
                pgSlide!!.setMasterSlideIndex(pgMaster!!.getSlideMasterIndex())
            }
            pgSlide!!.setLayoutSlideIndex(pgLayout!!.getSlideMasterIndex())
            //
            pgModel!!.appendSlide(pgSlide)

            pgSlide = null
            pgLayout = null
            pgMaster = null
            slidePart = null
            PictureReader.instance().dispose()
            HyperlinkReader.instance().disposs()
        } finally {
            saxreader.resetHandlers()
        }
    }

    private fun processGroupShape(pgSlide: PGSlide) {
        val grpShape = pgSlide.getGroupShape()
        if (grpShape == null) {
            return
        }


//        processGroupShapeID(grpShape);
        val count = pgSlide.getShapeCount()
        var grpSpID: Int
        for (i in 0..<count) {
            val shape = pgSlide.getShape(i)
            grpSpID = getGroupShapeID(shape!!.shapeID, grpShape)
            shape!!.groupShapeID = grpSpID
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

    /**
     * groupshape中全部shape处理成直接记录最外层groupshape的GroupID
     * @param grpShape
     */
    private fun processGroupShapeID(grpShape: MutableMap<Int, MutableList<Int>>) {
        var repeat = false
        val grpIDIter = grpShape.keys.iterator()
        var removeGrpID: MutableList<Int>? = null
        while (grpIDIter.hasNext()) {
            val grpID: Int = grpIDIter.next()!!
            val childShape = grpShape.get(grpID)
            val grpCnt = childShape!!.size
            var ids: MutableList<Int>? = null
            for (i in 0..<grpCnt) {
                val childchildShape = grpShape.get(childShape.get(i))
                if (childchildShape != null && childchildShape.size > 0) {
                    if (ids == null) {
                        ids = ArrayList<Int>()
                    }
                    ids.addAll(childchildShape)
                }
            }

            if (ids != null && ids.size > 0) {
                //current node is group shape
                repeat = true
                childShape.addAll(ids)
            } else {
                //all shapes are not groupshape
                if (removeGrpID == null) {
                    removeGrpID = ArrayList<Int>()
                }
                removeGrpID.add(grpID)
            }
        }

        if (repeat) {
            for (i in removeGrpID!!.indices) {
                grpShape.remove(removeGrpID.get(i))
            }
            processGroupShapeID(grpShape)
        }
    }

    /**
     * 处理slide的背景
     * @throws Exception
     */
    @Throws(Exception::class)
    private fun processBackground(
        slidePart: PackagePart?, pgMaster: PGMaster,
        pgLayout: PGLayout, pgSlide: PGSlide, bg: Element?
    ) {
        var bgFill: BackgroundAndFill? = null
        if (bg == null && pgSlide.getBackgroundAndFill() == null) {
            bgFill = pgLayout.getBackgroundAndFill()
            if (bgFill == null) {
                bgFill = pgMaster.getBackgroundAndFill()
            }
            pgSlide.setBackgroundAndFill(bgFill)
        } else if (bg != null) {
            bgFill = BackgroundReader.instance()
                .getBackground(control!!, zipPackage!!, slidePart!!, pgMaster, bg)
            pgSlide.setBackgroundAndFill(bgFill)
        }
    }

    /**
     * set slide page size
     * @param model
     */
    fun setPageSize(slideSize: Element?) {
        var pgx = 0f
        var pgy = 0f
        if (slideSize != null) {
            pgx = slideSize.attributeValue("cx")!!.toFloat() * MainConstant.PIXEL_DPI / MainConstant.EMU_PER_INCH
            pgy = slideSize.attributeValue("cy")!!.toFloat() * MainConstant.PIXEL_DPI / MainConstant.EMU_PER_INCH
        }
        pgModel!!.setPageSize(Dimension(pgx.toInt(), pgy.toInt()))
    }

    /**
     * 
     */
    @Throws(Exception::class)
    private fun processNotes(slidePart: PackagePart, pgSlide: PGSlide) {
        // get notes part
        val notesShip = slidePart.getRelationshipsByType(
            PackageRelationshipTypes.NOTES_SLIDE
        ).getRelationship(0)
        if (notesShip != null) {
            val notesPart = zipPackage!!.getPart(notesShip.getTargetURI())
            //
            val saxreader = SAXReader()
            val `in` = notesPart.getInputStream()
            val poiNote = saxreader.read(`in`)

            val root = poiNote!!.rootElement
            if (root != null) {
                val notes = ReaderKit.instance().getNotes(root)
                if (notes != null) {
                    val pgNotes = PGNotes(notes)
                    pgSlide.setNotes(pgNotes)
                }
            }

            `in`.close()
        }
    }

    //animation id and type
    private fun processSlideShow(pgSlide: PGSlide, elem: Element) {
        var elem = elem
        try {
            //"/cTn/childTnLst/par/cTn/childTnLst//par"   
            val elements: MutableList<Element> =
                elem.element("cTn")!!.element("childTnLst")!!.elements("par") as MutableList<Element>
            if (elements.size >= 1) {
                //after previous
                for (item in elements) {
                    val elementList: MutableList<Element> =
                        item.element("cTn")!!.element("childTnLst")!!.elements("par") as MutableList<Element>
                    for (e in elementList) {
                        //cTn, with previous( when elementList.size() > 1)
                        elem = e.element("cTn")!!
                        processAnimation(pgSlide, elem)
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun processAnimation(pgSlide: PGSlide, elem: Element) {
        var elem = elem
        val sType = elem.attributeValue("presetClass")
        elem = elem.element("childTnLst")!!
        if (elem.element("set") != null) {
            elem = elem.element("set")!!.element("cBhvr")!!.element("tgtEl")!!.element("spTgt")!!
        } else {
            //emph
            elem = (elem.elements()!!.get(0) as Element?)!!
            elem = elem.element("cBhvr")!!.element("tgtEl")!!.element("spTgt")!!
        }

        val shapeID = elem.attributeValue("spid")
        var nType = ShapeAnimation.SA_EMPH
        if (sType == "entr") {
            nType = ShapeAnimation.SA_ENTR
        } else if (sType == "emph") {
            nType = ShapeAnimation.SA_EMPH
        } else if (sType == "exit") {
            nType = ShapeAnimation.SA_EXIT
        } else {
            //path, verb, mediacall
            return
        }

        if (elem.element("txEl") != null && elem.element("txEl")!!.element("pRg") != null) {
            elem = elem.element("txEl")!!.element("pRg")!!
            //paragraph range
            val s = elem.attributeValue("st")
            val e = elem.attributeValue("end")

            pgSlide.addShapeAnimation(
                ShapeAnimation(shapeID!!.toInt(), nType, s!!.toInt(), e!!.toInt())
            )
        } else if (elem.element("bg") != null) {
            //background
            pgSlide.addShapeAnimation(
                ShapeAnimation(
                    shapeID!!.toInt(),
                    nType,
                    ShapeAnimation.Para_BG,
                    ShapeAnimation.Para_BG
                )
            )
        } else {
            pgSlide.addShapeAnimation(
                ShapeAnimation(shapeID!!.toInt(), nType)
            )
        }
    }

    /**
     * 
     * @param file
     * @param key
     * @return
     */
    @Throws(Exception::class)
    override fun searchContent(file: File?, key: String): Boolean {
        searched = false
        this.key = key

        zipPackage = ZipPackage(file!!.getAbsolutePath())
        val coreRel = zipPackage!!.getRelationshipsByType(
            PackageRelationshipTypes.CORE_DOCUMENT
        ).getRelationship(0)
        packagePart = zipPackage!!.getPart(coreRel)

        val saxreader = SAXReader()
        try {
            // presentation xml
            val `in` = packagePart!!.getInputStream()
            val preSaxHandler = PresentationSaxHandler_Search()
            saxreader.addHandler("/presentation/sldIdLst/sldId", preSaxHandler)

            saxreader.read(`in`)
            `in`.close()
        } catch (e: StopReaderError) {
        } finally {
            saxreader.resetHandlers()
        }

        this.key = null
        // close the file now: left to the finalizer, closing a file deleted meanwhile (shared
        // storage) fails with EIO and that exception kills the app
        zipPackage?.revert()
        zipPackage = null
        packagePart = null

        return searched
    }

    /**
     * search xml
     * @param elem
     * @param key
     * @return
     */
    fun searchContentForText(elem: Element, key: String): Boolean {
        val name = elem.name
        if (name == "sp") {
            val sb = StringBuilder()
            if (note && PGPlaceholderUtil.BODY != ReaderKit.instance().getPlaceholderType(elem)) {
                return false
            }
            val txBody = elem.element("txBody")
            if (txBody != null) {
                val ps: MutableList<Element> = txBody.elements("p") as MutableList<Element>
                for (p in ps) {
                    val rs: MutableList<Element> = p.elements("r") as MutableList<Element>
                    for (r in rs) {
                        val t = r.element("t")
                        if (t != null) {
                            sb.append(t.getText())
                        }
                    }
                    if (sb.indexOf(key) >= 0) {
                        this.key = null
                        // close the file now: left to the finalizer, closing a file deleted meanwhile (shared
                        // storage) fails with EIO and that exception kills the app
                        zipPackage?.revert()
                        zipPackage = null
                        packagePart = null
                        searched = true
                        return true
                    }
                    sb.delete(0, sb.length)
                }
            }
        } else if (name == "grpSp") {
            val it = elem.elementIterator()
            while (it!!.hasNext()) {
                if (searchContentForText(
                        (it!!.next() as com.wxiwei.office.fc.dom4j.Element?)!!,
                        key
                    )
                ) {
                    this.key = null
                    // close the file now: left to the finalizer, closing a file deleted meanwhile (shared
                    // storage) fails with EIO and that exception kills the app
                    zipPackage?.revert()
                    zipPackage = null
                    packagePart = null
                    searched = true
                    return true
                }
            }
        }

        return false
    }

    /**
     * 
     */
    override fun dispose() {
        if (isReaderFinish()) {
            super.dispose()

            if (abortReader && pgModel != null && pgModel!!.getSlideCount() < FIRST_READ_SLIDE_NUM && sldIds != null && sldIds!!.size > 0) {
                pgModel!!.dispose()
            }
            pgModel = null
            filePath = null
            // close the file now: left to the finalizer, closing a file deleted meanwhile (shared
            // storage) fails with EIO and that exception kills the app
            zipPackage?.revert()
            zipPackage = null
            packagePart = null
            //packageRel = null;
            if (nameLayout != null) {
                val iter = nameLayout!!.keys.iterator()
                while (iter.hasNext()) {
                    nameLayout!!.get(iter.next())!!.disposs()
                }
                nameLayout!!.clear()
                nameLayout = null
            }
            if (nameMaster != null) {
//                Iterator<String> iter = nameMaster.keySet().iterator();
//                while(iter.hasNext())
//                {
//                    nameMaster.get(iter.next()).dispose();
//                }
                nameMaster!!.clear()
                nameMaster = null
            }
            if (sldIds != null) {
                sldIds!!.clear()
                sldIds = null
            }
            if (defaultStyle != null) {
                defaultStyle!!.dispose()
                defaultStyle = null
            }

            key = null

            pgSlide = null
            pgLayout = null
            pgMaster = null
            slidePart = null
            HyperlinkReader.instance().disposs()
            PictureReader.instance().dispose()
            LayoutReader.instance().dispose()
            MasterReader.instance().dispose()
            RunAttr.instance().dispose()
            BulletNumberManage.instance().dispose()
        }
    }

    //
    private var slideNum = 1

    //
    private var currentReaderIndex = 0

    //
    private var pgModel: PGModel? = null

    //
    private var filePath: String?

    //
    private var zipPackage: ZipPackage? = null

    //
    private var packagePart: PackagePart? = null

    //
    private var nameLayout: MutableMap<String?, PGLayout?>? = Hashtable<String?, PGLayout?>()

    //
    private var nameMaster: MutableMap<String?, PGMaster?>? = Hashtable<String?, PGMaster?>()

    //
    private var sldIds: MutableList<String?>? = null

    // default text style
    private var defaultStyle: PGStyle? = null

    //temp parameter    
    private var slidePart: PackagePart? = null
    private var pgMaster: PGMaster? = null
    private var pgLayout: PGLayout? = null
    private var pgSlide: PGSlide? = null

    //for search
    private var key: String? = null
    private var searched = false
    private var note = false

    private var showMasterSp = false

    /**
     * 
     * @param filePath
     */
    init {
        this.control = control
        this.filePath = filePath
    }

    companion object {
        // 
        const val FIRST_READ_SLIDE_NUM: Int = 2
    }
}
