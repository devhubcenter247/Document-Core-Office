package com.alf06.document.reader.ui.home.document.office

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.editor.docsdk.SlideShowView
import com.wxiwei.office.editor.ui.DialogKit
import androidx.lifecycle.lifecycleScope
import com.alf06.document.reader.databinding.ActivitySlideShowBinding
import com.ui.baselib.base.BaseActivity
import com.ui.baselib.extensions.gone
import com.ui.baselib.extensions.visible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The slideshow: the SDK's [SlideShowView] plays the slides (transitions, shape animations tap by
 * tap, links); the bar here (long press to show it) has the pen / laser pointer, the slide list and
 * timed advance; volume keys and presentation remotes move on and back.
 */
class SlideShowActivity :
    BaseActivity<ActivitySlideShowBinding>(ActivitySlideShowBinding::inflate) {
    override val fullStatus: Boolean = true

    private lateinit var show: SlideShowView
    private lateinit var endScreen: TextView
    private lateinit var palette: LinearLayout
    private var hideUiJob: Job? = null

    private val src get() = source!!

    override fun backPressed() { finish() }

    override fun initialize() {
        if (source == null) { finish(); return }
        show = SlideShowView(this)
        endScreen = TextView(this).apply {
            text = "Kết thúc trình chiếu. Chạm để thoát."
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { finish() }
        }
        binding.stageHost.addView(show, FrameLayout.LayoutParams(-1, -1))
        binding.stageHost.addView(endScreen, FrameLayout.LayoutParams(-1, -1))
        palette = buildPalette()
        binding.stageHost.addView(palette, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        show.listener = object : SlideShowView.Listener {
            override fun onSlideChanged(slide: Int, count: Int) = updateCounter()
            override fun onEnded(ended: Boolean) { endScreen.visibility = if (ended) View.VISIBLE else View.GONE }
            override fun onLongPress() = toggleUi()
            override fun onSlideNotReady(slide: Int) = Toast.makeText(this@SlideShowActivity, "Chưa mở xong slide ${slide + 1}", Toast.LENGTH_SHORT).show()
        }
        addBarButtons()
    }

    override fun ActivitySlideShowBinding.setData() {
        showUi()
        show.start(src)
    }

    override fun ActivitySlideShowBinding.onClick() {
        icBack.setOnClickListener { backPressed() }
        icNexPage.setOnClickListener { show.next() }
        icPrevPage.setOnClickListener { show.previous() }
    }

    /** Keys before the views: arrows and page keys would otherwise move the focus between buttons. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finish()
            return true
        }
        return show.handleKey(event) || super.dispatchKeyEvent(event)
    }

    // ---- timed advance ---------------------------------------------------------------------

    private fun pickAuto() {
        val choices = listOf("Tắt" to (null to false), "3 giây" to (3000L to false), "5 giây" to (5000L to false), "10 giây" to (10000L to false),
            "20 giây" to (20000L to false), "5 giây, lặp lại" to (5000L to true), "10 giây, lặp lại" to (10000L to true))
        DialogKit(this).pick("Tự chuyển", choices.map { it.first }) { i ->
            val (ms, loop) = choices[i].second
            autoButton.text = if (ms == null) "⏱ Tự chuyển" else "⏱ " + choices[i].first
            show.setAutoAdvance(ms, loop)
        }
    }

    // ---- slide list ------------------------------------------------------------------------

    private fun pickSlide() {
        val labels = (0 until src.slideCount).map { i ->
            "${i + 1}. " + (src.title(i).takeIf { it.isNotBlank() } ?: "Slide ${i + 1}") + if (src.isHidden(i)) " (ẩn)" else ""
        }
        DialogKit(this).pick("Danh sách slide", labels, "Đóng") { i -> show.goTo(i) }
    }

    // ---- pen and laser ---------------------------------------------------------------------

    private fun setInk(mode: SlideShowView.Ink) {
        show.ink = mode
        palette.visibility = if (mode == SlideShowView.Ink.NONE) View.GONE else View.VISIBLE
        for (i in 0 until palette.childCount) {
            val v = palette.getChildAt(i)
            // colors and the eraser only mean something for the pen
            if (v.tag == "pen") v.visibility = if (mode == SlideShowView.Ink.LASER) View.GONE else View.VISIBLE
        }
        if (mode != SlideShowView.Ink.NONE) { hideUiJob?.cancel(); binding.lnUiState.gone() }
    }

    private fun buildPalette(): LinearLayout = LinearLayout(this).apply {
        visibility = View.GONE
        setBackgroundColor(0xB0000000.toInt())
        setPadding(dp(6), dp(4), dp(6), dp(4))
        gravity = Gravity.CENTER_VERTICAL
        for (c in listOf(Color.RED, 0xFFFFC000.toInt(), 0xFF00B050.toInt(), 0xFF0070C0.toInt(), Color.WHITE, Color.BLACK)) {
            addView(View(context).apply {
                tag = "pen"
                contentDescription = "Bút màu #%06X".format(c and 0xFFFFFF)
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c); setStroke(dp(2), Color.WHITE) }
                setOnClickListener { show.inkColor = c; show.ink = SlideShowView.Ink.PEN }
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        fun text(label: String, tag: String?, run: () -> Unit) = addView(TextView(context).apply {
            text = label; this.tag = tag; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { run() }
        })
        text("Tẩy", "pen") { show.ink = SlideShowView.Ink.ERASER }
        text("Xóa hết", "pen") { show.clearInk() }
        text("Xong", null) { setInk(SlideShowView.Ink.NONE) }
    }

    // ---- bar -------------------------------------------------------------------------------

    private lateinit var autoButton: TextView

    private fun addBarButtons() {
        val bar = binding.bottomBar
        fun button(label: String, run: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { run(); showUi() }
        }
        val items = listOf(
            button("☰ Danh sách") { pickSlide() },
            button("✎ Bút") { setInk(SlideShowView.Ink.PEN) },
            button("● Laser") { setInk(SlideShowView.Ink.LASER) },
            button("⏱ Tự chuyển") { pickAuto() }.also { autoButton = it },
        )
        items.forEachIndexed { i, v -> bar.addView(v, i) }
    }

    private fun toggleUi() { if (binding.lnUiState.visibility == View.VISIBLE) binding.lnUiState.gone() else showUi() }

    private fun showUi() {
        binding.lnUiState.visible()
        hideUiJob?.cancel()
        hideUiJob = lifecycleScope.launch {
            delay(UI_VISIBLE_DURATION)
            binding.lnUiState.gone()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateCounter() { binding.tvPageCurrent.text = "${show.currentSlide + 1}/${src.slideCount}" }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    // for tests: what is shown
    internal val shownSlide get() = show.currentSlide
    internal val shownStep get() = show.step
    internal val isEnded get() = show.isEnded
    internal val showView get() = show

    override fun onDestroy() {
        hideUiJob?.cancel()
        if (isFinishing) source = null
        super.onDestroy()
    }

    companion object {
        private const val UI_VISIBLE_DURATION = 3_000L

        /** What to show, from [SlideShowView.prepare]: handed over by the screen that opens the show. */
        var source: SlideShowView.Source? = null
    }
}
