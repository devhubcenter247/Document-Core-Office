/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.editor.pptx

import com.wxiwei.office.editor.*
import com.wxiwei.office.editor.ooxml.*
import com.wxiwei.office.fc.dom4j.Element
import com.wxiwei.office.fc.dom4j.Namespace
import com.wxiwei.office.fc.dom4j.QName
import java.io.File
import java.io.IOException
import kotlin.math.roundToLong

/** An empty slide: its layout (a relationship) gives the placeholders and background. */
private const val BLANK_SLIDE = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
    "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
    "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
    "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
    "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/>" +
    "<a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
    "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"

/** EMU coordinates, with width/height (not right/bottom). */
data class Rect(val x: Long, val y: Long, val width: Long, val height: Long)
data class Size(val width: Long, val height: Long)
data class Point(val x: Long, val y: Long)
enum class ShapeKind { TEXT, PICTURE, TABLE, GROUP, OTHER }
/** Text formatting for a whole shape; null fields stay as they are. [align]: l, ctr, r, just. */
data class TextFormat(
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
    val sizePt: Float? = null, val rgbHex: String? = null, val align: String? = null,
    /** Font name (the a:latin and a:cs typeface). */
    val font: String? = null,
)

/** [rotationDeg]: clockwise rotation of the shape's own xfrm (group rotation not included). */
data class PptxShapeInfo(val id: Int, val name: String, val kind: ShapeKind, val rectEmu: Rect,
                         val text: String, val isPlaceholder: Boolean, val rotationDeg: Float = 0f)

/**
 * Zero-based presentation order, including show="0" slides: PPTXReader.processSlide does not
 * filter hidden slides. Mutations queue in order; queries include queued edits. Save replays
 * against a fresh package and atomically writes a new file. Not thread safe.
 * Rectangles are axis aligned; rotation/flip and inherited master artwork are not hit-tested.
 * Failed calls return -1/false/empty and set lastError; a failed queue must be corrected before save.
 */
