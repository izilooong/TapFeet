/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input.swipe

import android.graphics.Rect

/**
 * Minimum travel (in dp) before a touch is classified as a swipe rather than a tap/stray touch.
 * Below this the gesture is undecided, so a finger resting on the keyboard surface never fires.
 */
const val SWIPE_SLOP_DP = 24

/** The four swipes the keyboard surface recognises. */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Classify a touch travel (`dx`, `dy` from the gesture's DOWN point, in px) as one of the four
 * swipes, or `null` while it is still under [slopPx] — i.e. while the gesture is undecided.
 *
 * This is THE gesture rule of the feature: [KeyboardFlyTextSelector] acts on exactly this verdict
 * (up = pick the candidate under the finger, down = ignored, left/right = page), and the Lab page's
 * gesture readout uses the same function, so what the Lab page reports is what the keyboard would
 * actually do — no second, drifting copy of the thresholds.
 *
 * The dominant axis decides; a perfect diagonal counts as horizontal (strict `>` on the vertical
 * component), which matches how the selector has always classified them.
 */
fun swipeDirection(dx: Float, dy: Float, slopPx: Float): SwipeDirection? {
    if (kotlin.math.hypot(dx, dy) < slopPx) return null
    if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
        return if (dy < 0f) SwipeDirection.UP else SwipeDirection.DOWN
    }
    return if (dx < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT
}

/**
 * Map an X coordinate on the keyboard surface to a candidate to select.
 *
 * [rects] pairs each visible candidate's on-screen rectangle with the engine selection index to
 * pass to `Fcitx.select` — produced by CandidatesView.candidateScreenRects (floating window) or
 * HorizontalCandidateComponent.flyCandidateRects (candidate bar). Rects live in the SAME absolute
 * screen coordinate space as the touch's [MotionEvent.getRawX], so the comparison is a direct hit
 * test against where each word actually renders. Because the rects follow the candidate bar's real
 * layout (center-justified / wrapped / multi-column), this naturally honours the Microsoft-style
 * centered first pick: sliding toward the center column selects the first candidate.
 *
 * If [x] lands outside every candidate's horizontal span (e.g. a gap between words), fall back to
 * the nearest candidate by center distance so a slightly-off slide still picks the closest word
 * instead of nothing.
 *
 * @return the engine selection index of the hit candidate, or -1 when [rects] is empty (nothing to
 *   choose from).
 */
fun candidateIndexAtX(x: Float, rects: List<Pair<Int, Rect>>): Int {
    if (rects.isEmpty()) return -1
    // Exact horizontal hit: x falls inside a candidate's on-screen column.
    rects.forEach { (idx, r) -> if (x >= r.left && x <= r.right) return idx }
    // Missed every column — pick the nearest candidate center.
    var best = -1
    var bestD = Float.MAX_VALUE
    rects.forEach { (idx, r) ->
        val c = (r.left + r.right) / 2f
        val d = kotlin.math.abs(c - x)
        if (d < bestD) {
            bestD = d
            best = idx
        }
    }
    return best
}
