/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.editor.docsdk

import androidx.annotation.MainThread
import com.wxiwei.office.editor.ui.OfficeEditPanel
import com.wxiwei.office.editor.ui.SlideEditPanel

/**
 * Edits the document of a [DocumentView], from [DocumentView.startEditing]. With the SDK's bar
 * (the default) there is nothing more to do. Without it, put your own buttons where you want and
 * run the commands you choose:
 *
 * ```
 * val editor = documentView.startEditing(EditFeature.all(), showToolbar = false) ?: return
 * boldButton.isVisible = editor.isAvailable(EditAction.BOLD)
 * boldButton.setOnClickListener { editor.run(EditAction.BOLD) }
 * colorButton.setOnClickListener { editor.run(EditAction.TEXT_COLOR, "C00000") }
 * editor.listener = object : DocumentEditor.Listener {
 *     override fun onStateChanged() { boldButton.isSelected = editor.isActive(EditAction.BOLD) }
 * }
 * ```
 *
 * Taps and long presses on the document (typing in Word, selecting cells and shapes, dragging
 * pictures) work either way. Everything is called on the main thread.
 *
 * Every question a command asks, the big dialogs too (Paragraph, Find & replace, the shape list,
 * animations...), can be your own UI: see [dialogs] and [EditRequest].
 *
 * [panel] is the edit UI itself (`com.wxiwei.office.editor.ui`), for everything else: its tabs,
 * Excel's formula box ([OfficeEditPanel.header]) or the selected cell
 * ([com.wxiwei.office.editor.ui.ExcelEditPanel.selectedRange], `cellInput`), or the SDK's bar
 * placed where you want (`EditToolbar(panel).view`). With your own bar, set
 * [OfficeEditPanel.toolbar] to it so the caret and the text being edited stay above it; the SDK
 * lifts its parent above the keyboard, or set [OfficeEditPanel.liftToolbar] to false and move it
 * yourself (IME insets).
 */
class DocumentEditor internal constructor(val panel: OfficeEditPanel) {

    interface Listener {
        /** What is selected, or the state of a command, may have changed: refresh your buttons. */
        fun onStateChanged() {}

        /** [status] says what is selected or what to do next, like "Tap the text to type". */
        fun onStatusChanged(status: String) {}
    }

    var listener: Listener? = null

    init {
        panel.addStateListener { listener?.onStateChanged() }
        panel.addStatusListener { listener?.onStatusChanged(it) }
    }

    /** Your own dialogs for the questions the commands ask; null: the SDK's. */
    var dialogs: EditDialogs?
        get() = panel.dialogHandler
        set(value) {
            panel.dialogHandler = value
        }

    /** Type of the document edited. */
    val documentType: DocumentType
        get() = when (panel.file.extension.lowercase()) {
            "docx" -> DocumentType.WORD
            "pptx" -> DocumentType.POWERPOINT
            else -> DocumentType.EXCEL
        }

    /** The commands of this document that the [EditFeature]s allow, in the order of the SDK's bar. */
    val actions: List<EditAction> get() = panel.actions()

    /** Whether [action] can run on this document. */
    fun isAvailable(action: EditAction): Boolean = panel.isAvailable(action)

    /**
     * Whether [action] can run now: false for Undo or Redo with nothing to undo or redo, Copy and
     * Cut with nothing selected, Paste with nothing on the clipboard, and for every command while
     * the document reopens after an edit. Grey your button out when false.
     */
    fun isEnabled(action: EditAction): Boolean = panel.isEnabled(action)

    /** Whether [action] is on where the caret or selection is (bold text, wrapped cell...). */
    fun isActive(action: EditAction): Boolean = panel.isActive(action)

    /** What is selected or what to do next, as a short sentence. */
    val status: String get() = panel.status

    /** What the user works on now (text, a table, cells, a shape...), or null when nothing is selected. */
    val context: EditContext? get() = panel.editContext

    /**
     * The commands for what is selected now, as the context bar shows them; empty when nothing is.
     * An app with its own bar shows these (refreshed in [Listener.onStateChanged]).
     */
    fun contextActions(): List<EditAction> = panel.contextActions()

