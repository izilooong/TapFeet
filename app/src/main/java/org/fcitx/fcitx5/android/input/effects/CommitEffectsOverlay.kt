/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.effects.EffectMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import timber.log.Timber

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

    /**
     * The picked candidate word sailing upward after a commit — the "Fly" effect, drawn here
     * instead of living in a throwaway TextView driven by ObjectAnimator: a fixed pixel
     * target flew off the top of tall screens, and a system "remove animations" setting
     * (or a 0x animator scale) silently shortened the animator to nothing.
     */
    private class Flyer {
        var x = 0f
        var startY = 0f
        var rise = 0f
        var text: String = ""
        var life = 0f
        var maxLife = 1f
    }

    companion object {
        private const val MAX_PARTICLES = 96
        private const val DEGRADED_MAX = 48
        private const val BASE_COUNT = 6
        private const val MIN_COUNT = 6
        private const val MIN_LIFE_MS = 700f
        private const val MAX_LIFE_MS = 1100f
        /**
         * Every kinematic below is expressed in **dp** and scaled by [density] where it is
         * used. As raw px/s constants they travelled half as far — visually — on a 3x phone
         * as on a 1.5x one, which is why the same build looked alive on one device and
         * "broken" on another.
         */
        private const val GRAVITY = 520f // dp/s^2
        /** Burst height is a slice of the open space above the launch point, not an absolute. */
        private const val RISE_RATIO = 0.42f
        private const val MIN_RISE_DP = 72f
        private const val MAX_RISE_DP = 300f
        private const val SPEED_JITTER = 0.22f
        private const val SPREAD = 1.2f // radians, tight upward jet
        /** Frames an emission may wait for the first layout before it gives up on this burst. */
        private const val MAX_EMIT_RETRIES = 3
        /**
         * How long the frame loop may go silent before it is assumed dead. A burst lasts at
         * most ~1.5s, so anything beyond this means the callback chain was lost rather than
         * that the effect simply finished.
         */
        private const val STALE_LOOP_MS = 1000L
        private const val COMBO_SHOW_MS = 1200L
        private const val COMBO_FADE_MS = 400f
        private const val FRAME_SAMPLES = 30

        private const val MAX_BUBBLES = 24
        private const val DEGRADED_BUBBLES = 12
        private const val BUBBLE_LIFE_MS = 1500f
        /** A dragged bubble covers this multiple of its launch speed over [BUBBLE_LIFE_MS]. */
        private const val BUBBLE_DIST_FACTOR = 1.28f
        private const val BUBBLE_VX = 70f // dp/s sideways spread
        private const val SWAY_FREQ = 3.2f // rad/s — wandering drift
        private const val SWAY_AMP = 55f // dp/s lateral sway amplitude
        private const val BUBBLE_FONT_SP = 16f
        private const val BUBBLE_PAD_DP = 12f

        private const val MAX_FLYERS = 12
        private const val FLY_LIFE_MS = 620f
        private const val FLY_FONT_SP = 20f
        private const val FLY_RISE_RATIO = 0.55f
        private const val FLY_MIN_RISE_DP = 88f
    }

    private val density = context.resources.displayMetrics.density
    // `DisplayMetrics.scaledDensity` is deprecated (API 34). Applying one sp through the public
    // TypedValue API yields the identical sp→px factor, so every `* scaledDensity` call site below
    // keeps working unchanged.
    private val scaledDensity = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics
    )

    private val pool = Array(MAX_PARTICLES) { Particle() }
    private var alive = 0

    private val bubbles = Array(MAX_BUBBLES) { Bubble() }
    private var bubbleAlive = 0

    private val flyers = Array(MAX_FLYERS) { Flyer() }
    private var flyerAlive = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val flyerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
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
    /** Wall-clock stamp of the last delivered frame; drives the stale-loop watchdog. */
    private var lastDoFrameMs = 0L
    /**
     * True exactly while a Choreographer frame callback is genuinely outstanding. This is what
     * was previously conflated with [running]: `running` means "there are particles to animate",
     * this means "a callback is actually posted". When the IME window detaches the *callback* is
     * silently dropped by the framework while `running` stays true — the two diverging is the bug.
     * Tracking them separately lets the loop re-seed idempotently without ever double-posting.
     */
    private var pendingCallback = false
    private val handler = Handler(Looper.getMainLooper())
    /** True while the [watchdogTask] is scheduled. */
    private var watchdogScheduled = false
    /** Retries left for an emission that lands before the first layout. */
    private var retryLeft = MAX_EMIT_RETRIES

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
        // CandidatesView is added to the same parent *after* us, so without an explicit
        // elevation it would win the z-order and clip the first half of every burst.
        elevation = 1000f
    }

    /**
     * Choreographer is thread-local: a frame callback posted from anywhere but the main thread
     * lands on *that* thread's Choreographer, which never ticks inside an IME window. The loop
     * would then sit at running=true forever and every later burst gets silently dropped — the
     * classic "effects suddenly stopped working, and never came back" report. Re-dispatch and
     * let the real work happen on the main thread instead of touching views from here.
     */
    private fun onMainThread(what: String, rerun: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        Timber.w("effects: %s called off the main thread, re-posting", what)
        post { rerun() }
        return false
    }

    fun onCommit(text: String) {
        if (!onMainThread("onCommit") { onCommit(text) }) return
        val prefs = AppPrefs.getInstance()
        if (prefs.advanced.disableAnimation.getValue()) {
            Timber.d("effects: skipped by disableAnimation")
            return
        }
        val effects = prefs.effects
        if (!effects.enabled.getValue() || effects.mode.getValue() != EffectMode.Particles) {
            Timber.d(
                "effects: skipped (enabled=%b mode=%s)",
                effects.enabled.getValue(), effects.mode.getValue()
            )
            return
        }

        val now = SystemClock.uptimeMillis()
        tracker.onCommit(text, now)

        // The burst depends on the master switch and Particles mode only; the combo counter
        // is an independent overlay governed by its own switch. Turning the counter off must
        // not swallow the particles — that coupling made particles silently vanish whenever
        // comboMeter was disabled.
        emit(burstX(), burstY(), tracker.tier, effects.particleDensity.getValue())
        Timber.d("effects: burst %s", text)
        if (effects.comboMeter.getValue()) {
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
     * Sails the picked candidate word upward from where it was chosen — the "Fly" effect.
     * Drawn on this overlay rather than on a throwaway TextView: see [Flyer].
     */
    fun flyTextAtScreen(screenX: Float, screenY: Float, text: String) {
        if (!onMainThread("flyText") { flyTextAtScreen(screenX, screenY, text) }) return
        // The overlay is MATCH_PARENT on the content view: a collapsed (0-height) IME window would
        // make every effect draw into nothing, which is indistinguishable from "the effect is off".
        Timber.d(
            "effects: flyer spawn text=%s screen=(%.0f,%.0f) overlay=%dx%d flyers=%d/%d",
            text, screenX, screenY, width, height, flyerAlive, MAX_FLYERS
        )
        if (text.isBlank() || flyerAlive >= MAX_FLYERS) {
            Timber.d("effects: flyer DROPPED (blank=%b flyerAlive=%d)", text.isBlank(), flyerAlive)
            return
        }
        val loc = intArrayOf(0, 0)
        getLocationOnScreen(loc)
        val y = screenY - loc[1]
        val f = flyers[flyerAlive++]
        f.x = screenX - loc[0]
        f.startY = y
        f.text = text
        f.rise = (y * FLY_RISE_RATIO).coerceAtLeast(FLY_MIN_RISE_DP * density)
        f.maxLife = FLY_LIFE_MS
        f.life = f.maxLife
        startIfNeeded()
    }

    /**
     * Spawns a bubble carrying [text] (the just-picked candidate) at the chosen candidate's
     * screen position; it then floats up with a random sideways lean. Triggered by
     * HorizontalCandidateComponent when [EffectMode.Bubble] is selected.
     */
    fun burstBubbleAtScreen(screenX: Float, screenY: Float, text: String) {
        if (!onMainThread("bubble") { burstBubbleAtScreen(screenX, screenY, text) }) return
        if (text.isBlank()) return
        val loc = intArrayOf(0, 0)
        getLocationOnScreen(loc)
        val cap = if (quality < 0.5f) DEGRADED_BUBBLES else MAX_BUBBLES
        if (bubbleAlive >= cap) return
        val y = screenY - loc[1]
        val b = bubbles[bubbleAlive++]
        b.x = screenX - loc[0]
        b.y = y
        b.text = text
        b.vx = (random.nextFloat() * 2f - 1f) * BUBBLE_VX * density
        b.vy = -(riseFor(y) / BUBBLE_DIST_FACTOR) * (0.85f + random.nextFloat() * 0.3f)
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
        val h = height.toFloat()
        val cv = candidatesView
        val y = if (cv != null && cv.isShown && cv.height > 0) cv.y else h * 0.9f
        // Bursts need headroom. A candidate bar parked near the top of the window leaves
        // nothing to climb into, so fall back to the bottom rather than firing upward into
        // the void from y≈0.
        return if (y > h * 0.22f) y else h * 0.9f
    }

    /**
     * How far an effect should travel upward: a slice of the space above the launch point,
     * clamped to a dp band. A fixed pixel distance is invisible on a tall display and sails
     * off the top of a short one — the two faces of the same "it does nothing on my phone"
     * report.
     */
    private fun riseFor(y: Float): Float =
        (y * RISE_RATIO).coerceIn(MIN_RISE_DP * density, MAX_RISE_DP * density)

    fun release() {
        running = false
        pendingCallback = false
        alive = 0
        bubbleAlive = 0
        flyerAlive = 0
        lastFrameNs = 0L
        // Zeroing this is what makes the staleness test correct: a released overlay
        // must look stale even if the callback chain was cut mid-flight (which is what happens when
        // the view is detached rather than drained). Otherwise a stale-but-fresh timestamp would
        // make the next burst silently drop itself.
        lastDoFrameMs = 0L
        cancelWatchdog()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /**
     * Posts a single frame callback, idempotently: if one is already outstanding we do nothing,
     * so the chain can never be doubled. Callers rely on this to re-seed a lost chain without
     * risking two concurrent chains (which would animate particles at 2x speed).
     */
    private fun armLoop() {
        if (pendingCallback) return
        pendingCallback = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private val watchdogTask = Runnable { checkAlive() }

    /**
     * Independent of any commit: while the loop is "running" but no frame has been delivered for
     * [STALE_LOOP_MS], the outstanding callback was lost (window detach / process backgrounded)
     * and must be re-seeded. This is the safety net that makes recovery no longer depend on the
     * user pausing for >1s and then typing again.
     */
    private fun checkAlive() {
        watchdogScheduled = false
        if (!running) return
        val sinceLast = SystemClock.uptimeMillis() - lastDoFrameMs
        if (sinceLast > STALE_LOOP_MS) {
            Choreographer.getInstance().removeFrameCallback(this)
            pendingCallback = false
            armLoop()
        }
        scheduleWatchdog()
    }

    private fun scheduleWatchdog() {
        if (watchdogScheduled || !running) return
        watchdogScheduled = true
        handler.postDelayed(watchdogTask, STALE_LOOP_MS)
    }

    private fun cancelWatchdog() {
        if (!watchdogScheduled) return
        watchdogScheduled = false
        handler.removeCallbacks(watchdogTask)
    }

    /**
     * Revives the loop the moment the IME window is shown again. The previous callback may or may
     * not have survived the detach; cancelling then re-posting guarantees exactly one live callback
     * either way, so effects resume instantly instead of waiting on a typing gap. Driven both from
     * the service's [org.fcitx.fcitx5.android.input.FcitxInputMethodService.onWindowShown] and from
     * this view's own [onWindowVisibilityChanged].
     */
    fun resumeIfNeeded() {
        if (!running) return
        Choreographer.getInstance().removeFrameCallback(this)
        pendingCallback = false
        armLoop()
        scheduleWatchdog()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resumeIfNeeded()
    }

    private fun emit(x: Float, y: Float, tier: Int, densityPref: Int) {
        // Firing before the very first layout would launch from (0,0) and the whole burst
        // would sail off-screen unseen — on some devices that is exactly the first commit.
        if (width <= 0 || height <= 0) {
            if (retryLeft > 0) {
                retryLeft--
                post { emit(x, y, tier, densityPref) }
            }
            return
        }
        retryLeft = MAX_EMIT_RETRIES
        // An anchor measured before layout (or a bar that never reported one) reads as 0;
        // recompute it now that the window has a size.
        val ox = if (x > 0f) x else anchorX()
        val oy = if (y > 0f) y else launchY()
        val cap = if (quality < 0.5f) DEGRADED_MAX else MAX_PARTICLES
        val count = ((BASE_COUNT + densityPref * 2 + tier * 2) * quality).toInt()
            .coerceAtLeast(MIN_COUNT)
        // A fresh random hue per burst — natural, never neon, and every committed word gets
        // its own colour. Particles cannot reuse the bubbles' pale pastels (they'd vanish on
        // a light candidate bar), so they get their own mid-value palette.
        val color = randomParticleColor()
        val minRadius = 2.5f * density
        val maxRadius = 6f * density
        // Launch speed is derived from how high the burst should climb, so it coasts to a
        // halt near the top of its arc instead of overshooting a short window.
        val rise = riseFor(oy)
        val baseSpeed = sqrt(2f * GRAVITY * density * rise)
        repeat(count) {
            if (alive >= cap) return
            val p = pool[alive++]
            val angle = -Math.PI.toFloat() / 2f + (random.nextFloat() - 0.5f) * SPREAD
            val speed = baseSpeed * (1f - SPEED_JITTER + random.nextFloat() * 2f * SPEED_JITTER)
            p.x = ox
            p.y = oy
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

    /**
     * Particle colour: random hue like the bubbles, but a solid filled dot cannot borrow the
     * bubble's airy high-value pastels — on a light candidate bar / input field they vanish.
     * Richer saturation and a mid value keep the burst visible on both light and dark ground.
     */
    private fun randomParticleColor(): Int {
        val hue = random.nextFloat() * 360f
        val sat = 0.5f + random.nextFloat() * 0.25f // 0.50..0.75 — vivid enough to read at 3-6dp
        val value = 0.4f + random.nextFloat() * 0.25f // 0.40..0.65 — mid, pops on light and dark
        return Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) {
            pendingCallback = false
            return
        }
        // The callback that invoked us has now been consumed; clear the flag so [armLoop] can
        // re-post a fresh one (and so the watchdog can tell a lost callback from a live one).
        pendingCallback = false
        val dtMs = if (lastFrameNs == 0L) 16.7f
        else ((frameTimeNanos - lastFrameNs) / 1_000_000f).coerceIn(1f, 50f)
        lastFrameNs = frameTimeNanos

        val now = SystemClock.uptimeMillis()
        lastDoFrameMs = now
        step(dtMs, now)
        recordFrame(dtMs)
        invalidate()

        if (alive > 0 || bubbleAlive > 0 || flyerAlive > 0 || now < comboVisibleUntil) {
            armLoop()
        } else {
            running = false
            pendingCallback = false
            cancelWatchdog()
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
            p.vy += GRAVITY * density * dtSec
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
            b.x += (b.vx + cos(b.phase) * SWAY_AMP * density) * dtSec
            b.y += b.vy * dtSec
            b.vy *= (1f - 0.22f * dtSec) // gentler drag so it floats higher
            bi++
        }
        var fi = 0
        while (fi < flyerAlive) {
            flyers[fi].life -= dtMs
            if (flyers[fi].life <= 0f) retireFlyer(fi) else fi++
        }
        tracker.tick(now)
    }

    private fun retireFlyer(index: Int) {
        val tmp = flyers[index]
        flyers[index] = flyers[flyerAlive - 1]
        flyers[flyerAlive - 1] = tmp
        flyerAlive--
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
            val speed = (120f + random.nextFloat() * 170f) * density
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
        drawFlyers(canvas)
        drawCombo(canvas)
    }

    private fun drawFlyers(canvas: Canvas) {
        if (flyerAlive == 0) return
        // Same colour rule as the old fly view: ride the candidate bar's own text colour so
        // the word stays legible on whatever theme is active.
        flyerPaint.color = ThemeManager.activeTheme.candidateTextColor
        flyerPaint.textSize = FLY_FONT_SP * scaledDensity
        for (i in 0 until flyerAlive) {
            val f = flyers[i]
            val progress = 1f - (f.life / f.maxLife).coerceIn(0f, 1f)
            // Ease out: leaves the candidate bar briskly, then settles at the top of the arc.
            val eased = 1f - (1f - progress) * (1f - progress)
            val alpha = if (progress < 0.75f) 1f else ((1f - progress) / 0.25f).coerceIn(0f, 1f)
            flyerPaint.alpha = (255 * alpha).toInt()
            canvas.drawText(f.text, f.x, f.startY - f.rise * eased, flyerPaint)
        }
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
        if (running && pendingCallback) {
            // Loop is genuinely alive: a callback is outstanding and we are mid-animation.
            return
        }
        // Either the loop ended naturally (running==false) or its callback was lost while
        // running (window detach / process backgrounded, leaving running==true with nothing
        // actually pending). The old code trusted a "silent <1s" shortcut here and skipped
        // re-seeding, which left running stuck true forever — the reported "effects suddenly
        // stop and only recover after a restart / IME switch" bug. We now always re-seed,
        // relying on [armLoop]'s idempotency to never double the chain.
        if (running && !pendingCallback) {
            Timber.w("effects: frame loop was dead while running, re-seeding it")
        }
        Choreographer.getInstance().removeFrameCallback(this)
        running = true
        armLoop()
        scheduleWatchdog()
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
        // Thresholds keep clear of a healthy 60Hz cadence (~16.7ms): sitting right on top of
        // it made ordinary 60Hz devices look like they were dropping frames, throttling the
        // burst down to a handful of dots — i.e. an effect that appeared to do nothing.
        quality = when {
            avg > 22f -> 0.55f
            avg > 18.5f -> 0.75f
            avg < 16.9f -> (quality + 0.1f).coerceAtMost(1f)
            else -> quality
        }
    }
}
