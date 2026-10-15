package com.wxiwei.office.editor.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.editor.docsdk.EditAction
import com.editor.docsdk.EditStyle

/**
 * The SDK's edit bar for a [panel]: Undo, Redo, a status line and Save on top, the panel's
 * [OfficeEditPanel.header] (Excel's formula box), then tabs and the icons of the selected tab.
 * Put [view] at the bottom of the screen; each button's tag is its [EditAction]'s name.
 */
class EditToolbar(private val panel: OfficeEditPanel) {
    private val context = panel.hostContext
    private val style: EditStyle = EditStyle.of(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * density).toInt()

    private val items = ArrayList<Pair<EditAction, View>>()
    private val tabs = panel.tabs
        .map { tab -> tab to tab.actions.filter { it !in TOP && panel.isAvailable(it) } }
        .filter { it.second.isNotEmpty() }
    private var selectedTab = 0
    private val tabViews = ArrayList<TextView>()
    // one row of icons per tab, made at once; the selected tab's shows
    private val rows = tabs.map { (_, actions) ->
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for (a in actions) addView(item(a))
        }
    }
    private val status = TextView(context).apply {
        setTextColor(style.labelText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.labelTextSp)
        typeface = style.typeface
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
        text = panel.status
        tag = STATUS
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(style.toolbarBackground)
        elevation = dp(8).toFloat()
        addView(divider())
        addView(topRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        panel.header?.let { header ->
            (header.parent as? ViewGroup)?.removeView(header)
            addView(divider())
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (tabs.isNotEmpty()) {
            addView(divider())
            if (tabs.size > 1) addView(tabRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(4), dp(2), dp(4), dp(4))
                clipToPadding = false
                addView(FrameLayout(context).apply { rows.forEach { addView(it) } })
            })
        }
    }

    init {
        panel.toolbar = view
        showTab(0)
        panel.addStatusListener { status.text = it }
        panel.addStateListener { refresh() }
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(style.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f)))
    }

    private fun topRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(8), 0)
        for (a in listOf(EditAction.UNDO, EditAction.REDO)) if (panel.isAvailable(a)) addView(item(a))
        addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (panel.isAvailable(EditAction.SAVE_COPY)) addView(item(EditAction.SAVE_COPY))
        addView(saveButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(4) })
    }

    private fun saveButton() = TextView(context).apply {
        setText(EditAction.SAVE.label)
        tag = EditAction.SAVE.name
        setTextColor(style.onAccent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create(style.typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), GradientDrawable().apply {
            setColor(style.accent)
            cornerRadius = dp(18).toFloat()
        }, null)
        setOnClickListener { panel.run(EditAction.SAVE, null, false) }
        style.itemStyler?.style(this, EditAction.SAVE)
    }

    private fun tabRow() = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            tabs.forEachIndexed { i, (tab, _) ->
                val label = TextView(context).apply {
                    setText(tab.title)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.tabTextSp)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    background = ripple(false)
                    setOnClickListener { showTab(i) }
                }
                tabViews.add(label)
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        })
    }

    private fun showTab(index: Int) {
        if (tabs.isEmpty()) return
        selectedTab = index
        tabViews.forEachIndexed { i, t ->
            val on = i == index
            t.setTextColor(if (on) style.accent else style.tabText)
            t.typeface = Typeface.create(style.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            // the selected tab is underlined in the accent color
            t.foreground = if (on) GradientDrawable().apply { setColor(style.accent) }.let { line ->
                android.graphics.drawable.LayerDrawable(arrayOf(line)).apply { setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(3)) }
            } else null
        }
        rows.forEachIndexed { i, r -> r.visibility = if (i == index) View.VISIBLE else View.GONE }
        refresh()
    }

    /** The icon button of [action]. */
    private fun item(action: EditAction): View = FrameLayout(context).apply {
        tag = action.name
        contentDescription = context.getString(action.label)
        TooltipCompat.setTooltipText(this, contentDescription)
        layoutParams = LinearLayout.LayoutParams(dp(style.buttonDp), dp(style.buttonDp)).apply { setMargins(dp(2), 0, dp(2), 0) }
        background = ripple(true)
        addView(ImageView(context).apply {
            setImageResource(style.icons?.icon(action) ?: action.icon)
            imageTintList = ColorStateList.valueOf(if (action in DELETES) style.deleteIcon else style.icon)
        }, FrameLayout.LayoutParams(dp(style.iconDp), dp(style.iconDp), Gravity.CENTER))
        setOnClickListener { panel.run(action, null, false) }
        style.itemStyler?.style(this, action)
        items.add(action to this)
    }

    /** Buttons whose command is on (bold text selected...) get a tinted background. */
    private fun refresh() {
        for ((action, v) in items) {
            v.alpha = if (panel.isEnabled(action)) 1f else 0.4f
            val on = panel.isActive(action)
            if (v.isSelected == on) continue
            v.isSelected = on
            ((v as ViewGroup).getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(if (on) style.iconActive else if (action in DELETES) style.deleteIcon else style.icon)
            v.background = if (on) ripple(true, style.activeBackground) else ripple(true)
        }
    }

    private fun ripple(rounded: Boolean, fill: Int = 0) = RippleDrawable(
        ColorStateList.valueOf(0x1F000000),
        if (fill != 0) GradientDrawable().apply { setColor(fill); cornerRadius = dp(10).toFloat() } else null,
        GradientDrawable().apply { setColor(-0x1); if (rounded) cornerRadius = dp(10).toFloat() },
    )

    companion object {
        /** Tag of the status line. */
        const val STATUS = "STATUS"
        /** On the top row, not in the tabs. */
        private val TOP = setOf(EditAction.UNDO, EditAction.REDO, EditAction.SAVE, EditAction.SAVE_COPY)
        private val DELETES = setOf(EditAction.DELETE, EditAction.DELETE_ROW, EditAction.DELETE_COLUMN, EditAction.DELETE_SLIDE, EditAction.CLEAR_CELLS)
    }
}