    /**
     * Shows the SDK's context bar (true by default): the commands for what is selected (copy and
     * paste, a table's rows and columns, the format of the text...), over the keyboard or the SDK's
     * bar. Its look follows [EditStyle] (textBar* sizes). False: build your own from [context] and
     * [contextActions].
     */
    var contextBar: Boolean
        get() = panel.contextBar
        set(value) { panel.contextBar = value }

    /** Changes the commands of a context: given it and the SDK's commands, returns those to show (empty hides the bar there). */
    fun interface ContextCommands {
        fun commands(context: EditContext, defaults: List<EditAction>): List<EditAction>
    }

    /** The app's choice of commands for each context; null: the SDK's. */
    var contextCommands: ContextCommands? = null
        set(value) {
            field = value
            panel.contextCommands = value?.let { c -> { context, defaults -> c.commands(context, defaults) } }
        }

    /**
     * Runs [action]. When it needs a value it asks the user (with [dialogs], or the SDK's dialog).
     * Returns false when [action] is not available.
     */
    @MainThread
    fun run(action: EditAction): Boolean = panel.run(action, null, false)

    /** Runs [action] with [value] instead of asking the user; see [EditAction] for the value of each. */
    @MainThread
    fun run(action: EditAction, value: Any?): Boolean = panel.run(action, value, true)

    /**
     * PowerPoint: the text of the selected text box, as being typed while it is edited in place;
     * paragraphs and line breaks are one "\n" each. Null when no shape is selected or in Word and Excel.
     * Positions in it are those of [textSelection] and [formatText].
     */
    val selectedText: String? get() = (panel as? SlideEditPanel)?.selectedText()

    /**
     * PowerPoint: the part of [selectedText] the user selected while typing in the text box (end
     * exclusive), or null. Format commands such as [EditAction.BOLD] or [EditAction.FONT] apply to it.
     */
    val textSelection: IntRange? get() = (panel as? SlideEditPanel)?.textSelection()

    /**
     * PowerPoint: formats chars [start, end) of the selected text box's text (positions as in
     * [selectedText]) with [format], shown at once and undone with [EditAction.UNDO]. While the box is
     * edited in place, the editor stays open with the same part selected. Returns false when no
     * text box is selected, the range is outside its text, a value is invalid, or in Word and Excel.
     */
    @MainThread
    fun formatText(start: Int, end: Int, format: TextFormat): Boolean {
        val slides = panel as? SlideEditPanel ?: return false
        return slides.formatText(start, end, com.wxiwei.office.editor.pptx.TextFormat(
            bold = format.bold, italic = format.italic, underline = format.underline,
            sizePt = format.sizePt, rgbHex = format.color?.removePrefix("#"), font = format.font))
    }

    /**
     * PowerPoint: shows the SDK's format bar over the keyboard while text is typed in a shape (true
     * by default). Set it to false to use your own buttons: [Listener.onStateChanged] is called as
     * the selection moves; read [textSelection] and [isActive], then run the format commands or
     * [formatText]. Takes effect the next time a shape's text is edited.
     */
    var textFormatBar: Boolean
        get() = (panel as? SlideEditPanel)?.textBar ?: false
        set(value) { (panel as? SlideEditPanel)?.textBar = value }

    /**
     * PowerPoint: the buttons of that bar, in order (hide one by leaving it out). [EditAction.FONT]
     * shows the font's name, [EditAction.FONT_SIZE] shows − size +. Colors and icons follow
     * [EditStyle] like the SDK's other bars.
     */
    var textFormatBarActions: List<EditAction>
        get() = (panel as? SlideEditPanel)?.textBarActions ?: emptyList()
        set(value) { (panel as? SlideEditPanel)?.textBarActions = value }

    /** True when there are edits not saved yet. */
    fun hasUnsavedChanges(): Boolean = panel.hasChanges()

    /** Writes the edits to the document; false when it could not (the user is told why). */
    @MainThread
    fun save(): Boolean = panel.save()
}
