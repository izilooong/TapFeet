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
 * Cursor-move mode (no candidates on screen): a four-way swipe drives the caret. Deliberately the
 * LONGEST threshold of the three — well above [SWIPE_UP_SLOP_DP] and [SWIPE_PAGE_SLOP_DP] — and
 * now also above TWO KEY WIDTHS on the keyboard surface. The driver's single-pointer stream
 * reports the CENTROID of resting fingers: two thumbs held two keys apart walk the reported
 * point from the first thumb, to the midpoint, to the second thumb — a total displacement of
 * exactly the key spacing (≈82dp at this device's ~41dp pitch), gliding there in small steps
 * that no per-sample filter can distinguish from a slow real swipe. A threshold above that
 * hard physical ceiling (96dp > 82dp with margin) makes the resting-two-finger case
 * mathematically unable to fire; a deliberate swipe simply travels farther before the first
 * caret step, then continuous tracking ([SWIPE_CURSOR_STEP_SLOP_DP]) takes over as before. This
 * is the DRAG-ENTRY threshold: clearing it fires the first caret step and opens continuous
 * tracking, so the longer travel stays the mode's mis-touch guard without making every step
 * feel heavy. Vertical caret moves (off by default) share the threshold. Kept generously above
 * the centroid ceiling (160dp ≈ four key pitches, nearly half the surface width) — cursor-by-
 * swipe is a rare gesture, so it leans hard toward never firing by accident over firing easily.
 */
const val SWIPE_CURSOR_SLOP_DP = 160f

/**
 * Hard floor for the cursor-move threshold, in raw dp — applied AFTER the fly-text sensitivity
 * scaling (which shrinks every slop at higher sensitivity). The resting-two-finger centroid
 * ceiling (~82dp = two key pitches) is a PHYSICAL constant of the hardware; it does not shrink
 * when the user raises gesture sensitivity. Without this floor, the sensitivity slider would
 * scale [SWIPE_CURSOR_SLOP_DP] down toward the ceiling and the mis-touch would come straight
 * back. The floor sits comfortably above the ceiling (108dp > ~82dp), so the slider keeps
 * shaving the threshold only within the safe band (160dp → 144dp), never below it.
 */
const val SWIPE_CURSOR_SLOP_FLOOR_DP = 144f

/**
 * Cursor DRAG step: after the entry swipe ([SWIPE_CURSOR_SLOP_DP]) opened continuous tracking, the
 * caret advances again for every [SWIPE_CURSOR_STEP_SLOP_DP] of travel from the last fire point —
 * trackpad semantics, the caret follows the finger in real time until UP/CANCEL. Much smaller than
 * the entry slop on purpose; each step re-origins at the fire point, and a direction change
 * mid-drag takes effect on the next step (no direction lock in drag).
 */
const val SWIPE_CURSOR_STEP_SLOP_DP = 24f

/**
 * Jump filter: a single MOVE sample that travels farther than this (dp) between consecutive
 * samples is a contact SWITCH, not finger motion — the touch driver's reported contact jumped
 * between two physically distinct fingers (two thumbs typing on opposite sides of the surface,
 * reported by single-pointer drivers as one teleporting stream), or the stream glitched. A real
 * swipe samples at 60–125Hz and moves a few px per sample; a thumb handoff moves hundreds of
 * px in one step. The selector resets the gesture on such a jump instead of letting the fake
 * displacement fire a page/cursor/delete action.
 *
 * Calibration (measured on the 1080px/2.625-density surface, ~41dp key pitch): a two-key thumb
 * handoff jumps ≈82dp and a three-key one ≈123dp — 90dp let the two-key case through. 60dp sits
 * below the two-key jump with margin while staying ~2× above the fastest per-sample travel a
 * real flick produces (observed strokes run 40–90 samples for a few hundred px, i.e. single-digit
 * dp per step), and still above every commit slop including [SWIPE_CURSOR_SLOP_DP].
 */
const val SWIPE_TELEPORT_SLOP_DP = 60f

/**
 * Re-anchor threshold (cursor mode only): a per-sample step of 16–60dp is treated as the
 * driver's centroid gliding between two resting contacts, and the gesture's origin moves to the
 * current sample so the fake displacement cannot accumulate. 24dp rather than the old 16dp
 * ([SWIPE_BASE_SLOP_DP]): a brisk real swipe whose samples got coalesced by the driver can
 * legitimately cover 16-20dp in one step, and re-anchoring those ate the up-swipe pick's
 * accumulated travel (the "up-swipe became insensitive" regression) — 24dp keeps a safety
 * margin above that while still sitting far below the ~41dp half-pitch of a two-key centroid
 * hop, which is the signal this filter exists to catch.
 */
const val SWIPE_REANCHOR_SLOP_DP = 24f

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
 * Corner-delete zone: fraction of the keyboard surface's WIDTH (measured inward from the right edge)
 * and HEIGHT (measured down from the top edge) that counts as the "top-right corner". A contact must
 * START inside this zone for a left swipe to be read as Backspace.
 *
 * The zone itself is the gesture's PRIMARY mis-touch filter — a corner, not a band: a stray left
 * swipe across the middle of the keyboard still pages candidates, and only a flick that begins up in
 * the right portion of the surface deletes.
 *
 * Height is deliberately generous: on the Titan2 Elite the keyboard touch surface spans roughly the
 * upper two-thirds of the display, and a natural "flick from the top-right" actually STARTS well
 * below the literal top edge (the real swipes land at ~y=240–336 of a ~747px-tall surface). A 30%
 * cap (≈224px) sat entirely above where fingers naturally begin, so the gesture never armed. 50%
 * (≈373px) comfortably covers the observed start band while staying clear of the lower half, where a
 * left swipe should still page. Width keeps the old 30% so the zone stays on the right third only.
 */
const val SWIPE_CORNER_DELETE_WIDTH_FRAC = 0.30f
const val SWIPE_CORNER_DELETE_HEIGHT_FRAC = 0.50f

/**
 * Top-right corner of [surface] as a display-space [Rect]: x from the right edge inward by
 * [SWIPE_CORNER_DELETE_WIDTH_FRAC] of the width, y from the top edge down by
 * [SWIPE_CORNER_DELETE_HEIGHT_FRAC] of the height. [surface] is the keyboard touch surface's full
 * declared motion bounds ([DeviceInfo.keyboardSurfaceRect]), already in the same display coordinate
 * space as the touch's `rawX`/`rawY`, so the selector's hit test is a direct `contains()`.
 */
fun cornerDeleteRegion(surface: Rect): Rect = Rect(
    (surface.right - surface.width() * SWIPE_CORNER_DELETE_WIDTH_FRAC).toInt(),
    surface.top,
    surface.right,
    (surface.top + surface.height() * SWIPE_CORNER_DELETE_HEIGHT_FRAC).toInt()
)

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
