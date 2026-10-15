/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.editor.docsdk

/**
 * What the user works on now: the context bar shows the commands for it ([DocumentEditor.context],
 * [DocumentEditor.contextActions]).
 */
enum class EditContext {
    /** Word: the caret or selected text. */
    TEXT,
    /** Word: the caret or selection in a table. */
    TABLE,
    /** Word: a selected picture. */
    PICTURE,
    /** Excel: the selected cell or range. */
    CELLS,
    /** PowerPoint: a selected shape, text box, picture or table. */
    SHAPE,
    /** PowerPoint: the text of a shape typed in place. */
    SHAPE_TEXT,
}
