/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:          FontKit.java
 *
 * 编译器:            android2.2
 * 时间:              下午5:12:46
 */
package com.wxiwei.office.simpletext.font

import android.graphics.Paint
import android.graphics.Typeface
import com.wxiwei.office.common.PaintKit
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.ss.model.baseModel.Cell
import com.wxiwei.office.ss.model.baseModel.Workbook
import com.wxiwei.office.ss.model.table.SSTableCellStyle
import java.text.BreakIterator
import java.util.regex.Pattern

/**
 * 布局绘制用到的与字体相关的方法
 *
 * Read版本:        Read V1.0
 *
 * 作者:            ljj8494
 *
 * 日期:            2011-11-8
 *
 * 负责人:          ljj8494
 */
class FontKit {
    // 断词、断行算法
    private val lineBreak: BreakIterator = BreakIterator.getLineInstance()
    private val typefaceCache = HashMap<Int, Typeface>()

    /**
     *
     * @param s
     * @param p
     * @return
     */
    fun getCellPaint(cell: Cell, wb: Workbook?, tableCellStyle: SSTableCellStyle?): Paint {
        //Paint paint = new Paint();
        val paint = PaintKit.instance().getPaint()
        paint.isAntiAlias = true
        val s = cell.getCellStyle()
        val font = wb?.getFont(s!!.getFontIndex().toInt())
        val isbold = font!!.isBold() //getBoldweight() > HSSFFont.BOLDWEIGHT_NORMAL;
        val isitalics = font.isItalic()

        //Strike
        if (font.isStrikeline()) {
            paint.isStrikeThruText = true
        }

        //underline
        if (font.getUnderline() != Font.U_NONE.toInt()) {
            paint.isUnderlineText = true
        }

        // 字符样式
        val fontIndex = s!!.getFontIndex().toInt()
        // the cell font's own family (resolved like Word text), real bold/italic faces
        val name = font.getName()
        paint.typeface = typefaceCache.getOrPut(fontIndex) {
            val manage = FontTypefaceManage.instance()
            if (name.isNullOrEmpty()) Typeface.create(Typeface.SANS_SERIF, (if (isbold) Typeface.BOLD else 0) or (if (isitalics) Typeface.ITALIC else 0))
            else manage.getFontTypeface(manage.addFontName(name), isbold, isitalics)
        }
        // what the device's font cannot draw (some keep their system font upright) is faked
        val fake = if (name.isNullOrEmpty()) (if (isbold && !paint.typeface.isBold) Typeface.BOLD else 0) or (if (isitalics && !paint.typeface.isItalic) Typeface.ITALIC else 0)
            else FontTypefaceManage.instance().let { it.fakeStyle(it.addFontName(name), isbold, isitalics) }
        if (fake and Typeface.BOLD != 0) paint.isFakeBoldText = true
        if (fake and Typeface.ITALIC != 0) paint.textSkewX = FontTypefaceManage.FAKE_ITALIC_SKEW
        // fontsize
        paint.textSize = (font.getFontSize() * MainConstant.POINT_TO_PIXEL + 0.5f).toFloat()
        // color
        var color = wb.getColor(font.getColorIndex())
        if ((color and 0xFFFFFF) == 0 && tableCellStyle != null) {
            color = tableCellStyle.getFontColor()
        }
        paint.setColor(color)

        return paint
    }

    /**
     * 查找换行时的断词点
     *
     * @param text
     * @param startPos
     * @return
     */
    @Synchronized
    fun findBreakOffset(text: String?, pos: Int): Int {
        lineBreak.setText(text)
        /*char ch = text.charAt(pos);
        while (ch == 0x20 || ch == 0x3000)
        {
            pos--;
            if (pos >= 0)
            {
                ch = text.charAt(pos);
            }
        }*/
        // 如果一行只有一个单，则返回布局点位置
        //int newPos = wordBreak.following(pos - 1);
        lineBreak.following(pos)
        val newPos = lineBreak.previous()
        // no break opportunity (one long word): break between characters, not inside an emoji
        return if (newPos == 0) clusterStart(text!!, pos).takeIf { it > 0 } ?: pos else newPos
    }

    /** [i], or the start of the character cluster (surrogate pair, emoji sequence, base + marks) it is inside. */
    fun clusterStart(text: String, i: Int): Int {
        if (i <= 0 || i >= text.length) return i
        val chars = BreakIterator.getCharacterInstance()
        chars.setText(text)
        return if (chars.isBoundary(i)) i else chars.preceding(i)
    }

    /** The end of the character cluster that starts at or contains [i]. */
    fun clusterEnd(text: String, i: Int): Int {
        if (i >= text.length) return text.length
        val chars = BreakIterator.getCharacterInstance()
        chars.setText(text)
        return chars.following(i).takeIf { it != BreakIterator.DONE } ?: text.length
    }

    fun breakText(content: String, lineWidth: Int, paint: Paint): List<String> {
        // Java String.split 语义（去掉末尾空串）
        val words = Pattern.compile("\\n").split(content)
        val textList: MutableList<String> = ArrayList()

        var index = 0
        var item: List<String>
        while (index < words.size) {
            item = wrapText(words[index], lineWidth, paint)
            val iter = item.iterator()
            while (iter.hasNext()) {
                textList.add(iter.next())
            }
            index++
        }

        return textList
    }

    /**
     * ignore char differences between word
     * @param content
     * @param lineWidth
     * @param paint
     * @return
     */
    fun wrapText(content: String, lineWidth: Int, paint: Paint): List<String> {
        var item = ""
        val restContent = content.substring(0)
        // Java String.split 语义（去掉末尾空串）
        val words: Array<String?> = Pattern.compile(" ").split(restContent)
        val textList: MutableList<String> = ArrayList()

        var wordIndex = 0
        var charIndex = 0
        var chars: CharArray
        while (wordIndex < words.size) {
            if (words[wordIndex]!!.length == 0) {
                words[wordIndex] = " "
            }
            wordIndex++
        }

        wordIndex = 0
        while (wordIndex < words.size) {
            //one word width larger than linewidth
            while (paint.measureText(words[wordIndex]) > lineWidth) {
                chars = words[wordIndex]!!.toCharArray()
                charIndex = chars.size
                item = words[wordIndex]!!.substring(0, charIndex)
                while (charIndex > 0 && paint.measureText(item) > lineWidth) {
                    charIndex--
                    item = words[wordIndex]!!.substring(0, charIndex)
                }
                textList.add(item)
                words[wordIndex] = words[wordIndex]!!.substring(charIndex, words[wordIndex]!!.length)
            }

            //made one line
            item = ""
            while (wordIndex < words.size && paint.measureText(item + words[wordIndex]) <= lineWidth) {
                item += words[wordIndex] + " "
                wordIndex++
            }
            textList.add(item.substring(0, item.length - 1))
        }

        disposeString(words)
        return textList
    }

    /**
     *
     * @param stringArray
     */
    private fun disposeString(stringArray: Array<String?>) {
        var index = 0
        while (index < stringArray.size) {
            stringArray[index] = null
            index++
        }
    }

    companion object {
        //
        private val fontKit = FontKit()

        /**
         *
         * @return
         */
        @JvmStatic
        fun instance(): FontKit {
            return fontKit
        }
    }
}
