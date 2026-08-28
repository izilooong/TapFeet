/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.effects

import android.os.SystemClock

/**
 * Tracks typing combo and speed, with no Android dependency beyond the clock.
 *
 * Combo breaks after [timeoutMs] of silence. Speed is chars-per-minute over a [windowMs]
 * sliding window, with the span floored at [MIN_SPAN_MS] so the first few chars don't
 * produce an absurd reading.
 */
class ComboTracker(
    private val timeoutMs: Long = 1500L,
    private val windowMs: Long = 10_000L
) {

    companion object {
        private const val CAPACITY = 256
        private const val MIN_SPAN_MS = 2_000L
    }

    var combo: Int = 0
        private set

    var cpm: Int = 0
        private set

    /** 0..3, drives particle count and colour temperature. */
    val tier: Int
        get() = when {
            cpm < 60 -> 0
            cpm < 120 -> 1
            cpm < 200 -> 2
            else -> 3
        }

    private val times = LongArray(CAPACITY)
    private val lengths = IntArray(CAPACITY)
    private var head = 0
    private var size = 0
    private var lastCommitMs = 0L

    fun onCommit(text: String, now: Long = SystemClock.uptimeMillis()) {
        if (lastCommitMs != 0L && now - lastCommitMs > timeoutMs) reset()
        lastCommitMs = now
        combo += 1
        val chars = countChars(text)
        val tail = (head + size) % CAPACITY
        times[tail] = now
        lengths[tail] = chars
        if (size < CAPACITY) size++ else head = (head + 1) % CAPACITY
        recompute(now)
    }

    /** Call once per frame so combo expires and the speed window slides even without input. */
    fun tick(now: Long = SystemClock.uptimeMillis()) {
        if (combo > 0 && lastCommitMs != 0L && now - lastCommitMs > timeoutMs) {
            reset()
            return
        }
        if (size > 0) recompute(now)
    }

    fun reset() {
        combo = 0
        cpm = 0
        head = 0
        size = 0
        lastCommitMs = 0L
    }

    private fun recompute(now: Long) {
        val cutoff = now - windowMs
        while (size > 0 && times[head] < cutoff) {
            head = (head + 1) % CAPACITY
            size--
        }
        if (size == 0) {
            cpm = 0
            return
        }
        var chars = 0
        for (i in 0 until size) chars += lengths[(head + i) % CAPACITY]
        // Floor the span so a burst right after a reset reads fast, not infinite.
        val span = (now - times[head]).coerceAtLeast(MIN_SPAN_MS)
        cpm = (chars * 60_000L / span).toInt()
    }

    private fun countChars(text: String): Int {
        var n = 0
        for (c in text) if (!c.isWhitespace()) n++
        return if (n == 0) 1 else n
    }
}
