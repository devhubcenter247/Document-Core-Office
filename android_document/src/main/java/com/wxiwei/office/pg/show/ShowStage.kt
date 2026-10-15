/*
 * Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * Proprietary and confidential. Unauthorized copying, modification or distribution of this
 * file, via any medium, is strictly prohibited without the written permission of dongb2002.
 */
package com.wxiwei.office.pg.show

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.wxiwei.office.editor.pptx.SlideEffect
import com.wxiwei.office.editor.pptx.SlideTransition
import com.wxiwei.office.pg.view.SlideDrawKit

/**
 * One slide on the stage: the picture of what does not move, and every animated shape as its own
 * view at its place, so effects run on the GPU at the display's frame rate.
 */
internal class SlideFrame(context: Context, val index: Int, val layers: SlideDrawKit.Layers) : FrameLayout(context) {
    val base = ImageView(context).apply { setImageBitmap(layers.base); scaleType = ImageView.ScaleType.FIT_XY }
    val shapes = HashMap<Int, ImageView>()

    init {
        setBackgroundColor(Color.WHITE)
        addView(base, LayoutParams(layers.base.width, layers.base.height))
        for ((id, pair) in layers.shapes) {
            val (bitmap, r) = pair
            val v = ImageView(context).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_XY; pivotX = r.width() / 2f; pivotY = r.height() / 2f }
            addView(v, LayoutParams(r.width(), r.height()).apply { leftMargin = r.left; topMargin = r.top })
            shapes[id] = v
        }
    }

    val slideWidth get() = layers.base.width
    val slideHeight get() = layers.base.height

    /** Puts newer pictures of the same slide in (pictures that were still loading). */
    fun refresh(newer: SlideDrawKit.Layers) {
        base.setImageBitmap(newer.base)
        for ((id, v) in shapes) newer.shapes[id]?.first?.let { v.setImageBitmap(it) }
    }

    /** A shape shown (or not) at rest: no effect running on it. */
    fun rest(id: Int, visible: Boolean) {
        val v = shapes[id] ?: return
        v.animate().cancel()
        v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.clipBounds = null
        v.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
}

/**
 * Plays a slide's effects and moves between slides with their transitions. [onSettled] runs when
 * nothing is moving any more (auto advance waits for it).
 */
internal class ShowStage(context: Context) : FrameLayout(context) {
    var current: SlideFrame? = null; private set
    private val running = ArrayList<Animator>()
    var onSettled: (() -> Unit)? = null

    init { setBackgroundColor(Color.BLACK); clipChildren = true }

    val isAnimating get() = running.isNotEmpty()

    /** Ends every effect and transition at once (a tap while they run, like PowerPoint). */
    fun finishAll() {
        for (a in ArrayList(running)) a.end()
        running.clear()
    }

