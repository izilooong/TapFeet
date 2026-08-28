/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.effects.EffectMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen overlay bolted straight onto the service's content view: bursts a puff of
 * particles near the candidate bar whenever text is committed, and shows a combo counter while
 * typing stays hot.
 *
 * It deliberately lives on the content view rather than inside [org.fcitx.fcitx5.android.input.InputView]
 * — InputView is GONE in hardware-keyboard mode (the Q25's whole point) and gets recreated on
 * theme changes. Nothing here rebuilds on either event.
 */
class CommitEffectsOverlay(context: Context) : View(context), Choreographer.FrameCallback {

    private class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f
        var maxLife = 1f
        var radius = 0f
        var color = 0
    }

    companion object {
        private const val MAX_PARTICLES = 96
        private const val DEGRADED_MAX = 48
        private const val BASE_COUNT = 6
        private const val MIN_COUNT = 4
        private const val MIN_LIFE_MS = 700f
        private const val MAX_LIFE_MS = 1100f
        private const val GRAVITY = 520f // px/s^2 — gentle, long visible climb
        private const val MIN_SPEED = 340f // px/s — strong upward launch
        private const val MAX_SPEED = 720f
        private const val SPREAD = 1.2f // radians, tight upward jet
        private const val COMBO_SHOW_MS = 1200L
        private const val COMBO_FADE_MS = 400f
        private const val FRAME_SAMPLES = 30
    }

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private val pool = Array(MAX_PARTICLES) { Particle() }
    private var alive = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val tracker = ComboTracker()
    private val random = Random(SystemClock.uptimeMillis())

    private var running = false
    private var lastFrameNs = 0L

    private var candidatesView: View? = null

    private var comboX = 0f
    private var comboY = 0f
    private var comboVisibleUntil = 0L

    private val frameTimes = FloatArray(FRAME_SAMPLES)
    private var frameIndex = 0
    private var frameFilled = 0
    private var quality = 1f

    init {
        // Never eat touches: the keyboard underneath must keep working.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun onCommit(text: String) {
        val prefs = AppPrefs.getInstance()
        if (prefs.advanced.disableAnimation.getValue()) return
        val effects = prefs.effects
        if (!effects.enabled.getValue() || effects.mode.getValue() != EffectMode.Particles) return
        val combo = effects.comboMeter.getValue()
        if (!combo) return

        val now = SystemClock.uptimeMillis()
        tracker.onCommit(text, now)

        // Reaching here means effects.enabled && mode == Particles, so burst unconditionally.
        emit(burstX(), burstY(), tracker.tier, effects.particleDensity.getValue())
        if (combo) {
            comboX = burstX()
            comboY = burstY()
            comboVisibleUntil = now + COMBO_SHOW_MS
        }
        burstFresh = false
        startIfNeeded()
    }

    fun setCandidatesView(view: View?) {
        candidatesView = view
    }

    // Centre of the just-picked candidate, in this overlay's coordinate space. Set by
    // HorizontalCandidateComponent right before a word commits, so the burst erupts where the
    // user actually tapped/selected. burstFresh marks it valid for the next onCommit only.
    private var burstX = 0f
    private var burstY = 0f
    private var burstFresh = false

    /**
     * Mirrors how the candidate fly animation locates a word: the selected candidate's
     * screen-centre is converted into this overlay's own space (it sits at the content view's
     * origin) so particles erupt exactly on the picked candidate.
     */
    fun setBurstAtScreen(screenX: Float, screenY: Float) {
        val loc = intArrayOf(0, 0)
        getLocationOnScreen(loc)
        burstX = screenX - loc[0]
        burstY = screenY - loc[1]
        burstFresh = true
    }

    private fun burstX() = if (burstFresh) burstX else anchorX()

    private fun burstY() = if (burstFresh) burstY else launchY()

    /** Bursts always launch from the horizontal centre so they never land in a corner. */
    private fun anchorX() = width * 0.5f

    /**
     * Burst base sits on the *top edge* of the candidate bar. Particles rise from there into
     * the open area above the bar — crucially, above the bar's own z-order so they are not
     * clipped by it. In hardware-keyboard mode the IME window is full-height (see
     * onEvaluateInputViewShown), so there is plenty of room to climb. With no bar, launch from
     * near the bottom of the window.
     */
    private fun launchY(): Float {
        val cv = candidatesView
        return if (cv != null && cv.isShown && cv.height > 0) {
            cv.y
        } else {
            height * 0.9f
        }
    }

    fun release() {
        running = false
        alive = 0
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun emit(x: Float, y: Float, tier: Int, densityPref: Int) {
        val cap = if (quality < 0.5f) DEGRADED_MAX else MAX_PARTICLES
        val count = ((BASE_COUNT + densityPref * 2 + tier * 2) * quality).toInt()
            .coerceAtLeast(MIN_COUNT)
        val color = tintFor(tier)
        val minRadius = 2.5f * density
        val maxRadius = 6f * density
        repeat(count) {
            if (alive >= cap) return
            val p = pool[alive++]
            val angle = -Math.PI.toFloat() / 2f + (random.nextFloat() - 0.5f) * SPREAD
            val speed = MIN_SPEED + random.nextFloat() * (MAX_SPEED - MIN_SPEED)
            p.x = x
            p.y = y
            p.vx = cos(angle) * speed
            p.vy = sin(angle) * speed
            p.maxLife = MIN_LIFE_MS + random.nextFloat() * (MAX_LIFE_MS - MIN_LIFE_MS)
            p.life = p.maxLife
            p.radius = minRadius + random.nextFloat() * (maxRadius - minRadius)
            p.color = color
        }
    }

    /**
     * Hue is pinned per tier (cold -> hot) while saturation and value ride on the active
     * theme, so bursts get visibly hotter as you speed up yet still belong to the theme.
     */
    private fun tintFor(tier: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(ThemeManager.activeTheme.accentKeyBackgroundColor, hsv)
        val hue = when (tier) {
            0 -> 200f
            1 -> 160f
            2 -> 42f
            else -> 14f
        }
        return Color.HSVToColor(
            floatArrayOf(
                hue,
                (hsv[1] + 0.25f).coerceAtMost(1f),
                (hsv[2] + 0.15f).coerceAtMost(1f)
            )
        )
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dtMs = if (lastFrameNs == 0L) 16.7f
        else ((frameTimeNanos - lastFrameNs) / 1_000_000f).coerceIn(1f, 50f)
        lastFrameNs = frameTimeNanos

        val now = SystemClock.uptimeMillis()
        step(dtMs, now)
        recordFrame(dtMs)
        invalidate()

        if (alive > 0 || now < comboVisibleUntil) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
            lastFrameNs = 0L
        }
    }

    private fun step(dtMs: Float, now: Long) {
        val dtSec = dtMs / 1000f
        var i = 0
        while (i < alive) {
            val p = pool[i]
            p.life -= dtMs
            if (p.life <= 0f) {
                // swap-remove: particle draw order is irrelevant
                val tmp = pool[i]
                pool[i] = pool[alive - 1]
                pool[alive - 1] = tmp
                alive--
                continue
            }
            p.vy += GRAVITY * dtSec
            p.x += p.vx * dtSec
            p.y += p.vy * dtSec
            i++
        }
        tracker.tick(now)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in 0 until alive) {
            val p = pool[i]
            val t = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.color = p.color
            paint.alpha = (255 * t * t).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.radius * (0.35f + 0.65f * t), paint)
        }
        drawCombo(canvas)
    }

    private fun drawCombo(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now >= comboVisibleUntil || tracker.combo < 2) return
        val fade = ((comboVisibleUntil - now) / COMBO_FADE_MS).coerceAtMost(1f)
        val left = comboX + 8f * density
        val top = comboY - 10f * density

        textPaint.color = tintFor(tracker.tier)
        textPaint.alpha = (255 * fade).toInt()
        textPaint.textSize = 20f * scaledDensity * (1f + 0.12f * tracker.tier)
        canvas.drawText("×${tracker.combo}", left, top, textPaint)

        textPaint.textSize = 12f * scaledDensity
        textPaint.alpha = (200 * fade).toInt()
        canvas.drawText("${tracker.cpm} CPM", left, top + 14f * scaledDensity, textPaint)
    }

    private fun startIfNeeded() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * Real frame intervals rather than measured draw cost — Choreographer hands us the actual
     * presentation timestamps, so a sustained gap above ~17.5ms means we are dropping frames
     * on this device and should thin the burst out.
     */
    private fun recordFrame(dtMs: Float) {
        frameTimes[frameIndex] = dtMs
        frameIndex = (frameIndex + 1) % FRAME_SAMPLES
        if (frameFilled < FRAME_SAMPLES) {
            frameFilled++
            return
        }
        if (frameIndex != 0) return
        val avg = frameTimes.average().toFloat()
        quality = when {
            avg > 22f -> 0.4f
            avg > 17.5f -> 0.7f
            avg < 16.9f -> (quality + 0.1f).coerceAtMost(1f)
            else -> quality
        }
    }
}
