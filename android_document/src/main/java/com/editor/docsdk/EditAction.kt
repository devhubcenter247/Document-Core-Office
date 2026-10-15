/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.editor.docsdk

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.wxiwei.office.R

/**
 * Every command of the Word, Excel and PowerPoint editors. Build your own buttons for the ones you
 * want and call [DocumentEditor.run]; [DocumentEditor.actions] lists those of the open document.
 * [icon] and [label] are the SDK's own icon (24 dp, white: tint it) and name, free to use.
 *
 * Commands that need a value (a color, a size...) ask the user with a dialog (see [EditDialogs]),
 * or take it from [DocumentEditor.run] with a value; the value of each is written next to it.
 */
enum class EditAction(@DrawableRes val icon: Int, @StringRes val label: Int) {
    // ---- every format ----
    UNDO(R.drawable.docsdk_ic_undo, R.string.docsdk_edit_undo),
    REDO(R.drawable.docsdk_ic_redo, R.string.docsdk_edit_redo),
    SAVE(R.drawable.docsdk_ic_save, R.string.docsdk_edit_save),
    /** The user picks where (system file picker). */
    SAVE_COPY(R.drawable.docsdk_ic_save_as, R.string.docsdk_edit_save_copy),

    // ---- text ----
    BOLD(R.drawable.docsdk_ic_format_bold, R.string.docsdk_edit_bold),
    ITALIC(R.drawable.docsdk_ic_format_italic, R.string.docsdk_edit_italic),
    UNDERLINE(R.drawable.docsdk_ic_format_underlined, R.string.docsdk_edit_underline),
    STRIKETHROUGH(R.drawable.docsdk_ic_format_strikethrough, R.string.docsdk_edit_strikethrough),
    SUPERSCRIPT(R.drawable.docsdk_ic_superscript, R.string.docsdk_edit_superscript),
    SUBSCRIPT(R.drawable.docsdk_ic_subscript, R.string.docsdk_edit_subscript),
    /** Value: color "RRGGBB". */
    TEXT_COLOR(R.drawable.docsdk_ic_format_color_text, R.string.docsdk_edit_text_color),
    /** Word. Value: color "RRGGBB", or null for none. */
    HIGHLIGHT(R.drawable.docsdk_ic_format_ink_highlighter, R.string.docsdk_edit_highlight),
    /** Excel. Value: color "RRGGBB", or null for none. */
    FILL_COLOR(R.drawable.docsdk_ic_format_color_fill, R.string.docsdk_edit_fill_color),
    /**
     * Word and PowerPoint. Value: font name. In PowerPoint, like the other text formats, it applies to
     * the part selected while typing in a text box, or else to all its text.
     */
    FONT(R.drawable.docsdk_ic_font_download, R.string.docsdk_edit_font),
    /** Value: size in points (Number). */
    FONT_SIZE(R.drawable.docsdk_ic_format_size, R.string.docsdk_edit_font_size),
    ALIGN_LEFT(R.drawable.docsdk_ic_format_align_left, R.string.docsdk_edit_align_left),
    ALIGN_CENTER(R.drawable.docsdk_ic_format_align_center, R.string.docsdk_edit_align_center),
    ALIGN_RIGHT(R.drawable.docsdk_ic_format_align_right, R.string.docsdk_edit_align_right),
    /** Word. */
    ALIGN_JUSTIFY(R.drawable.docsdk_ic_format_align_justify, R.string.docsdk_edit_justify),

    // ---- Word: paragraphs ----
    BULLETS(R.drawable.docsdk_ic_format_list_bulleted, R.string.docsdk_edit_bullets),
    NUMBERING(R.drawable.docsdk_ic_format_list_numbered, R.string.docsdk_edit_numbering),
    INDENT_MORE(R.drawable.docsdk_ic_format_indent_increase, R.string.docsdk_edit_indent_more),
    INDENT_LESS(R.drawable.docsdk_ic_format_indent_decrease, R.string.docsdk_edit_indent_less),
    /** The Paragraph dialog (alignment, indents, spacing). Value: [ParagraphFormat]. */
    PARAGRAPH(R.drawable.docsdk_ic_format_paragraph, R.string.docsdk_edit_paragraph_more),
    /** The Line spacing dialog. Value: [LineSpacingFormat]. */
    LINE_SPACING(R.drawable.docsdk_ic_format_line_spacing, R.string.docsdk_edit_line_spacing),

