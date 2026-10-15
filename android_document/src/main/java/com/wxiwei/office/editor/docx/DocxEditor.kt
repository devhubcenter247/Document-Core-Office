/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
package com.wxiwei.office.editor.docx

import com.wxiwei.office.editor.*
import com.wxiwei.office.editor.ooxml.*
import com.wxiwei.office.fc.dom4j.Element
import com.wxiwei.office.fc.dom4j.Namespace
import com.wxiwei.office.fc.dom4j.QName
import java.io.File
import java.text.Normalizer
import kotlin.math.roundToInt

private const val REL_NUMBERING = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
private val BULLETS = listOf("\u25CF", "\u25CB", "\u25A0")
private val NUMBER_FORMATS = listOf("decimal", "lowerLetter", "lowerRoman")

/** Queued operations use ORIGINAL UTF-16 model offsets, with exclusive ends.
 * Queue order is preserved, including overlapping formatting. Inserted text has no original offsets.
 * Save always reopens the source, verifies affected source leaves, then atomically writes a copy.
 */
class DocxEditor(private val source: File, private val map: DocxSourceMap) {
    private data class Op(val start: Long, val end: Long, val type: String, val value: String = "",
                          val image: File? = null, val width: Int = 0, val height: Int = 0, val cell: CellRef? = null,
                          val picture: Any? = null)

    /**
     * A table cell with no original offset (added by an earlier operation): cell [cell] of row [row]
     * of the table, counted when the operation runs. The table is the one [table] made (the
     * [lastOp] handle of an [insertTable]), else the one holding the operation's offset, which is
     * in its cell [anchorCell] of row [anchorRow] (checked, so the counts agree).
     */
    data class CellRef(val anchorRow: Int, val anchorCell: Int, val row: Int, val cell: Int, val table: Any? = null)

    /** Run properties (w:b "1", w:color "FF0000", w:sz half points...) for chars [from, to) of an inserted text. */
    data class RunFormat(val from: Int, val to: Int, val props: List<Pair<String, String>>)
    /**
     * What a queued insert (see [lastOp]) writes instead of its own text, e.g. the text as edited
     * later. For a new table cell, [paras] are the properties of its paragraphs, in order.
     */
    data class InsertOverride(val text: String, val runs: List<RunFormat>, val paras: List<ParaFormat> = emptyList())
    /**
     * Paragraph properties written as they are (null: left as it is): alignment (left, center,
     * right, both), indents in twips ([specialTwips] > 0 first line, < 0 hanging), spacing in
     * twips, line spacing [line] with its [lineRule] (auto, atLeast, exact), a [list] (bullet,
     * decimal, or "0" for none) and its level.
     */
    data class ParaFormat(val align: String? = null, val leftTwips: Int? = null, val rightTwips: Int? = null, val specialTwips: Int? = null,
                          val beforeTwips: Int? = null, val afterTwips: Int? = null, val line: Int? = null, val lineRule: String? = null,
                          val list: String? = null, val listLevel: Int? = null)
    private val ops = ArrayList<Op>()
    var lastError: EditResult.Error? = null; private set
    private fun queue(op: Op): Boolean {
        lastError = when {
            !source.extension.equals("docx", true) -> EditResult.Error(Reason.UNSUPPORTED_FORMAT, "Only DOCX is editable")
            op.start < 0 || op.end < op.start || map.partAt(op.start) == null || !mappedStory(op.start) ||
                (op.end > op.start && map.partAt(op.end - 1) != map.partAt(op.start)) ->
                EditResult.Error(Reason.INVALID_ARGUMENT, "Only the body, header and footer are editable")
            else -> null
        }
        if (lastError != null) return false
        ops.add(op); return true
    }

    // text boxes read with their source (those of the body; a header's are not)
    private val mappedTextboxes: Set<Long> by lazy {
        (0 until map.paragraphCount).map { map.paragraph(it).start }.filter { (it and AREA_MASK) == TEXTBOX }.map { it and STORY_MASK }.toSet()
    }
    private fun mappedStory(offset: Long) = (offset and AREA_MASK) != TEXTBOX || (offset and STORY_MASK) in mappedTextboxes

    /** Handle of the last queued operation (an insert), for [save] overrides. */
    fun lastOp(): Any? = ops.lastOrNull()

    private fun invalid(message: String): Boolean { lastError = EditResult.Error(Reason.INVALID_ARGUMENT, message); return false }
    fun highlight(start: Long, end: Long, color: String = "yellow"): Boolean {
        val hex = color.removePrefix("#")
        return if (hex.matches(Regex("(?i)[0-9a-f]{6}|[0-9a-f]{8}"))) queue(Op(start, end, "shd", hex.takeLast(6).uppercase()))
        else if (color in setOf("black", "blue", "cyan", "green", "magenta", "red", "yellow", "white", "darkBlue", "darkCyan", "darkGreen", "darkMagenta", "darkRed", "darkYellow", "darkGray", "lightGray", "none")) queue(Op(start, end, "highlight", color))
        else invalid("Invalid highlight color")
    }
    fun setBold(start: Long, end: Long, on: Boolean) = queue(Op(start, end, "b", if (on) "1" else "0"))
    fun setItalic(start: Long, end: Long, on: Boolean) = queue(Op(start, end, "i", if (on) "1" else "0"))
    fun setUnderline(start: Long, end: Long, on: Boolean) = queue(Op(start, end, "u", if (on) "single" else "none"))
    fun setStrike(start: Long, end: Long, on: Boolean) = queue(Op(start, end, "strike", if (on) "1" else "0"))
    /** The font of [start, end): [name] for Latin, complex-script and East Asian text alike. */
    fun setFont(start: Long, end: Long, name: String) =
        if (name.isBlank() || name.length > 31) invalid("Bad font name") else queue(Op(start, end, "rFonts", name))
    /** [script]: 1 superscript, 2 subscript, 0 back on the line. */
    fun setScript(start: Long, end: Long, script: Int) = queue(Op(start, end, "vertAlign", when (script) { 1 -> "superscript"; 2 -> "subscript"; else -> "baseline" }))
    fun setTextColor(start: Long, end: Long, rgbHex: String): Boolean {
        val rgb = rgbHex.removePrefix("#")
        return if (rgb.matches(Regex("(?i)[0-9a-f]{6}"))) queue(Op(start, end, "color", rgb.uppercase())) else invalid("Expected RRGGBB")
    }
    fun setFontSize(start: Long, end: Long, pt: Number): Boolean =
        if (pt.toDouble().isFinite() && pt.toDouble() in 1.0..1638.0) queue(Op(start, end, "sz", (pt.toDouble() * 2).roundToInt().toString())) else invalid("Invalid font size")
    /** Paragraph alignment of every paragraph touching [start, end): left, center, right, both. */
    fun setParagraphAlignment(start: Long, end: Long, align: String): Boolean =
        if (align in setOf("left", "center", "right", "both")) queue(Op(start, end, "pjc", align)) else invalid("Bad alignment")
    /** Left indent in twips (1/20 pt) of every paragraph touching [start, end). */
    fun setParagraphIndent(start: Long, end: Long, leftTwips: Int): Boolean =
        if (leftTwips in 0..31680) queue(Op(start, end, "pind", leftTwips.toString())) else invalid("Bad indent")
    /** Line spacing as a multiple of single (1.0, 1.5, 2.0...) of every paragraph touching [start, end). */
    fun setLineSpacing(start: Long, end: Long, multiple: Float): Boolean =
        if (multiple in 0.25f..10f) queue(Op(start, end, "pline", Math.round(multiple * 240).toString())) else invalid("Bad line spacing")
    /** Line height of the paragraphs [exactly] [points] (or at least that much). */
    fun setLineSpacingPoints(start: Long, end: Long, points: Float, exactly: Boolean): Boolean =
        if (points in 1f..1584f) queue(Op(start, end, "pline", Math.round(points * 20).toString() + "," + (if (exactly) "exact" else "atLeast"))) else invalid("Bad line spacing")
    /** Space before and after the paragraphs, in points. */
    fun setParagraphSpacing(start: Long, end: Long, beforePt: Float, afterPt: Float): Boolean =
        if (beforePt in 0f..1584f && afterPt in 0f..1584f) queue(Op(start, end, "pspace", "${Math.round(beforePt * 20)},${Math.round(afterPt * 20)}")) else invalid("Bad paragraph spacing")
    /**
     * Word's Paragraph dialog in one step: alignment (left, center, right, both), left/right indent in
     * twips, [specialTwips] > 0 first-line indent / < 0 hanging indent, space before/after in points.
     */
    fun setParagraphLayout(start: Long, end: Long, align: String, leftTwips: Int, rightTwips: Int, specialTwips: Int, beforePt: Float, afterPt: Float): Boolean = when {
        align !in setOf("left", "center", "right", "both") -> invalid("Bad alignment")
        leftTwips !in -31680..31680 || rightTwips !in -31680..31680 || specialTwips !in -31680..31680 -> invalid("Bad indent")
        beforePt !in 0f..1584f || afterPt !in 0f..1584f -> invalid("Bad paragraph spacing")
        else -> queue(Op(start, end, "ppara", "$align,$leftTwips,$rightTwips,$specialTwips,${Math.round(beforePt * 20)},${Math.round(afterPt * 20)}"))
    }
    /** Bullets ("●" list, level 0) on or off for every paragraph touching [start, end). */
    fun setBullets(start: Long, end: Long, on: Boolean) = queue(Op(start, end, "pnum", if (on) "bullet" else "0"))
    /** List level (0-8) of every listed paragraph touching [start, end). */
    fun setListLevel(start: Long, end: Long, level: Int): Boolean =
        if (level in 0..8) queue(Op(start, end, "plvl", level.toString())) else invalid("Bad list level")
    /** Numbering ("1." list, level 0) on or off for every paragraph touching [start, end). */
    fun setNumbering(start: Long, end: Long, on: Boolean) = queue(Op(start, end, "pnum", if (on) "decimal" else "0"))

