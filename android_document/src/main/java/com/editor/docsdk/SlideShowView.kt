/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.editor.docsdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import com.wxiwei.office.editor.pptx.PptxEditor
import com.wxiwei.office.editor.pptx.SlideEffect
import com.wxiwei.office.editor.pptx.SlideScript
import com.wxiwei.office.editor.ui.SlideEditPanel
import com.wxiwei.office.pg.control.Presentation
import com.wxiwei.office.pg.show.InkView
import com.wxiwei.office.pg.show.ShowStage
import com.wxiwei.office.pg.show.SlideFrame
import com.wxiwei.office.pg.view.SlideDrawKit
import com.wxiwei.office.reader.OfficeDocumentView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The slideshow of a presentation: each slide fills the view and comes in with its transition,
 * the animations of its shapes play tap by tap, links can be tapped, and a pen, an eraser and a
 * laser pointer draw over the slides.
 *
 * The view has no buttons: the app draws its own bar and calls [next], [previous], [goTo],
 * [ink] and [setAutoAdvance]; [Listener] tells what changed. A tap moves on (back, in the left
 * quarter), a fling left or right too. What to show comes from an open presentation, see
 * [DocumentView.prepareSlideShow] and [prepare]; it can be handed to a view of another activity.
 */
class SlideShowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** What the show tells the app. Every method has an empty default. */
    interface Listener {
        /** Slide [slide] (0 based) of [count] is shown. */
        fun onSlideChanged(slide: Int, count: Int) {}

        /** The show went past its last slide ([ended] true), or came back from there. */
        fun onEnded(ended: Boolean) {}

        /** A long press on the slide: the place to show or hide the app's bar. */
        fun onLongPress() {}

        /** A link to a web page was tapped. Return true when the app opens it; otherwise the view does. */
        fun onLink(url: String): Boolean = false

        /** Slide [slide] (0 based) is still being read and could not be shown yet. */
        fun onSlideNotReady(slide: Int) {}
    }

    /** What draws over the slide when a finger moves on it. */
    enum class Ink { NONE, PEN, ERASER, LASER }

    /**
     * The slides of a presentation ready to be shown, made by [prepare]. It reads from the
     * document view it came from: that view must stay open while the show runs.
     */
    class Source internal constructor(
        /** Slides of the presentation. */
        val slideCount: Int,
        internal val aspect: Float,
        /** Slide the show starts at, 0 based. */
        val startAt: Int,
        internal val script: List<SlideScript>,
        internal val layers: suspend (page: Int, width: Int, animated: Set<Int>) -> Pair<SlideDrawKit.Layers?, Boolean>,
        internal val invalidated: Flow<Int>,
    ) {
        /** Title of slide [slide] (0 based), empty when it has none. */
        fun title(slide: Int): String = script.getOrNull(slide)?.title.orEmpty()

        /** Whether slide [slide] (0 based) is hidden: the show skips it, [goTo] still shows it. */
        fun isHidden(slide: Int): Boolean = script.getOrNull(slide)?.hidden == true
    }

    var listener: Listener? = null

    private val stage = ShowStage(context)
    private val inkView = InkView(context)
    private var scope: CoroutineScope? = null
    private var loadJob: Job? = null
    private var autoJob: Job? = null
    private var source: Source? = null

    /** Effects of one tap; [auto]: they start with the slide. Each with its start in the group (ms). */
    private class Group(val auto: Boolean, val effects: MutableList<Pair<SlideEffect, Long>> = ArrayList())

    private var slide = -1
    private var doneSteps = 0
    private var groups: List<Group> = emptyList()
    private var ended = false
    private var startGroupPending = false
    private val cache = LinkedHashMap<Int, SlideDrawKit.Layers>()
    private var autoMs: Long? = null
    private var loop = false

    /** Slide shown, 0 based; -1 before the first one shows. */
    val currentSlide: Int get() = slide

    /** Slides of the show, 0 before [start]. */
    val slideCount: Int get() = source?.slideCount ?: 0

    /** Taps already played on the slide shown (its animations come in groups, one per tap). */
    val step: Int get() = doneSteps

    /** True after the last slide: [previous] comes back to it. */
    val isEnded: Boolean get() = ended

    /** True while a transition or an animation runs. */
    val isAnimating: Boolean get() = stage.isAnimating

    /** Where the slide is drawn in this view, empty before one shows. */
    val slideBounds: RectF get() = RectF(inkView.area)

    /** Ids of the shapes of the slide shown that have animations. */
    val animatedShapes: Set<Int> get() = stage.current?.shapes?.keys?.toSet().orEmpty()

    /** The view an animated shape of the slide shown is drawn in (see [animatedShapes]), or null. */
    fun shapeView(shapeId: Int): View? = stage.current?.shapes?.get(shapeId)

    /** Pen, eraser or laser pointer over the slide; [Ink.NONE] gives the touches back to the show. */
    var ink: Ink = Ink.NONE
        set(value) {
            field = value
            inkView.mode = when (value) {
                Ink.NONE -> InkView.Mode.NONE
                Ink.PEN -> InkView.Mode.PEN
                Ink.ERASER -> InkView.Mode.ERASER
                Ink.LASER -> InkView.Mode.LASER
            }
        }

    /** Color of the pen. */
    @get:ColorInt
    @setparam:ColorInt
    var inkColor: Int
        get() = inkView.color
        set(value) { inkView.color = value }

    /** Takes the pen strokes off the slide shown. */
    fun clearInk() = inkView.clear()

    /** Pen strokes on slide [slide] (0 based; the one shown by default). */
    @JvmOverloads
    fun strokeCount(slide: Int = currentSlide): Int = inkView.strokesOf(slide).size

    init {
        setBackgroundColor(Color.BLACK)
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        keepScreenOn = true
        stage.onSettled = {
            if (startGroupPending) {
                startGroupPending = false
                groups.firstOrNull()?.takeIf { it.auto }?.let { doneSteps = 1; stage.play(it.effects) }
            } else scheduleAuto()
        }
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) { placeInk(); if (slide >= 0) reload() } }
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
            override fun onLongPress(e: MotionEvent) { listener?.onLongPress() }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 800) { if (vx < 0) next() else previous(); return true }
                return false
            }
        })
        @SuppressLint("ClickableViewAccessibility")
        stage.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
    }

    /** Starts the show of [source] at [Source.startAt]. */
    @MainThread
    fun start(source: Source) {
        stop()
        this.source = source
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { this.scope = it }
        scope.launch { source.invalidated.collect { page -> cache.remove(page - 1); if (page - 1 == slide) reload() } }
        stage.post { if (this.source === source) go(source.startAt.coerceIn(0, maxOf(0, source.slideCount - 1))) }
    }

    /** Ends the show: nothing runs any more. The view stops by itself when it leaves the window. */
    @MainThread
    fun stop() {
        scope?.cancel()
        scope = null
        loadJob = null
        autoJob = null
        stage.finishAll()
        cache.clear()
        source = null
        slide = -1
        doneSteps = 0
        groups = emptyList()
        ended = false
        startGroupPending = false
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---- slides and steps ------------------------------------------------------------------

    private fun scriptOf(i: Int): SlideScript? = source?.script?.getOrNull(i)

    private fun groupsOf(effects: List<SlideEffect>): List<Group> {
        val out = ArrayList<Group>()
        var cur: Group? = null
        var groupStart = 0L; var groupEnd = 0L
        effects.forEachIndexed { i, e ->
            if (cur == null || e.start == SlideEffect.Start.CLICK) {
                cur = Group(i == 0 && e.start != SlideEffect.Start.CLICK).also { out.add(it) }
                groupStart = 0; groupEnd = 0
            }
            if (e.start == SlideEffect.Start.AFTER) groupStart = groupEnd
            val at = groupStart + e.delayMs
            cur!!.effects.add(e to at)
            groupEnd = maxOf(groupEnd, at + e.durationMs)
        }
        return out
    }

    private fun effects(i: Int) = scriptOf(i)?.effects.orEmpty()

    /** Every animated shape at rest as it is after the first [done] groups. */
    private fun applyState(frame: SlideFrame, done: Int) {
        val all = effects(frame.index)
        val visible = HashMap<Int, Boolean>()
        for (e in all) if (e.shapeId !in visible) visible[e.shapeId] = e.kind != SlideEffect.Kind.ENTRANCE
        for (g in groups.take(done)) for ((e, _) in g.effects) visible[e.shapeId] = e.kind != SlideEffect.Kind.EXIT
        for ((id, v) in visible) frame.rest(id, v)
    }

    private fun fitWidth(): Int {
        val aspect = source?.aspect ?: return 0
        val w = stage.width; val h = stage.height
        if (w <= 0 || h <= 0) return 0
        return minOf(w.toFloat(), h * aspect).toInt()
    }

    private suspend fun layersOf(i: Int): SlideDrawKit.Layers? {
        cache[i]?.let { return it }
        val src = source ?: return null
        val width = fitWidth().takeIf { it > 0 } ?: return null
        val animated = effects(i).map { it.shapeId }.toSet()
        var tries = 0
        while (true) {
            val (layers, complete) = src.layers(i + 1, width, animated)
            if (layers != null && (complete || tries >= 6)) {
                cache[i] = layers
                while (cache.size > 3) cache.remove(cache.keys.first { it != i })
                return layers
            }
            if (layers == null && tries >= 20) return null // the slide is still being read
            tries++
            delay(if (layers == null) 300 else 500)
        }
    }

    /** Shows slide [slide] (0 based) from its start, hidden or not. */
    @MainThread
    fun goTo(slide: Int) = go(slide)

    /** Shows slide [i]: with its transition when moving on, at its last step when coming back. */
    private fun go(i: Int, forward: Boolean = true, atEnd: Boolean = false) {
        val scope = scope ?: return
        if (i !in 0 until slideCount) return
        cancelAuto()
        setEnded(false)
        loadJob?.cancel()
        loadJob = scope.launch {
            val layers = layersOf(i) ?: run { listener?.onSlideNotReady(i); return@launch }
            val frame = SlideFrame(context, i, layers)
            slide = i
            groups = groupsOf(effects(i))
            doneSteps = if (atEnd) groups.size else 0
            applyState(frame, doneSteps)
            startGroupPending = !atEnd && groups.firstOrNull()?.auto == true
            stage.show(frame, scriptOf(i)?.transition, forward && !atEnd)
            inkView.slide = i
            placeInk()
            listener?.onSlideChanged(i, slideCount)
            // the next slide ready before it is asked for
            nextVisible(i)?.let { n -> if (n !in cache) launch { layersOf(n) } }
        }
    }

    /** The same slide drawn again (new size, or pictures loaded), at the same step. */
    private fun reload() {
        val scope = scope ?: return
        val i = slide.takeIf { it >= 0 } ?: return
        cache.remove(i)
        val done = doneSteps
        loadJob?.cancel()
        loadJob = scope.launch {
            val layers = layersOf(i) ?: return@launch
            val frame = SlideFrame(context, i, layers)
            applyState(frame, done)
            stage.show(frame, null, false)
            placeInk()
        }
    }

    private fun nextVisible(from: Int): Int? = (from + 1 until slideCount).firstOrNull { scriptOf(it)?.hidden != true }
    private fun previousVisible(from: Int): Int? = (from - 1 downTo 0).firstOrNull { scriptOf(it)?.hidden != true }

    /**
     * The next step: ends what is moving, else plays the next animations of the slide, else shows
     * the next slide; after the last one the show has ended (or starts again when it loops).
     */
    @MainThread
    fun next() {
        if (ended) return
        if (stage.isAnimating) { stage.finishAll(); return }
        if (slide < 0) return
        if (doneSteps < groups.size) { stage.play(groups[doneSteps].effects); doneSteps++; return }
        val n = nextVisible(slide)
        when {
            n != null -> go(n)
            loop -> (0 until slideCount).firstOrNull { scriptOf(it)?.hidden != true }?.let { go(it) }
            else -> showEnd()
        }
    }

    /** One step back: the animations of the last tap undone, else the slide before at its end. */
    @MainThread
    fun previous() {
        if (ended) { setEnded(false); return }
        if (stage.isAnimating) stage.finishAll()
        val first = if (groups.firstOrNull()?.auto == true) 1 else 0
        if (doneSteps > first) { doneSteps--; stage.current?.let { applyState(it, doneSteps) }; return }
        previousVisible(slide)?.let { go(it, forward = false, atEnd = true) }
    }

    private fun setEnded(value: Boolean) {
        if (ended == value) return
        ended = value
        listener?.onEnded(value)
    }

    private fun showEnd() {
        cancelAuto()
        setEnded(true)
    }

    // ---- taps and links --------------------------------------------------------------------

    private fun tap(x: Float, y: Float) {
        val frame = stage.current
        if (frame != null && !ended) {
            val left = (stage.width - frame.slideWidth) / 2f
            val top = (stage.height - frame.slideHeight) / 2f
            val emuX = ((x - left) / frame.layers.zoom * EMU_PER_PX).toLong()
            val emuY = ((y - top) / frame.layers.zoom * EMU_PER_PX).toLong()
            val link = scriptOf(frame.index)?.links?.lastOrNull { l ->
                emuX in l.rectEmu.x..(l.rectEmu.x + l.rectEmu.width) && emuY in l.rectEmu.y..(l.rectEmu.y + l.rectEmu.height)
            }
            if (link != null) {
                val url = link.url
                when {
                    url != null -> if (listener?.onLink(url) != true) try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        // nothing on the device opens it
                    }
                    link.slideIndex != null -> go(link.slideIndex)
                    link.jump == "next" -> next()
                    link.jump == "previous" -> previousVisible(slide)?.let { go(it) }
                    link.jump == "first" -> go(0)
                    link.jump == "last" -> go(slideCount - 1)
                    link.jump == "end" -> showEnd()
                }
                return
            }
        }
        if (x < stage.width * 0.25f) previous() else next()
    }

    /**
     * Volume keys, arrows, page keys, space, Enter and the keys of presentation remotes move on
     * and back. Call it from the activity's `dispatchKeyEvent` and return true when it does.
     */
    @MainThread
    fun handleKey(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_N -> ::next
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_P -> ::previous
            else -> return false
        }
        // on the key's release: a press is sometimes not delivered (focus moving between windows)
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) action()
        return true
    }

    // ---- timed advance ---------------------------------------------------------------------

    /**
     * Moves on by itself [afterMs] after everything stopped moving; null: only the slides' own
     * timings. With [loop] the show starts again after its last slide.
     */
    @MainThread
    @JvmOverloads
    fun setAutoAdvance(afterMs: Long?, loop: Boolean = false) {
        autoMs = afterMs
        this.loop = loop
        scheduleAuto()
    }

    private fun cancelAuto() { autoJob?.cancel(); autoJob = null }

    /** When nothing moves: the next step after the chosen time, or the slide's own timing. */
    private fun scheduleAuto() {
        cancelAuto()
        val scope = scope ?: return
        if (ended || slide < 0) return
        val ms = autoMs ?: scriptOf(slide)?.transition?.advanceAfterMs?.toLong() ?: return
        autoJob = scope.launch { delay(ms); next() }
    }

    // ---- pen and laser ---------------------------------------------------------------------

    private fun placeInk() {
        val frame = stage.current ?: return
        val left = (stage.width - frame.slideWidth) / 2f
        val top = (stage.height - frame.slideHeight) / 2f
        inkView.area.set(left, top, left + frame.slideWidth, top + frame.slideHeight)
        inkView.invalidate()
    }

    companion object {
        private const val EMU_PER_PX = 9525f

        /**
         * Gets the slideshow of the presentation open in [view] ready: [ready] runs on the main
         * thread with it, or with null when [view] shows no presentation yet. [file] is the
         * .pptx it shows (its animations, transitions, links and titles are read from it; none
         * for a .ppt or null), [editPanel] its edit panel while it is edited (the show then plays
         * the edits not saved yet). The show starts at slide [startAt] (0 based).
         */
        @JvmStatic
        @MainThread
        fun prepare(view: OfficeDocumentView, file: File?, editPanel: SlideEditPanel?, startAt: Int, ready: (Source?) -> Unit) {
            val slides = view.thumbnails
            val count = view.state.value.pageCount
            val size = (view.control?.getView() as? Presentation)?.getPageSize()
            if (slides == null || count == 0 || size == null || size.width <= 0 || size.height <= 0) return ready(null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope.launch {
                val script = editPanel?.showScript() ?: withContext(Dispatchers.IO) {
                    if (file != null && file.extension.equals("pptx", true)) PptxEditor(file).showScript() else emptyList()
                }
                ready(Source(count, size.width.toFloat() / size.height, startAt.coerceIn(0, count - 1), script,
                    { page, width, animated -> slides.slideLayers(page, width, animated) }, view.thumbnailInvalidated))
                scope.cancel()
            }
        }
    }
}