    // ---- Word: selection and clipboard ----
    SELECT_WORD(R.drawable.docsdk_ic_text_select_start, R.string.docsdk_edit_select_word),
    SELECT_PARAGRAPH(R.drawable.docsdk_ic_segment, R.string.docsdk_edit_select_paragraph),
    /** The whole table at the caret: then COPY copies the table, and PASTE puts it back as a table. */
    SELECT_TABLE(R.drawable.docsdk_ic_table, R.string.docsdk_edit_select_table),
    SELECT_ALL(R.drawable.docsdk_ic_select_all, R.string.docsdk_edit_select_all),
    DESELECT(R.drawable.docsdk_ic_deselect, R.string.docsdk_edit_deselect),
    COPY(R.drawable.docsdk_ic_content_copy, R.string.docsdk_edit_copy),
    CUT(R.drawable.docsdk_ic_content_cut, R.string.docsdk_edit_cut),
    PASTE(R.drawable.docsdk_ic_content_paste, R.string.docsdk_edit_paste),
    /** Word: the selected text. PowerPoint: the selected shape. */
    DELETE(R.drawable.docsdk_ic_delete, R.string.docsdk_edit_delete),
    NEW_LINE(R.drawable.docsdk_ic_keyboard_return, R.string.docsdk_edit_new_line),
    /** Word: at the selection. Value: text. */
    INSERT_TEXT(R.drawable.docsdk_ic_text_fields, R.string.docsdk_edit_insert_text),
    /** Word: the selected text. Value: text. */
    REPLACE_TEXT(R.drawable.docsdk_ic_text_fields, R.string.docsdk_edit_replace_text),
    /** Find and replace; your own dialog gets an [EditRequest.FindReplace] to search with. */
    FIND_REPLACE(R.drawable.docsdk_ic_find_replace, R.string.docsdk_edit_find_replace),

    // ---- insert ----
    /** Value: android.net.Uri or java.io.File of a picture. */
    INSERT_PICTURE(R.drawable.docsdk_ic_add_photo_alternate, R.string.docsdk_edit_add_picture),
    /** Word. Value: [TableSize]. */
    INSERT_TABLE(R.drawable.docsdk_ic_table, R.string.docsdk_edit_add_table),

    // ---- rows and columns (Word tables, Excel sheets) ----
    ROW_ABOVE(R.drawable.docsdk_ic_add_row_above, R.string.docsdk_edit_row_add_above),
    ROW_BELOW(R.drawable.docsdk_ic_add_row_below, R.string.docsdk_edit_row_add_below),
    COLUMN_LEFT(R.drawable.docsdk_ic_add_column_left, R.string.docsdk_edit_column_add_left),
    COLUMN_RIGHT(R.drawable.docsdk_ic_add_column_right, R.string.docsdk_edit_column_add_right),
    DELETE_ROW(R.drawable.docsdk_ic_playlist_remove, R.string.docsdk_edit_row_delete),
    DELETE_COLUMN(R.drawable.docsdk_ic_delete_column, R.string.docsdk_edit_column_delete),

    // ---- Excel ----
    /** The selected cell. Value: text, number or "=formula". */
    CELL_VALUE(R.drawable.docsdk_ic_function, R.string.docsdk_edit_cell_value),
    /** Value: a cell or range, like "B3" or "A1:C10". */
    GO_TO_CELL(R.drawable.docsdk_ic_location_searching, R.string.docsdk_edit_go_to_cells),
    /** The Alignment dialog (horizontal, vertical, indent, wrap). Value: [CellAlignmentFormat]. */
    CELL_ALIGNMENT(R.drawable.docsdk_ic_tune, R.string.docsdk_edit_alignment_more),
    WRAP_TEXT(R.drawable.docsdk_ic_wrap_text, R.string.docsdk_edit_wrap_text),
    /** Value: degrees 0, 45, 90, 135, 180, or 255 (letters stacked). */
    TEXT_ROTATION(R.drawable.docsdk_ic_text_rotation_angleup, R.string.docsdk_edit_text_rotation),
    BORDERS(R.drawable.docsdk_ic_border_all, R.string.docsdk_edit_borders),
    /** Value: an Excel number format, like "0.00" or "d/m/yyyy". */
    NUMBER_FORMAT(R.drawable.docsdk_ic_decimal_increase, R.string.docsdk_edit_number_format),
    /** Merges the selected range, or splits a merged cell. Value: true to merge without asking when values would be dropped. */
    MERGE_CELLS(R.drawable.docsdk_ic_cell_merge, R.string.docsdk_edit_merge_cells),
    /** The selected picture, or else the value of the cell. */
    CLEAR_CELLS(R.drawable.docsdk_ic_ink_eraser, R.string.docsdk_edit_clear_cells),
    /** Value: width in characters (Number). */
    COLUMN_WIDTH(R.drawable.docsdk_ic_width, R.string.docsdk_edit_column_width),
    COLUMN_NARROWER(R.drawable.docsdk_ic_column_narrower, R.string.docsdk_edit_column_narrower),
    COLUMN_WIDER(R.drawable.docsdk_ic_column_wider, R.string.docsdk_edit_column_wider),
    /** Value: height in points (Number). */
    ROW_HEIGHT(R.drawable.docsdk_ic_height, R.string.docsdk_edit_row_height),
    ROW_SHORTER(R.drawable.docsdk_ic_unfold_less, R.string.docsdk_edit_row_shorter),
    ROW_TALLER(R.drawable.docsdk_ic_unfold_more, R.string.docsdk_edit_row_taller),
    /** Value: the new sheet's name. */
    ADD_SHEET(R.drawable.docsdk_ic_add_box, R.string.docsdk_edit_add_sheet),

