package com.wxiwei.office.editor.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.editor.docsdk.EditAction
import com.editor.docsdk.EditContext
import com.editor.docsdk.EditDialogs
import com.editor.docsdk.EditFeature
import com.editor.docsdk.EditRequest
import com.editor.docsdk.EditStyle
import com.editor.docsdk.TableSize
import com.wxiwei.office.R
import com.wxiwei.office.editor.EditResult
import com.wxiwei.office.reader.OfficeDocumentView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Edits the open document in place: the commands ([EditAction]) of one format, the gestures on the
 * document and the views drawn over it. It has no bar of its own: [EditToolbar] makes the SDK's
 * one, or an app runs the commands from its own buttons. Call [close] when editing ends.
 */
abstract class OfficeEditPanel(
    protected val activity: AppCompatActivity,
    protected val reader: OfficeDocumentView,
    val file: File,
    /** What the user may change; the other commands and gestures are off. */
    protected val features: Set<EditFeature> = EditFeature.all(),
) {
    /** A tab of the SDK's bar and its commands, in order. */
    class Tab(@StringRes val title: Int, val actions: List<EditAction>)

    /** The tabs of the SDK's bar; commands not available are left out, and tabs left empty. */
    abstract val tabs: List<Tab>

    /** Shown by the SDK's bar above its tabs (Excel's formula box), or null. */
    open val header: View? = null

    /**
     * The bar at the bottom of the document: the SDK's, or the app's own set here. The caret and the
     * text being edited are kept above it (above the keyboard when there is none shown).
     */
    var toolbar: View? = null

    /**
     * True: the SDK lifts [toolbar]'s parent above the soft keyboard and lets the document scroll
     * out from under it. False: the app's layout does it (IME insets); the SDK only follows where
     * [toolbar] and the keyboard are.
     */
    var liftToolbar: Boolean = true

    private class Entry(val needs: Array<out EditFeature>, val run: () -> Unit)
    private val entries = LinkedHashMap<EditAction, Entry>()

    /** Registers what [action] does; it is available while every feature it [needs] is on. */
    protected fun action(action: EditAction, vararg needs: EditFeature, run: () -> Unit) {
        entries[action] = Entry(needs, run)
    }

    fun isAvailable(action: EditAction): Boolean =
        entries[action]?.let { e -> e.needs.all { has(it) } } == true

    /**
     * Whether [action] can run now: available, the document not reopening, and for Undo and Redo
     * something to undo or redo.
     */
    fun isEnabled(action: EditAction): Boolean = isAvailable(action) && !isBusy && when (action) {
        EditAction.UNDO -> canUndo()
        EditAction.REDO -> canRedo()
        else -> canRun(action)
    }

    protected open fun canUndo(): Boolean = true
    protected open fun canRedo(): Boolean = true

    /** Whether [action] has something to act on now (Copy needs a selection, Paste a clipboard). */
    protected open fun canRun(action: EditAction): Boolean = true

    /** Something on the clipboard to paste. */
    protected val canPaste: Boolean
        get() = (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).hasPrimaryClip()

    /** The available commands, in the order they were registered. */
    fun actions(): List<EditAction> = entries.keys.filter { isAvailable(it) }

    /** Whether [action] is on at the caret or selection (bold...); false when it has no such state. */
    open fun isActive(action: EditAction): Boolean = false

    // the command running and the value it was given: its first question takes the value
    protected var running: EditAction? = null
        private set
    private var preset: Any? = null
    private var hasPreset = false

    /** Runs [action], with [value] when [withValue]; false when it is not available or the document is reopening. */
    fun run(action: EditAction, value: Any?, withValue: Boolean): Boolean {
        // a command would act on the document being replaced (a slide index read from its reset page)
        if (isBusy) return false
        val entry = entries[action]?.takeIf { isAvailable(action) } ?: return false
        running = action
        preset = value
        hasPreset = withValue
        try {
            entry.run()
        } finally {
            running = null
            preset = null
            hasPreset = false
        }
        stateChanged()
        return true
    }

    /** The value given to the running command, once; (false, null) when there is none. */
    protected fun takePreset(): Pair<Boolean, Any?> {
        val given = hasPreset to preset
        hasPreset = false
        preset = null
        return given
    }

    /** The app's own dialogs; null: the SDK's. */
    var dialogHandler: EditDialogs? = null

    /** True when the app handled [request] itself. */
    protected fun appAnswers(request: EditRequest): Boolean = dialogHandler?.ask(request) == true

    private val stateListeners = ArrayList<() -> Unit>()
    private val statusListeners = ArrayList<(String) -> Unit>()

    fun addStateListener(listener: () -> Unit) { stateListeners.add(listener) }
    fun addStatusListener(listener: (String) -> Unit) { statusListeners.add(listener) }

    /** What is selected may have changed: buttons that show a state are refreshed. */
    protected fun stateChanged() {
        refreshContextBar()
        stateListeners.forEach { it() }
    }

    // ---- the context bar: the commands for what is selected, over the keyboard or the SDK's bar ----

    /** What the user works on now, or null when nothing is selected. */
    open val editContext: EditContext? get() = null

    /** The SDK's commands for [context], in the order of the bar. */
    protected open fun defaultActions(context: EditContext): List<EditAction> = emptyList()

    /** Font, size and color of the selected text, for the bar's FONT, FONT_SIZE and TEXT_COLOR. */
    internal open fun barState(): ContextBar.State = ContextBar.State()

    /** Shows the context bar (true by default); false: the app builds its own from [contextActions]. */
    var contextBar: Boolean = true
        set(value) { field = value; refreshContextBar() }

    /**
     * Changes the commands of a context: given the context and the SDK's commands, returns those to
     * show (an empty list hides the bar there).
     */
    var contextCommands: ((EditContext, List<EditAction>) -> List<EditAction>)? = null
        set(value) { field = value; refreshContextBar() }

    /** The commands for what is selected now (available ones only), empty when nothing is. */
    fun contextActions(): List<EditAction> {
        val c = editContext ?: return emptyList()
        val defaults = defaultActions(c)
        return (contextCommands?.invoke(c, defaults) ?: defaults).filter { isAvailable(it) }.distinct()
    }

    private var bar: ContextBar? = null
    private val placeBar = android.view.ViewTreeObserver.OnPreDrawListener { positionBar(); true }

    /** Shows, rebuilds or takes down the bar for what is selected, and lights its buttons. */
    protected fun refreshContextBar() {
        if (!started) return
        val actions = if (contextBar && !closed) contextActions() else emptyList()
        var b = bar
        if (b != null && b.actions != actions) {
            removeOverlay(b)
            if (reader.viewTreeObserver.isAlive) reader.viewTreeObserver.removeOnPreDrawListener(placeBar)
            bar = null
            b = null
        }
        if (actions.isEmpty()) return
        if (b == null) {
            b = ContextBar(context, editStyle, actions) { action, value -> if (value != null) run(action, value, true) else run(action, null, false) }
            bar = b
            addOverlay(b, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(editStyle.textBarButtonDp + 8)))
            reader.viewTreeObserver.addOnPreDrawListener(placeBar)
            positionBar()
        }
        b.show({ isActive(it) }, { isEnabled(it) }, barState())
    }

    /** The bar sits right over the keyboard or the SDK's bar. */
    private fun positionBar() {
        val b = bar ?: return
        val lp = b.layoutParams as android.widget.FrameLayout.LayoutParams
        val top = (areaBottom(reader) - lp.height).coerceAtLeast(0)
        if (lp.topMargin != top) { lp.topMargin = top; b.layoutParams = lp }
    }

    private var closed = false
    // a format's panel is made after this class: the bar waits for it
    private var started = false
    init { reader.post { started = true; refreshContextBar() } }

    /** What is selected or what to do next, shown in the bar's status line. */
    var status: String = ""
        protected set(value) {
            if (field == value) return
            field = value
            statusListeners.forEach { it(value) }
            // a new status comes with a new selection or caret
            stateChanged()
        }

    /** True when there are edits not written to the file yet. */
    abstract fun hasChanges(): Boolean

    /** Writes the document with every edit to [target]. */
    protected abstract fun writeTo(target: File): EditResult

    /** The file now holds every edit: start over on it. */
    protected abstract fun onSaved()

    /** Saves the edits over the file; true when there is nothing left to save. */
    fun save(): Boolean {
        val result = quietly { if (!hasChanges()) null else saveOver(file) { target -> writeTo(target) } }
        if (result == null) {
            toast(str(R.string.docsdk_edit_no_changes))
            return true
        }
        if (result !is EditResult.Ok) {
            report(result, "")
            return false
        }
        // other apps and the file list see the new size and date
        android.media.MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), null, null)
        EditDrafts.delete(context, file)
        quietly { onSaved() }
        val done = afterSave?.invoke(file) ?: true
        // the one message of a save, once everything is done
        if (done) report(result, str(R.string.docsdk_edit_saved_file, file.name))
        return done
    }

    /** Called with the file once Save wrote it; false when what comes next (a copy back...) failed. */
    var afterSave: ((File) -> Boolean)? = null

    /**
     * Writes the document with every edit to a place the user picks (Downloads, Drive...), keeping
     * the open file as it is.
     */
    fun saveCopy() {
        val mime = when (file.extension.lowercase()) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
        launcher = activity.activityResultRegistry.register("office-save-copy-" + System.nanoTime(),
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(mime)) { uri ->
            launcher?.unregister()
            if (uri == null) return@register
            val tmp = File(context.cacheDir, "save-copy." + file.extension).apply { delete() }
            val result = try { writeTo(tmp) } catch (e: Exception) { EditResult.Error(com.wxiwei.office.editor.Reason.IO, e.message ?: "Save failed", e) }
            if (result is EditResult.Ok) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    toast(str(R.string.docsdk_edit_copy_saved))
                } catch (e: Exception) {
                    toast(str(R.string.docsdk_edit_save_failed, e.message ?: ""))
                }
            } else report(result, "")
            tmp.delete()
        }
        launcher.launch(str(R.string.docsdk_edit_copy_file_name, file.nameWithoutExtension, file.extension))
    }

    /**
     * Asks for a color: Office's theme colors with their tints and shades, the standard colors, the
     * last ones used, or a #RRGGBB code; [none] adds a "no color" choice (null). Hex without "#".
     */
    protected fun pickColor(title: String, none: String? = null, onPick: (String?) -> Unit) {
        val (given, value) = takePreset()
        if (given) {
            val hex = (value as? String)?.removePrefix("#")?.uppercase()
            if (hex == null && none != null) return onPick(null)
            if (hex != null && hex.matches(Regex("[0-9A-F]{6}"))) return onPick(hex)
            return toast(str(R.string.docsdk_edit_color_code_invalid))
        }
        if (appAnswers(EditRequest.Color(running, title, none != null, onPick))) return
        val prefs = context.getSharedPreferences("office_edit", Context.MODE_PRIVATE)
        val recent = prefs.getString("recentColors", "")!!.split(',').filter { it.length == 6 }
        val kit = dialogs
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun choose(c: String) {
            prefs.edit().putString("recentColors", (listOf(c) + recent.filter { it != c }).take(10).joinToString(",")).apply()
            dialog?.dismiss()
            onPick(c)
        }
        fun swatch(c: String) = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat(); setColor(0xFF000000.toInt() or c.toInt(16)); setStroke(dp(1), kit.style.divider)
            }
            layoutParams = android.widget.GridLayout.LayoutParams().apply { width = dp(26); height = dp(26); setMargins(dp(2), dp(2), dp(2), dp(2)) }
            // read out by TalkBack (a plain square has nothing else to say)
            contentDescription = str(R.string.docsdk_edit_color_description, c)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { choose(c) }
        }
        fun grid(colors: List<String>) = android.widget.GridLayout(context).apply { columnCount = 10; colors.forEach { addView(swatch(it)) } }
        dialog = kit.show(title) {
            caption(str(R.string.docsdk_edit_theme_colors))
            // rows: the theme colors, then 80/60/40 % lighter, then 25/50 % darker (like Office)
            val shades = listOf(0f, 0.8f, 0.6f, 0.4f, -0.25f, -0.5f)
            view(grid(shades.flatMap { k -> THEME_COLORS.map { shade(it, k) } }))
            caption(str(R.string.docsdk_edit_standard_colors))
            view(grid(STANDARD_COLORS))
            if (recent.isNotEmpty()) { caption(str(R.string.docsdk_edit_recent_colors)); view(grid(recent)) }
            caption(str(R.string.docsdk_edit_color_code))
            val code = input("#RRGGBB").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
            val preview = View(context)
            // the code field and its preview side by side
            root.removeView(code)
            view(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(code, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(preview, LinearLayout.LayoutParams(dp(32), dp(32)))
            })
            code.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val h = s.toString().trim().removePrefix("#")
                    preview.background = if (h.matches(Regex("(?i)[0-9a-f]{6}"))) GradientDrawable().apply { setColor(0xFF000000.toInt() or h.toInt(16)); setStroke(dp(1), kit.style.divider); cornerRadius = dp(4).toFloat() } else null
                }
            })
            negative()
            positive(str(android.R.string.ok)) {
                val h = code.text.toString().trim().removePrefix("#").uppercase()
                if (h.matches(Regex("[0-9A-F]{6}"))) choose(h) else if (h.isNotEmpty()) toast(str(R.string.docsdk_edit_color_code_invalid))
            }
            if (none != null) neutral(none) { onPick(null) }
        }
    }

    /** [hex] made lighter (k > 0: that share of the way to white) or darker (k < 0: that share less). */
    private fun shade(hex: String, k: Float): String {
        if (k == 0f) return hex
        val c = hex.toInt(16)
        fun ch(v: Int) = (if (k > 0) v + (255 - v) * k else v * (1 + k)).toInt().coerceIn(0, 255)
        return "%02X%02X%02X".format(ch(c shr 16 and 255), ch(c shr 8 and 255), ch(c and 255))
    }

    /** Every dialog of the panel is built by this kit (one look, see [DialogStyle]). */
    protected val dialogs get() = DialogKit(context)

    /** Asks for a font size in points. */
    protected fun pickSize(onPick: (Float) -> Unit) {
        val (given, value) = takePreset()
        if (given) return (value as? Number)?.toFloat()?.takeIf { it > 0f }?.let(onPick) ?: toast(str(R.string.docsdk_edit_invalid_number))
        val sizes = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 40f, 48f, 60f, 72f)
        val title = str(R.string.docsdk_edit_font_size)
        if (appAnswers(EditRequest.FontSize(running, title, sizes, onPick))) return
        dialogs.pick(title, sizes.map { (if (it % 1f == 0f) it.toInt().toString() else it.toString()) + " pt" }) { i -> onPick(sizes[i]) }
    }

    /** Asks for one of [fonts], each shown in itself; [current] is checked. */
    protected fun pickFont(fonts: List<String>, current: String?, onPick: (String) -> Unit) {
        val (given, value) = takePreset()
        if (given) return (value as? String)?.takeIf { it.isNotBlank() }?.let(onPick) ?: toast(str(R.string.docsdk_edit_invalid_value))
        val title = str(R.string.docsdk_edit_font)
        if (appAnswers(EditRequest.Font(running, title, fonts, current, onPick))) return
        dialogs.show(title) {
            items(fonts.map { if (it.equals(current, ignoreCase = true)) "✓ $it" else it }) { i -> onPick(fonts[i]) }
                .forEachIndexed { i, row -> row.typeface = EditFonts.typeface(fonts[i]); row.textSize = 18f }
            negative()
        }
    }

    /**
     * Asks for one of [options] (label to value); a value given to run() must be one of the values.
     * [selected] is the index of the current one, or -1.
     */
    protected fun <T> pickOne(title: String, options: List<Pair<String, T>>, selected: Int = -1, onPick: (T) -> Unit) {
        val (given, value) = takePreset()
        if (given) {
            val match = options.firstOrNull { it.second == value || (it.second is Number && value is Number && (it.second as Number).toDouble() == value.toDouble()) }
            return match?.let { onPick(it.second) } ?: toast(str(R.string.docsdk_edit_invalid_value))
        }
        if (appAnswers(EditRequest.Choice(running, title, options.map { it.first }, selected) { i -> options.getOrNull(i)?.let { onPick(it.second) } })) return
        dialogs.pick(title, options.mapIndexed { i, o -> if (i == selected) "✓ " + o.first else o.first }) { i -> onPick(options[i].second) }
    }

    /** Asks for a text, starting from [initial]. */
    protected fun askText(title: String, hint: String, initial: String, onText: (String) -> Unit) {
        val (given, value) = takePreset()
        if (given) return onText(value?.toString() ?: "")
        if (appAnswers(EditRequest.Text(running, title, hint, initial, onText))) return
        dialogs.show(title) {
            val field = input(hint, initial).apply { selectAll() }
            positive(str(android.R.string.ok)) { onText(field.text.toString()) }
            negative()
        }
    }

    /** Asks for a number, starting from [initial]. */
    protected fun askNumber(title: String, initial: Double, onNumber: (Double) -> Unit) {
        val (given, value) = takePreset()
        if (given) return (value as? Number)?.toDouble()?.let(onNumber) ?: toast(str(R.string.docsdk_edit_invalid_number))
        if (appAnswers(EditRequest.Number(running, title, initial, onNumber))) return
        dialogs.show(title) {
            val field = input("", "%.1f".format(java.util.Locale.ROOT, initial), numeric = true).apply { selectAll() }
            positive(str(android.R.string.ok)) { field.text.toString().replace(',', '.').toDoubleOrNull()?.let(onNumber) ?: toast(str(R.string.docsdk_edit_invalid_number)) }
            negative()
        }
    }

    /** Asks for the rows and columns of a new table. */
    protected fun askTableSize(onSize: (TableSize) -> Unit) {
        val (given, value) = takePreset()
        if (given) return (value as? TableSize)?.let(onSize) ?: toast(str(R.string.docsdk_edit_invalid_value))
        val title = str(R.string.docsdk_edit_insert_table)
        if (appAnswers(EditRequest.Table(running, title, onSize))) return
        dialogs.show(title) {
            caption(str(R.string.docsdk_edit_row_count))
            val rows = input(str(R.string.docsdk_edit_row_count), "3").apply { inputType = InputType.TYPE_CLASS_NUMBER }
            caption(str(R.string.docsdk_edit_column_count))
            val cols = input(str(R.string.docsdk_edit_column_count), "3").apply { inputType = InputType.TYPE_CLASS_NUMBER }
            positive(str(R.string.docsdk_edit_insert)) {
                onSize(TableSize(rows.text.toString().toIntOrNull() ?: 0, cols.text.toString().toIntOrNull() ?: 0))
            }
            negative()
        }
    }

    /** Asks before [onYes]; a value true given to run() goes on at once. */
    protected fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        val (given, value) = takePreset()
        if (given) { if (value == true) onYes(); return }
        if (appAnswers(EditRequest.Confirm(running, title, message, onYes))) return
        dialogs.confirm(title, message, yes) { onYes() }
    }

    /** Asks for a picture of the device; a Uri or File given to run() is used as it is. */
    protected fun pickPicture(onPicture: (Uri) -> Unit) {
        val (given, value) = takePreset()
        if (given) {
            val uri = when (value) { is Uri -> value; is File -> Uri.fromFile(value); else -> null }
            return uri?.let(onPicture) ?: toast(str(R.string.docsdk_edit_picture_unreadable))
        }
        if (appAnswers(EditRequest.Picture(running, str(R.string.docsdk_edit_add_picture), onPicture))) return
        var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
        launcher = activity.activityResultRegistry.register("edit-picture-" + System.nanoTime(),
            androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            launcher?.unregister()
            if (uri != null) onPicture(uri)
        }
        launcher.launch("image/*")
    }

    /** [uri]'s picture copied to the cache (the engines read files), or null when it cannot be read. */
    protected fun pictureFile(uri: Uri, prefix: String): File? {
        val type = context.contentResolver.getType(uri) ?: uri.path.orEmpty()
        val ext = when { type.contains("png") -> "png"; type.contains("gif") -> "gif"; type.contains("bmp") -> "bmp"; else -> "jpeg" }
        val image = File(context.cacheDir, prefix + System.nanoTime() + "." + ext)
        return try {
            context.contentResolver.openInputStream(uri)!!.use { input -> image.outputStream().use { input.copyTo(it) } }
            image
        } catch (e: Exception) {
            toast(str(R.string.docsdk_edit_picture_unreadable))
            null
        }
    }

    /** Keeps the unsaved edits in a draft (the app may be killed in the background). */
    fun saveDraft() {
        if (!hasChanges()) return
        runCatching { EditDrafts.write(context, file) { target -> writeTo(target) } }
    }

    /** True while the viewer reopens a working copy for this panel: the activity keeps the panel. */
    var reopening = false
        private set

    // reopens not finished yet, [then] included
    private var pendingReopens = 0

    /**
     * True from [reopen] until its [then] has finished: the page and page count the viewer reports
     * meanwhile are those of a document half shown, and commands are refused.
     */
    val isBusy: Boolean get() = pendingReopens > 0

    /** Called when a reopen is done: the pages may all have changed (added, removed, reordered). */
    var onReopened: (() -> Unit)? = null

    /**
     * Shows [path] in the viewer (a working copy in the cache, or the saved file) and calls [then]
     * once it is open again, with the new document in reader.control.
     */
    protected fun reopen(path: File, then: suspend () -> Unit) {
        reopening = true
        pendingReopens++
        reader.open(path.absolutePath)
        activity.lifecycleScope.launch {
            try {
                // the viewer leaves Ready while it opens, then comes back to it
                kotlinx.coroutines.withTimeoutOrNull(5_000) { reader.state.first { it.status != com.wxiwei.office.reader.ReaderState.Status.Ready } }
                val state = reader.state.first {
                    it.status == com.wxiwei.office.reader.ReaderState.Status.Ready || it.status == com.wxiwei.office.reader.ReaderState.Status.Failed
                }
                reopening = false
                // the new document's frame was added on top of the caret, handles, selection frame
                overlays.forEach { it.bringToFront() }
                if (state.status == com.wxiwei.office.reader.ReaderState.Status.Ready) then() else toast(str(R.string.docsdk_edit_reopen_failed))
            } finally {
                pendingReopens--
                if (!isBusy) {
                    onReopened?.invoke()
                    stateChanged()
                }
            }
        }
    }

    // views drawn over the document (caret, selection handles, shape frame, in-place editor)
    private val overlays = ArrayList<View>()

    /** Adds [overlay] over the document; it stays over it when the document is reopened. */
    protected fun addOverlay(overlay: View, params: android.view.ViewGroup.LayoutParams =
        android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)) {
        reader.addView(overlay, params)
        overlays.add(overlay)
    }

    protected fun removeOverlay(overlay: View) {
        reader.removeView(overlay)
        overlays.remove(overlay)
    }

    /** The document's view, or null while the viewer (re)opens a document. */
    protected fun docView(): View? = if (reopening) null else runCatching { reader.control?.getView() }.getOrNull()

    /** A new file for a working copy in the cache: edits live there until Save writes the original. */
    protected fun workingCopy(): File =
        File(File(context.cacheDir, "edit-work").apply { mkdirs() }, "work-" + System.nanoTime() + "." + file.extension)

    /** Called when the panel is hidden; stop listening to the document. Call super. */
    open fun close() {
        closed = true
        refreshContextBar()
        keepAboveKeyboard(false)
        autosave.cancel()
    }

    /**
     * Every [autosaveMs] while the bar is open, the unsaved edits go to a draft too: a crash (not
     * only the app going to the background) then loses at most that much; reopening the document
     * offers the draft back.
     */
    private val autosave = activity.lifecycleScope.launch {
        while (true) {
            kotlinx.coroutines.delay(autosaveMs)
            if (!reopening && hasChanges()) saveDraft()
        }
    }

    // The app draws edge to edge, so the keyboard does not resize the window: lift the panel's
    // container above it instead.
    private val imeWatcher = android.view.ViewTreeObserver.OnPreDrawListener { liftAboveKeyboard(); true }
    private var watching = false

    /** Starts (or stops) keeping the toolbar above the soft keyboard. */
    fun keepAboveKeyboard(on: Boolean) {
        val observer = reader.rootView.viewTreeObserver
        if (on && !watching) observer.addOnPreDrawListener(imeWatcher)
        if (!on && watching && observer.isAlive) observer.removeOnPreDrawListener(imeWatcher)
        watching = on
        if (!on && liftToolbar) {
            (toolbar?.parent as? View)?.translationY = 0f
            if (reader.paddingBottom != 0) reader.setPadding(reader.paddingLeft, reader.paddingTop, reader.paddingRight, 0)
        }
    }

    // where the keyboard and the app's bar were last seen
    private var seenIme = 0
    private var seenBarTop = 0

    private fun liftAboveKeyboard() {
        val ime = androidx.core.view.ViewCompat.getRootWindowInsets(reader)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: return
        val container = toolbar?.parent as? View
        if (!liftToolbar || container == null) {
            // the app moves its bar (or has none): follow it and the keyboard
            val barTop = toolbar?.let { IntArray(2).also { at -> it.getLocationInWindow(at) }[1] } ?: 0
            if (ime != seenIme || barTop != seenBarTop) {
                seenIme = ime
                seenBarTop = barTop
                onKeyboardMoved()
            }
            return
        }
        val location = IntArray(2)
        container.getLocationInWindow(location)
        // where the toolbar sits when not lifted
        val restingTop = location[1] - container.translationY
        val bottom = restingTop + container.height
        val lift = if (ime <= 0) 0f else maxOf(0f, bottom - (container.rootView.height - ime))
        if (container.translationY != -lift) {
            container.translationY = -lift
            onKeyboardMoved()
        }
        // the toolbar (and the keyboard under it) cover the bottom of the document: let it scroll
        // that far, so its end can come above them
        val readerAt = IntArray(2)
        reader.getLocationInWindow(readerAt)
        val overlap = maxOf(0, (readerAt[1] + reader.height - (restingTop - lift)).toInt())
        if (reader.paddingBottom != overlap) reader.setPadding(reader.paddingLeft, reader.paddingTop, reader.paddingRight, overlap)
    }

    /** The toolbar moved with the keyboard; the visible part of the document changed. */
    protected open fun onKeyboardMoved() {}

    /** Bottom of the document area not covered by the context bar, the toolbar (or else the keyboard), in [of]'s coordinates. */
    protected fun visibleBottom(of: View): Int = areaBottom(of) - (bar?.takeIf { it.isShown }?.layoutParams?.height ?: 0)

    /** Bottom of the document area not covered by the toolbar (or else the keyboard), in [of]'s coordinates. */
    private fun areaBottom(of: View): Int {
        val at = IntArray(2).also { of.getLocationInWindow(it) }
        val bar = toolbar?.takeIf { it.isShown }
        val top = if (bar != null) IntArray(2).also { bar.getLocationInWindow(it) }[1]
            // the keyboard, or else the navigation bar the app draws under (edge to edge)
            else of.rootView.height - (androidx.core.view.ViewCompat.getRootWindowInsets(of)?.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime() or androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0)
        return minOf(of.height, top - at[1])
    }

    protected val context: Context get() = activity

    /** The activity the panel edits in, for views made for it (the SDK's bar). */
    val hostContext: Context get() = activity

    /** Colors and sizes of the bar, read once when it opens. */
    protected val editStyle: EditStyle = EditStyle.of(activity)

    protected fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    /** The SDK string [id], in the app's language. */
    protected fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    // while Save runs only its result is shown: the steps before and after it (text being typed
    // written to the document, a reopen) say nothing
    private var quiet = 0
    private var lastToast: Toast? = null

    /** Shows [message], replacing the one still on screen instead of queuing after it. */
    protected fun toast(message: String) {
        if (quiet > 0) return
        lastToast?.cancel()
        lastToast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private inline fun <T> quietly(block: () -> T): T {
        quiet++
        try { return block() } finally { quiet-- }
    }

    protected fun line(vararg views: View, weights: FloatArray? = null): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val w = weights?.getOrNull(i) ?: 0f
            addView(v, LinearLayout.LayoutParams(if (w > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, w))
        }
    }

    /** True when the user may use [feature]. */
    protected fun has(feature: EditFeature): Boolean = feature in features

    protected fun label(text: String = ""): TextView = TextView(context).apply {
        this.text = text
        textSize = editStyle.labelTextSp
        setTextColor(editStyle.labelText)
        typeface = editStyle.typeface
        setPadding(dp(4), dp(4), dp(8), dp(4))
    }

    protected fun input(hint: String): EditText = EditText(context).apply {
        this.hint = hint
        textSize = editStyle.inputTextSp
        typeface = editStyle.typeface
        editStyle.inputText?.let { setTextColor(it) }
        editStyle.inputHint?.let { setHintTextColor(it) }
        editStyle.inputLine?.let { backgroundTintList = android.content.res.ColorStateList.valueOf(it) }
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        editStyle.inputStyler?.style(this)
    }

    protected fun report(result: EditResult, saved: String) {
        when (result) {
            is EditResult.Ok -> toast(if (result.warnings.isEmpty()) saved else saved + " (" + result.warnings.first() + ")")
            is EditResult.Error -> toast(result.message)
        }
    }

    companion object {
        /** How often the open edits are kept in a draft (shorter in tests). */
        @JvmStatic var autosaveMs = 120_000L
        /** Office's theme colors: background/text light and dark, then accents 1-6. */
        private val THEME_COLORS = listOf("FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47")
        private val STANDARD_COLORS = listOf("C00000", "FF0000", "FFC000", "FFFF00", "92D050", "00B050", "00B0F0", "0070C0", "002060", "7030A0")
        /**
         * Saves through a temporary sibling file and then replaces [original], so a failed save
         * never leaves a half written document behind.
         */
        fun saveOver(original: File, save: (File) -> EditResult): EditResult {
            val tmp = File(original.parentFile, ".${original.nameWithoutExtension}.saving.${original.extension}")
            tmp.delete()
            val result = try {
                save(tmp)
            } catch (e: Exception) {
                EditResult.Error(com.wxiwei.office.editor.Reason.IO, e.message ?: "Save failed", e)
            }
            if (result !is EditResult.Ok) {
                tmp.delete()
                return result
            }
            return try {
                if (!tmp.renameTo(original)) {
                    tmp.copyTo(original, overwrite = true)
                    tmp.delete()
                }
                EditResult.Ok(original, result.warnings)
            } catch (e: Exception) {
                EditResult.Error(com.wxiwei.office.editor.Reason.IO, e.message ?: "Cannot replace the file", e)
            }
        }
    }
}
