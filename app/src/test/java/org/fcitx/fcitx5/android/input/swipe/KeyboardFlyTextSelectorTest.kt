/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the fly-text gesture state machine, driven through its numeric
 * [KeyboardFlyTextSelector.onGestureSample] entry instead of `android.view.MotionEvent`.
 *
 * These cover the sequences that actually broke on device: a missing UP latching the selector,
 * two-thumb graze, typing guard, and continuous cursor drag. The geometry helpers were testable
 * before; the state machine was not.
 */
class KeyboardFlyTextSelectorTest {

    // MotionEvent.ACTION_* values, inlined so the JVM tests never touch the Android stub jar.
    private companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }

    private fun selector(
        onPage: (Int) -> Unit = {},
        onCursor: (SwipeDirection, Boolean) -> Unit = { _, _ -> },
        typingGuard: () -> Boolean = { false },
        cursorMode: () -> Boolean = { false },
    ) = KeyboardFlyTextSelector(
        density = 1f,
        candidateRectsProvider = { emptyList() },
        onSelect = {},
        onPage = onPage,
        // Never instantiate android.graphics.Rect here: the mockable android jar throws "Stub!".
        cornerRegionProvider = { null },
        cornerDeleteEnabled = { false },
        onDelete = {},
        typingGuard = typingGuard,
        sensitivityProvider = { 100 },
        cursorModeProvider = cursorMode,
        onCursor = onCursor,
    )

    private fun down(s: KeyboardFlyTextSelector, t: Long = 1000L, x: Float = 500f, y: Float = 500f) =
        s.onGestureSample(ACTION_DOWN, x, y, t, 1)

    private fun move(s: KeyboardFlyTextSelector, x: Float, t: Long, y: Float = 500f) =
        s.onGestureSample(ACTION_MOVE, x, y, t, 1)

    private fun up(s: KeyboardFlyTextSelector, t: Long, x: Float = 500f) =
        s.onGestureSample(ACTION_UP, x, 500f, t, 1)

    @Test
    fun leftSwipePagesExactlyOnceUntilUp() {
        val pages = mutableListOf<Int>()
        val s = selector(onPage = { pages.add(it) })
        down(s)
        move(s, 400f, 1010L) // dx = -100 > SWIPE_PAGE_SLOP_DP (32) at density 1 / 100%
        assertEquals(listOf(1), pages)
        move(s, 300f, 1020L) // classified: further samples ignored
        assertEquals(listOf(1), pages)
        up(s, 1030L, 300f)
        assertFalse(s.gestureActive)
    }

    @Test
    fun rightSwipePagesBackwards() {
        val pages = mutableListOf<Int>()
        val s = selector(onPage = { pages.add(it) })
        down(s)
        move(s, 600f, 1010L)
        assertEquals(listOf(-1), pages)
    }

    @Test
    fun missingUpExpiresTheLatchedGesture() {
        val pages = mutableListOf<Int>()
        val s = selector(onPage = { pages.add(it) })
        down(s)
        move(s, 400f, 1010L)
        assertEquals(listOf(1), pages)
        // 1000ms of silence with no UP: the latched classifier must self-expire.
        move(s, 400f, 2010L)
        down(s, 2020L)
        move(s, 400f, 2030L)
        assertEquals(listOf(1, 1), pages)
    }

    @Test
    fun twoFingerGuardIgnoresTheWholeGesture() {
        val pages = mutableListOf<Int>()
        val s = selector(onPage = { pages.add(it) })
        s.onGestureSample(ACTION_DOWN, 500f, 500f, 1000L, 2)
        assertFalse(s.gestureActive)
        move(s, 400f, 1010L)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun typingGuardSuppressesTheContact() {
        val pages = mutableListOf<Int>()
        val s = selector(onPage = { pages.add(it) }, typingGuard = { true })
        down(s)
        assertFalse(s.gestureActive)
        move(s, 400f, 1010L)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun cursorDragFiresAnEntryThenContinuousSteps() {
        val events = mutableListOf<Pair<SwipeDirection, Boolean>>()
        val s = selector(cursorMode = { true }, onCursor = { dir, drag -> events += dir to drag })
        down(s)
        // 20px steps stay under SWIPE_REANCHOR_SLOP_DP (24) / SWIPE_TELEPORT_SLOP_DP (60),
        // but accumulate past the 160px cursor commit slop.
        var x = 500f
        for (i in 1..10) {
            x -= 20f
            move(s, x, 1000L + i * 10L)
        }
        assertTrue("entry fire", events.any { !it.second })
        assertTrue("drag step", events.any { it.second })
        assertTrue("direction", events.all { it.first == SwipeDirection.LEFT })
    }
}
