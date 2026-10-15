/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.editor.docsdk

/**
 * A text format for [DocumentEditor.formatText]: each property that is not null is set, the
 * others stay as they are.
 *
 * ```
 * editor.formatText(0, 5, TextFormat(bold = true, color = "C00000", font = "Times New Roman"))
 * ```
 */
data class TextFormat @JvmOverloads constructor(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    /** Size in points. */
    val sizePt: Float? = null,
    /** Color "RRGGBB". */
    val color: String? = null,
    /** Font name, such as "Arial". */
    val font: String? = null,
)