class PptxEditor(private val source: File) {
    private val ops = mutableListOf<(OoxmlPackage) -> Unit>()
    var lastError: EditResult.Error? = null; private set
    private fun failure(e: Exception): EditResult.Error = EditResult.Error(when (e) {
        is UnsupportedOperationException -> Reason.UNSUPPORTED_FORMAT
        is NoSuchElementException -> Reason.NOT_FOUND
        is IllegalArgumentException -> Reason.INVALID_ARGUMENT
        is IOException -> Reason.IO
        else -> Reason.INTERNAL
    }, e.message ?: "PPTX edit failed", e)
    private fun open(): OoxmlPackage {
        if (!source.extension.equals("pptx", true)) throw UnsupportedOperationException("Only PPTX is editable; legacy PPT is unsupported")
        return OoxmlPackage.open(source)
    }
    // The package with every queued op applied. Kept in memory so each edit costs one op, not a
    // re-read of the whole file; rebuilt by replaying the ops after a failure or undoLast.
    private var current: OoxmlPackage? = null
    private fun snapshot(): OoxmlPackage = current ?: open().also { pkg -> ops.forEach { it(pkg) }; current = pkg }
    private fun <T> read(fallback: T, block: (OoxmlPackage) -> T): T = try { block(snapshot()) } catch (e: Exception) { lastError = failure(e); fallback }
    private fun queue(op: (OoxmlPackage) -> Unit): Boolean = try {
        op(snapshot()); ops.add(op); lastError = null; true
    } catch (e: Exception) { current = null; lastError = failure(e); false } // a failed op may have half applied
    /** Drop the last queued operation (undo). */
    fun undoLast(): Boolean {
        if (ops.isEmpty()) return false
        ops.removeAt(ops.lastIndex); current = null; return true
    }
    val queuedCount: Int get() = ops.size
    fun slideSizeEmu(): Size = read(Size(0, 0)) { pkg ->
        val s = pkg.xml("ppt/presentation.xml").rootElement!!.firstChild(P, "sldSz") ?: error("Missing slide size")
        Size(s.num("cx"), s.num("cy"))
    }
    private fun part(pkg: OoxmlPackage, index: Int): String {
        val ids = pkg.xml("ppt/presentation.xml").rootElement!!.firstChild(P, "sldIdLst")?.childrenNamed(P, "sldId").orEmpty()
        require(index in ids.indices) { "Slide index out of range: $index" }
        val rid = ids[index].attributeValue(QName("id", R))
        val rel = pkg.relationships("ppt/presentation.xml").firstOrNull { it.id == rid && it.type == REL_SLIDE && it.targetMode != "External" }
            ?: throw NoSuchElementException("Missing slide relationship: $rid")
        return pkg.resolveTarget("ppt/presentation.xml", rel.target)
    }
    private fun tree(pkg: OoxmlPackage, part: String): Element = pkg.xml(part).rootElement!!.firstChild(P, "cSld")?.firstChild(P, "spTree") ?: error("Missing shape tree")
    private fun descendants(e: Element): List<Element> = listOf(e) + e.elements()!!.filterIsInstance<Element>().flatMap { descendants(it) }
    private fun nv(e: Element) = e.elements()!!.filterIsInstance<Element>().firstOrNull { it.namespaceURI == P.uRI && it.name!!.startsWith("nv") }
    private fun identity(e: Element) = nv(e)?.firstChild(P, "cNvPr")
    private fun placeholder(e: Element) = nv(e)?.firstChild(P, "nvPr")?.firstChild(P, "ph")
    private fun xfrm(e: Element): Element? = when (e.name) {
        "grpSp" -> e.firstChild(P, "grpSpPr")?.firstChild(A, "xfrm")
        "graphicFrame" -> e.firstChild(P, "xfrm")
        else -> e.firstChild(P, "spPr")?.firstChild(A, "xfrm")
    }
    private fun Element.num(key: String, default: Long = 0) = attributeValue(key)?.toLongOrNull() ?: default
    private fun rect(x: Element?): Rect? {
        val off = x?.firstChild(A, "off") ?: return null
        val ext = x.firstChild(A, "ext") ?: return null
        return Rect(off.num("x"), off.num("y"), ext.num("cx"), ext.num("cy"))
    }
    private fun inherited(pkg: OoxmlPackage, slide: String, shape: Element): Rect? {
        var ph = placeholder(shape) ?: return null
        var current = slide
        for (type in listOf("slideLayout", "slideMaster")) {
            val rel = pkg.relationships(current).firstOrNull { it.type == "${R.uRI}/$type" && it.targetMode != "External" } ?: continue
            current = pkg.resolveTarget(current, rel.target)
            val candidates = descendants(tree(pkg, current)).filter { placeholder(it) != null }
            val match = if (type == "slideLayout") {
                candidates.firstOrNull { placeholder(it)!!.num("idx") == ph.num("idx") && (placeholder(it)!!.attributeValue("type") ?: "obj") == (ph.attributeValue("type") ?: "obj") }
                    ?: candidates.firstOrNull { placeholder(it)!!.num("idx") == ph.num("idx") }
            } else candidates.firstOrNull { (placeholder(it)!!.attributeValue("type") ?: "obj") == (ph.attributeValue("type") ?: "obj") }
            if (match != null) { rect(xfrm(match))?.let { return it }; ph = placeholder(match)!! }
        }
        return null
    }
    private data class Transform(val sx: Double = 1.0, val sy: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
        fun map(r: Rect) = Rect((r.x * sx + tx).roundToLong(), (r.y * sy + ty).roundToLong(), (r.width * sx).roundToLong(), (r.height * sy).roundToLong())
        fun inverse(r: Rect): Rect { require(sx != 0.0 && sy != 0.0) { "Degenerate group transform" }; return Rect(((r.x - tx) / sx).roundToLong(), ((r.y - ty) / sy).roundToLong(), (r.width / sx).roundToLong(), (r.height / sy).roundToLong()) }
        fun group(x: Element?): Transform {
            val r = rectOf(x) ?: return this
            val off = x?.firstChild(A, "chOff"); val ext = x?.firstChild(A, "chExt")
            val cx = ext?.attributeValue("cx")?.toDoubleOrNull() ?: r.width.toDouble()
            val cy = ext?.attributeValue("cy")?.toDoubleOrNull() ?: r.height.toDouble()
            val gx = if (cx == 0.0) 1.0 else r.width / cx; val gy = if (cy == 0.0) 1.0 else r.height / cy
            return Transform(sx * gx, sy * gy, tx + sx * (r.x - gx * (off?.attributeValue("x")?.toDoubleOrNull() ?: 0.0)), ty + sy * (r.y - gy * (off?.attributeValue("y")?.toDoubleOrNull() ?: 0.0)))
        }
        companion object {

            private fun rectOf(x: Element?): Rect? {
                val o = x?.firstChild(A, "off") ?: return null; val e = x.firstChild(A, "ext") ?: return null
                return Rect(o.attributeValue("x")!!.toLong(), o.attributeValue("y")!!.toLong(), e.attributeValue("cx")!!.toLong(), e.attributeValue("cy")!!.toLong())
            }
        }
    }
    private fun walk(root: Element, t: Transform = Transform()): List<Pair<Element, Transform>> = root.elements()!!.filterIsInstance<Element>().flatMap { e ->
        if (identity(e) == null) emptyList() else listOf(e to t) + if (e.name == "grpSp") walk(e, t.group(xfrm(e))) else emptyList()
    }
    fun listShapes(slideIndex: Int): List<PptxShapeInfo> = read(emptyList()) { pkg ->
        val part = part(pkg, slideIndex)
        walk(tree(pkg, part)).map { (e, t) ->
            val id = identity(e)!!
            PptxShapeInfo(id.num("id").toInt(), id.attributeValue("name") ?: "", when (e.name) {
                // a shape filled with a picture and without text (Canva's image frames) is a picture
                "sp" -> if (descendants(e).none { it.namespaceURI == A.uRI && it.name == "t" } &&
                    descendants(e).any { it.name == "blipFill" }) ShapeKind.PICTURE else ShapeKind.TEXT
                "pic" -> ShapeKind.PICTURE; "grpSp" -> ShapeKind.GROUP
                "graphicFrame" -> if (descendants(e).any { it.namespaceURI == A.uRI && it.name == "tbl" }) ShapeKind.TABLE else ShapeKind.OTHER
                else -> ShapeKind.OTHER
            }, t.map(rect(xfrm(e)) ?: inherited(pkg, part, e) ?: Rect(0, 0, 0, 0)),
                descendants(e).filter { it.namespaceURI == A.uRI && it.name == "p" }.joinToString("\n") { p -> descendants(p).filter { it.namespaceURI == A.uRI && it.name in listOf("t", "br") }.joinToString("") { if (it.name == "br") "\n" else it.text ?: "" } }, placeholder(e) != null,
                (xfrm(e)?.num("rot") ?: 0L) / 60000f)
        }
    }
    private fun nextId(index: Int): Int = read(-1) { pkg ->
        val max = descendants(tree(pkg, part(pkg, index))).filter { it.namespaceURI == P.uRI && it.name == "cNvPr" }.maxOfOrNull { it.num("id") } ?: 0L
        require(max < Int.MAX_VALUE); (max + 1).toInt()
    }
    private fun Element.child(ns: Namespace, name: String): Element = addElement(QName(name, ns))!!
    private fun geometry(e: Element, r: Rect) {
        require(r.width > 0 && r.height > 0) { "Positive shape dimensions required" }
        e.child(A, "off").addAttribute("x", r.x.toString())!!.addAttribute("y", r.y.toString())
        e.child(A, "ext").addAttribute("cx", r.width.toString())!!.addAttribute("cy", r.height.toString())
    }
    private fun shapeProperties(e: Element, r: Rect) = e.child(P, "spPr").apply {
        geometry(child(A, "xfrm"), r); child(A, "prstGeom").addAttribute("prst", "rect")!!.child(A, "avLst")
    }
    private fun paragraphs(body: Element, text: String, rPr: Element?, pPr: Element?) {
        body.childrenNamed(A, "p").forEach { body.remove(it) }
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            val p = body.child(A, "p"); pPr?.let { p.add(it.createCopy()) }
            val r = p.child(A, "r"); rPr?.let { r.add(it.createCopy()) }; r.child(A, "t").text = line
        }
    }
    fun addTextBox(slideIndex: Int, rectEmu: Rect, text: String, sizePt: Float = 18f, rgbHex: String = "000000", bold: Boolean = false): Int {
        val id = nextId(slideIndex); if (id < 0) return -1
        return if (queue { pkg ->
            require(sizePt.isFinite() && sizePt in 1f..4000f && rgbHex.matches(Regex("[0-9a-fA-F]{6}"))) { "Invalid font size or RGB" }
            val sp = tree(pkg, part(pkg, slideIndex)).child(P, "sp")
            sp.child(P, "nvSpPr").apply { child(P, "cNvPr").addAttribute("id", "$id")!!.addAttribute("name", "TextBox $id"); child(P, "cNvSpPr").addAttribute("txBox", "1"); child(P, "nvPr") }
            shapeProperties(sp, rectEmu).child(A, "noFill")
            val body = sp.child(P, "txBody")
            // centered in the box, like a new text box of Google Slides / Keynote
            body.child(A, "bodyPr").addAttribute("wrap", "square")!!.addAttribute("rtlCol", "0")!!.addAttribute("anchor", "ctr")!!.child(A, "spAutoFit"); body.child(A, "lstStyle")
            val rp = newElement(A, "rPr").addAttribute("lang", "vi-VN")!!.addAttribute("sz", (sizePt * 100).roundToLong().toString())!!.addAttribute("b", if (bold) "1" else "0")!!
            rp.child(A, "solidFill").child(A, "srgbClr").addAttribute("val", rgbHex.uppercase())
            paragraphs(body, text, rp, newElement(A, "pPr").apply { addAttribute("algn", "ctr") })
        }) id else -1
    }
    /**
     * A preset shape [prst] (rect, roundRect, ellipse, triangle, rightArrow, line...) filled with
     * [fillHex] (none when null; a line has none) and outlined in [lineHex], [lineWidthPt] wide.
     */
    fun addShape(slideIndex: Int, rectEmu: Rect, prst: String, fillHex: String?, lineHex: String, lineWidthPt: Float = 1.5f): Int {
        val id = nextId(slideIndex); if (id < 0) return -1
        return if (queue { pkg ->
            val hex = Regex("[0-9a-fA-F]{6}")
            require(prst.matches(Regex("[A-Za-z0-9]+")) && lineHex.matches(hex) && (fillHex == null || fillHex.matches(hex)) && lineWidthPt in 0f..100f) { "Invalid shape" }
            val sp = tree(pkg, part(pkg, slideIndex)).child(P, "sp")
            sp.child(P, "nvSpPr").apply { child(P, "cNvPr").addAttribute("id", "$id")!!.addAttribute("name", "Shape $id"); child(P, "cNvSpPr"); child(P, "nvPr") }
            val spPr = sp.child(P, "spPr")
            geometry(spPr.child(A, "xfrm"), rectEmu)
            spPr.child(A, "prstGeom").addAttribute("prst", prst)!!.child(A, "avLst")
            // CT_ShapeProperties: xfrm, geometry, fill, ln
            if (fillHex == null || prst == "line") spPr.child(A, "noFill") else spPr.child(A, "solidFill").child(A, "srgbClr").addAttribute("val", fillHex.uppercase())
            spPr.child(A, "ln").addAttribute("w", Math.round(lineWidthPt * 12700).toString())!!.child(A, "solidFill").child(A, "srgbClr").addAttribute("val", lineHex.uppercase())
        }) id else -1
    }

    fun addImage(slideIndex: Int, rectEmu: Rect, imageFile: File): Int {
        val id = nextId(slideIndex); if (id < 0) return -1
        val bytes = try { imageFile.readBytes() } catch (e: Exception) { lastError = failure(e); return -1 }
        return if (queue { pkg ->
            val part = part(pkg, slideIndex)
            val media = pkg.addMedia("ppt/media", bytes, imageFile.extension)
            val rid = pkg.addRelationship(part, REL_IMAGE, "../media/${media.substringAfterLast('/')}")
            val pic = tree(pkg, part).child(P, "pic")
            pic.child(P, "nvPicPr").apply { child(P, "cNvPr").addAttribute("id", "$id")!!.addAttribute("name", "Picture $id"); child(P, "cNvPicPr").child(A, "picLocks").addAttribute("noChangeAspect", "1"); child(P, "nvPr") }
            pic.child(P, "blipFill").apply { child(A, "blip").addAttribute(QName("embed", R), rid); child(A, "stretch").child(A, "fillRect") }
            shapeProperties(pic, rectEmu)
        }) id else -1
    }
    private fun find(pkg: OoxmlPackage, index: Int, id: Int) = walk(tree(pkg, part(pkg, index))).firstOrNull { identity(it.first)?.num("id") == id.toLong() } ?: throw NoSuchElementException("Shape $id not found")
    fun setShapeText(slideIndex: Int, shapeId: Int, text: String): Boolean = queue { pkg ->
        val e = find(pkg, slideIndex, shapeId).first
        val body = e.firstChild(P, "txBody") ?: throw IllegalArgumentException("Shape has no editable text body")
        val rp = descendants(body).firstOrNull { it.namespaceURI == A.uRI && it.name == "r" }?.firstChild(A, "rPr")?.createCopy()
        val old = body.childrenNamed(A, "p")
        if (old.isEmpty()) return@queue paragraphs(body, text, rp, null)
        // keep the runs of the text that stays (Retext); new text borrows a neighbouring run
        val paras = old.map { p ->
            val runs = p.elements()!!.filterIsInstance<Element>()
                .filter { it.namespaceURI == A.uRI && it.name in setOf("r", "fld", "br") }
                .map { r -> Retext.Run(if (r.name == "br") "\n" else r.firstChild(A, "t")?.text ?: "", r, r.name == "br") }
            Retext.Para(p, runs, p.firstChild(A, "endParaRPr"))
        }
        val at = body.content()!!.indexOf(old.first())
        old.forEach { body.remove(it) }
        Retext.apply(paras, text).forEachIndexed { i, para ->
            val source = para.source.tag
            val p = newElement(A, "p")
            source.firstChild(A, "pPr")?.let { p.add(it.createCopy()) }
            for (run in para.runs) {
                val from = run.tag
                p.add(when {
                    from != null && run.lineBreak -> from.createCopy()!!
                    from != null -> from.createCopy()!!.also { r -> (r.firstChild(A, "t") ?: r.child(A, "t")).text = run.text }
                    else -> newElement(A, "r").also { r ->
                        (para.source.end?.createCopy(QName("rPr", A)) ?: rp?.createCopy())?.let { r.add(it) }
                        r.child(A, "t").text = run.text
                    }
                })
            }
            source.firstChild(A, "endParaRPr")?.let { p.add(it.createCopy()) }
            (body.content() as MutableList<Any?>).add(at + i, p)
        }
    }
    /** True when the shape's box grows or shrinks with its text (a:bodyPr/a:spAutoFit). */
    fun autoFits(slideIndex: Int, shapeId: Int): Boolean = read(false) { pkg ->
        find(pkg, slideIndex, shapeId).first.firstChild(P, "txBody")?.firstChild(A, "bodyPr")?.firstChild(A, "spAutoFit") != null
    }
    /** Bold/italic/underline and font of the shape's first text run as written in the slide (null: no text). */
    fun textFormatOf(slideIndex: Int, shapeId: Int): TextFormat? = textFormatAt(slideIndex, shapeId, 0)

    /**
     * Bold/italic/underline and font of the text run holding char [offset] of the shape's text
     * (positions as in [setTextFormat]), as written in the slide; the first run when none holds it.
     * Null: no text. The font is null when the run inherits it.
     */
    fun textFormatAt(slideIndex: Int, shapeId: Int, offset: Int): TextFormat? = read(null) { pkg ->
        val e = find(pkg, slideIndex, shapeId).first
        var first: Element? = null
        var hit: Element? = null
        var pos = 0
        paragraphs@ for ((k, p) in descendants(e).filter { it.namespaceURI == A.uRI && it.name == "p" }.withIndex()) {
            if (k > 0) pos++
            for (r in p.elements()!!.filterIsInstance<Element>().filter { it.namespaceURI == A.uRI }) {
                val len = when (r.name) { "r", "fld" -> r.firstChild(A, "t")?.text?.length ?: 0; "br" -> 1; else -> 0 }
                if (r.name == "r") {
                    if (first == null) first = r
                    if (offset >= pos && offset < pos + len) { hit = r; break@paragraphs }
                }
                pos += len
            }
        }
        val run = hit ?: first
        val rPr = (run ?: return@read null).firstChild(A, "rPr")
        fun on(v: String?) = v == "1" || v == "true"
        TextFormat(bold = on(rPr?.attributeValue("b")), italic = on(rPr?.attributeValue("i")),
            underline = rPr?.attributeValue("u").let { it != null && it != "none" },
            font = rPr?.firstChild(A, "latin")?.attributeValue("typeface")?.takeIf { it.isNotEmpty() && !it.startsWith("+") })
    }

    private fun checkFormat(format: TextFormat) {
        format.rgbHex?.let { require(it.removePrefix("#").matches(Regex("(?i)[0-9a-f]{6}"))) { "Expected RRGGBB" } }
        format.align?.let { require(it in setOf("l", "ctr", "r", "just")) { "Bad alignment" } }
        format.font?.let { require(it.isNotBlank() && it.length <= 64 && it.none { c -> c.isISOControl() }) { "Bad font name" } }
    }

    /** Formats every run (and paragraph for [TextFormat.align]) of a shape's text. */
    fun setTextFormat(slideIndex: Int, shapeId: Int, format: TextFormat): Boolean = queue { pkg ->
        val e = find(pkg, slideIndex, shapeId).first
        val body = e.firstChild(P, "txBody") ?: throw IllegalArgumentException("Shape has no editable text body")
        checkFormat(format)
        for (p in body.childrenNamed(A, "p")) {
            format.align?.let { al ->
                val pPr = p.firstChild(A, "pPr") ?: newElement(A, "pPr").also { (p.content() as MutableList<Any?>).add(0, it) }
                pPr.addAttribute("algn", al)
            }
            // the run properties of text runs, fields and the paragraph end
            val props = p.elements()!!.filterIsInstance<Element>().mapNotNull { r ->
                when {
                    r.namespaceURI != A.uRI -> null
                    r.name == "r" || r.name == "fld" -> r.firstChild(A, "rPr") ?: newElement(A, "rPr").also { (r.content() as MutableList<Any?>).add(0, it) }
                    r.name == "endParaRPr" -> r
                    else -> null
                }
            }
            for (rPr in props) runFormat(rPr, format)
        }
    }

    private fun runFormat(rPr: Element, format: TextFormat) {
        format.bold?.let { rPr.addAttribute("b", if (it) "1" else "0") }
        format.italic?.let { rPr.addAttribute("i", if (it) "1" else "0") }
        format.underline?.let { rPr.addAttribute("u", if (it) "sng" else "none") }
        format.sizePt?.let { rPr.addAttribute("sz", Math.round(it * 100).toString()) }
        format.rgbHex?.let { rgb ->
            // CT_TextCharacterProperties: ln, then the fill, before effects and fonts
            listOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill").forEach { n -> rPr.childrenNamed(A, n).forEach { rPr.remove(it) } }
            val fill = newElement(A, "solidFill").apply { add(newElement(A, "srgbClr").apply { addAttribute("val", rgb.removePrefix("#").uppercase()) }) }
            val content = rPr.content() as MutableList<Any?>
            val ln = rPr.firstChild(A, "ln")
            content.add(if (ln == null) 0 else content.indexOf(ln) + 1, fill)
        }
        format.font?.let { name ->
            // CT_TextCharacterProperties: ... latin, ea, cs, sym, hlinkClick, hlinkMouseOver, rtl, extLst
            listOf("latin", "cs").forEach { n -> rPr.childrenNamed(A, n).forEach { rPr.remove(it) } }
            val content = rPr.content() as MutableList<Any?>
            fun putBefore(local: String, next: List<String>) {
                val font = newElement(A, local).apply { addAttribute("typeface", name) }
                val at = content.indexOfFirst { it is Element && it.namespaceURI == A.uRI && it.name in next }
                if (at < 0) content.add(font) else content.add(at, font)
            }
            putBefore("latin", listOf("ea", "cs", "sym", "hlinkClick", "hlinkMouseOver", "rtl", "extLst"))
            putBefore("cs", listOf("sym", "hlinkClick", "hlinkMouseOver", "rtl", "extLst"))
        }
    }

    /**
     * Formats chars [start, end) of the shape's text (as [listShapes] gives it: paragraphs and line
     * breaks count one char each). Runs are split at both ends; [TextFormat.align] applies to the
     * paragraphs touched.
     */
    fun setTextFormat(slideIndex: Int, shapeId: Int, start: Int, end: Int, format: TextFormat): Boolean = queue { pkg ->
        require(start in 0 until end) { "Empty range" }
        val e = find(pkg, slideIndex, shapeId).first
        val body = e.firstChild(P, "txBody") ?: throw IllegalArgumentException("Shape has no editable text body")
        checkFormat(format)
        var pos = 0
        for ((k, p) in body.childrenNamed(A, "p").withIndex()) {
            if (k > 0) pos++ // the paragraph mark before it
            val paraStart = pos
            for (r in p.elements()!!.filterIsInstance<Element>().filter { it.namespaceURI == A.uRI }) {
                val len = when (r.name) { "r", "fld" -> r.firstChild(A, "t")?.text?.length ?: 0; "br" -> 1; else -> 0 }
                val a = maxOf(start, pos) - pos
                val b = minOf(end, pos + len) - pos
                if (b > a && r.name == "fld") runFormat(r.firstChild(A, "rPr") ?: newElement(A, "rPr").also { (r.content() as MutableList<Any?>).add(0, it) }, format)
                if (b > a && r.name == "r") {
                    val text = r.firstChild(A, "t")!!.text ?: ""
                    // the part in range as a run of its own, the rest keeps its runs
                    val content = p.content() as MutableList<Any?>
                    var at = content.indexOf(r)
                    fun piece(t: String) = r.createCopy()!!.also { c -> c.firstChild(A, "t")!!.text = t }
                    val mid = piece(text.substring(a, b))
                    if (a > 0) content.add(at++, piece(text.substring(0, a)))
                    content.add(at++, mid)
                    if (b < len) content.add(at, piece(text.substring(b)))
                    p.remove(r)
                    runFormat(mid.firstChild(A, "rPr") ?: newElement(A, "rPr").also { (mid.content() as MutableList<Any?>).add(0, it) }, format)
                }
                pos += len
            }
            if (format.align != null && pos >= start && paraStart < end) {
                val pPr = p.firstChild(A, "pPr") ?: newElement(A, "pPr").also { (p.content() as MutableList<Any?>).add(0, it) }
                pPr.addAttribute("algn", format.align)
            }
        }
    }

    // ---- slides ----------------------------------------------------------------------------

    private val presentationPart = "ppt/presentation.xml"
    private fun slideIds(pkg: OoxmlPackage): Element =
        pkg.xml(presentationPart).rootElement!!.firstChild(P, "sldIdLst") ?: error("Missing slide list")

    fun slideCount(): Int = read(0) { pkg -> slideIds(pkg).childrenNamed(P, "sldId").size }

    /** Removes a slide (and its notes); the last slide cannot be removed. */
    fun deleteSlide(slideIndex: Int): Boolean = queue { pkg ->
        val list = slideIds(pkg)
        val ids = list.childrenNamed(P, "sldId")
        require(ids.size > 1) { "A presentation keeps at least one slide" }
        val part = part(pkg, slideIndex)
        val entry = ids[slideIndex]
        val rid = entry.attributeValue(QName("id", R)) ?: error("Slide without relationship")
        list.remove(entry)
        pkg.removeRelationship(presentationPart, rid)
        // notes belong to this slide only
        pkg.relationships(part).filter { it.type.endsWith("/notesSlide") && it.targetMode != "External" }.forEach { rel ->
            val notes = pkg.resolveTarget(part, rel.target)
            pkg.remove(pkg.relsPartOf(notes)); pkg.remove(notes); pkg.removeOverride(notes)
        }
        pkg.remove(pkg.relsPartOf(part)); pkg.remove(part); pkg.removeOverride(part)
    }

    /** Copies a slide right after it; returns the new slide index or -1. */
    fun duplicateSlide(slideIndex: Int): Int {
        var result = -1
        val ok = queue { pkg ->
            val part = part(pkg, slideIndex)
            val copy = newSlidePart(pkg)
            pkg.putBytes(copy, pkg.bytes(part) ?: error("Missing slide part"))
            val rels = pkg.relsPartOf(part)
            if (pkg.has(rels)) {
                pkg.putBytes(pkg.relsPartOf(copy), pkg.bytes(rels)!!)
                // the copy has no notes of its own
                pkg.relationships(copy).filter { it.type.endsWith("/notesSlide") }.forEach { pkg.removeRelationship(copy, it.id) }
            }
            result = listSlide(pkg, copy, slideIndex + 1)
        }
        return if (ok) result else -1
    }

    /**
     * Adds an empty slide after [afterIndex] (-1: first), on the master's "blank" layout when it
     * has one, else on the layout of the slide before it. Returns the new slide's index.
     */
    fun addBlankSlide(afterIndex: Int): Int {
        var result = -1
        val ok = queue { pkg ->
            val count = slideIds(pkg).childrenNamed(P, "sldId").size
            require(afterIndex in -1 until count) { "Slide index out of range" }
            val near = part(pkg, afterIndex.coerceAtLeast(0).coerceAtMost(count - 1))
            val layoutType = "${R.uRI}/slideLayout"
            val nearLayout = pkg.relationships(near).firstOrNull { it.type == layoutType && it.targetMode != "External" }
                ?.let { pkg.resolveTarget(near, it.target) } ?: error("The slide has no layout")
            // the blank layout of the same master
            val master = pkg.relationships(nearLayout).firstOrNull { it.type == "${R.uRI}/slideMaster" }?.let { pkg.resolveTarget(nearLayout, it.target) }
            val blank = master?.let { m ->
                pkg.relationships(m).filter { it.type == layoutType }.map { pkg.resolveTarget(m, it.target) }
                    .firstOrNull { pkg.xml(it).rootElement?.attributeValue("type") == "blank" }
            }
            val layout = blank ?: nearLayout
            val slide = newSlidePart(pkg)
            pkg.putBytes(slide, BLANK_SLIDE.toByteArray(Charsets.UTF_8))
            pkg.addRelationship(slide, layoutType, "../slideLayouts/" + layout.substringAfterLast('/'))
            result = listSlide(pkg, slide, afterIndex + 1)
        }
        return if (ok) result else -1
    }

    private fun newSlidePart(pkg: OoxmlPackage): String {
        val number = pkg.partNames().mapNotNull { Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0
        return "ppt/slides/slide${number + 1}.xml"
    }

    /** Registers the slide part [slide] and puts it at [index] in the slide list. */
    private fun listSlide(pkg: OoxmlPackage, slide: String, index: Int): Int {
        pkg.ensureOverride(slide, "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")
        val rid = pkg.addRelationship(presentationPart, REL_SLIDE, "slides/" + slide.substringAfterLast('/'))
        val list = slideIds(pkg)
        val ids = list.childrenNamed(P, "sldId")
        val nextId = maxOf(255L, ids.maxOfOrNull { it.num("id") } ?: 255L) + 1
        val entry = newElement(P, "sldId").apply { addAttribute("id", nextId.toString()); addAttribute(QName("id", R), rid) }
        val content = list.content() as MutableList<Any?>
        if (index <= 0) content.add(content.indexOf(ids.first()), entry) else content.add(content.indexOf(ids[index - 1]) + 1, entry)
        return index
    }

    /** Moves a slide to [to] (index in the list after removing it from [from]). */
    fun moveSlide(from: Int, to: Int): Boolean = queue { pkg ->
        val list = slideIds(pkg)
        val ids = list.childrenNamed(P, "sldId")
        require(from in ids.indices && to in ids.indices) { "Slide index out of range" }
        if (from == to) return@queue
        val entry = ids[from]
        list.remove(entry)
        val rest = list.childrenNamed(P, "sldId")
        val content = list.content() as MutableList<Any?>
        if (to >= rest.size) content.add(content.indexOf(rest.last()) + 1, entry) else content.add(content.indexOf(rest[to]), entry)
    }

    fun moveShape(slideIndex: Int, shapeId: Int, rectEmu: Rect): Boolean = queue { pkg -> placeXfrm(pkg, slideIndex, shapeId, rectEmu) }

    /**
     * Sets the clockwise rotation in degrees (any value, stored in [0, 360)). A placeholder that
     * inherits its position gets its own xfrm first.
     */
    fun rotateShape(slideIndex: Int, shapeId: Int, degrees: Float): Boolean = queue { pkg ->
        require(degrees.isFinite()) { "Invalid rotation" }
        val e = find(pkg, slideIndex, shapeId).first
        if (xfrm(e)?.firstChild(A, "off") == null) {
            val r = inherited(pkg, part(pkg, slideIndex), e) ?: throw IllegalArgumentException("Shape has no position")
            placeXfrm(pkg, slideIndex, shapeId, r) // inherited rects are slide coordinates
        }
        val x = xfrm(e)!!
        val rot = Math.round(((degrees % 360f + 360f) % 360f) * 60000.0) % 21_600_000L
        x.attribute("rot")?.let { x.remove(it) }
        if (rot != 0L) x.addAttribute("rot", rot.toString())
    }

    private fun placeXfrm(pkg: OoxmlPackage, slideIndex: Int, shapeId: Int, rectEmu: Rect) {
        val (e, t) = find(pkg, slideIndex, shapeId)
        val r = t.inverse(rectEmu)
        val parent = when (e.name) { "grpSp" -> e.firstChild(P, "grpSpPr") ?: e.child(P, "grpSpPr"); "graphicFrame" -> e; else -> e.firstChild(P, "spPr") ?: e.child(P, "spPr") }
        val old = xfrm(e)
        val replacement = old?.createCopy() ?: newElement(if (e.name == "graphicFrame") P else A, "xfrm")
        listOf("off", "ext").forEach { name -> replacement.childrenNamed(A, name).forEach { replacement.remove(it) } }
        val coords = newElement(A, "xfrm"); geometry(coords, r)
        coords.elements()!!.filterIsInstance<Element>().reversed().forEach { (replacement.content() as MutableList<Any?>).add(0, it.createCopy()) }
        if (old != null) parent.remove(old)
        (parent.content() as MutableList<Any?>).add(if (e.name == "graphicFrame") minOf(1, parent.content()!!.size) else 0, replacement)
    }
    /**
     * Z-order: moves the shape among its siblings (the slide's shape tree or its group) to the
     * "front" or "back", or one step "forward" / "backward".
     */
    fun reorderShape(slideIndex: Int, shapeId: Int, where: String): Boolean = queue { pkg ->
        val e = find(pkg, slideIndex, shapeId).first
        val parent = e.parent!!
        @Suppress("UNCHECKED_CAST")
        val content = parent.content() as MutableList<Any?>
        val siblings = content.filterIsInstance<Element>().filter { identity(it) != null }
        val i = siblings.indexOf(e)
        val target = when (where) {
            "front" -> siblings.lastIndex
            "back" -> 0
            "forward" -> minOf(i + 1, siblings.lastIndex)
            "backward" -> maxOf(i - 1, 0)
            else -> throw IllegalArgumentException("Bad z-order move: $where")
        }
        if (target == i) throw IllegalStateException(if (target > 0) "Đã ở lớp trên cùng" else "Đã ở lớp dưới cùng")
        content.remove(e)
        val others = siblings.filter { it !== e }
        if (target >= others.size) content.add(content.indexOf(others.last()) + 1, e) else content.add(content.indexOf(others[target]), e)
    }

    /** The slide's animation sequence (see [SlideTiming]). */
    fun slideEffects(slideIndex: Int): List<SlideEffect> = read(emptyList()) { pkg -> SlideTiming.effects(pkg.xml(part(pkg, slideIndex)).rootElement!!) }
    /** Replaces the slide's animation sequence; an empty list removes it. */
    fun setSlideEffects(slideIndex: Int, effects: List<SlideEffect>): Boolean = queue { pkg ->
        val part = part(pkg, slideIndex)
        val ids = walk(tree(pkg, part)).mapNotNull { identity(it.first)?.num("id")?.toInt() }.toSet()
        effects.firstOrNull { it.shapeId !in ids }?.let { throw NoSuchElementException("Shape ${it.shapeId} not found") }
        SlideTiming.setEffects(pkg.xml(part).rootElement!!, effects)
    }
    fun slideTransition(slideIndex: Int): SlideTransition? = read(null) { pkg -> SlideTiming.transition(pkg.xml(part(pkg, slideIndex)).rootElement!!) }
    /** Sets the transition of the slides [slideIndexes] (null or type "none" removes it). */
    fun setSlideTransition(slideIndexes: List<Int>, t: SlideTransition?): Boolean = queue { pkg ->
        for (i in slideIndexes) SlideTiming.setTransition(pkg.xml(part(pkg, i)).rootElement!!, t)
    }
    /** Titles, hidden flags, transitions, animations and links of every slide (queued edits included). */
    fun showScript(): List<SlideScript> = read(emptyList()) { pkg ->
        val count = pkg.xml("ppt/presentation.xml").rootElement!!.firstChild(P, "sldIdLst")?.childrenNamed(P, "sldId").orEmpty().size
        val parts = (0 until count).map { part(pkg, it) }
        parts.mapIndexed { i, part ->
            val sld = pkg.xml(part).rootElement!!
            val shapes = walk(tree(pkg, part))
            fun textOf(e: Element) = descendants(e).filter { it.namespaceURI == A.uRI && it.name == "p" }
                .joinToString(" ") { p -> descendants(p).filter { it.namespaceURI == A.uRI && it.name == "t" }.joinToString("") { it.text ?: "" } }.trim()
            val title = shapes.firstOrNull { (e, _) -> placeholder(e)?.attributeValue("type") in setOf("title", "ctrTitle") && textOf(e).isNotEmpty() }?.let { textOf(it.first) }
                ?: shapes.map { textOf(it.first) }.firstOrNull { it.isNotEmpty() } ?: ""
            val links = shapes.mapNotNull { (e, t) ->
                val hl = identity(e)?.firstChild(A, "hlinkClick") ?: descendants(e).firstOrNull { it.namespaceURI == A.uRI && it.name == "hlinkClick" } ?: return@mapNotNull null
                val r = rect(xfrm(e))?.let { t.map(it) } ?: inherited(pkg, part, e) ?: return@mapNotNull null
                linkOf(pkg, part, hl, parts, r)
            }
            SlideScript(i, title.take(200), sld.attributeValue("show") == "0", SlideTiming.transition(sld), SlideTiming.effects(sld), links)
        }
    }

    private fun linkOf(pkg: OoxmlPackage, part: String, hl: Element, parts: List<String>, r: Rect): SlideLink? {
        val action = hl.attributeValue("action") ?: ""
        val rel = hl.attributeValue(QName("id", R))?.takeIf { it.isNotEmpty() }?.let { id -> pkg.relationships(part).firstOrNull { it.id == id } }
        return when {
            action.startsWith("ppaction://hlinkshowjump") -> when (action.substringAfter("jump=", "")) {
                "nextslide" -> "next"; "previousslide", "lastslideviewed" -> "previous"; "firstslide" -> "first"; "lastslide" -> "last"; "endshow" -> "end"
                else -> null
            }?.let { SlideLink(r, jump = it) }
            action.startsWith("ppaction://hlinksldjump") -> rel?.takeIf { it.targetMode != "External" }
                ?.let { parts.indexOf(pkg.resolveTarget(part, it.target)) }?.takeIf { it >= 0 }?.let { SlideLink(r, slideIndex = it) }
            action.isNotEmpty() -> null // macros, programs, other files: not run
            rel != null && rel.targetMode == "External" -> SlideLink(r, url = rel.target)
            else -> null
        }
    }

    /** Reads the current state of the package (queued edits applied): what a slideshow plays. */
    fun <T> readPackage(fallback: T, block: (OoxmlPackage) -> T): T = read(fallback, block)

    /** Shape ids of the slide in drawing order (a group's shapes right after it). */
    fun shapeOrder(slideIndex: Int): List<Int> = listShapes(slideIndex).map { it.id }

    /**
     * A shape copied by [copyShape]: its XML at slide coordinates (a shape in a group too), the
     * pictures and links it uses, its [text] for other apps and where it was ([rect], EMU).
     */
    class ShapeClip internal constructor(internal val element: Element, internal val rels: List<Pair<OoxmlPackage.Rel, ByteArray?>>,
                                         val text: String, val rect: Rect)

    /**
     * The shape [shapeId] with every queued edit, for [pasteShape]; null (see [lastError]) when it
     * is not there or holds an object kept in parts of its own (a chart, a diagram...).
     */
    fun copyShape(slideIndex: Int, shapeId: Int): ShapeClip? {
        val info = listShapes(slideIndex).firstOrNull { it.id == shapeId } ?: return null.also { lastError = failure(NoSuchElementException("Shape $shapeId not found")) }
        return read(null) { pkg ->
            val part = part(pkg, slideIndex)
            val copy = find(pkg, slideIndex, shapeId).first.createCopy()!!
            // at slide coordinates: a shape of a group, or placed by its layout, keeps where it is shown
            setOffExt(copy, info.rectEmu)
            val used = descendants(copy).flatMap { e -> e.attributes().orEmpty().filterIsInstance<com.wxiwei.office.fc.dom4j.Attribute>().filter { it.namespaceURI == R.uRI }.mapNotNull { it.value } }.toSet()
            val rels = pkg.relationships(part).filter { it.id in used }.map { rel ->
                when {
                    rel.targetMode == "External" -> rel to null
                    rel.type == REL_IMAGE -> rel to (pkg.bytes(pkg.resolveTarget(part, rel.target)) ?: throw NoSuchElementException("Missing picture ${rel.target}"))
                    else -> throw UnsupportedOperationException("This object cannot be copied")
                }
            }
            ShapeClip(copy, rels, info.text, info.rectEmu)
        }
    }

    /** Puts a copy of [clip] on slide [slideIndex], moved by [dx], [dy] EMU, on top. Returns its id, or -1. */
    fun pasteShape(slideIndex: Int, clip: ShapeClip, dx: Long, dy: Long): Int {
        val id = nextId(slideIndex); if (id < 0) return -1
        return if (queue { pkg ->
            val part = part(pkg, slideIndex)
            val e = clip.element.createCopy()!!
            // new ids for the shape and every shape inside it
            var next = id
            descendants(e).filter { it.namespaceURI == P.uRI && it.name == "cNvPr" }.forEach { it.addAttribute("id", "${next++}") }
            // its pictures and links, added to this slide
            val ids = HashMap<String, String>()
            for ((rel, bytes) in clip.rels) ids[rel.id] = if (bytes == null) pkg.addRelationship(part, rel.type, rel.target, external = true) else {
                val media = pkg.addMedia("ppt/media", bytes, rel.target.substringAfterLast('.', "png"))
                pkg.addRelationship(part, rel.type, "../media/${media.substringAfterLast('/')}")
            }
            descendants(e).forEach { el -> el.attributes().orEmpty().filterIsInstance<com.wxiwei.office.fc.dom4j.Attribute>()
                .filter { it.namespaceURI == R.uRI && it.value in ids }.forEach { it.value = ids[it.value] } }
            val r = clip.rect
            setOffExt(e, Rect(r.x + dx, r.y + dy, r.width, r.height))
            // on top: after the other shapes, before the tree's extensions
            val tree = tree(pkg, part)
            val content = tree.content() as MutableList<Any?>
            val ext = tree.firstChild(P, "extLst")
            if (ext == null) content.add(e) else content.add(content.indexOf(ext), e)
        }) id else -1
    }

    /** Sets where [e] is (its xfrm's offset and size; a group keeps its child space). */
    private fun setOffExt(e: Element, r: Rect) {
        val x = xfrm(e) ?: when (e.name) {
            "grpSp" -> (e.firstChild(P, "grpSpPr") ?: e.child(P, "grpSpPr")).let { pr -> newElement(A, "xfrm").also { (pr.content() as MutableList<Any?>).add(0, it) } }
            "graphicFrame" -> newElement(P, "xfrm").also { x -> val c = e.content() as MutableList<Any?>; val nv = nv(e); c.add(if (nv == null) 0 else c.indexOf(nv) + 1, x) }
            else -> (e.firstChild(P, "spPr") ?: e.child(P, "spPr")).let { pr -> newElement(A, "xfrm").also { (pr.content() as MutableList<Any?>).add(0, it) } }
        }
        val off = x.firstChild(A, "off") ?: newElement(A, "off").also { (x.content() as MutableList<Any?>).add(0, it) }
        val ext = x.firstChild(A, "ext") ?: newElement(A, "ext").also { val c = x.content() as MutableList<Any?>; c.add(c.indexOf(off) + 1, it) }
        off.addAttribute("x", r.x.toString()); off.addAttribute("y", r.y.toString())
        ext.addAttribute("cx", r.width.toString()); ext.addAttribute("cy", r.height.toString())
    }

    fun deleteShape(slideIndex: Int, shapeId: Int): Boolean = queue { pkg ->
        val part = part(pkg, slideIndex); val e = find(pkg, slideIndex, shapeId).first
        val embeds = descendants(e).mapNotNull { it.attributeValue(QName("embed", R)) }.toSet()
        e.parent!!.remove(e)
        val used = descendants(pkg.xml(part).rootElement!!).mapNotNull { it.attributeValue(QName("embed", R)) }.toSet()
        val relPart = pkg.relsPartOf(part)
        if (pkg.has(relPart)) { val rels = pkg.xml(relPart).rootElement; rels!!.elements()!!.filterIsInstance<Element>().filter { it.attributeValue("Id") in (embeds - used) && it.attributeValue("Type") == REL_IMAGE }.forEach { rels!!.remove(it) } }
    }
    fun save(target: File): EditResult {
        if (!source.extension.equals("pptx", true)) return EditResult.Error(Reason.UNSUPPORTED_FORMAT, "Legacy PPT is unsupported")
        return try {
            require(source.canonicalFile != target.canonicalFile) { "Save to a new file" }
            snapshot().saveTo(target)
        } catch (e: Exception) { failure(e).also { lastError = it } }
    }
}