    // ---- PowerPoint ----
    /** The shapes of the slide: select one, change its layer ([EditRequest.Shapes]). */
    SHAPE_LIST(R.drawable.docsdk_ic_layers, R.string.docsdk_edit_shape_list),
    /** The text of the selected shape. Value: text. */
    SET_TEXT(R.drawable.docsdk_ic_edit_note, R.string.docsdk_edit_set_text),
    /** Value: its text. */
    ADD_TEXT_BOX(R.drawable.docsdk_ic_text_ad, R.string.docsdk_edit_add_text_box),
    ADD_SHAPE(R.drawable.docsdk_ic_shapes, R.string.docsdk_edit_add_shape),
    MOVE_LEFT(R.drawable.docsdk_ic_arrow_back, R.string.docsdk_edit_move_left),
    MOVE_RIGHT(R.drawable.docsdk_ic_arrow_forward, R.string.docsdk_edit_move_right),
    MOVE_UP(R.drawable.docsdk_ic_arrow_upward, R.string.docsdk_edit_move_up),
    MOVE_DOWN(R.drawable.docsdk_ic_arrow_downward, R.string.docsdk_edit_move_down),
    ROTATE(R.drawable.docsdk_ic_rotate_right, R.string.docsdk_edit_rotate),
    /** The animations of the slide ([EditRequest.Animations]). */
    ANIMATIONS(R.drawable.docsdk_ic_animation, R.string.docsdk_edit_animations),
    /** Value: [TransitionFormat]. */
    TRANSITION(R.drawable.docsdk_ic_transition_slide, R.string.docsdk_edit_transition),
    ADD_SLIDE(R.drawable.docsdk_ic_library_add, R.string.docsdk_edit_add_slide),
    DUPLICATE_SLIDE(R.drawable.docsdk_ic_control_point_duplicate, R.string.docsdk_edit_duplicate_slide),
    SLIDE_UP(R.drawable.docsdk_ic_move_up, R.string.docsdk_edit_slide_up),
    SLIDE_DOWN(R.drawable.docsdk_ic_move_down, R.string.docsdk_edit_slide_down),
    DELETE_SLIDE(R.drawable.docsdk_ic_delete_sweep, R.string.docsdk_edit_delete_slide),
    EXPORT_PNG(R.drawable.docsdk_ic_image, R.string.docsdk_edit_export_png),
    EXPORT_PDF(R.drawable.docsdk_ic_picture_as_pdf, R.string.docsdk_edit_export_pdf),
}

/** Size of a new table ([EditAction.INSERT_TABLE]). */
data class TableSize(val rows: Int, val columns: Int)

/** A Word paragraph's layout ([EditAction.PARAGRAPH]). */
data class ParagraphFormat(
    /** "left", "center", "right" or "both" (justified). */
    val alignment: String,
    val leftIndentCm: Float,
    val rightIndentCm: Float,
    /** The first line's indent; negative for a hanging indent. */
    val firstLineCm: Float,
    val spaceBeforePt: Float,
    val spaceAfterPt: Float,
)

/** Line spacing of Word paragraphs ([EditAction.LINE_SPACING]), and the space before and after them. */
data class LineSpacingFormat(
    val rule: Rule,
    /** Lines (1.0, 1.15...) for [Rule.MULTIPLE]; points for the others. */
    val value: Float,
    val spaceBeforePt: Float,
    val spaceAfterPt: Float,
) {
    enum class Rule { MULTIPLE, EXACTLY, AT_LEAST }
}

/** Alignment of Excel cells ([EditAction.CELL_ALIGNMENT]). */
data class CellAlignmentFormat(
    /** "general", "left", "center", "right", "fill", "justify", "centerContinuous" or "distributed". */
    val horizontal: String,
    /** "top", "center", "bottom", "justify" or "distributed". */
    val vertical: String,
    /** 0 to 15. */
    val indent: Int,
    val wrap: Boolean,
)

/** How a slide comes in during the slideshow ([EditAction.TRANSITION]). */
data class TransitionFormat(
    /** "none", "fade", "push", "wipe", "cover", "pull", "split", "zoom" or "cut". */
    val type: String,
    /** "l", "r", "u" or "d" (push, wipe, cover, pull). */
    val direction: String? = null,
    val durationMs: Int = 750,
    /** Moves on by itself after this long; null: on a tap only. */
    val advanceAfterMs: Int? = null,
    /** Every slide gets it, not only the one shown. */
    val allSlides: Boolean = false,
)
