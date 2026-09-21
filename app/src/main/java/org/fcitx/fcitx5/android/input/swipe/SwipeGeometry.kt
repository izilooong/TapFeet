/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input.swipe

import android.graphics.Rect

/**
 * Gesture tuning for the keyboard-surface fly-text feature. ALL thresholds live here so the
 * selector and the Lab page's gesture read-out share one rule — there must be no second, drifting
 * copy of these numbers or of the dominant-axis logic.
 */

/**
 * Below this travel (dp, from the gesture's DOWN point) a touch is "nothing yet": a finger resting
 * on the keyboard surface, a micro-adjustment while typing. The selector uses this only to *lock* a
 * tentative direction early; it never fires here — the higher per-direction slops below do.
 */
const val SWIPE_BASE_SLOP_DP = 16f

/**
 * Up-swipe picks a candidate (commits it to the input — irreversible), so it demands the most
 * deliberate input: the finger must travel this far *vertically* before it counts. High on purpose —
 * a slight brush that drifts upward must not select a word.
 */
const val SWIPE_UP_SLOP_DP = 40f

/**
 * Left/right swipe pages the candidate strip (reversible, but disruptive), so a moderate threshold —
 * above the old flat 24dp so an accidental horizontal graze no longer pages.
 */
const val SWIPE_PAGE_SLOP_DP = 32f

/**
 * A swipe only commits to a direction when that axis clearly outweighs the other (≈34° off-axis).
 * Inside the deadzone between [SWIPE_AXIS_RATIO] and its reciprocal the gesture is "ambiguous" and
 * ignored — this is what stops a sloppy horizontal swipe from being hijacked into an up-select (and
 * vice versa). A perfect diagonal therefore lands in the deadzone rather than being forced horizontal.
 */
const val SWIPE_AXIS_RATIO = 1.5f

/**
 * Fly-text sensitivity (percent, [org.fcitx.fcitx5.android.data.prefs.AppPrefs] 50–150) → the
 * scale every swipe slop is multiplied by. 100 = untouched thresholds; 50 doubles every travel
 * requirement (harder to trigger); 150 shrinks them by a third (more responsive). Callers apply
 * it by passing `density * flyTextSensitivityScale(pct)` — since every slop is linear in density,
 * scaling density scales all of them at once, and the Lab page passes the same product so the
 * read-out cannot drift from what the keyboard actually does.
 */
fun flyTextSensitivityScale(sensitivityPct: Int): Float = 100f / sensitivityPct

/** The four swipes the keyboard surface recognises. */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Tentative direction: the axis the gesture has clearly committed to, decided as soon as the travel
 * passes [SWIPE_BASE_SLOP_DP] with a dominant axis ≥ [SWIPE_AXIS_RATIO]× the cross axis. Returns
 * `null` while undecided (below the base slop or inside the deadzone).
 *
 * The selector locks this as the gesture's direction the instant it is non-null, so a wobble later
 * in the stroke cannot reclassify an up into a page or vice versa; it is NOT the fire decision —
 * [swipeDirection] adds the higher per-direction commit slop on top.
 */
fun swipeAxis(dx: Float, dy: Float, density: Float): SwipeDirection? {
    val ax = kotlin.math.abs(dx)
    val ay = kotlin.math.abs(dy)
    if (kotlin.math.hypot(ax, ay) < SWIPE_BASE_SLOP_DP * density) return null
    if (ay >= ax * SWIPE_AXIS_RATIO) return if (dy < 0f) SwipeDirection.UP else SwipeDirection.DOWN
    if (ax >= ay * SWIPE_AXIS_RATIO) return if (dx < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT
    return null
}

/**
 * Committed direction: like [swipeAxis] but additionally requires the dominant-axis travel to clear
 * the per-direction commit slop ([SWIPE_UP_SLOP_DP] / [SWIPE_PAGE_SLOP_DP]). This is THE gesture rule
 * the feature fires on, and what the Lab page's read-out reports, so the read-out cannot drift from
 * the shipped thresholds. `null` means "not a swipe yet / ambiguous / too short to act on".
 */
fun swipeDirection(dx: Float, dy: Float, density: Float): SwipeDirection? {
    val dir = swipeAxis(dx, dy, density) ?: return null
    val slopPx = when (dir) {
        SwipeDirection.UP, SwipeDirection.DOWN -> SWIPE_UP_SLOP_DP * density
        SwipeDirection.LEFT, SwipeDirection.RIGHT -> SWIPE_PAGE_SLOP_DP * density
    }
    val travel = when (dir) {
        SwipeDirection.UP, SwipeDirection.DOWN -> kotlin.math.abs(dy)
        SwipeDirection.LEFT, SwipeDirection.RIGHT -> kotlin.math.abs(dx)
    }
    return if (travel >= slopPx) dir else null
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