    private fun track(a: Animator) {
        running.add(a)
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                running.remove(animation)
                if (running.isEmpty()) post { if (running.isEmpty()) onSettled?.invoke() }
            }
        })
        a.start()
    }

    private fun place(frame: SlideFrame) {
        addView(frame, LayoutParams(frame.slideWidth, frame.slideHeight, Gravity.CENTER))
        frame.clipChildren = true
    }

    /** Shows [frame] with [transition] from the slide shown (none: at once). */
    fun show(frame: SlideFrame, transition: SlideTransition?, forward: Boolean = true) {
        finishAll()
        val old = current
        current = frame
        place(frame)
        val type = if (old == null || !forward) "cut" else transition?.type ?: "cut"
        val d = (transition?.durationMs ?: 0).toLong().coerceAtLeast(1)
        val w = frame.slideWidth.toFloat(); val h = frame.slideHeight.toFloat()
        val dir = transition?.direction ?: "l"
        fun done() { old?.let { removeView(it) } }
        val set = AnimatorSet().apply { duration = d; interpolator = AccelerateDecelerateInterpolator() }
        when (type) {
            "fade", "other" -> set.play(ObjectAnimator.ofFloat(frame, View.ALPHA, 0f, 1f))
            "push" -> {
                val (dx, dy) = slide(dir, w, h)
                set.playTogether(ObjectAnimator.ofFloat(frame, View.TRANSLATION_X, dx, 0f), ObjectAnimator.ofFloat(frame, View.TRANSLATION_Y, dy, 0f),
                    ObjectAnimator.ofFloat(old!!, View.TRANSLATION_X, 0f, -dx), ObjectAnimator.ofFloat(old, View.TRANSLATION_Y, 0f, -dy))
            }
            "cover" -> {
                val (dx, dy) = slide(dir, w, h)
                set.playTogether(ObjectAnimator.ofFloat(frame, View.TRANSLATION_X, dx, 0f), ObjectAnimator.ofFloat(frame, View.TRANSLATION_Y, dy, 0f))
            }
            "pull" -> {
                // the old slide moves away and uncovers the new one under it
                removeView(frame); addView(frame, 0, LayoutParams(frame.slideWidth, frame.slideHeight, Gravity.CENTER))
                val (dx, dy) = slide(dir, w, h)
                set.playTogether(ObjectAnimator.ofFloat(old!!, View.TRANSLATION_X, 0f, -dx), ObjectAnimator.ofFloat(old, View.TRANSLATION_Y, 0f, -dy))
            }
            "wipe" -> set.play(reveal(frame) { p -> wipeRect(dir, p, frame.slideWidth, frame.slideHeight) })
            "split" -> set.play(reveal(frame) { p -> val half = (frame.slideHeight * p / 2).toInt(); Rect(0, frame.slideHeight / 2 - half, frame.slideWidth, frame.slideHeight / 2 + half) })
            "zoom" -> set.playTogether(ObjectAnimator.ofFloat(frame, View.SCALE_X, 0.3f, 1f), ObjectAnimator.ofFloat(frame, View.SCALE_Y, 0.3f, 1f), ObjectAnimator.ofFloat(frame, View.ALPHA, 0f, 1f))
            else -> { done(); post { if (running.isEmpty()) onSettled?.invoke() }; return }
        }
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                frame.translationX = 0f; frame.translationY = 0f; frame.alpha = 1f; frame.scaleX = 1f; frame.scaleY = 1f; frame.clipBounds = null
                done()
            }
        })
        track(set)
    }

    /** Where the new slide comes from for [dir] (the way the slides move: l, r, u, d). */
    private fun slide(dir: String, w: Float, h: Float): Pair<Float, Float> = when (dir) {
        "r" -> -w to 0f; "u" -> 0f to h; "d" -> 0f to -h; else -> w to 0f
    }

    private fun wipeRect(dir: String, p: Float, w: Int, h: Int): Rect = when (dir) {
        "r" -> Rect(0, 0, (w * p).toInt(), h)
        "u" -> Rect(0, (h * (1 - p)).toInt(), w, h)
        "d" -> Rect(0, 0, w, (h * p).toInt())
        else -> Rect((w * (1 - p)).toInt(), 0, w, h)
    }

    private fun reveal(v: View, rectAt: (Float) -> Rect): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { v.clipBounds = rectAt(it.animatedValue as Float) }
        addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { v.clipBounds = null } })
    }

    /** Plays [effects] (each with its start time in the group, ms) on the slide shown. */
    fun play(effects: List<Pair<SlideEffect, Long>>) {
        val frame = current ?: return
        for ((e, at) in effects) {
            val v = frame.shapes[e.shapeId] ?: continue
            effect(frame, v, e, at)?.let { track(it) }
        }
        if (running.isEmpty()) post { if (running.isEmpty()) onSettled?.invoke() }
    }

    private fun effect(frame: SlideFrame, v: View, e: SlideEffect, at: Long): Animator? {
        val d = e.durationMs.toLong().coerceAtLeast(1)
        val lp = v.layoutParams as LayoutParams
        val r = Rect(lp.leftMargin, lp.topMargin, lp.leftMargin + lp.width, lp.topMargin + lp.height)
        val into = e.kind == SlideEffect.Kind.ENTRANCE
        val out = e.kind == SlideEffect.Kind.EXIT
        val a: Animator = when (e.effect) {
            SlideEffect.Effect.APPEAR -> ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1
                addUpdateListener { if (!out) v.visibility = View.VISIBLE }
            }
            SlideEffect.Effect.FADE, SlideEffect.Effect.OTHER -> ObjectAnimator.ofFloat(v, View.ALPHA, if (out) 1f else 0f, if (out) 0f else 1f)
            SlideEffect.Effect.FLY -> {
                val (x, y) = when (e.direction) {
                    SlideEffect.Direction.LEFT -> -r.right.toFloat() to 0f
                    SlideEffect.Direction.RIGHT -> (frame.slideWidth - r.left).toFloat() to 0f
                    SlideEffect.Direction.TOP -> 0f to -r.bottom.toFloat()
                    SlideEffect.Direction.BOTTOM -> 0f to (frame.slideHeight - r.top).toFloat()
                }
                AnimatorSet().apply {
                    playTogether(ObjectAnimator.ofFloat(v, View.TRANSLATION_X, if (out) 0f else x, if (out) x else 0f),
                        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, if (out) 0f else y, if (out) y else 0f))
                    interpolator = DecelerateInterpolator()
                }
            }
            SlideEffect.Effect.ZOOM -> AnimatorSet().apply {
                val (from, to) = if (out) 1f to 0f else 0f to 1f
                playTogether(ObjectAnimator.ofFloat(v, View.SCALE_X, from, to), ObjectAnimator.ofFloat(v, View.SCALE_Y, from, to), ObjectAnimator.ofFloat(v, View.ALPHA, from, to))
            }
            SlideEffect.Effect.WIPE -> ValueAnimator.ofFloat(0f, 1f).apply {
                val w = r.width(); val h = r.height()
                addUpdateListener {
                    val p = it.animatedValue as Float
                    val shown = if (out) 1 - p else p
                    v.clipBounds = when (e.direction) {
                        SlideEffect.Direction.BOTTOM -> Rect(0, (h * (1 - shown)).toInt(), w, h)
                        SlideEffect.Direction.TOP -> Rect(0, 0, w, (h * shown).toInt())
                        SlideEffect.Direction.LEFT -> Rect(0, 0, (w * shown).toInt(), h)
                        SlideEffect.Direction.RIGHT -> Rect((w * (1 - shown)).toInt(), 0, w, h)
                    }
                }
            }
            SlideEffect.Effect.PULSE -> ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                addUpdateListener { val s = 1f + 0.5f * (it.animatedValue as Float); v.scaleX = s; v.scaleY = s }
            }
        }
        if (e.effect != SlideEffect.Effect.APPEAR) a.duration = d
        a.startDelay = at
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // at rest in its final state
                v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.clipBounds = null
                v.visibility = if (out) View.INVISIBLE else View.VISIBLE
            }
        })
        // an entrance waits in its first state (not seen) until it starts, delayed or not
        if (into && e.effect != SlideEffect.Effect.APPEAR) {
            when (e.effect) {
                SlideEffect.Effect.FADE, SlideEffect.Effect.OTHER -> v.alpha = 0f
                SlideEffect.Effect.ZOOM -> { v.scaleX = 0f; v.scaleY = 0f; v.alpha = 0f }
                SlideEffect.Effect.WIPE -> v.clipBounds = Rect(0, 0, 0, 0)
                SlideEffect.Effect.FLY -> when (e.direction) {
                    SlideEffect.Direction.LEFT -> v.translationX = -r.right.toFloat()
                    SlideEffect.Direction.RIGHT -> v.translationX = (frame.slideWidth - r.left).toFloat()
                    SlideEffect.Direction.TOP -> v.translationY = -r.bottom.toFloat()
                    SlideEffect.Direction.BOTTOM -> v.translationY = (frame.slideHeight - r.top).toFloat()
                }
                else -> Unit
            }
            v.visibility = View.VISIBLE
        }
        return a
    }
}