    /**
     * w:abstractNumId of the list [setBullets] uses: the document's first bullet list, else the
     * one save will add. The live view shows bullets with the list of this id.
     */
    val bulletListId: Int by lazy { listIds.first }
    /** Same for [setNumbering]. */
    val numberingListId: Int by lazy { listIds.second }
    private val listIds: Pair<Int, Int> by lazy {
        try {
            val pkg = OoxmlPackage.open(source)
            val bullet = listOf(pkg, "bullet", create = false).first
            // a new numbered list would come right after a new bullet list
            val decimal = listOf(pkg, "decimal", create = false, reserved = setOf(bullet)).first
            bullet to decimal
        } catch (e: Exception) { -1 to -1 }
    }

    private fun numberingPart(pkg: OoxmlPackage, create: Boolean): String? {
        val doc = "word/document.xml"
        pkg.relationships(doc).firstOrNull { it.type == REL_NUMBERING && it.targetMode != "External" }?.let { return pkg.resolveTarget(doc, it.target) }
        if (!create) return null
        val part = "word/numbering.xml"
        pkg.putXml(part, com.wxiwei.office.fc.dom4j.DocumentHelper.createDocument(newElement(W, "numbering"))!!)
        pkg.addRelationship(doc, REL_NUMBERING, "numbering.xml")
        pkg.ensureOverride(part, "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml")
        return part
    }

