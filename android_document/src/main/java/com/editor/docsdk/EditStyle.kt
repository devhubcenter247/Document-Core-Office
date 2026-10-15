/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.editor.docsdk

import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.annotation.DrawableRes

/**
 * The look of the SDK's edit bar (a row with Undo, Redo and Save, tabs, and a row of icons under
 * them) and of the caret, selection handles and frames drawn over the document. Its dialogs use
 * [DialogStyle]. Set [customizer] once (for example in `Application.onCreate`) to change it:
 *
 * ```
 * EditStyle.customizer = EditStyle.Customizer { context, style ->
 *     style.copy(accent = brand, selection = brand)
 * }
 * ```
 *
 * To use your own icons, give [icons]. For what colors and sizes cannot do, give an [itemStyler]:
 * it is called on every button of the bar. To build your own bar instead, see [DocumentEditor].
 */
data class EditStyle @JvmOverloads constructor(
    /** Background of the bar. */
    val toolbarBackground: Int = 0xFFFFFFFF.toInt(),
    /** Lines between the parts of the bar. */
    val divider: Int = 0xFFE3E3E3.toInt(),
    /** Icons of the buttons. */
    val icon: Int = 0xFF444746.toInt(),
    /** Icons of the buttons that are on (bold text selected...). */
    val iconActive: Int = 0xFF0B57D0.toInt(),
    /** Behind the buttons that are on. */
    val activeBackground: Int = 0xFFD3E3FD.toInt(),
    /** Icons of the buttons that delete. */
    val deleteIcon: Int = 0xFFB3261E.toInt(),
    /** The selected tab's text and line, and the Save button. */
    val accent: Int = 0xFF0B57D0.toInt(),
    /** Text of the Save button. */
    val onAccent: Int = 0xFFFFFFFF.toInt(),
    /** Text of the tabs not selected. */
    val tabText: Int = 0xFF5F6368.toInt(),
    val tabTextSp: Float = 14f,
    val typeface: Typeface = Typeface.DEFAULT,
    /** Size of the icons, and of the square each button takes. */
    val iconDp: Int = 24,
    val buttonDp: Int = 44,
    /** Status line (what is selected, what to do) and the Excel cell name. */
    val labelText: Int = 0xFF5F6368.toInt(),
    val labelTextSp: Float = 13f,
    /** Text of the Excel formula box; null keeps the color of the theme. */
    val inputText: Int? = null,
    val inputTextSp: Float = 15f,
    /** Hint of the Excel formula box; null keeps the color of the theme. */
    val inputHint: Int? = null,
    /** Underline of the Excel formula box; null keeps the color of the theme. */
    val inputLine: Int? = null,
    /** Caret, selection handles and the frames of pictures and shapes. */
    val selection: Int = 0xFF1A73E8.toInt(),
    /** Inside of the round handles that resize and rotate. */
    val handleFill: Int = 0xFFFFFFFF.toInt(),
    /** Where a dragged picture of a Word document will land. */
    val dropTarget: Int = 0xFFE8453C.toInt(),
    /** Called on every button of the bar once it is made; null: nothing more. */
    val itemStyler: ItemStyler? = null,
    /** The icon of each button, and of the check of the Excel formula box ([EditAction.CELL_VALUE]); null: the SDK's icons. */
    val icons: Icons? = null,
    /** The back button of [DocumentViewer]'s screen, drawn as it is (not tinted); null: the SDK's. */
    @DrawableRes val backIcon: Int? = null,
    /** Called on the Excel formula box once it is made; null: nothing more. */
    val inputStyler: InputStyler? = null,
    /** Background of the format bar over the keyboard (PowerPoint text typed in place); null: [toolbarBackground]. */
    val textBarBackground: Int? = null,
    /** Height of the format bar's buttons; the bar is this plus a 4dp margin above and below. */
    val textBarButtonDp: Int = 36,
    /** Size of the format bar's icons. */
    val textBarIconDp: Int = 20,
    /** Space between the format bar's buttons. */
    val textBarSpacingDp: Int = 6,
) {
    /** The icon of [action]'s button, tinted like the SDK's; null keeps the SDK's icon ([EditAction.icon]). */
    fun interface Icons {
        @DrawableRes
        fun icon(action: EditAction): Int?
    }

    /** Changes the [button] of [action] further (background, size...). Do not change its click listener. */
    fun interface ItemStyler {
        fun style(button: View, action: EditAction)
    }

    /** Changes [style], the default, into the style used for [context]; called each time a bar opens. */
    fun interface Customizer {
        fun customize(context: Context, style: EditStyle): EditStyle
    }

    companion object {
        /** Changes the style of every edit bar; null keeps the defaults. */
        @JvmStatic
        var customizer: Customizer? = null

        /** The style the edit bars of [context] use. */
        @JvmStatic
        fun of(context: Context): EditStyle = EditStyle().let { customizer?.customize(context, it) ?: it }
    }
}
