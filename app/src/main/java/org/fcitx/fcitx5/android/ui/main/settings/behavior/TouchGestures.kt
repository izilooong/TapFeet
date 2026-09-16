/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.view.InputDevice
import android.view.MotionEvent
import org.fcitx.fcitx5.android.input.TouchProbeLog
import org.fcitx.fcitx5.android.input.swipe.SwipeDirection
import org.fcitx.fcitx5.android.input.swipe.swipeDirection
import kotlin.math.hypot

/**
 * Turns the Lab page's raw [TouchProbeLog] backlog into **gestures**, and judges each one with the
 * very same rule the feature uses ([swipeDirection]).
 *
 * Why this exists: the Lab page used to answer "coordinates are arriving" (a trail on a canvas) but
 * not "the keyboard would have acted on that swipe" — and those are different questions. A swipe can
 * arrive perfectly yet never fire, because it stayed under the slop threshold, or because the
 * dominant axis was not the one the user meant, or because the samples came from the *screen*
 * (dev=7 / `SOURCE_TOUCHSCREEN`) instead of the keyboard surface (dev=6 / `SOURCE_TOUCHPAD`). Shared
 * by [TouchTrailView] (drawing) and [InputMethodTestFragment] (the read-out), so both describe the
 * same strokes, and by construction the read-out cannot drift from the shipped thresholds.
 */

/** One gesture: the samples from a DOWN to its UP/CANCEL — or, if none arrived, everything since. */
internal class TouchStroke(
    val points: List<TouchProbeLog.Entry>,
    val cancelled: Boolean,
    val ongoing: Boolean,
)

/**
 * Splits the probe backlog into one stroke per gesture. [TouchProbeLog.snapshot] is newest-first, so
 * it is walked in reverse to rebuild chronological order.
 */
internal fun buildTouchStrokes(entries: List<TouchProbeLog.Entry>): List<TouchStroke> {
    val strokes = ArrayList<TouchStroke>()
    var current: MutableList<TouchProbeLog.Entry>? = null
    for (entry in entries.asReversed()) {
        when (entry.action) {
            MotionEvent.ACTION_DOWN -> {
                current?.let { if (it.size > 1) strokes.add(TouchStroke(it, false, true)) }
                current = ArrayList<TouchProbeLog.Entry>(96).apply { add(entry) }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val open = current
                if (open != null) {
                    open.add(entry)
                    strokes.add(TouchStroke(open, entry.action == MotionEvent.ACTION_CANCEL, false))
                    current = null
                }
            }

            else -> current?.add(entry)
        }
    }
    current?.let { if (it.size > 1) strokes.add(TouchStroke(it, false, true)) }
    return strokes
}

/**
 * What a stroke amounts to, in the terms the feature cares about.
 *
 * [direction] is the verdict at the moment the gesture first travelled past the threshold — the same
 * instant [org.fcitx.fcitx5.android.input.swipe.KeyboardFlyTextSelector] acts on — and `null` when
 * the stroke never got that far (a tap, a stray brush, or a swipe that was too short for the
 * feature, which is exactly the case the read-out has to make visible). [travelPx] is the travel at
 * that instant, or the furthest the finger got when no verdict fired.
 */
internal class SwipeVerdict(
    val direction: SwipeDirection?,
    val travelPx: Float,
    val durationMs: Long,
    val peakPxPerSec: Float,
    val samples: Int,
    val keyboardSurface: Boolean,
    val deviceId: Int,
)

/** Per-direction gesture tally of the keyboard-surface strokes ("did it see my swipes, and how"). */
internal class GestureCounts(
    var up: Int = 0,
    var down: Int = 0,
    var left: Int = 0,
    var right: Int = 0,
    var belowThreshold: Int = 0,
) {
    fun add(direction: SwipeDirection?) {
        when (direction) {
            SwipeDirection.UP -> up++
            SwipeDirection.DOWN -> down++
            SwipeDirection.LEFT -> left++
            SwipeDirection.RIGHT -> right++
            null -> belowThreshold++
        }
    }
}

/** The Lab page's whole gesture read-out: the newest gesture, plus the surface tally behind it. */
internal class GestureReport(
    val latest: SwipeVerdict?,
    val counts: GestureCounts,
)

/**
 * Judge a single stroke. Replays the stroke sample by sample instead of comparing its endpoints:
 * the feature fires at the FIRST sample past the threshold, so a swipe that reverses (up then back
 * down) is an up-swipe to the keyboard even though its net displacement is ~0 — reporting the net
 * displacement would call that "nothing" and send the user chasing a bug that is not there.
 */
internal fun swipeVerdict(stroke: TouchStroke, slopPx: Float): SwipeVerdict? {
    val points = stroke.points
    if (points.size < 2) return null
    val first = points.first()

    var direction: SwipeDirection? = null
    var travelAtVerdict = 0f
    var furthest = 0f
    var peak = 0f

    for (i in 1 until points.size) {
        val p = points[i]
        val travel = hypot(p.rawX - first.rawX, p.rawY - first.rawY)
        if (travel > furthest) furthest = travel
        val dt = p.time - points[i - 1].time
        if (dt > 0) {
            val v = hypot(p.rawX - points[i - 1].rawX, p.rawY - points[i - 1].rawY) * 1000f / dt
            if (v > peak) peak = v
        }
        if (direction == null) {
            val verdict = swipeDirection(p.rawX - first.rawX, p.rawY - first.rawY, slopPx)
            if (verdict != null) {
                direction = verdict
                travelAtVerdict = travel
            }
        }
    }

    return SwipeVerdict(
        direction = direction,
        travelPx = if (direction != null) travelAtVerdict else furthest,
        durationMs = points.last().time - first.time,
        peakPxPerSec = peak,
        samples = points.size,
        // `==`, not a bit test: every pointer source shares class bit 0x2, so `!= 0` would label a
        // plain touchscreen as a touchpad (see TouchProbeLog.Entry.sourceName).
        keyboardSurface = first.source == InputDevice.SOURCE_TOUCHPAD,
        deviceId = first.deviceId,
    )
}

/**
 * The newest gesture plus the keyboard-surface tally. Strokes from other sources (a finger on the
 * display, pointer-mode motion) are deliberately NOT counted: mixing them in would make "it saw 6 of
 * my 6 swipes" unreadable, which is the one number this panel exists to produce.
 */
internal fun buildGestureReport(entries: List<TouchProbeLog.Entry>, slopPx: Float): GestureReport {
    val strokes = buildTouchStrokes(entries)
    val counts = GestureCounts()
    var latest: SwipeVerdict? = null
    strokes.forEach { stroke ->
        val verdict = swipeVerdict(stroke, slopPx) ?: return@forEach
        latest = verdict
        if (verdict.keyboardSurface) counts.add(verdict.direction)
    }
    return GestureReport(latest, counts)
}