    /**
     * (abstractNumId, numId) of a list whose first level has [format] (bullet, decimal); with
     * [create], added to the package when missing. [reserved] ids are taken by lists still to add.
     */
    private fun listOf(pkg: OoxmlPackage, format: String, create: Boolean, reserved: Set<Int> = emptySet()): Pair<Int, String> {
        val root = numberingPart(pkg, create)?.let { pkg.xml(it).rootElement }
        val abstracts = root?.childrenNamed(W, "abstractNum").orEmpty()
        val nums = root?.childrenNamed(W, "num").orEmpty()
        fun Element.w(name: String) = attributeValue(QName(name, W))
        for (a in abstracts) {
            val lvl = a.childrenNamed(W, "lvl").firstOrNull { it.w("ilvl") == "0" } ?: continue
            if (lvl.firstChild(W, "numFmt")?.w("val") != format) continue
            val id = a.w("abstractNumId") ?: continue
            val num = nums.firstOrNull { it.firstChild(W, "abstractNumId")?.w("val") == id } ?: continue
            return (id.toIntOrNull() ?: continue) to (num.w("numId") ?: continue)
        }
        val abstractId = ((abstracts.mapNotNull { it.w("abstractNumId")?.toIntOrNull() } + reserved).maxOrNull() ?: -1) + 1
        val numId = ((nums.mapNotNull { it.w("numId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        if (!create || root == null) return abstractId to numId
        val abstract = newElement(W, "abstractNum").apply {
            addAttribute(QName("abstractNumId", W), abstractId.toString())
            addElement(QName("multiLevelType", W))!!.addAttribute(QName("val", W), "hybridMultilevel")
            for (i in 0..8) addElement(QName("lvl", W))!!.apply {
                addAttribute(QName("ilvl", W), i.toString())
                addElement(QName("start", W))!!.addAttribute(QName("val", W), "1")
                addElement(QName("numFmt", W))!!.addAttribute(QName("val", W), if (format == "bullet") "bullet" else NUMBER_FORMATS[i % NUMBER_FORMATS.size])
                addElement(QName("lvlText", W))!!.addAttribute(QName("val", W), if (format == "bullet") BULLETS[i % BULLETS.size] else "%${i + 1}.")
                addElement(QName("lvlJc", W))!!.addAttribute(QName("val", W), "left")
                addElement(QName("pPr", W))!!.addElement(QName("ind", W))!!
                    .addAttribute(QName("left", W), (720 * (i + 1)).toString())!!.addAttribute(QName("hanging", W), "360")
            }
        }
        val num = newElement(W, "num").apply {
            addAttribute(QName("numId", W), numId)
            addElement(QName("abstractNumId", W))!!.addAttribute(QName("val", W), abstractId.toString())
        }
        // CT_Numbering: every abstractNum, then every num, then numIdMacAtCleanup
        val children = root.elements()!!.filterIsInstance<Element>()
        addBefore(root, abstracts.lastOrNull()?.let { children.getOrNull(children.indexOf(it) + 1) } ?: children.firstOrNull { it.name == "num" || it.name == "numIdMacAtCleanup" }, abstract)
        val after = root.elements()!!.filterIsInstance<Element>()
        addBefore(root, nums.lastOrNull()?.let { after.getOrNull(after.indexOf(it) + 1) } ?: after.firstOrNull { it.name == "numIdMacAtCleanup" }, num)
        return abstractId to numId
    }

    fun insertText(offset: Long, text: String) = queue(Op(offset, offset, "insert", text))
    fun deleteText(start: Long, end: Long) = if (touchesObject(start, end)) invalid("Pictures and fields cannot be deleted as text") else queue(Op(start, end, "delete"))
    fun replaceText(start: Long, end: Long, text: String) = if (touchesObject(start, end)) invalid("Pictures and fields cannot be replaced as text") else queue(Op(start, end, "replace", text))
    /** True when [start, end) (original offsets) holds a picture, shape or field, which save cannot delete. */
    fun touchesObject(start: Long, end: Long): Boolean = (0 until map.size).any { i ->
        val l = map.leaf(i)
        l.start < end && l.end > start && (l.kind == DocxSourceMap.Kind.FIELD || l.kind == DocxSourceMap.Kind.OBJECT)
    }
    fun insertImage(offset: Long, imageFile: File, widthPx: Int, heightPx: Int): Boolean =
        if (widthPx <= 0 || heightPx <= 0 || !imageFile.isFile) invalid("Image file and positive dimensions required")
        else queue(Op(offset, offset, "image", image = imageFile, width = widthPx, height = heightPx))
    fun appendParagraph(text: String) = queue(Op(0, 0, "append", text))
    /** The picture or shape at [offset] (its one-char object; or the one an [insertImage] handle [picture] made) gets the size [widthEmu] x [heightEmu]. */
    fun resizeObject(offset: Long, widthEmu: Long, heightEmu: Long, picture: Any? = null): Boolean =
        if (widthEmu <= 0 || heightEmu <= 0) invalid("Positive size required") else queue(Op(offset, offset, "objsize", "$widthEmu,$heightEmu", picture = picture))
    /** Moves the in-line picture at [from] (or the one an [insertImage] handle [picture] made) to the text position [to] (original offsets). */
    fun moveObject(from: Long, to: Long, picture: Any? = null): Boolean = queue(Op(from, from, "objmove", to.toString(), picture = picture))
    /** Moves the floating picture or shape at [offset] by [dxEmu], [dyEmu] on the page. */
    fun shiftObject(offset: Long, dxEmu: Long, dyEmu: Long): Boolean = queue(Op(offset, offset, "objshift", "$dxEmu,$dyEmu"))
    /** A [rows] x [cols] table with thin borders after the body paragraph holding [offset]. */
    fun insertTable(offset: Long, rows: Int, cols: Int): Boolean =
        if (rows !in 1..200 || cols !in 1..63) invalid("Bad table size") else queue(Op(offset, offset, "table", width = rows, height = cols))
    /**
     * The border after grid column [boundary] - 1 of the table holding [offset] moves by [dTwips]:
     * the column before it gets wider, the one after it narrower (the table keeps its width);
     * the right edge ([boundary] = column count) widens the last column and the table.
     */
    fun resizeTableColumn(offset: Long, boundary: Int, dTwips: Int, cell: CellRef? = null): Boolean =
        if (boundary < 1) invalid("Bad column border") else queue(Op(offset, offset, "tblcol", "$boundary,$dTwips", cell = cell))
    /** A new empty row [below] (or above) the row holding [offset] (or the one [cell] names), with the same cells. */
    fun insertTableRow(offset: Long, below: Boolean, cell: CellRef? = null): Boolean = queue(Op(offset, offset, "tblrowins", if (below) "below" else "above", cell = cell))
    /** A new empty column [right] of (or left of) the cell holding [offset], as wide as that cell's column was; all columns shrink so the table keeps its width. */
    fun insertTableColumn(offset: Long, right: Boolean, cell: CellRef? = null): Boolean = queue(Op(offset, offset, "tblcolins", if (right) "right" else "left", cell = cell))
    /** Removes the row holding [offset] (not the last one of its table). */
    fun deleteTableRow(offset: Long, cell: CellRef? = null): Boolean = queue(Op(offset, offset, "tblrowdel", cell = cell))
    /** Removes the grid columns of the cell holding [offset] (not the last one); the others widen so the table keeps its width. */
    fun deleteTableColumn(offset: Long, cell: CellRef? = null): Boolean = queue(Op(offset, offset, "tblcoldel", cell = cell))
    /** The row holding [offset] is at least [twips] high. */
    fun setTableRowHeight(offset: Long, twips: Int, cell: CellRef? = null): Boolean =
        if (twips < 0) invalid("Bad row height") else queue(Op(offset, offset, "tblrow", twips.toString(), cell = cell))
    /** Moves the body table holding [offset] before the body paragraph (or table) holding [to], or [after] it. */
    fun moveTable(offset: Long, to: Long, after: Boolean, cell: CellRef? = null): Boolean = queue(Op(offset, offset, "tblmove", "$to,$after", cell = cell))
    /**
     * A copy of the body table holding [offset] (or the one [cell] names) as the queued edits make
     * it, for [pasteTable]; null (see [lastError]) when there is none. Bookmarks and Word's paragraph
     * ids are left out of the copy, so pasting it twice makes no duplicates.
     */
    fun copyTable(offset: Long, cell: CellRef? = null, overrides: Map<Any, InsertOverride> = emptyMap(),
                  cellFills: Map<Any, List<InsertOverride?>> = emptyMap()): Element? {
        grabbed = null
        if (!queue(Op(offset, offset, "tblgrab", cell = cell))) return null
        try { write(null, overrides, cellFills) } finally { ops.removeAt(ops.lastIndex) }
        return grabbed
    }
    // the table the last [copyTable] took
    private var grabbed: Element? = null

    /** Puts a copy of [table] (from [copyTable]) before the body paragraph (or table) holding [to], or [after] it. */
    fun pasteTable(to: Long, after: Boolean, table: Element): Boolean =
        if (table.name != "tbl" || table.namespaceURI != W.uRI) invalid("Not a table") else queue(Op(to, to, "tblpaste", after.toString(), picture = table))

    /** Removes the body table holding [offset] (or the one [cell] names), keeping a paragraph where it was when one is needed. */
    fun deleteTable(offset: Long, cell: CellRef? = null): Boolean = queue(Op(offset, offset, "tbldel", cell = cell))

    /** Drops the last queued operation (undo of a live edit). */
    fun undoLast(): Boolean = if (ops.isEmpty()) false else { ops.removeAt(ops.lastIndex); true }
    /** Number of queued operations. */
    val pendingCount: Int get() = ops.size

    private class Failure(val reason: Reason, message: String) : Exception(message)
    private fun fail(reason: Reason, message: String): Nothing = throw Failure(reason, message)
    private data class Piece(var start: Long, var end: Long, val run: Element, val kind: DocxSourceMap.Kind)
    private data class Boundary(val parent: Element, val before: Element?, val style: Element?)

    /**
     * Writes the edited document to [target]. [overrides] (by [lastOp] handle) replace the text
     * of queued inserts, with their run formatting.
     */
    /**
     * [cellFills] (by [lastOp] handle of an [insertTableRow] or [insertTableColumn]) is the text of
     * each cell that op makes, in order (null: left empty), with its run formatting.
     */
    fun save(target: File, overrides: Map<Any, InsertOverride> = emptyMap(), cellFills: Map<Any, List<InsertOverride?>> = emptyMap()): EditResult =
        write(target, overrides, cellFills)

    /** Applies the queued operations as [save] does, without writing a file: an [EditResult.Error] names what save would fail on. */
    fun check(overrides: Map<Any, InsertOverride> = emptyMap(), cellFills: Map<Any, List<InsertOverride?>> = emptyMap()): EditResult =
        write(null, overrides, cellFills)

    private fun write(target: File?, overrides: Map<Any, InsertOverride>, cellFills: Map<Any, List<InsertOverride?>>): EditResult {
        val result = try {
            if (!source.extension.equals("docx", true)) fail(Reason.UNSUPPORTED_FORMAT, "Only DOCX is editable")
            val pkg = OoxmlPackage.open(source)
            // one pass per edited part: the body (with its text boxes), a header, a footer
            val parts = map.parts()
            for (name in ops.map { (map.partAt(it.start) ?: fail(Reason.MAP_MISMATCH, "No part for offset ${it.start}")).name }.distinct()) {
                Session(pkg, java.util.IdentityHashMap(overrides), name, parts.filter { it.name == name }, java.util.IdentityHashMap(cellFills)).applyAll()
            }
            if (target != null) pkg.saveTo(target) else EditResult.Ok(source)
        } catch (e: Failure) { EditResult.Error(e.reason, e.message ?: "Edit failed")
        } catch (e: IllegalArgumentException) { EditResult.Error(Reason.INVALID_ARGUMENT, e.message ?: "Invalid argument", e)
        } catch (e: java.io.IOException) { EditResult.Error(Reason.IO, e.message ?: "I/O error", e)
        } catch (e: Exception) { EditResult.Error(Reason.INTERNAL, e.message ?: "Edit failed", e) }
        lastError = result as? EditResult.Error
        return result
    }

    private inner class Session(val pkg: OoxmlPackage, val overrides: java.util.IdentityHashMap<Any, InsertOverride>,
                                val part: String, val ranges: List<DocxSourceMap.Part>,
                                val cellFills: java.util.IdentityHashMap<Any, List<InsertOverride?>> = java.util.IdentityHashMap()) {
        val root: Element = pkg.xml(part).rootElement!!
        // w:hdr and w:ftr hold their paragraphs directly
        val body = if (part == "word/document.xml") root.firstChild(W, "body") ?: fail(Reason.MAP_MISMATCH, "No document body") else root
        private fun mine(offset: Long) = ranges.any { offset >= it.start && offset < it.end }
        val ops = this@DocxEditor.ops.filter { mine(it.start) }
        val runs = ArrayList<Element>()
        val paragraphs = ArrayList<Element>()
        val leaves = (0 until map.size).map { map.leaf(it) }.filter { mine(it.start) }.sortedBy { it.start }
        val paras = (0 until map.paragraphCount).map { map.paragraph(it) }.filter { mine(it.start) }
        val pieces = ArrayList<Piece>()
        val insertionEnds = HashMap<Long, Element>()
        val deletedSeparators = HashSet<Long>()
        val opaqueSpans = HashMap<Int, LongRange>()
        val listNumIds = HashMap<String, String>()
        /** The w:tbl each queued [insertTable] made, by its op. */
        val createdTables = java.util.IdentityHashMap<Any, Element>()
        /** The w:r each queued [insertImage] made, by its op: later [resizeObject] and [moveObject] with that handle find it. */
        val createdPictures = java.util.IdentityHashMap<Any, Element>()
        fun listNumId(format: String): String = listNumIds.getOrPut(format) {
            // lists are added in the same order the view reserved their ids: bullets first
            if (format == "decimal" && bulletListId >= 0 && numberingListId == bulletListId + 1 && "bullet" !in listNumIds) listNumId("bullet")
            listOf(pkg, format, create = true).second
        }
        init {
            body.elements()!!.filterIsInstance<Element>().filter { it.namespaceURI == W.uRI && it.name in setOf("p", "tbl", "sdt") }.forEach { child ->
                walk(child) { if (it.namespaceURI == W.uRI) when (it.name) { "r" -> runs.add(it); "p" -> paragraphs.add(it) } }
            }
        }
        fun affected(leaf: DocxSourceMap.Leaf) = ops.any { op -> op.type != "append" &&
            if (op.start == op.end) op.start in leaf.start..leaf.end else leaf.start < op.end && leaf.end > op.start }
        fun applyAll() {
            // Verify against untouched DOM, before any operation can change its text.
            for (leaf in leaves) {
                if (leaf.kind == DocxSourceMap.Kind.PARA_END) continue
                val rr = leaf.runIndices.map { runs.getOrNull(it) ?: fail(Reason.MAP_MISMATCH, "Missing run $it") }
                if (affected(leaf)) {
                    val actual = if (leaf.kind == DocxSourceMap.Kind.OBJECT) {
                        if (rr.size == 1 && rr[0].elements()!!.filterIsInstance<Element>().any { it.name in setOf("drawing", "pict", "object", "AlternateContent") }) "1" else ""
                    } else rr.joinToString("") { runText(it) }
                    if (actual != leaf.text || rr.isEmpty()) fail(Reason.MAP_MISMATCH, "Source text differs at ${leaf.start}")
                }
                if (leaf.kind == DocxSourceMap.Kind.OBJECT || leaf.kind == DocxSourceMap.Kind.FIELD) leaf.runIndices.forEach { id ->
                    val old = opaqueSpans[id]
                    opaqueSpans[id] = minOf(old?.first ?: leaf.start, leaf.start)..maxOf(old?.last ?: leaf.end, leaf.end)
                }
                if (leaf.kind == DocxSourceMap.Kind.TEXT && rr.size == 1) pieces.add(Piece(leaf.start, leaf.end, rr[0], leaf.kind))
                else rr.forEach { pieces.add(Piece(leaf.start, leaf.end, it, leaf.kind)) }
            }
            for (op in ops) {
                if (op.type == "append") { append(op.value); continue }
                checkRange(op)
                when (op.type) {
                    "insert" -> overrides[op].let { o -> insert(boundary(op.start), o?.text ?: op.value, o?.runs) }?.let { insertionEnds[op.start] = it }
                    "image" -> { val b = boundary(op.start); val run = image(op); addBefore(b.parent, b.before, run); insertionEnds[op.start] = run; createdPictures[op] = run }
                    "delete", "replace" -> {
                        // Capture insertion anchor before removing original characters.
                        val b = if (op.type == "replace") boundary(op.start) else null
                        if (b != null) overrides[op].let { o -> insert(b, o?.text ?: op.value, o?.runs) }?.let { insertionEnds[op.start] = it }
                        delete(op.start, op.end)
                    }
                    "pjc", "pind", "pline", "pspace", "pnum", "plvl", "ppara" -> paragraphFormat(op)
                    "table" -> table(op)
                    "tblmove" -> moveTable(op)
                    "tblgrab" -> grabTable(op)
                    "tblpaste" -> pasteTable(op)
                    "tbldel" -> removeTable(op)
                    "tblcol" -> tableColumn(op)
                    "tblrow" -> tableRow(op)
                    "tblrowins" -> insertRow(op)
                    "tblcolins" -> insertColumn(op)
                    "tblrowdel" -> deleteRow(op)
                    "tblcoldel" -> deleteColumn(op)
                    "objsize", "objmove", "objshift" -> objectOp(op)
                    else -> format(op)
                }
            }
        }
        fun checkRange(op: Op) {
            if (op.start == op.end) {
                if (paras.none { op.start >= it.start && op.start < it.end }) fail(Reason.INVALID_ARGUMENT, "Offset outside mapped body")
            } else {
                var covered = op.start
                for (leaf in leaves.filter { it.start < op.end && it.end > op.start }) {
                    if (leaf.start > covered) fail(Reason.MAP_MISMATCH, "Unmapped selection")
                    covered = maxOf(covered, leaf.end)
                }
                if (covered < op.end) fail(Reason.MAP_MISMATCH, "Unmapped selection")
            }
        }
        fun split(at: Long) {
            val piece = pieces.firstOrNull { it.kind == DocxSourceMap.Kind.TEXT && at > it.start && at < it.end } ?: return
            val parent = piece.run.parent ?: fail(Reason.MAP_MISMATCH, "Run was removed")
            val cut = (at - piece.start).toInt()
            val originalText = runText(piece.run)
            val raw = rawRunText(piece.run)
            if (raw != originalText) fail(Reason.MAP_MISMATCH, "Cannot split normalized text safely")
            val left = sliceRun(piece.run, 0, cut)
            val right = sliceRun(piece.run, cut, originalText.length)
            addBefore(parent, piece.run, left); addBefore(parent, piece.run, right); piece.run.detach()
            val index = pieces.indexOf(piece)
            pieces.removeAt(index)
            pieces.add(index, Piece(at, piece.end, right, piece.kind))
            pieces.add(index, Piece(piece.start, at, left, piece.kind))
        }
        fun paragraphFormat(op: Op) {
            val end = maxOf(op.end, op.start + 1)
            for (p in paras.filter { it.start < end && it.end > op.start }) {
                val e = paragraphs.getOrNull(p.paraIndex) ?: fail(Reason.MAP_MISMATCH, "Missing paragraph")
                val pPr = e.firstChild(W, "pPr") ?: newElement(W, "pPr").also { addBefore(e, e.elements()!!.filterIsInstance<Element>().firstOrNull(), it) }
                if (op.type == "plvl") {
                    val numPr = pPr.firstChild(W, "numPr") ?: continue // style lists keep their level
                    val ilvl = numPr.firstChild(W, "ilvl") ?: newElement(W, "ilvl").also { addBefore(numPr, numPr.elements()!!.filterIsInstance<Element>().firstOrNull(), it) }
                    ilvl.addAttribute(QName("val", W), op.value)
                    continue
                }
                if (op.type == "pnum") {
                    // numId 0 turns off a list the paragraph style would give
                    pPr.firstChild(W, "numPr")?.let { pPr.remove(it) }
                    val numPr = newElement(W, "numPr")
                    numPr.addElement(QName("ilvl", W))!!.addAttribute(QName("val", W), "0")
                    numPr.addElement(QName("numId", W))!!.addAttribute(QName("val", W), if (op.value == "0") "0" else listNumId(op.value))
                    insertInPPr(pPr, numPr)
                    continue
                }
                if (op.type == "ppara") {
                    val v = op.value.split(',')
                    fun child(name: String) = pPr.firstChild(W, name) ?: newElement(W, name).also { insertInPPr(pPr, it) }
                    fun drop(e: Element, vararg names: String) = names.forEach { a -> e.attribute(QName(a, W))?.let { e.remove(it) } }
                    child("jc").addAttribute(QName("val", W), v[0])
                    val ind = child("ind")
                    drop(ind, "start", "end", "leftChars", "rightChars", "firstLineChars", "hangingChars", "firstLine", "hanging")
                    ind.addAttribute(QName("left", W), v[1])
                    ind.addAttribute(QName("right", W), v[2])
                    val special = v[3].toInt()
                    if (special > 0) ind.addAttribute(QName("firstLine", W), special.toString())
                    else if (special < 0) ind.addAttribute(QName("hanging", W), (-special).toString())
                    val spacing = child("spacing")
                    drop(spacing, "beforeLines", "afterLines", "beforeAutospacing", "afterAutospacing")
                    spacing.addAttribute(QName("before", W), v[4])
                    spacing.addAttribute(QName("after", W), v[5])
                    continue
                }
                val (name, attrs) = when (op.type) {
                    "pjc" -> "jc" to listOf("val" to op.value)
                    "pind" -> "ind" to listOf("left" to op.value)
                    "pspace" -> op.value.split(',').let { "spacing" to listOf("before" to it[0], "after" to it[1]) }
                    else -> op.value.split(',').let { "spacing" to listOf("line" to it[0], "lineRule" to (it.getOrNull(1) ?: "auto")) }
                }
                val child = pPr.firstChild(W, name) ?: newElement(W, name).also { insertInPPr(pPr, it) }
                // spacing in points wins over spacing in lines of text and "auto" spacing
                if (op.type == "pspace") listOf("beforeLines", "afterLines", "beforeAutospacing", "afterAutospacing").forEach { a -> child.attribute(QName(a, W))?.let { child.remove(it) } }
                // w:start is the bidi-neutral twin of w:left
                if (name == "ind") child.attribute(QName("start", W))?.let { child.remove(it) }
                attrs.forEach { (k, v) -> child.addAttribute(QName(k, W), v) }
            }
        }
        /** CT_PPr is a sequence: Word rejects children out of order. */
        fun insertInPPr(pPr: Element, child: Element) {
            val order = listOf("pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr",
                "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct",
                "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind",
                "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment",
                "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")
            val rank = order.indexOf(child.name)
            val next = pPr.elements()!!.filterIsInstance<Element>().firstOrNull { order.indexOf(it.name) > rank }
            addBefore(pPr, next, child)
        }
        fun format(op: Op) {
            split(op.end); split(op.start)
            pieces.filter { it.start < op.end && it.end > op.start }.forEach { piece -> runProp(piece.run, op.type, op.value) }
        }
        fun runProp(run: Element, type: String, value: String) {
            val pr = run.firstChild(W, "rPr") ?: newElement(W, "rPr").also { addBefore(run, run.elements()!!.filterIsInstance<Element>().firstOrNull(), it) }
            pr.childrenNamed(W, type).forEach { it.detach() }
            if (type == "highlight") pr.childrenNamed(W, "shd").forEach { it.detach() }
            if (type == "shd") pr.childrenNamed(W, "highlight").forEach { it.detach() }
            putRunProp(pr, newElement(W, type).apply {
                when (type) {
                    // one name for every script; no theme font over it
                    "rFonts" -> listOf("ascii", "hAnsi", "cs", "eastAsia").forEach { addAttribute(QName(it, W), value) }
                    "shd" -> { addAttribute(QName("fill", W), value); addAttribute(QName("val", W), "clear") }
                    else -> addAttribute(QName("val", W), value)
                }
            })
            if (type == "sz") { pr.childrenNamed(W, "szCs").forEach { it.detach() }; putRunProp(pr, newElement(W, "szCs").apply { addAttribute(QName("val", W), value) }) }
        }

        /** Puts [e] into the run properties [pr] where CT_RPr wants it: Word refuses them out of order. */
        fun putRunProp(pr: Element, e: Element) {
            val rank = RPR_ORDER.indexOf(e.name).let { if (it < 0) RPR_ORDER.size else it }
            val next = pr.elements()!!.filterIsInstance<Element>().firstOrNull { (RPR_ORDER.indexOf(it.name).let { i -> if (i < 0) RPR_ORDER.size else i }) > rank }
            addBefore(pr, next, e)
        }
        fun boundary(at: Long): Boundary {
            if (opaqueSpans.values.any { at > it.first && at < it.last })
                fail(Reason.INVALID_ARGUMENT, "Insert before or after the whole field/object run")
            leaves.firstOrNull { at > it.start && at < it.end && it.kind in setOf(DocxSourceMap.Kind.FIELD, DocxSourceMap.Kind.OBJECT) }?.let {
                fail(Reason.INVALID_ARGUMENT, "Insert before or after fields/objects")
            }
            insertionEnds[at]?.takeIf { it.parent != null }?.let { inserted ->
                val parent = inserted.parent
                val siblings = parent!!.elements()!!.filterIsInstance<Element>()
                return Boundary(parent, siblings.getOrNull(siblings.indexOf(inserted) + 1), inserted.firstChild(W, "rPr")?.createCopy())
            }
            split(at)
            val next = pieces.firstOrNull { it.start == at && it.run.parent != null }
            if (next != null) {
                val parent = next.run.parent
                val style = next.run.firstChild(W, "rPr")?.createCopy()
                return if (parent!!.name == "fldSimple") Boundary(parent.parent!!, parent, style) else Boundary(parent, next.run, style)
            }
            val previous = pieces.lastOrNull { it.end == at && it.run.parent != null }
            if (previous != null) {
                val anchor = if (previous.run.parent!!.name == "fldSimple") previous.run.parent else previous.run
                val parent = anchor!!.parent
                val siblings = parent!!.elements()!!.filterIsInstance<Element>()
                return Boundary(parent, siblings.getOrNull(siblings.indexOf(anchor) + 1), previous.run.firstChild(W, "rPr")?.createCopy())
            }
            val para = paras.firstOrNull { at >= it.start && at < it.end } ?: fail(Reason.INVALID_ARGUMENT, "No paragraph at offset")
            val element = paragraphs.getOrNull(para.paraIndex) ?: fail(Reason.MAP_MISMATCH, "Missing paragraph")
            // the text from here on was deleted: go before what is left of the paragraph after it
            val later = pieces.filter { it.start > at && it.start < para.end && it.run.parent != null }.minByOrNull { it.start }
            if (later != null) {
                val parent = later.run.parent!!
                val style = later.run.firstChild(W, "rPr")?.createCopy()
                return if (parent.name == "fldSimple") Boundary(parent.parent!!, parent, style) else Boundary(parent, later.run, style)
            }
            if (at != para.start && leaves.none { it.kind == DocxSourceMap.Kind.PARA_END && it.start == at }) fail(Reason.INVALID_ARGUMENT, "Offset was deleted by an earlier operation")
            // the start of an emptied paragraph: after its properties, before anything else
            val first = if (at == para.start) element.elements()!!.filterIsInstance<Element>().firstOrNull { it.name != "pPr" } else null
            return Boundary(element, first, null)
        }
        fun insert(b: Boundary, text: String, formats: List<RunFormat>? = null): Element? {
            // runs for chars [at, at + part.length) of text, split where the formatting changes
            fun runs(part: String, at: Int): List<Element> {
                if (part.isEmpty()) return emptyList()
                if (formats == null) return listOf(textRun(part, b.style))
                val cuts = (listOf(at, at + part.length) + formats.flatMap { listOf(it.from, it.to) }).filter { it in at..at + part.length }.distinct().sorted()
                return cuts.zipWithNext().filter { it.first < it.second }.map { (x, z) ->
                    textRun(text.substring(x, z), b.style).also { run ->
                        formats.filter { it.from <= x && it.to >= z }.forEach { f -> f.props.forEach { (k, v) -> runProp(run, k, v) } }
                    }
                }
            }
            val parts = text.split('\n')
            if (parts.size == 1) {
                val made = runs(text, 0)
                made.forEach { addBefore(b.parent, b.before, it) }
                return made.lastOrNull()
            }
            if (b.parent.name != "p" || b.parent.namespaceURI != W.uRI) fail(Reason.INVALID_ARGUMENT, "Paragraph split inside a hyperlink/field is unsupported")
            val para = b.parent
            val parent = para.parent ?: fail(Reason.INVALID_ARGUMENT, "Detached paragraph")
            val after = parent.elements()!!.filterIsInstance<Element>().let { it.getOrNull(it.indexOf(para) + 1) }
            val tail = if (b.before == null) emptyList() else para.elements()!!.filterIsInstance<Element>().let { it.drop(it.indexOf(b.before)) }
            runs(parts[0], 0).forEach { addBefore(para, b.before, it) }
            var last = para
            var lastRun: Element? = null
            var at = parts[0].length + 1
            parts.drop(1).forEach { part ->
                last = newElement(W, "p")
                para.firstChild(W, "pPr")?.let { last.add(it.createCopy()) }
                val made = runs(part, at)
                made.forEach { last.add(it) }
                lastRun = made.lastOrNull()
                addBefore(parent, after, last)
                at += part.length + 1
            }
            tail.forEach { it.detach(); last.add(it) }
            // The original paragraph separator now belongs to the last new paragraph.
            for (i in paragraphs.indices) if (paragraphs[i] === para) paragraphs[i] = last
            return lastRun
        }
        fun delete(start: Long, end: Long) {
            val affected = leaves.filter { it.start < end && it.end > start }
            if (affected.any { it.kind in setOf(DocxSourceMap.Kind.FIELD, DocxSourceMap.Kind.OBJECT) }) fail(Reason.INVALID_ARGUMENT, "Text deletion of fields/objects is unsupported")
            split(end); split(start)
            pieces.filter { it.start >= start && it.end <= end }.toList().forEach { it.run.detach(); pieces.remove(it) }
            // Removing a paragraph separator joins adjacent paragraphs, never crosses cell boundaries.
            affected.filter { it.kind == DocxSourceMap.Kind.PARA_END && it.start !in deletedSeparators }.sortedByDescending { it.start }.forEach { leaf ->
                val p = paras.firstOrNull { leaf.start >= it.start && leaf.start < it.end } ?: fail(Reason.MAP_MISMATCH, "Missing paragraph")
                val first = paragraphs[p.paraIndex]
                val parent = first.parent ?: fail(Reason.INVALID_ARGUMENT, "Paragraph already deleted")
                val siblings = parent.elements()!!.filterIsInstance<Element>()
                val next = siblings.getOrNull(siblings.indexOf(first) + 1)
                if (next == null || next.name != "p" || next.namespaceURI != W.uRI) fail(Reason.INVALID_ARGUMENT, "Cannot delete final paragraph or cross table/cell boundaries")
                next.elements()!!.filterIsInstance<Element>().filter { it.name != "pPr" }.forEach { it.detach(); first.add(it) }
                for (i in paragraphs.indices) if (paragraphs[i] === next) paragraphs[i] = first
                next.detach()
                deletedSeparators.add(leaf.start)
            }
        }
        /** The run holding the picture/shape whose one-char object starts at [at]. */
        fun objectRun(at: Long): Element =
            pieces.firstOrNull { it.kind == DocxSourceMap.Kind.OBJECT && it.start == at && it.run.parent != null }?.run
                ?: fail(Reason.INVALID_ARGUMENT, "No picture at offset")

        fun objectOp(op: Op) {
            val run = op.picture?.let { createdPictures[it] ?: fail(Reason.MAP_MISMATCH, "Picture was removed") } ?: objectRun(op.start)
            when (op.type) {
                "objsize" -> {
                    val (cx, cy) = op.value.split(',')
                    // the frame on the page (wp:extent) and the picture itself (a:ext of its xfrm)
                    for (e in descendantsOf(run)) {
                        if ((e.name == "extent" && e.namespaceURI == WP.uRI) || (e.name == "ext" && e.namespaceURI == A.uRI && e.parent?.name == "xfrm")) {
                            e.addAttribute("cx", cx); e.addAttribute("cy", cy)
                        }
                    }
                }
                "objshift" -> {
                    val (dx, dy) = op.value.split(',').map { it.toLong() }
                    val anchor = descendantsOf(run).firstOrNull { it.name == "anchor" && it.namespaceURI == WP.uRI }
                        ?: fail(Reason.INVALID_ARGUMENT, "Not a floating picture")
                    for ((axis, d) in listOf("positionH" to dx, "positionV" to dy)) {
                        val pos = anchor.firstChild(WP, axis)?.firstChild(WP, "posOffset") ?: fail(Reason.INVALID_ARGUMENT, "Picture placed by alignment")
                        pos.text = ((pos.text?.trim()?.toLongOrNull() ?: 0L) + d).toString()
                    }
                }
                "objmove" -> {
                    val to = op.value.toLong()
                    if (to == op.start || to == op.start + 1) return
                    val b = boundary(to)
                    if (b.before === run) return
                    run.detach()
                    addBefore(b.parent, b.before, run)
                    // it keeps its piece: its old place is empty now
                    pieces.filter { it.run === run }.forEach { it.start = to; it.end = to }
                }
            }
        }

        fun table(op: Op) {
            val para = paras.firstOrNull { op.start >= it.start && op.start < it.end } ?: fail(Reason.INVALID_ARGUMENT, "No paragraph at offset")
            val p = paragraphs.getOrNull(para.paraIndex) ?: fail(Reason.MAP_MISMATCH, "Missing paragraph")
            if (p.parent !== body) fail(Reason.INVALID_ARGUMENT, "A table goes between paragraphs of the body")
            // the text width of the page, split evenly
            val sect = body.firstChild(W, "sectPr")
            fun twips(e: Element?, k: String) = e?.attributeValue(QName(k, W))?.toIntOrNull()
            val page = twips(sect?.firstChild(W, "pgSz"), "w") ?: 12240
            val margins = (twips(sect?.firstChild(W, "pgMar"), "left") ?: 1440) + (twips(sect?.firstChild(W, "pgMar"), "right") ?: 1440)
            val width = maxOf(1440, page - margins) / op.height * op.height
            val colWidth = width / op.height
            fun Element.w(name: String, vararg attrs: Pair<String, String>): Element =
                addElement(QName(name, W))!!.also { e -> attrs.forEach { (k, v) -> e.addAttribute(QName(k, W), v) } }
            val tbl = newElement(W, "tbl")
            tbl.w("tblPr").apply {
                w("tblW", "w" to width.toString(), "type" to "dxa")
                w("tblBorders").apply {
                    for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV"))
                        w(side, "val" to "single", "sz" to "4", "space" to "0", "color" to "auto")
                }
                w("tblLayout", "type" to "fixed")
            }
            tbl.w("tblGrid").apply { repeat(op.height) { w("gridCol", "w" to colWidth.toString()) } }
            val made = ArrayList<Element>()
            repeat(op.width) {
                val tr = tbl.w("tr")
                repeat(op.height) {
                    val tc = tr.w("tc")
                    tc.w("tcPr").w("tcW", "w" to colWidth.toString(), "type" to "dxa")
                    tc.w("p")
                    made.add(tc)
                }
            }
            createdTables[op] = tbl
            fill(op, made)
            val siblings = body.elements()!!.filterIsInstance<Element>()
            val next = siblings.getOrNull(siblings.indexOf(p) + 1)
            addBefore(body, next, tbl)
            // Word wants a paragraph after a table
            if (next == null || next.name != "p") addBefore(body, next, newElement(W, "p"))
        }
        /** The child of the body (a paragraph, a table) holding the paragraph at [offset]. */
        fun bodyChildAt(offset: Long): Element {
            val para = paras.firstOrNull { offset >= it.start && offset < it.end } ?: fail(Reason.INVALID_ARGUMENT, "No paragraph at offset")
            var e = paragraphs.getOrNull(para.paraIndex) ?: fail(Reason.MAP_MISMATCH, "Missing paragraph")
            while (e.parent !== body) e = e.parent ?: fail(Reason.INVALID_ARGUMENT, "Not in the body")
            return e
        }

        /** The paragraph at [offset] and its nearest ancestor named [local] (w:tbl, w:tr). */
        fun ancestorAt(offset: Long, local: String): Element {
            val para = paras.firstOrNull { offset >= it.start && offset < it.end } ?: fail(Reason.INVALID_ARGUMENT, "No paragraph at offset")
            var e: Element? = paragraphs.getOrNull(para.paraIndex) ?: fail(Reason.MAP_MISMATCH, "Missing paragraph")
            while (e != null && !(e.name == local && e.namespaceURI == W.uRI)) e = e.parent
            return e ?: fail(Reason.INVALID_ARGUMENT, "Not in a table")
        }
        fun twipsOf(e: Element?, attr: String = "w"): Int? = e?.attributeValue(QName(attr, W))?.toIntOrNull()

        /** The table of [op]: made by an earlier [insertTable] ([CellRef.table]), or the one holding its offset. */
        fun tableOf(op: Op): Element = op.cell?.table?.let { createdTables[it] ?: fail(Reason.MAP_MISMATCH, "Table was removed") } ?: ancestorAt(op.start, "tbl")

        /** The cell of [op]: the one holding its offset, or the one its [Op.cell] counts to. */
        fun cellOf(op: Op): Element {
            val ref = op.cell ?: return ancestorAt(op.start, "tc")
            if (ref.table != null) return tableOf(op).childrenNamed(W, "tr").getOrNull(ref.row)?.childrenNamed(W, "tc")?.getOrNull(ref.cell) ?: fail(Reason.MAP_MISMATCH, "No such cell")
            val tbl = ancestorAt(op.start, "tbl")
            val anchor = ancestorAt(op.start, "tc")
            val rows = tbl.childrenNamed(W, "tr")
            val anchorRow = anchor.parent
            if (rows.indexOf(anchorRow) != ref.anchorRow || anchorRow!!.childrenNamed(W, "tc").indexOf(anchor) != ref.anchorCell)
                fail(Reason.MAP_MISMATCH, "Table rows differ from the view")
            return rows.getOrNull(ref.row)?.childrenNamed(W, "tc")?.getOrNull(ref.cell) ?: fail(Reason.MAP_MISMATCH, "No such cell")
        }
        fun rowOf(op: Op): Element = if (op.cell == null) ancestorAt(op.start, "tr") else cellOf(op).parent!!

        fun tableColumn(op: Op) {
            val (g, d) = op.value.split(',').map { it.toInt() }
            val tbl = tableOf(op)
            val grid = tbl.firstChild(W, "tblGrid")?.childrenNamed(W, "gridCol") ?: fail(Reason.INVALID_ARGUMENT, "Table without grid")
            if (g !in 1..grid.size) fail(Reason.INVALID_ARGUMENT, "Bad column border")
            val widths = grid.map { twipsOf(it) ?: 0 }.toMutableList()
            widths[g - 1] += d
            if (g < widths.size) widths[g] -= d
            if (widths.any { it <= 0 }) fail(Reason.INVALID_ARGUMENT, "Column too narrow")
            grid.forEachIndexed { i, e -> e.addAttribute(QName("w", W), widths[i].toString()) }
            // the cells ending or starting at the border take the grid width they span
            for (tr in tbl.childrenNamed(W, "tr")) {
                var col = twipsOf(tr.firstChild(W, "trPr")?.firstChild(W, "gridBefore"), "val") ?: 0
                for (tc in tr.childrenNamed(W, "tc")) {
                    val span = twipsOf(tc.firstChild(W, "tcPr")?.firstChild(W, "gridSpan"), "val") ?: 1
                    if (col + span == g || col == g) {
                        tc.firstChild(W, "tcPr")?.firstChild(W, "tcW")?.let { w ->
                            w.addAttribute(QName("w", W), widths.subList(col, minOf(col + span, widths.size)).sum().toString())
                            w.addAttribute(QName("type", W), "dxa")
                        }
                    }
                    col += span
                }
            }
            // the right edge makes the table wider
            if (g == widths.size) tbl.firstChild(W, "tblPr")?.firstChild(W, "tblW")?.let { w ->
                if (w.attributeValue(QName("type", W)) == "dxa") w.addAttribute(QName("w", W), widths.sum().toString())
            }
        }

        fun tableRow(op: Op) {
            val tr = rowOf(op)
            // CT_Row: tblPrEx?, trPr?, then the cells
            val trPr = tr.firstChild(W, "trPr") ?: newElement(W, "trPr").also { p ->
                val children = tr.elements()!!.filterIsInstance<Element>()
                val after = children.firstOrNull { it.name == "tblPrEx" }
                addBefore(tr, if (after != null) children.getOrNull(children.indexOf(after) + 1) else children.firstOrNull(), p)
            }
            val h = trPr.firstChild(W, "trHeight") ?: trPr.addElement(QName("trHeight", W))!!
            h.addAttribute(QName("val", W), op.value)
            // "at least": the text still fits (an exact height keeps its rule)
            if (h.attributeValue(QName("hRule", W)) != "exact") h.addAttribute(QName("hRule", W), "atLeast")
        }

        /** An empty cell like [like]: its properties (no merge), one empty paragraph with its first paragraph's properties. */
        fun emptyCell(like: Element, widthTwips: Int? = null): Element {
            val tc = newElement(W, "tc")
            like.firstChild(W, "tcPr")?.let { pr ->
                val copy = pr.createCopy()!!
                copy.childrenNamed(W, "gridSpan").forEach { it.detach() }
                copy.childrenNamed(W, "vMerge").forEach { it.detach() }
                if (widthTwips != null) copy.firstChild(W, "tcW")?.let { w ->
                    w.addAttribute(QName("w", W), widthTwips.toString()); w.addAttribute(QName("type", W), "dxa")
                }
                tc.add(copy)
            }
            val p = tc.addElement(QName("p", W))!!
            like.firstChild(W, "p")?.firstChild(W, "pPr")?.let { p.add(it.createCopy()!!) }
            return tc
        }

        /** The text shown in the new cells [made] of [op], written into their empty paragraph, with the paragraphs' properties. */
        fun fill(op: Op, made: List<Element>) {
            val fills = cellFills[op] ?: return
            made.forEachIndexed { i, tc ->
                val f = fills.getOrNull(i) ?: return@forEachIndexed
                val p = tc.firstChild(W, "p") ?: return@forEachIndexed
                if (f.text.isNotEmpty()) insert(Boundary(p, null, null), f.text, f.runs)
                tc.childrenNamed(W, "p").forEachIndexed { k, e -> f.paras.getOrNull(k)?.let { paraFormat(e, it) } }
            }
        }

        /** Writes [f] into the properties of paragraph [p]. */
        fun paraFormat(p: Element, f: ParaFormat) {
            val pPr = p.firstChild(W, "pPr") ?: newElement(W, "pPr").also { addBefore(p, p.elements()!!.filterIsInstance<Element>().firstOrNull(), it) }
            fun child(name: String) = pPr.firstChild(W, name) ?: newElement(W, name).also { insertInPPr(pPr, it) }
            fun Element.put(k: String, v: Any) = addAttribute(QName(k, W), v.toString())
            fun Element.drop(vararg names: String) = names.forEach { a -> attribute(QName(a, W))?.let { remove(it) } }
            f.align?.let { child("jc").put("val", it) }
            if (f.leftTwips != null || f.rightTwips != null || f.specialTwips != null) child("ind").apply {
                // w:start/w:end are the bidi-neutral twins of w:left/w:right
                f.leftTwips?.let { drop("start", "leftChars"); put("left", it) }
                f.rightTwips?.let { drop("end", "rightChars"); put("right", it) }
                f.specialTwips?.let { s ->
                    drop("firstLine", "hanging", "firstLineChars", "hangingChars")
                    if (s > 0) put("firstLine", s) else if (s < 0) put("hanging", -s)
                }
            }
            if (f.beforeTwips != null || f.afterTwips != null || f.line != null) child("spacing").apply {
                f.beforeTwips?.let { drop("beforeLines", "beforeAutospacing"); put("before", it) }
                f.afterTwips?.let { drop("afterLines", "afterAutospacing"); put("after", it) }
                f.line?.let { put("line", it); put("lineRule", f.lineRule ?: "auto") }
            }
            if (f.list != null) {
                pPr.firstChild(W, "numPr")?.let { pPr.remove(it) }
                val numPr = newElement(W, "numPr")
                numPr.addElement(QName("ilvl", W))!!.addAttribute(QName("val", W), (f.listLevel ?: 0).toString())
                numPr.addElement(QName("numId", W))!!.addAttribute(QName("val", W), if (f.list == "0") "0" else listNumId(f.list))
                insertInPPr(pPr, numPr)
            } else f.listLevel?.let { level -> pPr.firstChild(W, "numPr")?.firstChild(W, "ilvl")?.addAttribute(QName("val", W), level.toString()) }
        }

        fun insertRow(op: Op) {
            val tr = rowOf(op)
            val row = newElement(W, "tr")
            val made = ArrayList<Element>()
            tr.firstChild(W, "tblPrEx")?.let { row.add(it.createCopy()!!) }
            // the same row properties, but never repeated as a header row
            tr.firstChild(W, "trPr")?.let { pr -> row.add(pr.createCopy()!!.also { c -> c.childrenNamed(W, "tblHeader").forEach { it.detach() } }) }
            for (tc in tr.childrenNamed(W, "tc")) {
                val cell = emptyCell(tc)
                // a merged cell stays merged over the new row's matching cells
                tc.firstChild(W, "tcPr")?.firstChild(W, "gridSpan")?.let { span ->
                    (cell.firstChild(W, "tcPr") ?: newElement(W, "tcPr").also { addBefore(cell, cell.elements()!!.filterIsInstance<Element>().firstOrNull(), it) }).add(span.createCopy()!!)
                }
                row.add(cell)
                made.add(cell)
            }
            val parent = tr.parent ?: fail(Reason.MAP_MISMATCH, "Row was removed")
            val siblings = parent.elements()!!.filterIsInstance<Element>()
            addBefore(parent, if (op.value == "below") siblings.getOrNull(siblings.indexOf(tr) + 1) else tr, row)
            fill(op, made)
        }

        fun insertColumn(op: Op) {
            val right = op.value == "right"
            val here = cellOf(op)
            val tbl = here.parent!!.parent!!
            val gridEl = tbl.firstChild(W, "tblGrid") ?: fail(Reason.INVALID_ARGUMENT, "Table without grid")
            val grid = gridEl.childrenNamed(W, "gridCol")
            // the grid columns of the cell holding the caret
            var at = -1
            var width = 0
            run {
                val tr = here.parent!!
                var col = twipsOf(tr.firstChild(W, "trPr")?.firstChild(W, "gridBefore"), "val") ?: 0
                for (tc in tr.childrenNamed(W, "tc")) {
                    val span = twipsOf(tc.firstChild(W, "tcPr")?.firstChild(W, "gridSpan"), "val") ?: 1
                    if (tc === here) {
                        at = if (right) col + span else col
                        width = twipsOf(grid.getOrNull(if (right) col + span - 1 else col)) ?: 1440
                    }
                    col += span
                }
            }
            if (at < 0) fail(Reason.MAP_MISMATCH, "Cell not in its row")
            val newCol = newElement(W, "gridCol").also { it.addAttribute(QName("w", W), width.toString()) }
            addBefore(gridEl, grid.getOrNull(at), newCol)
            val made = ArrayList<Element>()
            for (tr in tbl.childrenNamed(W, "tr")) {
                var col = twipsOf(tr.firstChild(W, "trPr")?.firstChild(W, "gridBefore"), "val") ?: 0
                val cells = tr.childrenNamed(W, "tc")
                var done = false
                for ((k, tc) in cells.withIndex()) {
                    val spanEl = tc.firstChild(W, "tcPr")?.firstChild(W, "gridSpan")
                    val span = twipsOf(spanEl, "val") ?: 1
                    when {
                        // a merged cell across the new column takes it in
                        col < at && at < col + span -> {
                            spanEl!!.addAttribute(QName("val", W), (span + 1).toString())
                            tc.firstChild(W, "tcPr")?.firstChild(W, "tcW")?.let { w -> twipsOf(w)?.let { w.addAttribute(QName("w", W), (it + width).toString()) } }
                            done = true
                        }
                        col == at -> { addBefore(tr, tc, emptyCell(tc, width).also { made.add(it) }); done = true }
                    }
                    if (done) break
                    col += span
                    // after the last cell
                    if (k == cells.lastIndex && col == at) { addBefore(tr, null, emptyCell(tc, width).also { made.add(it) }); done = true }
                }
            }
            fill(op, made)
            // the table keeps its width: every column gives up its share to the new one
            val cols = gridEl.childrenNamed(W, "gridCol")
            val old = cols.map { twipsOf(it) ?: 0 }
            val total = old.sum() - width
            if (total > 0) {
                val scaled = old.map { maxOf(1, Math.round(it.toDouble() * total / old.sum()).toInt()) }.toMutableList()
                scaled[scaled.lastIndex] += total - scaled.sum()
                cols.forEachIndexed { i, e -> e.addAttribute(QName("w", W), scaled[i].toString()) }
                for (tr in tbl.childrenNamed(W, "tr")) {
                    var col = twipsOf(tr.firstChild(W, "trPr")?.firstChild(W, "gridBefore"), "val") ?: 0
                    for (tc in tr.childrenNamed(W, "tc")) {
                        val span = twipsOf(tc.firstChild(W, "tcPr")?.firstChild(W, "gridSpan"), "val") ?: 1
                        tc.firstChild(W, "tcPr")?.firstChild(W, "tcW")?.let { w ->
                            if (w.attributeValue(QName("type", W)) == "dxa") w.addAttribute(QName("w", W), scaled.subList(minOf(col, scaled.size), minOf(col + span, scaled.size)).sum().toString())
                        }
                        col += span
                    }
                }
            }
        }

        /** Grid start column of each cell of [tr], with its span. */
        fun cellColumns(tr: Element): List<Triple<Element, Int, Int>> {
            var col = twipsOf(tr.firstChild(W, "trPr")?.firstChild(W, "gridBefore"), "val") ?: 0
            return tr.childrenNamed(W, "tc").map { tc ->
                val span = twipsOf(tc.firstChild(W, "tcPr")?.firstChild(W, "gridSpan"), "val") ?: 1
                Triple(tc, col, span).also { col += span }
            }
        }

        fun deleteRow(op: Op) {
            val tr = rowOf(op)
            val tbl = tr.parent ?: fail(Reason.MAP_MISMATCH, "Row was removed")
            val rows = tbl.childrenNamed(W, "tr")
            if (rows.size <= 1) fail(Reason.INVALID_ARGUMENT, "The last row of a table")
            // a vertical merge starting here starts in the next row now
            rows.getOrNull(rows.indexOf(tr) + 1)?.let { next ->
                val below = cellColumns(next)
                for ((tc, col, _) in cellColumns(tr)) {
                    if (tc.firstChild(W, "tcPr")?.firstChild(W, "vMerge")?.attributeValue(QName("val", W)) != "restart") continue
                    below.firstOrNull { it.second == col }?.first?.firstChild(W, "tcPr")?.firstChild(W, "vMerge")?.let { v ->
                        if (v.attributeValue(QName("val", W)) != "restart") v.addAttribute(QName("val", W), "restart")
                    }
                }
            }
            tr.detach()
        }

        fun deleteColumn(op: Op) {
            val here = cellOf(op)
            val tbl = here.parent!!.parent!!
            val gridEl = tbl.firstChild(W, "tblGrid") ?: fail(Reason.INVALID_ARGUMENT, "Table without grid")
            val grid = gridEl.childrenNamed(W, "gridCol")
            val (_, from, span) = cellColumns(here.parent!!).firstOrNull { it.first === here } ?: fail(Reason.MAP_MISMATCH, "Cell not in its row")
            val to = from + span
            if (span >= grid.size) fail(Reason.INVALID_ARGUMENT, "The last column of a table")
            for (tr in tbl.childrenNamed(W, "tr")) {
                for ((tc, col, n) in cellColumns(tr)) {
                    val overlap = minOf(col + n, to) - maxOf(col, from)
                    if (overlap <= 0) continue
                    if (overlap >= n) tc.detach()
                    else tc.firstChild(W, "tcPr")?.firstChild(W, "gridSpan")?.addAttribute(QName("val", W), (n - overlap).toString())
                }
                // a row left without cells goes too
                if (tr.childrenNamed(W, "tc").isEmpty()) tr.detach()
            }
            val old = grid.map { twipsOf(it) ?: 0 }
            val total = old.sum()
            grid.subList(from, to).forEach { it.detach() }
            val kept = old.filterIndexed { i, _ -> i !in from until to }
            // the table keeps its width: the other columns share what was taken out
            val scaled = kept.map { maxOf(1, Math.round(it.toDouble() * total / kept.sum()).toInt()) }.toMutableList()
            scaled[scaled.lastIndex] += total - scaled.sum()
            gridEl.childrenNamed(W, "gridCol").forEachIndexed { i, e -> e.addAttribute(QName("w", W), scaled[i].toString()) }
            for (tr in tbl.childrenNamed(W, "tr")) for ((tc, col, n) in cellColumns(tr)) {
                tc.firstChild(W, "tcPr")?.firstChild(W, "tcW")?.let { w ->
                    if (w.attributeValue(QName("type", W)) == "dxa") w.addAttribute(QName("w", W), scaled.subList(minOf(col, scaled.size), minOf(col + n, scaled.size)).sum().toString())
                }
            }
        }

        fun moveTable(op: Op) {
            val (to, after) = op.value.split(',').let { it[0].toLong() to it[1].toBoolean() }
            val tbl = if (op.cell?.table != null) tableOf(op) else bodyChildAt(op.start)
            if (tbl.name != "tbl" || tbl.namespaceURI != W.uRI) fail(Reason.INVALID_ARGUMENT, "No table at offset")
            val target = bodyChildAt(to)
            if (target === tbl) return
            tbl.detach()
            val siblings = body.elements()!!.filterIsInstance<Element>()
            addBefore(body, if (after) siblings.getOrNull(siblings.indexOf(target) + 1) else target, tbl)
            // Word wants a paragraph after a table
            val now = body.elements()!!.filterIsInstance<Element>()
            val next = now.getOrNull(now.indexOf(tbl) + 1)
            if (next == null || next.name != "p") addBefore(body, next, newElement(W, "p"))
        }
        fun grabTable(op: Op) {
            val tbl = if (op.cell?.table != null) tableOf(op) else bodyChildAt(op.start)
            if (tbl.name != "tbl" || tbl.namespaceURI != W.uRI) fail(Reason.INVALID_ARGUMENT, "No table at offset")
            val copy = tbl.createCopy()!!
            val drop = ArrayList<Element>()
            walk(copy) { e ->
                if (e.namespaceURI == W.uRI && e.name in setOf("bookmarkStart", "bookmarkEnd")) drop.add(e)
                e.attributes()?.filterIsInstance<com.wxiwei.office.fc.dom4j.Attribute>()?.forEach { a -> if (a.getName() in setOf("paraId", "textId")) e.remove(a) }
            }
            drop.forEach { it.detach() }
            grabbed = copy
        }
        fun removeTable(op: Op) {
            val tbl = if (op.cell?.table != null) tableOf(op) else bodyChildAt(op.start)
            if (tbl.name != "tbl" || tbl.namespaceURI != W.uRI) fail(Reason.INVALID_ARGUMENT, "No table at offset")
            val siblings = body.elements()!!.filterIsInstance<Element>()
            val next = siblings.getOrNull(siblings.indexOf(tbl) + 1)
            val prev = siblings.getOrNull(siblings.indexOf(tbl) - 1)
            tbl.detach()
            // the body keeps a paragraph before its section properties, and Word keeps one after a table
            if ((prev == null || prev.name == "tbl") && (next == null || next.name != "p")) addBefore(body, next, newElement(W, "p"))
        }
        fun pasteTable(op: Op) {
            val target = bodyChildAt(op.start)
            val copy = (op.picture as Element).createCopy()!!
            val siblings = body.elements()!!.filterIsInstance<Element>()
            addBefore(body, if (op.value.toBoolean()) siblings.getOrNull(siblings.indexOf(target) + 1) else target, copy)
            // Word wants a paragraph after a table
            val now = body.elements()!!.filterIsInstance<Element>()
            val next = now.getOrNull(now.indexOf(copy) + 1)
            if (next == null || next.name != "p") addBefore(body, next, newElement(W, "p"))
        }
        fun append(text: String) { text.split('\n').forEach { part ->
            val p = newElement(W, "p"); p.add(textRun(part, null)); addBefore(body, body.firstChild(W, "sectPr"), p)
        } }
        fun image(op: Op): Element {
            val media = pkg.addMedia("word/media", op.image!!.readBytes(), op.image.extension)
            val rel = pkg.addRelationship(part, REL_IMAGE, media.removePrefix("word/"))
            var maxId = 0L
            walk(root) { if (it.name == "docPr" && it.namespaceURI == WP.uRI) maxId = maxOf(maxId, it.attributeValue("id")?.toLongOrNull() ?: 0) }
            val cx = pxToEmu(op.width).toString(); val cy = pxToEmu(op.height).toString()
            val run = newElement(W, "r")
            val inline = run.addElement(QName("drawing", W))!!.addElement(QName("inline", WP))
            inline!!.addElement(QName("extent", WP))!!.addAttribute("cx", cx)!!.addAttribute("cy", cy)
            inline!!.addElement(QName("docPr", WP))!!.addAttribute("id", (maxId + 1).toString())!!.addAttribute("name", "Edit image ${maxId + 1}")
            val pic = inline!!.addElement(QName("graphic", A))!!.addElement(QName("graphicData", A))!!.addAttribute("uri", PIC.uRI)!!.addElement(QName("pic", PIC))
            val nv = pic!!.addElement(QName("nvPicPr", PIC))
            nv!!.addElement(QName("cNvPr", PIC))!!.addAttribute("id", "0")!!.addAttribute("name", op.image.name)
            nv!!.addElement(QName("cNvPicPr", PIC))
            val fill = pic!!.addElement(QName("blipFill", PIC))
            fill!!.addElement(QName("blip", A))!!.addAttribute(QName("embed", R), rel)
            fill!!.addElement(QName("stretch", A))!!.addElement(QName("fillRect", A))
            val sp = pic!!.addElement(QName("spPr", PIC))
            val xfrm = sp!!.addElement(QName("xfrm", A))
            xfrm!!.addElement(QName("off", A))!!.addAttribute("x", "0")!!.addAttribute("y", "0")
            xfrm!!.addElement(QName("ext", A))!!.addAttribute("cx", cx)!!.addAttribute("cy", cy)
            sp!!.addElement(QName("prstGeom", A))!!.addAttribute("prst", "rect")!!.addElement(QName("avLst", A))
            return run
        }
    }
    companion object {
        /** Children of w:rPr in schema order (CT_RPr). */
        private val RPR_ORDER = listOf("rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow",
            "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs",
            "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath")
        private const val AREA_MASK = com.wxiwei.office.constant.wp.WPModelConstant.AREA_MASK
        private const val TEXTBOX = com.wxiwei.office.constant.wp.WPModelConstant.TEXTBOX
        private const val STORY_MASK = AREA_MASK or com.wxiwei.office.constant.wp.WPModelConstant.TEXTBOX_MASK
        internal fun walk(root: Element, visit: (Element) -> Unit) { visit(root); root.elements()!!.filterIsInstance<Element>().forEach { walk(it, visit) } }
        private fun descendantsOf(root: Element): List<Element> = ArrayList<Element>().also { list -> walk(root) { list.add(it) } }
        private fun childText(child: Element): String = when (child.name) {
            "t" -> child.text ?: ""
            "tab", "ptab" -> " "
            "br" -> if (child.attributeValue("type") == "page") "\u000c" else "\u000b"
            "cr" -> "\u000b"
            "noBreakHyphen" -> "-"
            else -> ""
        }
        private fun rawRunText(run: Element) = run.elements()!!.filterIsInstance<Element>().joinToString("") { childText(it) }
        internal fun runText(run: Element): String {
            var text = rawRunText(run)
            if (text.length > 1) text = text.replace('\u000c', '\u000b')
            return Normalizer.normalize(text, Normalizer.Form.NFC)
        }
        private fun addBefore(parent: Element, before: Element?, child: Element) {
            if (before == null) parent.add(child)
            else { val content = parent.content() as MutableList<Any?>; content.add(content.indexOf(before), child) }
        }
        private fun textRun(text: String, style: Element?): Element = newElement(W, "r").apply {
            style?.let { add(it.createCopy()) }
            val buffer = StringBuilder()
            fun flush() { if (buffer.isNotEmpty()) { addElement(QName("t", W))!!.addAttribute(QName("space", Namespace.XML_NAMESPACE), "preserve")!!.text = buffer.toString(); buffer.setLength(0) } }
            text.forEach { c -> when (c) {
                '\t', '\u000b', '\u000c' -> { flush(); addElement(QName(if (c == '\t') "tab" else "br", W)).also { if (c == '\u000c') it!!.addAttribute(QName("type", W), "page") } }
                else -> buffer.append(c)
            } }; flush()
        }
        private fun sliceRun(run: Element, start: Int, end: Int): Element {
            val copy = run.createCopy()!!
            copy.elements()!!.filterIsInstance<Element>().filter { it.name != "rPr" }.forEach { it.detach() }
            var pos = 0
            run.elements()!!.filterIsInstance<Element>().filter { it.name != "rPr" }.forEach { child ->
                val text = childText(child); val a = maxOf(start, pos); val b = minOf(end, pos + text.length)
                if (b > a) copy.add(child.createCopy()!!.apply { if (name == "t") {
                    this.text = text.substring(a - pos, b - pos); addAttribute(QName("space", Namespace.XML_NAMESPACE), "preserve")
                } })
                else if (text.isEmpty()) failUnknownChild(child)
                pos += text.length
            }
            return copy
        }
        private fun failUnknownChild(child: Element) { throw Failure(Reason.MAP_MISMATCH, "Cannot safely split run containing ${child.name}") }
    }
}
