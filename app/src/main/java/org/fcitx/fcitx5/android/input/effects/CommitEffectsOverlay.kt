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

    private class Bubble {
        var x = 0f
        var y = 0f
        var text: String = ""
        var vx = 0f
        var vy = 0f
        var phase = 0f
        var radius = 0f
        var color = 0
        var life = 0f
        var maxLife = 1f
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

        private const val MAX_BUBBLES = 24
        private const val DEGRADED_BUBBLES = 12
        private const val BUBBLE_LIFE_MS = 1500f
        private const val BUBBLE_MIN_VY = 175f // px/s upward — climbs noticeably higher
        private const val BUBBLE_MAX_VY = 345f
        private const val BUBBLE_VX = 70f // px/s sideways spread
        private const val SWAY_FREQ = 3.2f // rad/s — wandering drift
        private const val SWAY_AMP = 55f // px/s lateral sway amplitude
        private const val BUBBLE_FONT_SP = 16f
        private const val BUBBLE_PAD_DP = 12f
    }

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private val pool = Array(MAX_PARTICLES) { Particle() }
    private var alive = 0

    private val bubbles = Array(MAX_BUBBLES) { Bubble() }
    private var bubbleAlive = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
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

    /**
     * Spawns a bubble carrying [text] (the just-picked candidate) at the chosen candidate's
     * screen position; it then floats up with a random sideways lean. Triggered by
     * HorizontalCandidateComponent when [EffectMode.Bubble] is selected.
     */
    fun burstBubbleAtScreen(screenX: Float, screenY: Float, text: String) {
        if (text.isBlank()) return
        val loc = intArrayOf(0, 0)
        getLocationOnScreen(loc)
        val cap = if (quality < 0.5f) DEGRADED_BUBBLES else MAX_BUBBLES
        if (bubbleAlive >= cap) return
        val b = bubbles[bubbleAlive++]
        b.x = screenX - loc[0]
        b.y = screenY - loc[1]
        b.text = text
        b.vx = (random.nextFloat() * 2f - 1f) * BUBBLE_VX
        b.vy = -(BUBBLE_MIN_VY + random.nextFloat() * (BUBBLE_MAX_VY - BUBBLE_MIN_VY))
        b.phase = random.nextFloat() * Math.PI.toFloat() * 2f
        b.maxLife = BUBBLE_LIFE_MS * (0.8f + random.nextFloat() * 0.4f)
        b.life = b.maxLife
        b.color = randomBubbleColor()
        bubbleTextPaint.textSize = BUBBLE_FONT_SP * scaledDensity
        val tw = bubbleTextPaint.measureText(text)
        b.radius = tw.coerceAtLeast(bubbleTextPaint.textSize) / 2f + BUBBLE_PAD_DP * density
        startIfNeeded()
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
        bubbleAlive = 0
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun emit(x: Float, y: Float, tier: Int, densityPref: Int) {
        val cap = if (quality < 0.5f) DEGRADED_MAX else MAX_PARTICLES
        val count = ((BASE_COUNT + densityPref * 2 + tier * 2) * quality).toInt()
            .coerceAtLeast(MIN_COUNT)
        // A fresh random soap-bubble hue per burst — natural, never neon, and every
        // committed word gets its own colour.
        val color = randomBubbleColor()
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

    /**
     * Soft, soap-bubble style fill: a fresh random hue with muted saturation and high value,
     * so colours read as airy and natural rather than neon. Each bubble draws its own on spawn.
     */
    private fun randomBubbleColor(): Int {
        val hue = random.nextFloat() * 360f
        val sat = 0.35f + random.nextFloat() * 0.25f // 0.35..0.60 — gentle, not garish
        val value = 0.72f + random.nextFloat() * 0.23f // 0.72..0.95 — bright, see-through
        return Color.HSVToColor(floatArrayOf(hue, sat, value))
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

        if (alive > 0 || bubbleAlive > 0 || now < comboVisibleUntil) {
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
        var bi = 0
        while (bi < bubbleAlive) {
            val b = bubbles[bi]
            b.life -= dtMs
            if (b.life <= 0f) {
                // Pops into a small puff of its own colour at end of life.
                popBubble(b.x, b.y, b.color)
                retireBubble(bi)
                continue
            }
            if (b.y + b.radius < 0f) {
                // Drifted clear off the top of the screen — just retire it.
                retireBubble(bi)
                continue
            }
            b.phase += SWAY_FREQ * dtSec
            b.x += (b.vx + cos(b.phase) * SWAY_AMP) * dtSec
            b.y += b.vy * dtSec
            b.vy *= (1f - 0.22f * dtSec) // gentler drag so it floats higher
            bi++
        }
        tracker.tick(now)
    }

    private fun retireBubble(index: Int) {
        val tmp = bubbles[index]
        bubbles[index] = bubbles[bubbleAlive - 1]
        bubbles[bubbleAlive - 1] = tmp
        bubbleAlive--
    }

    /**
     * A bubble "pops" into a small all-directions puff of its own colour when its life ends,
     * instead of simply fading out. Reuses the particle pool so the fragments fall under the
     * same gravity as everything else.
     */
    private fun popBubble(x: Float, y: Float, color: Int) {
        val cap = if (quality < 0.5f) DEGRADED_MAX else MAX_PARTICLES
        val count = if (quality < 0.5f) 5 else 9
        repeat(count) {
            if (alive >= cap) return
            val p = pool[alive++]
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = 120f + random.nextFloat() * 170f
            p.x = x
            p.y = y
            p.vx = cos(angle) * speed
            p.vy = sin(angle) * speed
            p.maxLife = 320f + random.nextFloat() * 280f
            p.life = p.maxLife
            p.radius = (2f + random.nextFloat() * 3f) * density
            p.color = color
        }
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
        drawBubbles(canvas)
        drawCombo(canvas)
    }

    private fun drawBubbles(canvas: Canvas) {
        // The word inside each bubble inherits the candidate-bar's text colour, so it always
        // matches whatever theme the user is on.
        bubbleTextPaint.color = ThemeManager.activeTheme.candidateTextColor
        for (i in 0 until bubbleAlive) {
            val b = bubbles[i]
            val a = (b.life / b.maxLife).coerceIn(0f, 1f)
            // Stay fully opaque the whole flight — the pop at end of life is the real exit,
            // so no point fading while drifting. Just a tiny tail fade right before it bursts
            // to soften the hand-off into the fragment puff.
            val alpha = if (a > 0.12f) 1f else (a / 0.12f).coerceIn(0f, 1f)
            // Wind-blown wobble: the bubble breathes between a tall and a wide ellipse as it
            // drifts, so it never reads as a rigid circle.
            val wob = sin(b.phase * 1.3f) * 0.14f
            val rx = b.radius * (1f + wob)
            val ry = b.radius * (1f - wob)
            val cx = b.x
            val cy = b.y

            // Translucent film body.
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = b.color
            bubblePaint.alpha = (255 * 0.30f * alpha).toInt().coerceIn(0, 255)
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, bubblePaint)

            // Bright rim — a hairline Fresnel-style edge light. Kept sub-pixel thin on purpose:
            // anything thicker reads as a drawn outline rather than the sheen of a film edge.
            bubblePaint.style = Paint.Style.STROKE
            bubblePaint.alpha = (255 * 0.55f * alpha).toInt().coerceIn(0, 255)
            bubblePaint.strokeWidth = 0.6f * density
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, bubblePaint)

            // Natural lit side: a soft highlight wash top-left, plus a tighter brighter glint,
            // both white at low alpha so they read as a reflection rather than a painted dot.
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = Color.WHITE
            bubblePaint.alpha = (255 * 0.36f * alpha).toInt().coerceIn(0, 255)
            canvas.drawOval(cx - rx * 0.45f, cy - ry * 0.55f,
                cx + rx * 0.08f, cy - ry * 0.12f, bubblePaint)
            bubblePaint.alpha = (255 * 0.60f * alpha).toInt().coerceIn(0, 255)
            canvas.drawOval(cx - rx * 0.50f, cy - ry * 0.62f,
                cx - rx * 0.22f, cy - ry * 0.38f, bubblePaint)

            // The picked candidate word, riding inside the bubble.
            bubbleTextPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            val fm = bubbleTextPaint.fontMetrics
            val ty = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(b.text, cx, ty, bubbleTextPaint)
        }
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
