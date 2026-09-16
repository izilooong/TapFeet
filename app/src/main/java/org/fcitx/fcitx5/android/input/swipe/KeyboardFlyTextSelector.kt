/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input.swipe

import android.graphics.Rect
import android.view.MotionEvent
import timber.log.Timber

/**
 * Gesture state machine for the "keyboard fly-text" feature: physical-keyboard mode only, driven by
 * touches the IME captures over the keyboard surface (see [org.fcitx.fcitx5.android.input.KeyboardSurfaceProbeWindow]).
 *
 * Two gestures:
 *  - **Up-swipe** (vertical-dominant, upward): pick the candidate whose on-screen column the finger
 *    is over — resolved by [candidateIndexAtX] against live [candidateRectsProvider] rects.
 *  - **Left / right swipe** (horizontal-dominant): page candidates. Left = next page, right = previous.
 *
 * Coordinates: candidate rects are absolute screen coordinates ([android.view.View.getLocationOnScreen]),
 * so the incoming [MotionEvent] must be tested against [MotionEvent.getRawX] / [MotionEvent.getRawY]
 * (NOT [MotionEvent.getX], which is relative to the capture window). The provider is queried at
 * gesture-classify time so rects are always fresh, not a stale snapshot.
 *
 * Robustness: some touch sources stream MOVE but never UP, which would otherwise leave the state
 * machine latched and silently swallow the next gesture. The in-flight gesture therefore self-expires
 * after [EXPIRE_MS] of silence, and any fresh DOWN hard-resets — so a missing UP can never permanently
 * lock candidate selection.
 *
 * Pure function + callbacks: this class holds no Android context beyond what the caller injects, and
 * never forwards touches anywhere (the capture window stays the only touch sink).
 */
class KeyboardFlyTextSelector(
    private val density: Float,
    private val candidateRectsProvider: () -> List<Pair<Int, Rect>>,
    private val onSelect: (Int) -> Unit,
    private val onPage: (Int) -> Unit
) {
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    /** Once true the gesture is classified/consumed; further MOVEs are ignored until reset. */
    private var classified = false
    private var lastEventTime = 0L

    private val slopPx: Float get() = SWIPE_SLOP_DP * density

    /**
     * True while a finger is down on the keyboard surface (between DOWN and UP/CANCEL).
     *
     * The service uses this to hold the capture window open past its expiry deadline: dropping the
     * claiming window mid-gesture would hand the remaining MOVEs to the app and silently kill a
     * slow swipe. A gesture that was already classified (or was reset) counts as NOT active.
     */
    val gestureActive: Boolean get() = downTime != 0L

    /**
     * If a touch source only streams MOVE (no UP), the latched [classified] flag would otherwise
     * survive forever. Expire it after this quiet window so a fresh gesture starts clean.
     */
    private val EXPIRE_MS = 900L

    fun onTouchEvent(event: MotionEvent) {
        val t = event.eventTime
        // A long silence between events means the previous gesture was abandoned (no UP seen).
        if (classified && (t - lastEventTime) > EXPIRE_MS) reset()
        lastEventTime = t

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                downX = event.rawX
                downY = event.rawY
                downTime = t
                Timber.i("FlyText: DOWN rawX=${event.rawX} rawY=${event.rawY}")
            }
            MotionEvent.ACTION_MOVE -> {
                if (classified || downTime == 0L) return
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                // The verdict — and the slop gate — come from the shared classifier, so the Lab page's
                // gesture readout reports exactly what happens here (see [swipeDirection]).
                when (swipeDirection(dx, dy, slopPx)) {
                    null -> return // not far enough yet
                    SwipeDirection.UP -> {
                        val rects = candidateRectsProvider()
                        val idx = candidateIndexAtX(event.rawX, rects)
                        Timber.i("FlyText: up-swipe over ${rects.size} rects → idx=$idx (rawX=${event.rawX})")
                        if (idx >= 0) {
                            onSelect(idx)
                        }
                    }
                    // Only an UP-swipe selects a candidate; a down-swipe is a no-op (no "scroll
                    // candidates" gesture — keep the model simple and safe).
                    SwipeDirection.DOWN -> Timber.i("FlyText: down-swipe (ignored)")
                    // Left swipe (dx < 0) = next page, right = previous (the swap pref is applied by
                    // the service).
                    SwipeDirection.LEFT -> {
                        Timber.i("FlyText: page dir=1 (rawX=${event.rawX})")
                        onPage(1)
                    }
                    SwipeDirection.RIGHT -> {
                        Timber.i("FlyText: page dir=-1 (rawX=${event.rawX})")
                        onPage(-1)
                    }
                }
                classified = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    /** Clear in-flight gesture state. Safe to call any time (e.g. on input finish). */
    fun reset() {
        downX = 0f
        downY = 0f
        downTime = 0L
        classified = false
    }
}
